package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import androidx.annotation.RequiresPermission
import java.util.UUID

internal data class RideOnGattInventory(val services: Map<UUID, Map<UUID, Int>>)
internal data class RideOnGattMatch(val serviceUuid: UUID, val properties: Map<UUID, Int>)

internal fun matchRideOnGatt(inventory: RideOnGattInventory): RideOnGattMatch? {
    val candidates = listOf(RideOnUuids.ORIGINAL_SERVICE, RideOnUuids.CORE2_SERVICE)
    for (serviceUuid in candidates) {
        val characteristics = inventory.services[serviceUuid] ?: continue
        val asyncTx = characteristics[RideOnUuids.ASYNC_TX] ?: continue
        val syncRx = characteristics[RideOnUuids.SYNC_RX] ?: continue
        val syncTx = characteristics[RideOnUuids.SYNC_TX] ?: continue
        if (asyncTx and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) continue
        if (syncRx and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE == 0) continue
        if (syncTx and BluetoothGattCharacteristic.PROPERTY_INDICATE == 0) continue
        return RideOnGattMatch(
            serviceUuid,
            mapOf(
                RideOnUuids.ASYNC_TX to asyncTx,
                RideOnUuids.SYNC_RX to syncRx,
                RideOnUuids.SYNC_TX to syncTx,
            ),
        )
    }
    return null
}

internal fun List<BluetoothGattService>.toRideOnInventory() = RideOnGattInventory(
    associate { service ->
        service.uuid to service.characteristics.associate { it.uuid to it.properties }
    },
)

/** Empty until section 15.3 qualifies an exact CORE 2 model/firmware pair. */
internal object RideOnActivationPolicy {
    private val qualifiedModels: Set<Pair<String, String>> = emptySet()
    fun isQualified(model: String?, firmware: String?): Boolean =
        model != null && firmware != null && model to firmware in qualifiedModels
}

internal fun rideOnAdapterCandidate(
    match: RideOnGattMatch,
    model: String?,
    firmware: String?,
    livenessTimeoutMs: Long,
): MachineAdapterCandidate? {
    if (!RideOnActivationPolicy.isQualified(model, firmware)) return null
    return MachineAdapterCandidate(
        adapterId = AdapterLivenessCoordinator.RIDEON_ADAPTER_ID,
        evidence = MatchEvidence(
            listOf(
                MatchEvidenceFact(
                    MatchEvidenceKind.GATT_SERVICE,
                    match.serviceUuid.toString(),
                    40,
                    CollisionRisk.LOW,
                    "Complete RideOn surface on hardware-qualified model/firmware",
                ),
            ),
        ),
        rank = AdapterLivenessCoordinator.RANK_RIDEON,
        requiresSetup = true,
        livenessTimeoutMs = livenessTimeoutMs,
    )
}

/** Discovery-only in 2.2: validates and records structure, performs no subscriptions or writes. */
internal class RideOnProtocolHandler : BtEngineGattCore.ProtocolHandler {
    override val primaryServices = setOf(RideOnUuids.ORIGINAL_SERVICE, RideOnUuids.CORE2_SERVICE)
    internal var structuralMatch: RideOnGattMatch? = null
        private set

    override fun onConnected(engine: BtEngine, gatt: BluetoothGatt) {
        structuralMatch = null
    }

    override fun onDisconnected(engine: BtEngine) {
        structuralMatch = null
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onServicesDiscovered(
        engine: BtEngine,
        gatt: BluetoothGatt,
        services: List<BluetoothGattService>,
    ): Boolean {
        structuralMatch = matchRideOnGatt(services.toRideOnInventory())
        return structuralMatch != null
    }

    internal fun recordStructuralMatch(match: RideOnGattMatch) {
        structuralMatch = match
    }
}
