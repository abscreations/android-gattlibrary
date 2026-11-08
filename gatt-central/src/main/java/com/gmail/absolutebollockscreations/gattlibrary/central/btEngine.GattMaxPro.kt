package com.gmail.absolutebollockscreations.gattlibrary.central

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import androidx.annotation.RequiresPermission
import com.gmail.absolutebollockscreations.gattlibrary.core.catalog.MachineFamilyIds
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.MaxProUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids
import java.util.UUID

/** Scope the MTU-23 experiment to the explicitly selected MaxPro family. */
internal fun maxProMtuExperimentEnabled(candidate: String?): Boolean = candidate == MachineFamilyIds.MAXPRO

/** Android 14+ replaces the first requested MTU with 517; earlier releases accept our 23 request. */
internal fun maxProRequestedAttMtu(experiment: Boolean, androidSdk: Int, preferredMtu: Int): Int? = when {
    !experiment -> preferredMtu
    androidSdk >= 34 -> null
    else -> 23
}

/** Recognized MAXPRO links never activate canonical fitness-machine/sensor handlers. */
internal fun maxProHandlerServices(candidate: String?, services: Set<UUID>): Set<UUID> =
    if (candidate == MachineFamilyIds.MAXPRO) services.filterTo(linkedSetOf()) { it == MaxProUuids.SERVICE }
    else services

/** Pure inventory gate: aliases on another service must not satisfy this fingerprint. */
internal fun maxProSurfaceSupported(candidate: String?, notify: Boolean, cccd: Boolean,
    write: Boolean): Boolean = candidate == MachineFamilyIds.MAXPRO && notify && cccd && write

/** Passive measurement only. No MetricEmitter, FTMS capability or workout command exists here. */
internal class MaxProProtocolHandler : BtEngineGattCore.ProtocolHandler {
    override val primaryServices: Set<UUID> = setOf(MaxProUuids.SERVICE)
    override val extraCharacteristics: Set<UUID> = setOf(MaxProUuids.TELEMETRY, MaxProUuids.COMMAND)
    private var structuralMatch = false
    internal fun hasStructuralMatch(): Boolean = structuralMatch
    internal fun recordStructuralMatch() { structuralMatch = true }

    private var owner: ConnectionId? = null
    private var gate: MaxProSubscriptionGate? = null
    private var subscribedAtMs = 0L
    private var subscriptionDescriptor: BluetoothGattDescriptor? = null
    private var writeMode = StackEntry.WriteMode.WITHOUT_RESPONSE
    private var largeNotificationReported = false

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onServicesDiscovered(engine: BtEngine, gatt: BluetoothGatt,
        services: List<BluetoothGattService>): Boolean {
        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: return false
        if (!context.machineConnection) return false
        val candidate = engine.state.discoveredDevices.value.values.firstOrNull {
            canonicalScanAddress(it.address) == canonicalScanAddress(context.address)
        }?.matchCandidateId
        val service = services.singleOrNull { it.uuid == MaxProUuids.SERVICE } ?: return false
        val tx = service.getCharacteristic(MaxProUuids.TELEMETRY) ?: return false
        val rx = service.getCharacteristic(MaxProUuids.COMMAND) ?: return false
        // The shared queue addresses characteristics by UUID. Reject ambiguous databases rather
        // than allowing a command to resolve to an endpoint under a different service.
        if (services.sumOf { svc -> svc.characteristics.count { it.uuid == MaxProUuids.COMMAND } } != 1 ||
            services.sumOf { svc -> svc.characteristics.count { it.uuid == MaxProUuids.TELEMETRY } } != 1) return false
        val canNotify = tx.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val canWrite = rx.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        if (!maxProSurfaceSupported(candidate, canNotify, tx.getDescriptor(SigUuids.UUID_CCCD) != null,
                canWrite)) return false
        recordStructuralMatch()
        largeNotificationReported = false
        owner = context.connectionId
        subscribedAtMs = engine.elapsedRealtimeMs()
        gate = MaxProSubscriptionGate(subscribedAtMs)
        subscriptionDescriptor = tx.getDescriptor(SigUuids.UUID_CCCD)
        writeMode = if (rx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            StackEntry.WriteMode.WITHOUT_RESPONSE else StackEntry.WriteMode.WITH_RESPONSE
        engine.state.maxProStore.begin(context.connectionId)
        engine.enableNotifications(context, tx)
        return true
    }

    override fun onCccdPlanReady(engine: BtEngine, gatt: BluetoothGatt) {
        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: return
        if (context.connectionId != owner || !context.cccd.ready.value) return
        if (gate?.subscriptionsReady(engine.elapsedRealtimeMs()) != true) return
        subscribedAtMs = engine.elapsedRealtimeMs()
        engine.state.maxProStore.status(context.connectionId, MaxProLinkStatus.LISTENING)
    }

    override fun onDescriptorWrite(engine: BtEngine, gatt: BluetoothGatt,
        desc: BluetoothGattDescriptor, status: Int) {
        if (desc !== subscriptionDescriptor || desc.uuid != SigUuids.UUID_CCCD ||
            desc.characteristic?.uuid != MaxProUuids.TELEMETRY ||
            desc.characteristic?.service?.uuid != MaxProUuids.SERVICE) return
        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: return
        if (context.connectionId != owner) return
        val success = status == BluetoothGatt.GATT_SUCCESS
        gate?.descriptorCompleted(success)
        if (!success) {
            engine.state.maxProStore.status(context.connectionId, MaxProLinkStatus.FAILED)
        }
    }

    override fun onCharacteristicChanged(engine: BtEngine, gatt: BluetoothGatt,
        ch: BluetoothGattCharacteristic, value: ByteArray) {
        if (ch.uuid != MaxProUuids.TELEMETRY || ch.service?.uuid != MaxProUuids.SERVICE) return
        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: return
        if (context.connectionId != owner) return
        if (value.size > 20 && !largeNotificationReported) {
            largeNotificationReported = true
            timber.log.Timber.tag(BtEngineState.TAG).w(
                "MAXPRO default-MTU experiment: received value len=%d (>20); 20-byte constraint not established",
                value.size,
            )
        }
        // Firmware can emit before the descriptor callback: retain bytes during setup too.
        engine.state.maxProStore.feed(context.connectionId, value, engine.elapsedRealtimeMs()) { interpreted ->
            captureMaxProInterpretation(context.connectionId, interpreted)
        }
        if (gate?.ready == true) engine.state.maxProStore.status(context.connectionId, MaxProLinkStatus.LISTENING)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onLivenessTick(engine: BtEngine, gatt: BluetoothGatt) {
        val context = engine.state.connectionRegistry.contextForGatt(gatt) ?: return
        if (context.connectionId != owner) return
        val now = engine.elapsedRealtimeMs()
        val setup = gate ?: return
        if (setup.timedOut(now)) {
            engine.state.maxProStore.status(context.connectionId, MaxProLinkStatus.FAILED)
            return
        }
        if (setup.takeFirmwareQuery(now)) {
            engine.state.maxProStore.querySent(context.connectionId)
            engine.pushWrite(context, byteArrayOf(0x5b, 0x01, 0x5d), MaxProUuids.COMMAND,
                writeMode = writeMode, onComplete = { success ->
                    if (!success) engine.state.maxProStore.querySent(context.connectionId, failed = true)
                })
        }
        val last = engine.state.maxPro.value.lastTelemetryMillis
        if (setup.ready && now - (last ?: subscribedAtMs) > 5_000L)
            engine.state.maxProStore.status(context.connectionId, MaxProLinkStatus.STALE)
    }

    override fun onDisconnected(engine: BtEngine) {
        owner?.let { engine.state.maxProStore.end(it) }
        owner = null
        structuralMatch = false
        gate = null
        subscriptionDescriptor = null
    }
}
