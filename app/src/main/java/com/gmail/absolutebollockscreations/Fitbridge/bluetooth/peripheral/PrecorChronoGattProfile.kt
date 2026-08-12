package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.SimulatorPersona
import java.util.UUID

/** Immutable, measurement-only GATT and legacy-advertising plans for the Chrono fixture. */
internal data class PrecorChronoAdvertisingPlan(
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

        fun create(): PrecorChronoAdvertisingPlan {
            val name = SimulatorPersona.PrecorChrono.advertisedName
            return PrecorChronoAdvertisingPlan(
                // Q32 remains open. This is deliberately the served UUID so a second FitBridge
                // instance exercises the current registry-derived scan filter.
                serviceUuid = BluetoothIds.UUID_PRECOR_SERVICE,
                localName = name,
                advertisingBytes = 3 + 18,
                scanResponseBytes = 2 + name.toByteArray(Charsets.UTF_8).size,
            )
        }
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
