package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtServiceProfiles
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtServiceProfiles.FtmsMachineProfile
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.KEISER_M_SERIES_CANDIDATE_ID
import java.util.UUID

/** Stable measurement capabilities for one GATT database lifetime. */
data class FtmsMeasurementCapabilities(
    val averageSpeed: Boolean = false,
    val cadence: Boolean = false,
    val totalDistance: Boolean = false,
    val pace: Boolean = false,
    val inclination: Boolean = false,
    val elevationGain: Boolean = false,
    val stepCount: Boolean = false,
    val resistance: Boolean = false,
    val strideCount: Boolean = false,
    val expendedEnergy: Boolean = true,
    val heartRate: Boolean = true,
    val elapsedTime: Boolean = true,
    val power: Boolean = false,
    val forceOnBeltAndPowerOutput: Boolean = false,
) {
    fun toFeatureBits(): Int {
        val f = FtmsSpec.Feature
        var bits = 0
        if (averageSpeed) bits = bits or (1 shl f.AVERAGE_SPEED)
        if (cadence) bits = bits or (1 shl f.CADENCE)
        if (totalDistance) bits = bits or (1 shl f.TOTAL_DISTANCE)
        if (pace) bits = bits or (1 shl f.PACE)
        if (inclination) bits = bits or (1 shl f.INCLINATION)
        if (elevationGain) bits = bits or (1 shl f.ELEVATION_GAIN)
        if (stepCount) bits = bits or (1 shl f.STEP_COUNT)
        if (resistance) bits = bits or (1 shl f.RESISTANCE_LEVEL)
        if (strideCount) bits = bits or (1 shl f.STRIDE_COUNT)
        if (expendedEnergy) bits = bits or (1 shl f.EXPENDED_ENERGY)
        if (heartRate) bits = bits or (1 shl f.HEART_RATE)
        if (elapsedTime) bits = bits or (1 shl f.ELAPSED_TIME)
        if (power) bits = bits or (1 shl f.POWER_MEASUREMENT)
        if (forceOnBeltAndPowerOutput) bits = bits or (1 shl f.FORCE_ON_BELT_AND_POWER)
        return bits
    }
}

/**
 * Per-persona wire facts for the peripheral role.
 *
 * These are extensions on the existing [FtmsMachineProfile] rather than a second
 * enum, so the engine, the ViewModel and [BtServiceProfiles] keep their current
 * API. Naming in this codebase is crossed relative to the spec and deliberately
 * so:
 *
 *  - [FtmsMachineProfile.STAIR_STEPPER] is the spec's *Step Climber*,
 *    characteristic 0x2ACF, machine type bit 2
 *  - [FtmsMachineProfile.STAIR_CLIMBER] is the spec's *Stair Climber*,
 *    characteristic 0x2AD0, machine type bit 3
 *
 * These two are easy to transpose. The mappings below (and the ones already in
 * [BtServiceProfiles]) are the single source of truth; do not re-derive them at
 * call sites.
 */

/** The one machine data characteristic this persona exposes. */
val FtmsMachineProfile.dataCharacteristic: UUID
    get() = BtServiceProfiles.dataUuidForProfile(this)

/** Fitness Machine Type bit for the advertising Service Data structure. */
val FtmsMachineProfile.machineTypeBit: Int
    get() = BtServiceProfiles.fitnessMachineTypeFor(this)

/**
 * Advertised local name.
 *
 * Deliberately short: the name costs (2 + length) octets of the 31-octet legacy
 * advertising budget, and we also carry a 7-octet Service Data structure.
 */
val FtmsMachineProfile.advertisedName: String
    get() = BtServiceProfiles.deviceNameFor(this)

/** Stable wire name used for status reporting and diagnostics. */
val FtmsMachineProfile.wireName: String
    get() = when (this) {
        FtmsMachineProfile.TREADMILL -> "treadmill"
        FtmsMachineProfile.ELLIPTICAL -> "crossTrainer"
        FtmsMachineProfile.STAIR_STEPPER -> "stepClimber"
        FtmsMachineProfile.STAIR_CLIMBER -> "stairClimber"
        FtmsMachineProfile.INDOOR_ROWER -> "rower"
        FtmsMachineProfile.INDOOR_BIKE -> "indoorBike"
    }

/**
 * Fitness Machine Feature bits this persona can actually produce.
 *
 * Derived from what each encoder emits, NOT from machine type. Keep in sync with
 * [FtmsDataEncoders] - a bit claimed here but never sent may be ignored by strict
 * clients, and a field sent without its bit is a protocol violation.
 *
 * This replaces `BtServiceProfiles.fmfFor()`, which claimed Metabolic Equivalent
 * (bit 11) for every persona even though no encoder has ever emitted that field,
 * mapped the rower's stroke rate onto Cadence but the climbers' step rate onto
 * Step Count inconsistently with their encoders, and claimed Pace (bit 5) for the
 * treadmill, which emits neither instantaneous nor average pace.
 */
val FtmsMachineProfile.measurementCapabilities: FtmsMeasurementCapabilities
    get() = when (this) {
        FtmsMachineProfile.TREADMILL -> FtmsMeasurementCapabilities(
            averageSpeed = true,
            totalDistance = true,
            inclination = true,
            elevationGain = true,
            forceOnBeltAndPowerOutput = true,
        )

        FtmsMachineProfile.ELLIPTICAL -> FtmsMeasurementCapabilities(
            averageSpeed = true,
            totalDistance = true,
            inclination = true,
            elevationGain = true,
            stepCount = true,
            strideCount = true,
            resistance = true,
            power = true,
        )

        // 0x2ACF Step Climber Data.
        FtmsMachineProfile.STAIR_STEPPER -> FtmsMeasurementCapabilities(
            elevationGain = true,
            stepCount = true,
        )

        // 0x2AD0 Stair Climber Data.
        FtmsMachineProfile.STAIR_CLIMBER -> FtmsMeasurementCapabilities(
            elevationGain = true,
            stepCount = true,
            strideCount = true,
        )

        FtmsMachineProfile.INDOOR_ROWER -> FtmsMeasurementCapabilities(
            cadence = true,
            totalDistance = true,
            pace = true,
            resistance = true,
            power = true,
        )

        FtmsMachineProfile.INDOOR_BIKE -> FtmsMeasurementCapabilities(
            averageSpeed = true,
            cadence = true,
            totalDistance = true,
            resistance = true,
            power = true,
        )
    }

val FtmsMachineProfile.featureBits: Int
    get() = measurementCapabilities.toFeatureBits()

/**
 * Measurement fields the Chrono can supply to the downstream Indoor Bike encoder.
 *
 * Average values are generated from the native instantaneous samples; energy, heart rate and
 * elapsed time are part of every encoder record through [FtmsMeasurementCapabilities]' defaults.
 * Resistance is deliberately absent: neither the Chrono frame nor either reference project
 * supplies it, so claiming bit 7 would be a source-family capability violation.
 */
internal val precorChronoMeasurementCapabilities = FtmsMeasurementCapabilities(
    averageSpeed = true,
    cadence = true,
    totalDistance = true,
    power = true,
)

/** Measurement-only contract for Keiser M-series manufacturer advertisements (plan §6.7). */
internal val keiserMSeriesMeasurementCapabilities = FtmsMeasurementCapabilities(
    averageSpeed = true,
    cadence = true,
    totalDistance = true,
    resistance = true,
    power = true,
)

/** FE-C Page 19 has no FTMS Force on Belt/Power Output field despite using Treadmill persona. */
internal val feCTreadmillMeasurementCapabilities =
    FtmsMachineProfile.TREADMILL.measurementCapabilities.copy(forceOnBeltAndPowerOutput = false)

/**
 * Select the stable downstream measurement contract from the active machine context.
 *
 * A link exposing both FTMS and Precor remains on the standard FTMS contract because D6 ranks
 * FTMS first. The override is intentionally limited to a Precor-only machine link; simulator
 * bikes and ordinary FTMS bikes keep the established indoor-bike capabilities.
 */
internal fun providerMeasurementCapabilities(
    profile: FtmsMachineProfile,
    upstreamServiceUuids: Set<UUID>,
    advertisementCandidateId: String? = null,
): FtmsMeasurementCapabilities = when {
    profile == FtmsMachineProfile.INDOOR_BIKE &&
        advertisementCandidateId == KEISER_M_SERIES_CANDIDATE_ID ->
        keiserMSeriesMeasurementCapabilities

    profile == FtmsMachineProfile.TREADMILL &&
        BluetoothIds.UUID_FEC_SERVICE in upstreamServiceUuids &&
        BluetoothIds.UUID_FTMS_SERVICE !in upstreamServiceUuids ->
        feCTreadmillMeasurementCapabilities

    profile == FtmsMachineProfile.INDOOR_BIKE &&
        BluetoothIds.UUID_PRECOR_SERVICE in upstreamServiceUuids &&
        BluetoothIds.UUID_FTMS_SERVICE !in upstreamServiceUuids ->
        precorChronoMeasurementCapabilities

    else -> profile.measurementCapabilities
}

/**
 * Build the 3-octet FTMS Service Data payload (Flags + Fitness Machine Type).
 *
 * @param available value of the Fitness Machine Available flag (Table 3.2 bit 0).
 */
fun ftmsServiceData(profile: FtmsMachineProfile, available: Boolean = true): ByteArray =
    BtServiceProfiles.ftmsServiceDataFor(profile, available)

/** Personas whose data characteristic requires Instantaneous Speed. */
fun requiresSpeed(profile: FtmsMachineProfile): Boolean = when (profile) {
    FtmsMachineProfile.INDOOR_BIKE,
    FtmsMachineProfile.TREADMILL,
    FtmsMachineProfile.ELLIPTICAL,
    -> true

    FtmsMachineProfile.INDOOR_ROWER,
    FtmsMachineProfile.STAIR_STEPPER,
    FtmsMachineProfile.STAIR_CLIMBER,
    -> false
}

/** Unused import guard: keeps [BluetoothIds] referenced for readers of this file. */
@Suppress("unused")
private val ftmsServiceUuid: UUID = BluetoothIds.UUID_FTMS_SERVICE
