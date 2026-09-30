package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids

/**
 * Plan §11.1 matcher obligations for the Concept2 PM — positive, negative, ambiguous, and
 * standard-plus-proprietary coexistence.
 *
 * `vendor.concept2.pm` is the registry's first **non-SIG** definition. `MatchDefinition` already
 * stored an arbitrary [CanonicalUuid], so no new machinery was needed — which is worth pinning,
 * because the temptation with a vendor family is to add a parallel path rather than use the one
 * that already generalises.
 */
class Concept2MatcherTest {

    private val registry = DeviceMatcherRegistry.standard()

    private fun uuid(u: java.util.UUID) = CanonicalUuid.fromUuid(u)

    private val ergService = uuid(BluetoothIds.UUID_C2_ERG_SERVICE)
    private val ftmsService = uuid(FtmsUuids.UUID_FTMS_SERVICE)

    private fun candidates(observation: MatchObservation) =
        registry.rankedCandidates(observation)

    private fun idsOf(observation: MatchObservation) =
        candidates(observation).map { it.candidateId }

    // ── Positive ─────────────────────────────────────────────────────────────────

    @Test
    fun `an advertised Erg service matches the Concept2 candidate`() {
        val ids = idsOf(MatchObservation(advertisedServiceUuids = setOf(ergService)))

        assertEquals(listOf("vendor.concept2.pm"), ids)
    }

    @Test
    fun `a discovered Erg service and its characteristics are decisive`() {
        val match = candidates(
            MatchObservation(
                localName = "PM5 430123 RowErg",
                discoveredServiceUuids = setOf(ergService),
                discoveredCharacteristicUuids = setOf(
                    uuid(BluetoothIds.UUID_C2_GENERAL_STATUS),
                    uuid(BluetoothIds.UUID_C2_ADDITIONAL_STATUS_1),
                    uuid(BluetoothIds.UUID_C2_MULTIPLEXED_INFORMATION),
                ),
            ),
        ).single()

        assertEquals("vendor.concept2.pm", match.candidateId)
        // Service (75) plus three characteristic fingerprints (90 each) saturates confidence,
        // and none of it comes from the name.
        assertTrue(
            "three characteristic fingerprints plus the service should be near-certain",
            match.evidence.confidence >= 0.99,
        )
        assertTrue(
            "no name evidence contributes to this match",
            match.evidence.facts.none { it.kind == MatchEvidenceKind.LOCAL_NAME_PATTERN },
        )
    }

    @Test
    fun `any one persona characteristic is enough to fingerprint the family`() {
        // No other vendor uses this base UUID, so a single characteristic is decisive. Tested
        // per characteristic because a PM on older firmware may expose only some of them.
        for (characteristic in listOf(
            BluetoothIds.UUID_C2_GENERAL_STATUS,
            BluetoothIds.UUID_C2_ADDITIONAL_STATUS_1,
            BluetoothIds.UUID_C2_ADDITIONAL_STATUS_2,
            BluetoothIds.UUID_C2_ADDITIONAL_STROKE_DATA,
            BluetoothIds.UUID_C2_MULTIPLEXED_INFORMATION,
        )) {
            val ids = idsOf(
                MatchObservation(discoveredCharacteristicUuids = setOf(uuid(characteristic))),
            )
            assertEquals("$characteristic should identify a PM", listOf("vendor.concept2.pm"), ids)
        }
    }

    // ── Negative ─────────────────────────────────────────────────────────────────

    @Test
    fun `a name alone never matches the family`() {
        // The definition carries no local-name pattern at all. A `^pm[0-9]` pattern was tried
        // and removed: it adds nothing when structural evidence is present, and the only case
        // it changes is a device advertising nothing Concept2 while calling itself PM5 —
        // exactly where a match must not be claimed.
        assertTrue(idsOf(MatchObservation(localName = "PM5 430123")).isEmpty())
        assertTrue(idsOf(MatchObservation(localName = "PM5 430123 RowErg")).isEmpty())
    }

    @Test
    fun `the name selects a persona but never an identity`() {
        // Identity and persona are different questions. The name answers the second only, and
        // only once structure has answered the first.
        assertTrue(idsOf(MatchObservation(localName = "PM5 RowErg")).isEmpty())
        assertEquals(FusionPersona.ROW_PADDLE, concept2PersonaFor(deviceName = "PM5 RowErg"))
    }

    @Test
    fun `an unrelated machine does not match`() {
        val ids = idsOf(
            MatchObservation(
                localName = "Zwift Hub",
                advertisedServiceUuids = setOf(ftmsService),
            ),
        )

        assertEquals(listOf("sig.ftms"), ids)
    }

    @Test
    fun `a renamed PM still matches on structure`() {
        // A PM5 renamed by its owner is still a PM5. The name pattern is supporting evidence
        // only, so losing it must not lose the match.
        val match = candidates(
            MatchObservation(
                localName = "Boathouse erg 3",
                discoveredServiceUuids = setOf(ergService),
            ),
        ).single()

        assertEquals("vendor.concept2.pm", match.candidateId)
    }

    // ── Ambiguous: standard plus proprietary coexistence ─────────────────────────

    @Test
    fun `a PM5 advertising both services matches both candidates`() {
        // The §11.1 coexistence case. Both must match: discarding the proprietary candidate
        // here is exactly what D6 rejects, because it removes the fallback before the liveness
        // timeout can ever use it.
        val ids = idsOf(
            MatchObservation(
                localName = "PM5 430123",
                advertisedServiceUuids = setOf(ftmsService, ergService),
            ),
        )

        assertEquals(2, ids.size)
        assertTrue("sig.ftms" in ids)
        assertTrue("vendor.concept2.pm" in ids)
    }

    @Test
    fun `matcher ranking does not restate the D6 adapter decision`() {
        // Both candidates match with equal structural evidence, so matcher order is decided by
        // preference. That ordering is about which candidate is more specific, not about which
        // adapter drives the link — D6 answers that in AdapterLivenessCoordinator. Two
        // independent statements of the same decision would be a defect waiting to diverge.
        val observation = MatchObservation(
            advertisedServiceUuids = setOf(ftmsService, ergService),
        )
        val ranked = candidates(observation)

        assertEquals(2, ranked.size)
        assertEquals(
            "equal structural evidence: an advertised service match apiece",
            ranked[0].rank,
            ranked[1].rank,
        )
        assertTrue(
            "FTMS is the more general definition and sorts first here",
            ranked.first().candidateId == "sig.ftms",
        )
    }

    // ── Registry integrity ───────────────────────────────────────────────────────

    @Test
    fun `the Erg service is registered as a full vendor UUID with no known SIG service`() {
        assertTrue(ergService in registry.registeredServices())
        assertNull(
            "a vendor family has no KnownBleService; that enum is the SIG set",
            registry.knownService(ergService),
        )
        assertNotNull(registry.knownService(ftmsService))
    }

    @Test
    fun `the registered Erg UUID is the one the specification gives`() {
        assertEquals(
            "ce060030-43e5-11e4-916c-0800200c9a66",
            ergService.value,
        )
    }
}
