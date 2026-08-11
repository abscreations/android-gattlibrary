package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Concept2 subscription planning, fallback policy and persona selection — plan §5a, D41.
 *
 * Kept as pure decisions rather than handler internals, and tested here for the reason §11.7
 * established in Phase 4: logic sealed inside a GATT handler cannot be reached by a test, and
 * that is how a defect ships through it.
 */
class Concept2PlanTest {

    private val multiplexed = BluetoothIds.UUID_C2_MULTIPLEXED_INFORMATION
    private val general = BluetoothIds.UUID_C2_GENERAL_STATUS
    private val status1 = BluetoothIds.UUID_C2_ADDITIONAL_STATUS_1
    private val status2 = BluetoothIds.UUID_C2_ADDITIONAL_STATUS_2
    private val stroke = BluetoothIds.UUID_C2_ADDITIONAL_STROKE_DATA

    private val allIndividual = setOf(general, status1, status2, stroke)

    // ── UUID resolution ──────────────────────────────────────────────────────────

    @Test
    fun `concept2 uuids expand from the published base`() {
        // Quoted from the vendor specification revision 1.30, not derived from TMIW's aliases.
        // Plan §10 forbids the latter; §5a requires this to be done rather than assumed.
        assertEquals(
            UUID.fromString("ce060030-43e5-11e4-916c-0800200c9a66"),
            BluetoothIds.UUID_C2_ERG_SERVICE,
        )
        assertEquals(UUID.fromString("ce060031-43e5-11e4-916c-0800200c9a66"), general)
        assertEquals(UUID.fromString("ce060032-43e5-11e4-916c-0800200c9a66"), status1)
        assertEquals(UUID.fromString("ce060033-43e5-11e4-916c-0800200c9a66"), status2)
        assertEquals(UUID.fromString("ce060036-43e5-11e4-916c-0800200c9a66"), stroke)
        assertEquals(UUID.fromString("ce060080-43e5-11e4-916c-0800200c9a66"), multiplexed)
    }

    @Test
    fun `the alias low byte matches the multiplexed identification byte`() {
        // The multiplexed identifiers are the low bytes of these UUIDs, which is what lets one
        // set of decoders serve both delivery paths.
        val ids = mapOf(
            general to Concept2Measurement.Id.GENERAL_STATUS,
            status1 to Concept2Measurement.Id.ADDITIONAL_STATUS_1,
            status2 to Concept2Measurement.Id.ADDITIONAL_STATUS_2,
            stroke to Concept2Measurement.Id.ADDITIONAL_STROKE_DATA,
        )
        for ((uuid, id) in ids) {
            val alias = ((uuid.mostSignificantBits ushr 32) and 0xFFFF).toInt()
            assertEquals("alias low byte must equal the multiplexed identifier", id, alias)
        }
    }

    // ── Planning ─────────────────────────────────────────────────────────────────

    @Test
    fun `a PM offering the multiplexed characteristic is subscribed once`() {
        val plan = planConcept2Discovery(allIndividual + multiplexed)

        assertTrue(plan.activate)
        assertEquals(Concept2DeliveryPath.MULTIPLEXED, plan.path)
        assertEquals(1, plan.operations.size)
        assertEquals(multiplexed, plan.operations.single().characteristicUuid)
    }

    @Test
    fun `a PM without the multiplexed characteristic falls straight to individual`() {
        val plan = planConcept2Discovery(allIndividual)

        assertTrue(plan.activate)
        assertEquals(Concept2DeliveryPath.INDIVIDUAL, plan.path)
        assertEquals(
            "older firmware predates 0x0080; the four consumed characteristics are armed instead",
            CONCEPT2_CONSUMED_CHARACTERISTICS,
            plan.operations.map { it.characteristicUuid },
        )
    }

    @Test
    fun `a partial individual set arms only what is present`() {
        val plan = planConcept2Discovery(setOf(general, stroke))

        assertTrue(plan.activate)
        assertEquals(Concept2DeliveryPath.INDIVIDUAL, plan.path)
        assertEquals(listOf(general, stroke), plan.operations.map { it.characteristicUuid })
    }

    @Test
    fun `a service with none of the consumed characteristics does not activate`() {
        val plan = planConcept2Discovery(setOf(BluetoothIds.UUID_C2_STATUS_SAMPLE_RATE))

        assertFalse(plan.activate)
        assertNull(plan.path)
        assertTrue(plan.operations.isEmpty())
    }

    // ── Fallback ─────────────────────────────────────────────────────────────────

    @Test
    fun `a silent multiplexed characteristic falls back after the timeout`() {
        assertTrue(
            concept2FallbackDecision(
                Concept2DeliveryPath.MULTIPLEXED,
                framesReceived = 0,
                elapsedSinceCccdReadyMs = CONCEPT2_LIVENESS_TIMEOUT_MS,
            ),
        )
    }

    @Test
    fun `a single multiplexed frame prevents the fallback forever`() {
        // One frame proves the path works. A PM that then goes quiet is a PM nobody is using,
        // not a broken subscription.
        assertFalse(
            concept2FallbackDecision(
                Concept2DeliveryPath.MULTIPLEXED,
                framesReceived = 1,
                elapsedSinceCccdReadyMs = CONCEPT2_LIVENESS_TIMEOUT_MS * 100,
            ),
        )
    }

    @Test
    fun `the fallback does not fire before the timeout`() {
        assertFalse(
            concept2FallbackDecision(
                Concept2DeliveryPath.MULTIPLEXED,
                framesReceived = 0,
                elapsedSinceCccdReadyMs = CONCEPT2_LIVENESS_TIMEOUT_MS - 1,
            ),
        )
    }

    @Test
    fun `the individual path never falls back to itself`() {
        assertFalse(
            concept2FallbackDecision(
                Concept2DeliveryPath.INDIVIDUAL,
                framesReceived = 0,
                elapsedSinceCccdReadyMs = CONCEPT2_LIVENESS_TIMEOUT_MS * 10,
            ),
        )
    }

    @Test
    fun `the timeout is generous relative to the default notification cadence`() {
        // 0x0034 defaults to 500 ms, so a live PM should produce roughly twenty frames inside
        // the window. Pinned because the margin is the whole argument for not needing a
        // motion gate to arm the timer.
        val defaultCadenceMs = 500L
        assertTrue(
            "the window must leave room for a machine that is merely idle",
            CONCEPT2_LIVENESS_TIMEOUT_MS / defaultCadenceMs >= 20,
        )
    }

    @Test
    fun `the fallback arms only the characteristics the PM actually has`() {
        assertEquals(
            CONCEPT2_CONSUMED_CHARACTERISTICS,
            planConcept2IndividualFallback(allIndividual).map { it.characteristicUuid },
        )
        assertEquals(
            listOf(status1),
            planConcept2IndividualFallback(setOf(status1, multiplexed)).map { it.characteristicUuid },
        )
        assertTrue(planConcept2IndividualFallback(emptySet()).isEmpty())
    }

    // ── Persona ──────────────────────────────────────────────────────────────────

    @Test
    fun `the name postfix selects the persona`() {
        assertEquals(FusionPersona.ROW_PADDLE, concept2PersonaFor("PM5 430123 RowErg"))
        assertEquals(FusionPersona.ROW_PADDLE, concept2PersonaFor("PM5 430123 SkiErg"))
        assertEquals(FusionPersona.RIDE, concept2PersonaFor("PM5 430123 BikeErg"))
    }

    @Test
    fun `a bare PM stays multi-sport and defers to the user`() {
        // Not a failure to classify: the machine has not said which ergometer it is, so the
        // user's session-mode selection stands. Returning a guess here would override them.
        assertNull(concept2PersonaFor("PM5 430123"))
        assertNull(concept2PersonaFor("PM5"))
        assertNull(concept2PersonaFor(null))
    }

    @Test
    fun `persona matching is case-insensitive`() {
        assertEquals(FusionPersona.ROW_PADDLE, concept2PersonaFor("pm5 rowerg"))
        assertEquals(FusionPersona.RIDE, concept2PersonaFor("PM5 BIKEERG"))
    }

    @Test
    fun `SkiErg rows under the paddle persona rather than inventing one`() {
        // A SkiErg is not a rower, but its metrics — stroke rate, pace per 500 m, power — are
        // the rowing set, and ROW_PADDLE is the persona whose plausibility bounds fit them.
        assertEquals(
            concept2PersonaFor("PM5 RowErg"),
            concept2PersonaFor("PM5 SkiErg"),
        )
    }

    @Test
    fun `name recognition is narrow and does not decide activation`() {
        assertTrue(isConcept2Name("PM5 430123"))
        assertTrue(isConcept2Name("pm3"))
        assertFalse(isConcept2Name("Zwift Hub"))
        assertFalse(isConcept2Name(null))
    }
}
