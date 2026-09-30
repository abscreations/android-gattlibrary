package com.gmail.absolutebollockscreations.gattlibrary.simulator

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids

/** Android GATT objects for the immutable PM5 firmware manifest. */
internal object Concept2Pm5GattRegistry {
    fun buildServices(persona: SimulatorPersona.Concept2Pm5): List<BluetoothGattService> {
        val profile = Concept2Pm5GattProfile(persona)
        return profile.services.map { (serviceUuid, manifest) ->
            BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY).also { service ->
                manifest.forEach { spec ->
                    var properties = 0
                    var permissions = 0
                    if (spec.readable) {
                        properties = properties or BluetoothGattCharacteristic.PROPERTY_READ
                        permissions = permissions or BluetoothGattCharacteristic.PERMISSION_READ
                    }
                    if (spec.writable) {
                        properties = properties or BluetoothGattCharacteristic.PROPERTY_WRITE
                        permissions = permissions or BluetoothGattCharacteristic.PERMISSION_WRITE
                    }
                    if (spec.writeWithoutResponse) {
                        properties = properties or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                        permissions = permissions or BluetoothGattCharacteristic.PERMISSION_WRITE
                    }
                    if (spec.notifiable) {
                        properties = properties or BluetoothGattCharacteristic.PROPERTY_NOTIFY
                    }
                    val characteristic = BluetoothGattCharacteristic(spec.uuid, properties, permissions)
                    if (spec.notifiable) characteristic.addDescriptor(cccd())
                    service.addCharacteristic(characteristic)
                }
            }
        }
    }

    private fun cccd() = BluetoothGattDescriptor(
        SigUuids.UUID_CCCD,
        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
    )
}
