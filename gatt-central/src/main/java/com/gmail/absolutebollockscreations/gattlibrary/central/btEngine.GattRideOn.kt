package com.gmail.absolutebollockscreations.gattlibrary.central

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import androidx.annotation.RequiresPermission
import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.RideOnServiceForm
import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.RideOnSpec

internal data class RideOnGattInventory(val services: Map<UUID, Map<UUID, Int>>)
internal data class RideOnGattMatch(val serviceUuid: UUID, val properties: Map<UUID, Int>)

internal fun matchRideOnGatt(inventory: RideOnGattInventory): RideOnGattMatch? {
    val candidates = listOf(RideOnSpec.ORIGINAL_SERVICE, RideOnSpec.CORE2_SERVICE)
    for (serviceUuid in candidates) {
        val characteristics = inventory.services[serviceUuid] ?: continue
        val asyncTx = characteristics[RideOnSpec.ASYNC_TX] ?: continue
        val syncRx = characteristics[RideOnSpec.SYNC_RX] ?: continue
        val syncTx = characteristics[RideOnSpec.SYNC_TX] ?: continue
        if (asyncTx and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) continue
        if (syncRx and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE == 0) continue
        if (syncTx and BluetoothGattCharacteristic.PROPERTY_INDICATE == 0) continue
        return RideOnGattMatch(
            serviceUuid,
            mapOf(
                RideOnSpec.ASYNC_TX to asyncTx,
                RideOnSpec.SYNC_RX to syncRx,
                RideOnSpec.SYNC_TX to syncTx,
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
    override val primaryServices = setOf(RideOnSpec.ORIGINAL_SERVICE, RideOnSpec.CORE2_SERVICE)
    internal var structuralMatch: RideOnGattMatch? = null
        private set
    internal var controlSession: RideOnControlSession? = null
        private set
    private var subscriptionGate: RideOnSubscriptionGate? = null

    override fun onConnected(engine: BtEngine, gatt: BluetoothGatt) {
        resetLinkState()
    }

    override fun onDisconnected(engine: BtEngine) {
        resetLinkState()
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

    internal fun bindQualifiedSession(session: RideOnControlSession) {
        controlSession?.close()
        controlSession = session
        subscriptionGate = RideOnSubscriptionGate(session.connectionId)
    }

    internal fun createQualifiedSession(
        engine: BtEngine,
        context: ConnectionContext,
        model: String?,
        firmware: String?,
    ): Boolean {
        val match = structuralMatch ?: return false
        if (!RideOnActivationPolicy.isQualified(model, firmware)) return false
        val gatt = context.gatt ?: return false
        val transport = BtEngineGattTransport(engine, context.connectionId)
        val form = if (match.serviceUuid == RideOnSpec.CORE2_SERVICE) RideOnServiceForm.CORE2
        else RideOnServiceForm.ORIGINAL
        val session = RideOnControlSession(
            transport,
            form,
            onRidingData = { telemetry -> emitRideOnTelemetry(engine, gatt, telemetry) },
            onControlGranted = {
                engine.state.adapterLiveness.setupSucceeded(context, MonotonicMetricClock.SYSTEM.nowMs())
            },
            onInitializationFailed = {
                engine.state.adapterLiveness.setupFailed(context, "RideOn initialization failed")
            },
        )
        bindQualifiedSession(session)
        if (!transport.enableNotifications(RideOnSpec.ASYNC_TX) ||
            !transport.enableNotifications(RideOnSpec.SYNC_TX)
        ) {
            resetLinkState()
            return false
        }
        return true
    }

    internal fun markSubscriptionReady(connectionId: ConnectionId, characteristic: UUID): Boolean {
        val bothReady = subscriptionGate?.markReady(connectionId, characteristic) == true
        return bothReady && controlSession?.startAfterSubscriptionsReady() == true
    }

    internal fun handlePacket(connectionId: ConnectionId, characteristic: UUID, value: ByteArray): Boolean {
        val session = controlSession ?: return false
        if (session.connectionId != connectionId) return false
        session.onPacket(connectionId, characteristic, value)
        return true
    }

    private fun resetLinkState() {
        controlSession?.close()
        controlSession = null
        subscriptionGate = null
        structuralMatch = null
    }

    override fun onCharacteristicChanged(
        engine: BtEngine,
        gatt: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: return
        handlePacket(context.connectionId, ch.uuid, value)
    }
}

private fun emitRideOnTelemetry(engine: BtEngine, gatt: BluetoothGatt, telemetry: RideOnTelemetry) {
    val emitter = engine.metricEmitterFor(
        gatt, RideOnSpec.ASYNC_TX, MetricProtocol.VENDOR, MetricSourceClass.VENDOR_NATIVE,
    ) ?: return
    telemetry.powerWatts?.let { emitter.emit(MetricKind.POWER, it.toDouble()) }
    telemetry.cadenceRpm?.let { emitter.emit(MetricKind.CADENCE, it.toDouble()) }
    telemetry.speedKph?.let { emitter.emit(MetricKind.SPEED, it) }
    telemetry.heartRateBpm?.let { emitter.emit(MetricKind.HEART_RATE, it.toDouble()) }
}
