package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The Life Fitness indoor-bike cadence quirk — plan §4c, Q28, and §11.3.
 *
 * ## Why this is a flag correction and not a layout
 *
 * §4c's existing quirk is a layout replacement: "ignore the specification, read these octets".
 * That is the powerful and dangerous form, which is why Mr Captain's layout is verified
 * octet-by-octet against a pinned vector.
 *
 * This machine's defect is smaller. It sends Instantaneous Cadence at its specification offset
 * and simply fails to set the presence bit. Repairing the flag word lets the standard walk do
 * every bit of the decoding, so the correction *cannot* misalign a field — the failure mode a
 * hand-written layout exists to risk.
 *
 * ## The arithmetic is the evidence, and it is asserted rather than described
 *
 * A flag correction is only admissible if the corrected word accounts for exactly the observed
 * packet length while the as-sent word does not. If that holds, the missing field must be
 * present at its standard offset and nothing has been inferred. [flag correction widths
 * reconcile the observed packet lengths] computes both widths from the FTMS field table and
 * checks them against the capture corpus.
 *
 * Without that check the quirk would rest on "TMIW decodes it this way", which is what §4c
 * spent two phases refusing to ship.
 */
class LifeFitnessCadenceQuirkTest {

    private val bikeUuid: UUID = BluetoothIds.UUID_BIKING_DATA

    /** FTMS Indoor Bike Data field widths, by flag bit. Bit 0 is inverted: clear means present. */
    private val fieldWidths = mapOf(
        1 to 2,   // Average Speed
        2 to 2,   // Instantaneous Cadence
        3 to 2,   // Average Cadence
        4 to 3,   // Total Distance
        5 to 2,   // Resistance Level
        6 to 2,   // Instantaneous Power
        7 to 2,   // Average Power
        8 to 5,   // Expended Energy: total, per hour, per minute
        9 to 1,   // Heart Rate
        10 to 1,  // Metabolic Equivalent
        11 to 2,  // Elapsed Time
        12 to 2,  // Remaining Time
    )

    private fun declaredWidth(flags: Int): Int {
        var width = 2
        if (flags and 0x0001 == 0) width += 2 // Instantaneous Speed
        for ((bit, octets) in fieldWidths) {
            if (flags and (1 shl bit) != 0) width += octets
        }
        return width
    }

    private fun captureCases(): List<Map<String, Any?>> {
        val stream = javaClass.classLoader
            ?.getResourceAsStream("fixtures/tmiw/life_fitness_issue_462_indoor_bike_capture.json")
        assertNotNull("capture corpus missing", stream)
        val fixture = FixtureJson.obj(
            FixtureJson.parse(stream!!.bufferedReader().use { it.readText() }),
        )
        return FixtureJson.array(fixture["cases"]).map(FixtureJson::obj)
    }

    private fun packetOf(case: Map<String, Any?>) =
        FixtureJson.array(case["packet"]).map { FixtureJson.int(it).toByte() }.toByteArray()

    private fun replay(packet: ByteArray, deviceName: String?): MetricFusion {
        val fusion = MetricFusion(BtEngineState()).also { it.setPersona(FusionPersona.RIDE) }
        BtEngineFtmsParsers.doBike(
            MetricEmitter(
                fusion = fusion,
                sourceId = MetricSourceId(ConnectionId("quirk", 1L), MetricProtocol.FTMS, bikeUuid),
                sourceClass = MetricSourceClass.FTMS,
                arrivalMs = 1_000L,
            ),
            packet,
            deviceName,
        )
        return fusion
    }

    // ── The evidential basis ─────────────────────────────────────────────────────

    @Test
    fun `flag correction widths reconcile the observed packet lengths`() {
        // The whole justification. As sent, each flag word accounts for two octets fewer than
        // the machine actually transmits; corrected, the arithmetic is exact. That is what makes
        // "the field is present at its standard offset" a deduction rather than a hope.
        val quirks = FtmsQuirkRegistry.all.filter { it.isFlagCorrection }
        assertEquals("two Life Fitness bike families are registered", 2, quirks.size)

        for (quirk in quirks) {
            for (flags in quirk.flagWords) {
                val asSent = declaredWidth(flags)
                val corrected = declaredWidth(quirk.correctedFlags(flags))

                assertEquals(
                    "${quirk.id}: corrected flags must account for the observed length exactly",
                    quirk.packetLength,
                    corrected,
                )
                assertTrue(
                    "${quirk.id}: the as-sent word must NOT account for it, or there would be " +
                        "no missing field and no quirk to justify",
                    asSent < quirk.packetLength,
                )
                assertEquals(
                    "${quirk.id}: the shortfall must be exactly the cadence field",
                    2,
                    corrected - asSent,
                )
            }
        }
    }

    @Test
    fun `every registered family appears in the physical capture`() {
        // §4c: a fingerprint asserts that a real machine behaves this way. A registered family
        // absent from the capture would be an assertion with nothing behind it.
        val observed = captureCases()
            .map { FixtureJson.string(it["flags"]) to FixtureJson.int(it["length"]) }
            .toSet()

        for (quirk in FtmsQuirkRegistry.all.filter { it.isFlagCorrection }) {
            for (flags in quirk.flagWords) {
                assertTrue(
                    "${quirk.id} is registered but no captured packet matches it",
                    ("0x%04X".format(flags) to quirk.packetLength) in observed,
                )
            }
        }
    }

    @Test
    fun `the unconfirmed TMIW family is deliberately not registered`() {
        // 0x19FA/28 reconciles arithmetically the same way, so it is almost certainly the same
        // machine behaviour — and it is still not registered, because it appears in no physical
        // packet. Pinned so that adding it later is a deliberate act with evidence attached
        // rather than a tidy-up.
        val registered = FtmsQuirkRegistry.all
            .filter { it.isFlagCorrection }
            .flatMap { q -> q.flagWords.map { it to q.packetLength } }
            .toSet()

        assertTrue((0x09FA to 26) in registered)
        assertTrue((0x0BFA to 27) in registered)
        assertTrue(
            "0x19FA/28 is reference-only evidence and must not gate a decoding quirk",
            (0x19FA to 28) !in registered,
        )
    }

    // ── Behaviour ────────────────────────────────────────────────────────────────

    @Test
    fun `a Life Fitness bike packet yields cadence despite its clear presence bit`() {
        var withCadence = 0

        for (case in captureCases()) {
            val fusion = replay(packetOf(case), "LF Indoor Bike")
            val cadence = fusion.latestSample(MetricKind.CADENCE)?.value
            assertNotNull(
                "sequence ${FixtureJson.int(case["sequence"])} must yield cadence",
                cadence,
            )
            assertTrue(
                "sequence ${FixtureJson.int(case["sequence"])} cadence $cadence must be plausible",
                cadence!! in PlausibilityBounds.rangeFor(MetricKind.CADENCE, FusionPersona.RIDE)!!,
            )
            withCadence++
        }

        assertEquals("all 115 captured packets", 115, withCadence)
    }

    @Test
    fun `the same packet from an unnamed device yields no cadence`() {
        // The quirk must not fire on identity FitBridge does not have. Without the name there is
        // no Life Fitness claim, so the flag word is believed as sent — cadence absent, and the
        // remaining fields decoded at the offsets the machine's own flags imply.
        val case = captureCases().first()

        assertNull(replay(packetOf(case), null).latestSample(MetricKind.CADENCE))
        assertNull(replay(packetOf(case), "Wahoo KICKR").latestSample(MetricKind.CADENCE))
    }

    @Test
    fun `a matching name with a non-matching packet falls through unchanged`() {
        // UnknownPacketPolicy.FALL_BACK_TO_STANDARD, asserted rather than assumed. A firmware
        // revision that sends a different flag word or length is a different machine as far as
        // this registry is concerned.
        val case = captureCases().first()
        val truncated = packetOf(case).copyOf(24)

        assertNull(
            FtmsQuirkRegistry.find("lf bike", bikeUuid, 0x09FA, 24),
        )
        assertNull(
            "a length the fingerprint does not name must not be corrected",
            replay(truncated, "LF Indoor Bike").latestSample(MetricKind.CADENCE),
        )
    }

    @Test
    fun `a conforming machine that sets the bit is untouched by the quirk`() {
        // The over-broad-quirk failure mode. A bike that correctly declares cadence has a flag
        // word the fingerprint does not name, so nothing is corrected and nothing is re-read.
        val conforming = 0x09FA or Flags.BIKE_CADENCE

        assertNull(FtmsQuirkRegistry.find("lf bike", bikeUuid, conforming, 26))
    }

    @Test
    fun `a flag correction never replaces the field walk`() {
        // The invariant that keeps this the weaker, safer kind of quirk. Enforced in
        // FtmsQuirk's init block; asserted here so the guarantee is visible in the suite.
        for (quirk in FtmsQuirkRegistry.all) {
            assertTrue(
                "${quirk.id} must be a layout or a correction, never both",
                quirk.layout.isEmpty() != (quirk.forceFlagBits == 0),
            )
        }
    }
}
