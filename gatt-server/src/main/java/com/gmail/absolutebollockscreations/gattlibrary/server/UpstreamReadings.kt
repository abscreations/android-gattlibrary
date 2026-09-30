package com.gmail.absolutebollockscreations.gattlibrary.server

import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import kotlin.math.roundToInt

/**
 * Half the notifier's emission floor, so the notifier always has a sample newer than its own cadence.
 *
 * **Derived, not a literal.** The original 350 ms was chosen as twice the notifier's then-700 ms tick.
 * Lowering that tick to a 250 ms floor silently inverted the relationship: the pump became *slower*
 * than the notifier, so consecutive emissions could carry an identical snapshot and every emission
 * carried a sample up to 350 ms old. Deriving the value keeps the intent from drifting the next time
 * either side changes. Every [MetricSampler] (the central pump, the in-memory upstream) uses it.
 *
 * What this bounds is only the *sampling* delay - the gap between a value landing in the upstream
 * state and the sampler copying it into the store. Sampling faster than the upstream machine notifies
 * produces no fresher data, only repeated reads of an unchanged value; the store's averages and
 * elevation accumulator are trapezoidal and time-weighted, so a shorter period leaves them
 * mathematically unchanged.
 */
const val DEFAULT_METRIC_SAMPLE_PERIOD_MS: Long = DEFAULT_EMISSION_PERIOD_MS / 2

/**
 * One coherent read of an upstream machine's metrics, in the units the FitBridge engine holds them,
 * before they are projected to FTMS-native units (LIB-28).
 *
 * This is the single home of that projection. The central-backed pump and the in-memory upstream
 * both build one of these and call [toSnapshot], so a unit conversion fixed in one place cannot be
 * left broken in the other. A null field means the source never supplied the metric; it is carried
 * through as a null snapshot field, never as zero.
 */
data class UpstreamReadings(
    val speedKph: Double? = null,
    /** Fallback when [speedKph] is absent: some machines report mph only. */
    val speedMph: Double? = null,
    val averageSpeedKph: Double? = null,
    /** Minutes per km, as the engine holds pace. */
    val instantPaceMinPerKm: Double? = null,
    val averagePaceMinPerKm: Double? = null,
    val strokeRate: Double? = null,
    val instantCadence: Double? = null,
    val averageCadence: Double? = null,
    val instantPower: Int? = null,
    val averagePower: Int? = null,
    val forceOnBeltNewtons: Int? = null,
    val heartRate: Int? = null,
    val totalDistanceMeters: Int? = null,
    val elapsedSeconds: Int? = null,
    val caloriesTotal: Int? = null,
    val caloriesPerHour: Int? = null,
    val resistance: Double? = null,
    val inclinationPercent: Double? = null,
    val elevationGainPositiveMeters: Double? = null,
    val elevationGainNegativeMeters: Double? = null,
    val totalFloors: Int? = null,
    val strokeCount: Int? = null,
    val totalStrides: Double? = null,
    val totalSteps: Int? = null,
    val cscNativeEvents: CscNativeEventInput? = null,
    val cpsNativeEvents: CpsNativeEventInput? = null,
    val treadmillNativePace: TreadmillNativePaceInput? = null,
) {
    /**
     * @param profile the persona being served; it decides pace, cadence and count routing.
     * @param updatedAtMs `SystemClock.elapsedRealtime()` at sampling time (see
     *   [FtmsMetricSnapshot.updatedAtMs] for what that does and does not measure).
     */
    fun toSnapshot(profile: FtmsMachineProfile?, updatedAtMs: Long): FtmsMetricSnapshot {
        // The engine publishes speed in whichever unit the machine reported.
        // Prefer km/h and fall back to converting mph rather than dropping speed,
        // which is mandatory for the speed-based personas.
        val speedKph = speedKph ?: speedMph?.let { it * MILES_TO_KM }

        // FTMS Rower Data carries pace rather than speed. Prefer canonical native
        // speed so a distance-derived pace cannot disagree with the speed shown by
        // the central UI. Display units never participate in this projection.
        val pace500 = if (profile == FtmsMachineProfile.INDOOR_ROWER) {
            if (speedKph != null) {
                speedKphToSecondsPer500m(speedKph)
            } else {
                instantPaceMinPerKm?.let { minutesPerKmToSecondsPer500m(it) }
            }
        } else {
            null
        }
        val avgPace500 = if (profile == FtmsMachineProfile.INDOOR_ROWER) {
            if (averageSpeedKph != null) {
                speedKphToSecondsPer500m(averageSpeedKph)
            } else {
                averagePaceMinPerKm?.let { minutesPerKmToSecondsPer500m(it) }
            }
        } else {
            null
        }

        // Cadence is reported under two different names depending on machine
        // family; the rower's is stroke rate.
        val cadence = when (profile) {
            FtmsMachineProfile.INDOOR_ROWER -> strokeRate ?: instantCadence
            else -> instantCadence
        }

        // Stroke / stride / step count, per persona. Each characteristic means
        // something different by this field, so it is selected here rather than
        // guessed in the encoder.
        val count = when (profile) {
            FtmsMachineProfile.INDOOR_ROWER -> strokeCount
            FtmsMachineProfile.ELLIPTICAL,
            FtmsMachineProfile.STAIR_CLIMBER,
            -> totalStrides?.roundToInt()

            FtmsMachineProfile.STAIR_STEPPER -> totalSteps
            else -> null
        }

        return FtmsMetricSnapshot(
            speedKph = speedKph,
            averageSpeedKph = averageSpeedKph,
            paceSecondsPer500m = pace500,
            averagePaceSecondsPer500m = avgPace500,
            cadence = cadence,
            averageCadence = averageCadence,
            powerWatts = instantPower,
            averagePowerWatts = averagePower,
            forceOnBeltNewtons = forceOnBeltNewtons,
            heartRate = heartRate,
            distanceMeters = totalDistanceMeters,
            elapsedSeconds = elapsedSeconds,
            caloriesTotal = caloriesTotal,
            // Native or explicitly derived energy rate is forwarded when present.
            // Per-minute is the same rate converted to the FTMS integer field.
            caloriesPerHour = caloriesPerHour,
            caloriesPerMinute = caloriesPerHour?.let { it / 60 },
            // Not rounded: 0x2ACE Resistance Level is in tenths, so rounding here
            // discarded the fractional part of every compliant machine's level.
            resistance = resistance?.toFloat(),
            inclinationPercent = inclinationPercent,
            positiveElevationGainMeters = elevationGainPositiveMeters,
            negativeElevationGainMeters = elevationGainNegativeMeters,
            floors = totalFloors,
            strokeCount = count,
            cscNativeEvents = cscNativeEvents,
            cpsNativeEvents = cpsNativeEvents,
            // Treadmill only: no other persona has the field, and it is never synthesised.
            treadmillNativePace = if (profile == FtmsMachineProfile.TREADMILL) treadmillNativePace else null,
            updatedAtMs = updatedAtMs,
        )
    }

    private fun minutesPerKmToSecondsPer500m(minutesPerKm: Double): Double? =
        if (minutesPerKm.isFinite() && minutesPerKm > 0.0) minutesPerKm * 60.0 / 2.0 else null

    private fun speedKphToSecondsPer500m(speedKph: Double): Double? =
        if (speedKph.isFinite() && speedKph > 0.0) 1_800.0 / speedKph else null

    companion object {
        const val MILES_TO_KM = 1.609344
    }
}
