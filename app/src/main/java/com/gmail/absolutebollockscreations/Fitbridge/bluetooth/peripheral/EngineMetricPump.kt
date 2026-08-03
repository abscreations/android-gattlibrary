package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import android.os.SystemClock
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtEngineState
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtServiceProfiles.FtmsMachineProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
) {

    private var job: Job? = null

    fun start(scope: CoroutineScope, periodMs: Long = DEFAULT_SAMPLE_PERIOD_MS) {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                sampleOnce()
                delay(periodMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Visible for the controller so a status read can force a fresh sample. */
    fun sampleOnce() {
        store.update(snapshot(), profileProvider())
    }

    private fun snapshot(): FtmsMetricSnapshot {
        val profile = profileProvider()

        // The engine publishes speed in whichever unit the machine reported.
        // Prefer km/h and fall back to converting mph rather than dropping speed,
        // which is mandatory for the speed-based personas.
        val speedKph = engineState.speedKph.value
            ?: engineState.speedMph.value?.let { it * MILES_TO_KM }

        // Rower pace: the engine carries pace as minutes per km / per unit, while
        // FTMS Rower Data wants seconds per 500 m. Convert only for the rower,
        // where the wire field actually exists.
        val pace500 = if (profile == FtmsMachineProfile.INDOOR_ROWER) {
            engineState.instPace.value?.let { minutesPerKmToSecondsPer500m(it) }
        } else {
            null
        }
        val avgPace500 = if (profile == FtmsMachineProfile.INDOOR_ROWER) {
            engineState.avgPace.value?.let { minutesPerKmToSecondsPer500m(it) }
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
            // The engine does not decode the per-hour / per-minute energy rates,
            // so they stay absent rather than being sent as zero. The encoder
            // writes the FTMS "not available" sentinels for them.
            caloriesPerHour = null,
            caloriesPerMinute = null,
            resistance = engineState.resistance.value?.roundToInt(),
            inclinationPercent = engineState.inclination.value,
            positiveElevationGainMeters = engineState.elevationGainPos.value,
            negativeElevationGainMeters = engineState.elevationGainNeg.value,
            floors = engineState.totalFloors.value,
            strokeCount = strokeCount,
            updatedAtMs = SystemClock.elapsedRealtime(),
        )
    }

    private fun minutesPerKmToSecondsPer500m(minutesPerKm: Double): Double? =
        if (minutesPerKm.isFinite() && minutesPerKm > 0.0) minutesPerKm * 60.0 / 2.0 else null

    companion object {
        /**
         * Twice the notifier's default cadence, so the notifier always has a
         * reasonably fresh sample without the store doing pointless work.
         */
        const val DEFAULT_SAMPLE_PERIOD_MS = 350L
        private const val MILES_TO_KM = 1.609344
    }
}
