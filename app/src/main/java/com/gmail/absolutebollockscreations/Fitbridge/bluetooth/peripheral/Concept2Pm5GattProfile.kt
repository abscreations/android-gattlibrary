package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.Concept2CharacteristicManifest
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.SimulatorPersona
import java.util.UUID

/**
 * Pure PM5 GATT description and offset-aware Device Information values.
 *
 * The service set is not fixed: it is whatever the persona's
 * [com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.Concept2Pm5GattSurface]
 * says the emulated hardware generation has. The `PRE_FTMS` surface omits `CE060040`
 * entirely, because `PM5 430392046` has no heart-rate-receive service, so a client that
 * writes belt heart rate into the PM must find nowhere to write it here either.
 */
class Concept2Pm5GattProfile(val persona: SimulatorPersona.Concept2Pm5) {
    val services: Map<UUID, List<Concept2CharacteristicManifest>> = buildMap {
        put(
            BluetoothIds.UUID_C2_DEVICE_INFO_SERVICE,
            persona.compatibility.deviceInformation.map {
                Concept2CharacteristicManifest(it, readable = true)
            },
        )
        put(BluetoothIds.UUID_C2_PM_CONTROL_SERVICE, pmControlManifest)
        put(BluetoothIds.UUID_C2_ERG_SERVICE, persona.compatibility.characteristics)
        if (persona.compatibility.exposesHeartRateReceive) {
            put(BluetoothIds.UUID_C2_HEART_RATE_RECEIVE_SERVICE, heartRateReceiveManifest)
        }
    }

    /**
     * A read is answered only for a characteristic this persona actually publishes. Without
     * that guard a persona could omit `0x0016` from its manifest and still answer a read of
     * it, which is the kind of divergence between declaration and behaviour that makes a
     * simulator stop being evidence about anything.
     */
    fun readDeviceInformation(uuid: UUID, offset: Int, negotiatedMtu: Int = 23): ByteArray? {
        if (offset < 0) return null
        if (uuid !in persona.compatibility.deviceInformation) return null
        val value = when (uuid) {
            BluetoothIds.UUID_C2_MODEL_NUMBER -> fixedAscii("PM5", 16)
            BluetoothIds.UUID_C2_SERIAL_NUMBER -> fixedAscii(persona.compatibility.serialNumber, 9)
            BluetoothIds.UUID_C2_HARDWARE_REVISION ->
                fixedAscii(persona.compatibility.hardwareRevision, 3)
            BluetoothIds.UUID_C2_FIRMWARE_REVISION ->
                fixedAscii(persona.compatibility.firmwareIdentity, 20)
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
        val pmControlManifest: List<Concept2CharacteristicManifest> = listOf(
            Concept2CharacteristicManifest(BluetoothIds.UUID_C2_CSAFE_REQUEST, writable = true),
            Concept2CharacteristicManifest(
                BluetoothIds.UUID_C2_CSAFE_RESPONSE,
                readable = true,
                notifiable = true,
            ),
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
