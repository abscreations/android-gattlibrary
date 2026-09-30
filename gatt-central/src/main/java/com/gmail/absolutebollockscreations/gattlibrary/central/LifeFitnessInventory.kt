package com.gmail.absolutebollockscreations.gattlibrary.central

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids

/**
 * The one place Android GATT objects become a [LifeFitnessFingerprint.ServiceInventory].
 *
 * Deliberately the whole of the platform coupling for Life Fitness identification. Everything
 * downstream — the fingerprint, the gate, the transcript, the coordinator — is pure and
 * therefore exhaustively testable; this file is the boundary those depend on and is small
 * enough to read in one go, because it cannot be unit-tested without a device.
 *
 * **Property semantics are the part worth checking.** A characteristic that reports neither
 * `WRITE` nor `WRITE_NO_RESPONSE` cannot carry a setup write, and the fingerprint declines on
 * exactly that (`PROPERTY_INCOMPATIBLE`). Both are accepted here: the transcript uses
 * write-with-response through the queue, but a machine advertising only write-without-response
 * is still offering a writable characteristic, and refusing it would decline a device over a
 * distinction the handshake evidence never draws.
 */
internal fun List<BluetoothGattService>.toLifeFitnessInventory():
    List<LifeFitnessFingerprint.ServiceInventory> = map { service ->
    LifeFitnessFingerprint.ServiceInventory(
        serviceUuid = service.uuid,
        characteristics = service.characteristics.orEmpty().map { it.toInventory() },
    )
}

private fun BluetoothGattCharacteristic.toInventory(): LifeFitnessFingerprint.CharacteristicInventory {
    val properties = properties
    return LifeFitnessFingerprint.CharacteristicInventory(
        uuid = uuid,
        writable = properties and
            (BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0,
        notifyOrIndicate = properties and
            (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0,
        // A notify characteristic with no CCCD cannot be subscribed, so the fingerprint treats
        // it as no status source at all rather than claiming one it cannot arm.
        hasCccd = getDescriptor(SigUuids.UUID_CCCD) != null,
    )
}
