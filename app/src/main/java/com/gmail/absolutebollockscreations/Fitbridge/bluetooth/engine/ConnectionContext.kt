package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.UUID

/** Identifies one physical-link lifetime, including reconnects to the same BLE address. */
internal data class ConnectionId(
    val address: String,
    val linkGeneration: Long,
)

internal enum class LinkState(val bluetoothProfileState: Int) {
    DISCONNECTED(BluetoothProfile.STATE_DISCONNECTED),
    CONNECTING(BluetoothProfile.STATE_CONNECTING),
    CONNECTED(BluetoothProfile.STATE_CONNECTED),
    DISCONNECTING(BluetoothProfile.STATE_DISCONNECTING),
    ;

    companion object {
        fun fromBluetoothProfileState(state: Int): LinkState =
            entries.firstOrNull { it.bluetoothProfileState == state } ?: DISCONNECTED
    }
}

/** Serialized operation state owned by one physical GATT link. */
internal class GattQueue {
    private val lock: Any = Any()
    private val entries: ArrayDeque<StackEntry> = ArrayDeque()
    private var writeBusy: Boolean = false

    val size: Int get() = synchronized(lock) { entries.size }
    val isBusy: Boolean get() = synchronized(lock) { writeBusy }

    fun enqueue(entry: StackEntry): Int = synchronized(lock) {
        entries.addLast(entry)
        entries.size
    }

    fun beginDispatch(): StackEntry? = synchronized(lock) {
        if (entries.isEmpty() || writeBusy) return@synchronized null
        writeBusy = true
        entries.first()
    }

    fun head(): StackEntry? = synchronized(lock) { entries.firstOrNull() }

    fun isInFlight(entry: StackEntry): Boolean = synchronized(lock) {
        writeBusy && entries.firstOrNull() === entry
    }

    fun finishHead(): StackEntry? = synchronized(lock) {
        val finished = entries.removeFirstOrNull()
        writeBusy = false
        finished
    }

    fun finishIfHead(expected: StackEntry): StackEntry? = synchronized(lock) {
        if (!writeBusy || entries.firstOrNull() !== expected) return@synchronized null
        entries.removeFirst().also { writeBusy = false }
    }

    fun clear() = synchronized(lock) {
        entries.clear()
        writeBusy = false
    }
}

/** Full service/characteristic identity for one CCCD enable request. */
internal data class CccdTarget(
    val serviceUuid: UUID,
    val characteristicUuid: UUID,
)

/** CCCD enable plan, readiness, and recovery state owned by one physical GATT link. */
internal class CccdState {
    private val lock = Any()
    val ready = MutableStateFlow(false)
    private val enablePlan = linkedSetOf<CccdTarget>()
    private var enablePlanFailed = false

    private var pendingWritesValue: Int = 0
    val pendingWrites: Int get() = synchronized(lock) { pendingWritesValue }

    private var recoveryAttemptsValue: Int = 0
    val recoveryAttempts: Int get() = synchronized(lock) { recoveryAttemptsValue }
    val plannedEnableCount: Int get() = synchronized(lock) { enablePlan.size }

    /** Merge in stable order and return only targets not already present in this context's plan. */
    fun mergeEnablePlan(targets: Iterable<CccdTarget>): List<CccdTarget> = synchronized(lock) {
        buildList {
            targets.forEach { target ->
                if (enablePlan.add(target)) add(target)
            }
        }
    }

    fun enablePlanSnapshot(): List<CccdTarget> = synchronized(lock) { enablePlan.toList() }

    fun removeEnableTarget(target: CccdTarget) = synchronized(lock) {
        enablePlan.remove(target)
    }

    fun markEnablePlanFailed() = synchronized(lock) {
        enablePlanFailed = true
        ready.value = false
    }

    fun setReady(value: Boolean) = synchronized(lock) {
        ready.value = value
    }

    fun incrementPending(): Int = synchronized(lock) {
        pendingWritesValue += 1
        pendingWritesValue
    }

    /** Returns true only when this completion transitions the context to ready. */
    fun completePending(): Boolean = synchronized(lock) {
        if (pendingWritesValue <= 0) return@synchronized false
        pendingWritesValue -= 1
        if (pendingWritesValue == 0 && !enablePlanFailed) {
            ready.value = true
            true
        } else {
            false
        }
    }

    fun resetOrchestration(clearPlan: Boolean = false) = synchronized(lock) {
        pendingWritesValue = 0
        enablePlanFailed = false
        ready.value = false
        if (clearPlan) enablePlan.clear()
    }

    fun nextRecoveryAttempt(): Int = synchronized(lock) {
        recoveryAttemptsValue += 1
        recoveryAttemptsValue
    }

    fun resetRecoveryAttempts() = synchronized(lock) {
        recoveryAttemptsValue = 0
    }

    fun reset() = synchronized(lock) {
        pendingWritesValue = 0
        recoveryAttemptsValue = 0
        enablePlanFailed = false
        ready.value = false
        enablePlan.clear()
    }
}

/** In-session link recovery state; intentionally empty until recovery moves in Phase 0.3. */
internal class LinkRecoveryState

/** All state whose lifetime is one physical BLE connection. */
internal class ConnectionContext(
    val connectionId: ConnectionId,
    val address: String,
    val machineConnection: Boolean = false,
    var gatt: BluetoothGatt?,
    val connectionState: MutableStateFlow<LinkState>,
    val discoveredServices: MutableList<BluetoothGattService> = mutableListOf(),
    val discoveredServiceUuids: MutableSet<UUID> = linkedSetOf(),
    val handlers: MutableList<BtEngineGattCore.ProtocolHandler> = mutableListOf(),
    val queue: GattQueue = GattQueue(),
    val cccd: CccdState = CccdState(),
    var linkRecovery: LinkRecoveryState? = null,
) {
    /** Callback-owned, idempotent termination entry point used by explicit disconnects. */
    @Volatile
    var terminationHook: ((reason: String) -> Unit)? = null

    /** Callback-owned hook for deliberately activating another handler on this physical link. */
    @Volatile
    var handlerActivator: ((BtEngineGattCore.ProtocolHandler) -> Boolean)? = null

    @Volatile
    var isActive: Boolean = true
        internal set
}

internal fun canonicalConnectionAddress(address: String): String =
    address.trim().uppercase(Locale.ROOT)

internal fun BluetoothGatt.safeAddress(): String = runCatching {
    device?.address.orEmpty()
}.getOrDefault("")

/**
 * Compatibility registry for the two existing public connection APIs. Callbacks are bound to
 * [ConnectionContext] identity; roles only select the legacy UI projections.
 */
internal class ConnectionRegistry {
    private val lock = Any()
    private val contexts = linkedMapOf<ConnectionId, ConnectionContext>()
    private val roleOwners = mutableMapOf<LegacyConnectionRole, ConnectionId>()
    private var nextLinkGeneration = 1L
    private val _machineConnectionState =
        MutableStateFlow(BluetoothProfile.STATE_DISCONNECTED)
    val machineConnectionState: StateFlow<Int> = _machineConnectionState
    private val _hrmConnectionState =
        MutableStateFlow(BluetoothProfile.STATE_DISCONNECTED)
    val hrmConnectionState: StateFlow<Int> = _hrmConnectionState
    private val _machineCccdReady = MutableStateFlow(false)
    val machineCccdReady: StateFlow<Boolean> = _machineCccdReady
    private val _connectedDevices =
        MutableStateFlow<List<BtEngineState.ConnectedDeviceLink>>(emptyList())
    val connectedDevices: StateFlow<List<BtEngineState.ConnectedDeviceLink>> = _connectedDevices

    fun current(role: LegacyConnectionRole): ConnectionContext? = synchronized(lock) {
        roleOwners[role]?.let(contexts::get)
    }

    fun contextForGatt(gatt: BluetoothGatt): ConnectionContext? = synchronized(lock) {
        contexts.values.firstOrNull { it.gatt === gatt }
    }

    fun contextsForAddress(address: String): List<ConnectionContext> = synchronized(lock) {
        val canonicalAddress = canonicalConnectionAddress(address)
        contexts.values.filter { it.isActive && it.address == canonicalAddress }
    }

    fun contextForKey(connectionKey: String): ConnectionContext? = synchronized(lock) {
        contexts.values.firstOrNull { it.connectionKey == connectionKey }
    }

    fun activateHandlerOn(
        address: String,
        handler: BtEngineGattCore.ProtocolHandler,
    ): Boolean {
        val matchingContexts = contextsForAddress(address)
        val alreadyActive = matchingContexts.any { context ->
            context.handlers.any { active ->
                active.primaryServices.any(handler.primaryServices::contains)
            }
        }
        if (alreadyActive) return true

        val target = matchingContexts.firstOrNull { context ->
            context.discoveredServiceUuids.any(handler.primaryServices::contains)
        } ?: return false
        val activated = target.handlerActivator?.invoke(handler) == true
        if (activated) synchronized(lock) { publishConnectedDevices() }
        return activated
    }

    fun roleOf(context: ConnectionContext): LegacyConnectionRole? = synchronized(lock) {
        roleOwners.entries.firstOrNull { it.value == context.connectionId }?.key
    }

    fun isCurrent(context: ConnectionContext): Boolean = synchronized(lock) {
        context.isActive && contexts[context.connectionId] === context
    }

    fun open(
        role: LegacyConnectionRole,
        address: String,
        gatt: BluetoothGatt?,
        initialState: Int,
    ): ConnectionContext = synchronized(lock) {
        roleOwners[role]?.let { existingId ->
            contexts.remove(existingId)?.deactivate()
        }
        val context = createContext(
            address = address,
            gatt = gatt,
            initialState = initialState,
            machineConnection = role == LegacyConnectionRole.FTMS,
        )
        contexts[context.connectionId] = context
        roleOwners[role] = context.connectionId
        connectionProjection(role).value = initialState
        if (role == LegacyConnectionRole.FTMS) _machineCccdReady.value = false
        publishConnectedDevices()
        context
    }

    /** Open another physical sensor link without replacing an existing sensor connection. */
    fun openAdditional(
        address: String,
        gatt: BluetoothGatt?,
        initialState: Int,
    ): ConnectionContext = synchronized(lock) {
        val context = createContext(
            address = address,
            gatt = gatt,
            initialState = initialState,
            machineConnection = false,
        )
        contexts[context.connectionId] = context
        if (roleOwners[LegacyConnectionRole.HRM] == null) {
            roleOwners[LegacyConnectionRole.HRM] = context.connectionId
            _hrmConnectionState.value = initialState
        }
        publishConnectedDevices()
        context
    }

    fun bind(
        role: LegacyConnectionRole,
        address: String,
        gatt: BluetoothGatt,
        initialState: Int,
    ): ConnectionContext = synchronized(lock) {
        val current = current(role)
        if (current?.gatt === gatt) return@synchronized current
        open(role, address, gatt, initialState)
    }

    /** Attach the platform GATT returned after a callback was created for [context]. */
    fun attach(context: ConnectionContext, gatt: BluetoothGatt): Boolean = synchronized(lock) {
        if (!context.isActive || contexts[context.connectionId] !== context) return@synchronized false
        context.gatt = gatt
        publishConnectedDevices()
        true
    }

    fun clear(role: LegacyConnectionRole): ConnectionContext? = synchronized(lock) {
        roleOwners.remove(role)?.let(contexts::remove)?.also { context ->
            context.deactivate()
            connectionProjection(role).value = BluetoothProfile.STATE_DISCONNECTED
            if (role == LegacyConnectionRole.FTMS) _machineCccdReady.value = false
            publishConnectedDevices()
        }
    }

    /** Clear only if [context] is still the context occupying its compatibility slot. */
    fun clear(context: ConnectionContext): Boolean = synchronized(lock) {
        if (contexts.remove(context.connectionId) !== context) return@synchronized false
        val role = roleOwners.entries.firstOrNull { it.value == context.connectionId }?.key
        if (role != null) roleOwners.remove(role)
        context.deactivate()
        role?.let { compatibilityRole ->
            connectionProjection(compatibilityRole).value = BluetoothProfile.STATE_DISCONNECTED
            if (compatibilityRole == LegacyConnectionRole.FTMS) _machineCccdReady.value = false
        }
        publishConnectedDevices()
        true
    }

    /** Always update the context; update a legacy projection only for the current slot owner. */
    fun updateState(context: ConnectionContext, state: Int) = synchronized(lock) {
        context.connectionState.value = LinkState.fromBluetoothProfileState(state)
        roleOf(context)?.let { role ->
            connectionProjection(role).value = state
        }
        publishConnectedDevices()
    }

    fun setCccdReady(context: ConnectionContext, ready: Boolean) = synchronized(lock) {
        context.cccd.setReady(ready)
        if (current(LegacyConnectionRole.FTMS) === context) {
            _machineCccdReady.value = ready
        }
    }

    fun resetCccd(context: ConnectionContext, clearPlan: Boolean = true) = synchronized(lock) {
        context.cccd.resetOrchestration(clearPlan)
        if (current(LegacyConnectionRole.FTMS) === context) {
            _machineCccdReady.value = false
        }
    }

    fun completeCccdWrite(context: ConnectionContext): Boolean = synchronized(lock) {
        val becameReady = context.cccd.completePending()
        if (current(LegacyConnectionRole.FTMS) === context) {
            _machineCccdReady.value = context.cccd.ready.value
        }
        becameReady
    }

    fun replaceServices(context: ConnectionContext, services: List<BluetoothGattService>) =
        synchronized(lock) {
            if (!context.isActive) return@synchronized
            context.discoveredServices.apply {
                clear()
                addAll(services)
            }
            context.discoveredServiceUuids.apply {
                clear()
                addAll(services.map { it.uuid })
            }
            publishConnectedDevices()
        }

    fun replaceHandlers(
        context: ConnectionContext,
        handlers: Collection<BtEngineGattCore.ProtocolHandler>,
    ) = synchronized(lock) {
        if (!context.isActive) return@synchronized
            context.handlers.apply {
                clear()
                addAll(handlers)
            }
            publishConnectedDevices()
        }

    private fun publishConnectedDevices() {
        _connectedDevices.value = contexts.values.map { context ->
            val handlerServices = context.handlers
                .flatMapTo(linkedSetOf()) { handler ->
                    handler.primaryServices.mapNotNull(::serviceTypeForUuid)
                }
            val fallbackService = if (context.machineConnection) {
                BtEngineState.BtServiceType.FTMS
            } else {
                BtEngineState.BtServiceType.HeartRate
            }
            BtEngineState.ConnectedDeviceLink(
                connectionKey = context.connectionKey,
                address = context.address,
                connectionState = context.connectionState.value.bluetoothProfileState,
                activeServices = handlerServices.ifEmpty { setOf(fallbackService) },
                machineConnection = context.machineConnection,
            )
        }.sortedBy { it.connectionKey }
    }

    private fun createContext(
        address: String,
        gatt: BluetoothGatt?,
        initialState: Int,
        machineConnection: Boolean,
    ): ConnectionContext {
        val canonicalAddress = canonicalConnectionAddress(address)
        return ConnectionContext(
            connectionId = ConnectionId(canonicalAddress, nextLinkGeneration++),
            address = canonicalAddress,
            machineConnection = machineConnection,
            gatt = gatt,
            connectionState = MutableStateFlow(LinkState.fromBluetoothProfileState(initialState)),
        )
    }

    private fun connectionProjection(role: LegacyConnectionRole): MutableStateFlow<Int> =
        when (role) {
            LegacyConnectionRole.FTMS -> _machineConnectionState
            LegacyConnectionRole.HRM -> _hrmConnectionState
        }

    private fun ConnectionContext.deactivate() {
        isActive = false
        connectionState.value = LinkState.DISCONNECTED
        queue.clear()
        cccd.reset()
        discoveredServices.clear()
        discoveredServiceUuids.clear()
        terminationHook = null
        handlerActivator = null
        gatt = null
    }
}

private val ConnectionContext.connectionKey: String
    get() = "${connectionId.address}#${connectionId.linkGeneration}"

internal fun serviceTypeForUuid(uuid: UUID): BtEngineState.BtServiceType? = when (uuid) {
    BluetoothIds.UUID_FTMS_SERVICE -> BtEngineState.BtServiceType.FTMS
    BluetoothIds.UUID_HRM_SERVICE -> BtEngineState.BtServiceType.HeartRate
    BluetoothIds.UUID_CSC_SERVICE -> BtEngineState.BtServiceType.CSC
    BluetoothIds.UUID_RSC_SERVICE -> BtEngineState.BtServiceType.RSC
    BluetoothIds.UUID_POWER_SERVICE -> BtEngineState.BtServiceType.Power
    BluetoothIds.UUID_GLUCOSE_SERVICE -> BtEngineState.BtServiceType.Glucose
    else -> null
}
