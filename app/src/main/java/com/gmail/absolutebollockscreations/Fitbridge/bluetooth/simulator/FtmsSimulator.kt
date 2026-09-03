package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import android.content.Context
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
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

        /** True only after a client enables a PM5 workout-data CCCD, not status alone. */
        val concept2WorkoutRequested: Boolean get() = false

        /** Publish one proprietary Chrono measurement frame from the coherent bike sample. */
        fun publishPrecor(sample: FtmsMockSample) {}

        /**
         * Publish one running-footpod tick: RSC measurement, Cycling Power measurement and, on
         * its own slower tick, battery level. The peripheral renders all three from one gait
         * snapshot so cadence and the crank counter cannot disagree.
         */
        fun publishStrydFootpod(sample: FtmsMockSample) {}

        /** Set the Keiser manufacturer value, or suspend its advertising set on null. */
        fun publishKeiser(manufacturerPayload: ByteArray?) {}

        /** Publish one capture-shaped Life Fitness FTMS record after local setup unlocks the link. */
        fun publishLifeFitness(sample: FtmsMockSample) {}

        /**
         * Offer one tick's x70 frames, each tagged with the characteristic it belongs on.
         *
         * Unlike the other personas this passes *frames* rather than a sample, because the three
         * streams are rendered from one console state and the encoder owns that rendering. Handing
         * the peripheral a sample would mean encoding twice and inviting the two copies to disagree
         * about resistance, which is the one cross-stream property hardware never gets wrong.
         */
        fun publishSchwinnX70(frames: List<Pair<java.util.UUID, ByteArray>>) {}

        /**
         * Emit a Zwift Click button tap on the active Kickr/Zwift Hub persona's already-open
         * RideOn connection. Returns false (and does nothing) for every other persona - the
         * default here, rather than an abstract method, keeps every other host implementation
         * unchanged (same rationale as publishPrecor()/publishLifeFitness()'s empty defaults
         * above).
         */
        fun sendZwiftClickShift(
            direction: com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.ZwiftClickDirection,
        ): Boolean = false

        val isAdvertising: Boolean
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
        /** Last accepted Set Indoor Bike Simulation Parameters payload, or null. */
        val acceptedSimulation: FtmsMockSimulationParams? = null,
        /** "Control mode" debug label; see [SimulatorControlModeLabels]. */
        val controlModeLabel: String = SimulatorControlModeLabels.AUTOMATIC,
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
            publish(e.snapshot())
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

    /** UI entry point for the simulator's Zwift Click section. */
    fun sendZwiftClickShift(
        direction: com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.ZwiftClickDirection,
    ): Boolean = peripheral.sendZwiftClickShift(direction)

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
            is SimulatorPersona.KeiserMSeries -> {
                startKeiser(persona)
                return
            }
            SimulatorPersona.PrecorChrono -> {
                startPrecor()
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
        if (realMachineAttached()) {
            _state.value = _state.value.copy(
                lastError = "Disconnect the physical machine before simulating data",
            )
            return
        }

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
        schwinnX70 = null
        keiserScenario = null
        lastKeiserScenarioEvent = null
        job?.cancel()
        job = null
        engine = null
        concept2Engine = null
        FitBridgeWakeLock.releaseCpu(wakeLockOwner)
        peripheral.setControlOverride(null)
        runCatching { peripheral.stopAdvertising() }
            .onFailure { Timber.tag(TAG).w(it, "stopping simulator advertising threw") }

        sessionProjection.end()

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
                publishConcept2(active.tick(PERIOD_MS / 1000.0, peripheral.concept2WorkoutRequested))
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
                delay(PrecorChronoEncoder.PAIR_PERIOD_MS)
                val active = engine ?: break
                publish(active.tick(PrecorChronoEncoder.PAIR_PERIOD_MS / 1000.0))
            }
        }
        Timber.tag(TAG).i("Precor Chrono simulator started")
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

        sessionProjection.begin(haveControl = false)
        FitBridgeWakeLock.acquireCpu(appContext, wakeLockOwner)
        _state.value = State(
            running = true,
            persona = persona,
            machine = FtmsMachineProfile.TREADMILL,
            controlsEnabled = false,
            controlsAvailable = false,
            advertising = peripheral.isAdvertising,
        )
        publish(mockEngine.snapshot())
        job = scope.launch {
            while (isActive) {
                delay(StrydFootpodEncoder.MEASUREMENT_PERIOD_MS)
                val active = engine ?: break
                publish(active.tick(StrydFootpodEncoder.MEASUREMENT_PERIOD_MS / 1000.0))
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
        val periodMs = persona.profile.samplePeriodMs
        job = scope.launch {
            while (isActive) {
                delay(periodMs)
                val active = engine ?: break
                publish(active.tick(periodMs / 1000.0))
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

        sessionProjection.begin(haveControl = persona.controlEnabled)
        FitBridgeWakeLock.acquireCpu(appContext, wakeLockOwner)
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
        sessionProjection.begin(haveControl = false)
        FitBridgeWakeLock.acquireCpu(appContext, wakeLockOwner)
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
        val periodMs = SchwinnX70Ride.PAIR_PERIOD_MS
        job = scope.launch {
            while (isActive) {
                delay(periodMs)
                val active = engine ?: break
                publish(active.tick(periodMs / 1000.0))
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

        sessionProjection.begin(haveControl = false)
        FitBridgeWakeLock.acquireCpu(appContext, wakeLockOwner)
        _state.value = State(
            running = true,
            persona = persona,
            machine = persona.machine.profile,
            controlsEnabled = false,
            controlsAvailable = false,
            advertising = peripheral.isAdvertising,
        )
        publish(lifeFitnessVisibleSample(persona.machine, mockEngine.snapshot()))
        job = scope.launch {
            while (isActive) {
                delay(LIFE_FITNESS_PERIOD_MS)
                val active = engine ?: break
                publish(lifeFitnessVisibleSample(persona.machine, active.tick(LIFE_FITNESS_PERIOD_MS / 1000.0)))
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
            externalBpm = engineState.externalHeartRate.value,
            lastExternalAtMs = engineState.lastExternalAt,
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
            BluetoothIds.UUID_X70_SUPPLEMENTAL to session.encoder.encodeSupplemental(state),
            BluetoothIds.UUID_X70_SIBLING to session.encoder.encodeSibling(state),
            BluetoothIds.UUID_X70_MEASUREMENT to session.encoder.encodeMain(state),
        )
        session.encoder.completeTick()
        peripheral.publishSchwinnX70(frames)
    }

    /**
     * Write one sample into [BtEngineState].
     *
     * Deliberately mirrors what the FTMS parsers do for a real machine, including
     * leaving a metric untouched when this persona does not produce it - a
     * simulated rower must not publish an inclination any more than a real one
     * would.
     */
    private fun publish(generated: FtmsMockSample) {
        // A live strap attached to the central overrides the generated heart rate, applied here
        // rather than in each encoder because this is the last point before the standard-FTMS path
        // (engineState -> EngineMetricPump) and the proprietary path (straight to the encoders)
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
        if (_state.value.persona == SimulatorPersona.StrydFootpod) {
            peripheral.publishStrydFootpod(sample)
        }
        if (_state.value.persona is SimulatorPersona.LifeFitness) {
            peripheral.publishLifeFitness(sample)
        }
        schwinnX70?.let { session -> publishSchwinnX70Frames(session, sample) }
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

        /** Capture corpus median is 1.02 seconds; one second preserves the physical cadence. */
        const val LIFE_FITNESS_PERIOD_MS = 1_000L

        /**
         * The mock engine's indoor-bike speed that maps to a persona's captured peak.
         *
         * Scaling rather than clamping keeps the ride's shape on a console whose captured peak is
         * 17.9 km/h as well as one that reaches 26.3. Clamping would flatten the faster half of
         * every 270 session into a constant.
         */

        private const val MILES_TO_KM = 1.609344
    }
}
