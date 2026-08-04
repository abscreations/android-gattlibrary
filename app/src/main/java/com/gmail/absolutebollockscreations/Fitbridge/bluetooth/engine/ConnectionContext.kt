package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import kotlinx.coroutines.flow.MutableStateFlow
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

/**
 * Per-link queue owner introduced in Phase 0.2. The legacy engine queue remains authoritative
 * until Phase 0.3 moves dispatch and completion into this holder.
 */
internal class GattQueue {
    val lock: Any = Any()
    val entries: ArrayDeque<StackEntry> = ArrayDeque()
    var writeBusy: Boolean = false
}

/** Per-link CCCD state reserved for the Phase 0.3 migration. */
internal class CccdState {
    val ready = MutableStateFlow(false)
    var pendingWrites: Int = 0
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
        contexts[role] = context
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
        contexts.remove(role)?.also {
            it.connectionState.value = LinkState.DISCONNECTED
            it.gatt = null
        }
    }

    fun updateState(role: LegacyConnectionRole, state: Int) = synchronized(lock) {
        contexts[role]?.connectionState?.value = LinkState.fromBluetoothProfileState(state)
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
}
