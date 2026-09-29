package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Consumers for the four capture-derived Life Fitness fixtures extracted on 2026-08-25.
 *
 * ## Why these exist separately from [LifeFitnessQzCorpusTest]
 *
 * That class consumes *measurement* corpora — replay vectors and a negative collision set. These
 * four fixtures are the parts of the same two captures that the 2026-08-13 extraction discarded:
 * the setup transcript, the GATT inventory, the link timing, and the Discover SE frame layer. The
 * measurement filters (`btatt.uuid16 == 0x2acd`, `payload length == 40`) were correct for what they
 * were for and blind to everything else, and plan **V14** was recorded as unresolvable on the
 * strength of that blindness.
 *
 * ## What these tests are for
 *
 * Not decoding. The transcript is code FitBridge already ships; these pin it to the only trace of a
 * console being sent a profile and then streaming, so that editing an encoding back to TMIW's
 * source form fails here rather than silently diverging from hardware.
 *
 * The Discover SE assertions are deliberately *negative*: they hold the V2 variant boundary open.
 * Nothing in FitBridge parses that protocol, and these tests exist so that a future contributor
 * cannot quietly widen the canonical matcher to swallow it.
 */
class LifeFitnessCaptureEvidenceTest {

    private val fixtureNames = listOf(
        "life_fitness_issue_1018_setup_transcript",
        "life_fitness_issue_1018_gatt_inventory",
        "life_fitness_issue_1018_link_timeline",
        "life_fitness_issue_3374_discover_se_framing",
    )

    private fun text(name: String): String {
        val stream = javaClass.classLoader?.getResourceAsStream("fixtures/qz/$name.json")
            ?: error("fixture $name.json is missing")
        return stream.bufferedReader().use { it.readText() }
    }

    private fun load(name: String): Map<String, Any?> = FixtureJson.obj(FixtureJson.parse(text(name)))

    private fun arr(value: Any?): List<Map<String, Any?>> =
        FixtureJson.array(value).map(FixtureJson::obj)

    private fun bool(value: Any?): Boolean =
        value as? Boolean ?: error("expected a JSON boolean, got $value")

    private fun bytes(value: Any?): List<Int> = FixtureJson.array(value).map(FixtureJson::int)

    // ── Privacy ──────────────────────────────────────────────────────────────────

    @Test
    fun `no capture fixture carries an address, an email or a profile value`() {
        // The #1018 capture contains a real user's name, age, height and weight; the #3374 capture
        // additionally contains a facility name and a street address. Every one of those is
        // redacted at extraction. This test is the guard on re-extraction: a future regeneration
        // that forgets the redaction step fails here rather than committing someone's data.
        val mac = Regex("(?i)\\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\\b")
        val email = Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b")

        for (name in fixtureNames) {
            val raw = text(name)
            assertTrue("$name must be a JSON object", load(name).isNotEmpty())
            assertFalse("$name contains a Bluetooth address", mac.containsMatchIn(raw))
            assertFalse("$name contains an email address", email.containsMatchIn(raw))
        }
    }

    @Test
    fun `every personal setup field is redacted to structure only`() {
        val personal = setOf("first name", "last name", "email", "age", "gender", "weight", "height")

        for (session in arr(load("life_fitness_issue_1018_setup_transcript")["sessions"])) {
            for (case in arr(session["cases"]).filter { FixtureJson.string(it["step"]) in personal }) {
                val step = FixtureJson.string(case["step"])
                assertEquals("$step must be redacted", "redacted", FixtureJson.string(case["value"]))
                assertFalse("$step must not carry raw octets", case.containsKey("bytes"))
            }
        }
    }

    // ── The transcript FitBridge ships against the transcript a machine accepted ──

    private val profile = LifeFitnessProfile(
        firstName = "Given",
        lastName = "Family",
        email = "someone@example.test",
        gender = 1,
        weightKg = 75,
        heightCm = 170,
    )

    private fun shipped() = LifeFitnessSetup.transcript(
        control = BluetoothIds.UUID_LF_CONTROL_SERVICE,
        user = BluetoothIds.UUID_LF_USER_SERVICE,
        status = BluetoothIds.UUID_LF_STATUS,
        profile = profile,
        ageYears = 41,
    )

    private fun capturedWrites(session: Map<String, Any?>) =
        arr(session["cases"]).filter { FixtureJson.string(it["opcode"]).startsWith("WRITE_") }

    @Test
    fun `both captured sessions send the identical transcript`() {
        // The capture is one machine and one client, so a difference between its two sessions
        // would mean something in the handshake is stateful. Nothing is.
        val sessions = arr(load("life_fitness_issue_1018_setup_transcript")["sessions"])
        assertEquals(2, sessions.size)

        fun shape(session: Map<String, Any?>) = arr(session["cases"]).map {
            listOf(it["opcode"], it["handle"], it["byteLength"], it["bytes"])
        }
        assertEquals(shape(sessions[0]), shape(sessions[1]))
    }

    @Test
    fun `the shipped operation order is the captured operation order`() {
        val captured = capturedWrites(arr(load("life_fitness_issue_1018_setup_transcript")["sessions"])[0])
            .map { FixtureJson.string(it["step"]) }
            // The capture arms the two CCCDs after the handshake; the transcript ends at close.
            .filterNot { it.startsWith("CCCD") }

        // The transcript's labels are FitBridge's; map them onto the capture's step names.
        val shipped = shipped()
            .filterNot { it.kind == LifeFitnessSetup.OpKind.ENABLE_STATUS }
            .map { if (it.label.startsWith("control 1")) "control 1" else it.label }
            .map { if (it == "control 2 magic") "control 2" else it }

        assertEquals(shipped, captured)
    }

    @Test
    fun `names, email and language carry no NUL terminator, as the capture does not`() {
        // TMIW appends one and FitBridge copied it until 2026-08-25. Nothing on the wire has one.
        val session = arr(load("life_fitness_issue_1018_setup_transcript")["sessions"])[0]
        val strings = setOf("first name", "last name", "email", "language")

        for (case in arr(session["cases"]).filter { FixtureJson.string(it["step"]) in strings }) {
            assertFalse(
                "${FixtureJson.string(case["step"])} is terminated on the wire",
                bool(case["trailingNulTerminator"]),
            )
        }

        for (op in shipped().filter { it.label in strings }) {
            assertFalse(
                "${op.label} must not be terminated",
                op.payload!!.any { it.toInt() == 0 },
            )
        }
    }

    @Test
    fun `the shipped field widths match the captured field widths`() {
        // Age is the one that moved: 0x2A80 is uint8 in the SIG spec and TMIW writes one octet,
        // but the capture writes two. Weight and height were already right and are asserted here
        // so that a future edit cannot "fix" age by breaking them.
        val session = arr(load("life_fitness_issue_1018_setup_transcript")["sessions"])[0]
        val capturedWidth = arr(session["cases"])
            .associate { FixtureJson.string(it["step"]) to FixtureJson.int(it["byteLength"]) }

        assertEquals(2, capturedWidth.getValue("age"))
        for (step in listOf("age", "gender", "weight", "height", "language", "vendor token 1", "vendor token 2")) {
            val op = shipped().single { it.label == step }
            assertEquals("$step width", capturedWidth.getValue(step), op.payload!!.size)
        }
    }

    @Test
    fun `V14 - the captured token 2 payload is the one FitBridge ships`() {
        // The whole of V14's resolution, in one assertion. The register recorded this as an
        // unresolvable coin flip between TMIW's 0x00 and QZ's 0x01 on the stated ground that the
        // #1018 trace did not capture the step; it does, in both sessions, followed each time by a
        // completed handshake and sustained Treadmill Data.
        for (session in arr(load("life_fitness_issue_1018_setup_transcript")["sessions"])) {
            val token2 = arr(session["cases"]).single { FixtureJson.string(it["step"]) == "vendor token 2" }
            assertEquals(listOf(0x00), bytes(token2["bytes"]))
        }
        assertEquals(
            listOf(0x00),
            shipped().single { it.label == "vendor token 2" }.payload!!.map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun `the captured control payloads are byte-identical to the shipped constants`() {
        val session = arr(load("life_fitness_issue_1018_setup_transcript")["sessions"])[0]
        val control2 = arr(session["cases"]).single { FixtureJson.string(it["step"]) == "control 2" }
        val control1 = arr(session["cases"]).filter { FixtureJson.string(it["step"]) == "control 1" }

        assertEquals(
            LifeFitnessSetup.CONTROL_2_MAGIC.map { it.toInt() and 0xFF },
            bytes(control2["bytes"]),
        )
        assertEquals(listOf(LifeFitnessSetup.CONTROL_1_OPEN.toInt()), bytes(control1.first()["bytes"]))
        assertEquals(listOf(LifeFitnessSetup.CONTROL_1_CLOSE.toInt()), bytes(control1.last()["bytes"]))
    }

    @Test
    fun `the profile block is a write command on the wire and a write request in FitBridge`() {
        // D90, 2026-08-25: a deliberate, recorded divergence rather than an oversight. The
        // transcript replaced TMIW's fixed sleeps with completion gating, and a Write Command has
        // no completion to gate on. This test exists so the difference stays visible: if the
        // profile block is ever switched to write-without-response, the gating design has to be
        // revisited in the same change.
        val session = arr(load("life_fitness_issue_1018_setup_transcript")["sessions"])[0]
        val profileSteps = setOf(
            "first name", "last name", "email", "age", "gender", "language",
            "vendor token 1", "vendor token 2", "weight", "height",
        )

        for (case in arr(session["cases"]).filter { FixtureJson.string(it["step"]) in profileSteps }) {
            assertEquals(
                "${FixtureJson.string(case["step"])} is a Write Command on the wire",
                "WRITE_COMMAND",
                FixtureJson.string(case["opcode"]),
            )
        }
        for (case in arr(session["cases"]).filter { FixtureJson.string(it["step"]) == "control 2" }) {
            assertEquals("WRITE_REQUEST", FixtureJson.string(case["opcode"]))
        }
        assertTrue(
            "every profile step is still a PROFILE_WRITE in the shipped transcript",
            shipped().filter { it.label in profileSteps }
                .all { it.kind == LifeFitnessSetup.OpKind.PROFILE_WRITE },
        )
    }

    // ── The inventory: what the machine does not offer ────────────────────────────

    private fun inventory() = load("life_fitness_issue_1018_gatt_inventory")

    private fun service(uuid: UUID): Map<String, Any?> =
        arr(inventory()["services"]).single { UUID.fromString(FixtureJson.string(it["uuid"])) == uuid }

    private fun characteristicUuids(uuid: UUID): Set<UUID> =
        arr(service(uuid)["characteristics"]).map { UUID.fromString(FixtureJson.string(it["uuid"])) }.toSet()

    /** `BluetoothIds.shortUuid` is private, so the SIG base is expanded locally. */
    private fun sig(hex: Int): UUID = UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(hex))

    @Test
    fun `the captured FTMS service offers no control point and no supported ranges`() {
        // Plan section 6c.0 keeps the Life Fitness control path unreachable on the
        // untestable-family rule. This is the first direct observation that there is nothing to
        // reach: the machine's 0x1826 is a read-only telemetry surface.
        val ftms = characteristicUuids(BluetoothIds.UUID_FTMS_SERVICE)

        assertFalse("0x2AD9 control point must be absent", ftms.contains(sig(0x2AD9)))
        for (range in 0x2AD4..0x2AD8) {
            assertFalse(
                "0x${range.toString(16).uppercase()} supported-range must be absent",
                ftms.contains(sig(range)),
            )
        }
        assertTrue(ftms.contains(sig(0x2ACD)))
        assertTrue(ftms.contains(sig(0x2ACC)))
    }

    @Test
    fun `the FTMS feature value is present but unobserved and must not be inferred`() {
        // 0x2ACC is readable and the client never read it. Anything that claims to know this
        // machine's feature bitfield is inventing it.
        val assertions = FixtureJson.obj(inventory()["assertions"])

        assertTrue(bool(assertions["ftmsFeatureReadable"]))
        assertFalse(bool(assertions["ftmsFeatureValueObserved"]))
    }

    @Test
    fun `the vendor UUID constants are the ones the machine actually exposes`() {
        // D52 keeps runtime pair validation, but the constants themselves were read out of TMIW
        // and QZ source. This binds each of them to an observed GATT.
        val control = characteristicUuids(BluetoothIds.UUID_LF_CONTROL_SERVICE)
        val user = characteristicUuids(BluetoothIds.UUID_LF_USER_SERVICE)

        for (uuid in listOf(
            BluetoothIds.UUID_LF_STATUS,
            BluetoothIds.UUID_LF_CONTROL_1,
            BluetoothIds.UUID_LF_CONTROL_2,
        )) {
            assertTrue("$uuid must live under the control service", control.contains(uuid))
        }

        // Both vendor tokens are children of the USER service, not the control service, which is
        // where the shipped transcript already targets them. Asserted because getting this wrong
        // produces a write that lands nowhere rather than an obvious failure.
        assertTrue(user.contains(BluetoothIds.UUID_LF_VENDOR_TOKEN_1))
        assertTrue(user.contains(BluetoothIds.UUID_LF_VENDOR_TOKEN_2))
        assertFalse(control.contains(BluetoothIds.UUID_LF_VENDOR_TOKEN_1))
        assertFalse(control.contains(BluetoothIds.UUID_LF_VENDOR_TOKEN_2))

        // The SIG User Data characteristics are children of the vendor user service, not of a
        // 0x181C User Data service. A UUID-only lookup would not find them on this machine.
        for (uuid in listOf(
            BluetoothIds.UUID_USER_FIRST_NAME,
            BluetoothIds.UUID_USER_LAST_NAME,
            BluetoothIds.UUID_USER_EMAIL,
            BluetoothIds.UUID_USER_AGE,
            BluetoothIds.UUID_USER_GENDER,
            BluetoothIds.UUID_USER_LANGUAGE,
            BluetoothIds.UUID_USER_WEIGHT,
            BluetoothIds.UUID_USER_HEIGHT,
        )) {
            assertTrue("$uuid must live under the vendor user service", user.contains(uuid))
        }
    }

    // ── Link timing and continuity hazards ───────────────────────────────────────

    @Test
    fun `the observed cadence is not exactly 1 Hz`() {
        // A freshness or staleness bound derived from an assumed exact 1000 ms false-positives on
        // this machine. The recorded spread is the evidence for whatever bound Phase 6 picks.
        val cadence = FixtureJson.obj(load("life_fitness_issue_1018_link_timeline")["notificationCadence"])
        val min = FixtureJson.int(cadence["observedMinMs"])
        val max = FixtureJson.int(cadence["observedMaxMs"])

        assertTrue("observed minimum $min ms is well under a second", min < 1_000)
        assertTrue("observed maximum $max ms is over a second", max > 1_000)
    }

    @Test
    fun `the capture contains a byte-identical duplicate notification`() {
        // The case that breaks naive cumulative accumulation: the same packet twice, elapsed-time
        // counter included, so a parser that adds deltas without checking identity double-counts.
        val hazards = FixtureJson.obj(load("life_fitness_issue_1018_link_timeline")["continuityHazards"])
        val duplicates = arr(hazards["duplicateNotifications"])

        assertEquals(1, duplicates.size)
        val duplicate = duplicates.single()
        assertEquals(
            FixtureJson.int(duplicate["sequence"]) - 1,
            FixtureJson.int(duplicate["previousSequence"]),
        )
        assertTrue("the duplicate arrives inside a normal notification interval",
            FixtureJson.int(duplicate["deltaMs"]) < 1_000)
    }

    @Test
    fun `the second session restarts every cumulative counter`() {
        // Not a rollover. A reconnect to the same machine, with elapsed time back at 1.
        val sessions = arr(load("life_fitness_issue_1018_link_timeline")["sessions"])

        assertEquals(2, sessions.size)
        for (session in sessions) {
            assertEquals(1, FixtureJson.int(session["firstElapsedTimeSeconds"]))
        }
        assertTrue(
            FixtureJson.int(sessions[1]["firstElapsedTimeSeconds"]) <
                FixtureJson.int(sessions[0]["lastElapsedTimeSeconds"]),
        )
    }

    // ── Discover SE: holding the variant boundary open ───────────────────────────

    private fun framing() = load("life_fitness_issue_3374_discover_se_framing")

    @Test
    fun `every short Discover SE frame satisfies the checksum rule`() {
        // The rule already recorded for the 40-byte measurements generalises to every short frame
        // in the capture, which is what makes it a framing rule rather than a coincidence of one
        // packet shape.
        val counts = FixtureJson.obj(framing()["frameCounts"])

        assertEquals(0, FixtureJson.int(counts["shortFramesFailingChecksum"]))
        assertEquals(
            FixtureJson.int(counts["shortFramed"]),
            FixtureJson.int(counts["shortFramesSatisfyingChecksum"]),
        )
        assertTrue(
            "the measurements are a strict subset of the short frames",
            FixtureJson.int(counts["fortyByteMeasurements"]) < FixtureJson.int(counts["shortFramed"]),
        )
    }

    @Test
    fun `Discover SE shares the control service UUID but no characteristic with the canonical path`() {
        // V2's variant split, made executable. Matching this family on the service UUID alone
        // would fingerprint a Discover console as the setup-gated standard-FTMS machine and run a
        // handshake against characteristics it does not have.
        val discover = load("life_fitness_issue_3374_discover_se_inventory")
        val discoverService = arr(discover["services"])
            .single { UUID.fromString(FixtureJson.string(it["uuid"])) == BluetoothIds.UUID_LF_CONTROL_SERVICE }
        val discoverCharacteristics = arr(discoverService["characteristics"])
            .map { UUID.fromString(FixtureJson.string(it["uuid"])) }.toSet()

        val canonical = characteristicUuids(BluetoothIds.UUID_LF_CONTROL_SERVICE)

        assertTrue("the service UUID is shared", discoverCharacteristics.isNotEmpty() && canonical.isNotEmpty())
        assertTrue(
            "no characteristic is shared, so the service UUID alone cannot identify the variant",
            discoverCharacteristics.intersect(canonical).isEmpty(),
        )
    }
}
