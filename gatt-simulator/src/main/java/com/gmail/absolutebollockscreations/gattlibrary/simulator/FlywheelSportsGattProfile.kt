package com.gmail.absolutebollockscreations.gattlibrary.simulator

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.advertisedName
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.FlywheelUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids

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
                serviceUuid = FlywheelUuids.UUID_FLYWHEEL_SERVICE,
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
        Characteristic(FlywheelUuids.UUID_FLYWHEEL_RX, false, true, true, false, false),
        Characteristic(FlywheelUuids.UUID_FLYWHEEL_TX, false, false, false, true, true),
    )

    fun acceptsInertRxWrite(uuid: UUID, preparedWrite: Boolean, offset: Int): Boolean =
        uuid == FlywheelUuids.UUID_FLYWHEEL_RX && !preparedWrite && offset == 0

    fun buildService(): BluetoothGattService = BluetoothGattService(
        FlywheelUuids.UUID_FLYWHEEL_SERVICE,
        BluetoothGattService.SERVICE_TYPE_PRIMARY,
    ).also { service ->
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                FlywheelUuids.UUID_FLYWHEEL_RX,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            ),
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                FlywheelUuids.UUID_FLYWHEEL_TX,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                0,
            ).also { tx ->
                tx.addDescriptor(
                    BluetoothGattDescriptor(
                        SigUuids.UUID_CCCD,
                        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                    ),
                )
            },
        )
    }
}
