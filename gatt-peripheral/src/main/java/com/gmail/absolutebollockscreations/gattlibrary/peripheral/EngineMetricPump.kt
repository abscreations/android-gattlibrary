package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import android.os.SystemClock
import com.gmail.absolutebollockscreations.gattlibrary.central.BtEngineState
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.gmail.absolutebollockscreations.gattlibrary.central.CscNativeEventSnapshot
import com.gmail.absolutebollockscreations.gattlibrary.central.CpsNativeEventSnapshot
import com.gmail.absolutebollockscreations.gattlibrary.central.TreadmillNativePaceSnapshot
import com.gmail.absolutebollockscreations.gattlibrary.server.CpsNativeEventInput
import com.gmail.absolutebollockscreations.gattlibrary.server.CscNativeEventInput
import com.gmail.absolutebollockscreations.gattlibrary.server.DEFAULT_METRIC_SAMPLE_PERIOD_MS
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsMetricSnapshot
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsMetricStore
import com.gmail.absolutebollockscreations.gattlibrary.server.MetricSampler
import com.gmail.absolutebollockscreations.gattlibrary.server.TreadmillNativePaceInput
import com.gmail.absolutebollockscreations.gattlibrary.server.UpstreamReadings

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

    private fun snapshot(): FtmsMetricSnapshot = UpstreamReadings(
        speedKph = engineState.speedKph.value,
        speedMph = engineState.speedMph.value,
        averageSpeedKph = engineState.avgSpeedKph.value,
        instantPaceMinPerKm = engineState.instPace.value,
        averagePaceMinPerKm = engineState.avgPace.value,
        strokeRate = engineState.strokeRate.value,
        instantCadence = engineState.instantCadence.value,
        averageCadence = engineState.averageCadence.value,
        instantPower = engineState.instantPower.value,
        averagePower = engineState.averagePower.value,
        forceOnBeltNewtons = engineState.forceOnBelt.value,
        heartRate = engineState.heartRate.value,
        totalDistanceMeters = engineState.totalDistanceMeters.value,
        elapsedSeconds = engineState.elapsedTimeSec.value,
        caloriesTotal = engineState.expEnergyTotal.value,
        caloriesPerHour = engineState.expEnergyRateKcalPerHour.value,
        resistance = engineState.resistance.value,
        inclinationPercent = engineState.inclination.value,
        elevationGainPositiveMeters = engineState.elevationGainPos.value,
        elevationGainNegativeMeters = engineState.elevationGainNeg.value,
        totalFloors = engineState.totalFloors.value,
        strokeCount = engineState.strokeCount.value,
        totalStrides = engineState.totalStrides.value,
        totalSteps = engineState.totalSteps.value,
        cscNativeEvents = engineState.cscNativeEventSnapshot()?.toInput(),
        cpsNativeEvents = engineState.cpsNativeEventSnapshot()?.toInput(),
        treadmillNativePace = engineState.treadmillNativePaceSnapshot()?.toInput(),
    ).toSnapshot(profileProvider(), SystemClock.elapsedRealtime())

    companion object {
        /** Shared with every other sampler; the derivation and its rationale live on [DEFAULT_METRIC_SAMPLE_PERIOD_MS]. */
        const val DEFAULT_SAMPLE_PERIOD_MS = DEFAULT_METRIC_SAMPLE_PERIOD_MS
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
