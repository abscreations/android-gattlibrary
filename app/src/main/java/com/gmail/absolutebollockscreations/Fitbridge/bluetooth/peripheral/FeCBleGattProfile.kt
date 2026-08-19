package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds

/**
 * FE-C-over-BLE GATT manifest (plan §6).
 *
 * `FEC2` is always served, notify + CCCD. `FEC3` (write-without-response) is served only for
 * personas the plan marks control-capable by default — the Trainer — proving FitBridge accepts
 * `FEC1 + FEC2` alone as a valid measurement-only machine for the others (plan §6.2). Tests may
 * still instantiate a non-Trainer persona with FEC3 enabled; that is a test configuration, not a
 * physical-equipment claim (plan §3.1).
 *
 * **`FEC3` is write-without-response only.** The Phase-9 central path is defined against the
 * no-response implementation; if later physical evidence shows a trainer advertises both write
 * types, that becomes a separate compatibility mode rather than silently weakening this persona
 * (plan §6.1).
 */
internal object FeCBleGattProfile {

    private const val NOTIFY = BluetoothGattCharacteristic.PROPERTY_NOTIFY
    private const val WRITE_NO_RESPONSE = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE

    /**
     * `includeCps` publishes FitBridge's existing standards-compliant Cycling Power Service
     * (`0x1818`, [CyclingServiceRegistry.buildCyclingPowerService]) as a second primary service on
     * the same GATT server (plan §6.3, §18) — not a new one-off CPS implementation. Android GATT
     * servers only accept one `addService` at a time, so the caller must add these sequentially,
     * waiting for each `onServiceAdded` callback before adding the next.
     */
    fun buildServices(includeControl: Boolean, includeCps: Boolean): List<BluetoothGattService> {
        val service = BluetoothGattService(BluetoothIds.UUID_FEC_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(notifying(BluetoothIds.UUID_FEC_MEASUREMENT))
        if (includeControl) {
            service.addCharacteristic(
                BluetoothGattCharacteristic(
                    BluetoothIds.UUID_FEC_CONTROL,
                    WRITE_NO_RESPONSE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE,
                ),
            )
        }
        return if (includeCps) {
            listOf(service, CyclingServiceRegistry.buildCyclingPowerService())
        } else {
            listOf(service)
        }
    }

    private fun notifying(uuid: java.util.UUID): BluetoothGattCharacteristic {
        val characteristic = BluetoothGattCharacteristic(
            uuid,
            NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        characteristic.addDescriptor(
            BluetoothGattDescriptor(
                BluetoothIds.UUID_CCCD,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ),
        )
        return characteristic
    }
}

/**
 * Legacy advertising content for the FE-C-over-BLE personas (plan §5).
 *
 * Discovery correctness is based on the `FEC1` 128-bit service UUID in the advertisement itself,
 * never on the local name — the name is diagnostic only and lives in the scan response.
 * Flags (3) + complete 128-bit service UUID list (18) = 21 of 31 legacy advertising octets, well
 * inside budget with no manufacturer block to compete for space.
 */
internal object FeCBleAdvertisingPlan {
    const val DEFAULT_LOCAL_NAME = "FitBridge FE-C"
}
