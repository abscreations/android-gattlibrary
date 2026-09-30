package com.gmail.absolutebollockscreations.gattlibrary.central

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids

/** Identifies one physical-link lifetime, including reconnects to the same BLE address. */
data class ConnectionId(
    val address: String,
    val linkGeneration: Long,
)

@GattLibraryInternalApi
enum class LinkState(val bluetoothProfileState: Int) {
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
@GattLibraryInternalApi
class GattQueue {
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
@GattLibraryInternalApi
data class CccdTarget(
    val serviceUuid: UUID,
    val characteristicUuid: UUID,
)

/** Bounded result of claiming the next CCCD recovery attempt for one connection context. */
@GattLibraryInternalApi
sealed interface CccdRecoveryDecision {
    data class Retry(val attempt: Int) : CccdRecoveryDecision
    data object Terminate : CccdRecoveryDecision
}

/** CCCD enable plan, readiness, and recovery state owned by one physical GATT link. */
@GattLibraryInternalApi
class CccdState {
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

    /** Atomically claim one retry, or terminate once the context-owned bound is exhausted. */
    fun nextRecoveryDecision(maxAttempts: Int): CccdRecoveryDecision = synchronized(lock) {
        require(maxAttempts >= 0)
        if (recoveryAttemptsValue >= maxAttempts) {
            CccdRecoveryDecision.Terminate
        } else {
            recoveryAttemptsValue += 1
            CccdRecoveryDecision.Retry(recoveryAttemptsValue)
        }
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
@GattLibraryInternalApi
class LinkRecoveryState

/** Effective-readiness gate for the proprietary transcript on an FTMS machine link. */
@GattLibraryInternalApi
enum class LifeFitnessLinkSetupState {
    NOT_REQUIRED,
    PENDING,
    SUCCEEDED,
    FAILED,
}

/** All state whose lifetime is one physical BLE connection. */
@GattLibraryInternalApi
class ConnectionContext(
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
    var cscRoleCapability: BtEngineState.CscRoleCapability? = null,
    var cscAssignedRoles: Set<BtEngineState.CscRole> = emptySet(),
    var cscRequestedRoles: Set<BtEngineState.CscRole>? = null,
    var machineDeviceType: BtEngineState.MachineDeviceType? = null,
) {
    /**
     * The Life Fitness setup run for this link, created lazily when one is needed.
     *
     * Per-context by construction, which is how a transcript is bound to a link generation: the
     * context dies with the connection, so a run can never outlive the machine that began it or
     * resume across a reconnect. That matters more here than for most per-link state — resuming
     * a half-completed handshake would replay control writes D61 refuses to repeat.
     */
    @Volatile
    var lifeFitnessSetup: LifeFitnessSetupDriver? = null

    /**
     * Monotonic deadline for the in-flight setup operation, or null when none is outstanding.
     *
     * A settle bound rather than a delay: the official Life Fitness app completes each step in
     * 0.2–0.4 s, so anything approaching `LifeFitnessSetup.SETTLE_TIMEOUT_MS` already means the
     * step is not going to complete.
     */
    @Volatile
    var lifeFitnessSetupDeadlineMs: Long? = null

    /**
     * Descriptor completion and Life Fitness setup completion are separate facts. The raw CCCD
     * state may become ready while this remains [LifeFitnessLinkSetupState.PENDING], but the
     * legacy/public readiness projection must not open until both have succeeded.
     */
    @Volatile
    var lifeFitnessSetupState: LifeFitnessLinkSetupState = LifeFitnessLinkSetupState.NOT_REQUIRED

    /** Callback-owned, idempotent termination entry point used by explicit disconnects. */
    @Volatile
    var terminationHook: ((reason: String) -> Unit)? = null

    /** Callback-owned hook for deliberately activating another handler on this physical link. */
    @Volatile
    var handlerActivator: ((BtEngineGattCore.ProtocolHandler) -> Boolean)? = null

    /**
     * Deliver the engine's periodic tick to this link's active handlers.
     *
     * The handlers live inside the GATT callback's closure, which is the right place for them —
     * they are callback state and they die with the callback. But that left the engine with no way
     * to reach them, and `Concept2ProtocolHandler.onLivenessTick` sat uncalled for two phases as a
     * result. This is the symmetric partner of [handlerActivator]: that one lets a handler be added
     * from outside, this one lets every handler be reached from outside.
     *
     * Null when no callback is bound, and **cleared by [ConnectionContext.reset]** along with
     * `handlerActivator`, so a tick can never reach a handler whose link has ended.
     */
    @Volatile
    var handlerTicker: (() -> Unit)? = null

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

    /** Direct lookup by identity, for connection-scoped collaborators (e.g. GattTransport). */
    fun contextFor(connectionId: ConnectionId): ConnectionContext? = synchronized(lock) {
        contexts[connectionId]?.takeIf { it.isActive }
    }

    fun contextsForAddress(address: String): List<ConnectionContext> = synchronized(lock) {
        val canonicalAddress = canonicalConnectionAddress(address)
        contexts.values.filter { it.isActive && it.address == canonicalAddress }
    }

    fun contextForKey(connectionKey: String): ConnectionContext? = synchronized(lock) {
        contexts.values.firstOrNull { it.connectionKey == connectionKey }
    }

    /**
     * Live machine links, for the periodic work that has to poll rather than react.
     *
     * A snapshot taken under the lock and iterated outside it: the callers are timeout sweeps
     * that may act on a context, and holding the registry lock while doing so would invite the
     * deadlock every other accessor here avoids by copying.
     */
    fun machineContexts(): List<ConnectionContext> = synchronized(lock) {
        contexts.values.filter { it.isActive && it.machineConnection }
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

    /** Handle-based activation must never resolve a replacement context by address. */
    fun activateHandlerOn(context: ConnectionContext, handler: BtEngineGattCore.ProtocolHandler): Boolean {
        if (!isCurrent(context)) return false
        if (context.handlers.any { active -> active.primaryServices.any(handler.primaryServices::contains) }) return true
        if (context.discoveredServiceUuids.none(handler.primaryServices::contains)) return false
        val activated = context.handlerActivator?.invoke(handler) == true
        return synchronized(lock) {
            if (!isCurrent(context)) false else {
                if (activated) publishConnectedDevices()
                activated
            }
        }
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
            _machineCccdReady.value = effectiveCccdReady(context)
        }
    }

    /** Re-project readiness after a setup-state transition without changing raw CCCD state. */
    fun refreshCccdReady(context: ConnectionContext): Boolean = synchronized(lock) {
        val effective = effectiveCccdReady(context)
        if (current(LegacyConnectionRole.FTMS) === context) {
            _machineCccdReady.value = effective
        }
        effective
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
            _machineCccdReady.value = effectiveCccdReady(context)
        }
        becameReady
    }

    private fun effectiveCccdReady(context: ConnectionContext): Boolean =
        context.cccd.ready.value &&
            context.lifeFitnessSetupState != LifeFitnessLinkSetupState.PENDING &&
            context.lifeFitnessSetupState != LifeFitnessLinkSetupState.FAILED

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

    fun setCscRoleCapability(
        context: ConnectionContext,
        capability: BtEngineState.CscRoleCapability?,
    ) = synchronized(lock) {
        if (!context.isActive || contexts[context.connectionId] !== context) return@synchronized
        context.cscRoleCapability = capability
        publishConnectedDevices()
    }

    fun setCscAssignedRoles(
        context: ConnectionContext,
        assignedRoles: Set<BtEngineState.CscRole>,
    ) = synchronized(lock) {
        if (!context.isActive || contexts[context.connectionId] !== context) return@synchronized
        context.cscAssignedRoles = assignedRoles
        publishConnectedDevices()
    }

    fun setCscRequestedRoles(
        context: ConnectionContext,
        requestedRoles: Set<BtEngineState.CscRole>?,
    ) = synchronized(lock) {
        if (!context.isActive || contexts[context.connectionId] !== context) return@synchronized
        context.cscRequestedRoles = requestedRoles
    }

    fun setMachineDeviceType(
        context: ConnectionContext,
        deviceType: BtEngineState.MachineDeviceType?,
    ) = synchronized(lock) {
        if (!context.isActive || contexts[context.connectionId] !== context) return@synchronized
        if (context.machineDeviceType == deviceType) return@synchronized
        context.machineDeviceType = deviceType
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
                machineIdentityFamily = machineIdentityFamily(context),
                machineDeviceType = context.machineDeviceType
                    ?: if (BluetoothIds.UUID_PRECOR_SERVICE in context.discoveredServiceUuids ||
                        BluetoothIds.UUID_FLYWHEEL_SERVICE in context.discoveredServiceUuids
                    ) {
                        BtEngineState.MachineDeviceType.BIKE
                    } else {
                        null
                    },
                cscRoleCapability = context.cscRoleCapability,
                cscAssignedRoles = context.cscAssignedRoles,
            )
        }.sortedBy { it.connectionKey }
    }

    /** Proprietary evidence outranks generic FTMS when a machine exposes both services. */
    private fun machineIdentityFamily(
        context: ConnectionContext,
    ): BtEngineState.MachineIdentityFamily? = when {
        BluetoothIds.UUID_C2_ERG_SERVICE in context.discoveredServiceUuids ->
            BtEngineState.MachineIdentityFamily.CONCEPT2_PM5

        BluetoothIds.UUID_PRECOR_SERVICE in context.discoveredServiceUuids ->
            BtEngineState.MachineIdentityFamily.PRECOR_SPINNER_CHRONO_POWER

        BluetoothIds.UUID_FEC_SERVICE in context.discoveredServiceUuids ->
            BtEngineState.MachineIdentityFamily.FE_C_OVER_BLE

        BluetoothIds.UUID_FLYWHEEL_SERVICE in context.discoveredServiceUuids ->
            BtEngineState.MachineIdentityFamily.FLYWHEEL_SPORTS

        context.handlers.filterIsInstance<KayakFirstProtocolHandler>()
            .any { it.isProtocolConfirmed() } ->
            BtEngineState.MachineIdentityFamily.KAYAK_FIRST

        context.lifeFitnessSetupState != LifeFitnessLinkSetupState.NOT_REQUIRED ->
            BtEngineState.MachineIdentityFamily.LIFE_FITNESS

        FtmsUuids.UUID_FTMS_SERVICE in context.discoveredServiceUuids ->
            BtEngineState.MachineIdentityFamily.FTMS

        else -> null
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
        handlerTicker = null
        cscRoleCapability = null
        cscAssignedRoles = emptySet()
        cscRequestedRoles = null
        gatt = null
    }
}

private val ConnectionContext.connectionKey: String
    get() = "${connectionId.address}#${connectionId.linkGeneration}"

internal fun serviceTypeForUuid(uuid: UUID): BtEngineState.BtServiceType? = when (uuid) {
    FtmsUuids.UUID_FTMS_SERVICE -> BtEngineState.BtServiceType.FTMS
    BluetoothIds.UUID_HRM_SERVICE -> BtEngineState.BtServiceType.HeartRate
    BluetoothIds.UUID_CSC_SERVICE -> BtEngineState.BtServiceType.CSC
    BluetoothIds.UUID_RSC_SERVICE -> BtEngineState.BtServiceType.RSC
    BluetoothIds.UUID_POWER_SERVICE -> BtEngineState.BtServiceType.Power
    BluetoothIds.UUID_GLUCOSE_SERVICE -> BtEngineState.BtServiceType.Glucose
    else -> null
}
