package com.gmail.absolutebollockscreations.gattlibrary.simulator

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.advertisedName
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.KayakFirstUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids

internal data class KayakFirstAdvertisingPlan(
    val localName: String,
    val manufacturerId: Int,
    val manufacturerPayload: ByteArray,
    val advertisingBytes: Int,
    val scanResponseBytes: Int,
    val primaryPayload: ByteArray,
    val scanResponsePayload: ByteArray,
) {
    init {
        require(localName.toByteArray(Charsets.UTF_8).size == 8)
        require(advertisingBytes <= 31 && scanResponseBytes <= 31)
    }

    companion object {
        fun create(
            localName: String = SimulatorPersona.KayakFirstBlue.advertisedName,
            localAddress: String = "06:05:04:03:02:01",
        ): KayakFirstAdvertisingPlan {
            require(Regex("^(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$").matches(localAddress)) {
                "invalid Bluetooth address"
            }
            val address = localAddress.split(':').map { it.toIntOrNull(16) }
            require(address.size == 6 && address.all { it != null }) { "invalid Bluetooth address" }
            val reversedAddress = address.map { requireNotNull(it).toByte() }.reversed().toByteArray()
            val manufacturerPayload = byteArrayOf(0x88.toByte(), 0xA0.toByte()) + reversedAddress
            val primary = byteArrayOf(
                0x02, 0x01, 0x06,
                0x05, 0x02, 0xE0.toByte(), 0xFF.toByte(), 0xE1.toByte(), 0xFF.toByte(),
                0x0B, 0xFF.toByte(), 0x59, 0xA7.toByte(),
            ) + manufacturerPayload
            val nameBytes = localName.toByteArray(Charsets.UTF_8)
            val scan = byteArrayOf((nameBytes.size + 1).toByte(), 0x09) + nameBytes + byteArrayOf(
                0x05, 0x12, 0x08, 0x00, 0x08, 0x00,
                0x02, 0x0A, 0x00,
            )
            return KayakFirstAdvertisingPlan(
                localName = localName,
                manufacturerId = 0xA759,
                manufacturerPayload = manufacturerPayload,
                advertisingBytes = primary.size,
                scanResponseBytes = scan.size,
                primaryPayload = primary,
                scanResponsePayload = scan,
            )
        }
    }
}

internal object KayakFirstGattProfile {
    data class Characteristic(
        val uuid: java.util.UUID,
        val writeWithResponse: Boolean,
        val writeWithoutResponse: Boolean,
        val notifiable: Boolean,
        val hasCccd: Boolean,
    )

    val characteristics = listOf(
        Characteristic(
            uuid = KayakFirstUuids.UUID_KAYAK_FIRST_IO,
            writeWithResponse = true,
            writeWithoutResponse = true,
            notifiable = true,
            hasCccd = true,
        ),
    )

    fun buildService(): BluetoothGattService = BluetoothGattService(
        KayakFirstUuids.UUID_KAYAK_FIRST_SERVICE,
        BluetoothGattService.SERVICE_TYPE_PRIMARY,
    ).also { service ->
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                KayakFirstUuids.UUID_KAYAK_FIRST_IO,
                // Physical-console captures exercise Write Command. TMIW 2.2.1 instead uses its
                // default Write Request mode, so the virtual compatibility surface accepts both.
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ).also { io ->
                io.addDescriptor(
                    BluetoothGattDescriptor(
                        SigUuids.UUID_CCCD,
                        BluetoothGattDescriptor.PERMISSION_READ or
                            BluetoothGattDescriptor.PERMISSION_WRITE,
                    ),
                )
            },
        )
    }

    fun acceptsWrite(characteristic: BluetoothGattCharacteristic, prepared: Boolean, offset: Int) =
        characteristic.uuid == KayakFirstUuids.UUID_KAYAK_FIRST_IO && !prepared && offset == 0
}
