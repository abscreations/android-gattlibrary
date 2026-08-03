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
//   The [build] function takes a [BtEngine] and a list of handlers and returns a single
//   BluetoothGattCallback instance that:
//     • Figures out whether it is an FTMS or HRM connection (based on handler.primaryServices).
//     • After discovery, populates an activeHandlers set with only the handlers whose primary
//       services are present on that connection.
//     • Routes all GATT I/O events to the appropriate handlers.
//
// -------------------------------------------------------------------------------------------------
// CONNECTION / DISCOVERY ORCHESTRATION
//   onConnectionStateChange:
//     • Updates the correct state flows in [BtEngineState]:
//         - connectionState / gatt       for FTMS connections.
//         - hrmConnectionState / gattHrm for HRM connections.
//     • On CONNECTED:
//         - Binds the GATT into the appropriate slot (gatt vs gattHrm).
//         - For FTMS, resets:
//             • CCCD orchestration flags (cccdReady=false, pendingCccdWrites=0)
//             • FTMS capabilities snapshot (ftmsCapabilities + legacy range flags) so that
//               a new link always starts “capability-blank” until range reads complete.
//         - Calls handler.onConnected(...).
//         - Requests HIGH connection priority and MTU=185 (best-effort).
//         - Starts service discovery.
//     • On DISCONNECTED:
//         - Calls handler.onDisconnected(...).
//         - Clears handler-specific state (HRM chars, FTMS control, queue, CCCD flags).
//         - For FTMS, also resets capabilities again as a safety net.
//         - Nulls the appropriate GATT slot and updates connection state.
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
//   All read/write/descriptor callbacks integrate with BtEngine’s serialized GATT queue:
//     • On success → engine.queueSucceed() to advance the queue.
//     • On failure →
//         - Logs and records a GATT error via BtEngineState.setGattErrorFor(...).
//         - Calls engine.queueFail(...) to let the queue decide recovery.
//     • onCharacteristicChanged(...) calls engine.handleStack() to allow the queue to
//       make progress on notifications/indications as well.
//
// -------------------------------------------------------------------------------------------------
// ROUTING & CCCD MANAGEMENT
//   Characteristic routing:
//     • routeToOwners(...) first looks for handlers that explicitly claim the characteristic
//       in extraCharacteristics; if none, it finds handlers that own the *service* containing
//       that characteristic; as a last resort, it fans out to all activeHandlers.
//
//   Descriptor routing:
//     • ownersByDescriptor(...) looks at both the parent service UUID and the characteristic
//       UUID (via primaryServices + extraCharacteristics) to find the right handlers.
//
//   FTMS CCCD tracking:
//     • isFtmsCallback flags callbacks created for FTMS profiles.
//     • isFtmsCccd(...) recognizes CCCD writes for FTMS data, status, and control point.
//     • On successful descriptor writes for FTMS CCCDs:
//         - Decrements state.pendingCccdWrites under a lock.
//         - When it reaches zero, flips state.cccdReady to true and logs the transition.
//
// -------------------------------------------------------------------------------------------------
// IMPLEMENTATION NOTES
//   • This core is deliberately profile-agnostic: it only knows about FTMS/HRM identities via
//     BluetoothIds constants and service UUIDs.
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
import androidx.annotation.RequiresPermission
import timber.log.Timber
import java.util.UUID

internal object BtEngineGattCore {

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
    }

    /** Build the routed callback over the provided protocol handlers. */
    fun build(
        engine: BtEngine,
        handlers: List<ProtocolHandler>
    ): BluetoothGattCallback = object : BluetoothGattCallback() {

        /** Is this callback instance for an HRM connection? (vs FTMS) */
        private val isHrmCallback: Boolean =
            handlers.any { it.primaryServices.contains(BluetoothIds.UUID_HRM_SERVICE) }

        /** Is this callback instance for an FTMS connection? */
        private val isFtmsCallback: Boolean =
            handlers.any { it.primaryServices.contains(BluetoothIds.UUID_FTMS_SERVICE) }

        /** Active handlers for this connection (populated post-discovery). */
        private val activeHandlers = LinkedHashSet<ProtocolHandler>()

        // ───────────────────────────────── Connection lifecycle ─────────────────────────────────

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Timber.tag(BtEngineState.TAG)
                .i(
                    "Gatt:onConnectionStateChange status=%d newState=%d (isHrm=%s isFtms=%s)",
                    status, newState, isHrmCallback, isFtmsCallback
                )

            // Reflect state on the correct flow
            if (isHrmCallback) {
                engine.state.setHrmConnectionState(newState)
            } else {
                // default to FTMS (current code only creates FTMS or HRM callbacks)
                engine.state.setConnectionState(newState)
            }

            if (status != GATT_SUCCESS && newState == STATE_DISCONNECTED) {
                engine.state.setGattErrorFor(gatt, "Disconnect status=$status")
            }

            when (newState) {
                STATE_CONNECTED -> {
                    // Bind the correct gatt slot
                    if (isHrmCallback) {
                        engine.state.gattHrm = gatt
                    } else if (isFtmsCallback) {
                        engine.state.gatt = gatt

                        // Only FTMS path manipulates these orchestration flags.
                        // Also reset FTMS capabilities snapshot at the start of a new link.
                        engine.state.setCccdReady(false)
                        engine.state.pendingCccdWrites = 0
                        engine.state.resetFtmsCapabilities()
                        Timber.tag(BtEngineState.TAG)
                            .d("FTMS connected → reset CCCD flags and capabilities snapshot")
                    }

                    // Allow handlers to prep
                    handlers.forEach { runCatching { it.onConnected(engine, gatt) } }

                    // Boost link & MTU, then discover
                    runCatching { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
                    runCatching { gatt.requestMtu(185) }
                    gatt.discoverServices()
                }

                STATE_DISCONNECTED -> {
                    // Notify handlers, then clear local state
                    activeHandlers.toList().forEach { runCatching { it.onDisconnected(engine) } }
                    activeHandlers.clear()

                    if (isHrmCallback) {
                        engine.state.gattHrm = null
                        engine.state.hrmMeasurementChar = null
                        engine.state.hrmCtrlPointChar = null
                        engine.state.hrmBodyLocChar = null
                        engine.state.setHrmConnectionState(STATE_DISCONNECTED)
                        engine.state.setExternalHeartRate(null)
                        HrmArbitration.reselect(engine)
                    } else if (isFtmsCallback) {
                        engine.state.gatt = null
                        engine.state.setHaveControl(false)

                        // Clear write queue
                        synchronized(engine.state.lock) {
                            engine.state.queue.clear()
                            engine.state.writeBusy = false
                        }

                        // Reset FTMS CCCD orchestration flags
                        engine.state.setCccdReady(false)
                        engine.state.pendingCccdWrites = 0

                        // Reset FTMS capabilities snapshot + legacy range flags so a future
                        // connection always starts from a clean capability state.
                        engine.state.resetFtmsCapabilities()
                        Timber.tag(BtEngineState.TAG)
                            .d("FTMS disconnected → reset queue, CCCD flags, and capabilities")
                    }
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != GATT_SUCCESS) return

            // Reset CCCD readiness only for FTMS path
            if (isFtmsCallback) {
                engine.state.setCccdReady(false)
                engine.state.pendingCccdWrites = 0
                Timber.tag(BtEngineState.TAG)
                    .d("onServicesDiscovered(FTMS) → reset CCCD orchestration counters")
            }

            val services = try {
                gatt.services ?: emptyList()
            } catch (_: SecurityException) {
                emptyList()
            }

            // Select handlers for present services
            val matched = handlers.filter { handler ->
                services.any { s -> handler.primaryServices.contains(s.uuid) }
            }

            // Let matched handlers wire notifications/reads
            matched.forEach { h ->
                val handled = runCatching { h.onServicesDiscovered(engine, gatt, services) }
                    .onFailure {
                        Timber.tag(BtEngineState.TAG)
                            .w(it, "handler.onServicesDiscovered failed")
                    }
                    .getOrDefault(false)
                if (handled) activeHandlers.add(h)
            }

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
            engine.handleStack() // advance queue on notify/indicate
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
            if (status == GATT_SUCCESS) {
                engine.queueSucceed()
            } else {
                val msg = "read failed status=$status uuid=${safeUuid(characteristic)}"
                engine.state.setGattErrorFor(gatt, msg)
                Timber.tag(BtEngineState.TAG).w(msg)
                engine.queueFail(msg)
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
            if (status == GATT_SUCCESS) {
                engine.queueSucceed()
            } else {
                val msg = "write failed status=$status uuid=${safeUuid(characteristic)}"
                engine.state.setGattErrorFor(gatt, msg)
                Timber.tag(BtEngineState.TAG).w(msg)
                engine.queueFail(msg)
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
            if (status == GATT_SUCCESS) {
                engine.queueSucceed()
            } else {
                val msg = "descriptor read failed status=$status uuid=${safeUuid(descriptor)}"
                engine.state.setGattErrorFor(gatt, msg)
                Timber.tag(BtEngineState.TAG).w(msg)
                engine.queueFail(msg)
            }
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
            if (status == GATT_SUCCESS) {
                engine.queueSucceed()
            } else {
                val msg = "descriptor write failed status=$status uuid=${safeUuid(descriptor)}"
                engine.state.setGattErrorFor(gatt, msg)
                Timber.tag(BtEngineState.TAG).w(msg)
                engine.queueFail(msg)
            }

            // Robust CCCD readiness: decrement only on successful FTMS CCCDs; flip ready when counter hits zero.
            if (isFtmsCallback && status == GATT_SUCCESS && isFtmsCccd(descriptor)) {
                var pending: Int
                var becameReady = false
                synchronized(engine.state.lock) {
                    if (engine.state.pendingCccdWrites > 0) engine.state.pendingCccdWrites--
                    pending = engine.state.pendingCccdWrites
                    if (pending == 0) {
                        engine.state.setCccdReady(true)
                        becameReady = true
                    }
                }
                val ch = descriptor.characteristic?.uuid
                Timber.tag(BtEngineState.TAG).d("CCCD written for %s → pending=%d", ch, pending)
                if (becameReady) {
                    Timber.tag(BtEngineState.TAG).d("All FTMS CCCDs armed → cccdReady=true")
                }
            }

            // Route to owners
            (ownersByDescriptor(descriptor) ?: activeHandlers).forEach {
                it.onDescriptorWrite(engine, gatt, descriptor, status)
            }
        }

        // ───────────────────────────────── Routing helpers ─────────────────────────────────

        private fun routeToOwners(
            gatt: BluetoothGatt,
            charUuid: UUID,
            action: (ProtocolHandler) -> Unit
        ) {
            val explicit = activeHandlers.filter { it.extraCharacteristics.contains(charUuid) }
            if (explicit.isNotEmpty()) {
                explicit.forEach(action); return
            }
            val ownerByService = activeHandlers.filter { handler ->
                val sUuid = characteristicServiceUuidOrNull(gatt, charUuid)
                sUuid != null && handler.primaryServices.contains(sUuid)
            }
            (ownerByService.ifEmpty { activeHandlers }).forEach(action)
        }

        private fun ownersByDescriptor(desc: BluetoothGattDescriptor): Set<ProtocolHandler>? {
            val sUuid = desc.characteristic?.service?.uuid ?: return null
            val charUuid = desc.characteristic?.uuid
            val matched = activeHandlers.filter { h ->
                h.primaryServices.contains(sUuid) ||
                        (charUuid != null && h.extraCharacteristics.contains(charUuid))
            }
            return matched.toSet()
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

            // Fallback: check the other slot (helps if some stacks delay services list)
            fun from(other: BluetoothGatt?): UUID? =
                other?.services?.firstNotNullOfOrNull { svc ->
                    svc.characteristics.firstOrNull { it.uuid == charUuid }?.let { svc.uuid }
                }

            return from(engine.state.gatt) ?: from(engine.state.gattHrm)
        }

        private fun isFtmsCccd(desc: BluetoothGattDescriptor): Boolean {
            if (desc.uuid != BluetoothIds.UUID_CCCD) return false
            val u = desc.characteristic?.uuid ?: return false
            return u == BluetoothIds.UUID_FITNESS_MACHINE_CONTROL_POINT ||
                    u == BluetoothIds.UUID_TREADMILL_DATA ||
                    u == BluetoothIds.UUID_ELLIPTICAL_DATA ||
                    u == BluetoothIds.UUID_STAIR_STEPPER_DATA ||
                    u == BluetoothIds.UUID_STAIR_CLIMBER_DATA ||
                    u == BluetoothIds.UUID_ROWING_MACHINE_DATA ||
                    u == BluetoothIds.UUID_BIKING_DATA ||
                    u == BluetoothIds.UUID_FITNESS_MACHINE_STATUS
        }

        private fun safeUuid(ch: BluetoothGattCharacteristic): String =
            runCatching { ch.uuid.toString() }.getOrElse { "perm-denied" }

        private fun safeUuid(desc: BluetoothGattDescriptor): String =
            runCatching { desc.uuid.toString() }.getOrElse { "perm-denied" }
    }
}
