package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import androidx.annotation.RequiresPermission
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

/**
 * Write-free Phase-10.1 handler. It proves the control surface belongs to this link, then retains
 * only structural evidence for adapter selection. A later work unit owns CCCD and unlock writes.
 */
internal class WahooKickrProtocolHandler : BtEngineGattCore.ProtocolHandler {
    override val primaryServices: Set<UUID> = setOf(WahooKickrUuids.CYCLING_POWER_SERVICE)
    override val extraCharacteristics: Set<UUID> = setOf(WahooKickrUuids.CONTROL)

    internal var structuralMatch: WahooKickrGattMatch? = null
        private set

    internal fun resetLinkState() {
        structuralMatch = null
    }

    /** Test seam for liveness plumbing without constructing Android framework GATT objects. */
    internal fun recordStructuralMatch(match: WahooKickrGattMatch) {
        structuralMatch = match
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
        structuralMatch = match
        Timber.tag(BtEngineState.TAG).i(
            "Legacy KICKR surface matched on link generation %d; markers=%d rideOn=%d",
            context.connectionId.linkGeneration,
            match.markerServices.size,
            match.rideOnServices.size,
        )
        return true
    }
}
