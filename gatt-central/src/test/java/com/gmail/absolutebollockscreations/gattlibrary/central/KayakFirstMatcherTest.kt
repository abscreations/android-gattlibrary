package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids

class KayakFirstMatcherTest {
    private val registry = DeviceMatcherRegistry.standard()
    private val service = CanonicalUuid.fromUuid(BluetoothIds.UUID_KAYAK_FIRST_SERVICE)
    private val io = CanonicalUuid.fromUuid(BluetoothIds.UUID_KAYAK_FIRST_IO)

    @Test
    fun `advertised FFE0 admits only a provisional rowing machine candidate`() {
        val candidate = candidate(
            MatchObservation(advertisedServiceUuids = setOf(service)),
        )

        assertTrue(candidate.machineRole)
        assertEquals(MatchTransport.GATT, candidate.transport)
        assertEquals(ActivityType.ROWING, candidate.advertisedActivityType)
        assertEquals(setOf(MatchEvidenceKind.ADVERTISED_SERVICE), kinds(candidate))
    }

    @Test
    fun `physical advertisement shape adds confidence without exposing its identifier`() {
        val payload = ManufacturerPayload.fromAndroidScanRecord(
            KAYAK_FIRST_MANUFACTURER_ID,
            byteArrayOf(0x88.toByte(), 0xA0.toByte(), 1, 2, 3, 4, 5, 6),
        )
        val candidate = candidate(
            MatchObservation(
                localName = "25100001",
                deviceAddress = "06:05:04:03:02:01",
                manufacturerCompanyIds = setOf(KAYAK_FIRST_MANUFACTURER_ID),
                manufacturerPayloads = mapOf(KAYAK_FIRST_MANUFACTURER_ID to payload),
                advertisedServiceUuids = setOf(service),
            ),
        )

        assertEquals(
            setOf(
                MatchEvidenceKind.ADVERTISED_SERVICE,
                MatchEvidenceKind.MANUFACTURER_COMPANY_ID,
                MatchEvidenceKind.MANUFACTURER_PAYLOAD,
                MatchEvidenceKind.ADDRESS_CORRELATION,
                MatchEvidenceKind.LOCAL_NAME_PATTERN,
            ),
            kinds(candidate),
        )
        assertTrue(candidate.evidence.facts.none { "25100001" in it.structuralValue })
        assertTrue(candidate.evidence.facts.none { "88" in it.structuralValue })
        assertTrue(candidate.evidence.facts.none { "06:05" in it.structuralValue })
    }

    @Test
    fun `name or manufacturer shape without FFE0 never admits the family`() {
        val payload = ManufacturerPayload.fromAndroidScanRecord(
            KAYAK_FIRST_MANUFACTURER_ID,
            byteArrayOf(0x88.toByte(), 0xA0.toByte(), 1, 2, 3, 4, 5, 6),
        )
        val observations = listOf(
            MatchObservation(localName = "25100001"),
            MatchObservation(
                manufacturerCompanyIds = setOf(KAYAK_FIRST_MANUFACTURER_ID),
                manufacturerPayloads = mapOf(KAYAK_FIRST_MANUFACTURER_ID to payload),
            ),
        )
        observations.forEach { observation ->
            assertTrue(
                registry.rankedCandidates(observation).none {
                    it.candidateId == KAYAK_FIRST_CANDIDATE_ID
                },
            )
        }
    }

    @Test
    fun `manufacturer prefix and exact length are both required for confidence`() {
        val wrong = listOf(
            byteArrayOf(0x88.toByte(), 0xA0.toByte(), 1, 2, 3, 4, 5),
            byteArrayOf(0x88.toByte(), 0xA1.toByte(), 1, 2, 3, 4, 5, 6),
        )
        wrong.forEach { value ->
            val payload = ManufacturerPayload.fromAndroidScanRecord(
                KAYAK_FIRST_MANUFACTURER_ID,
                value,
            )
            assertFalse(isKayakFirstManufacturerPayload(payload))
            val candidate = candidate(
                MatchObservation(
                    advertisedServiceUuids = setOf(service),
                    manufacturerCompanyIds = setOf(KAYAK_FIRST_MANUFACTURER_ID),
                    manufacturerPayloads = mapOf(KAYAK_FIRST_MANUFACTURER_ID to payload),
                ),
            )
            assertFalse(MatchEvidenceKind.MANUFACTURER_PAYLOAD in kinds(candidate))
        }
    }

    @Test
    fun `post-discovery exact parent identifiers can reconstruct a candidate`() {
        val candidate = candidate(
            MatchObservation(
                discoveredServiceUuids = setOf(service),
                discoveredCharacteristicUuids = setOf(io),
            ),
        )
        assertEquals(
            setOf(MatchEvidenceKind.GATT_SERVICE, MatchEvidenceKind.GATT_CHARACTERISTIC),
            kinds(candidate),
        )
    }

    @Test
    fun `FTMS wins an ambiguous advertisement and FFE0 stays reachable`() {
        val ftms = CanonicalUuid.fromUuid(FtmsUuids.UUID_FTMS_SERVICE)
        val candidates = registry.rankedCandidates(
            MatchObservation(advertisedServiceUuids = setOf(service, ftms)),
        )

        assertEquals("sig.ftms", candidates.first().candidateId)
        assertTrue(candidates.any { it.candidateId == KAYAK_FIRST_CANDIDATE_ID })
        assertTrue(service in registry.scanFilterServiceUuids())
        assertTrue(BluetoothIds.UUID_KAYAK_FIRST_SERVICE in hardwareScanServiceUuids())
    }

    private fun candidate(observation: MatchObservation): RankedMatchCandidate =
        registry.rankedCandidates(observation).single {
            it.candidateId == KAYAK_FIRST_CANDIDATE_ID
        }

    private fun kinds(candidate: RankedMatchCandidate): Set<MatchEvidenceKind> =
        candidate.evidence.facts.mapTo(linkedSetOf()) { it.kind }
}
