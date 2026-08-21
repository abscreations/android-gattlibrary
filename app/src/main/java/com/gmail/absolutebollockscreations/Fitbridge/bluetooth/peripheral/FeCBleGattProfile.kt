package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.FeCBleSimulatorIdentity
import java.util.UUID

/** Full UUIDs observed in the Neo GATT discovery; none are alias-derived. */
internal object TacxGattIds {
    val SERVICE_A501: UUID = UUID.fromString("669aa501-0c08-969e-e211-86ad5062675f")
    val CHAR_AC01: UUID = UUID.fromString("669aac01-0c08-969e-e211-86ad5062675f")
    val PRESENTATION_FORMAT: UUID = UUID.fromString("00002904-0000-1000-8000-00805f9b34fb")
    val CYCLING_POWER_VECTOR: UUID = UUID.fromString("00002a64-0000-1000-8000-00805f9b34fb")
}

internal data class TacxAdvertisingPlan(
    val advertiseServices: List<UUID>,
    val scanResponseServices: List<UUID>,
    val manufacturerId: Int? = null,
    val manufacturerData: ByteArray? = null,
    val serviceData: Map<UUID, ByteArray> = emptyMap(),
    val includeNameInAdvertisement: Boolean,
    /** Captured GAP Appearance; Android's public legacy advertiser cannot emit it. */
    val capturedAppearance: Int? = null,
)

internal data class TacxGattCharacteristicDefinition(
    val uuid: UUID,
    val properties: Int,
    val descriptors: Set<UUID> = emptySet(),
)

internal data class TacxGattServiceDefinition(
    val uuid: UUID,
    val characteristics: List<TacxGattCharacteristicDefinition>,
)

internal fun tacxAdvertisingPlan(identity: FeCBleSimulatorIdentity, includeCps: Boolean): TacxAdvertisingPlan =
    when (identity) {
        FeCBleSimulatorIdentity.TACX_NEO -> TacxAdvertisingPlan(
            advertiseServices = emptyList(),
            scanResponseServices = listOf(BluetoothIds.UUID_CYCLING_POWER_SERVICE, BluetoothIds.UUID_CSC_SERVICE, BluetoothIds.UUID_DEVICE_INFORMATION_SERVICE, BluetoothIds.UUID_FEC_SERVICE),
            includeNameInAdvertisement = true,
            capturedAppearance = 0x0480,
        )
        FeCBleSimulatorIdentity.GENERIC -> TacxAdvertisingPlan(
            advertiseServices = buildList {
                add(BluetoothIds.UUID_FEC_SERVICE)
                if (includeCps) add(BluetoothIds.UUID_CYCLING_POWER_SERVICE)
            },
            scanResponseServices = emptyList(),
            includeNameInAdvertisement = false,
        )
    }

/** Captured read values plus deliberately conservative synthetic values needed for discovery. */
internal fun tacxReadValue(identity: FeCBleSimulatorIdentity, uuid: UUID, includeCps: Boolean): ByteArray? = when (uuid) {
    BluetoothIds.UUID_MANUFACTURER_NAME -> if (identity == FeCBleSimulatorIdentity.GENERIC) null else "Tacx".toByteArray()
    BluetoothIds.UUID_MODEL_NUMBER -> when (identity) {
        FeCBleSimulatorIdentity.TACX_NEO -> "Neo".toByteArray()
        FeCBleSimulatorIdentity.GENERIC -> null
    }
    BluetoothIds.UUID_SERIAL_NUMBER -> if (identity == FeCBleSimulatorIdentity.TACX_NEO) identity.syntheticSerial.toByteArray() else null
    BluetoothIds.UUID_HARDWARE_REVISION -> if (identity == FeCBleSimulatorIdentity.TACX_NEO) "0".toByteArray() else null
    BluetoothIds.UUID_FIRMWARE_REVISION -> when (identity) {
        FeCBleSimulatorIdentity.TACX_NEO -> "0.7.4".toByteArray()
        FeCBleSimulatorIdentity.GENERIC -> null
    }
    BluetoothIds.UUID_CYCLING_POWER_FEATURE -> if (includeCps) CyclingSensorEncoders.cyclingPowerFeature() else null
    BluetoothIds.UUID_CSC_FEATURE -> if (identity == FeCBleSimulatorIdentity.TACX_NEO) CyclingSensorEncoders.cscFeature() else null
    BluetoothIds.UUID_SENSOR_LOCATION -> if (includeCps || identity == FeCBleSimulatorIdentity.TACX_NEO) CyclingSensorEncoders.sensorLocation() else null
    TacxGattIds.CHAR_AC01 -> if (identity == FeCBleSimulatorIdentity.GENERIC) null else byteArrayOf(0)
    else -> null
}

/** FE-C plus the exact service/characteristic property surface observed for each Tacx identity. */
internal object FeCBleGattProfile {
    private const val READ = BluetoothGattCharacteristic.PROPERTY_READ
    private const val WRITE = BluetoothGattCharacteristic.PROPERTY_WRITE
    private const val WRITE_NR = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
    private const val NOTIFY = BluetoothGattCharacteristic.PROPERTY_NOTIFY
    private const val INDICATE = BluetoothGattCharacteristic.PROPERTY_INDICATE

    fun buildServices(includeControl: Boolean, includeCps: Boolean, identity: FeCBleSimulatorIdentity = FeCBleSimulatorIdentity.GENERIC): List<BluetoothGattService> = when (identity) {
        FeCBleSimulatorIdentity.GENERIC -> listOf(fec(includeControl)) + if (includeCps) listOf(CyclingServiceRegistry.buildCyclingPowerService()) else emptyList()
        else -> capturedManifest(identity, includeControl).map(::buildService)
    }

    /** Language-neutral capture inventory used by tests and by the Android GATT builder. */
    fun capturedManifest(identity: FeCBleSimulatorIdentity, includeControl: Boolean = true): List<TacxGattServiceDefinition> = when (identity) {
        FeCBleSimulatorIdentity.GENERIC -> emptyList()
        FeCBleSimulatorIdentity.TACX_NEO -> listOf(
            fecDefinition(includeControl),
            dService(BluetoothIds.UUID_DEVICE_INFORMATION_SERVICE, dChar(BluetoothIds.UUID_MANUFACTURER_NAME, READ), dChar(BluetoothIds.UUID_MODEL_NUMBER, READ), dChar(BluetoothIds.UUID_SERIAL_NUMBER, READ), dChar(BluetoothIds.UUID_HARDWARE_REVISION, READ), dChar(BluetoothIds.UUID_FIRMWARE_REVISION, READ)),
            dService(BluetoothIds.UUID_CSC_SERVICE, dChar(BluetoothIds.UUID_CSC_MEASUREMENT, NOTIFY, BluetoothIds.UUID_CCCD), dChar(BluetoothIds.UUID_CSC_FEATURE, READ), dChar(BluetoothIds.UUID_SENSOR_LOCATION, READ)),
            dService(BluetoothIds.UUID_CYCLING_POWER_SERVICE, dChar(BluetoothIds.UUID_CYCLING_POWER_MEASUREMENT, NOTIFY, BluetoothIds.UUID_CCCD), dChar(BluetoothIds.UUID_CYCLING_POWER_FEATURE, READ), dChar(BluetoothIds.UUID_SENSOR_LOCATION, READ), dChar(TacxGattIds.CYCLING_POWER_VECTOR, NOTIFY, BluetoothIds.UUID_CCCD), dChar(BluetoothIds.UUID_CYCLING_POWER_CONTROL_POINT, WRITE or INDICATE, BluetoothIds.UUID_CCCD)),
            dService(TacxGattIds.SERVICE_A501, dChar(TacxGattIds.CHAR_AC01, READ or WRITE, TacxGattIds.PRESENTATION_FORMAT)),
        )
    }

    private fun fecDefinition(includeControl: Boolean) = dService(BluetoothIds.UUID_FEC_SERVICE, *listOfNotNull(
        dChar(BluetoothIds.UUID_FEC_MEASUREMENT, NOTIFY, BluetoothIds.UUID_CCCD),
        if (includeControl) dChar(BluetoothIds.UUID_FEC_CONTROL, WRITE or WRITE_NR) else null,
    ).toTypedArray())

    private fun dService(uuid: UUID, vararg characteristics: TacxGattCharacteristicDefinition) = TacxGattServiceDefinition(uuid, characteristics.toList())
    private fun dChar(uuid: UUID, properties: Int, vararg descriptors: UUID) = TacxGattCharacteristicDefinition(uuid, properties, descriptors.toSet())

    private fun buildService(definition: TacxGattServiceDefinition) = BluetoothGattService(definition.uuid, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        definition.characteristics.forEach { item ->
            val characteristic = BluetoothGattCharacteristic(
                item.uuid,
                item.properties,
                (if (item.properties and READ != 0) BluetoothGattCharacteristic.PERMISSION_READ else 0) or
                    (if (item.properties and (WRITE or WRITE_NR) != 0) BluetoothGattCharacteristic.PERMISSION_WRITE else 0),
            )
            item.descriptors.forEach { uuid ->
                characteristic.addDescriptor(BluetoothGattDescriptor(uuid, if (uuid == BluetoothIds.UUID_CCCD) BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE else BluetoothGattDescriptor.PERMISSION_READ))
            }
            addCharacteristic(characteristic)
        }
    }

    private fun fec(includeControl: Boolean) = primary(BluetoothIds.UUID_FEC_SERVICE, listOfNotNull(
        characteristic(BluetoothIds.UUID_FEC_MEASUREMENT, NOTIFY, cccd = true),
        if (includeControl) characteristic(BluetoothIds.UUID_FEC_CONTROL, WRITE_NR) else null,
    ))

    private fun primary(uuid: UUID, characteristics: List<BluetoothGattCharacteristic>) = BluetoothGattService(uuid, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        characteristics.forEach(::addCharacteristic)
    }

    private fun characteristic(uuid: UUID, properties: Int, cccd: Boolean = false) =
        BluetoothGattCharacteristic(
            uuid,
            properties,
            (if (properties and READ != 0) BluetoothGattCharacteristic.PERMISSION_READ else 0) or
                (if (properties and (WRITE or WRITE_NR) != 0) BluetoothGattCharacteristic.PERMISSION_WRITE else 0),
        ).apply {
            if (cccd) addDescriptor(BluetoothGattDescriptor(BluetoothIds.UUID_CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        }
}

internal object FeCBleAdvertisingPlan { const val DEFAULT_LOCAL_NAME = "FitBridge FE-C" }
