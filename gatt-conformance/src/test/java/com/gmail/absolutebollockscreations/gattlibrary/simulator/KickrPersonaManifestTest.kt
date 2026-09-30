package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.RideOnServiceForm
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.NordicUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.WahooUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.ZwiftUuids

class KickrPersonaManifestTest {
    @Test
    fun `original Core advertising is the exact captured legacy payload`() {
        val plan = manifest(KickrSimulatorPersona.CORE_ORIGINAL_ZWIFT).advertising

        assertEquals("KICKR CORE C3FB", plan.localName)
        assertEquals(
            listOf(SigUuids.UUID_CYCLING_POWER_SERVICE, FtmsUuids.UUID_FTMS_SERVICE),
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
            listOf(SigUuids.UUID_CYCLING_POWER_SERVICE, FtmsUuids.UUID_FTMS_SERVICE),
            plan.advertisedServices,
        )
        assertFalse(WahooUuids.UUID_WAHOO_RIDEON_SERVICE in plan.advertisedServices)
        assertFalse(WahooUuids.UUID_WAHOO_RIDEON_CORE2_SERVICE in plan.advertisedServices)
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
                SigUuids.UUID_CYCLING_POWER_SERVICE,
                WahooUuids.UUID_WAHOO_KICKR_CONTROL,
                setOf(KickrGattProperty.WRITE, KickrGattProperty.INDICATE),
                cccd = true,
            )
            assertCharacteristic(
                manifest,
                WahooUuids.UUID_WAHOO_KICKR_MARKER_EE01,
                WahooUuids.UUID_WAHOO_KICKR_E002,
                setOf(KickrGattProperty.WRITE_WITHOUT_RESPONSE, KickrGattProperty.NOTIFY),
                cccd = true,
            )
            assertCharacteristic(
                manifest,
                FtmsUuids.UUID_FTMS_SERVICE,
                FtmsUuids.UUID_FITNESS_MACHINE_CONTROL_POINT,
                setOf(KickrGattProperty.WRITE, KickrGattProperty.INDICATE),
                cccd = true,
            )
            assertCharacteristic(
                manifest,
                SigUuids.UUID_USER_DATA_SERVICE,
                SigUuids.UUID_USER_WEIGHT,
                setOf(KickrGattProperty.READ, KickrGattProperty.WRITE),
                cccd = false,
            )
        }
    }

    @Test
    fun `original Core alone has captured original Zwift and EE03 surfaces`() {
        val original = manifest(KickrSimulatorPersona.CORE_ORIGINAL_ZWIFT)
        val v5 = manifest(KickrSimulatorPersona.V5_STANDARD_LEGACY)

        assertNull(v5.service(WahooUuids.UUID_WAHOO_RIDEON_SERVICE))
        assertNull(v5.zwiftServiceForm)
        assertEquals(RideOnServiceForm.ORIGINAL, original.zwiftServiceForm)
        assertNull(v5.service(WahooUuids.UUID_WAHOO_KICKR_MARKER_EE03))
        assertCharacteristic(
            original,
            WahooUuids.UUID_WAHOO_RIDEON_SERVICE,
            WahooUuids.UUID_WAHOO_RIDEON_ASYNC_TX,
            setOf(KickrGattProperty.NOTIFY),
            cccd = true,
        )
        assertCharacteristic(
            original,
            WahooUuids.UUID_WAHOO_RIDEON_SERVICE,
            WahooUuids.UUID_WAHOO_RIDEON_SYNC_RX,
            setOf(KickrGattProperty.WRITE_WITHOUT_RESPONSE),
            cccd = false,
        )
        assertCharacteristic(
            original,
            WahooUuids.UUID_WAHOO_RIDEON_SERVICE,
            WahooUuids.UUID_WAHOO_RIDEON_SYNC_TX,
            setOf(KickrGattProperty.INDICATE),
            cccd = true,
        )
        assertCharacteristic(
            original,
            WahooUuids.UUID_WAHOO_KICKR_MARKER_EE03,
            WahooUuids.UUID_WAHOO_KICKR_E00A,
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
            listOf(SigUuids.UUID_CYCLING_POWER_SERVICE, FtmsUuids.UUID_FTMS_SERVICE),
            core2.advertising.advertisedServices,
        )
        assertArrayEquals(KickrPersonaManifest.CORE2_SIM_ADVERTISING, core2.advertising.exactLegacyPayload)
        assertEquals(28, core2.advertising.semanticLegacyBytes)
        assertTrue(KickrEvidenceLabel.HCI in core2.advertising.evidence)
        assertTrue(KickrEvidenceLabel.SYN in core2.advertising.evidence)
        assertTrue(core2.service(SigUuids.UUID_DEVICE_INFORMATION_SERVICE) != null)
        assertTrue(core2.service(SigUuids.UUID_CYCLING_POWER_SERVICE) != null)
        assertTrue(core2.service(FtmsUuids.UUID_FTMS_SERVICE) != null)
        assertTrue(core2.service(WahooUuids.UUID_WAHOO_RIDEON_CORE2_SERVICE) != null)
        assertNull(core2.service(WahooUuids.UUID_WAHOO_RIDEON_SERVICE))
        assertEquals(RideOnServiceForm.CORE2, core2.zwiftServiceForm)
        assertTrue(core2.identity.evidence.all { it == KickrEvidenceLabel.SYN })
    }

    @Test
    fun `CORE 2 connected GATT matches the captured service and characteristic surface`() {
        val core2 = manifest(KickrSimulatorPersona.CORE2_FC82_PROVISIONAL)

        val expected = linkedMapOf(
            SigUuids.UUID_HRM_SERVICE to setOf(
                SigUuids.UUID_HEART_RATE_MEASUREMENT,
                SigUuids.UUID_BODY_SENSOR_LOCATION,
            ),
            SigUuids.UUID_DEVICE_INFORMATION_SERVICE to setOf(
                SigUuids.UUID_MANUFACTURER_NAME,
                SigUuids.UUID_SERIAL_NUMBER,
                SigUuids.UUID_HARDWARE_REVISION,
                SigUuids.UUID_FIRMWARE_REVISION,
            ),
            SigUuids.UUID_USER_DATA_SERVICE to setOf(SigUuids.UUID_USER_WEIGHT),
            SigUuids.UUID_CYCLING_POWER_SERVICE to setOf(
                SigUuids.UUID_CYCLING_POWER_MEASUREMENT,
                SigUuids.UUID_CYCLING_POWER_FEATURE,
                SigUuids.UUID_SENSOR_LOCATION,
                WahooUuids.UUID_WAHOO_KICKR_CONTROL,
                SigUuids.UUID_CYCLING_POWER_CONTROL_POINT,
            ),
            NordicUuids.UUID_SECURE_DFU_SERVICE to setOf(NordicUuids.UUID_SECURE_DFU_CONTROL),
            WahooUuids.UUID_WAHOO_RIDEON_CORE2_SERVICE to setOf(
                WahooUuids.UUID_WAHOO_RIDEON_ASYNC_TX,
                WahooUuids.UUID_WAHOO_RIDEON_SYNC_RX,
                WahooUuids.UUID_WAHOO_RIDEON_SYNC_TX,
            ),
            WahooUuids.UUID_WAHOO_KICKR_MARKER_EE01 to setOf(
                WahooUuids.UUID_WAHOO_KICKR_E002,
                WahooUuids.UUID_WAHOO_KICKR_E004,
            ),
            WahooUuids.UUID_WAHOO_KICKR_MARKER_EE06 to setOf(
                WahooUuids.UUID_WAHOO_KICKR_E023,
                WahooUuids.UUID_WAHOO_KICKR_E018,
            ),
            FtmsUuids.UUID_FTMS_SERVICE to setOf(
                FtmsUuids.UUID_FITNESS_MACHINE_FEATURE,
                FtmsUuids.UUID_TRAINING_STATUS,
                FtmsUuids.UUID_FITNESS_MACHINE_CONTROL_POINT,
                FtmsUuids.UUID_FITNESS_MACHINE_STATUS,
                FtmsUuids.UUID_BIKING_DATA,
                FtmsUuids.UUID_SUPPORTED_RESISTANCE_RANGE,
                FtmsUuids.UUID_SUPPORTED_POWER_RANGE,
            ),
            WahooUuids.UUID_WAHOO_KICKR_MARKER_EE0B to setOf(WahooUuids.UUID_WAHOO_KICKR_E037),
        )
        assertEquals(
            expected,
            core2.services.associate { service ->
                service.uuid to service.characteristics.mapTo(linkedSetOf()) { it.uuid }
            },
        )
        assertCharacteristic(
            core2,
            SigUuids.UUID_HRM_SERVICE,
            SigUuids.UUID_HEART_RATE_MEASUREMENT,
            setOf(KickrGattProperty.NOTIFY),
            cccd = true,
        )
        assertCharacteristic(
            core2,
            SigUuids.UUID_HRM_SERVICE,
            SigUuids.UUID_BODY_SENSOR_LOCATION,
            setOf(KickrGattProperty.READ),
            cccd = false,
        )
        assertCharacteristic(
            core2,
            NordicUuids.UUID_SECURE_DFU_SERVICE,
            NordicUuids.UUID_SECURE_DFU_CONTROL,
            setOf(KickrGattProperty.WRITE, KickrGattProperty.INDICATE),
            cccd = true,
        )
        assertCharacteristic(
            core2,
            WahooUuids.UUID_WAHOO_KICKR_MARKER_EE06,
            WahooUuids.UUID_WAHOO_KICKR_E018,
            setOf(KickrGattProperty.WRITE_WITHOUT_RESPONSE, KickrGattProperty.NOTIFY),
            cccd = true,
        )
        assertTrue(core2.services.all { KickrEvidenceLabel.LOG in it.evidence })
    }

    @Test
    fun `Zwift Hub matches repeated captured advertising and connected GATT`() {
        val hub = manifest(KickrSimulatorPersona.ZWIFT_HUB_CAPTURED)

        assertTrue(hub.persona.provisional)
        assertEquals("Zwift Hub", hub.advertising.localName)
        assertEquals(
            listOf(
                SigUuids.UUID_CYCLING_POWER_SERVICE,
                FtmsUuids.UUID_FTMS_SERVICE,
                SigUuids.UUID_DEVICE_INFORMATION_SERVICE,
            ),
            hub.advertising.advertisedServices,
        )
        assertEquals(listOf(WahooUuids.UUID_WAHOO_RIDEON_SERVICE), hub.advertising.scanResponseServices)
        assertEquals(0x094A, hub.advertising.manufacturerId)
        assertArrayEquals(byteArrayOf(0x06, 0x2E, 0xBE.toByte()), hub.advertising.manufacturerData)
        assertEquals(setOf(KickrEvidenceLabel.LOG, KickrEvidenceLabel.SYN), hub.advertising.evidence)
        assertTrue(hub.advertising.semanticLegacyBytes <= KickrAdvertisingPlan.MAX_LEGACY_BYTES)
        assertTrue(hub.advertising.semanticScanResponseBytes <= KickrAdvertisingPlan.MAX_LEGACY_BYTES)
        assertNull(hub.advertising.exactLegacyPayload)
        assertEquals(
            listOf(
                SigUuids.UUID_CYCLING_POWER_SERVICE,
                FtmsUuids.UUID_FTMS_SERVICE,
                ZwiftUuids.UUID_ZWIFT_HUB_SUPPORT_SERVICE,
                SigUuids.UUID_HRM_SERVICE,
                NordicUuids.UUID_NORDIC_UART_SERVICE,
                NordicUuids.UUID_SECURE_DFU_SERVICE,
                SigUuids.UUID_DEVICE_INFORMATION_SERVICE,
                WahooUuids.UUID_WAHOO_RIDEON_SERVICE,
            ),
            hub.services.map { it.uuid },
        )
        assertFalse(hub.services.any { it.uuid == WahooUuids.UUID_WAHOO_RIDEON_CORE2_SERVICE })
        assertEquals(RideOnServiceForm.ORIGINAL, hub.zwiftServiceForm)
        assertNull(hub.legacyAckMode)
        assertEquals(
            mapOf(
                SigUuids.UUID_CYCLING_POWER_MEASUREMENT to setOf(KickrGattProperty.NOTIFY),
                SigUuids.UUID_CYCLING_POWER_FEATURE to setOf(KickrGattProperty.READ),
                SigUuids.UUID_SENSOR_LOCATION to setOf(KickrGattProperty.READ),
                SigUuids.UUID_CYCLING_POWER_CONTROL_POINT to setOf(KickrGattProperty.WRITE, KickrGattProperty.INDICATE),
                FtmsUuids.UUID_BIKING_DATA to setOf(KickrGattProperty.NOTIFY),
                FtmsUuids.UUID_FITNESS_MACHINE_FEATURE to setOf(KickrGattProperty.READ),
                FtmsUuids.UUID_FITNESS_MACHINE_STATUS to setOf(KickrGattProperty.NOTIFY),
                FtmsUuids.UUID_SUPPORTED_RESISTANCE_RANGE to setOf(KickrGattProperty.READ),
                FtmsUuids.UUID_SUPPORTED_POWER_RANGE to setOf(KickrGattProperty.READ),
                FtmsUuids.UUID_FITNESS_MACHINE_CONTROL_POINT to setOf(KickrGattProperty.WRITE, KickrGattProperty.INDICATE),
                ZwiftUuids.UUID_ZWIFT_HUB_SUPPORT_READ to setOf(KickrGattProperty.READ),
                ZwiftUuids.UUID_ZWIFT_HUB_SUPPORT_NOTIFY to setOf(KickrGattProperty.READ, KickrGattProperty.NOTIFY),
                ZwiftUuids.UUID_ZWIFT_HUB_SUPPORT_CONTROL to setOf(KickrGattProperty.WRITE, KickrGattProperty.INDICATE),
                SigUuids.UUID_HEART_RATE_MEASUREMENT to setOf(KickrGattProperty.NOTIFY),
                SigUuids.UUID_BODY_SENSOR_LOCATION to setOf(KickrGattProperty.READ),
                NordicUuids.UUID_NORDIC_UART_RX to setOf(KickrGattProperty.WRITE, KickrGattProperty.WRITE_WITHOUT_RESPONSE),
                NordicUuids.UUID_NORDIC_UART_TX to setOf(KickrGattProperty.NOTIFY),
                NordicUuids.UUID_SECURE_DFU_CONTROL to setOf(KickrGattProperty.WRITE, KickrGattProperty.INDICATE),
                SigUuids.UUID_MANUFACTURER_NAME to setOf(KickrGattProperty.READ),
                SigUuids.UUID_MODEL_NUMBER to setOf(KickrGattProperty.READ),
                SigUuids.UUID_SERIAL_NUMBER to setOf(KickrGattProperty.READ),
                SigUuids.UUID_HARDWARE_REVISION to setOf(KickrGattProperty.READ),
                SigUuids.UUID_FIRMWARE_REVISION to setOf(KickrGattProperty.READ),
                WahooUuids.UUID_WAHOO_RIDEON_ASYNC_TX to setOf(KickrGattProperty.NOTIFY),
                WahooUuids.UUID_WAHOO_RIDEON_SYNC_RX to setOf(KickrGattProperty.WRITE_WITHOUT_RESPONSE),
                WahooUuids.UUID_WAHOO_RIDEON_SYNC_TX to setOf(KickrGattProperty.READ, KickrGattProperty.INDICATE),
            ),
            hub.services.flatMap { it.characteristics }.associate { it.uuid to it.properties },
        )
        assertCharacteristic(
            hub,
            WahooUuids.UUID_WAHOO_RIDEON_SERVICE,
            WahooUuids.UUID_WAHOO_RIDEON_ASYNC_TX,
            setOf(KickrGattProperty.NOTIFY),
            cccd = true,
        )
        assertCharacteristic(
            hub,
            WahooUuids.UUID_WAHOO_RIDEON_SERVICE,
            WahooUuids.UUID_WAHOO_RIDEON_SYNC_RX,
            setOf(KickrGattProperty.WRITE_WITHOUT_RESPONSE),
            cccd = false,
        )
        assertCharacteristic(
            hub,
            WahooUuids.UUID_WAHOO_RIDEON_SERVICE,
            WahooUuids.UUID_WAHOO_RIDEON_SYNC_TX,
            setOf(KickrGattProperty.READ, KickrGattProperty.INDICATE),
            cccd = true,
        )
        assertCharacteristic(
            hub,
            ZwiftUuids.UUID_ZWIFT_HUB_SUPPORT_SERVICE,
            ZwiftUuids.UUID_ZWIFT_HUB_SUPPORT_CONTROL,
            setOf(KickrGattProperty.WRITE, KickrGattProperty.INDICATE),
            cccd = true,
        )
        assertCharacteristic(
            hub,
            NordicUuids.UUID_NORDIC_UART_SERVICE,
            NordicUuids.UUID_NORDIC_UART_RX,
            setOf(KickrGattProperty.WRITE, KickrGattProperty.WRITE_WITHOUT_RESPONSE),
            cccd = false,
        )
        assertTrue(hub.services.all { KickrEvidenceLabel.LOG in it.evidence })
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
        assertNull(manifest(KickrSimulatorPersona.ZWIFT_HUB_CAPTURED).legacyAckMode)
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
