package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import java.util.UUID
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.advertisedName

/** Immutable, measurement-only GATT and legacy-advertising plans for the Chrono fixture. */
internal data class PrecorChronoAdvertisingPlan(
    val serviceUuid: UUID,
    val localName: String,
    val includeNameInAdvertising: Boolean,
    val includeTxPowerInScanResponse: Boolean,
    val manufacturerId: Int,
    val manufacturerPayload: ByteArray,
    val advertisingBytes: Int,
    val scanResponseBytes: Int,
) {
    init {
        require(advertisingBytes <= MAX_LEGACY_BYTES)
        require(scanResponseBytes <= MAX_LEGACY_BYTES)
    }

    companion object {
        const val MAX_LEGACY_BYTES = 31

        fun create(): PrecorChronoAdvertisingPlan {
            val name = SimulatorPersona.PrecorChrono.advertisedName
            val manufacturerPayload = byteArrayOf(0x02, 0x06)
            return PrecorChronoAdvertisingPlan(
                // QZ issue #261's physical Chrono capture places the name in ADV_IND and the
                // served UUID, TX power, and this manufacturer block in SCAN_RSP.
                serviceUuid = BluetoothIds.UUID_PRECOR_SERVICE,
                localName = name,
                includeNameInAdvertising = true,
                includeTxPowerInScanResponse = true,
                manufacturerId = CAPTURED_MANUFACTURER_ID,
                manufacturerPayload = manufacturerPayload,
                advertisingBytes = 3 + 2 + name.toByteArray(Charsets.UTF_8).size,
                scanResponseBytes = 3 + 18 + 4 + manufacturerPayload.size,
            )
        }

        /** Little-endian `fc 01` in the captured Manufacturer Specific Data field. */
        const val CAPTURED_MANUFACTURER_ID = 0x01FC
    }
}

internal object PrecorChronoGattProfile {
    data class Characteristic(
        val uuid: UUID,
        val readable: Boolean,
        val writable: Boolean,
        val notifiable: Boolean,
    )

    val characteristics = listOf(
        Characteristic(
            uuid = BluetoothIds.UUID_PRECOR_MEASUREMENT,
            readable = false,
            writable = false,
            notifiable = true,
        ),
    )

    fun buildService(): BluetoothGattService =
        BluetoothGattService(
            BluetoothIds.UUID_PRECOR_SERVICE,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        ).also { service ->
            val measurement = BluetoothGattCharacteristic(
                BluetoothIds.UUID_PRECOR_MEASUREMENT,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                0,
            )
            measurement.addDescriptor(
                BluetoothGattDescriptor(
                    BluetoothIds.UUID_CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                ),
            )
            service.addCharacteristic(measurement)
        }
}
