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
    var gatt: BluetoothGatt?,
    val connectionState: MutableStateFlow<LinkState>,
    val discoveredServices: MutableList<BluetoothGattService> = mutableListOf(),
    val handlers: MutableList<BtEngineGattCore.ProtocolHandler> = mutableListOf(),
    val queue: GattQueue = GattQueue(),
    val cccd: CccdState = CccdState(),
    var linkRecovery: LinkRecoveryState? = null,
) {
    /** Callback-owned, idempotent termination entry point used by explicit disconnects. */
    @Volatile
    var terminationHook: ((reason: String) -> Unit)? = null

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
    private val contexts = mutableMapOf<LegacyConnectionRole, ConnectionContext>()
    private var nextLinkGeneration = 1L
    private val _machineConnectionState =
        MutableStateFlow(BluetoothProfile.STATE_DISCONNECTED)
    val machineConnectionState: StateFlow<Int> = _machineConnectionState
    private val _hrmConnectionState =
        MutableStateFlow(BluetoothProfile.STATE_DISCONNECTED)
    val hrmConnectionState: StateFlow<Int> = _hrmConnectionState
    private val _machineCccdReady = MutableStateFlow(false)
    val machineCccdReady: StateFlow<Boolean> = _machineCccdReady

    fun current(role: LegacyConnectionRole): ConnectionContext? = synchronized(lock) {
        contexts[role]
    }

    fun contextForGatt(gatt: BluetoothGatt): ConnectionContext? = synchronized(lock) {
        contexts.values.firstOrNull { it.gatt === gatt }
    }

    fun roleOf(context: ConnectionContext): LegacyConnectionRole? = synchronized(lock) {
        contexts.entries.firstOrNull { it.value === context }?.key
    }

    fun isCurrent(context: ConnectionContext): Boolean = synchronized(lock) {
        context.isActive && contexts.values.any { it === context }
    }

    fun open(
        role: LegacyConnectionRole,
        address: String,
        gatt: BluetoothGatt?,
        initialState: Int,
    ): ConnectionContext = synchronized(lock) {
        val canonicalAddress = canonicalConnectionAddress(address)
        val context = ConnectionContext(
            connectionId = ConnectionId(canonicalAddress, nextLinkGeneration++),
            address = canonicalAddress,
            gatt = gatt,
            connectionState = MutableStateFlow(LinkState.fromBluetoothProfileState(initialState)),
        )
        contexts.put(role, context)?.deactivate()
        connectionProjection(role).value = initialState
        if (role == LegacyConnectionRole.FTMS) _machineCccdReady.value = false
        context
    }

    fun bind(
        role: LegacyConnectionRole,
        address: String,
        gatt: BluetoothGatt,
        initialState: Int,
    ): ConnectionContext = synchronized(lock) {
        val current = contexts[role]
        if (current?.gatt === gatt) return@synchronized current
        open(role, address, gatt, initialState)
    }

    /** Attach the platform GATT returned after a callback was created for [context]. */
    fun attach(context: ConnectionContext, gatt: BluetoothGatt): Boolean = synchronized(lock) {
        if (!context.isActive || contexts.values.none { it === context }) return@synchronized false
        context.gatt = gatt
        true
    }

    fun clear(role: LegacyConnectionRole): ConnectionContext? = synchronized(lock) {
        contexts.remove(role)?.also { context ->
            context.deactivate()
            connectionProjection(role).value = BluetoothProfile.STATE_DISCONNECTED
            if (role == LegacyConnectionRole.FTMS) _machineCccdReady.value = false
        }
    }

    /** Clear only if [context] is still the context occupying its compatibility slot. */
    fun clear(context: ConnectionContext): Boolean = synchronized(lock) {
        val role = contexts.entries.firstOrNull { it.value === context }?.key
            ?: return@synchronized false
        contexts.remove(role)
        context.deactivate()
        connectionProjection(role).value = BluetoothProfile.STATE_DISCONNECTED
        if (role == LegacyConnectionRole.FTMS) _machineCccdReady.value = false
        true
    }

    /** Always update the context; update a legacy projection only for the current slot owner. */
    fun updateState(context: ConnectionContext, state: Int) = synchronized(lock) {
        context.connectionState.value = LinkState.fromBluetoothProfileState(state)
        contexts.entries.firstOrNull { it.value === context }?.key?.let { role ->
            connectionProjection(role).value = state
        }
    }

    fun setCccdReady(context: ConnectionContext, ready: Boolean) = synchronized(lock) {
        context.cccd.setReady(ready)
        if (contexts[LegacyConnectionRole.FTMS] === context) {
            _machineCccdReady.value = ready
        }
    }

    fun resetCccd(context: ConnectionContext, clearPlan: Boolean = true) = synchronized(lock) {
        context.cccd.resetOrchestration(clearPlan)
        if (contexts[LegacyConnectionRole.FTMS] === context) {
            _machineCccdReady.value = false
        }
    }

    fun completeCccdWrite(context: ConnectionContext): Boolean = synchronized(lock) {
        val becameReady = context.cccd.completePending()
        if (contexts[LegacyConnectionRole.FTMS] === context) {
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
        terminationHook = null
        gatt = null
    }
}
