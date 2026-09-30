package com.gmail.absolutebollockscreations.gattlibrary.simulator

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds
import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.FtmsMeasurementCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.advertisedName
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.LifeFitnessUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids

/** Capture-derived legacy advertising identity for one Life Fitness console persona. */
internal data class LifeFitnessAdvertisingPlan(
    val localName: String,
    val advertisedServices: List<UUID>,
    val serviceDataService: UUID,
    val serviceData: ByteArray,
    val advertisingBytes: Int,
    val scanResponseBytes: Int,
) {
    init {
        require(advertisingBytes <= MAX_LEGACY_BYTES)
        require(scanResponseBytes <= MAX_LEGACY_BYTES)
    }

    companion object {
        const val MAX_LEGACY_BYTES = 31

        fun create(machine: LifeFitnessMachine): LifeFitnessAdvertisingPlan {
            val name = SimulatorPersona.LifeFitness(machine).advertisedName
            return LifeFitnessAdvertisingPlan(
                localName = name,
                // Physical captures advertise only the SIG FTMS UUID. B90/B91 are
                // discovered after connection and must not leak into advertising.
                advertisedServices = listOf(FtmsUuids.UUID_FTMS_SERVICE),
                serviceDataService = FtmsUuids.UUID_FTMS_SERVICE,
                serviceData = machine.equipmentServiceData.copyOf(),
                // Flags + TX power + complete 16-bit service list + FTMS service data.
                advertisingBytes = 3 + 3 + 4 + 7,
                // Complete local name. The adapter owns the actual name bytes.
                scanResponseBytes = 2 + name.toByteArray(Charsets.UTF_8).size,
            )
        }
    }
}

/** Language-neutral manifest used by tests to guard the captured GATT inventory. */
internal data class LifeFitnessGattCharacteristic(
    val uuid: UUID,
    val properties: Int,
)

internal data class LifeFitnessGattService(
    val uuid: UUID,
    val characteristics: List<LifeFitnessGattCharacteristic>,
    val primary: Boolean,
)

internal object LifeFitnessGattProfile {
    private const val READ = BluetoothGattCharacteristic.PROPERTY_READ
    private const val WRITE = BluetoothGattCharacteristic.PROPERTY_WRITE
    private const val WRITE_NO_RESPONSE = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
    private const val NOTIFY = BluetoothGattCharacteristic.PROPERTY_NOTIFY
    private const val INDICATE = BluetoothGattCharacteristic.PROPERTY_INDICATE

    fun manifest(machine: LifeFitnessMachine): List<LifeFitnessGattService> = listOf(
        service(
            SigUuids.UUID_BOND_MANAGEMENT_SERVICE,
            characteristic(SigUuids.UUID_BOND_MANAGEMENT_FEATURE, READ),
            characteristic(SigUuids.UUID_BOND_MANAGEMENT_CONTROL_POINT, WRITE or WRITE_NO_RESPONSE),
        ),
        service(
            FtmsUuids.UUID_FTMS_SERVICE,
            characteristic(FtmsUuids.UUID_FITNESS_MACHINE_FEATURE, READ),
            characteristic(machine.measurementUuid, NOTIFY),
            characteristic(FtmsUuids.UUID_TRAINING_STATUS, READ or NOTIFY),
            characteristic(FtmsUuids.UUID_FITNESS_MACHINE_STATUS, NOTIFY),
        ),
        service(LifeFitnessUuids.UUID_LF_SECONDARY_SERVICE),
        service(
            LifeFitnessUuids.UUID_LF_FD00_SERVICE,
            characteristic(LifeFitnessUuids.UUID_LF_FD01, WRITE_NO_RESPONSE),
            characteristic(LifeFitnessUuids.UUID_LF_FD02, READ),
            characteristic(LifeFitnessUuids.UUID_LF_FD05, WRITE or INDICATE),
        ),
        service(
            SigUuids.UUID_DEVICE_INFORMATION_SERVICE,
            characteristic(SigUuids.UUID_MANUFACTURER_NAME, READ),
            characteristic(SigUuids.UUID_MODEL_NUMBER, READ),
            characteristic(SigUuids.UUID_SERIAL_NUMBER, READ),
            characteristic(SigUuids.UUID_HARDWARE_REVISION, READ),
            characteristic(SigUuids.UUID_FIRMWARE_REVISION, READ),
            characteristic(SigUuids.UUID_SOFTWARE_REVISION, READ),
            characteristic(SigUuids.UUID_PNP_ID, READ),
        ),
        service(
            LifeFitnessUuids.UUID_LF_USER_SERVICE,
            *userCharacteristics().toTypedArray(),
        ),
        service(
            LifeFitnessUuids.UUID_LF_CONTROL_SERVICE,
            characteristic(LifeFitnessUuids.UUID_LF_B90_READ_5AA9, READ),
            characteristic(LifeFitnessUuids.UUID_LF_STATUS, NOTIFY),
            characteristic(LifeFitnessUuids.UUID_LF_CONTROL_1, WRITE),
            characteristic(LifeFitnessUuids.UUID_LF_B90_RW_NOTIFY_52A6, READ or WRITE or NOTIFY),
            characteristic(LifeFitnessUuids.UUID_LF_B90_NOTIFY_5AA8, NOTIFY),
            characteristic(LifeFitnessUuids.UUID_LF_B90_READ_ECA0, READ),
            characteristic(LifeFitnessUuids.UUID_LF_B90_READ_EF5C, READ),
            characteristic(LifeFitnessUuids.UUID_LF_B90_WRITE_F4E8, WRITE or WRITE_NO_RESPONSE),
            characteristic(LifeFitnessUuids.UUID_LF_B90_WRITE_F164, WRITE or WRITE_NO_RESPONSE),
            characteristic(LifeFitnessUuids.UUID_LF_B90_WRITE_F330, WRITE or WRITE_NO_RESPONSE),
            characteristic(LifeFitnessUuids.UUID_LF_B90_WRITE_FB00, WRITE or WRITE_NO_RESPONSE),
            characteristic(LifeFitnessUuids.UUID_LF_B90_WRITE_FD94, WRITE or WRITE_NO_RESPONSE),
            characteristic(LifeFitnessUuids.UUID_LF_CONTROL_2, WRITE),
            characteristic(LifeFitnessUuids.UUID_LF_B90_WRITE_6AB6, WRITE),
        ),
    )

    fun buildServices(machine: LifeFitnessMachine): List<BluetoothGattService> =
        manifest(machine).map { definition ->
            BluetoothGattService(
                definition.uuid,
                if (definition.primary) {
                    BluetoothGattService.SERVICE_TYPE_PRIMARY
                } else {
                    BluetoothGattService.SERVICE_TYPE_SECONDARY
                },
            ).also { service ->
                definition.characteristics.forEach { item ->
                    val permissions =
                        (if (item.properties and READ != 0) BluetoothGattCharacteristic.PERMISSION_READ else 0) or
                            (if (item.properties and (WRITE or WRITE_NO_RESPONSE) != 0) {
                                BluetoothGattCharacteristic.PERMISSION_WRITE
                            } else {
                                0
                            })
                    val characteristic = BluetoothGattCharacteristic(item.uuid, item.properties, permissions)
                    if (item.properties and (NOTIFY or INDICATE) != 0) {
                        characteristic.addDescriptor(
                            BluetoothGattDescriptor(
                                SigUuids.UUID_CCCD,
                                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                            ),
                        )
                    }
                    service.addCharacteristic(characteristic)
                }
            }
        }

    private fun userCharacteristics(): List<LifeFitnessGattCharacteristic> = listOf(
        SigUuids.UUID_USER_FIRST_NAME,
        SigUuids.UUID_USER_LAST_NAME,
        SigUuids.UUID_USER_EMAIL,
        SigUuids.UUID_USER_DATE_OF_BIRTH,
        SigUuids.UUID_USER_AGE,
        SigUuids.UUID_USER_GENDER,
        SigUuids.UUID_USER_LANGUAGE,
        SigUuids.UUID_USER_VO2_MAX,
        SigUuids.UUID_USER_MAX_RECOMMENDED_HEART_RATE,
        LifeFitnessUuids.UUID_LF_VENDOR_TOKEN_1,
        LifeFitnessUuids.UUID_LF_VENDOR_TOKEN_2,
        SigUuids.UUID_USER_WEIGHT,
        SigUuids.UUID_USER_HEIGHT,
    ).map { characteristic(it, READ or WRITE or WRITE_NO_RESPONSE) }

    private fun service(
        uuid: UUID,
        vararg characteristics: LifeFitnessGattCharacteristic,
    ) = LifeFitnessGattService(
        uuid = uuid,
        characteristics = characteristics.toList(),
        primary = uuid != LifeFitnessUuids.UUID_LF_SECONDARY_SERVICE,
    )

    private fun characteristic(uuid: UUID, properties: Int) =
        LifeFitnessGattCharacteristic(uuid, properties)
}

/** Measurement-feature word derived from the fields in the captured Life Fitness encoders. */
internal fun lifeFitnessFeatureBits(machine: LifeFitnessMachine): Int = when (machine) {
    LifeFitnessMachine.TREADMILL -> FtmsMeasurementCapabilities(
        averageSpeed = true,
        totalDistance = true,
        inclination = true,
        elevationGain = true,
    )
    LifeFitnessMachine.CROSS_TRAINER -> FtmsMeasurementCapabilities(
        averageSpeed = true,
        totalDistance = true,
        elevationGain = true,
        stepCount = true,
        strideCount = true,
        resistance = true,
        power = true,
    )
    LifeFitnessMachine.STAIR_CLIMBER -> FtmsMeasurementCapabilities(
        elevationGain = true,
        stepCount = true,
        strideCount = true,
    )
    LifeFitnessMachine.INDOOR_BIKE -> FtmsMeasurementCapabilities(
        averageSpeed = true,
        cadence = true,
        totalDistance = true,
        resistance = true,
        power = true,
    )
}.toFeatureBits()

internal fun lifeFitnessFeatureValue(machine: LifeFitnessMachine): ByteArray {
    val bits = lifeFitnessFeatureBits(machine)
    return byteArrayOf(
        bits.toByte(),
        (bits ushr 8).toByte(),
        (bits ushr 16).toByte(),
        (bits ushr 24).toByte(),
        0, 0, 0, 0,
    )
}
