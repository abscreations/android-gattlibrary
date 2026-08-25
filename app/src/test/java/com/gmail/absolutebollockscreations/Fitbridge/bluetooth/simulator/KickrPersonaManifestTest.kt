package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
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
    fun `CORE 2 probe is isolated to FC82 and visibly synthetic`() {
        val core2 = manifest(KickrSimulatorPersona.CORE2_FC82_PROVISIONAL)

        assertTrue(core2.persona.provisional)
        assertEquals("KICKR CORE 2 SIM", core2.advertising.localName)
        assertEquals(listOf(BluetoothIds.UUID_WAHOO_RIDEON_CORE2_SERVICE), core2.advertising.advertisedServices)
        assertTrue(KickrEvidenceLabel.SYN in core2.advertising.evidence)
        assertEquals(listOf(BluetoothIds.UUID_WAHOO_RIDEON_CORE2_SERVICE), core2.services.map { it.uuid })
        assertNull(core2.service(BluetoothIds.UUID_DEVICE_INFORMATION_SERVICE))
        assertNull(core2.service(BluetoothIds.UUID_CYCLING_POWER_SERVICE))
        assertNull(core2.service(BluetoothIds.UUID_FTMS_SERVICE))
        assertNull(core2.service(BluetoothIds.UUID_WAHOO_RIDEON_SERVICE))
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
