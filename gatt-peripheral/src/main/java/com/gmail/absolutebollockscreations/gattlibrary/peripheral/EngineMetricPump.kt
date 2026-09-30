package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import android.os.SystemClock
import com.gmail.absolutebollockscreations.gattlibrary.central.BtEngineState
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.gmail.absolutebollockscreations.gattlibrary.central.CscNativeEventSnapshot
import com.gmail.absolutebollockscreations.gattlibrary.central.CpsNativeEventSnapshot
import com.gmail.absolutebollockscreations.gattlibrary.central.TreadmillNativePaceSnapshot
import com.gmail.absolutebollockscreations.gattlibrary.server.CpsNativeEventInput
import com.gmail.absolutebollockscreations.gattlibrary.server.CscNativeEventInput
import com.gmail.absolutebollockscreations.gattlibrary.server.DEFAULT_EMISSION_PERIOD_MS
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsMetricSnapshot
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsMetricStore
import com.gmail.absolutebollockscreations.gattlibrary.server.MetricSampler
import com.gmail.absolutebollockscreations.gattlibrary.server.TreadmillNativePaceInput

/**
 * Samples [BtEngineState] into [FtmsMetricStore].
 *
 * This is the seam that replaces the old notifier's direct reads of
 * `engineState.<flow>.value` from inside the encoders. Two things change:
 *
 *  1. **Absent means absent.** A StateFlow holding null is carried through as a
 *     null snapshot field, so the encoder omits it and clears its flag bit. The
 *     old encoders substituted 0, which told the client the machine really was
 *     reporting zero speed / zero power.
 *  2. **One coherent sample.** Every field is read in one pass, so a record can
 *     never mix a speed from one upstream packet with a cadence from the next.
 *
 * Sampling rather than collecting each flow individually is deliberate: it keeps
 * the snapshot internally consistent and bounds the update rate regardless of how
 * chatty the upstream machine is.
 */
class EngineMetricPump(
    private val engineState: BtEngineState,
    private val store: FtmsMetricStore,
    private val profileProvider: () -> FtmsMachineProfile?,
) : MetricSampler {

    private var job: Job? = null

    override fun start(scope: CoroutineScope): Unit = start(scope, DEFAULT_SAMPLE_PERIOD_MS)

    fun start(scope: CoroutineScope, periodMs: Long) {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                sampleOnce()
                delay(periodMs)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    /** Visible for the controller so a status read can force a fresh sample. */
    override fun sampleOnce() {
        store.update(snapshot(), profileProvider())
    }

    private fun snapshot(): FtmsMetricSnapshot {
        val profile = profileProvider()

        // The engine publishes speed in whichever unit the machine reported.
        // Prefer km/h and fall back to converting mph rather than dropping speed,
        // which is mandatory for the speed-based personas.
        val speedKph = engineState.speedKph.value
            ?: engineState.speedMph.value?.let { it * MILES_TO_KM }

        // FTMS Rower Data carries pace rather than speed. Prefer canonical native
        // speed so a distance-derived pace cannot disagree with the speed shown by
        // the central UI. Display units never participate in this projection.
        val pace500 = if (profile == FtmsMachineProfile.INDOOR_ROWER) {
            if (speedKph != null) {
                speedKphToSecondsPer500m(speedKph)
            } else {
                engineState.instPace.value?.let { minutesPerKmToSecondsPer500m(it) }
            }
        } else {
            null
        }
        val avgPace500 = if (profile == FtmsMachineProfile.INDOOR_ROWER) {
            val averageSpeedKph = engineState.avgSpeedKph.value
            if (averageSpeedKph != null) {
                speedKphToSecondsPer500m(averageSpeedKph)
            } else {
                engineState.avgPace.value?.let { minutesPerKmToSecondsPer500m(it) }
            }
        } else {
            null
        }

        // Cadence is reported under two different names depending on machine
        // family; the rower's is stroke rate.
        val cadence = when (profile) {
            FtmsMachineProfile.INDOOR_ROWER ->
                engineState.strokeRate.value ?: engineState.instantCadence.value

            else -> engineState.instantCadence.value
        }

        // Stroke / stride / step count, per persona. Each characteristic means
        // something different by this field, so it is selected here rather than
        // guessed in the encoder.
        val strokeCount = when (profile) {
            FtmsMachineProfile.INDOOR_ROWER -> engineState.strokeCount.value
            FtmsMachineProfile.ELLIPTICAL,
            FtmsMachineProfile.STAIR_CLIMBER,
            -> engineState.totalStrides.value?.roundToInt()

            FtmsMachineProfile.STAIR_STEPPER -> engineState.totalSteps.value
            else -> null
        }

        return FtmsMetricSnapshot(
            speedKph = speedKph,
            averageSpeedKph = engineState.avgSpeedKph.value,
            paceSecondsPer500m = pace500,
            averagePaceSecondsPer500m = avgPace500,
            cadence = cadence,
            averageCadence = engineState.averageCadence.value,
            powerWatts = engineState.instantPower.value,
            averagePowerWatts = engineState.averagePower.value,
            forceOnBeltNewtons = engineState.forceOnBelt.value,
            heartRate = engineState.heartRate.value,
            distanceMeters = engineState.totalDistanceMeters.value,
            elapsedSeconds = engineState.elapsedTimeSec.value,
            caloriesTotal = engineState.expEnergyTotal.value,
            // Native or explicitly derived energy rate is forwarded when present.
            // Per-minute is the same rate converted to the FTMS integer field.
            caloriesPerHour = engineState.expEnergyRateKcalPerHour.value,
            caloriesPerMinute = engineState.expEnergyRateKcalPerHour.value?.let { it / 60 },
            // Not rounded: 0x2ACE Resistance Level is in tenths, so rounding here
            // discarded the fractional part of every compliant machine's level.
            resistance = engineState.resistance.value?.toFloat(),
            inclinationPercent = engineState.inclination.value,
            positiveElevationGainMeters = engineState.elevationGainPos.value,
            negativeElevationGainMeters = engineState.elevationGainNeg.value,
            floors = engineState.totalFloors.value,
            strokeCount = strokeCount,
            cscNativeEvents = engineState.cscNativeEventSnapshot()?.toInput(),
            cpsNativeEvents = engineState.cpsNativeEventSnapshot()?.toInput(),
            // Treadmill only: no other persona has the field, and it is never synthesised.
            treadmillNativePace = if (profile == FtmsMachineProfile.TREADMILL) {
                engineState.treadmillNativePaceSnapshot()?.toInput()
            } else {
                null
            },
            updatedAtMs = SystemClock.elapsedRealtime(),
        )
    }

    private fun minutesPerKmToSecondsPer500m(minutesPerKm: Double): Double? =
        if (minutesPerKm.isFinite() && minutesPerKm > 0.0) minutesPerKm * 60.0 / 2.0 else null

    private fun speedKphToSecondsPer500m(speedKph: Double): Double? =
        if (speedKph.isFinite() && speedKph > 0.0) 1_800.0 / speedKph else null

    companion object {
        /**
         * Half the notifier's emission floor, so the notifier always has a sample
         * newer than its own cadence.
         *
         * **Derived, not a literal.** The original 350 ms was chosen as twice the
         * notifier's then-700 ms tick. Lowering that tick to a 250 ms floor
         * silently inverted the relationship: the pump became *slower* than the
         * notifier, so consecutive emissions could carry an identical snapshot and
         * every emission carried a sample up to 350 ms old. Deriving the value
         * keeps the intent from drifting the next time either side changes.
         *
         * What this bounds is only the *sampling* delay — the gap between a value
         * landing in [BtEngineState] and the pump copying it into the store. It
         * does not and cannot bound how long that value sat in the StateFlow
         * before the pump ran; see the note on [FtmsMetricSnapshot.updatedAtMs]
         * about what the staleness metric can and cannot see.
         *
         * Sampling faster than the upstream machine notifies produces no fresher
         * data, only repeated reads of an unchanged value. The cost is a handful
         * of StateFlow reads and one allocation per sample, which is negligible;
         * the store's averages and elevation accumulator are trapezoidal and
         * time-weighted, so a shorter period leaves them mathematically unchanged
         * and marginally more accurate.
         */
        const val DEFAULT_SAMPLE_PERIOD_MS = DEFAULT_EMISSION_PERIOD_MS / 2

        private const val MILES_TO_KM = 1.609344
    }
}

private fun CscNativeEventSnapshot.toInput(): CscNativeEventInput = CscNativeEventInput(
    cumulativeWheelRevolutions = cumulativeWheelRevolutions,
    lastWheelEventTimeTicks = lastWheelEventTimeTicks,
    cumulativeCrankRevolutions = cumulativeCrankRevolutions,
    lastCrankEventTimeTicks = lastCrankEventTimeTicks,
)

private fun CpsNativeEventSnapshot.toInput(): CpsNativeEventInput = CpsNativeEventInput(
    cumulativeWheelRevolutions = cumulativeWheelRevolutions,
    lastWheelEventTimeTicks = lastWheelEventTimeTicks,
    cumulativeCrankRevolutions = cumulativeCrankRevolutions,
    lastCrankEventTimeTicks = lastCrankEventTimeTicks,
)

private fun TreadmillNativePaceSnapshot.toInput(): TreadmillNativePaceInput = TreadmillNativePaceInput(
    instantaneousRaw = instantaneousRaw,
    averageRaw = averageRaw,
)
