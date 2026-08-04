package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

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

/** CCCD readiness and recovery state owned by one physical GATT link. */
internal class CccdState {
    private val lock = Any()
    val ready = MutableStateFlow(false)

    private var pendingWritesValue: Int = 0
    val pendingWrites: Int get() = synchronized(lock) { pendingWritesValue }

    private var recoveryAttemptsValue: Int = 0
    val recoveryAttempts: Int get() = synchronized(lock) { recoveryAttemptsValue }

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
        if (pendingWritesValue == 0) {
            ready.value = true
            true
        } else {
            false
        }
    }

    fun resetOrchestration() = synchronized(lock) {
        pendingWritesValue = 0
        ready.value = false
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
        ready.value = false
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
)

internal fun canonicalConnectionAddress(address: String): String =
    address.trim().uppercase(Locale.ROOT)

internal fun BluetoothGatt.safeAddress(): String = runCatching {
    device?.address.orEmpty()
}.getOrDefault("")

/**
 * Two-slot compatibility registry for Phase 0.2. The roles preserve current FTMS/HRM behavior;
 * later phases replace role-based callback routing with [ConnectionId] identity.
 */
internal class ConnectionRegistry {
    private val lock = Any()
    private val contexts = mutableMapOf<LegacyConnectionRole, ConnectionContext>()
    private var nextLinkGeneration = 1L
    private val _machineCccdReady = MutableStateFlow(false)
    val machineCccdReady: StateFlow<Boolean> = _machineCccdReady

    fun current(role: LegacyConnectionRole): ConnectionContext? = synchronized(lock) {
        contexts[role]
    }

    fun contextForGatt(gatt: BluetoothGatt): ConnectionContext? = synchronized(lock) {
        contexts.values.firstOrNull { it.gatt === gatt }
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

    fun clear(role: LegacyConnectionRole): ConnectionContext? = synchronized(lock) {
        contexts.remove(role)?.also { context ->
            context.deactivate()
            if (role == LegacyConnectionRole.FTMS) _machineCccdReady.value = false
        }
    }

    fun updateState(role: LegacyConnectionRole, state: Int) = synchronized(lock) {
        contexts[role]?.connectionState?.value = LinkState.fromBluetoothProfileState(state)
    }

    fun setCccdReady(context: ConnectionContext, ready: Boolean) = synchronized(lock) {
        context.cccd.setReady(ready)
        if (contexts[LegacyConnectionRole.FTMS] === context) {
            _machineCccdReady.value = ready
        }
    }

    fun resetCccd(context: ConnectionContext) = synchronized(lock) {
        context.cccd.resetOrchestration()
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

    fun replaceServices(gatt: BluetoothGatt, services: List<BluetoothGattService>) =
        synchronized(lock) {
            contextForGatt(gatt)?.discoveredServices?.apply {
                clear()
                addAll(services)
            }
        }

    fun replaceHandlers(
        gatt: BluetoothGatt,
        handlers: Collection<BtEngineGattCore.ProtocolHandler>,
    ) = synchronized(lock) {
        contextForGatt(gatt)?.handlers?.apply {
            clear()
            addAll(handlers)
        }
    }

    private fun ConnectionContext.deactivate() {
        connectionState.value = LinkState.DISCONNECTED
        queue.clear()
        cccd.reset()
        gatt = null
    }
}
