package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.server.MovementCountKind
import com.gmail.absolutebollockscreations.gattlibrary.server.SimulatedUpstreamHost
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsControlSink
import com.gmail.absolutebollockscreations.gattlibrary.server.TargetCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.core.KeepAwake
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SchwinnX70Uuids

/**
 * Debug-only synthetic data source.
 *
 * Ported from TrackMyIndoorWorkout's `mock_data_source.dart`, with one
 * deliberate difference: TMIW pushes samples straight into the peripheral's
 * metric store, because its own UI is fed from Dart. FitBridge is a bridge, so
 * the simulator instead writes into the host engine state ([SimulatedUpstreamHost]) - the same normalised model a
 * real machine populates. Everything downstream then works unchanged: the app's
 * Biking/Rowing/Treadmill screens light up, HR zones and charts populate, and
 * `EngineMetricPump` carries the same numbers out over FTMS. One injection point
 * exercises both roles of the bridge.
 *
 * Ownership is exclusive. Starting the simulator while a real machine is
 * connected would put two writers on one state object and produce nonsense, so
 * [start] refuses in that case, for every persona. The reverse holds too (decision L17,
 * LIB-9c): when the engine starts an upstream machine link for bridging, a running simulator
 * stops completely, and it does not restart when the bridge ends.
 */
public class FtmsSimulator(
    private val upstream: SimulatedUpstreamHost,
    private val peripheral: SimulatorPeripheralHost,
    /** CPU keep-awake owned by the host app (LIB-7/S7); shares its registry with RunMode. */
    private val keepAwake: KeepAwake,
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
    public interface SimulatorPeripheralHost {
        /** Publish the GATT database and advertise. Blocking; returns success. */
        public fun startAdvertising(persona: SimulatorPersona, targets: TargetCapabilities): Boolean

        public fun stopAdvertising()

        /** Route Control Point procedures to [sink], or back to the machine on null. */
        public fun setControlOverride(sink: FtmsControlSink?)

        /** Control workout output independently of advertising and setup. */
        public fun setWorkoutRunning(running: Boolean) {}

        /** Publish the latest rich PM5 snapshot to the PM5 measurement scheduler. */
        public fun publishConcept2(snapshot: Concept2Pm5Snapshot) {}

        /** Clear PM5 CSAFE workout state without changing its connection or CCCD state. */
        public fun resetConcept2WorkoutState() {}

        /** Latest complete CSAFE workout program accepted from the connected PM5 client. */
        public val concept2WorkoutProgram: Concept2WorkoutProgram? get() = null

        /** Consume an acknowledged Workout/Terminate Workout command without dropping BLE. */
        public fun consumeConcept2WorkoutTerminationRequest(): Boolean = false

        /** Publish one proprietary Chrono measurement frame from the coherent bike sample. */
        public fun publishPrecor(sample: FtmsMockSample) {}

        /** Publish one proprietary Flywheel measurement frame from the coherent bike sample. */
        public fun publishFlywheel(sample: FtmsMockSample) {}

        /** Update the snapshot returned by the KayakFirst console on its next command-6 poll. */
        public fun publishKayakFirst(sample: FtmsMockSample) {}

        /**
         * Publish one running-footpod tick: RSC measurement, Cycling Power measurement and, on
         * its own slower tick, battery level. The peripheral renders all three from one gait
         * snapshot so cadence and the crank counter cannot disagree.
         */
        public fun publishStrydFootpod(sample: FtmsMockSample) {}

        /** Set the Keiser manufacturer value, or suspend its advertising set on null. */
        public fun publishKeiser(manufacturerPayload: ByteArray?) {}

        /** Publish one capture-shaped Life Fitness FTMS record after local setup unlocks the link. */
        public fun publishLifeFitness(sample: FtmsMockSample) {}

        /** Publish one generated workout tick through the active Sole persona. */
        public fun publishSole(profile: SoleSimulatorProfile, sample: FtmsMockSample) {}

        /**
         * Offer one tick's x70 frames, each tagged with the characteristic it belongs on.
         *
         * Unlike the other personas this passes *frames* rather than a sample, because the three
         * streams are rendered from one console state and the encoder owns that rendering. Handing
         * the peripheral a sample would mean encoding twice and inviting the two copies to disagree
         * about resistance, which is the one cross-stream property hardware never gets wrong.
         */
        public fun publishSchwinnX70(frames: List<Pair<java.util.UUID, ByteArray>>) {}

        /**
         * Emit a Zwift Click button tap on the active Kickr/Zwift Hub persona's already-open
         * RideOn connection. Returns false (and does nothing) for every other persona - the
         * default here, rather than an abstract method, keeps every other host implementation
         * unchanged (same rationale as publishPrecor()/publishLifeFitness()'s empty defaults
         * above).
         */
        public fun sendZwiftClickShift(
            direction: ZwiftClickDirection,
        ): Boolean = false

        /** True when the active persona has completed the setup required before generation. */
        public val generationReady: Boolean get() = false

        /** True while the active persona permits cumulative model state to advance. */
        public val generationAdvancing: Boolean get() = generationReady

        public val isAdvertising: Boolean
    }

    /** Live x70 generation state: one console model, one encoder, one tick period. */
    private class SchwinnX70Session(
        val model: SchwinnX70ConsoleModel,
        val encoder: SchwinnX70FrameEncoder,
    ) {
        val periodMs: Long get() = SchwinnX70Ride.PAIR_PERIOD_MS
        val periodSeconds: Double get() = SchwinnX70Ride.PAIR_PERIOD_MS / 1000.0
    }

    private var schwinnX70: SchwinnX70Session? = null
    private var keiserScenario: KeiserMSeriesValidationScenario? = null
    private var lastKeiserScenarioEvent: KeiserMSeriesValidationScenario.Event? = null

    public data class State(
        val running: Boolean = false,
        val persona: SimulatorPersona? = null,
        val concept2Scenario: Concept2Scenario? = null,
        val concept2WorkoutRunning: Boolean = false,
        val concept2WorkoutComplete: Boolean = false,
        val concept2ProgramProgress: Concept2ProgramProgress? = null,
        val machine: FtmsMachineProfile? = null,
        val controlsEnabled: Boolean = false,
        val controlsAvailable: Boolean = false,
        val advertising: Boolean = false,
        val samplesGenerated: Long = 0,
        val elapsedSeconds: Int = 0,
        /** Targets a downstream client has set and the simulator has applied. */
        val acceptedTargets: Map<FtmsMockTarget, Double> = emptyMap(),
        /** Last accepted Set Indoor Bike Simulation Parameters payload, or null. */
        val acceptedSimulation: FtmsMockSimulationParams? = null,
        /** "Control mode" debug label; see [SimulatorControlModeLabels]. */
        val controlModeLabel: String = SimulatorControlModeLabels.AUTOMATIC,
        val lastSample: FtmsMockSample? = null,
        val lastError: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(State())
    public val state: StateFlow<State> = _state

    private val _workoutRunning = MutableStateFlow(false)
    /** Local workout state for the personas that use the shared Start/Stop Workout controls. */
    public val workoutRunning: StateFlow<Boolean> = _workoutRunning
    private val workoutLock = Any()

    /** Begin or resume generation on the current BLE link. */
    public fun startWorkout(): Unit = synchronized(workoutLock) {
        if (!_state.value.running || _state.value.persona is SimulatorPersona.Concept2Pm5 ||
            _workoutRunning.value
        ) return@synchronized
        upstream.beginSession(haveControl = _state.value.controlsEnabled)
        peripheral.setWorkoutRunning(true)
        _workoutRunning.value = true
    }

    /** Pause generation and hold the workout totals while the peripheral stays connected. */
    public fun stopWorkout(): Unit = synchronized(workoutLock) {
        if (!_workoutRunning.value) return@synchronized
        _workoutRunning.value = false
        peripheral.setWorkoutRunning(false)
        upstream.endSession()
        _state.value = _state.value.copy(lastSample = null)
    }

    /** One atomic generation step with respect to Start/Stop Workout. */
    private fun generate(active: FtmsMockEngine, sample: () -> FtmsMockSample) {
        synchronized(workoutLock) {
            if (engine === active && _workoutRunning.value) publish(sample())
        }
    }

    private var job: Job? = null

    @Volatile
    private var engine: FtmsMockEngine? = null
    private var concept2Engine: Concept2Pm5MockEngine? = null
    private val generationGate = SimulatorGenerationGate()

    private suspend fun awaitClientSubscription(): Boolean {
        while (
            currentCoroutineContext().isActive &&
            !generationGate.observe(peripheral.generationReady)
        ) {
            delay(SUBSCRIPTION_POLL_MS)
        }
        return generationGate.observe(peripheral.generationReady)
    }

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
            generate(e) { e.snapshot() }
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
        ): Boolean {
            val e = engine ?: return false
            val params = FtmsMockSimulationParams(windMps, gradePercent, crr, cw)
            if (!e.setSimulationParams(params)) return false
            Timber.tag(TAG).i(
                "control simulation applied wind=%.3f grade=%.2f crr=%.4f cw=%.2f",
                windMps,
                gradePercent,
                crr,
                cw,
            )
            // Same immediate-reflection rationale as apply(): a watch should see the accepted
            // simulation parameters within its own procedure timeout, not on the next tick.
            generate(e) { e.snapshot() }
            publishTargets()
            return true
        }

        private fun apply(type: FtmsMockTarget, value: Double): Boolean {
            val e = engine ?: return false
            if (!e.setTarget(type, value)) return false
            Timber.tag(TAG).i("control target applied %s = %.2f", type, value)
            // Reflect the accepted target in the measurement stream immediately
            // rather than waiting for the next tick, so a watch sees the machine
            // respond within its own procedure timeout.
            generate(e) { e.snapshot() }
            publishTargets()
            return true
        }
    }

    private val controlSink = SimulatorControlSink()

    private val soleControlSink = object : FtmsControlSink by controlSink {
        override val canControl: Boolean
            get() = _state.value.running && _state.value.persona is SimulatorPersona.Sole && controlSink.canControl
        override val targetCapabilities: TargetCapabilities
            get() = (_state.value.persona as? SimulatorPersona.Sole)?.profile?.testControlTargets
                ?.takeIf { canControl } ?: TargetCapabilities.NONE

        override fun requestControl(): Boolean = canControl
        override fun reset(): Boolean {
            if (!canControl) return false
            stopWorkout()
            return controlSink.reset()
        }
        override fun startOrResume(): Boolean {
            if (!canControl) return false
            startWorkout()
            return true
        }
        override fun stopOrPause(stop: Boolean): Boolean {
            if (!canControl) return false
            stopWorkout()
            return true
        }
    }

    /** True when a real machine is attached; the simulator must not fight it. */
    private fun realMachineAttached(): Boolean = upstream.machineLinkAttached

    /** A machine link is attached, connecting or connected: bridge mode owns the engine (L17). */
    private fun bridgeLinkActive(): Boolean =
        realMachineAttached() ||
            SimulatorBridgeGuard.upstreamLinkActive(upstream.machineConnectionState.value)

    /** Full stop because a bridge is starting; the reason stays on screen (LIB-9c). */
    private fun stopForBridge() {
        if (!_state.value.running) return
        Timber.tag(TAG).i("machine link starting: stopping simulator (bridge and simulator are exclusive, L17)")
        stop()
        _state.value = _state.value.copy(lastError = SimulatorBridgeGuard.STOPPED_FOR_BRIDGE)
    }

    public fun controlsAvailableFor(machine: FtmsMachineProfile): Boolean =
        FtmsMockControlProfile.forMachine(machine).any

    public fun controlsAvailableFor(profile: SoleSimulatorProfile): Boolean = profile.testControlTargets.any

    /** UI entry point for the simulator's Zwift Click section. */
    public fun sendZwiftClickShift(
        direction: ZwiftClickDirection,
    ): Boolean = peripheral.sendZwiftClickShift(direction)

    /** Begin or resume only the PM5 workout model; advertising and GATT already remain active. */
    public fun startConcept2Workout(): Boolean {
        val active = concept2Engine ?: return false
        if (_state.value.persona !is SimulatorPersona.Concept2Pm5) return false
        active.startWorkout()
        _state.value = _state.value.copy(
            concept2WorkoutRunning = active.isWorkoutRunning,
            concept2WorkoutComplete = active.isWorkoutComplete,
            concept2ProgramProgress = active.programProgress(),
        )
        return true
    }

    /** Freeze only PM5 metric advancement. The existing [stop] remains the teardown operation. */
    public fun pauseConcept2Workout(): Boolean {
        val active = concept2Engine ?: return false
        if (_state.value.persona !is SimulatorPersona.Concept2Pm5) return false
        active.pauseWorkout()
        publishConcept2(active.wireSnapshot())
        _state.value = _state.value.copy(
            concept2WorkoutRunning = active.isWorkoutRunning,
            concept2WorkoutComplete = active.isWorkoutComplete,
        )
        return true
    }

    /** Finish PM5 telemetry and emit its summary state without clearing accumulated totals. */
    public fun endConcept2Workout(): Boolean {
        val active = concept2Engine ?: return false
        if (_state.value.persona !is SimulatorPersona.Concept2Pm5) return false
        val changed = active.endWorkout()
        publishConcept2(active.snapshot())
        _state.value = _state.value.copy(
            concept2WorkoutRunning = false,
            concept2WorkoutComplete = true,
            concept2ProgramProgress = active.programProgress(),
        )
        return changed
    }

    /** Reset PM5 workout metrics without touching advertising, GATT, CCCDs or CSAFE state. */
    public fun resetConcept2Workout(): Boolean {
        val active = concept2Engine ?: return false
        if (_state.value.persona !is SimulatorPersona.Concept2Pm5) return false
        active.resetWorkout()
        peripheral.resetConcept2WorkoutState()
        publishConcept2(active.snapshot())
        _state.value = _state.value.copy(
            concept2WorkoutRunning = false,
            concept2WorkoutComplete = false,
        )
        return true
    }

    /** Protocol-level entry point for the standard and proprietary simulator personas. */
    public fun start(
        persona: SimulatorPersona,
        enableControls: Boolean = false,
        concept2Scenario: Concept2Scenario = Concept2Scenario.STEADY,
    ) {
        if (bridgeLinkActive()) {
            _state.value = _state.value.copy(lastError = SimulatorBridgeGuard.START_REFUSED)
            return
        }
        if (!_state.value.running) generationGate.reset()
        when (persona) {
            is SimulatorPersona.Sole -> {
                startSole(persona, enableControls)
                return
            }
            is SimulatorPersona.Concept2Pm5 -> {
                startConcept2(persona, concept2Scenario)
                return
            }
            is SimulatorPersona.KeiserMSeries -> {
                startKeiser(persona)
                return
            }
            SimulatorPersona.PrecorChrono -> {
                startPrecor()
                return
            }
            SimulatorPersona.FlywheelSports -> {
                startFlywheel()
                return
            }
            SimulatorPersona.KayakFirstBlue,
            SimulatorPersona.KayakFirstOrange,
            -> {
                startKayakFirst(persona)
                return
            }
            is SimulatorPersona.LifeFitness -> {
                startLifeFitness(persona)
                return
            }
            is SimulatorPersona.SchwinnX70 -> {
                startSchwinnX70(persona)
                return
            }
            is SimulatorPersona.FeCBle -> {
                // The "Accept target commands" switch is a free test toggle for every FE-C
                // equipment type (SimulatorPersona.FeCBle's own KDoc: overriding controlEnabled
                // on a measurement-only persona is a supported test configuration, not a
                // physical-equipment claim) - so what the UI asked for always wins over the
                // persona's own equipment-type default, rather than being silently ignored the
                // way it was before this fix (this branch used to call startFeCBle(persona)
                // directly, so enableControls never reached FEC3 registration at all).
                startFeCBle(persona.copy(controlEnabled = enableControls))
                return
            }
            is SimulatorPersona.Kickr -> {
                // Same shape as the FE-C branch above: what the "Accept target commands" switch
                // asked for wins over the persona's own default, instead of being dropped on the
                // floor the way it was when this branch passed `persona` through unchanged.
                startKickr(persona.copy(controlEnabled = enableControls))
                return
            }
            SimulatorPersona.StrydFootpod -> {
                startStrydFootpod()
                return
            }
            SimulatorPersona.IndoorBikeSpeedAndPower -> Unit
            is SimulatorPersona.Ftms -> Unit
        }
        val machine = requireNotNull(persona.standardSurface()).profile

        val effectiveControls = persona is SimulatorPersona.Ftms &&
            enableControls && controlsAvailableFor(machine)
        val current = _state.value
        if (current.running &&
            current.persona == persona &&
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

        upstream.beginSession(haveControl = effectiveControls)

        // Keep the CPU up for as long as we are generating, so bridging survives
        // the screen going off. Paired with the release in stop().
        keepAwake.acquireCpu(wakeLockOwner)

        _state.value = State(
            running = true,
            persona = persona,
            machine = machine,
            controlsEnabled = effectiveControls,
            controlsAvailable = controlsAvailableFor(machine),
            advertising = peripheral.isAdvertising,
            acceptedTargets = mockEngine.controlTargets(),
        )

        job = scope.launch {
            if (!awaitClientSubscription()) return@launch
            generate(mockEngine) { mockEngine.snapshot() }
            while (isActive) {
                delay(PERIOD_MS)
                val e = engine ?: break
                generate(e) { e.tick(PERIOD_MS / 1000.0) }
            }
        }
        Timber.tag(TAG).i("simulator started machine=%s controls=%s", machine, effectiveControls)
    }

    public fun stop() {
        stopWorkout()
        schwinnX70 = null
        keiserScenario = null
        lastKeiserScenarioEvent = null
        job?.cancel()
        job = null
        engine = null
        concept2Engine = null
        generationGate.reset()
        keepAwake.releaseCpu(wakeLockOwner)
        peripheral.setControlOverride(null)
        runCatching { peripheral.stopAdvertising() }
            .onFailure { Timber.tag(TAG).w(it, "stopping simulator advertising threw") }

        upstream.endSession()

        // Link-scoped, like every other per-run field here: a new run must log its own first
        // strap-takeover rather than inheriting the last run's outcome and staying silent.
        lastHeartRateOverride = null

        _state.value = State(controlsAvailable = _state.value.controlsAvailable)
        Timber.tag(TAG).i("simulator stopped")
    }

    private fun startConcept2(persona: SimulatorPersona.Concept2Pm5, scenario: Concept2Scenario) {
        if (realMachineAttached()) {
            _state.value = State(persona = persona, lastError = "Disconnect the physical machine before simulating data")
            return
        }
        if (_state.value.running) stop()
        val pm5 = Concept2Pm5MockEngine(persona.machine, scenario, startsPaused = true)
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
        upstream.beginSession(haveControl = false)
        keepAwake.acquireCpu(wakeLockOwner)
        _state.value = State(
            running = true,
            persona = persona,
            concept2Scenario = scenario,
            concept2WorkoutRunning = false,
            concept2WorkoutComplete = false,
            controlsEnabled = false,
            controlsAvailable = false,
            advertising = peripheral.isAdvertising,
        )
        job = scope.launch {
            if (!awaitClientSubscription()) return@launch
            publishConcept2(pm5.snapshot())
            var appliedProgram: Concept2WorkoutProgram? = null
            while (isActive) {
                delay(CONCEPT2_PERIOD_MS)
                val active = concept2Engine ?: break
                if (peripheral.consumeConcept2WorkoutTerminationRequest()) {
                    endConcept2Workout()
                }
                val receivedProgram = peripheral.concept2WorkoutProgram
                if (receivedProgram == null) {
                    appliedProgram = null
                } else if (receivedProgram != appliedProgram) {
                    active.configureWorkout(receivedProgram)
                    appliedProgram = receivedProgram
                    publishConcept2(active.snapshot())
                }
                if (active.isWorkoutRunning) {
                    publishConcept2(active.tick(CONCEPT2_PERIOD_MS / 1000.0))
                }
            }
        }
        Timber.tag(TAG).i(
            "PM5 simulator started machine=%s compatibility=%s",
            persona.machine,
            persona.compatibility,
        )
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

        upstream.beginSession(haveControl = false)
        keepAwake.acquireCpu(wakeLockOwner)
        _state.value = State(
            running = true,
            persona = persona,
            machine = FtmsMachineProfile.INDOOR_BIKE,
            controlsEnabled = false,
            controlsAvailable = false,
            advertising = peripheral.isAdvertising,
        )
        job = scope.launch {
            if (!awaitClientSubscription()) return@launch
            generate(mockEngine) { mockEngine.snapshot() }
            while (isActive) {
                delay(PrecorChronoEncoder.PAIR_PERIOD_MS)
                val active = engine ?: break
                generate(active) { active.tick(PrecorChronoEncoder.PAIR_PERIOD_MS / 1000.0) }
            }
        }
        Timber.tag(TAG).i("Precor Chrono simulator started")
    }

    private fun startFlywheel() {
        val persona = SimulatorPersona.FlywheelSports
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

        upstream.beginSession(haveControl = false)
        keepAwake.acquireCpu(wakeLockOwner)
        _state.value = State(
            running = true,
            persona = persona,
            machine = FtmsMachineProfile.INDOOR_BIKE,
            controlsEnabled = false,
            controlsAvailable = false,
            advertising = peripheral.isAdvertising,
        )
        job = scope.launch {
            if (!awaitClientSubscription()) return@launch
            generate(mockEngine) { mockEngine.snapshot() }
            while (isActive) {
                delay(FlywheelSportsEncoder.PERIOD_MS)
                val active = engine ?: break
                generate(active) { active.tick(FlywheelSportsEncoder.PERIOD_MS / 1000.0) }
            }
        }
        Timber.tag(TAG).i("Flywheel Sports simulator started")
    }

    private fun startKayakFirst(persona: SimulatorPersona) {
        require(
            persona == SimulatorPersona.KayakFirstBlue ||
                persona == SimulatorPersona.KayakFirstOrange,
        )
        if (realMachineAttached()) {
            _state.value = State(persona = persona, lastError = "Disconnect the physical machine before simulating data")
            return
        }
        if (_state.value.running) stop()
        val mockEngine = FtmsMockEngine(FtmsMachineProfile.INDOOR_ROWER, controlsEnabled = false)
        engine = mockEngine
        peripheral.setControlOverride(null)
        if (!runCatching { peripheral.startAdvertising(persona, TargetCapabilities.NONE) }.getOrDefault(false)) {
            engine = null
            _state.value = State(persona = persona, lastError = "Could not start advertising as ${persona.advertisedName}")
            return
        }
        upstream.beginSession(haveControl = false)
        keepAwake.acquireCpu(wakeLockOwner)
        _state.value = State(
            running = true,
            persona = persona,
            machine = FtmsMachineProfile.INDOOR_ROWER,
            advertising = peripheral.isAdvertising,
        )
        job = scope.launch {
            if (!awaitClientSubscription()) return@launch
            generate(mockEngine) { kayakFirstIdleSample() }
            while (isActive) {
                while (isActive && !peripheral.generationAdvancing) {
                    delay(SUBSCRIPTION_POLL_MS)
                }
                if (!isActive) break
                delay(KayakFirstSimulatorTelemetryEncoder.PERIOD_MS)
                if (!peripheral.generationAdvancing) continue
                val active = engine ?: break
                generate(active) { active.tick(KayakFirstSimulatorTelemetryEncoder.PERIOD_MS / 1_000.0) }
            }
        }
        Timber.tag(TAG).i("KayakFirst simulator started persona=%s", persona.advertisedName)
    }

    /**
     * Running footpod session.
     *
     * Driven by the treadmill ride rather than the indoor-bike one every other measurement-only
     * persona uses: a footpod that reported 24-38 km/h would be describing a cyclist, and the
     * gait model in `RunningGait` is only anchored to the corpus inside running and walking
     * speeds. Controls are off unconditionally - the captured Cycling Power Control Point was
     * never written in any capture, so there is no reference behaviour to honour and the plan's
     * downstream-honesty invariant says an unhonourable control is not advertised.
     */
    private fun startStrydFootpod() {
        val persona = SimulatorPersona.StrydFootpod
        if (realMachineAttached()) {
            _state.value = State(
                persona = persona,
                lastError = "Disconnect the physical machine before simulating data",
            )
            return
        }
        if (_state.value.running) stop()

        val mockEngine = FtmsMockEngine(FtmsMachineProfile.TREADMILL, controlsEnabled = false)
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

        upstream.beginSession(haveControl = false)
        keepAwake.acquireCpu(wakeLockOwner)
        _state.value = State(
            running = true,
            persona = persona,
            machine = FtmsMachineProfile.TREADMILL,
            controlsEnabled = false,
            controlsAvailable = false,
            advertising = peripheral.isAdvertising,
        )
        job = scope.launch {
            if (!awaitClientSubscription()) return@launch
            generate(mockEngine) { mockEngine.snapshot() }
            while (isActive) {
                delay(StrydFootpodEncoder.MEASUREMENT_PERIOD_MS)
                val active = engine ?: break
                generate(active) { active.tick(StrydFootpodEncoder.MEASUREMENT_PERIOD_MS / 1000.0) }
            }
        }
        Timber.tag(TAG).i("Stryd footpod simulator started")
    }

    private fun startKeiser(persona: SimulatorPersona.KeiserMSeries) {
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
        keiserScenario = KeiserMSeriesValidationScenario(persona.profile)
        lastKeiserScenarioEvent = null
        peripheral.setControlOverride(null)
        val started = runCatching {
            peripheral.startAdvertising(persona, TargetCapabilities.NONE)
        }.getOrDefault(false)
        if (!started) {
            engine = null
            keiserScenario = null
            _state.value = State(
                persona = persona,
                lastError = "Could not start advertising as ${persona.advertisedName}",
            )
            return
        }

        upstream.beginSession(haveControl = false)
        keepAwake.acquireCpu(wakeLockOwner)
        _state.value = State(
            running = true,
            persona = persona,
            machine = FtmsMachineProfile.INDOOR_BIKE,
            controlsEnabled = false,
            controlsAvailable = false,
            advertising = peripheral.isAdvertising,
        )
        val periodMs = persona.profile.samplePeriodMs
        job = scope.launch {
            while (isActive) {
                delay(periodMs)
                val active = engine ?: break
                generate(active) { active.tick(periodMs / 1000.0) }
            }
        }
        Timber.tag(TAG).i("Keiser M-series Android simulator started")
    }

    /**
     * Start the FE-C-over-BLE simulator (plan `docs/fe-c-ble_simulator_implementation.md`).
     *
     * Unlike every other branch here, this persona owns its own ride: [FeCBlePeripheralController]
     * carries a self-contained `FeCBleMachineState` and 250 ms scheduler so ERG/SIM control writes
     * on FEC3 converge the same state the scheduler is encoding from. There is no `FtmsMockEngine`
     * and no tick loop here — [SimulatorPeripheralHost.startAdvertising] blocks until the controller
     * itself is fully up, same as every other simulator-only persona's await pattern.
     */
    private fun startFeCBle(persona: SimulatorPersona.FeCBle) {
        if (realMachineAttached()) {
            _state.value = State(
                persona = persona,
                lastError = "Disconnect the physical machine before simulating data",
            )
            return
        }
        if (_state.value.running) stop()

        peripheral.setControlOverride(null)
        val started = runCatching {
            peripheral.startAdvertising(persona, TargetCapabilities.NONE)
        }.getOrDefault(false)
        if (!started) {
            _state.value = State(
                persona = persona,
                lastError = "Could not start advertising as ${persona.advertisedName}",
            )
            return
        }

        upstream.beginSession(haveControl = persona.controlEnabled)
        keepAwake.acquireCpu(wakeLockOwner)
        _state.value = State(
            running = true,
            persona = persona,
            controlsEnabled = persona.controlEnabled,
            // FEC3 availability is a free per-run toggle for every FE-C equipment type here
            // (see the class doc above), so the switch is always interactive - not gated on
            // equipment type the way FTMS's controlsAvailableFor(machine) gates by profile.
            controlsAvailable = true,
            advertising = peripheral.isAdvertising,
        )
        Timber.tag(TAG).i(
            "FE-C over BLE simulator started equipment=%s control=%s",
            persona.equipmentType,
            persona.controlEnabled,
        )
    }

    /** KICKR owns its deterministic model and 500 ms publisher inside its controller. */
    private fun startKickr(persona: SimulatorPersona.Kickr) {
        if (realMachineAttached()) {
            _state.value = State(
                persona = persona,
                lastError = "Disconnect the physical machine before simulating data",
            )
            return
        }
        if (_state.value.running) stop()

        peripheral.setControlOverride(null)
        val started = runCatching {
            peripheral.startAdvertising(persona, TargetCapabilities.NONE)
        }.getOrDefault(false)
        if (!started) {
            _state.value = State(
                persona = persona,
                lastError = "Could not start advertising as ${persona.advertisedName}",
            )
            return
        }

        // Control is negotiated independently on each downstream route; starting the simulator
        // itself does not mean a client owns any of those routes yet.
        upstream.beginSession(haveControl = false)
        keepAwake.acquireCpu(wakeLockOwner)
        _state.value = State(
            running = true,
            persona = persona,
            controlsEnabled = persona.controlEnabled,
            // All four KICKR/Zwift personas implement FTMS target setting (Kinomap and MyWhoosh
            // captures show third-party apps driving them through 0x2AD9), so the switch is
            // genuinely interactive here. It used to be false, which rendered the card as
            // "This machine type has no FTMS target-setting procedure" - untrue of every one
            // of them.
            controlsAvailable = true,
            advertising = peripheral.isAdvertising,
        )
        Timber.tag(TAG).i("KICKR simulator started persona=%s", persona.device)
    }

    private fun startSole(persona: SimulatorPersona.Sole, enableControls: Boolean) {
        if (realMachineAttached()) {
            _state.value = State(persona = persona, lastError = "Disconnect the physical machine before simulating data")
            return
        }
        if (_state.value.running) stop()
        val machine = persona.profile.machine
        val available = persona.profile.testControlTargets
        val targets = if (enableControls) available else TargetCapabilities.NONE
        val mockEngine = FtmsMockEngine(machine, controlsEnabled = targets.any)
        engine = mockEngine
        peripheral.setControlOverride(soleControlSink.takeIf { targets.any })
        val started = runCatching {
            peripheral.startAdvertising(persona, targets)
        }.getOrDefault(false)
        if (!started) {
            peripheral.setControlOverride(null)
            engine = null
            _state.value = State(persona = persona, lastError = "Could not start advertising as ${persona.advertisedName}")
            return
        }
        upstream.beginSession(haveControl = false)
        keepAwake.acquireCpu(wakeLockOwner)
        _state.value = State(
            running = true, persona = persona, machine = machine, advertising = peripheral.isAdvertising,
            controlsAvailable = available.any, controlsEnabled = targets.any,
        )
        job = scope.launch {
            if (!awaitClientSubscription()) return@launch
            while (isActive && engine === mockEngine) {
                if (peripheral.generationReady) generate(mockEngine) { mockEngine.tick(PERIOD_MS / 1000.0) }
                delay(PERIOD_MS)
            }
        }
    }

    private fun startSchwinnX70(persona: SimulatorPersona.SchwinnX70) {
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
        schwinnX70 = SchwinnX70Session(
            model = SchwinnX70ConsoleModel(),
            encoder = SchwinnX70FrameEncoder(),
        )
        peripheral.setControlOverride(null)
        val started = runCatching {
            peripheral.startAdvertising(persona, TargetCapabilities.NONE)
        }.getOrDefault(false)
        if (!started) {
            engine = null
            schwinnX70 = null
            _state.value = State(
                persona = persona,
                lastError = "Could not start advertising as ${persona.advertisedName}",
            )
            return
        }

        upstream.beginSession(haveControl = false)
        keepAwake.acquireCpu(wakeLockOwner)
        _state.value = State(
            running = true,
            persona = persona,
            machine = FtmsMachineProfile.INDOOR_BIKE,
            controlsEnabled = false,
            controlsAvailable = false,
            advertising = peripheral.isAdvertising,
        )
        val periodMs = SchwinnX70Ride.PAIR_PERIOD_MS
        job = scope.launch {
            if (!awaitClientSubscription()) return@launch
            generate(mockEngine) { mockEngine.snapshot() }
            while (isActive) {
                delay(periodMs)
                val active = engine ?: break
                generate(active) { active.tick(periodMs / 1000.0) }
            }
        }
        Timber.tag(TAG).i("Schwinn x70 simulator started")
    }

    private fun startLifeFitness(persona: SimulatorPersona.LifeFitness) {
        if (realMachineAttached()) {
            _state.value = State(
                persona = persona,
                lastError = "Disconnect the physical machine before simulating data",
            )
            return
        }
        if (_state.value.running) stop()

        val mockEngine = FtmsMockEngine(persona.machine.profile, controlsEnabled = false)
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

        upstream.beginSession(haveControl = false)
        keepAwake.acquireCpu(wakeLockOwner)
        _state.value = State(
            running = true,
            persona = persona,
            machine = persona.machine.profile,
            controlsEnabled = false,
            controlsAvailable = false,
            advertising = peripheral.isAdvertising,
        )
        job = scope.launch {
            if (!awaitClientSubscription()) return@launch
            generate(mockEngine) { lifeFitnessVisibleSample(persona.machine, mockEngine.snapshot()) }
            while (isActive) {
                delay(LIFE_FITNESS_PERIOD_MS)
                val active = engine ?: break
                generate(active) { lifeFitnessVisibleSample(persona.machine, active.tick(LIFE_FITNESS_PERIOD_MS / 1000.0)) }
            }
        }
        Timber.tag(TAG).i("Life Fitness simulator started machine=%s generated=true", persona.machine)
    }

    /** Keep simulator diagnostics aligned with fields actually carried by the selected encoder. */
    private fun lifeFitnessVisibleSample(machine: LifeFitnessMachine, sample: FtmsMockSample): FtmsMockSample =
        when (machine) {
            LifeFitnessMachine.TREADMILL -> sample.copy(
                powerWatts = null,
                averagePowerWatts = null,
                forceOnBeltNewtons = null,
            )
            LifeFitnessMachine.CROSS_TRAINER -> sample.copy(inclinationPercent = null)
            LifeFitnessMachine.STAIR_CLIMBER -> sample.copy(
                powerWatts = null,
                averagePowerWatts = null,
            )
            LifeFitnessMachine.INDOOR_BIKE -> sample
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

    public fun dispose() {
        stop()
        scope.cancel()
    }

    /** Refresh the advertising flag, which the peripheral owns and can change. */
    public fun refreshAdvertisingState() {
        val s = _state.value
        if (!s.running) return
        _state.value = s.copy(advertising = peripheral.isAdvertising)
    }

    private fun publishTargets() {
        val e = engine ?: return
        _state.value = _state.value.copy(
            acceptedTargets = e.controlTargets(),
            acceptedSimulation = e.simulationParams(),
            controlModeLabel = e.controlModeLabel(),
        )
    }

    /** The last outcome, so the fallback is logged on its transitions and not once a second. */
    private var lastHeartRateOverride: SimulatorHeartRateOverride.Outcome? = null

    /**
     * Replace the generated heart rate with a live strap's reading when one is fresh.
     *
     * Reads `externalHeartRate` rather than `heartRate`: the simulator writes its own value into
     * the latter, so reading it back would return the generated number one tick later.
     *
     * **Logged on transition only.** The interesting events are a strap taking over and a strap
     * going quiet mid-session — the second especially, because a validation capture that is half
     * real and half synthetic must not look uniform afterwards. Logging every second would bury
     * both in noise.
     */
    private fun withLiveHeartRate(sample: FtmsMockSample): FtmsMockSample {
        val result = SimulatorHeartRateOverride.resolve(
            generatedBpm = sample.heartRate,
            externalBpm = upstream.externalHeartRate.value,
            lastExternalAtMs = upstream.lastExternalHeartRateAtMs,
            nowMs = System.currentTimeMillis(),
        )

        if (result.outcome != lastHeartRateOverride) {
            when (result.outcome) {
                SimulatorHeartRateOverride.Outcome.OVERRIDDEN ->
                    Timber.tag(TAG).i(
                        "Simulator heart rate now sourced from the attached strap; generated value suppressed",
                    )
                SimulatorHeartRateOverride.Outcome.STALE_FELL_BACK ->
                    Timber.tag(TAG).w(
                        "Simulator heart rate fell back to generated: no strap reading for over %d ms. " +
                            "Frames from here on are synthetic.",
                        SimulatorHeartRateOverride.FRESHNESS_MS,
                    )
                SimulatorHeartRateOverride.Outcome.NO_EXTERNAL_SOURCE -> Unit
            }
            lastHeartRateOverride = result.outcome
        }

        return if (result.bpm == sample.heartRate) sample else sample.copy(heartRate = result.bpm)
    }

    /**
     * Render one tick of x70 traffic from a single console state.
     *
     * **Engine values, unscaled.** Each persona used to rescale the engine's speed into its own
     * captured peak, so one ride reached a client as three different numbers and none matched what
     * this app displayed. The only conversion left is km/h to mph, which
     * [SchwinnX70ConsoleModel] owns and Q12 explains.
     *
     * **All three frames come from one [SchwinnX70ConsoleState].** That is what makes the streams
     * agree by construction: resistance is one field read twice, not two fields that have to be
     * kept in step, and the paired frames carry one sequence counter because they are one emission.
     */
    private fun publishSchwinnX70Frames(session: SchwinnX70Session, sample: FtmsMockSample) {
        val state = session.model.advance(
            seconds = session.periodSeconds,
            speedKph = sample.speedKph ?: 0.0,
            powerWatts = sample.powerWatts?.toDouble() ?: 0.0,
            cadenceRpm = sample.cadence ?: 0.0,
            heartRateBpm = sample.heartRate,
            energyKcal = sample.caloriesTotal.toDouble(),
            distanceMeters = sample.distanceMeters?.toDouble() ?: 0.0,
        )
        val frames = listOf(
            SchwinnX70Uuids.UUID_X70_SUPPLEMENTAL to session.encoder.encodeSupplemental(state),
            SchwinnX70Uuids.UUID_X70_SIBLING to session.encoder.encodeSibling(state),
            SchwinnX70Uuids.UUID_X70_MEASUREMENT to session.encoder.encodeMain(state),
        )
        session.encoder.completeTick()
        peripheral.publishSchwinnX70(frames)
    }

    /**
     * Write one sample into the host engine state through [SimulatedUpstreamHost].
     *
     * Deliberately mirrors what the FTMS parsers do for a real machine, including
     * leaving a metric untouched when this persona does not produce it - a
     * simulated rower must not publish an inclination any more than a real one
     * would.
     */
    private fun publish(generated: FtmsMockSample) {
        // A live strap attached to the central overrides the generated heart rate, applied here
        // rather than in each encoder because this is the last point before the standard-FTMS path
        // (host engine state -> EngineMetricPump) and the proprietary path (straight to the encoders)
        // diverge. One substitution covers every persona, including ones not written yet.
        var sample = withLiveHeartRate(generated)

        keiserScenario?.let { scenario ->
            val output = scenario.next(sample)
            if (output.event != lastKeiserScenarioEvent) {
                Timber.tag(TAG).i(
                    "Keiser scenario transition persona=%s event=%s loop=%d elapsedMs=%d payload=%s payloadBytes=%d",
                    (_state.value.persona as? SimulatorPersona.KeiserMSeries)?.advertisedName,
                    output.event,
                    output.loopIndex,
                    output.loopElapsedMs,
                    if (output.manufacturerPayload == null) "SUSPEND" else "PUBLISH",
                    output.manufacturerPayload?.size ?: 0,
                )
                lastKeiserScenarioEvent = output.event
            }
            sample = output.sample
            peripheral.publishKeiser(output.manufacturerPayload)
        }

        if (_state.value.persona == SimulatorPersona.PrecorChrono) {
            peripheral.publishPrecor(sample)
        }
        if (_state.value.persona == SimulatorPersona.FlywheelSports) {
            peripheral.publishFlywheel(sample)
        }
        if (_state.value.persona == SimulatorPersona.KayakFirstBlue ||
            _state.value.persona == SimulatorPersona.KayakFirstOrange
        ) {
            peripheral.publishKayakFirst(sample)
        }
        if (_state.value.persona == SimulatorPersona.StrydFootpod) {
            peripheral.publishStrydFootpod(sample)
        }
        if (_state.value.persona is SimulatorPersona.LifeFitness) {
            peripheral.publishLifeFitness(sample)
        }
        (_state.value.persona as? SimulatorPersona.Sole)?.let { peripheral.publishSole(it.profile, sample) }
        schwinnX70?.let { session -> publishSchwinnX70Frames(session, sample) }
        upstream.publish(
            sample.toUpstreamSample(
                engine?.profile?.let { profile ->
                    when {
                        profile.isRower -> MovementCountKind.STROKES
                        profile.isClimber -> MovementCountKind.STEPS
                        else -> MovementCountKind.STRIDES
                    }
                },
            ),
        )

        val s = _state.value
        _state.value = s.copy(
            samplesGenerated = s.samplesGenerated + 1,
            elapsedSeconds = sample.elapsedSeconds,
            concept2WorkoutRunning = concept2Engine?.isWorkoutRunning
                ?: s.concept2WorkoutRunning,
            concept2WorkoutComplete = concept2Engine?.isWorkoutComplete
                ?: s.concept2WorkoutComplete,
            concept2ProgramProgress = concept2Engine?.programProgress(),
            advertising = peripheral.isAdvertising,
            lastSample = sample,
        )
    }

    // Last in the class body so every property is initialised before the first emission.
    init {
        // L17 (LIB-9c): an upstream machine link starting means a bridge is starting. Watch the
        // link rather than provider advertising, because the link comes first: the simulator
        // must stop writing into the host engine state before the real machine starts writing there.
        scope.launch {
            upstream.machineConnectionState.collect { linkState ->
                if (SimulatorBridgeGuard.upstreamLinkActive(linkState)) stopForBridge()
            }
        }
    }

    public companion object {
        private const val SUBSCRIPTION_POLL_MS = 50L
        private const val TAG = "FtmsSimulator"

        /**
         * Source cadence, independent of the peripheral's 700 ms notification
         * cadence. The notifier always samples the latest snapshot, so this also
         * exercises the same producer/consumer decoupling used when a real
         * machine delivers records irregularly.
         */
        internal const val PERIOD_MS = 500L

        /** ErgData sample-rate value 2 requests one PM5 sample every 250 ms. */
        @SimulatorProbeApi
        public const val CONCEPT2_PERIOD_MS: Long = 250L

        /** Capture corpus median is 1.02 seconds; one second preserves the physical cadence. */
        internal const val LIFE_FITNESS_PERIOD_MS = 1_000L

        /**
         * The mock engine's indoor-bike speed that maps to a persona's captured peak.
         *
         * Scaling rather than clamping keeps the ride's shape on a console whose captured peak is
         * 17.9 km/h as well as one that reaches 26.3. Clamping would flatten the faster half of
         * every 270 session into a constant.
         */

    }
}
