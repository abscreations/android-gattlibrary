package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import android.content.Context
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtEngineState
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtServiceProfiles.FtmsMachineProfile
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.FtmsControlSink
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.TargetCapabilities
import com.gmail.absolutebollockscreations.Fitbridge.power.FitBridgeWakeLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Debug-only synthetic data source.
 *
 * Ported from TrackMyIndoorWorkout's `mock_data_source.dart`, with one
 * deliberate difference: TMIW pushes samples straight into the peripheral's
 * metric store, because its own UI is fed from Dart. FitBridge is a bridge, so
 * the simulator instead writes into [BtEngineState] - the same normalised model a
 * real machine populates. Everything downstream then works unchanged: the app's
 * Biking/Rowing/Treadmill screens light up, HR zones and charts populate, and
 * `EngineMetricPump` carries the same numbers out over FTMS. One injection point
 * exercises both roles of the bridge.
 *
 * Ownership is exclusive. Starting the simulator while a real machine is
 * connected would put two writers on one state object and produce nonsense, so
 * [start] refuses in that case.
 */
class FtmsSimulator(
    private val appContext: Context,
    private val engineState: BtEngineState,
    private val peripheral: SimulatorPeripheralHost,
) {

    /**
     * CPU wake-lock owner, scoped to the generator rather than to the page.
     *
     * The display lock belongs to the page (see `KeepAwake` in SimulatorScreen),
     * but the CPU lock must outlive it: the normal workflow is start generating,
     * close the page, then go and connect a watch. Tying it to the composable
     * would drop it at exactly the moment it starts mattering.
     */
    private val wakeLockOwner = object {
        override fun toString(): String = "Simulator:generator"
    }

    /**
     * The peripheral operations the simulator needs. Kept as an interface so the
     * generator can be tested without a Bluetooth stack, and so the simulator
     * does not reach into `BtServiceManager`'s internals.
     */
    interface SimulatorPeripheralHost {
        /** Publish the GATT database and advertise. Blocking; returns success. */
        fun startAdvertising(persona: SimulatorPersona, targets: TargetCapabilities): Boolean

        fun stopAdvertising()

        /** Route Control Point procedures to [sink], or back to the machine on null. */
        fun setControlOverride(sink: FtmsControlSink?)

        /** Publish the latest rich PM5 snapshot to the PM5 measurement scheduler. */
        fun publishConcept2(snapshot: Concept2Pm5Snapshot) {}

        /** Publish one proprietary Chrono measurement frame from the coherent bike sample. */
        fun publishPrecor(sample: FtmsMockSample) {}

        val isAdvertising: Boolean
    }

    data class State(
        val running: Boolean = false,
        val persona: SimulatorPersona? = null,
        val concept2Scenario: Concept2Scenario? = null,
        val machine: FtmsMachineProfile? = null,
        val controlsEnabled: Boolean = false,
        val controlsAvailable: Boolean = false,
        val advertising: Boolean = false,
        val samplesGenerated: Long = 0,
        val elapsedSeconds: Int = 0,
        /** Targets a downstream client has set and the simulator has applied. */
        val acceptedTargets: Map<FtmsMockTarget, Double> = emptyMap(),
        val lastSample: FtmsMockSample? = null,
        val lastError: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var job: Job? = null
    private val sessionProjection = SimulatorSessionProjection(engineState)

    @Volatile
    private var engine: FtmsMockEngine? = null
    private var concept2Engine: Concept2Pm5MockEngine? = null

    /**
     * Control Point target while the simulator owns the machine.
     *
     * Reports the *simulator's* capability rather than the engine's, so the
     * Supported-* Range characteristics, the Target Setting Features word and
     * what actually happens when a target is written all come from one place.
     */
    private inner class SimulatorControlSink : FtmsControlSink {
        override val canControl: Boolean
            get() = engine?.controlsEnabled == true

        override val targetCapabilities: TargetCapabilities
            get() = engine?.controlCapabilities ?: TargetCapabilities.NONE

        // This local sink is authoritative; no upstream machine will echo a
        // target status later, so the Control Point handler publishes it now.
        override val reportsTargetChangesAsynchronously: Boolean = false
        override val clampsTargetsToAdvertisedRange: Boolean = true

        override fun requestControl(): Boolean = true

        override fun reset(): Boolean {
            val e = engine ?: return false
            e.reset()
            publish(e.snapshot())
            publishTargets()
            return true
        }

        // A synthetic machine is always willing to start and stop; the local
        // procedure state machine in the peripheral tracks which it is.
        override fun startOrResume(): Boolean = true
        override fun stopOrPause(stop: Boolean): Boolean = true

        override fun setTargetSpeedKph(kph: Double): Boolean =
            apply(FtmsMockTarget.SPEED, kph)

        override fun setTargetInclinationPercent(percent: Double): Boolean =
            apply(FtmsMockTarget.INCLINATION, percent)

        override fun setTargetResistance(level: Double): Boolean =
            apply(FtmsMockTarget.RESISTANCE, level)

        override fun setTargetPowerWatts(watts: Int): Boolean =
            apply(FtmsMockTarget.POWER, watts.toDouble())

        /** No synthetic heart-rate target: the athlete is not a controllable machine. */
        override fun setTargetHeartRate(bpm: Int): Boolean = false

        override fun setIndoorBikeSimulation(
            windMps: Double,
            gradePercent: Double,
            crr: Double,
            cw: Double,
        ): Boolean = false

        private fun apply(type: FtmsMockTarget, value: Double): Boolean {
            val e = engine ?: return false
            if (!e.setTarget(type, value)) return false
            Timber.tag(TAG).i("control target applied %s = %.2f", type, value)
            // Reflect the accepted target in the measurement stream immediately
            // rather than waiting for the next tick, so a watch sees the machine
            // respond within its own procedure timeout.
            publish(e.snapshot())
            publishTargets()
            return true
        }
    }

    private val controlSink = SimulatorControlSink()

    /** True when a real machine is attached; the simulator must not fight it. */
    private fun realMachineAttached(): Boolean = engineState.gatt != null

    fun controlsAvailableFor(machine: FtmsMachineProfile): Boolean =
        FtmsMockControlProfile.forMachine(machine).any

    /**
     * Start generating data for [machine] and advertise as that machine.
     *
     * Blocking on the advertising verdict, so call it off the main thread.
     */
    fun start(machine: FtmsMachineProfile, enableControls: Boolean) {
        start(SimulatorPersona.Ftms(machine), enableControls)
    }

    /** Protocol-level entry point for the standard and proprietary simulator personas. */
    fun start(
        persona: SimulatorPersona,
        enableControls: Boolean = false,
        concept2Scenario: Concept2Scenario = Concept2Scenario.STEADY,
    ) {
        when (persona) {
            is SimulatorPersona.Concept2Pm5 -> {
                startConcept2(persona, concept2Scenario)
                return
            }
            SimulatorPersona.PrecorChrono -> {
                startPrecor()
                return
            }
            is SimulatorPersona.Ftms -> Unit
        }
        val machine = persona.profile
        if (realMachineAttached()) {
            _state.value = _state.value.copy(
                lastError = "Disconnect the physical machine before simulating data",
            )
            return
        }

        val effectiveControls = enableControls && controlsAvailableFor(machine)
        val current = _state.value
        if (current.running &&
            current.machine == machine &&
            current.controlsEnabled == effectiveControls
        ) {
            return
        }
        if (current.running) stop()

        val mockEngine = FtmsMockEngine(machine, controlsEnabled = effectiveControls)
        engine = mockEngine

        // Install the control sink before advertising begins, so a very fast
        // downstream connection cannot write a target while the peripheral is
        // still pointed at the (absent) real machine.
        peripheral.setControlOverride(controlSink)

        val started = runCatching {
            peripheral.startAdvertising(persona, mockEngine.controlCapabilities)
        }.getOrElse { t ->
            Timber.tag(TAG).w(t, "simulator advertising threw")
            false
        }

        if (!started) {
            peripheral.setControlOverride(null)
            engine = null
            _state.value = State(
                controlsAvailable = controlsAvailableFor(machine),
                lastError = "Could not start advertising as ${machine.name}",
            )
            return
        }

        sessionProjection.begin(haveControl = effectiveControls)

        // Keep the CPU up for as long as we are generating, so bridging survives
        // the screen going off. Paired with the release in stop().
        FitBridgeWakeLock.acquireCpu(appContext, wakeLockOwner)

        _state.value = State(
            running = true,
            persona = persona,
            machine = machine,
            controlsEnabled = effectiveControls,
            controlsAvailable = controlsAvailableFor(machine),
            advertising = peripheral.isAdvertising,
            acceptedTargets = mockEngine.controlTargets(),
        )

        // Publish one sample before the loop so the UI and any already-subscribed
        // client see data immediately rather than after the first period.
        publish(mockEngine.snapshot())

        job = scope.launch {
            while (isActive) {
                delay(PERIOD_MS)
                val e = engine ?: break
                publish(e.tick(PERIOD_MS / 1000.0))
            }
        }
        Timber.tag(TAG).i("simulator started machine=%s controls=%s", machine, effectiveControls)
    }

    fun stop() {
        job?.cancel()
        job = null
        engine = null
        concept2Engine = null
        FitBridgeWakeLock.releaseCpu(wakeLockOwner)
        peripheral.setControlOverride(null)
        runCatching { peripheral.stopAdvertising() }
            .onFailure { Timber.tag(TAG).w(it, "stopping simulator advertising threw") }

        sessionProjection.end()

        _state.value = State(controlsAvailable = _state.value.controlsAvailable)
        Timber.tag(TAG).i("simulator stopped")
    }

    private fun startConcept2(persona: SimulatorPersona.Concept2Pm5, scenario: Concept2Scenario) {
        if (realMachineAttached()) {
            _state.value = State(persona = persona, lastError = "Disconnect the physical machine before simulating data")
            return
        }
        if (_state.value.running) stop()
        val pm5 = Concept2Pm5MockEngine(persona.machine, scenario)
        concept2Engine = pm5
        peripheral.setControlOverride(null)
        val started = runCatching {
            peripheral.startAdvertising(persona, TargetCapabilities.NONE)
        }.getOrDefault(false)
        if (!started) {
            concept2Engine = null
            _state.value = State(persona = persona, lastError = "Could not start advertising as ${persona.advertisedName}")
            return
        }
        sessionProjection.begin(haveControl = false)
        FitBridgeWakeLock.acquireCpu(appContext, wakeLockOwner)
        _state.value = State(
            running = true,
            persona = persona,
            concept2Scenario = scenario,
            controlsEnabled = false,
            controlsAvailable = false,
            advertising = peripheral.isAdvertising,
        )
        publishConcept2(pm5.snapshot())
        job = scope.launch {
            while (isActive) {
                delay(PERIOD_MS)
                val active = concept2Engine ?: break
                publishConcept2(active.tick(PERIOD_MS / 1000.0))
            }
        }
        Timber.tag(TAG).i("PM5 simulator started machine=%s firmware=%s", persona.machine, persona.firmware)
    }

    private fun startPrecor() {
        val persona = SimulatorPersona.PrecorChrono
        if (realMachineAttached()) {
            _state.value = State(
                persona = persona,
                lastError = "Disconnect the physical machine before simulating data",
            )
            return
        }
        if (_state.value.running) stop()

        val mockEngine = FtmsMockEngine(FtmsMachineProfile.INDOOR_BIKE, controlsEnabled = false)
        engine = mockEngine
        peripheral.setControlOverride(null)
        val started = runCatching {
            peripheral.startAdvertising(persona, TargetCapabilities.NONE)
        }.getOrDefault(false)
        if (!started) {
            engine = null
            _state.value = State(
                persona = persona,
                lastError = "Could not start advertising as ${persona.advertisedName}",
            )
            return
        }

        sessionProjection.begin(haveControl = false)
        FitBridgeWakeLock.acquireCpu(appContext, wakeLockOwner)
        _state.value = State(
            running = true,
            persona = persona,
            machine = FtmsMachineProfile.INDOOR_BIKE,
            controlsEnabled = false,
            controlsAvailable = false,
            advertising = peripheral.isAdvertising,
        )
        publish(mockEngine.snapshot())
        job = scope.launch {
            while (isActive) {
                delay(PERIOD_MS)
                val active = engine ?: break
                publish(active.tick(PERIOD_MS / 1000.0))
            }
        }
        Timber.tag(TAG).i("Precor Chrono simulator started")
    }

    private fun publishConcept2(snapshot: Concept2Pm5Snapshot) {
        peripheral.publishConcept2(snapshot)
        val speedKph = snapshot.speedMillimetresPerSecond / 1000.0 * 3.6
        publish(
            FtmsMockSample(
                elapsedSeconds = snapshot.elapsedCentiseconds / 100,
                heartRate = snapshot.heartRate,
                caloriesTotal = snapshot.totalCalories,
                caloriesPerHour = snapshot.caloriesPerHour,
                caloriesPerMinute = snapshot.caloriesPerHour / 60,
                speedKph = speedKph,
                averageSpeedKph = if (snapshot.elapsedCentiseconds > 0) {
                    (snapshot.distanceDecimetres / 10.0) / (snapshot.elapsedCentiseconds / 100.0) * 3.6
                } else speedKph,
                distanceMeters = snapshot.distanceDecimetres / 10,
                cadence = snapshot.strokeRate.toDouble(),
                averageCadence = snapshot.averageCadence,
                powerWatts = snapshot.currentPowerWatts,
                averagePowerWatts = snapshot.averagePowerWatts,
                instantPaceMinPerKm = snapshot.currentPaceCentiseconds / 100.0 / 500.0,
                averagePaceMinPerKm = snapshot.averagePaceCentiseconds / 100.0 / 500.0,
                movementCount = snapshot.strokeCount,
            ),
        )
    }

    fun dispose() {
        stop()
        scope.cancel()
    }

    /** Refresh the advertising flag, which the peripheral owns and can change. */
    fun refreshAdvertisingState() {
        val s = _state.value
        if (!s.running) return
        _state.value = s.copy(advertising = peripheral.isAdvertising)
    }

    private fun publishTargets() {
        val e = engine ?: return
        _state.value = _state.value.copy(acceptedTargets = e.controlTargets())
    }

    /**
     * Write one sample into [BtEngineState].
     *
     * Deliberately mirrors what the FTMS parsers do for a real machine, including
     * leaving a metric untouched when this persona does not produce it - a
     * simulated rower must not publish an inclination any more than a real one
     * would.
     */
    private fun publish(sample: FtmsMockSample) {
        if (_state.value.persona == SimulatorPersona.PrecorChrono) {
            peripheral.publishPrecor(sample)
        }
        engineState.setElapsedSeconds(sample.elapsedSeconds)
        engineState.setHeartRate(sample.heartRate)
        engineState.setExpEnergyTotal(sample.caloriesTotal)
        engineState.setExpEnergyRateKcalPerHour(sample.caloriesPerHour)

        sample.speedKph?.let {
            engineState.setSpeedKph(it)
            engineState.setSpeedMph(it / MILES_TO_KM)
        }
        sample.averageSpeedKph?.let { engineState.setAvgSpeedKph(it) }
        sample.distanceMeters?.let { engineState.setTotalDistanceMeters(it) }
        sample.cadence?.let { engineState.setInstantCadence(it) }
        engineState.setAverageCadence(sample.averageCadence)
        sample.resistance?.let { engineState.setResistance(it.toDouble()) }
        sample.inclinationPercent?.let { engineState.setInclination(it) }
        sample.powerWatts?.let { engineState.setInstantPower(it) }
        engineState.setAveragePower(sample.averagePowerWatts)
        sample.forceOnBeltNewtons?.let { engineState.appendForceOnBeltSample(it) }
        sample.instantPaceMinPerKm?.let { engineState.setInstantPace(it) }
        sample.averagePaceMinPerKm?.let { engineState.setAvgPace(it) }
        sample.positiveElevationMeters?.let { engineState.setElevationGains(it, null) }
        sample.floors?.let { engineState.setTotalFloors(it) }

        // The count field means a different thing per persona, so it is routed
        // rather than written to all three.
        sample.movementCount?.let { count ->
            val e = engine
            when {
                e == null -> Unit
                e.profile.isRower -> {
                    engineState.setStrokeCount(count)
                    sample.cadence?.let { engineState.setStrokeRate(it) }
                }

                e.profile.isClimber -> engineState.setTotalSteps(count)
                else -> engineState.setTotalStrides(count.toDouble())
            }
        }

        val s = _state.value
        _state.value = s.copy(
            samplesGenerated = s.samplesGenerated + 1,
            elapsedSeconds = sample.elapsedSeconds,
            advertising = peripheral.isAdvertising,
            lastSample = sample,
        )
    }

    companion object {
        private const val TAG = "FtmsSimulator"

        /**
         * Source cadence, independent of the peripheral's 700 ms notification
         * cadence. The notifier always samples the latest snapshot, so this also
         * exercises the same producer/consumer decoupling used when a real
         * machine delivers records irregularly.
         */
        const val PERIOD_MS = 500L

        private const val MILES_TO_KM = 1.609344
    }
}
