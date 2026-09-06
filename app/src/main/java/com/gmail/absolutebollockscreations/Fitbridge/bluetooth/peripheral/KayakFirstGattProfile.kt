package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.SimulatorPersona

internal data class KayakFirstAdvertisingPlan(
    val localName: String,
    val manufacturerId: Int,
    val manufacturerPayload: ByteArray,
    val advertisingBytes: Int,
    val scanResponseBytes: Int,
) {
    init {
        require(localName.toByteArray(Charsets.UTF_8).size == 8)
        require(advertisingBytes <= 31 && scanResponseBytes <= 31)
    }

    companion object {
        fun create() = KayakFirstAdvertisingPlan(
            localName = SimulatorPersona.KayakFirstBlue.advertisedName,
            manufacturerId = 0xA759,
            manufacturerPayload = byteArrayOf(
                0x88.toByte(), 0xA0.toByte(), 0x01, 0x02, 0x03, 0x04, 0x05, 0x06,
            ),
            // Flags AD (3) + complete 16-bit service UUID AD (4).
            advertisingBytes = 7,
            // Complete-name AD (10) + manufacturer AD header/id/payload (12).
            scanResponseBytes = 22,
        )
    }
}

internal object KayakFirstGattProfile {
    data class Characteristic(
        val uuid: java.util.UUID,
        val writeWithoutResponse: Boolean,
        val notifiable: Boolean,
        val hasCccd: Boolean,
    )

    val characteristics = listOf(
        Characteristic(BluetoothIds.UUID_KAYAK_FIRST_IO, true, true, true),
    )

    fun buildService(): BluetoothGattService = BluetoothGattService(
        BluetoothIds.UUID_KAYAK_FIRST_SERVICE,
        BluetoothGattService.SERVICE_TYPE_PRIMARY,
    ).also { service ->
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                BluetoothIds.UUID_KAYAK_FIRST_IO,
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ).also { io ->
                io.addDescriptor(
                    BluetoothGattDescriptor(
                        BluetoothIds.UUID_CCCD,
                        BluetoothGattDescriptor.PERMISSION_READ or
                            BluetoothGattDescriptor.PERMISSION_WRITE,
                    ),
                )
            },
        )
    }

    fun acceptsWrite(characteristic: BluetoothGattCharacteristic, prepared: Boolean, offset: Int) =
        characteristic.uuid == BluetoothIds.UUID_KAYAK_FIRST_IO && !prepared && offset == 0
}
