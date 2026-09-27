// =================================================================================================
// File: btEngine.GattCore.kt
// Package: com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine
// -------------------------------------------------------------------------------------------------
// ROLE
//   Central, routed BluetoothGattCallback implementation shared by all profiles (FTMS, HRM, etc).
//
//   This module owns **no** profile-specific logic; instead it:
//     • Wraps Android's BluetoothGattCallback into a single routed callback per connection.
//     • Delegates all profile details to pluggable [ProtocolHandler] instances.
//     • Manages connection lifecycle, service discovery, and the shared GATT op queue.
//     • Tracks and orchestrates CCCD state (pending writes / cccdReady) for FTMS.
//     • Resets FTMS capabilities on new FTMS connections and on disconnect so that
//       BtEngineState.ftmsCapabilities / legacy range flags never leak across links.
//
// -------------------------------------------------------------------------------------------------
// PROTOCOL HANDLER CONTRACT
//   Each profile (e.g. FTMS, HRM) implements [ProtocolHandler]:
//     • Declares which primary service UUIDs it owns (primaryServices).
//     • Optionally declares extra characteristic UUIDs it wants to handle cross-service
//       (extraCharacteristics), e.g. status / control point.
//     • Receives routed callbacks for:
//         - onConnected / onDisconnected
//         - onServicesDiscovered
//         - onCharacteristicChanged
//         - onCharacteristicRead / onCharacteristicWrite
//         - onDescriptorWrite (e.g., CCCD writes)
//
//   The [build] function takes a [BtEngine] and one [ConnectionContext] and returns a single
//   BluetoothGattCallback instance that:
//     • Binds every callback to that context's link identity.
//     • After discovery, populates an activeHandlers set with only the handlers whose primary
//       services are present on that connection.
//     • Routes all GATT I/O events to the appropriate handlers.
//
// -------------------------------------------------------------------------------------------------
// CONNECTION / DISCOVERY ORCHESTRATION
//   onConnectionStateChange:
//     • Updates the context's connection-state flow. Legacy FTMS/HRM flows are projections.
//     • On CONNECTED:
//         - Binds the GATT to the callback's pre-registered connection context.
//         - For FTMS, resets:
//             • Its context-owned CCCD orchestration state
//             • FTMS capabilities snapshot (ftmsCapabilities + legacy range flags) so that
//               a new link always starts “capability-blank” until range reads complete.
//         - Calls handler.onConnected(...).
//         - Requests HIGH connection priority and MTU=512 (best-effort).
//         - Starts service discovery only after MTU completion, with a timeout fallback.
//     • On DISCONNECTED:
//         - Calls handler.onDisconnected(...).
//         - Clears handler-specific state (HRM chars, FTMS control, queue, CCCD flags).
//         - For FTMS, also resets capabilities again as a safety net.
//         - Clears that exact context and updates its compatibility projection, if any.
//
//   onServicesDiscovered:
//     • For FTMS: resets CCCD orchestration counters again before handlers run.
//     • Reads the live service list from the GATT (best-effort; guards SecurityException).
//     • Selects handlers whose primaryServices are present and calls their
//       onServicesDiscovered(...) with the full service list.
//     • Adds any handler that returns true to activeHandlers for subsequent routing.
//
// -------------------------------------------------------------------------------------------------
// GATT QUEUE INTEGRATION
//   All read/write/descriptor callbacks integrate with the owning context's serialized queue:
//     • Completion advances only when GATT identity, operation, and UUID match the queue head.
//     • On failure →
//         - Logs and records a GATT error via BtEngineState.setGattErrorFor(...).
//         - Calls engine.queueFail(context, ...) to let that queue decide recovery.
//     • onCharacteristicChanged(...) calls engine.handleStack(context) to allow the queue to
//       make progress on notifications/indications as well.
//
// -------------------------------------------------------------------------------------------------
// ROUTING & CCCD MANAGEMENT
//   Characteristic routing:
//     • routeToOwners(...) first looks for handlers that explicitly claim the characteristic
//       in extraCharacteristics; if none, it finds handlers that own the *service* containing
//       that characteristic; an unowned callback is logged and not delivered.
//
//   Descriptor routing:
//     • ownersByDescriptor(...) looks at both the parent service UUID and the characteristic
//       UUID (via primaryServices + extraCharacteristics) to find the right handlers.
//
//   FTMS CCCD tracking:
//     • On successful descriptor writes for FTMS CCCDs:
//         - Decrements context.cccd.pendingWrites.
//         - When it reaches zero, flips context.cccd.ready to true and updates the machine projection.
//
// -------------------------------------------------------------------------------------------------
// IMPLEMENTATION NOTES
//   • Protocol selection uses discovered services; legacy cleanup uses registry-slot identity,
//     never handler UUIDs or a callback kind.
//   • All state updates go through [BtEngineState]; no UI coupling.
//   • All permission-sensitive calls are annotated with @RequiresPermission and guarded
//     as needed by callers higher up in BtEngine.
// =================================================================================================

package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGatt.GATT_SUCCESS
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile.STATE_CONNECTED
import android.bluetooth.BluetoothProfile.STATE_DISCONNECTED
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission
import timber.log.Timber
import java.util.UUID

private const val PHASE_05_TAG = "Phase05Hw"

internal class GattDiscoveryAttemptGate(
    private val maxAttempts: Int,
) {
    private val lock = Any()
    private var attempts = 0
    private var inFlight = false
    private var completed = false

    fun tryStartAttempt(): Int? = synchronized(lock) {
        if (completed || inFlight || attempts >= maxAttempts) return@synchronized null
        attempts += 1
        inFlight = true
        attempts
    }

    fun releaseForRetry(): Boolean = synchronized(lock) {
        inFlight = false
        !completed && attempts < maxAttempts
    }

    fun complete() = synchronized(lock) {
        completed = true
        inFlight = false
    }

    fun reset() = synchronized(lock) {
        attempts = 0
        inFlight = false
        completed = false
    }
}

/**
 * Compatibility roles for the two public connection slots retained during Phase 0.
 * Connection identity is carried by ConnectionContext rather than by this role.
 */
internal enum class LegacyConnectionRole { FTMS, HRM }

internal data class LegacyConnectionSlots<T>(
    val ftms: T?,
    val hrm: T?,
) {
    fun current(role: LegacyConnectionRole): T? = when (role) {
        LegacyConnectionRole.FTMS -> ftms
        LegacyConnectionRole.HRM -> hrm
    }

    fun without(role: LegacyConnectionRole): LegacyConnectionSlots<T> = when (role) {
        LegacyConnectionRole.FTMS -> copy(ftms = null)
        LegacyConnectionRole.HRM -> copy(hrm = null)
    }
}

internal fun matchingProtocolHandlers(
    handlers: List<BtEngineGattCore.ProtocolHandler>,
    discoveredServiceUuids: Set<UUID>,
): List<BtEngineGattCore.ProtocolHandler> {
    val eligibleServices = centralProtocolServiceUuids(discoveredServiceUuids)
    return handlers.filter { handler -> handler.primaryServices.any(eligibleServices::contains) }
}

/** Pure identity lifecycle used by the Android callback and JVM characterization tests. */
internal class ContextCallbackLifecycle(
    private val state: BtEngineState,
    val context: ConnectionContext,
) {
    private val candidateHandlers = context.handlers.toList()

    fun publishConnectionState(newState: Int) {
        state.setConnectionState(context, newState)
    }

    fun matchingHandlers(discoveredServiceUuids: Set<UUID>): List<BtEngineGattCore.ProtocolHandler> =
        matchingProtocolHandlers(candidateHandlers, discoveredServiceUuids)

    fun candidates(): List<BtEngineGattCore.ProtocolHandler> = candidateHandlers
}

/** Thread-safe active-handler ownership with one irreversible termination handoff. */
internal class ActiveHandlerLifecycle<T> {
    private val lock = Any()
    private val active = LinkedHashSet<T>()
    private var terminated = false

    fun addAll(values: Iterable<T>) = synchronized(lock) {
        if (!terminated) active.addAll(values)
    }

    fun snapshot(): List<T> = synchronized(lock) { active.toList() }

    /** Returns handlers only for the first termination caller; later callers receive null. */
    fun terminateOnce(): List<T>? = synchronized(lock) {
        if (terminated) return@synchronized null
        terminated = true
        active.toList().also { active.clear() }
    }

    /** Drop stale callback state without claiming ownership of connection termination. */
    fun discard() = synchronized(lock) { active.clear() }
}

internal object BtEngineGattCore {

    private const val PREFERRED_ATT_MTU = 512
    private const val MTU_DISCOVERY_FALLBACK_MS = 2_000L
    private const val DISCOVERY_RETRY_DELAY_MS = 300L
    private const val MAX_DISCOVERY_ATTEMPTS = 3

    /** Minimal protocol contract implemented per profile (FTMS, HRM, ...). */
    internal interface ProtocolHandler {
        /** Primary services this handler owns. */
        val primaryServices: Set<UUID>

        /** Optional: additional characteristic UUIDs to match or cross-route. */
        val extraCharacteristics: Set<UUID> get() = emptySet()

        /** Connection just established, before discovery. */
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        fun onConnected(engine: BtEngine, gatt: BluetoothGatt) {}

        /** Disconnected; clear any handler-local state if needed. */
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        fun onDisconnected(engine: BtEngine) {}

        /** Services discovered. Return true if you recognized/handled your service(s). */
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        fun onServicesDiscovered(
            engine: BtEngine,
            gatt: BluetoothGatt,
            services: List<BluetoothGattService>
        ): Boolean

        /** Notifications/Indications for any characteristic this handler owns. */
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        fun onCharacteristicChanged(
            engine: BtEngine,
            gatt: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray
        ) {}

        /** Reads for any characteristic this handler owns (value is provided). */
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        fun onCharacteristicRead(
            engine: BtEngine,
            gatt: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {}

        /** Writes for any characteristic this handler owns. */
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        fun onCharacteristicWrite(
            engine: BtEngine,
            gatt: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int
        ) {}

        /** Descriptor writes this handler initiated (e.g., CCCD). */
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        fun onDescriptorWrite(
            engine: BtEngine,
            gatt: BluetoothGatt,
            desc: BluetoothGattDescriptor,
            status: Int
        ) {}

        /** The complete merged CCCD plan for this physical connection is armed. */
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        fun onCccdPlanReady(engine: BtEngine, gatt: BluetoothGatt) {}

        /**
         * The engine's periodic liveness tick, for handlers that need to notice an *absence*.
         *
         * **This exists because it was missing, and its absence hid a defect for two phases.**
         * `Concept2ProtocolHandler` had an `onLivenessTick` that nothing called, so D41's
         * multiplexed-to-individual fallback never fired: a PM whose `0x0080` subscription went
         * silent stayed silent forever. The method was written, tested in isolation, and
         * unreachable — the fifth instance of that pattern in this project.
         *
         * Every other callback here is driven by an event. A timeout is the absence of one, so a
         * handler that needs to act on silence has nowhere to hang unless the interface offers a
         * poll. Declaring it here rather than reaching into handler internals from the engine means
         * the next handler with the same need finds a seam instead of inventing a private timer.
         */
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        fun onLivenessTick(engine: BtEngine, gatt: BluetoothGatt) {}
    }

    /** Build one routed callback bound to [context] identity. */
    fun build(
        engine: BtEngine,
        context: ConnectionContext,
    ): BluetoothGattCallback {
        val callback = object : BluetoothGattCallback() {
        private val lifecycle = ContextCallbackLifecycle(engine.state, context)
        private val handlers = lifecycle.candidates()

        /** Active handlers for this connection (populated post-discovery). */
        private val activeHandlers = ActiveHandlerLifecycle<ProtocolHandler>()

        init {
            context.handlerActivator = { handler -> activateAdditionalHandler(handler) }
            context.handlerTicker = { deliverLivenessTick() }
        }

        /**
         * Offer the engine's tick to every active handler on this link.
         *
         * Snapshots first, so a handler activating or terminating mid-tick cannot mutate the
         * collection being iterated. Each handler is isolated: one throwing must not stop the
         * others, because a tick delivers timeouts and a swallowed one is a fallback that never
         * fires — which is the exact defect this seam was added to fix.
         */
        @SuppressLint("MissingPermission")
        private fun deliverLivenessTick() {
            if (!context.isActive) return
            val gatt = activeGatt ?: context.gatt ?: return
            for (handler in activeHandlers.snapshot()) {
                runCatching { handler.onLivenessTick(engine, gatt) }
                    .onFailure {
                        Timber.tag(BtEngineState.TAG).w(
                            it,
                            "liveness tick failed for %s",
                            handler::class.java.simpleName,
                        )
                    }
            }
        }

        private val discoveryHandler = Handler(Looper.getMainLooper())
        private val discoveryGate = GattDiscoveryAttemptGate(MAX_DISCOVERY_ATTEMPTS)
        @Volatile private var activeGatt: BluetoothGatt? = null
        private var mtuFallback: Runnable? = null
        private var discoveryRetry: Runnable? = null

        private fun ownsGatt(gatt: BluetoothGatt, allowUnbound: Boolean = false): Boolean {
            if (!context.isActive) return false
            return context.gatt === gatt || (allowUnbound && context.gatt == null)
        }

        @SuppressLint("MissingPermission")
        private fun activateAdditionalHandler(handler: ProtocolHandler): Boolean {
            val gatt = activeGatt ?: context.gatt ?: return false
            if (!ownsGatt(gatt) ||
                context.connectionState.value != LinkState.CONNECTED ||
                centralProtocolServiceUuids(context.discoveredServiceUuids).none(handler.primaryServices::contains)
            ) {
                return false
            }
            if (activeHandlers.snapshot().any { active ->
                    active.primaryServices.any(handler.primaryServices::contains)
                }
            ) {
                return true
            }

            runCatching { handler.onConnected(engine, gatt) }
                .onFailure {
                    Timber.tag(BtEngineState.TAG).w(it, "additional handler onConnected failed")
                }
            val activated = runCatching {
                handler.onServicesDiscovered(
                    engine,
                    gatt,
                    context.discoveredServices.toList(),
                )
            }.onFailure {
                Timber.tag(BtEngineState.TAG).w(it, "additional handler activation failed")
            }.getOrDefault(false)
            if (!activated) return false

            activeHandlers.addAll(listOf(handler))
            engine.state.connectionRegistry.replaceHandlers(context, activeHandlers.snapshot())
            runCatching { handler.onCccdPlanReady(engine, gatt) }
                .onFailure {
                    Timber.tag(BtEngineState.TAG).w(it, "additional handler CCCD plan failed")
                }
            Timber.tag(BtEngineState.TAG).i(
                "Activated additional handler=%s on context=%s",
                handlerName(handler),
                context.connectionId,
            )
            return true
        }

        private fun cancelDiscoveryCallbacks() {
            mtuFallback?.let(discoveryHandler::removeCallbacks)
            discoveryRetry?.let(discoveryHandler::removeCallbacks)
            mtuFallback = null
            discoveryRetry = null
        }

        private fun resetDiscovery(gatt: BluetoothGatt) {
            cancelDiscoveryCallbacks()
            discoveryGate.reset()
            activeGatt = gatt
        }

        private fun clearDiscovery(gatt: BluetoothGatt) {
            if (activeGatt !== gatt) return
            cancelDiscoveryCallbacks()
            activeGatt = null
            discoveryGate.reset()
        }

        /** Explicit-disconnect entry point installed on the owning connection context. */
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        fun terminateFromOwner(reason: String) {
            terminateConnection(context.gatt, reason)
        }

        /**
         * End this physical-link generation exactly once, regardless of whether explicit control
         * or a platform callback observed the disconnect first.
         */
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        private fun terminateConnection(gatt: BluetoothGatt?, reason: String) {
            val handlersToClean = activeHandlers.terminateOnce()
            if (handlersToClean == null) {
                Timber.tag(BtEngineState.TAG).d(
                    "Connection termination already completed context=%s reason=%s",
                    context.connectionId,
                    reason,
                )
                return
            }
            engine.onPhysicalLinkTerminating(context, reason)

            gatt?.let(::clearDiscovery) ?: cancelDiscoveryCallbacks()
            val compatibilityRole = engine.state.connectionRegistry.roleOf(context)
            Timber.tag(BtEngineState.TAG).i(
                "Terminating connection context=%s role=%s reason=%s handlers=%d",
                context.connectionId,
                compatibilityRole ?: "none",
                reason,
                handlersToClean.size,
            )

            handlersToClean.forEach { handler ->
                if (engine.bleConfig.phase05HardwareTest) {
                    Timber.tag(PHASE_05_TAG).i(
                        "PHASE05 disconnect cleanup start generation=%d handler=%s reason=%s",
                        context.connectionId.linkGeneration,
                        handlerName(handler),
                        reason,
                    )
                }
                runCatching { handler.onDisconnected(engine) }
                    .onSuccess {
                        if (engine.bleConfig.phase05HardwareTest) {
                            Timber.tag(PHASE_05_TAG).i(
                                "PHASE05 disconnect cleanup complete generation=%d handler=%s " +
                                    "reason=%s",
                                context.connectionId.linkGeneration,
                                handlerName(handler),
                                reason,
                            )
                        }
                    }
                    .onFailure {
                        Timber.tag(BtEngineState.TAG).w(
                            it,
                            "handler.onDisconnected failed handler=%s",
                            handlerName(handler),
                        )
                    }
            }

            engine.state.setConnectionState(context, STATE_DISCONNECTED)
            if (compatibilityRole == LegacyConnectionRole.HRM) {
                engine.state.hrmMeasurementChar = null
                engine.state.hrmCtrlPointChar = null
                engine.state.hrmBodyLocChar = null
                engine.state.setExternalHeartRate(null)
            } else if (compatibilityRole == LegacyConnectionRole.FTMS) {
                engine.state.setHaveControl(false)
                engine.state.resetFtmsCapabilities()
                Timber.tag(BtEngineState.TAG)
                    .d("FTMS disconnected → reset queue, CCCD flags, and capabilities")
            }

            engine.metricFusion.onDisconnected(context.connectionId)
            engine.state.clearConnection(context)
            gatt?.let { linkGatt ->
                runCatching { linkGatt.close() }
                    .onFailure {
                        Timber.tag(BtEngineState.TAG).w(it, "BluetoothGatt.close() failed")
                    }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        private fun startServiceDiscovery(gatt: BluetoothGatt, reason: String) {
            if (activeGatt !== gatt || !ownsGatt(gatt)) {
                Timber.tag(BtEngineState.TAG)
                    .d("Ignoring service discovery for stale GATT (%s)", reason)
                return
            }

            val attempt = discoveryGate.tryStartAttempt() ?: return
            val accepted = runCatching { gatt.discoverServices() }
                .onFailure {
                    Timber.tag(BtEngineState.TAG)
                        .w(it, "discoverServices() threw (attempt=%d, reason=%s)", attempt, reason)
                }
                .getOrDefault(false)

            Timber.tag(BtEngineState.TAG).i(
                "discoverServices() accepted=%s attempt=%d reason=%s",
                accepted,
                attempt,
                reason,
            )

            if (!accepted) retryServiceDiscovery(gatt, "discoverServices() returned false")
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        private fun retryServiceDiscovery(gatt: BluetoothGatt, failure: String) {
            if (activeGatt !== gatt || !ownsGatt(gatt)) return

            if (!discoveryGate.releaseForRetry()) {
                val msg = "Service discovery failed after $MAX_DISCOVERY_ATTEMPTS attempts: $failure"
                Timber.tag(BtEngineState.TAG).e(msg)
                clearDiscovery(gatt)
                engine.setGattErrorFor(gatt, msg)
                return
            }

            discoveryRetry?.let(discoveryHandler::removeCallbacks)
            discoveryRetry = Runnable {
                discoveryRetry = null
                startServiceDiscovery(gatt, "retry after $failure")
            }.also { discoveryHandler.postDelayed(it, DISCOVERY_RETRY_DELAY_MS) }
        }

        private fun scheduleMtuFallback(gatt: BluetoothGatt) {
            mtuFallback?.let(discoveryHandler::removeCallbacks)
            mtuFallback = Runnable {
                mtuFallback = null
                if (activeGatt === gatt && ownsGatt(gatt)) {
                    Timber.tag(BtEngineState.TAG)
                        .w("MTU callback timed out; falling back to service discovery")
                    startServiceDiscovery(gatt, "MTU callback timeout")
                }
            }.also { discoveryHandler.postDelayed(it, MTU_DISCOVERY_FALLBACK_MS) }
        }

        // ───────────────────────────────── Connection lifecycle ─────────────────────────────────

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Timber.tag(BtEngineState.TAG)
                .i(
                    "Gatt:onConnectionStateChange context=%s status=%d newState=%d",
                    context.connectionId, status, newState,
                )

            if (!ownsGatt(gatt, allowUnbound = true)) {
                Timber.tag(BtEngineState.TAG)
                    .d("Ignoring callback for inactive or mismatched context=%s", context.connectionId)
                clearDiscovery(gatt)
                activeHandlers.discard()
                if (newState == STATE_DISCONNECTED) runCatching { gatt.close() }
                return
            }

            // The callback owns this context regardless of the protocols it may activate.
            context.gatt = gatt
            engine.state.attachGatt(context, gatt)
            lifecycle.publishConnectionState(newState)
            val compatibilityRole = engine.state.connectionRegistry.roleOf(context)

            if (status != GATT_SUCCESS &&
                newState == STATE_DISCONNECTED
            ) {
                engine.state.setGattErrorFor(gatt, "Disconnect status=$status")
            }

            when (newState) {
                STATE_CONNECTED -> {
                    CaptureHooks.connected(context.connectionId)
                    engine.onPhysicalLinkConnected(context)
                    resetDiscovery(gatt)

                    if (compatibilityRole == LegacyConnectionRole.FTMS) {
                        // Only FTMS path manipulates these orchestration flags.
                        // Also reset FTMS capabilities snapshot at the start of a new link.
                        engine.state.connectionRegistry.resetCccd(context)
                        engine.state.resetFtmsCapabilities()
                        Timber.tag(BtEngineState.TAG)
                            .d("FTMS connected → reset CCCD flags and capabilities snapshot")
                    }

                    // Allow handlers to prep
                    handlers.forEach { runCatching { it.onConnected(engine, gatt) } }

                    // Connection priority is independent of ATT traffic. MTU exchange and
                    // service discovery are not: wait for onMtuChanged before discovering so
                    // Android 11-era stacks do not silently drop the discovery request.
                    val priorityAccepted = runCatching {
                        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    }.onFailure {
                        Timber.tag(BtEngineState.TAG).w(it, "requestConnectionPriority() threw")
                    }.getOrDefault(false)
                    Timber.tag(BtEngineState.TAG)
                        .d("requestConnectionPriority() accepted=%s", priorityAccepted)

                    val mtuAccepted = runCatching { gatt.requestMtu(PREFERRED_ATT_MTU) }
                        .onFailure {
                            Timber.tag(BtEngineState.TAG)
                                .w(it, "requestMtu(%d) threw", PREFERRED_ATT_MTU)
                        }
                        .getOrDefault(false)
                    Timber.tag(BtEngineState.TAG).d(
                        "requestMtu(%d) accepted=%s",
                        PREFERRED_ATT_MTU,
                        mtuAccepted,
                    )

                    if (mtuAccepted) {
                        scheduleMtuFallback(gatt)
                    } else {
                        startServiceDiscovery(gatt, "MTU request was not accepted")
                    }
                }

                STATE_DISCONNECTED -> {
                    CaptureHooks.disconnected(
                        context.connectionId,
                        reason = "platform disconnect status=$status",
                    )
                    terminateConnection(gatt, "platform disconnect status=$status")
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (activeGatt !== gatt || !ownsGatt(gatt)) {
                Timber.tag(BtEngineState.TAG)
                    .d("Ignoring MTU callback for stale GATT (mtu=%d status=%d)", mtu, status)
                return
            }

            mtuFallback?.let(discoveryHandler::removeCallbacks)
            mtuFallback = null
            Timber.tag(BtEngineState.TAG)
                .i("onMtuChanged(mtu=%d status=%d) → starting service discovery", mtu, status)
            startServiceDiscovery(
                gatt,
                if (status == GATT_SUCCESS) "MTU configured to $mtu" else "MTU failed with status=$status",
            )
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (activeGatt !== gatt || !ownsGatt(gatt)) {
                Timber.tag(BtEngineState.TAG)
                    .d("Ignoring service discovery callback for stale GATT (status=%d)", status)
                return
            }
            if (status != GATT_SUCCESS) {
                Timber.tag(BtEngineState.TAG)
                    .w("onServicesDiscovered failed status=%d", status)
                retryServiceDiscovery(gatt, "onServicesDiscovered status=$status")
                return
            }

            cancelDiscoveryCallbacks()
            discoveryGate.complete()

            // §0.9.1 calls this the single most useful event for triage: "it
            // connects but shows nothing" is usually answered by looking at what
            // the device actually exposes, and nothing else carries that.
            CaptureHooks.gattInventory(context.connectionId, gatt)

            // Reset CCCD readiness only for the current machine compatibility context.
            if (engine.state.connectionRegistry.roleOf(context) == LegacyConnectionRole.FTMS) {
                engine.state.connectionRegistry.resetCccd(context)
                Timber.tag(BtEngineState.TAG)
                    .d("onServicesDiscovered(FTMS) → reset CCCD orchestration counters")
            }

            val services = try {
                gatt.services ?: emptyList()
            } catch (_: SecurityException) {
                emptyList()
            }
            engine.state.connectionRegistry.replaceServices(context, services)

            val discoveredServiceUuids = services.mapTo(linkedSetOf()) { it.uuid }

            // Offer every matching handler the full service list; several may activate.
            activeHandlers.addAll(
                activateMatchingProtocolHandlers(handlers, discoveredServiceUuids) { h ->
                    runCatching { h.onServicesDiscovered(engine, gatt, services) }
                        .onFailure {
                            Timber.tag(BtEngineState.TAG)
                                .w(it, "handler.onServicesDiscovered failed")
                        }
                    .getOrDefault(false)
                }
            )
            val activeSnapshot = activeHandlers.snapshot()
            engine.state.connectionRegistry.replaceHandlers(context, activeSnapshot)

            Timber.tag(BtEngineState.TAG)
                .i("Services discovered: total=%d, activeHandlers=%d", services.size, activeSnapshot.size)
            if (engine.bleConfig.phase05HardwareTest) {
                Timber.tag(PHASE_05_TAG).i(
                    "PHASE05 active handlers generation=%d count=%d handlers=%s",
                    context.connectionId.linkGeneration,
                    activeSnapshot.size,
                    handlerNames(activeSnapshot),
                )
            }
        }

        // ───────────────────────────────── I/O callbacks & fan-out ─────────────────────────────────

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (!ownsGatt(gatt)) return
            // Raw bytes before anything parses them: §0.9.1's fixture-generation
            // and protocol-specification consumers both need the payload exactly
            // as it arrived, not a decode of it.
            CaptureHooks.notified(context.connectionId, characteristic.uuid, value)
            engine.handleStack(context)
            routeToOwners(gatt, characteristic.uuid) {
                it.onCharacteristicChanged(engine, gatt, characteristic, value)
            }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) = onCharacteristicChanged(gatt, characteristic, characteristic.value ?: ByteArray(0))

        // API 33+ characteristic read
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (!ownsGatt(gatt)) return
            if (status != GATT_SUCCESS) {
                val msg = "read failed status=$status uuid=${safeUuid(characteristic)}"
                engine.state.setGattErrorFor(gatt, msg)
                Timber.tag(BtEngineState.TAG).w(msg)
            }
            if (status == GATT_SUCCESS) {
                CaptureHooks.read(context.connectionId, characteristic.uuid, value)
                engine.queueSucceed(context, gatt, StackEntry.Op.READ, characteristic.uuid)
            } else {
                engine.queueFail(
                    context,
                    gatt,
                    StackEntry.Op.READ,
                    characteristic.uuid,
                    reason = "read failed status=$status uuid=${safeUuid(characteristic)}",
                )
            }
            routeToOwners(gatt, characteristic.uuid) {
                it.onCharacteristicRead(engine, gatt, characteristic, value, status)
            }
        }

        // Legacy forwarder → calls the API 33+ path with current value buffer
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) = onCharacteristicRead(
            gatt,
            characteristic,
            characteristic.value ?: ByteArray(0),
            status
        )

        @Suppress("DEPRECATION")
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (!ownsGatt(gatt)) return
            if (status != GATT_SUCCESS) {
                val msg = "write failed status=$status uuid=${safeUuid(characteristic)}"
                engine.state.setGattErrorFor(gatt, msg)
                Timber.tag(BtEngineState.TAG).w(msg)
            }
            if (status == GATT_SUCCESS) {
                // Redaction is applied inside the hook by characteristic UUID, so
                // a profile write cannot leak here even though this call site has
                // no idea what it is carrying.
                CaptureHooks.wrote(
                    connectionId = context.connectionId,
                    characteristic = characteristic.uuid,
                    // characteristic.value is deprecated on API 33+; the enclosing
                    // override already suppresses it, and it is the only place the
                    // written bytes are still available at completion time.
                    payload = characteristic.value ?: ByteArray(0),
                    withoutResponse = characteristic.writeType ==
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                )
                engine.queueSucceed(context, gatt, StackEntry.Op.WRITE, characteristic.uuid)
            } else {
                engine.queueFail(
                    context,
                    gatt,
                    StackEntry.Op.WRITE,
                    characteristic.uuid,
                    reason = "write failed status=$status uuid=${safeUuid(characteristic)}",
                )
            }
            routeToOwners(gatt, characteristic.uuid) {
                it.onCharacteristicWrite(engine, gatt, characteristic, status)
            }
        }

        // API 33+ descriptor read (status, then value)
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onDescriptorRead(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
            value: ByteArray
        ) {
            if (!ownsGatt(gatt)) return
            if (status != GATT_SUCCESS) {
                val msg = "descriptor read failed status=$status uuid=${safeUuid(descriptor)}"
                engine.state.setGattErrorFor(gatt, msg)
                Timber.tag(BtEngineState.TAG).w(msg)
            }
            Timber.tag(BtEngineState.TAG).d(
                "Descriptor read has no queued operation to complete: %s",
                safeUuid(descriptor),
            )
            // No handler routing for descriptor reads; extend ProtocolHandler if needed.
        }

        // Legacy (pre-API 33) forwarder → call the API 33+ version with the current value buffer
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onDescriptorRead(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) = onDescriptorRead(
            gatt,
            descriptor,
            status,
            descriptor.value ?: ByteArray(0)
        )

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (!ownsGatt(gatt)) return
            if (status != GATT_SUCCESS) {
                val msg = "descriptor write failed status=$status uuid=${safeUuid(descriptor)}"
                engine.state.setGattErrorFor(gatt, msg)
                Timber.tag(BtEngineState.TAG).w(msg)
            }
            val charUuid = descriptor.characteristic?.uuid
            val completionMatched = when {
                status == GATT_SUCCESS -> engine.queueSucceed(
                    context,
                    gatt,
                    StackEntry.Op.DESC_WRITE,
                    charUuid,
                    descriptor.uuid,
                    descriptor,
                )
                descriptor.uuid == BluetoothIds.UUID_CCCD ->
                    engine.handleCccdWriteFailureRecovery(
                        context,
                        gatt,
                        descriptor,
                        CccdWriteFailureCause.Callback(status),
                    )
                else -> engine.queueFail(
                    context = context,
                    callbackGatt = gatt,
                    callbackOp = StackEntry.Op.DESC_WRITE,
                    callbackCharUuid = charUuid,
                    callbackDescriptorUuid = descriptor.uuid,
                    reason = "descriptor write failed status=$status uuid=${safeUuid(descriptor)}",
                    callbackDescriptorIdentity = descriptor,
                )
            }

            if (descriptor.uuid == BluetoothIds.UUID_CCCD) {
                CaptureHooks.cccd(
                    connectionId = context.connectionId,
                    descriptor = descriptor,
                    enabled = status == GATT_SUCCESS,
                    result = if (status == GATT_SUCCESS) "SUCCESS" else "status=$status",
                )
            }

            // Decrement only for the matching successful CCCD completion on this context.
            var cccdPlanBecameReady = false
            if (completionMatched && status == GATT_SUCCESS && descriptor.uuid == BluetoothIds.UUID_CCCD) {
                cccdPlanBecameReady = engine.state.connectionRegistry.completeCccdWrite(context)
                val pending = context.cccd.pendingWrites
                Timber.tag(BtEngineState.TAG).d("CCCD written for %s → pending=%d", charUuid, pending)
            }

            // Route to owners
            ownersByDescriptor(descriptor).forEach {
                it.onDescriptorWrite(engine, gatt, descriptor, status)
            }

            if (cccdPlanBecameReady) {
                // One successful descriptor is not enough in a merged multi-handler plan.
                // Reset the bounded failure counter only after every CCCD has completed.
                engine.resetCccdRecoveryAttemptsFor(context)
                // §0.7a: the liveness clock starts here, not at connect. The
                // device has demonstrably accepted every subscription, so from
                // this instant silence is the machine's doing rather than ours.
                engine.state.adapterLiveness.onSubscriptionsComplete(
                    context,
                    android.os.SystemClock.elapsedRealtime(),
                )
                Timber.tag(BtEngineState.TAG).d(
                    "All CCCDs armed; effective readiness remains setup-gated context=%s",
                    context.connectionId,
                )
                activeHandlers.snapshot().forEach { handler ->
                    if (engine.bleConfig.phase05HardwareTest) {
                        Timber.tag(PHASE_05_TAG).i(
                            "PHASE05 merged CCCD ready delivery generation=%d handler=%s",
                            context.connectionId.linkGeneration,
                            handlerName(handler),
                        )
                    }
                    runCatching { handler.onCccdPlanReady(engine, gatt) }
                        .onFailure {
                            Timber.tag(BtEngineState.TAG)
                                .w(it, "handler.onCccdPlanReady failed")
                        }
                }
            }
        }

        // ───────────────────────────────── Routing helpers ─────────────────────────────────

        private fun routeToOwners(
            gatt: BluetoothGatt,
            charUuid: UUID,
            action: (ProtocolHandler) -> Unit
        ) {
            val serviceUuid = characteristicServiceUuidOrNull(gatt, charUuid)
            val route = resolveProtocolHandlerRoute(
                activeHandlers.snapshot(),
                charUuid,
                serviceUuid,
            )
            if (route.owners.isEmpty()) {
                Timber.tag(BtEngineState.TAG).e(
                    "No active protocol handler owns characteristic=%s service=%s context=%s",
                    charUuid,
                    serviceUuid,
                    context.connectionId,
                )
                return
            }
            if (engine.bleConfig.phase05HardwareTest) {
                Timber.tag(PHASE_05_TAG).d(
                    "PHASE05 characteristic route generation=%d characteristic=%s service=%s " +
                        "ownership=%s owners=%s",
                    context.connectionId.linkGeneration,
                    charUuid,
                    serviceUuid,
                    route.ownership,
                    handlerNames(route.owners),
                )
            }
            route.owners.forEach(action)
        }

        private fun ownersByDescriptor(desc: BluetoothGattDescriptor): List<ProtocolHandler> {
            val characteristic = desc.characteristic
            val charUuid = characteristic?.uuid
            val serviceUuid = characteristic?.service?.uuid
            if (charUuid == null) {
                Timber.tag(BtEngineState.TAG).e(
                    "Descriptor callback has no characteristic context=%s descriptor=%s",
                    context.connectionId,
                    safeUuid(desc),
                )
                return emptyList()
            }
            val route = resolveProtocolHandlerRoute(
                activeHandlers.snapshot(),
                charUuid,
                serviceUuid,
            )
            if (route.owners.isEmpty()) {
                Timber.tag(BtEngineState.TAG).e(
                    "No active protocol handler owns descriptor characteristic=%s service=%s context=%s",
                    charUuid,
                    serviceUuid,
                    context.connectionId,
                )
            } else if (engine.bleConfig.phase05HardwareTest) {
                Timber.tag(PHASE_05_TAG).d(
                    "PHASE05 descriptor route generation=%d characteristic=%s service=%s " +
                        "descriptor=%s ownership=%s owners=%s",
                    context.connectionId.linkGeneration,
                    charUuid,
                    serviceUuid,
                    safeUuid(desc),
                    route.ownership,
                    handlerNames(route.owners),
                )
            }
            return route.owners
        }

        private fun handlerNames(handlers: Collection<ProtocolHandler>): String =
            handlers.joinToString(prefix = "[", postfix = "]") { handlerName(it) }

        private fun handlerName(handler: ProtocolHandler): String =
            handler::class.java.simpleName.ifBlank { handler::class.java.name }

        /** Best-effort lookup of the containing service UUID for a characteristic UUID. */
        private fun characteristicServiceUuidOrNull(
            gatt: BluetoothGatt,
            charUuid: UUID
        ): UUID? {
            // First look in this connection's GATT
            gatt.services?.firstNotNullOfOrNull { svc ->
                svc.characteristics.firstOrNull { it.uuid == charUuid }?.let { svc.uuid }
            }?.let { return it }

            return null
        }

        private fun safeUuid(ch: BluetoothGattCharacteristic): String =
            runCatching { ch.uuid.toString() }.getOrElse { "perm-denied" }

        private fun safeUuid(desc: BluetoothGattDescriptor): String =
            runCatching { desc.uuid.toString() }.getOrElse { "perm-denied" }

        }
        context.terminationHook = { reason -> callback.terminateFromOwner(reason) }
        return callback
    }
}
