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
): List<BtEngineGattCore.ProtocolHandler> = handlers.filter { handler ->
    handler.primaryServices.any(discoveredServiceUuids::contains)
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
    }

    /** Build one routed callback bound to [context] identity. */
    fun build(
        engine: BtEngine,
        context: ConnectionContext,
    ): BluetoothGattCallback = object : BluetoothGattCallback() {
        private val lifecycle = ContextCallbackLifecycle(engine.state, context)
        private val handlers = lifecycle.candidates()

        /** Active handlers for this connection (populated post-discovery). */
        private val activeHandlers = LinkedHashSet<ProtocolHandler>()

        private val discoveryHandler = Handler(Looper.getMainLooper())
        private val discoveryGate = GattDiscoveryAttemptGate(MAX_DISCOVERY_ATTEMPTS)
        @Volatile private var activeGatt: BluetoothGatt? = null
        private var mtuFallback: Runnable? = null
        private var discoveryRetry: Runnable? = null

        private fun ownsGatt(gatt: BluetoothGatt, allowUnbound: Boolean = false): Boolean {
            if (!context.isActive) return false
            return context.gatt === gatt || (allowUnbound && context.gatt == null)
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
                activeHandlers.clear()
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
                    clearDiscovery(gatt)

                    // Notify handlers, then clear local state
                    activeHandlers.toList().forEach { runCatching { it.onDisconnected(engine) } }
                    activeHandlers.clear()

                    if (compatibilityRole == LegacyConnectionRole.HRM) {
                        engine.state.hrmMeasurementChar = null
                        engine.state.hrmCtrlPointChar = null
                        engine.state.hrmBodyLocChar = null
                        engine.state.setExternalHeartRate(null)
                        HrmArbitration.reselect(engine)
                    } else if (compatibilityRole == LegacyConnectionRole.FTMS) {
                        engine.state.setHaveControl(false)

                        // Reset FTMS capabilities snapshot + legacy range flags so a future
                        // connection always starts from a clean capability state.
                        engine.state.resetFtmsCapabilities()
                        Timber.tag(BtEngineState.TAG)
                            .d("FTMS disconnected → reset queue, CCCD flags, and capabilities")
                    }

                    engine.state.clearConnection(context)

                    runCatching { gatt.close() }
                        .onFailure {
                            Timber.tag(BtEngineState.TAG).w(it, "BluetoothGatt.close() failed")
                        }
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
            engine.state.connectionRegistry.replaceHandlers(context, activeHandlers)

            Timber.tag(BtEngineState.TAG)
                .i("Services discovered: total=%d, activeHandlers=%d", services.size, activeHandlers.size)
        }

        // ───────────────────────────────── I/O callbacks & fan-out ─────────────────────────────────

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (!ownsGatt(gatt)) return
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
                )
                else -> engine.queueFail(
                    context,
                    gatt,
                    StackEntry.Op.DESC_WRITE,
                    charUuid,
                    descriptor.uuid,
                    "descriptor write failed status=$status uuid=${safeUuid(descriptor)}",
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
                Timber.tag(BtEngineState.TAG).d(
                    "All CCCDs armed → cccdReady=true context=%s",
                    context.connectionId,
                )
                activeHandlers.forEach { handler ->
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
            val route = resolveProtocolHandlerRoute(activeHandlers, charUuid, serviceUuid)
            if (route.owners.isEmpty()) {
                Timber.tag(BtEngineState.TAG).e(
                    "No active protocol handler owns characteristic=%s service=%s context=%s",
                    charUuid,
                    serviceUuid,
                    context.connectionId,
                )
                return
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
            val route = resolveProtocolHandlerRoute(activeHandlers, charUuid, serviceUuid)
            if (route.owners.isEmpty()) {
                Timber.tag(BtEngineState.TAG).e(
                    "No active protocol handler owns descriptor characteristic=%s service=%s context=%s",
                    charUuid,
                    serviceUuid,
                    context.connectionId,
                )
            }
            return route.owners
        }

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
}
