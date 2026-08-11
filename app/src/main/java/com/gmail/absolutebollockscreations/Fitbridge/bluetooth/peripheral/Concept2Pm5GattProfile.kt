package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.Concept2CharacteristicManifest
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.SimulatorPersona
import java.util.UUID

/** Pure PM5 GATT description and offset-aware Device Information values. */
class Concept2Pm5GattProfile(val persona: SimulatorPersona.Concept2Pm5) {
    val services: Map<UUID, List<Concept2CharacteristicManifest>> = linkedMapOf(
        BluetoothIds.UUID_C2_DEVICE_INFO_SERVICE to deviceInformationManifest,
        BluetoothIds.UUID_C2_PM_CONTROL_SERVICE to pmControlManifest,
        BluetoothIds.UUID_C2_ERG_SERVICE to persona.firmware.characteristics,
        BluetoothIds.UUID_C2_HEART_RATE_RECEIVE_SERVICE to heartRateReceiveManifest,
    )

    fun readDeviceInformation(uuid: UUID, offset: Int, negotiatedMtu: Int = 23): ByteArray? {
        if (offset < 0) return null
        val value = when (uuid) {
            BluetoothIds.UUID_C2_MODEL_NUMBER -> fixedAscii("PM5", 16)
            BluetoothIds.UUID_C2_SERIAL_NUMBER -> fixedAscii(persona.serialNumber, 9)
            BluetoothIds.UUID_C2_HARDWARE_REVISION ->
                fixedAscii(persona.firmware.hardwareRevision, 3)
            BluetoothIds.UUID_C2_FIRMWARE_REVISION ->
                fixedAscii(persona.firmware.firmwareRevision, 20)
            BluetoothIds.UUID_C2_MANUFACTURER_NAME -> fixedAscii("Concept2", 16)
            BluetoothIds.UUID_C2_MACHINE_TYPE -> byteArrayOf(persona.machine.wireMachineType.toByte())
            BluetoothIds.UUID_C2_ATT_RECEIVE_MTU -> u16le(negotiatedMtu.coerceIn(23, 517))
            // Android does not expose the controller's active DLE maximum to a GATT server.
            // The 1.30 baseline therefore declares the conservative BLE default (27 octets).
            BluetoothIds.UUID_C2_LL_MAX_TX_RX_BYTES -> u16le(27)
            else -> return null
        }
        return if (offset <= value.size) value.copyOfRange(offset, value.size) else null
    }

    companion object {
        val deviceInformationManifest: List<Concept2CharacteristicManifest> = listOf(
            BluetoothIds.UUID_C2_MODEL_NUMBER,
            BluetoothIds.UUID_C2_SERIAL_NUMBER,
            BluetoothIds.UUID_C2_HARDWARE_REVISION,
            BluetoothIds.UUID_C2_FIRMWARE_REVISION,
            BluetoothIds.UUID_C2_MANUFACTURER_NAME,
            BluetoothIds.UUID_C2_MACHINE_TYPE,
            BluetoothIds.UUID_C2_ATT_RECEIVE_MTU,
            BluetoothIds.UUID_C2_LL_MAX_TX_RX_BYTES,
        ).map { Concept2CharacteristicManifest(it, readable = true) }

        val pmControlManifest: List<Concept2CharacteristicManifest> = listOf(
            Concept2CharacteristicManifest(BluetoothIds.UUID_C2_CSAFE_REQUEST, writable = true),
            Concept2CharacteristicManifest(BluetoothIds.UUID_C2_CSAFE_RESPONSE, readable = true),
        )

        val heartRateReceiveManifest: List<Concept2CharacteristicManifest> = listOf(
            Concept2CharacteristicManifest(BluetoothIds.UUID_C2_HEART_RATE_RECEIVE, writable = true),
        )

        private fun fixedAscii(value: String, size: Int): ByteArray {
            val source = value.toByteArray(Charsets.US_ASCII)
            require(source.size <= size)
            return ByteArray(size).also { source.copyInto(it) }
        }

        private fun u16le(value: Int): ByteArray = byteArrayOf(value.toByte(), (value ushr 8).toByte())
    }
}
