package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.Concept2Uuids

/**
 * Concept2 subscription planning, fallback policy and persona selection — plan §5a, D41.
 *
 * Kept as pure decisions rather than handler internals, and tested here for the reason §11.7
 * established in Phase 4: logic sealed inside a GATT handler cannot be reached by a test, and
 * that is how a defect ships through it.
 */
class Concept2PlanTest {

    private val multiplexed = Concept2Uuids.UUID_C2_MULTIPLEXED_INFORMATION
    private val general = Concept2Uuids.UUID_C2_GENERAL_STATUS
    private val status1 = Concept2Uuids.UUID_C2_ADDITIONAL_STATUS_1
    private val status2 = Concept2Uuids.UUID_C2_ADDITIONAL_STATUS_2
    private val stroke = Concept2Uuids.UUID_C2_ADDITIONAL_STROKE_DATA

    private val allIndividual = setOf(general, status1, status2, stroke)

    @Test
    fun `the subscription plan issues notification enables and nothing else`() {
        // A planned write would be the first step of an unsupported Concept2 control path.
        val everything = allIndividual + multiplexed + Concept2Uuids.UUID_C2_STATUS_SAMPLE_RATE

        for (available in listOf(everything, everything - multiplexed)) {
            val plan = planConcept2Discovery(available)
            assertTrue(
                plan.operations.all { it.kind == Concept2GattOperationKind.ENABLE_NOTIFICATIONS },
            )
        }
        assertTrue(
            planConcept2IndividualFallback(everything)
                .all { it.kind == Concept2GattOperationKind.ENABLE_NOTIFICATIONS },
        )
    }

    // ── UUID resolution ──────────────────────────────────────────────────────────

    @Test
    fun `concept2 uuids expand from the published base`() {
        // Quoted from the vendor specification revision 1.30, not derived from TMIW's aliases.
        // Plan §10 forbids the latter; §5a requires this to be done rather than assumed.
        assertEquals(
            UUID.fromString("ce060030-43e5-11e4-916c-0800200c9a66"),
            Concept2Uuids.UUID_C2_ERG_SERVICE,
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
        val plan = planConcept2Discovery(setOf(Concept2Uuids.UUID_C2_STATUS_SAMPLE_RATE))

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

    // ── Erg Machine Type 0x0016: the vendor's own identification ─────────────────

    @Test
    fun `the machine type enum classifies by range, not by exact value`() {
        // The specification's enum is sparse and grouped. Every documented member is checked,
        // plus the gaps between them, because Concept2 has added members to these blocks over
        // time and a range rule survives that where a lookup table would not.
        val rowers = listOf(0, 1, 2, 3, 5, 7, 8, 16, 17, 18, 19, 20, 32, 64)
        val skis = listOf(128, 143)
        val bikes = listOf(192, 193, 194, 207)

        for (v in rowers) assertEquals("0x%02X".format(v), Concept2ErgFamily.ROWER, concept2ErgFamilyFor(v))
        for (v in skis) assertEquals("0x%02X".format(v), Concept2ErgFamily.SKI, concept2ErgFamilyFor(v))
        for (v in bikes) assertEquals("0x%02X".format(v), Concept2ErgFamily.BIKE, concept2ErgFamilyFor(v))

        // Multi-erg values name their own discipline.
        assertEquals(Concept2ErgFamily.ROWER, concept2ErgFamilyFor(224))
        assertEquals(Concept2ErgFamily.SKI, concept2ErgFamilyFor(225))
        assertEquals(Concept2ErgFamily.BIKE, concept2ErgFamilyFor(226))

        // Undocumented values above the multi-erg block are not guessed at.
        assertNull(concept2ErgFamilyFor(240))
        assertNull(concept2ErgFamilyFor(255))
        assertNull(concept2ErgFamilyFor(null))
    }

    @Test
    fun `an unknown value inside the rowing block reads as a rower`() {
        // Deliberate: Concept2 added the simulator (7) and dynamic (8) entries after the
        // lettered models, so the block grows. A future rower is far likelier than a machine
        // of an entirely new kind appearing below 128.
        assertEquals(Concept2ErgFamily.ROWER, concept2ErgFamilyFor(4))
        assertEquals(Concept2ErgFamily.ROWER, concept2ErgFamilyFor(100))
    }

    @Test
    fun `the machine type octet decodes from a one-byte read`() {
        assertEquals(
            Concept2ErgFamily.BIKE,
            decodeConcept2MachineType(byteArrayOf(192.toByte())),
        )
        assertEquals(
            Concept2ErgFamily.SKI,
            decodeConcept2MachineType(byteArrayOf(128.toByte())),
        )
        assertNull("an empty read says nothing", decodeConcept2MachineType(byteArrayOf()))
        assertNull(decodeConcept2MachineType(null))
    }

    // ── Persona: machine first, name as fallback ─────────────────────────────────

    @Test
    fun `the machine type decides the persona when it is available`() {
        assertEquals(
            FusionPersona.ROW_PADDLE,
            concept2PersonaFor(machineType = Concept2ErgFamily.ROWER),
        )
        assertEquals(
            FusionPersona.ROW_PADDLE,
            concept2PersonaFor(machineType = Concept2ErgFamily.SKI),
        )
        assertEquals(
            FusionPersona.RIDE,
            concept2PersonaFor(machineType = Concept2ErgFamily.BIKE),
        )
    }

    @Test
    fun `the machine type overrides a contradicting name`() {
        // The characteristic is the vendor's own statement; a name can be anything an owner
        // typed. If they disagree, the machine wins.
        assertEquals(
            FusionPersona.RIDE,
            concept2PersonaFor(machineType = Concept2ErgFamily.BIKE, deviceName = "PM5 123 Row"),
        )
    }

    @Test
    fun `the name is used only when the machine has not identified itself`() {
        // TMIW's form: prefix PM5, postfix Row/Ski/Bike.
        assertEquals(FusionPersona.ROW_PADDLE, concept2PersonaFor(deviceName = "PM5 430123 Row"))
        assertEquals(FusionPersona.ROW_PADDLE, concept2PersonaFor(deviceName = "PM5 430123 Ski"))
        assertEquals(FusionPersona.RIDE, concept2PersonaFor(deviceName = "PM5 430123 Bike"))

        // And the fuller spellings a renamed unit or a simulator may use.
        assertEquals(FusionPersona.ROW_PADDLE, concept2PersonaFor(deviceName = "PM5 430123 RowErg"))
        assertEquals(FusionPersona.RIDE, concept2PersonaFor(deviceName = "PM5 430123 BikeErg"))
    }

    @Test
    fun `a stock PM name identifies nothing and defers to the user`() {
        // This is what a real PM advertises: the specification gives the GAP device name as
        // "PM5 430000000", serial only, and its pairing-record example is "PM5 430343693".
        // No discipline appears in it — which is exactly why TMIW, whose only path is the
        // name, prompts on every real unit.
        assertNull(concept2PersonaFor(deviceName = "PM5 430000000"))
        assertNull(concept2PersonaFor(deviceName = "PM5 430343693"))
        assertNull(concept2PersonaFor(deviceName = "PM5"))
        assertNull(concept2PersonaFor())
    }

    @Test
    fun `name fallback is case-insensitive and requires the PM prefix`() {
        assertEquals(FusionPersona.ROW_PADDLE, concept2PersonaFor(deviceName = "pm5 430123 row"))
        assertEquals(FusionPersona.RIDE, concept2PersonaFor(deviceName = "PM5 430123 BIKE"))

        // A non-Concept2 device whose name happens to contain "bike" must not be classified
        // as a PM by this function.
        assertNull(concept2PersonaFor(deviceName = "Zwift Hub Bike"))
    }

    @Test
    fun `SkiErg rows under the paddle persona rather than inventing one`() {
        // A SkiErg is not a rower, but its metrics — stroke rate, pace per 500 m, power — are
        // the rowing set, and ROW_PADDLE is the persona whose plausibility bounds fit them.
        assertEquals(
            concept2PersonaFor(machineType = Concept2ErgFamily.ROWER),
            concept2PersonaFor(machineType = Concept2ErgFamily.SKI),
        )
    }

    // ── The identification read ──────────────────────────────────────────────────

    @Test
    fun `identification reads 0x0016 when the firmware exposes it`() {
        val plan = planConcept2Identification(
            setOf(
                Concept2Uuids.UUID_C2_MODEL_NUMBER,
                Concept2Uuids.UUID_C2_SERIAL_NUMBER,
                Concept2Uuids.UUID_C2_MACHINE_TYPE,
            ),
        )

        assertEquals(1, plan.size)
        assertEquals(Concept2Uuids.UUID_C2_MACHINE_TYPE, plan.single().characteristicUuid)
        assertEquals(Concept2GattOperationKind.READ, plan.single().kind)
    }

    @Test
    fun `firmware without 0x0016 plans no identification read`() {
        // Valid only for PM5 V150-V199.99 and V204-V299.99 per the specification, so an older
        // or newer unit may not expose it. Persona then falls back to the name, and failing
        // that to the user's choice.
        val plan = planConcept2Identification(
            setOf(Concept2Uuids.UUID_C2_MODEL_NUMBER, Concept2Uuids.UUID_C2_SERIAL_NUMBER),
        )

        assertTrue(plan.isEmpty())
    }

    @Test
    fun `name recognition is narrow and does not decide activation`() {
        assertTrue(isConcept2Name("PM5 430123"))
        assertTrue(isConcept2Name("pm3"))
        assertFalse(isConcept2Name("Zwift Hub"))
        assertFalse(isConcept2Name(null))
    }
}
