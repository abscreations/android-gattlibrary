package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import android.os.SystemClock
import com.gmail.absolutebollockscreations.gattlibrary.central.BtEngineState
import com.gmail.absolutebollockscreations.gattlibrary.central.CycleSensorEvents
import com.gmail.absolutebollockscreations.gattlibrary.central.MeasurementSnapshot
import com.gmail.absolutebollockscreations.gattlibrary.central.TreadmillRawPace
import com.gmail.absolutebollockscreations.gattlibrary.central.toMeasurementSnapshot
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.gmail.absolutebollockscreations.gattlibrary.server.CpsNativeEventInput
import com.gmail.absolutebollockscreations.gattlibrary.server.CscNativeEventInput
import com.gmail.absolutebollockscreations.gattlibrary.server.DEFAULT_METRIC_SAMPLE_PERIOD_MS
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsMetricSnapshot
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsMetricStore
import com.gmail.absolutebollockscreations.gattlibrary.server.MetricSampler
import com.gmail.absolutebollockscreations.gattlibrary.server.TreadmillNativePaceInput
import com.gmail.absolutebollockscreations.gattlibrary.server.UpstreamReadings

/**
 * Samples central's public [MeasurementSnapshot] into [FtmsMetricStore] (L24).
 *
 * Production reads `FitnessGattClient.measurements` through [measurements]; nothing here touches
 * engine state. The engine-state constructor below is transitional, for tests that still drive
 * `BtEngineState` directly, and is removed with them. One consequence of reading the client: the
 * snapshot is produced on the client's own collector, so it can lag the engine by one dispatch.
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
    private val measurements: () -> MeasurementSnapshot,
    private val store: FtmsMetricStore,
    private val profileProvider: () -> FtmsMachineProfile?,
) : MetricSampler {

    /** Transitional: reads engine state directly. Tests only; production passes the client's snapshot. */
    constructor(
        engineState: BtEngineState,
        store: FtmsMetricStore,
        profileProvider: () -> FtmsMachineProfile?,
    ) : this({ engineState.toMeasurementSnapshot() }, store, profileProvider)

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
        val m = measurements()
        return UpstreamReadings(
            speedKph = m.speedKph,
            averageSpeedKph = m.averageSpeedKph,
            instantPaceMinPerKm = m.paceMinPerKm,
            averagePaceMinPerKm = m.averagePaceMinPerKm,
            strokeRate = m.strokeRate,
            instantCadence = m.cadence,
            averageCadence = m.averageCadence,
            instantPower = m.powerWatts,
            averagePower = m.averagePowerWatts,
            forceOnBeltNewtons = m.forceOnBeltNewtons,
            heartRate = m.heartRate,
            totalDistanceMeters = m.distanceMeters,
            elapsedSeconds = m.elapsedSeconds,
            caloriesTotal = m.energyKcal,
            caloriesPerHour = m.energyRateKcalPerHour,
            resistance = m.resistance,
            inclinationPercent = m.inclinationPercent,
            elevationGainPositiveMeters = m.ascentMeters,
            elevationGainNegativeMeters = m.descentMeters,
            totalFloors = m.floors,
            strokeCount = m.strokeCount,
            totalStrides = m.strideCount,
            totalSteps = m.stepCount,
            cscNativeEvents = m.cscEvents?.toCscInput(),
            cpsNativeEvents = m.cpsEvents?.toCpsInput(),
            treadmillNativePace = m.treadmillRawPace?.toInput(),
        ).toSnapshot(profileProvider(), SystemClock.elapsedRealtime())
    }

    companion object {
        /** Shared with every other sampler; the derivation and its rationale live on [DEFAULT_METRIC_SAMPLE_PERIOD_MS]. */
        const val DEFAULT_SAMPLE_PERIOD_MS = DEFAULT_METRIC_SAMPLE_PERIOD_MS
    }
}

private fun CycleSensorEvents.toCscInput(): CscNativeEventInput = CscNativeEventInput(
    cumulativeWheelRevolutions = cumulativeWheelRevolutions,
    lastWheelEventTimeTicks = lastWheelEventTimeTicks,
    cumulativeCrankRevolutions = cumulativeCrankRevolutions,
    lastCrankEventTimeTicks = lastCrankEventTimeTicks,
)

private fun CycleSensorEvents.toCpsInput(): CpsNativeEventInput = CpsNativeEventInput(
    cumulativeWheelRevolutions = cumulativeWheelRevolutions,
    lastWheelEventTimeTicks = lastWheelEventTimeTicks,
    cumulativeCrankRevolutions = cumulativeCrankRevolutions,
    lastCrankEventTimeTicks = lastCrankEventTimeTicks,
)

private fun TreadmillRawPace.toInput(): TreadmillNativePaceInput = TreadmillNativePaceInput(
    instantaneousRaw = instantaneousRaw,
    averageRaw = averageRaw,
)
