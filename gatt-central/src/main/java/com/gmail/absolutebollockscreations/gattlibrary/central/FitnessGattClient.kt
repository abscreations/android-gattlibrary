package com.gmail.absolutebollockscreations.gattlibrary.central

import android.content.Context
import com.gmail.absolutebollockscreations.gattlibrary.core.ActivityType
import com.gmail.absolutebollockscreations.gattlibrary.core.BleConfig
import com.gmail.absolutebollockscreations.gattlibrary.core.HrZoneMode
import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.KayakFirstHandshakeVariant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Central discovery/connection owner. Metrics and commands migrate in later bounded units. */
public class FitnessGattClient internal constructor(
    private val backend: CentralConnectionBackend,
    private val scope: CoroutineScope,
    /**
     * Runs the measurement collector. Deliberately not the client's own scope dispatcher, which is the
     * main thread in production: the peripheral bridge samples [measurements] while the UI may be busy,
     * and a main-thread hop would delay its data. One serialized slot on the shared default pool gives
     * the collector a thread it does not have to wait for the UI to release, without a parked thread.
     */
    measurementDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    private val lock = Any()
    private val projection = DiscoveryProjection(Any())
    private var closed = false
    private var timeout: Job? = null
    private var scanGeneration = 0L
    // Source-value references behind the last published snapshots. Scan packets change only
    // discovery; without this, every packet would also re-emit an unchanged connection snapshot.
    private var discoveryInputs: List<Any?> = emptyList()
    private var connectionInputs: List<Any?> = emptyList()
    private val mutableDiscovery = MutableStateFlow(discoverySnapshot())
    private val mutableConnections = MutableStateFlow(connectionSnapshot())
    public val discovery: StateFlow<DiscoverySnapshot> = mutableDiscovery.asStateFlow()
    public val connections: StateFlow<ConnectionSnapshot> = mutableConnections.asStateFlow()
    private val mutableMeasurements = MutableStateFlow(backend.state.toMeasurementSnapshot())

    /** Fused metrics of the connected machine in canonical units; fields are null while absent (unit 4a). */
    public val measurements: StateFlow<MeasurementSnapshot> = mutableMeasurements.asStateFlow()
    private val mutableHistory = MutableStateFlow(backend.state.history.value)

    /**
     * Every chart series recorded this session, one immutable [MeasurementHistory] per change. Views are
     * O(1) to take and never change afterwards; collect it for graphs and read the series you need. A
     * session start or reset replaces it with an empty history, and closing the client empties it.
     */
    public val history: StateFlow<MeasurementHistory> = mutableHistory.asStateFlow()
    private val mutableCapabilities = MutableStateFlow(backend.state.deviceCapabilities.value)

    /**
     * What the connected machine can be asked to do, in canonical units; empty while no machine is
     * connected. See [DeviceCapabilities] for what it includes and leaves out.
     */
    public val capabilities: StateFlow<DeviceCapabilities> = mutableCapabilities.asStateFlow()
    /**
     * What the machine reports about its own control state, as it happens (see [ControlEvent]). Nothing
     * is replayed to a late collector. The flow ends for a collector once this client is closed.
     */
    public val controlEvents: Flow<ControlEvent> =
        backend.state.controlEvents.takeWhile { synchronized(lock) { !closed } }
    private val tokenOwner = Any()
    private val mutableMachineToken = MutableStateFlow(backend.currentMachineKey()?.let { MachineToken(tokenOwner, it) })

    /**
     * The machine commands are addressed to, or null while none is connected or selected. It changes
     * whenever the machine does, including a reconnect to the same address, so a command prepared
     * for the old machine is refused instead of reaching the new one.
     */
    public val machineToken: StateFlow<MachineToken?> = mutableMachineToken.asStateFlow()
    private val mutableSessionActive = MutableStateFlow(backend.state.activeSession.value)

    /** Whether a workout session is active. Read-only for now; session control arrives with typed commands. */
    public val sessionActive: StateFlow<Boolean> = mutableSessionActive.asStateFlow()
    private val mutableInferredActivity = MutableStateFlow(backend.state.inferredActivity.value)

    /** MAXPRO-only raw and derived telemetry, separate from measurements and downstream capabilities. */
    public val maxPro: StateFlow<MaxProState> get() = backend.state.maxPro

    /** Start a new display observation window without clearing the device or any standard session. */
    public fun resetMaxProActivity(): Unit = whenOpen { backend.state.maxProStore.resetActivity() }

    /**
     * The attached heart-rate strap's own reading, or null when none has reported.
     *
     * Deliberately not [MeasurementSnapshot.heartRate], which is the fused value and may come from
     * the machine or a local generator. This is populated only from a source classified as a
     * heart-rate monitor, so a consumer can tell a strap from the machine's own figure.
     */
    public val externalHeartRate: StateFlow<Int?> get() = backend.state.externalHeartRate

    /** Wall-clock milliseconds at which [externalHeartRate] last updated; zero means never. */
    public val externalHeartRateUpdatedAtMs: Long get() = backend.state.lastExternalAt

    /**
     * The activity the connected machine was classified as, which is the default persona for a
     * downstream bridge (a ski machine classifies as rowing, for example); null while no machine is
     * classified. Never [ActivityType.HEARTRATE].
     */
    public val inferredActivity: StateFlow<ActivityType?> = mutableInferredActivity.asStateFlow()

    init {
        scope.launch {
            merge(
                backend.state.discoveredDevices.map { Unit }, backend.state.isScanning.map { Unit },
                backend.discoveryRequested.map { Unit }, backend.scanFailure.map { Unit },
                backend.state.connectedDevices.map { Unit }, backend.state.activeAdvertisementMachine.map { Unit },
                backend.state.connectionState.map { Unit }, backend.state.machineProbe.map { Unit },
                backend.state.cccdReady.map { Unit },
            ).collect { synchronized(lock) { if (!closed) refresh() } }
        }
        scope.launch(measurementDispatcher) {
            // StateFlow drops equal snapshots, so redundant engine pings cost one projection, not a re-emission.
            merge(*backend.state.measurementChanges().toTypedArray()).collect {
                synchronized(lock) { if (!closed) mutableMeasurements.value = backend.state.toMeasurementSnapshot() }
            }
        }
        scope.launch(measurementDispatcher) {
            // The recorder already publishes one immutable history per change, so this only forwards it.
            backend.state.history.collect { latest ->
                synchronized(lock) { if (!closed) mutableHistory.value = latest }
            }
        }
        scope.launch(measurementDispatcher) {
            backend.state.deviceCapabilities.collect { latest ->
                synchronized(lock) { if (!closed) mutableCapabilities.value = latest }
            }
        }
        scope.launch(measurementDispatcher) {
            backend.state.activeSession.collect { latest ->
                synchronized(lock) { if (!closed) mutableSessionActive.value = latest }
            }
        }
        scope.launch(measurementDispatcher) {
            backend.state.inferredActivity.collect { latest ->
                synchronized(lock) { if (!closed) mutableInferredActivity.value = latest }
            }
        }
    }

    public fun startScan(options: ScanOptions = ScanOptions()): OperationResult = operation(scan = true) {
        backend.startScan(options.useHardwareFilters)
        val generation = ++scanGeneration
        timeout?.cancel()
        if (!backend.state.isScanning.value) {
            backend.stopScan()
            return@operation OperationFailure.REJECTED
        }
        timeout = scope.launch {
            delay(options.durationMillis)
            synchronized(lock) {
                // A cancelled timer may already be waiting for the lock while a new scan starts.
                if (!closed && generation == scanGeneration) stopScan()
            }
        }
        null
    }

    /** Releases discovery only; advertisement telemetry may still own the radio. */
    public fun stopScan(): OperationResult = operation(checkPlatform = false) {
        scanGeneration++
        timeout?.cancel()
        timeout = null
        backend.stopScan()
        null
    }

    public fun clearDiscoveredDevices(): OperationResult = operation(checkPlatform = false) {
        backend.state.putDiscoveredDevices(emptyMap())
        null
    }

    public fun selectMachine(device: DeviceHandle): OperationResult = operation(scan = device.transport == DeviceTransport.ADVERTISEMENT_ONLY) {
        when {
            !projection.accepts(device) -> OperationFailure.STALE_HANDLE
            // An unresolved row has no address to route to; never guess one.
            device.address.isBlank() -> OperationFailure.REJECTED
            else -> backend.selectMachine(device.address).failureIfFalse()
        }
    }

    public fun connectSensor(device: DeviceHandle, assignment: SensorAssignment): OperationResult = operation {
        when {
            !projection.accepts(device) -> OperationFailure.STALE_HANDLE
            device.transport != DeviceTransport.GATT -> OperationFailure.UNSUPPORTED
            device.address.isBlank() -> OperationFailure.REJECTED
            else -> backend.connectSensor(device.address, assignment).failureIfFalse()
        }
    }

    public fun activateSensorService(link: LinkHandle, assignment: SensorAssignment): OperationResult = operation {
        if (!projection.accepts(link)) OperationFailure.STALE_HANDLE
        else backend.activateService(link.key, assignment).failureIfFalse()
    }

    /** Disconnects every service on this physical-link generation. */
    public fun disconnect(link: LinkHandle): OperationResult = operation {
        if (!projection.accepts(link)) OperationFailure.STALE_HANDLE
        else backend.disconnect(link.key).failureIfFalse()
    }

    public fun deselectMachine(): OperationResult = operation(checkPlatform = false) {
        backend.deselectMachine().failureIfFalse()
    }

    /**
     * Profile fields a connected Life Fitness machine asked for and did not get. Empty means no
     * prompt is owed. The values are field identities, never the values behind them. Not cleared
     * on [close]; it belongs to the engine the client wraps.
     */
    public val lifeFitnessProfilePrompt: StateFlow<List<LifeFitnessProfileField>> get() = backend.config.lifeFitnessProfilePrompt

    /** Progress of the Kayak First setup handshake, or null when none is running. */
    public val kayakFirstSetupProgress: StateFlow<KayakFirstSetupProgress?> get() = backend.config.kayakFirstSetupProgress

    /** Heart-rate zone inputs, applied together so zones are recalculated from one consistent set. */
    public fun setHeartRateProfile(maxHr: Int?, restingHr: Int?, zoneMode: HrZoneMode): Unit =
        whenOpen { backend.config.setHeartRateProfile(maxHr, restingHr, zoneMode) }

    /** CSC wheel circumference and elliptical stride length, in millimetres, for the next session. */
    public fun setCscCalibration(wheelCircumferenceMm: Int, ellipticalStrideLengthMm: Int): Unit =
        whenOpen { backend.config.setCscCalibration(wheelCircumferenceMm, ellipticalStrideLengthMm) }

    /** Life Fitness handshake profile from the settings screen. */
    public fun setLifeFitnessProfile(profile: LifeFitnessProfile, ageYears: Int): Unit =
        whenOpen { backend.config.setLifeFitnessProfile(profile, ageYears) }

    /** Dismiss the Life Fitness profile prompt. The connection is unaffected. */
    public fun dismissLifeFitnessProfilePrompt(): Unit = whenOpen { backend.config.dismissLifeFitnessProfilePrompt() }

    /** Set before selecting a Kayak First machine; applies to the next link generation. */
    public fun setKayakFirstSportMode(mode: KayakFirstSportMode): Unit =
        whenOpen { backend.config.setKayakFirstSportMode(mode) }

    /** Set before selecting a Kayak First machine; applies to the next link generation. */
    public fun setKayakFirstHandshakeVariant(variant: KayakFirstHandshakeVariant): Unit =
        whenOpen { backend.config.setKayakFirstHandshakeVariant(variant) }

    /** Set before selecting a Kayak First machine; applies to the next link generation. */
    public fun setKayakFirstDisplayConfiguration(configuration: KayakFirstDisplayConfiguration): Unit =
        whenOpen { backend.config.setKayakFirstDisplayConfiguration(configuration) }

    /**
     * Discards accumulated history and running averages ahead of a new run. Keeps totals, elapsed
     * time, laps already recorded and whether a session is active.
     */
    public fun clearSessionData(): Unit = whenOpen { backend.config.clearSessionData() }

    /** Asks the user to turn Bluetooth on if it is off. Needs the Bluetooth connect permission on API 31+. */
    public fun ensureBluetoothEnabled(activity: android.app.Activity): Unit =
        whenOpen { backend.config.ensureBluetoothEnabled(activity) }

    /** The identity of the machine now (see [MachineIdentity]), or null when there is none. Null after [close]. */
    public fun machineIdentity(): MachineIdentity? =
        if (synchronized(lock) { closed }) null else backend.machineIdentity()

    /** Requests control of the machine. [CommandResult.Queued] means submitted, not that control was granted. */
    public fun requestControl(token: MachineToken): CommandResult = submit(token, MachineCommand.RequestControl)

    private inline fun whenOpen(action: () -> Unit) {
        if (synchronized(lock) { closed }) return
        action()
    }

    /**
     * Opens a workout session: persona for fusion, elapsed clock, link recovery for the connected
     * links, and [sessionActive]. The session is tied to the machine that is connected now, if any.
     * Nothing happens after [close].
     */
    public fun beginSession(activity: ActivityType) {
        val address = synchronized(lock) {
            if (closed) return
            refresh()
            mutableConnections.value.links.firstOrNull { it.machineConnection }?.address
        }
        backend.beginSession(activity, address)
    }

    /** Ends the workout session and clears its data, including elapsed time and laps. Nothing happens after [close]. */
    public fun endSession() {
        if (synchronized(lock) { closed }) return
        backend.endSession()
    }

    /**
     * The app's own Start. For a machine that runs its own session (Kayak First) this starts that
     * session; otherwise it is the machine's Start/Resume. [CommandResult.Queued] means submitted.
     * This is distinct from [startOrResume], which relays a downstream client's request.
     */
    public fun startRun(token: MachineToken): CommandResult = submit(token, MachineCommand.StartRun)

    /** The app's own Stop; see [startRun]. */
    public fun stopRun(token: MachineToken): CommandResult = submit(token, MachineCommand.StopRun)

    /** Start, or resume after a pause. [CommandResult.Queued] means submitted, not that the machine started. */
    public fun startOrResume(token: MachineToken): CommandResult = submit(token, MachineCommand.StartOrResume)

    /** Stop or pause; the machine's control path has a single operation for both. */
    public fun stopOrPause(token: MachineToken): CommandResult = submit(token, MachineCommand.StopOrPause)

    /** Target speed in km/h. */
    public fun setTargetSpeedKph(token: MachineToken, kph: Float): CommandResult =
        submit(token, MachineCommand.TargetSpeedKph(kph))

    /** Target incline in percent. */
    public fun setTargetInclinePercent(token: MachineToken, percent: Float): CommandResult =
        submit(token, MachineCommand.TargetInclinePercent(percent))

    /** Target resistance in the machine's own level units, as [DeviceCapabilities.resistanceRange] reports them. */
    public fun setTargetResistanceLevel(token: MachineToken, level: Float): CommandResult =
        submit(token, MachineCommand.TargetResistanceLevel(level))

    /** Target power in watts. */
    public fun setTargetPowerWatts(token: MachineToken, watts: Int): CommandResult =
        submit(token, MachineCommand.TargetPowerWatts(watts))

    /** Wheel circumference in millimetres, for machines that take it. */
    public fun setWheelCircumferenceMm(token: MachineToken, millimetres: Float): CommandResult =
        submit(token, MachineCommand.WheelCircumferenceMm(millimetres))

    /** Indoor-bike simulation: wind in m/s, grade in percent, and the two unitless coefficients. */
    public fun setIndoorBikeSimulation(
        token: MachineToken,
        windSpeedMps: Float,
        gradePercent: Float,
        rollingResistanceCoefficient: Float,
        windResistanceCoefficient: Float,
    ): CommandResult = submit(
        token,
        MachineCommand.IndoorBikeSimulation(
            windSpeedMps, gradePercent, rollingResistanceCoefficient, windResistanceCoefficient,
        ),
    )

    /**
     * Not under the client lock: the engine call can take a moment and must not stall discovery or
     * snapshot publication. The check against the current machine and the dispatch happen together
     * in the backend, so a command is never sent to a machine other than the one its token names.
     */
    private fun submit(token: MachineToken, command: MachineCommand): CommandResult {
        if (synchronized(lock) { closed }) return CommandResult.Rejected(RejectionReason.CLIENT_CLOSED)
        if (token.owner !== tokenOwner) return CommandResult.MachineChanged
        val accepted = backend.submit(token.key, command) ?: return CommandResult.MachineChanged
        return if (accepted) CommandResult.Queued else CommandResult.Rejected(RejectionReason.NOT_ACCEPTED)
    }

    public fun close(): Unit = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        scanGeneration++
        timeout?.cancel()
        try { backend.close() } finally {
            scope.cancel()
            mutableDiscovery.value = DiscoverySnapshot(false, false, emptyList(), null)
            mutableMeasurements.value = MeasurementSnapshot.EMPTY
            mutableHistory.value = MeasurementHistory.EMPTY
            mutableCapabilities.value = DeviceCapabilities.NONE
            mutableSessionActive.value = false
            mutableInferredActivity.value = null
            mutableMachineToken.value = null
            mutableConnections.value = ConnectionSnapshot(emptyList(), null, ConnectionStatus.DISCONNECTED, MachineProbeState.IDLE, false)
        }
    }

    private fun operation(
        scan: Boolean = false,
        checkPlatform: Boolean = true,
        action: () -> OperationFailure?,
    ): OperationResult = synchronized(lock) {
        if (closed) return@synchronized OperationResult(OperationFailure.CLOSED)
        // Validate against live engine state, even when the projection collector has not run yet.
        refresh()
        val failure = try {
            (if (checkPlatform) backend.platformFailure(scan) else null) ?: action()
        } catch (_: SecurityException) {
            OperationFailure.PERMISSION_REQUIRED
        }
        refresh()
        OperationResult(failure)
    }

    private fun refresh() {
        updateMachineToken()
        if (!sameReferences(discoveryInputs, currentDiscoveryInputs())) mutableDiscovery.value = discoverySnapshot()
        if (!sameReferences(connectionInputs, currentConnectionInputs())) mutableConnections.value = connectionSnapshot()
    }

    private fun updateMachineToken() {
        val key = backend.currentMachineKey()
        if (key == mutableMachineToken.value?.key) return
        mutableMachineToken.value = key?.let { MachineToken(tokenOwner, it) }
    }

    private fun currentDiscoveryInputs(): List<Any?> = listOf(
        backend.discoveryRequested.value, backend.state.isScanning.value,
        backend.state.discoveredDevices.value, backend.scanFailure.value,
    )

    private fun currentConnectionInputs(): List<Any?> = listOf(
        backend.state.connectedDevices.value, backend.state.activeAdvertisementMachine.value,
        backend.state.connectionState.value, backend.state.machineProbe.value, backend.state.cccdReady.value,
    )

    @Suppress("UNCHECKED_CAST")
    private fun discoverySnapshot(): DiscoverySnapshot {
        val inputs = currentDiscoveryInputs()
        discoveryInputs = inputs
        return DiscoverySnapshot(
            inputs[0] as Boolean, inputs[1] as Boolean,
            projection.deviceRows(inputs[2] as Map<String, BtEngineState.DiscoveredDevice>), inputs[3] as Int?,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun connectionSnapshot(): ConnectionSnapshot {
        val inputs = currentConnectionInputs()
        connectionInputs = inputs
        return ConnectionSnapshot(
            projection.linkRows(inputs[0] as List<BtEngineState.ConnectedDeviceLink>),
            (inputs[1] as BtEngineState.ActiveAdvertisementMachine?)?.toConsumer(),
            connectionStatus(inputs[2] as Int), inputs[3] as MachineProbeState, inputs[4] as Boolean,
        )
    }

    public companion object {
        public fun create(
            context: Context,
            config: BleConfig = BleConfig(),
            handlers: HandlerSet = HandlerSet.all(),
        ): FitnessGattClient = BtEngine.create(context.applicationContext, config, handlers).discoveryClient
    }
}

/**
 * Engine collections are replaced, never mutated, so identity detects any change without an O(n)
 * comparison per scan packet. Scalars and value records compare by value (boxing is not stable).
 */
private fun sameReferences(previous: List<Any?>, current: List<Any?>): Boolean =
    previous.size == current.size && previous.indices.all { i ->
        val before = previous[i]
        val after = current[i]
        if (before is Collection<*> || before is Map<*, *>) before === after else before == after
    }

private fun Boolean.failureIfFalse(): OperationFailure? = if (this) null else OperationFailure.REJECTED

/** Test seam stays inside central; no fake GATT or mutable state escapes the public client. */
internal interface CentralConnectionBackend {
    val state: BtEngineState
    val discoveryRequested: StateFlow<Boolean>
    val scanFailure: StateFlow<Int?>
    fun platformFailure(scan: Boolean): OperationFailure?
    fun startScan(hardwareFilters: Boolean)
    fun stopScan()
    fun selectMachine(address: String): Boolean
    fun connectSensor(address: String, assignment: SensorAssignment): Boolean
    fun activateService(key: String, assignment: SensorAssignment): Boolean
    fun disconnect(key: String): Boolean
    fun deselectMachine(): Boolean
    fun close()

    /** Identity of the machine commands would reach right now, or null if none is connected or selected. */
    fun currentMachineKey(): Any?

    /**
     * Send [command] to the machine only if [expectedKey] still names it. Returns null when it does
     * not (nothing sent), otherwise whether the machine's control path accepted the submission.
     */
    fun submit(expectedKey: Any, command: MachineCommand): Boolean?

    /** Settings and setup state the app pushes to or reads from the engine. */
    val config: CentralConfigBackend

    /** Identity of the machine now, or null when there is no machine link and no advertisement-only machine. */
    fun machineIdentity(): MachineIdentity?

    /** Open a workout session for [activity] on the machine at [machineAddress] (null for sensors only). */
    fun beginSession(activity: ActivityType, machineAddress: String?)

    /** End the workout session and clear its data. */
    fun endSession()
}

/** Settings the app sets on the engine, and the setup state it reads back. Test seam, like the backend. */
internal interface CentralConfigBackend {
    val lifeFitnessProfilePrompt: StateFlow<List<LifeFitnessProfileField>>
    val kayakFirstSetupProgress: StateFlow<KayakFirstSetupProgress?>
    fun setHeartRateProfile(maxHr: Int?, restingHr: Int?, zoneMode: HrZoneMode)
    fun setCscCalibration(wheelCircumferenceMm: Int, ellipticalStrideLengthMm: Int)
    fun setLifeFitnessProfile(profile: LifeFitnessProfile, ageYears: Int)
    fun dismissLifeFitnessProfilePrompt()
    fun setKayakFirstSportMode(mode: KayakFirstSportMode)
    fun setKayakFirstHandshakeVariant(variant: KayakFirstHandshakeVariant)
    fun setKayakFirstDisplayConfiguration(configuration: KayakFirstDisplayConfiguration)
    fun clearSessionData()
    fun ensureBluetoothEnabled(activity: android.app.Activity)
}
