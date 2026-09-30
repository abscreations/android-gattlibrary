package com.gmail.absolutebollockscreations.gattlibrary.simulator

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids

/** Mechanical Android translation of the evidence-labelled pure KICKR manifest. */
internal object KickrGattRegistry {
    fun buildServices(manifest: KickrPersonaManifest): List<BluetoothGattService> =
        manifest.services.map { service ->
            BluetoothGattService(service.uuid, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
                service.characteristics.forEach { addCharacteristic(characteristic(it)) }
            }
        }

    private fun characteristic(manifest: KickrCharacteristicManifest): BluetoothGattCharacteristic {
        val (properties, permissions) = flags(manifest.properties)
        return BluetoothGattCharacteristic(manifest.uuid, properties, permissions).apply {
            if (manifest.hasCccd) addDescriptor(cccd())
        }
    }

    internal fun flags(propertiesToMap: Set<KickrGattProperty>): Pair<Int, Int> {
        var properties = 0
        var permissions = 0
        if (KickrGattProperty.READ in propertiesToMap) {
            properties = properties or BluetoothGattCharacteristic.PROPERTY_READ
            permissions = permissions or BluetoothGattCharacteristic.PERMISSION_READ
        }
        if (KickrGattProperty.WRITE in propertiesToMap) {
            properties = properties or BluetoothGattCharacteristic.PROPERTY_WRITE
            permissions = permissions or BluetoothGattCharacteristic.PERMISSION_WRITE
        }
        if (KickrGattProperty.WRITE_WITHOUT_RESPONSE in propertiesToMap) {
            properties = properties or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
            permissions = permissions or BluetoothGattCharacteristic.PERMISSION_WRITE
        }
        if (KickrGattProperty.NOTIFY in propertiesToMap) {
            properties = properties or BluetoothGattCharacteristic.PROPERTY_NOTIFY
        }
        if (KickrGattProperty.INDICATE in propertiesToMap) {
            properties = properties or BluetoothGattCharacteristic.PROPERTY_INDICATE
        }
        return properties to permissions
    }

    private fun cccd() = BluetoothGattDescriptor(
        SigUuids.UUID_CCCD,
        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
    )
}
