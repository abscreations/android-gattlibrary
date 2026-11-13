package com.gmail.absolutebollockscreations.gattlibrary.simulator

import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.core.bytes.LittleEndian.u16Wrapping
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.Concept2Uuids

/**
 * Pure PM5 GATT description and offset-aware Device Information values.
 *
 * The service set is not fixed: it is whatever the persona's
 * [com.gmail.absolutebollockscreations.gattlibrary.simulator.Concept2Pm5GattSurface]
 * says the emulated hardware generation has. The `PRE_FTMS` surface omits `CE060040`
 * entirely, because `PM5 430392046` has no heart-rate-receive service, so a client that
 * writes belt heart rate into the PM must find nowhere to write it here either.
 */
@SimulatorProbeApi
public class Concept2Pm5GattProfile(public val persona: SimulatorPersona.Concept2Pm5) {
    public val services: Map<UUID, List<Concept2CharacteristicManifest>> = buildMap {
        if (persona.compatibility.exposesFtms) {
            // Registration order reproduces the captured post-GAP/GATT handle order.
            put(Concept2Uuids.UUID_C2_PM_CONTROL_SERVICE, modernPmControlManifest)
        }
        put(
            Concept2Uuids.UUID_C2_DEVICE_INFO_SERVICE,
            persona.compatibility.deviceInformation.map {
                Concept2CharacteristicManifest(it, readable = true)
            },
        )
        if (!persona.compatibility.exposesFtms) put(Concept2Uuids.UUID_C2_PM_CONTROL_SERVICE, pmControlManifest)
        put(Concept2Uuids.UUID_C2_ERG_SERVICE, persona.compatibility.characteristics)
        if (persona.compatibility.exposesHeartRateReceive) {
            put(Concept2Uuids.UUID_C2_HEART_RATE_RECEIVE_SERVICE, heartRateReceiveManifest)
        }
        if (persona.compatibility.exposesFtms) {
            put(FtmsUuids.UUID_FTMS_SERVICE, ftmsManifest(persona.machine))
        }
    }

    /**
     * A read is answered only for a characteristic this persona actually publishes. Without
     * that guard a persona could omit `0x0016` from its manifest and still answer a read of
     * it, which is the kind of divergence between declaration and behaviour that makes a
     * simulator stop being evidence about anything.
     */
    public fun readDeviceInformation(uuid: UUID, offset: Int, negotiatedMtu: Int = 23): ByteArray? {
        if (offset < 0) return null
        if (uuid !in persona.compatibility.deviceInformation) return null
        val value = when (uuid) {
            Concept2Uuids.UUID_C2_MODEL_NUMBER -> fixedAscii("PM5", 16)
            Concept2Uuids.UUID_C2_SERIAL_NUMBER -> fixedAscii(persona.compatibility.serialNumber, 9)
            Concept2Uuids.UUID_C2_HARDWARE_REVISION ->
                fixedAscii(persona.compatibility.hardwareRevision, 3)
            Concept2Uuids.UUID_C2_FIRMWARE_REVISION ->
                fixedAscii(persona.compatibility.firmwareIdentity, 20)
            Concept2Uuids.UUID_C2_MANUFACTURER_NAME -> fixedAscii("Concept2", 16)
            Concept2Uuids.UUID_C2_MACHINE_TYPE -> byteArrayOf(persona.machine.wireMachineType.toByte())
            Concept2Uuids.UUID_C2_ATT_RECEIVE_MTU -> u16Wrapping(negotiatedMtu.coerceIn(23, 517))
            // Android does not expose the controller's active DLE maximum to a GATT server.
            // The 1.30 baseline therefore declares the conservative BLE default (27 octets).
            Concept2Uuids.UUID_C2_LL_MAX_TX_RX_BYTES -> u16Wrapping(27)
            else -> return null
        }
        return if (offset <= value.size) value.copyOfRange(offset, value.size) else null
    }

    /** Read-only standard values captured from the modern RowErg's minimal FTMS service. */
    public fun readFtms(uuid: UUID): ByteArray? = when (uuid) {
        FtmsUuids.UUID_FITNESS_MACHINE_FEATURE -> MODERN_ROW_FTMS_FEATURE.copyOf()
        else -> null
    }

    public companion object {
        public val pmControlManifest: List<Concept2CharacteristicManifest> = listOf(
            Concept2CharacteristicManifest(Concept2Uuids.UUID_C2_CSAFE_REQUEST, writable = true),
            Concept2CharacteristicManifest(
                Concept2Uuids.UUID_C2_CSAFE_RESPONSE,
                readable = true,
                notifiable = true,
            ),
        )

        /** Exact `0x0C` request and `0x10` response properties from PM5 431225189. */
        public val modernPmControlManifest: List<Concept2CharacteristicManifest> = listOf(
            Concept2CharacteristicManifest(
                Concept2Uuids.UUID_C2_CSAFE_REQUEST,
                writable = true,
                writeWithoutResponse = true,
            ),
            Concept2CharacteristicManifest(
                Concept2Uuids.UUID_C2_CSAFE_RESPONSE,
                notifiable = true,
            ),
        )

        public val heartRateReceiveManifest: List<Concept2CharacteristicManifest> = listOf(
            Concept2CharacteristicManifest(Concept2Uuids.UUID_C2_HEART_RATE_RECEIVE, writable = true),
        )

        public fun ftmsManifest(machine: Concept2Machine): List<Concept2CharacteristicManifest> = listOf(
            Concept2CharacteristicManifest(FtmsUuids.UUID_FITNESS_MACHINE_FEATURE, readable = true),
            Concept2CharacteristicManifest(
                if (machine == Concept2Machine.BIKE_ERG) FtmsUuids.UUID_BIKING_DATA
                else FtmsUuids.UUID_ROWING_MACHINE_DATA,
                notifiable = true,
            ),
        )

        // 0x000056A6 machine features, no target-setting features (little endian).
        private val MODERN_ROW_FTMS_FEATURE = byteArrayOf(
            0xA6.toByte(), 0x56, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )

        private fun fixedAscii(value: String, size: Int): ByteArray {
            val source = value.toByteArray(Charsets.US_ASCII)
            require(source.size <= size)
            return ByteArray(size).also { source.copyInto(it) }
        }
    }
}
