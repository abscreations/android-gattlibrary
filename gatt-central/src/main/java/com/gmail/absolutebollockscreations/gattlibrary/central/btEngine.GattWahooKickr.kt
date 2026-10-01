package com.gmail.absolutebollockscreations.gattlibrary.central

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import androidx.annotation.RequiresPermission
import com.gmail.absolutebollockscreations.gattlibrary.core.capture.BleCapture
import com.gmail.absolutebollockscreations.gattlibrary.core.capture.ErrorEvent
import timber.log.Timber
import java.util.UUID

/** Android-free structural inventory used to keep adversarial GATT matching unit-testable. */
internal data class WahooKickrGattInventory(
    val services: Map<UUID, Map<UUID, Int>>,
)

internal data class WahooKickrGattMatch(
    val markerServices: Set<UUID>,
    val controlProperties: Int,
    val rideOnServices: Set<UUID>,
)

/**
 * Require the complete legacy fingerprint; UUID presence without parent and properties is not
 * enough. Marker services corroborate this as a Wahoo surface but are never command targets.
 */
internal fun matchWahooKickrGatt(inventory: WahooKickrGattInventory): WahooKickrGattMatch? {
    val cpsCharacteristics = inventory.services[WahooKickrUuids.CYCLING_POWER_SERVICE]
        ?: return null
    val properties = cpsCharacteristics[WahooKickrUuids.CONTROL] ?: return null
    if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0) return null
    if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE == 0) return null

    val markers = inventory.services.keys.intersect(WahooKickrUuids.MARKER_SERVICES)
    if (markers.isEmpty()) return null

    return WahooKickrGattMatch(
        markerServices = markers,
        controlProperties = properties,
        rideOnServices = inventory.services.keys.intersect(WahooKickrUuids.RIDEON_SERVICES),
    )
}

internal fun List<BluetoothGattService>.toWahooKickrInventory(): WahooKickrGattInventory =
    WahooKickrGattInventory(
        associate { service ->
            service.uuid to service.characteristics.associate { characteristic ->
                characteristic.uuid to characteristic.properties
            }
        },
    )

internal fun shouldStartWahooSession(selectedAdapterId: String?, cccdReady: Boolean): Boolean =
    cccdReady && selectedAdapterId == AdapterLivenessCoordinator.WAHOO_KICKR_ADAPTER_ID

/**
 * Legacy KICKR handler. Phase 10.4 owns CCCD/session lifecycle but deliberately exposes no
 * downstream endpoint or target capability; those remain a later, separately gated unit.
 */
internal class WahooKickrProtocolHandler : BtEngineGattCore.ProtocolHandler {
    override val primaryServices: Set<UUID> = setOf(WahooKickrUuids.CYCLING_POWER_SERVICE)
    override val extraCharacteristics: Set<UUID> = setOf(WahooKickrUuids.CONTROL)

    internal var structuralMatch: WahooKickrGattMatch? = null
        private set
    internal var controlSession: WahooKickrControlSession? = null
        private set

    internal fun resetLinkState() {
        controlSession?.close()
        controlSession = null
        structuralMatch = null
    }

    /** Test seam for liveness plumbing without constructing Android framework GATT objects. */
    internal fun recordStructuralMatch(match: WahooKickrGattMatch) {
        structuralMatch = match
    }

    /** Production and unit tests share these exact generation/characteristic gates. */
    internal fun bindControlSession(session: WahooKickrControlSession) {
        controlSession?.close()
        controlSession = session
    }

    internal fun handleCccdReady(connectionId: ConnectionId): Boolean {
        val session = controlSession ?: return false
        if (session.connectionId != connectionId) return false
        return session.startAfterCccdReady()
    }

    internal fun handleControlIndication(
        connectionId: ConnectionId,
        characteristicUuid: UUID,
        value: ByteArray,
    ): Boolean {
        val session = controlSession ?: return false
        if (characteristicUuid != WahooKickrUuids.CONTROL || session.connectionId != connectionId) {
            return false
        }
        session.onIndication(connectionId, value)
        return true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onConnected(engine: BtEngine, gatt: BluetoothGatt) = resetLinkState()

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onDisconnected(engine: BtEngine) = resetLinkState()

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onServicesDiscovered(
        engine: BtEngine,
        gatt: BluetoothGatt,
        services: List<BluetoothGattService>,
    ): Boolean {
        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: run {
            Timber.tag(BtEngineState.TAG).w("KICKR discovery ignored for unregistered GATT")
            return false
        }
        val match = matchWahooKickrGatt(services.toWahooKickrInventory()) ?: return false
        val controlCharacteristic = services
            .first { it.uuid == WahooKickrUuids.CYCLING_POWER_SERVICE }
            .characteristics
            .first { it.uuid == WahooKickrUuids.CONTROL }
        structuralMatch = match
        val transport = BtEngineGattTransport(engine, context.connectionId)
        bindControlSession(
            WahooKickrControlSession(
                transport = transport,
                onOutcome = { opcode, outcome ->
                    BleCapture.emit(
                        ErrorEvent(
                            conn = BleCapture.labelFor(context.connectionId.address),
                            where = "wahoo_control",
                            message = "opcode=0x${opcode.toString(16)} outcome=$outcome",
                        ),
                    )
                },
                onCommandAccepted = { command, _ ->
                    if (command is WahooKickrCommand.WheelCircumference &&
                        engine.state.connectionRegistry.contextFor(context.connectionId) === context
                    ) {
                        engine.acceptWheelCircumference(command.millimetres)
                        engine.serviceControlSink?.onWheelCircumferenceChangedMm(command.millimetres)
                    }
                },
                onUnrecognizedResponse = { response ->
                    // The exact bytes are already retained by CaptureHooks.notified before handler
                    // dispatch. This companion event adds classification and length only.
                    BleCapture.emit(
                        ErrorEvent(
                            conn = BleCapture.labelFor(context.connectionId.address),
                            where = "wahoo_response",
                            message = "classification=${response::class.java.simpleName} " +
                                "length=${response.raw.size}",
                        ),
                    )
                },
                onControlGranted = {
                    if (engine.state.connectionRegistry.contextFor(context.connectionId) === context) {
                        engine.state.adapterLiveness.setupSucceeded(
                            context,
                            MonotonicMetricClock.SYSTEM.nowMs(),
                        )
                    }
                },
                onInitializationFailed = {
                    if (engine.state.connectionRegistry.contextFor(context.connectionId) === context) {
                        engine.state.adapterLiveness.setupFailed(
                            context,
                            "legacy KICKR unlock failed",
                        )
                    }
                },
                onControlStateChanged = { engine.state.refreshDeviceCapabilities() },
            ),
        )
        // Use the transport so CCCD queuing stays on this ConnectionContext's shared GATT queue.
        // The session does not start here; merged-plan readiness calls onCccdPlanReady below.
        if (!transport.enableNotifications(controlCharacteristic.uuid)) {
            resetLinkState()
            return false
        }
        Timber.tag(BtEngineState.TAG).i(
            "Legacy KICKR surface matched on link generation %d; markers=%d rideOn=%d",
            context.connectionId.linkGeneration,
            match.markerServices.size,
            match.rideOnServices.size,
        )
        return true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCccdPlanReady(engine: BtEngine, gatt: BluetoothGatt) {
        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: return
        startSessionIfSelected(engine, context)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCharacteristicChanged(
        engine: BtEngine,
        gatt: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: return
        handleControlIndication(context.connectionId, ch.uuid, value)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onLivenessTick(engine: BtEngine, gatt: BluetoothGatt) {
        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: return
        // AdapterLivenessCoordinator ticks immediately before handlers. If silent FTMS has just
        // promoted Wahoo, this is the first safe moment to unlock the fallback—never before.
        startSessionIfSelected(engine, context)
    }

    private fun startSessionIfSelected(engine: BtEngine, context: ConnectionContext): Boolean {
        val selected = engine.state.adapterLiveness.selectedAdapterId(context.connectionId)
        if (!shouldStartWahooSession(selected, context.cccd.ready.value)) return false
        return handleCccdReady(context.connectionId)
    }
}
