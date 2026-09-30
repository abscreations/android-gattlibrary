package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids

/**
 * Builds the FTMS GATT schema.
 *
 * Two rules drive what gets registered, and both are corrections against the old
 * `BtServiceRegistry.buildFtmsService()`:
 *
 *  1. **Exactly one machine data characteristic**, matching the active persona.
 *     The previous schema registered all six at once, which tells a client the
 *     peripheral is six machines simultaneously and leaves five characteristics
 *     permanently silent.
 *  2. **Supported-* Range characteristics only when the corresponding Target
 *     Setting feature is supported** (FTMS 4.11-4.15). The previous schema
 *     registered all five unconditionally.
 *
 * Also new here: Training Status (0x2AD3), which the previous schema omitted
 * entirely, and Write Without Response on the Control Point.
 *
 * Stateless and pure - safe to re-invoke when the persona changes.
 */
internal object PeripheralGattRegistry {

    fun buildFtmsService(
        profile: FtmsMachineProfile,
        targets: TargetCapabilities,
        includeTrainingStatus: Boolean,
    ): BluetoothGattService {
        val svc = BluetoothGattService(
            FtmsUuids.UUID_FTMS_SERVICE,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )

        // 0x2ACC Fitness Machine Feature - Read, mandatory (FTMS section 4).
        svc.addCharacteristic(readOnly(FtmsUuids.UUID_FITNESS_MACHINE_FEATURE))

        // 0x2ADA Fitness Machine Status - Notify.
        svc.addCharacteristic(notify(FtmsUuids.UUID_FITNESS_MACHINE_STATUS))

        // 0x2AD9 Control Point - Write / Write Without Response + Indicate.
        //
        // FTMS normally uses acknowledged writes, but Garmin Connect IQ sends
        // Request Control as an ATT Write Command. Advertising and accepting both
        // transports lets that client receive the normal FTMS response indication
        // instead of timing out after a silently ignored command. This single
        // missing property is the most likely reason a Connect IQ datafield could
        // never take control of the previous build.
        val cp = BluetoothGattCharacteristic(
            FtmsUuids.UUID_FITNESS_MACHINE_CONTROL_POINT,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        cp.addDescriptor(cccd())
        svc.addCharacteristic(cp)

        // Exactly one machine data characteristic.
        svc.addCharacteristic(notify(profile.dataCharacteristic))

        if (includeTrainingStatus) {
            // 0x2AD3 - Read + Notify.
            val ts = BluetoothGattCharacteristic(
                FtmsUuids.UUID_TRAINING_STATUS,
                BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ,
            )
            ts.addDescriptor(cccd())
            svc.addCharacteristic(ts)
        }

        // Supported-* ranges, conditional on target capabilities.
        if (targets.supportsSpeed) {
            svc.addCharacteristic(readOnly(FtmsUuids.UUID_SUPPORTED_SPEED_RANGE))
        }
        if (targets.supportsInclination) {
            svc.addCharacteristic(readOnly(FtmsUuids.UUID_SUPPORTED_INCLINATION_RANGE))
        }
        if (targets.supportsResistance) {
            svc.addCharacteristic(readOnly(FtmsUuids.UUID_SUPPORTED_RESISTANCE_RANGE))
        }
        if (targets.supportsHeartRate) {
            svc.addCharacteristic(readOnly(FtmsUuids.UUID_SUPPORTED_HEART_RATE_RANGE))
        }
        if (targets.supportsPower) {
            svc.addCharacteristic(readOnly(FtmsUuids.UUID_SUPPORTED_POWER_RANGE))
        }

        return svc
    }

    private fun cccd(): BluetoothGattDescriptor = BluetoothGattDescriptor(
        BluetoothIds.UUID_CCCD,
        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
    )

    private fun notify(uuid: UUID): BluetoothGattCharacteristic {
        val c = BluetoothGattCharacteristic(
            uuid,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            0, // no read/write permission: notify-only, per FTMS section 4
        )
        c.addDescriptor(cccd())
        return c
    }

    private fun readOnly(uuid: UUID): BluetoothGattCharacteristic =
        BluetoothGattCharacteristic(
            uuid,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
}
