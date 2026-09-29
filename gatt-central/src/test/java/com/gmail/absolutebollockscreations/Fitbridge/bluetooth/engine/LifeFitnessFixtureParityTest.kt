package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6 work unit 6.2 — the four Life Fitness persona corpora, plan §11.3 and §11.11.
 *
 * ## Two sources doing different jobs
 *
 * The **vectors** come from TMIW's four Life Fitness parser tests at the pinned commit. The
 * **confirmation** comes from TMIW issue #462: official-app HCI captures of physical Life
 * Fitness equipment, which record the notification lengths four real machines actually emitted.
 * Neither alone would justify tier C for this path — TMIW alone is single-source, and #462
 * alone preserves no packet bytes, because the raw captures carry addresses and profile data
 * and are deliberately not archived.
 *
 * Together they say something worth having: these are the packet shapes real machines send, and
 * here is a decoder that reproduces the reference implementation's values for them.
 *
 * ## The confirmation is checked, not just cited
 *
 * `physicallyConfirmedLength` per case, verified against the corpus's own
 * `physicallyObservedLengths`, and the check found something: **three of the four personas
 * carry a TMIW vector at a length no observed machine produced** — cross trainer 35, stair
 * climber 18, indoor bike 28.
 *
 * Those are not bad data. Each is a different flag word with one more or fewer optional field,
 * exactly what a machine with a different feature set emits, and they are valuable parser
 * coverage. They are simply tier-1 evidence sitting beside tier-C evidence in the same file, and
 * the distinction is recorded rather than averaged away. Four machines at one event is four
 * machines, not a product line.
 *
 * ## The capture corpus, and which corpus decides what
 *
 * The `life_fitness_issue_462_*_capture` fixtures hold 447 sanitized ATT payloads extracted from
 * the same archive — the physical packets themselves, in capture order, with every address,
 * name, timestamp and non-measurement frame removed. They carry no decoded expectations by
 * design: manufacturing goldens from FitBridge's own decoder would make parity circular.
 *
 * The two corpora answer different questions and neither substitutes for the other:
 *
 * - **What must the decoder produce?** The parser-test corpus, whose expectations come from
 *   TMIW rather than from FitBridge.
 * - **What do real machines actually send?** The capture corpus — and therefore which flag and
 *   length families may gate a decoding quirk.
 *
 * That second point settled an open question. `0x19FA`/28 appears in TMIW's tests and in **none**
 * of the 115 physical bike packets, so it is reference coverage and not a quirk fingerprint.
 */
class LifeFitnessFixtureParityTest {

    private val corpora = listOf(
        "ftms_life_fitness_cross_trainer",
        "ftms_life_fitness_stair_climber",
        "ftms_life_fitness_indoor_bike",
        "ftms_life_fitness_treadmill",
    )

    private fun load(name: String): Map<String, Any?> {
        val stream = javaClass.classLoader?.getResourceAsStream("fixtures/tmiw/$name.json")
        assertNotNull("fixture $name.json is missing from test resources", stream)
        return FixtureJson.obj(FixtureJson.parse(stream!!.bufferedReader().use { it.readText() }))
    }

    private fun cases(fixture: Map<String, Any?>) =
        FixtureJson.array(fixture["cases"]).map(FixtureJson::obj)

    private fun packetOf(case: Map<String, Any?>): ByteArray =
        FixtureJson.array(case["packet"]).map { FixtureJson.int(it).toByte() }.toByteArray()

    @Test
    fun `every case declares a length that matches its packet`() {
        for (name in corpora) {
            for (case in cases(load(name))) {
                val label = "$name/${FixtureJson.string(case["name"])}"
                assertEquals(
                    "$label declared length",
                    FixtureJson.int(case["length"]),
                    packetOf(case).size,
                )
            }
        }
    }

    @Test
    fun `every case declares its flag word correctly`() {
        // The flag word drives every optional-field decision downstream, so a corpus that
        // mislabels it would make the quirk fingerprints below meaningless.
        for (name in corpora) {
            for (case in cases(load(name))) {
                val packet = packetOf(case)
                val flags = (packet[0].toInt() and 0xFF) or ((packet[1].toInt() and 0xFF) shl 8)
                assertEquals(
                    "$name/${FixtureJson.string(case["name"])} flags",
                    FixtureJson.string(case["flags"]),
                    "0x%04X".format(flags),
                )
            }
        }
    }

    @Test
    fun `only the cross trainer declares a 24-bit flags field, and its label is provably complete`() {
        // Found while diagnosing a replay failure, not by reading the specification: an
        // implausible 619 km/h came out of reading speed at offset 2, because FTMS Cross Trainer
        // Data carries a **24-bit** flags field where 0x2ACD, 0x2AD0 and 0x2AD2 carry 16.
        // `doElliptical` already reads it correctly; the fixtures I generated declared
        // `flagByteSize: 2` for all four, which was wrong for this one.
        //
        // The per-case `flags` label remains a 16-bit value for grouping consistency. That is
        // only honest while the high octet is zero, so it is asserted rather than assumed — in
        // both the reference corpus and all 77 captured packets. A future capture with a
        // non-zero high octet makes the label ambiguous and the grouping key must widen.
        assertEquals(3, FixtureJson.int(load("ftms_life_fitness_cross_trainer")["flagByteSize"]))
        for (name in corpora - "ftms_life_fitness_cross_trainer") {
            assertEquals("$name flags are 16-bit", 2, FixtureJson.int(load(name)["flagByteSize"]))
        }

        // The two corpora label their cases differently — the reference fixtures carry a string
        // `name`, the capture fixtures a numeric `sequence` — so the label is built without
        // assuming either type.
        for (name in listOf("ftms_life_fitness_cross_trainer", CROSS_TRAINER_CAPTURE)) {
            for (case in cases(load(name))) {
                val label = case["name"] ?: case["sequence"]
                assertEquals(
                    "$name/$label: the 16-bit flag label is only valid while the high octet is zero",
                    0,
                    packetOf(case)[2].toInt() and 0xFF,
                )
            }
        }
    }

    @Test
    fun `physical confirmation is computed from the declared observed lengths, not asserted`() {
        // The corroboration claim has to be checkable or it is decoration. This recomputes each
        // case's confirmation flag from the corpus's own observed-length set.
        for (name in corpora) {
            val fixture = load(name)
            val observed = FixtureJson.array(fixture["physicallyObservedLengths"])
                .map(FixtureJson::int)
                .toSet()
            assertTrue("$name must declare observed lengths", observed.isNotEmpty())

            for (case in cases(fixture)) {
                val label = "$name/${FixtureJson.string(case["name"])}"
                assertEquals(
                    "$label confirmation must follow from the observed set",
                    FixtureJson.int(case["length"]) in observed,
                    case["physicallyConfirmedLength"] as Boolean,
                )
            }
        }
    }

    @Test
    fun `the corpora record which vectors physical capture did not confirm`() {
        // Pins the finding rather than leaving it in a comment: if a future capture widens the
        // observed set, this fails and the evidence record is updated deliberately.
        val unconfirmed = corpora.associateWith { name ->
            cases(load(name)).filterNot { it["physicallyConfirmedLength"] as Boolean }
                .map { FixtureJson.int(it["length"]) }
                .toSet()
        }.filterValues { it.isNotEmpty() }

        assertEquals(
            mapOf(
                "ftms_life_fitness_cross_trainer" to setOf(35),
                "ftms_life_fitness_stair_climber" to setOf(18),
                "ftms_life_fitness_indoor_bike" to setOf(28),
            ),
            unconfirmed,
        )
        assertFalse(
            "the treadmill corpus is fully confirmed",
            unconfirmed.containsKey("ftms_life_fitness_treadmill"),
        )
    }

    @Test
    fun `each corpus names its source, commit and corroboration`() {
        for (name in corpora) {
            val fixture = load(name)
            assertTrue(
                "$name must name its TMIW source file",
                FixtureJson.string(fixture["source"]).contains(".dart"),
            )
            assertEquals(
                "$name must pin the reference commit",
                "85f2ee7520705c99ce81db7355df1be7f4b72ecf",
                FixtureJson.string(fixture["source_commit"]),
            )
            assertTrue(
                "$name must cite the physical corroboration and its limit",
                FixtureJson.string(fixture["corroboration"]).contains("#462") &&
                    FixtureJson.string(fixture["corroborationLimit"]).isNotBlank(),
            )
        }
    }

    // ── Q28: the cadence quirk, evidenced ────────────────────────────────────────

    @Test
    fun `every indoor bike vector carries cadence while its presence flag is clear`() {
        // The quirk itself, stated against real vectors. FTMS Indoor Bike Data bit 2 is
        // Instantaneous Cadence Present; it is clear in all five, and TMIW still decodes a
        // plausible cadence from offsets 6-7 at 0.5 rpm resolution. Issue #462's independent
        // bike trace shows the same behaviour on physical hardware.
        val fixture = load("ftms_life_fitness_indoor_bike")
        var checked = 0

        for (case in cases(fixture)) {
            val label = FixtureJson.string(case["name"])
            val packet = packetOf(case)
            val flags = (packet[0].toInt() and 0xFF) or ((packet[1].toInt() and 0xFF) shl 8)

            assertEquals(
                "$label must have the cadence presence bit clear — that is the quirk",
                0,
                flags and CADENCE_PRESENT_BIT,
            )

            val hidden = ((packet[6].toInt() and 0xFF) or ((packet[7].toInt() and 0xFF) shl 8)) / 2.0
            assertEquals(
                "$label hidden cadence at offsets 6-7",
                FixtureJson.double(FixtureJson.obj(case["expect"])["cadence"]),
                hidden,
                0.5,
            )
            assertTrue("$label cadence must be plausible", hidden in 0.0..250.0)
            checked++
        }

        assertEquals("all five bike vectors participate", 5, checked)
    }

    @Test
    fun `only physically captured flag and length pairs are quirk fingerprints`() {
        // §4c's rule: a quirk without a fingerprint is ungated name-prefix matching. **Which
        // fingerprints qualify is decided by the capture corpus, not by the parser-test corpus.**
        //
        // The parser-test corpus carries three families. The 447-packet capture corpus carries
        // two: `0x19FA`/28 appears in TMIW's tests and in no physical packet. It stays as parser
        // coverage and must not gate a decoding quirk, because a fingerprint's whole job is to
        // say "a real machine does this" and nothing observed says that of `0x19FA`.
        val fromParserTests = cases(load("ftms_life_fitness_indoor_bike"))
            .map { FixtureJson.string(it["flags"]) to FixtureJson.int(it["length"]) }
            .toSet()
        val fromCapture = quirkFingerprints()

        assertEquals(setOf("0x09FA" to 26, "0x0BFA" to 27, "0x19FA" to 28), fromParserTests)
        assertEquals(setOf("0x09FA" to 26, "0x0BFA" to 27), fromCapture)
        assertTrue(
            "every physically observed family must also be reference-covered",
            fromParserTests.containsAll(fromCapture),
        )
        assertEquals(
            "0x19FA/28 is reference-only and is not a quirk fingerprint",
            setOf("0x19FA" to 28),
            fromParserTests - fromCapture,
        )
    }

    @Test
    fun `all 115 captured bike packets have the cadence presence bit clear`() {
        // The quirk asserted against physical packets rather than reference expectations. This
        // is what raises Q28 from "TMIW says so" to "a real machine did this 115 times".
        val fixture = load(BIKE_CAPTURE)
        val cases = cases(fixture)

        assertEquals(115, cases.size)
        assertEquals(115, FixtureJson.int(fixture["caseCount"]))

        for (case in cases) {
            val packet = packetOf(case)
            val flags = (packet[0].toInt() and 0xFF) or ((packet[1].toInt() and 0xFF) shl 8)
            assertEquals(
                "sequence ${FixtureJson.int(case["sequence"])} must have the cadence bit clear",
                0,
                flags and CADENCE_PRESENT_BIT,
            )
        }
    }

    @Test
    fun `a conforming packet with a clear cadence bit must not be read as carrying cadence`() {
        // The failure mode of an over-broad quirk, and worse than shipping no quirk at all: two
        // arbitrary octets of an ordinary machine's packet become a cadence reading.
        //
        // **The first version of this test was wrong**, and instructively so. It used flags
        // `0x0044` as its "cadence bit clear" control — but `0x0044` is `0x40 | 0x04`, and
        // `0x04` *is* the cadence bit. The guard assertion below is what caught it, which is the
        // argument for asserting a control case's premise rather than trusting the constant.
        //
        // `0x0040` is Instantaneous Power Present alone: flags 2 + instantaneous speed 2 +
        // instantaneous power 2 = 6 octets, with bit 0 clear meaning speed is present.
        val conformingFlags = 0x0040
        val conformingLength = 6

        assertEquals(
            "the control case must genuinely have the cadence bit clear",
            0,
            conformingFlags and CADENCE_PRESENT_BIT,
        )

        assertFalse(
            "a conforming packet must not match a physically observed quirk family",
            ("0x%04X".format(conformingFlags) to conformingLength) in quirkFingerprints(),
        )
    }

    /** The physically observed fingerprints, read from the capture corpus rather than restated. */
    private fun quirkFingerprints(): Set<Pair<String, Int>> =
        FixtureJson.array(load(BIKE_CAPTURE)["observedFingerprints"])
            .map(FixtureJson::obj)
            .map { FixtureJson.string(it["flags"]) to FixtureJson.int(it["length"]) }
            .toSet()

    private companion object {
        /** FTMS Indoor Bike Data flags bit 2. */
        const val CADENCE_PRESENT_BIT = 0x0004

        /** The 115-packet sanitized capture corpus extracted from TMIW issue #462. */
        const val BIKE_CAPTURE = "life_fitness_issue_462_indoor_bike_capture"

        /** The 77-packet cross-trainer capture, the only 24-bit-flags persona. */
        const val CROSS_TRAINER_CAPTURE = "life_fitness_issue_462_cross_trainer_capture"
    }
}
