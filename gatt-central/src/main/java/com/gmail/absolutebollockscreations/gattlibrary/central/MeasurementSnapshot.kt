package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * One coherent read of the connected machine's fused metrics, in canonical units (L24, unit 4a).
 *
 * A null field means the metric is absent: no source has supplied it, or the source went away. It is
 * never zero in disguise. Speed is km/h only; display units are a presentation concern for the caller.
 * Current, average and cumulative values are separate fields rather than one overloaded number.
 *
 * Deliberately not included yet: per-value source identity, quality and arrival time (added later as
 * an additive slice when a caller needs them), Metabolic Equivalent and Remaining Time (deferred until
 * central's decoding collects them), and chart history (its own projection).
 */
public data class MeasurementSnapshot(
    val speedKph: Double? = null,
    val averageSpeedKph: Double? = null,
    /** Instantaneous pace, minutes per kilometre. */
    val paceMinPerKm: Double? = null,
    val averagePaceMinPerKm: Double? = null,
    /** Cycling or stepping cadence, per minute. Rowers also report [strokeRate]; both are kept, not merged. */
    val cadence: Double? = null,
    val averageCadence: Double? = null,
    /** Strokes per minute. */
    val strokeRate: Double? = null,
    val powerWatts: Int? = null,
    val averagePowerWatts: Int? = null,
    val forceOnBeltNewtons: Int? = null,
    val heartRate: Int? = null,
    /** Heart-rate zone 0..5 from the configured zones, or null when heart rate is unknown. */
    val heartRateZone: Int? = null,
    /** Running average of heart-rate samples since the session began, rounded to whole bpm. */
    val averageHeartRate: Int? = null,
    /** Zone 0..5 of [averageHeartRate], or null when there is no average. */
    val averageHeartRateZone: Int? = null,
    val distanceMeters: Int? = null,
    val elapsedSeconds: Int? = null,
    /** Cumulative energy since the session began. */
    val energyKcal: Int? = null,
    val energyRateKcalPerHour: Int? = null,
    /** Resistance level in the machine's own public units; fractional levels are preserved. */
    val resistance: Double? = null,
    val inclinationPercent: Double? = null,
    /** Cumulative ascent and descent in metres; both non-negative. */
    val ascentMeters: Double? = null,
    val descentMeters: Double? = null,
    val floors: Int? = null,
    val strokeCount: Int? = null,
    val strideCount: Double? = null,
    val stepCount: Int? = null,
    /** Raw selected CSC counters and event times, for re-emission by a bridge. */
    val cscEvents: CycleSensorEvents? = null,
    /** Raw selected CPS counters and event times, for re-emission by a bridge. */
    val cpsEvents: CycleSensorEvents? = null,
    /** Raw treadmill pace octets from the machine, re-emitted verbatim; never synthesised. */
    val treadmillRawPace: TreadmillRawPace? = null,
) {
    public companion object {
        public val EMPTY: MeasurementSnapshot = MeasurementSnapshot()
    }
}

/** Wheel and crank event counters exactly as a CSC or CPS sensor reported them. */
public data class CycleSensorEvents(
    val cumulativeWheelRevolutions: Long? = null,
    val lastWheelEventTimeTicks: Int? = null,
    val cumulativeCrankRevolutions: Int? = null,
    val lastCrankEventTimeTicks: Int? = null,
)

/** Raw `uint8` treadmill pace values (0.1 km/min per step), exactly as the machine sent them. */
public data class TreadmillRawPace(
    val instantaneousRaw: Int? = null,
    val averageRaw: Int? = null,
)

/**
 * Reads the engine's current metric state into a public snapshot. Pure apart from those reads.
 *
 * Marked internal SPI because it takes [BtEngineState], which leaves the public surface once its
 * callers migrate. Production code reads [FitnessGattClient.measurements] instead; this exists for
 * tests that still drive engine state directly, and is deleted with them.
 */
@GattLibraryInternalApi
internal fun BtEngineState.toMeasurementSnapshot(): MeasurementSnapshot = MeasurementSnapshot(
    speedKph = speedKph.value,
    averageSpeedKph = avgSpeedKph.value,
    // The canonical min/km flows, NOT instPace/avgPace: those are display-unit paces (min/mi when the
    // UI is set to imperial) and would make this field depend on a presentation setting.
    paceMinPerKm = instantPaceMinPerKm.value,
    averagePaceMinPerKm = averagePaceMinPerKm.value,
    cadence = instantCadence.value,
    averageCadence = averageCadence.value,
    strokeRate = strokeRate.value,
    powerWatts = instantPower.value,
    averagePowerWatts = averagePower.value,
    forceOnBeltNewtons = forceOnBelt.value,
    heartRate = heartRate.value,
    heartRateZone = heartRateZone.value,
    averageHeartRate = averageHeartRate.value,
    averageHeartRateZone = averageHeartRateZone.value,
    distanceMeters = totalDistanceMeters.value,
    elapsedSeconds = elapsedTimeSec.value,
    energyKcal = expEnergyTotal.value,
    energyRateKcalPerHour = expEnergyRateKcalPerHour.value,
    resistance = resistance.value,
    inclinationPercent = inclination.value,
    ascentMeters = elevationGainPos.value,
    descentMeters = elevationGainNeg.value,
    floors = totalFloors.value,
    strokeCount = strokeCount.value,
    strideCount = totalStrides.value,
    stepCount = totalSteps.value,
    cscEvents = cscNativeEventSnapshot()?.let {
        CycleSensorEvents(
            it.cumulativeWheelRevolutions, it.lastWheelEventTimeTicks,
            it.cumulativeCrankRevolutions, it.lastCrankEventTimeTicks,
        )
    },
    cpsEvents = cpsNativeEventSnapshot()?.let {
        CycleSensorEvents(
            it.cumulativeWheelRevolutions, it.lastWheelEventTimeTicks,
            it.cumulativeCrankRevolutions, it.lastCrankEventTimeTicks,
        )
    },
    treadmillRawPace = treadmillNativePaceSnapshot()?.let { TreadmillRawPace(it.instantaneousRaw, it.averageRaw) },
)

/** Every engine signal the snapshot depends on, as change pings. The native stores are not flows, so each carries a revision. */
internal fun BtEngineState.measurementChanges(): List<Flow<Unit>> {
    fun <T> Flow<T>.ping(): Flow<Unit> = map { }
    return listOf(
        speedKph.ping(), avgSpeedKph.ping(), instantPaceMinPerKm.ping(), averagePaceMinPerKm.ping(),
        instantCadence.ping(), averageCadence.ping(), strokeRate.ping(),
        instantPower.ping(), averagePower.ping(), forceOnBelt.ping(),
        heartRate.ping(), heartRateZone.ping(), averageHeartRate.ping(), averageHeartRateZone.ping(),
        totalDistanceMeters.ping(), elapsedTimeSec.ping(),
        expEnergyTotal.ping(), expEnergyRateKcalPerHour.ping(),
        resistance.ping(), inclination.ping(), elevationGainPos.ping(), elevationGainNeg.ping(),
        totalFloors.ping(), strokeCount.ping(), totalStrides.ping(), totalSteps.ping(),
        cscNativeEvents.revision.ping(), cpsNativeEvents.revision.ping(), treadmillNativePace.revision.ping(),
    )
}
