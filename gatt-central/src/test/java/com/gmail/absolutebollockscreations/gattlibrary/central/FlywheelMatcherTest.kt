package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.FecBleUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.FlywheelUuids

class FlywheelMatcherTest {
    private val registry = DeviceMatcherRegistry.standard()
    private val service = CanonicalUuid.fromUuid(FlywheelUuids.UUID_FLYWHEEL_SERVICE)

    @Test
    fun `exact name and full service create an evidence-bearing machine candidate`() {
        val candidate = registry.rankedCandidates(
            MatchObservation(
                localName = "Flywheel 1",
                advertisedServiceUuids = setOf(service),
            ),
        ).single { it.candidateId == FLYWHEEL_CANDIDATE_ID }

        assertTrue(candidate.machineRole)
        assertEquals(MatchTransport.GATT, candidate.transport)
        assertEquals(service, candidate.serviceUuid)
        assertEquals(
            setOf(MatchEvidenceKind.LOCAL_NAME_PATTERN, MatchEvidenceKind.ADVERTISED_SERVICE),
            candidate.evidence.facts.mapTo(linkedSetOf()) { it.kind },
        )
        assertTrue(candidate.evidence.facts.all { it.note.isNotBlank() })
    }

    @Test
    fun `name alone and Nordic UART service alone cannot claim Flywheel`() {
        val nameOnly = registry.rankedCandidates(MatchObservation(localName = "Flywheel 1"))
        val serviceOnly = registry.rankedCandidates(
            MatchObservation(advertisedServiceUuids = setOf(service)),
        )

        assertTrue(nameOnly.none { it.candidateId == FLYWHEEL_CANDIDATE_ID })
        assertTrue(serviceOnly.none { it.candidateId == FLYWHEEL_CANDIDATE_ID })
    }

    @Test
    fun `similar base and Tacx FE-C service do not satisfy Flywheel identity`() {
        val similarBase = CanonicalUuid.parse128("6e400001-b5a3-f393-e0a9-e50e24dccaaa")
        val observations = listOf(
            MatchObservation(
                localName = "Flywheel 1",
                advertisedServiceUuids = setOf(similarBase),
            ),
            MatchObservation(
                localName = "Flywheel 1",
                advertisedServiceUuids = setOf(CanonicalUuid.fromUuid(FecBleUuids.UUID_FEC_SERVICE)),
            ),
        )

        for (observation in observations) {
            assertTrue(
                registry.rankedCandidates(observation).none { it.candidateId == FLYWHEEL_CANDIDATE_ID },
            )
        }
        assertFalse(similarBase == service)
        assertFalse(FecBleUuids.UUID_FEC_SERVICE == FlywheelUuids.UUID_FLYWHEEL_SERVICE)
    }

    @Test
    fun `Flywheel service is present in the registry-derived radio filter`() {
        assertTrue(service in registry.scanFilterServiceUuids())
        assertTrue(FlywheelUuids.UUID_FLYWHEEL_SERVICE in hardwareScanServiceUuids())
    }
}
