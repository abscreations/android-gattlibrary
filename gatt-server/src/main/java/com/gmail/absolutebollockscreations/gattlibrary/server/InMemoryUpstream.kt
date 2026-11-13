package com.gmail.absolutebollockscreations.gattlibrary.server

import android.bluetooth.BluetoothProfile
import android.os.SystemClock
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * An upstream with no central module behind it (LIB-28): the simulator publishes into process
 * memory and a sampler copies that into the [FtmsMetricStore], so an app made of only
 * core + server + simulator can serve a synthetic machine.
 *
 * It is both halves of the seam: [SimulatedUpstreamHost] for the simulator to write into, and
 * [FtmsUpstreamPort] for [FtmsPeripheralController] to read from. The FitBridge debug app does the
 * same job over the real engine state; both project through [UpstreamReadings.toSnapshot], and the
 * app's parity test pins that they stay equal.
 *
 * Semantics mirror the engine path on purpose: [publish] leaves a metric untouched when the persona
 * does not produce it, averages are overwritten, and [endSession] returns to an idle machine.
 *
 * There is no physical machine, so no link is attached, the connection state stays disconnected,
 * control targets are refused (the simulator installs its own control override), and
 * [supportsPaceMeasurement] is whatever the host chose.
 */
public class InMemoryUpstream(
    private val pacedMachine: Boolean = false,
    private val elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val wallClockMs: () -> Long = { System.currentTimeMillis() },
) : SimulatedUpstreamHost, FtmsUpstreamPort {

    @Volatile
    private var readings = UpstreamReadings()

    private val lock = Any()

    // ---- SimulatedUpstreamHost ----

    override val machineLinkAttached: Boolean = false

    override val machineConnectionState: StateFlow<Int> =
        MutableStateFlow(BluetoothProfile.STATE_DISCONNECTED)

    private val _externalHeartRate = MutableStateFlow<Int?>(null)
    override val externalHeartRate: StateFlow<Int?> = _externalHeartRate

    @Volatile
    override var lastExternalHeartRateAtMs: Long = 0L
        private set

    /** Feed an external strap reading (or null when it drops); the simulator prefers it to its own value. */
    public fun setExternalHeartRate(bpm: Int?) {
        lastExternalHeartRateAtMs = if (bpm == null) 0L else wallClockMs()
        _externalHeartRate.value = bpm
    }

    override fun beginSession(haveControl: Boolean) {
        // Control is decided by the simulator's own control override; there is no engine flag to set.
    }

    override fun endSession() {
        synchronized(lock) { readings = UpstreamReadings() }
    }

    override fun publish(sample: SimulatedUpstreamSample) {
        synchronized(lock) {
            var r = readings.copy(
                elapsedSeconds = sample.elapsedSeconds,
                heartRate = sample.heartRate,
                caloriesTotal = sample.caloriesTotal,
                caloriesPerHour = sample.caloriesPerHour,
                // Always overwritten, even with null, exactly as the engine path does.
                averageCadence = sample.averageCadence,
                averagePower = sample.averagePowerWatts,
            )
            sample.speedKph?.let {
                r = r.copy(speedKph = it)
            }
            sample.averageSpeedKph?.let { r = r.copy(averageSpeedKph = it) }
            sample.distanceMeters?.let { r = r.copy(totalDistanceMeters = it) }
            sample.cadence?.let { r = r.copy(instantCadence = it) }
            sample.resistance?.let { r = r.copy(resistance = it.toDouble()) }
            sample.inclinationPercent?.let { r = r.copy(inclinationPercent = it) }
            sample.powerWatts?.let { r = r.copy(instantPower = it) }
            sample.forceOnBeltNewtons?.let { r = r.copy(forceOnBeltNewtons = it) }
            sample.instantPaceMinPerKm?.let { r = r.copy(instantPaceMinPerKm = it) }
            sample.averagePaceMinPerKm?.let { r = r.copy(averagePaceMinPerKm = it) }
            sample.positiveElevationMeters?.let { r = r.copy(elevationGainPositiveMeters = it) }
            sample.floors?.let { r = r.copy(totalFloors = it) }
            // The count field means a different thing per persona, so it is routed rather than
            // written to all three.
            sample.movementCount?.let { count ->
                when (sample.movementCountKind) {
                    null -> Unit
                    MovementCountKind.STROKES -> {
                        r = r.copy(strokeCount = count)
                        sample.cadence?.let { r = r.copy(strokeRate = it) }
                    }
                    MovementCountKind.STEPS -> r = r.copy(totalSteps = count)
                    MovementCountKind.STRIDES -> r = r.copy(totalStrides = count.toDouble())
                }
            }
            readings = r
        }
    }

    /** The current readings, for tests and diagnostics. */
    public fun currentReadings(): UpstreamReadings = readings

    // ---- FtmsUpstreamPort ----

    override val controlSink: UpstreamControlSink = object : UpstreamControlSink, FtmsControlSink by InertFtmsControlSink {
        override fun beginSession(targets: TargetCapabilities) = Unit
        override fun endSession() = Unit
    }

    override fun connectedCentralAddresses(): Set<String> = emptySet()

    override fun supportsPaceMeasurement(): Boolean = pacedMachine

    override fun createMetricSampler(
        store: FtmsMetricStore,
        profileProvider: () -> FtmsMachineProfile?,
    ): MetricSampler = object : MetricSampler {
        private var job: Job? = null

        override fun start(scope: CoroutineScope) {
            if (job != null) return
            job = scope.launch {
                while (isActive) {
                    sampleOnce()
                    delay(DEFAULT_METRIC_SAMPLE_PERIOD_MS)
                }
            }
        }

        override fun stop() {
            job?.cancel()
            job = null
        }

        override fun sampleOnce() {
            val profile = profileProvider()
            store.update(readings.toSnapshot(profile, elapsedRealtimeMs()), profile)
        }
    }
}
