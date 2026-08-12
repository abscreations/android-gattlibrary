package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class DeviceMatchingTest {
    @Test
    fun `canonical UUID requires full 128-bit input and normalizes lowercase`() {
        assertEquals(
            "00001814-0000-1000-8000-00805f9b34fb",
            CanonicalUuid.parse128("00001814-0000-1000-8000-00805F9B34FB").value,
        )
        assertThrows(IllegalArgumentException::class.java) { CanonicalUuid.parse128("1814") }
        assertThrows(IllegalArgumentException::class.java) { CanonicalUuid.parse128("0x1814") }
    }

    @Test
    fun `SIG assigned numbers expand only into canonical Bluetooth base UUIDs`() {
        assertTrue(
            CanonicalUuid.fromUuid(BluetoothIds.UUID_RSC_SERVICE) in
                DeviceMatcherRegistry.standard().registeredServices(),
        )
    }

    @Test
    fun `RSC service matches without a recognizable local name`() {
        val candidates = rankedServiceCandidates(
            MatchObservation(
                localName = "",
                advertisedServiceUuids = setOf(CanonicalUuid.fromUuid(BluetoothIds.UUID_RSC_SERVICE)),
            ),
        )

        assertEquals(KnownBleService.RSC, candidates.single().knownService)
        assertEquals(MatchEvidenceKind.ADVERTISED_SERVICE, candidates.single().evidence.facts.single().kind)
    }

    @Test
    fun `service data alone produces a ranked candidate`() {
        val candidates = rankedServiceCandidates(
            MatchObservation(
                serviceDataUuids = setOf(CanonicalUuid.fromUuid(BluetoothIds.UUID_CSC_SERVICE)),
            ),
        )

        assertEquals(KnownBleService.CSC, candidates.single().knownService)
        assertEquals(70, candidates.single().rank)
    }

    @Test
    fun `multi-service ranking is deterministic and retains every candidate`() {
        val observation = MatchObservation(
            advertisedServiceUuids = setOf(
                CanonicalUuid.fromUuid(BluetoothIds.UUID_HRM_SERVICE),
                CanonicalUuid.fromUuid(BluetoothIds.UUID_FTMS_SERVICE),
                CanonicalUuid.fromUuid(BluetoothIds.UUID_RSC_SERVICE),
            ),
        )
        val candidates = rankedServiceCandidates(observation)

        assertEquals(
            listOf(KnownBleService.FTMS, KnownBleService.HRM, KnownBleService.RSC),
            candidates.map { it.knownService },
        )
        assertTrue(candidates.all { it.evidence.facts.all { fact -> fact.note.isNotBlank() } })
    }

    @Test
    fun `name evidence cannot displace an equally strong full service match`() {
        val candidates = rankedServiceCandidates(
            MatchObservation(
                localName = "Garmin HRM-Pro",
                advertisedServiceUuids = setOf(
                    CanonicalUuid.fromUuid(BluetoothIds.UUID_FTMS_SERVICE),
                    CanonicalUuid.fromUuid(BluetoothIds.UUID_HRM_SERVICE),
                ),
            ),
        )

        assertEquals(KnownBleService.FTMS, candidates.first().knownService)
        assertTrue(
            candidates.first { it.knownService == KnownBleService.HRM }
                .evidence.facts.any { it.kind == MatchEvidenceKind.LOCAL_NAME_PATTERN },
        )
    }

    @Test
    fun `full UUID comparison rejects a vendor UUID that only resembles RSC`() {
        val vendorUuid = CanonicalUuid.parse128("00001814-1111-2222-3333-444455556666")
        val candidates = rankedServiceCandidates(
            MatchObservation(advertisedServiceUuids = setOf(vendorUuid)),
        )

        assertTrue(candidates.isEmpty())
        assertFalse(vendorUuid == CanonicalUuid.fromUuid(BluetoothIds.UUID_RSC_SERVICE))
    }

    @Test
    fun `strong vendor fingerprint outranks a bare FTMS service`() {
        val vendorService = CanonicalUuid.parse128("12345678-1234-5678-9abc-def012345678")
        val personaCharacteristic = CanonicalUuid.parse128("87654321-4321-8765-cba9-876543210fed")
        val registry = DeviceMatcherRegistry.of(
            listOf(
                MatchDefinition(
                    candidateId = "generic.ftms",
                    serviceUuid = CanonicalUuid.fromUuid(BluetoothIds.UUID_FTMS_SERVICE),
                    knownService = KnownBleService.FTMS,
                    preference = 600,
                ),
                MatchDefinition(
                    candidateId = "vendor.machine",
                    serviceUuid = vendorService,
                    preference = 10,
                    localNamePatterns = listOf(Regex("Vendor Run")),
                    manufacturerCompanyIds = setOf(321),
                    personaCharacteristicUuids = setOf(personaCharacteristic),
                    packetShape = PacketShapeRule(20..20, flagsMask = 0x0f, flagsValue = 0x02),
                ),
            ),
        )
        val candidates = registry.rankedCandidates(
            MatchObservation(
                localName = "Vendor Runner",
                manufacturerCompanyIds = setOf(321),
                advertisedServiceUuids = setOf(
                    CanonicalUuid.fromUuid(BluetoothIds.UUID_FTMS_SERVICE),
                    vendorService,
                ),
                discoveredServiceUuids = setOf(vendorService),
                discoveredCharacteristicUuids = setOf(personaCharacteristic),
                packetLength = 20,
                packetFlags = 0x12,
            ),
        )

        assertEquals("vendor.machine", candidates.first().candidateId)
        assertTrue(
            candidates.first().evidence.facts.map { it.kind }.containsAll(
                setOf(
                    MatchEvidenceKind.LOCAL_NAME_PATTERN,
                    MatchEvidenceKind.MANUFACTURER_COMPANY_ID,
                    MatchEvidenceKind.ADVERTISED_SERVICE,
                    MatchEvidenceKind.GATT_SERVICE,
                    MatchEvidenceKind.GATT_CHARACTERISTIC,
                    MatchEvidenceKind.PACKET_SHAPE,
                ),
            ),
        )
        assertTrue(candidates.first().evidence.confidence > candidates.last().evidence.confidence)
    }

    @Test
    fun `hardware scan services cover every registered family, not only the SIG profiles`() {
        // **This assertion used to be an equality against exactly these five UUIDs, and that
        // was the discovery defect written down as a test.** The filter was a hardcoded list of
        // five `KnownBleService` values, so no proprietary family could ever be scanned for;
        // the test asserted the hardcoded list matched itself and passed while PM5s were
        // invisible. Corrected 2026-08-12 to assert coverage of the registry, which is the
        // property that actually matters.
        val filter = hardwareScanServiceUuids().toSet()

        for (sig in listOf(
            BluetoothIds.UUID_FTMS_SERVICE,
            BluetoothIds.UUID_HRM_SERVICE,
            BluetoothIds.UUID_CSC_SERVICE,
            BluetoothIds.UUID_RSC_SERVICE,
            BluetoothIds.UUID_POWER_SERVICE,
        )) {
            assertTrue("$sig must remain scannable", sig in filter)
        }

        for (vendor in listOf(
            BluetoothIds.UUID_C2_DISCOVERY_SERVICE,
            BluetoothIds.UUID_PRECOR_SERVICE,
        )) {
            assertTrue("$vendor must be scannable", vendor in filter)
        }

        assertEquals(
            "the filter is derived from the registry and must not drift from it",
            DeviceMatcherRegistry.standard().scanFilterServiceUuids()
                .map(CanonicalUuid::toUuid)
                .toSet(),
            filter,
        )
    }

    @Test
    fun `UUID round trip preserves exact identity`() {
        val uuid = UUID.randomUUID()
        assertEquals(uuid, CanonicalUuid.fromUuid(uuid).toUuid())
    }
}
