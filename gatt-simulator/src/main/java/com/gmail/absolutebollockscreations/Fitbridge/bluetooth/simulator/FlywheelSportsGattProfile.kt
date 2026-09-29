package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import java.util.UUID
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.advertisedName

internal data class FlywheelSportsAdvertisingPlan(
    val serviceUuid: UUID,
    val localName: String,
    val advertisingBytes: Int,
    val scanResponseBytes: Int,
) {
    init {
        require(advertisingBytes <= MAX_LEGACY_BYTES)
        require(scanResponseBytes <= MAX_LEGACY_BYTES)
    }

    companion object {
        const val MAX_LEGACY_BYTES = 31

        fun create(): FlywheelSportsAdvertisingPlan {
            val name = SimulatorPersona.FlywheelSports.advertisedName
            return FlywheelSportsAdvertisingPlan(
                serviceUuid = BluetoothIds.UUID_FLYWHEEL_SERVICE,
                localName = name,
                advertisingBytes = 3 + 2 + name.toByteArray(Charsets.UTF_8).size,
                scanResponseBytes = 18,
            )
        }
    }
}

internal object FlywheelSportsGattProfile {
    data class Characteristic(
        val uuid: UUID,
        val readable: Boolean,
        val writable: Boolean,
        val writeWithoutResponse: Boolean,
        val notifiable: Boolean,
        val hasCccd: Boolean,
    )

    val characteristics = listOf(
        Characteristic(BluetoothIds.UUID_FLYWHEEL_RX, false, true, true, false, false),
        Characteristic(BluetoothIds.UUID_FLYWHEEL_TX, false, false, false, true, true),
    )

    fun acceptsInertRxWrite(uuid: UUID, preparedWrite: Boolean, offset: Int): Boolean =
        uuid == BluetoothIds.UUID_FLYWHEEL_RX && !preparedWrite && offset == 0

    fun buildService(): BluetoothGattService = BluetoothGattService(
        BluetoothIds.UUID_FLYWHEEL_SERVICE,
        BluetoothGattService.SERVICE_TYPE_PRIMARY,
    ).also { service ->
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                BluetoothIds.UUID_FLYWHEEL_RX,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ),
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                BluetoothIds.UUID_FLYWHEEL_TX,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                0,
            ).also { tx ->
                tx.addDescriptor(
                    BluetoothGattDescriptor(
                        BluetoothIds.UUID_CCCD,
                        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                    ),
                )
            },
        )
    }
}
