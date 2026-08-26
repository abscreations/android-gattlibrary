package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.RideOnServiceForm
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KickrPersonaManifestTest {
    @Test
    fun `original Core advertising is the exact captured legacy payload`() {
        val plan = manifest(KickrSimulatorPersona.CORE_ORIGINAL_ZWIFT).advertising

        assertEquals("KICKR CORE C3FB", plan.localName)
        assertEquals(
            listOf(BluetoothIds.UUID_CYCLING_POWER_SERVICE, BluetoothIds.UUID_FTMS_SERVICE),
            plan.advertisedServices,
        )
        assertArrayEquals(KickrPersonaManifest.ORIGINAL_CORE_ADVERTISING, plan.exactLegacyPayload)
        assertEquals(26, plan.semanticLegacyBytes)
        assertTrue(KickrEvidenceLabel.HCI in plan.evidence)
    }

    @Test
    fun `v5 advertisement is semantic only and has no Zwift service`() {
        val plan = manifest(KickrSimulatorPersona.V5_STANDARD_LEGACY).advertising

        assertEquals("Wahoo KICKR AEE4", plan.localName)
        assertNull(plan.exactLegacyPayload)
        assertEquals(
            listOf(BluetoothIds.UUID_CYCLING_POWER_SERVICE, BluetoothIds.UUID_FTMS_SERVICE),
            plan.advertisedServices,
        )
        assertFalse(BluetoothIds.UUID_WAHOO_RIDEON_SERVICE in plan.advertisedServices)
        assertFalse(BluetoothIds.UUID_WAHOO_RIDEON_CORE2_SERVICE in plan.advertisedServices)
        assertTrue(plan.semanticLegacyBytes <= KickrAdvertisingPlan.MAX_LEGACY_BYTES)
    }

    @Test
    fun `standard surfaces pin parents properties and CCCDs`() {
        listOf(
            KickrSimulatorPersona.V5_STANDARD_LEGACY,
            KickrSimulatorPersona.CORE_ORIGINAL_ZWIFT,
            KickrSimulatorPersona.CORE2_FC82_PROVISIONAL,
        ).forEach { persona ->
            val manifest = manifest(persona)
            assertCharacteristic(
                manifest,
                BluetoothIds.UUID_CYCLING_POWER_SERVICE,
                BluetoothIds.UUID_WAHOO_KICKR_CONTROL,
                setOf(KickrGattProperty.WRITE, KickrGattProperty.INDICATE),
                cccd = true,
            )
            assertCharacteristic(
                manifest,
                BluetoothIds.UUID_WAHOO_KICKR_MARKER_EE01,
                BluetoothIds.UUID_WAHOO_KICKR_E002,
                setOf(KickrGattProperty.WRITE_WITHOUT_RESPONSE, KickrGattProperty.NOTIFY),
                cccd = true,
            )
            assertCharacteristic(
                manifest,
                BluetoothIds.UUID_FTMS_SERVICE,
                BluetoothIds.UUID_FITNESS_MACHINE_CONTROL_POINT,
                setOf(KickrGattProperty.WRITE, KickrGattProperty.INDICATE),
                cccd = true,
            )
            assertCharacteristic(
                manifest,
                BluetoothIds.UUID_USER_DATA_SERVICE,
                BluetoothIds.UUID_USER_WEIGHT,
                setOf(KickrGattProperty.READ, KickrGattProperty.WRITE),
                cccd = false,
            )
        }
    }

    @Test
    fun `original Core alone has captured original Zwift and EE03 surfaces`() {
        val original = manifest(KickrSimulatorPersona.CORE_ORIGINAL_ZWIFT)
        val v5 = manifest(KickrSimulatorPersona.V5_STANDARD_LEGACY)

        assertNull(v5.service(BluetoothIds.UUID_WAHOO_RIDEON_SERVICE))
        assertNull(v5.zwiftServiceForm)
        assertEquals(RideOnServiceForm.ORIGINAL, original.zwiftServiceForm)
        assertNull(v5.service(BluetoothIds.UUID_WAHOO_KICKR_MARKER_EE03))
        assertCharacteristic(
            original,
            BluetoothIds.UUID_WAHOO_RIDEON_SERVICE,
            BluetoothIds.UUID_WAHOO_RIDEON_ASYNC_TX,
            setOf(KickrGattProperty.NOTIFY),
            cccd = true,
        )
        assertCharacteristic(
            original,
            BluetoothIds.UUID_WAHOO_RIDEON_SERVICE,
            BluetoothIds.UUID_WAHOO_RIDEON_SYNC_RX,
            setOf(KickrGattProperty.WRITE_WITHOUT_RESPONSE),
            cccd = false,
        )
        assertCharacteristic(
            original,
            BluetoothIds.UUID_WAHOO_RIDEON_SERVICE,
            BluetoothIds.UUID_WAHOO_RIDEON_SYNC_TX,
            setOf(KickrGattProperty.INDICATE),
            cccd = true,
        )
        assertCharacteristic(
            original,
            BluetoothIds.UUID_WAHOO_KICKR_MARKER_EE03,
            BluetoothIds.UUID_WAHOO_KICKR_E00A,
            setOf(KickrGattProperty.NOTIFY),
            cccd = true,
        )
    }

    @Test
    fun `CORE 2 uses captured standard advertising plus FC82 GATT`() {
        val core2 = manifest(KickrSimulatorPersona.CORE2_FC82_PROVISIONAL)

        assertTrue(core2.persona.provisional)
        assertEquals("KICKR CORE 2 1A2B", core2.advertising.localName)
        assertEquals(
            listOf(BluetoothIds.UUID_CYCLING_POWER_SERVICE, BluetoothIds.UUID_FTMS_SERVICE),
            core2.advertising.advertisedServices,
        )
        assertArrayEquals(KickrPersonaManifest.CORE2_SIM_ADVERTISING, core2.advertising.exactLegacyPayload)
        assertEquals(28, core2.advertising.semanticLegacyBytes)
        assertTrue(KickrEvidenceLabel.HCI in core2.advertising.evidence)
        assertTrue(KickrEvidenceLabel.SYN in core2.advertising.evidence)
        assertTrue(core2.service(BluetoothIds.UUID_DEVICE_INFORMATION_SERVICE) != null)
        assertTrue(core2.service(BluetoothIds.UUID_CYCLING_POWER_SERVICE) != null)
        assertTrue(core2.service(BluetoothIds.UUID_FTMS_SERVICE) != null)
        assertTrue(core2.service(BluetoothIds.UUID_WAHOO_RIDEON_CORE2_SERVICE) != null)
        assertNull(core2.service(BluetoothIds.UUID_WAHOO_RIDEON_SERVICE))
        assertEquals(RideOnServiceForm.CORE2, core2.zwiftServiceForm)
        assertTrue(core2.identity.evidence.all { it == KickrEvidenceLabel.SYN })
    }

    @Test
    fun `CORE 2 physical advertising evidence remains pinned separately from name adaptation`() {
        assertArrayEquals(
            byteArrayOf(
                0x02, 0x01, 0x06,
                0x05, 0x03, 0x18, 0x18, 0x26, 0x18,
                0x10, 0x09, 0x4B, 0x49, 0x43, 0x4B, 0x52, 0x20,
                0x43, 0x4F, 0x52, 0x45, 0x20, 0x31, 0x41, 0x32, 0x42,
            ),
            KickrPersonaManifest.CAPTURED_CORE2_ADVERTISING,
        )
    }

    @Test
    fun `CORE 2 captured and adapted packets decode to flags services and complete names`() {
        val captured = adStructures(KickrPersonaManifest.CAPTURED_CORE2_ADVERTISING)
        val adapted = adStructures(KickrPersonaManifest.CORE2_SIM_ADVERTISING)

        listOf(captured, adapted).forEach { structures ->
            assertArrayEquals(byteArrayOf(0x06), structures.getValue(0x01))
            assertArrayEquals(byteArrayOf(0x18, 0x18, 0x26, 0x18), structures.getValue(0x03))
            assertFalse(structures.values.any { it.contains(0x82.toByte()) && it.contains(0xFC.toByte()) })
        }
        assertEquals("KICKR CORE 1A2B", captured.getValue(0x09).toString(Charsets.US_ASCII))
        assertEquals("KICKR CORE 2 1A2B", adapted.getValue(0x09).toString(Charsets.US_ASCII))
        assertEquals(KickrPersonaManifest.CAPTURED_CORE2_ADVERTISING.size + 2, adapted.values.sumOf { it.size + 2 })
    }

    @Test
    fun `every readable CORE 2 compatibility characteristic has a synthetic response`() {
        val core2 = manifest(KickrSimulatorPersona.CORE2_FC82_PROVISIONAL)
        val readable = core2.services.flatMap { service ->
            service.characteristics.filter { KickrGattProperty.READ in it.properties }
        }

        assertTrue(readable.isNotEmpty())
        readable.forEach { characteristic ->
            assertTrue(
                "missing CORE 2 read ${characteristic.uuid}",
                KickrStandardEncoders.readValue(core2.persona, characteristic.uuid) != null,
            )
        }
    }

    @Test
    fun `all persona advertisements fit the legacy byte budget`() {
        KickrSimulatorPersona.entries.forEach { persona ->
            assertTrue(manifest(persona).advertising.semanticLegacyBytes <= KickrAdvertisingPlan.MAX_LEGACY_BYTES)
        }
    }

    @Test
    fun `legacy acknowledgement defaults are explicit and evidence labelled`() {
        listOf(
            KickrSimulatorPersona.V5_STANDARD_LEGACY,
            KickrSimulatorPersona.CORE_ORIGINAL_ZWIFT,
        ).forEach { persona ->
            val mode = manifest(persona).legacyAckMode
            assertEquals(KickrLegacyAckMode.ACKNOWLEDGING_SYNTHETIC, mode)
            assertEquals(KickrEvidenceLabel.SYN, mode!!.evidence)
        }
        assertNull(manifest(KickrSimulatorPersona.CORE2_FC82_PROVISIONAL).legacyAckMode)
        assertEquals(KickrEvidenceLabel.X, KickrLegacyAckMode.SILENT_CORROBORATED.evidence)
    }

    private fun manifest(persona: KickrSimulatorPersona) = KickrPersonaManifest.forPersona(persona)

    private fun adStructures(packet: ByteArray): Map<Int, ByteArray> {
        val structures = linkedMapOf<Int, ByteArray>()
        var offset = 0
        while (offset < packet.size) {
            val length = packet[offset].toInt() and 0xFF
            require(length > 0 && offset + length < packet.size)
            val type = packet[offset + 1].toInt() and 0xFF
            structures[type] = packet.copyOfRange(offset + 2, offset + length + 1)
            offset += length + 1
        }
        require(offset == packet.size)
        return structures
    }

    private fun assertCharacteristic(
        manifest: KickrPersonaManifest,
        serviceUuid: java.util.UUID,
        characteristicUuid: java.util.UUID,
        properties: Set<KickrGattProperty>,
        cccd: Boolean,
    ) {
        val service = requireNotNull(manifest.service(serviceUuid))
        val characteristic = requireNotNull(service.characteristics.singleOrNull { it.uuid == characteristicUuid })
        assertEquals(properties, characteristic.properties)
        assertEquals(cccd, characteristic.hasCccd)
    }
}
