package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsDataEncoders
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsMetricSnapshot
import com.gmail.absolutebollockscreations.gattlibrary.server.TargetCapabilities
import kotlin.math.roundToInt

internal val SoleSimulatorProfile.machine: FtmsMachineProfile
    get() = when (this) {
        SoleSimulatorProfile.LCB, SoleSimulatorProfile.R92 -> FtmsMachineProfile.INDOOR_BIKE
        else -> FtmsMachineProfile.TREADMILL
    }

internal val SoleSimulatorProfile.testControlTargets: TargetCapabilities
    get() = when (this) {
        SoleSimulatorProfile.ST90, SoleSimulatorProfile.F80_MODERN, SoleSimulatorProfile.F85_59 ->
            TargetCapabilities(
                speed = true,
                speedRange = FtmsMockControlProfile.speedRange,
                inclination = true,
                inclinationRange = FtmsMockControlProfile.inclinationRange,
            )
        else -> TargetCapabilities.NONE
    }

internal val SoleSimulatorProfile.usesProprietaryWorkout: Boolean
    get() = when (this) {
        SoleSimulatorProfile.ST90, SoleSimulatorProfile.F80_MODERN, SoleSimulatorProfile.F65 -> false
        else -> true
    }

/**
 * F65 has no captured vendor transcript. Its UART stream runs through [SoleF65VendorResponder]
 * rather than replay, so it is separate from [usesProprietaryWorkout].
 */
internal val SoleSimulatorProfile.usesSyntheticVendorSession: Boolean
    get() = this == SoleSimulatorProfile.F65

internal val SoleSimulatorProfile.sendsVendorWorkout: Boolean
    get() = usesProprietaryWorkout || usesSyntheticVendorSession

internal val SoleSimulatorProfile.ftmsWorkoutKey: SoleAttribute
    get() = SoleAttribute(SoleCapture.treadmill.service, FtmsUuids.dataUuidForProfile(machine))

internal fun SoleSimulatorProfile.workoutNotificationKeys(
    subscriptions: Map<SoleAttribute, Int>,
    vendorStarted: Boolean,
): List<SoleAttribute> = buildList {
    val ftms = ftmsWorkoutKey
    if (subscriptions[ftms] == 1) add(ftms)
    if (sendsVendorWorkout && vendorStarted && subscriptions[SoleCapture.vendorNotify] == 1) {
        add(SoleCapture.vendorNotify)
    }
}

internal fun soleFtmsWorkoutFrames(
    profile: SoleSimulatorProfile,
    sample: FtmsMockSample,
    maxPayload: Int,
): List<ByteArray> {
    val speed = sample.speedKph ?: return emptyList()
    if (profile == SoleSimulatorProfile.F65) {
        val rawSpeed = (speed * 100).roundToInt().coerceIn(0, 0xFFFF)
        // The captured F65 flags claim HR, but the first packet only has room for speed.
        // The second packet is the observed zero-HR record, with its padding.
        return listOf(byteArrayOf(0, 1, rawSpeed.toByte(), (rawSpeed shr 8).toByte()), byteArrayOf(1, 1, 0, 0))
    }
    return FtmsDataEncoders.encode(
        profile = profile.machine,
        snapshot = FtmsMetricSnapshot(
            averageSpeedKph = sample.averageSpeedKph,
            distanceMeters = sample.distanceMeters,
            inclinationPercent = sample.inclinationPercent,
            positiveElevationGainMeters = sample.positiveElevationMeters,
            caloriesTotal = sample.caloriesTotal,
            caloriesPerHour = sample.caloriesPerHour,
            caloriesPerMinute = sample.caloriesPerMinute,
            heartRate = sample.heartRate.takeIf { it > 0 },
            elapsedSeconds = sample.elapsedSeconds,
            forceOnBeltNewtons = sample.forceOnBeltNewtons,
            cadence = sample.cadence,
            averageCadence = sample.averageCadence,
            resistance = sample.resistance?.toFloat(),
            powerWatts = sample.powerWatts,
            averagePowerWatts = sample.averagePowerWatts.takeIf { profile.machine == FtmsMachineProfile.INDOOR_BIKE },
        ),
        speedKph = speed,
        maxPayload = maxPayload,
    )
}

internal fun soleVendorWorkoutFrames(
    profile: SoleSimulatorProfile,
    sample: FtmsMockSample,
    maxPayload: Int,
): List<ByteArray> {
    val speed = sample.speedKph ?: return emptyList()
    return when {
        profile.machine == FtmsMachineProfile.TREADMILL -> listOf(
            workoutFrame(18).apply {
                this[9] = sample.heartRate.coerceIn(0, 255).toByte()
                this[10] = (speed / KM_PER_MILE * 10).roundToInt().coerceIn(0, 255).toByte()
                // Whole-percent, unsigned incline. F65's FTMS short packets have no incline field,
                // so this is its only incline channel.
                this[11] = (sample.inclinationPercent ?: 0.0).roundToInt().coerceIn(0, 255).toByte()
                // The captured F65 workout frame carries 01 at byte 12; its meaning is unknown.
                if (profile == SoleSimulatorProfile.F65) this[12] = 1
                this[16] = if (profile == SoleSimulatorProfile.F63) 0xff.toByte() else 1
            },
        )
        else -> {
            val resistance = (sample.resistance ?: 0).coerceIn(0, 255).toByte()
            val level = byteArrayOf(0x5b, 0x02, 0x13, resistance, 0x5d)
            val workout = workoutFrame(22).apply {
                putU16Be(3, sample.elapsedSeconds)
                putU16Be(7, ((sample.distanceMeters ?: 0) / 1_609.344 * 100).roundToInt())
                this[10] = (sample.cadence ?: 0.0).roundToInt().coerceIn(0, 255).toByte()
                putU16Be(11, (speed / KM_PER_MILE * 100).roundToInt())
                putU16Be(13, sample.powerWatts ?: 0)
            }
            if (profile == SoleSimulatorProfile.R92 || maxPayload < workout.size) {
                listOf(level, workout.copyOfRange(0, 20), workout.copyOfRange(20, 22))
            } else {
                listOf(level, workout)
            }
        }
    }
}

private const val KM_PER_MILE = 1.609344

private fun workoutFrame(size: Int): ByteArray = ByteArray(size).apply {
    this[0] = 0x5b
    this[1] = (size - 3).toByte()
    this[2] = 0x06
    this[lastIndex] = 0x5d
}

private fun ByteArray.putU16Be(offset: Int, value: Int) {
    val raw = value.coerceIn(0, 0xffff)
    this[offset] = (raw shr 8).toByte()
    this[offset + 1] = raw.toByte()
}
