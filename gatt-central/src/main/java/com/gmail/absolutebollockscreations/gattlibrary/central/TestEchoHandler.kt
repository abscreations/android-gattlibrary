package com.gmail.absolutebollockscreations.gattlibrary.central

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import androidx.annotation.RequiresPermission
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids

/**
 * Phase 0.5 hardware-only observer for a Garmin HRM that also exposes the standard RSC service.
 *
 * Debug builds register this as a companion to [HrmProtocolHandler]. It subscribes to RSC
 * Measurement solely to prove same-GATT multi-handler routing and merged CCCD behavior. It never
 * parses the payload or writes workout metrics, so it cannot become an accidental Phase 1 RSC
 * implementation.
 */
internal class TestEchoHandler : BtEngineGattCore.ProtocolHandler {

    override val primaryServices: Set<UUID> = setOf(SigUuids.UUID_RSC_SERVICE)
    override val extraCharacteristics: Set<UUID> = setOf(SigUuids.UUID_RSC_MEASUREMENT)

    private val instanceId = nextInstanceId.incrementAndGet()
    private var linkGeneration: Long? = null
    private var activationCount = 0
    private var notificationCount = 0
    private var unexpectedNotificationCount = 0
    private var readCount = 0
    private var writeCount = 0
    private var descriptorWriteCount = 0
    private var cccdReadyCount = 0
    private var disconnectCount = 0

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onServicesDiscovered(
        engine: BtEngine,
        gatt: BluetoothGatt,
        services: List<BluetoothGattService>,
    ): Boolean {
        val rscService = services.firstOrNull { it.uuid == SigUuids.UUID_RSC_SERVICE }
            ?: return false

        logGattInventory(services)

        val measurement = rscService.getCharacteristic(SigUuids.UUID_RSC_MEASUREMENT)
        if (measurement == null) {
            Timber.tag(TAG).w(
                "PHASE05 echo declined instance=%d reason=missing-rsc-measurement service=%s",
                instanceId,
                SigUuids.UUID_RSC_SERVICE,
            )
            return false
        }

        val context = engine.state.connectionRegistry.contextForGatt(gatt)
        if (context == null) {
            Timber.tag(TAG).w(
                "PHASE05 echo declined instance=%d reason=unregistered-gatt",
                instanceId,
            )
            return false
        }

        linkGeneration = context.connectionId.linkGeneration
        activationCount += 1
        Timber.tag(TAG).i(
            "PHASE05 echo activated instance=%d generation=%d activationCount=%d " +
                "service=%s characteristic=%s properties=%s",
            instanceId,
            context.connectionId.linkGeneration,
            activationCount,
            SigUuids.UUID_RSC_SERVICE,
            SigUuids.UUID_RSC_MEASUREMENT,
            propertiesString(measurement.properties),
        )

        engine.enableNotifications(context, measurement)
        return true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCharacteristicChanged(
        engine: BtEngine,
        gatt: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        value: ByteArray,
    ) {
        notificationCount += 1
        val expected = ch.uuid == SigUuids.UUID_RSC_MEASUREMENT
        if (!expected) unexpectedNotificationCount += 1

        val message =
            "PHASE05 echo notification instance=%d generation=%s characteristic=%s " +
                "length=%d count=%d expected=%s unexpectedCount=%d"
        if (expected) {
            Timber.tag(TAG).i(
                message,
                instanceId,
                generationLabel(),
                ch.uuid,
                value.size,
                notificationCount,
                true,
                unexpectedNotificationCount,
            )
        } else {
            Timber.tag(TAG).w(
                message,
                instanceId,
                generationLabel(),
                ch.uuid,
                value.size,
                notificationCount,
                false,
                unexpectedNotificationCount,
            )
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCharacteristicRead(
        engine: BtEngine,
        gatt: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        readCount += 1
        Timber.tag(TAG).i(
            "PHASE05 echo read instance=%d generation=%s characteristic=%s length=%d " +
                "status=%d count=%d",
            instanceId,
            generationLabel(),
            ch.uuid,
            value.size,
            status,
            readCount,
        )
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCharacteristicWrite(
        engine: BtEngine,
        gatt: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        status: Int,
    ) {
        writeCount += 1
        Timber.tag(TAG).i(
            "PHASE05 echo write instance=%d generation=%s characteristic=%s status=%d count=%d",
            instanceId,
            generationLabel(),
            ch.uuid,
            status,
            writeCount,
        )
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onDescriptorWrite(
        engine: BtEngine,
        gatt: BluetoothGatt,
        desc: BluetoothGattDescriptor,
        status: Int,
    ) {
        descriptorWriteCount += 1
        Timber.tag(TAG).i(
            "PHASE05 echo descriptor instance=%d generation=%s characteristic=%s " +
                "descriptor=%s status=%d count=%d",
            instanceId,
            generationLabel(),
            desc.characteristic?.uuid,
            desc.uuid,
            status,
            descriptorWriteCount,
        )
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCccdPlanReady(engine: BtEngine, gatt: BluetoothGatt) {
        cccdReadyCount += 1
        Timber.tag(TAG).i(
            "PHASE05 echo merged-cccd-ready instance=%d generation=%s count=%d",
            instanceId,
            generationLabel(),
            cccdReadyCount,
        )
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onDisconnected(engine: BtEngine) {
        disconnectCount += 1
        Timber.tag(TAG).i(
            "PHASE05 echo disconnected instance=%d generation=%s disconnectCount=%d " +
                "activations=%d notifications=%d unexpectedNotifications=%d reads=%d writes=%d " +
                "descriptorWrites=%d cccdReady=%d",
            instanceId,
            generationLabel(),
            disconnectCount,
            activationCount,
            notificationCount,
            unexpectedNotificationCount,
            readCount,
            writeCount,
            descriptorWriteCount,
            cccdReadyCount,
        )
    }

    private fun generationLabel(): String = linkGeneration?.toString() ?: "unknown"

    private fun logGattInventory(services: List<BluetoothGattService>) {
        Timber.tag(TAG).i(
            "PHASE05 GATT inventory instance=%d services=%d",
            instanceId,
            services.size,
        )
        services.forEach { service ->
            Timber.tag(TAG).i(
                "PHASE05 GATT service instance=%d uuid=%s characteristics=%d",
                instanceId,
                service.uuid,
                service.characteristics.size,
            )
            service.characteristics.forEach { characteristic ->
                val descriptors = characteristic.descriptors
                    .map { it.uuid.toString() }
                    .sorted()
                    .joinToString(prefix = "[", postfix = "]")
                Timber.tag(TAG).i(
                    "PHASE05 GATT characteristic instance=%d service=%s uuid=%s " +
                        "properties=%s descriptors=%s",
                    instanceId,
                    service.uuid,
                    characteristic.uuid,
                    propertiesString(characteristic.properties),
                    descriptors,
                )
            }
        }
    }

    private fun propertiesString(properties: Int): String = buildList {
        if (properties and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) add("BROADCAST")
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            add("WRITE_NO_RESPONSE")
        }
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) {
            add("SIGNED_WRITE")
        }
        if (properties and BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS != 0) {
            add("EXTENDED_PROPERTIES")
        }
    }.joinToString(separator = "|", prefix = "[", postfix = "]")

    private companion object {
        const val TAG = "Phase05Hw"
        val nextInstanceId = AtomicLong(0)
    }
}
