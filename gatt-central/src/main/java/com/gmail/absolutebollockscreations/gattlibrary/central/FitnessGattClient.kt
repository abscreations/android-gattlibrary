package com.gmail.absolutebollockscreations.gattlibrary.central

import android.content.Context
import com.gmail.absolutebollockscreations.gattlibrary.core.BleConfig
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

    public fun close(): Unit = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        scanGeneration++
        timeout?.cancel()
        try { backend.close() } finally {
            scope.cancel()
            mutableDiscovery.value = DiscoverySnapshot(false, false, emptyList(), null)
            mutableMeasurements.value = MeasurementSnapshot.EMPTY
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
        if (!sameReferences(discoveryInputs, currentDiscoveryInputs())) mutableDiscovery.value = discoverySnapshot()
        if (!sameReferences(connectionInputs, currentConnectionInputs())) mutableConnections.value = connectionSnapshot()
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
}
