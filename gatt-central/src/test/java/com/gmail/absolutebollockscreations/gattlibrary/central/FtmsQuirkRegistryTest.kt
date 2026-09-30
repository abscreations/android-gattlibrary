package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids

/**
 * Phase 4c exit criteria for the FTMS quirk registry.
 *
 * The plan asks for three things per quirk, and all three are here: a **non-conforming**
 * fixture proving the override fires and decodes correctly, a **conforming** fixture proving it
 * does *not* fire on a standard packet, and a **cross-contamination** test proving an unrelated
 * machine is unaffected by the quirk being registered at all.
 *
 * The plan names Schwinn IC4 against the Matrix quirk for that last one. No Matrix quirk exists
 * — plan §12.1 V7 resolved both Matrix layouts as ordinary spec-order frames with an absent
 * tail, decoded correctly by D38 without an override — so the criterion is met against the
 * quirk that does exist. The Schwinn IC4 vector is still the probe, which keeps the spirit: a
 * conforming bike must be untouched by a registry that contains a rower override.
 */
class FtmsQuirkRegistryTest {

    private data class Harness(val fusion: MetricFusion, val emitter: MetricEmitter)

    private fun harness(characteristic: UUID, persona: FusionPersona): Harness {
        val fusion = MetricFusion(BtEngineState()).also { it.setPersona(persona) }
        return Harness(
            fusion,
            MetricEmitter(
                fusion = fusion,
                sourceId = MetricSourceId(
                    ConnectionId("quirk-test", 1L),
                    MetricProtocol.FTMS,
                    characteristic,
                ),
                sourceClass = MetricSourceClass.FTMS,
                arrivalMs = 5_000L,
            ),
        )
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { (v[it] and 0xFF).toByte() }

    private fun MetricFusion.valueOf(metric: MetricKind): Double? = latestSample(metric)?.value

    /** `ftms_mr_captain` case 2, the vector the layout was derived from. */
    private val mrCaptainFrame =
        bytes(60, 11, 48, 2, 0, 10, 0, 0, 209, 0, 94, 1, 22, 0, 1, 0, 2, 2, 8, 0)

    // ── Non-conforming fixture: the override fires and decodes ───────────────────

    @Test
    fun `mr captain layout decodes its reordered energy block and absent average pace`() {
        val h = harness(FtmsUuids.UUID_ROWING_MACHINE_DATA, FusionPersona.ROW_PADDLE)

        BtEngineFtmsParsers.doRower(h.emitter, mrCaptainFrame, deviceName = "XG-Rower-01")

        // Every value here is TMIW's, from the pinned corpus.
        assertEquals(24.0, h.fusion.valueOf(MetricKind.STROKE_RATE)!!, 1e-9)
        assertEquals(2.0, h.fusion.valueOf(MetricKind.STROKE_COUNT)!!, 1e-9)
        assertEquals(10.0, h.fusion.valueOf(MetricKind.DISTANCE)!!, 1e-9)
        assertEquals(350.0, h.fusion.valueOf(MetricKind.POWER)!!, 1e-9)
        assertEquals(1.0, h.fusion.valueOf(MetricKind.ENERGY)!!, 1e-9)
        assertEquals(514.0, h.fusion.valueOf(MetricKind.ENERGY_RATE)!!, 1e-9)

        // Pace: 209 s/500 m -> 418 s/km -> 6.9667 min/km.
        assertEquals(418.0 / 60.0, h.fusion.valueOf(MetricKind.INSTANT_PACE)!!, 1e-9)

        // The broken elapsed octet is stepped over and never published. Octet 18 is 8, which
        // would look like a plausible elapsed time and is exactly why it must not surface.
        assertNull(
            "the one-octet elapsed field is known-broken and must be dropped, not published",
            h.fusion.valueOf(MetricKind.ELAPSED),
        )
    }

    /**
     * Without the override the same octets decode differently — pace and power in particular,
     * because a standard walk consumes two octets for the Average Pace the machine omits.
     *
     * This is the evidence that the quirk is doing something rather than decorating a parse
     * that already worked.
     */
    @Test
    fun `the same frame parsed without the quirk disagrees on power`() {
        val h = harness(FtmsUuids.UUID_ROWING_MACHINE_DATA, FusionPersona.ROW_PADDLE)

        BtEngineFtmsParsers.doRower(h.emitter, mrCaptainFrame, deviceName = null)

        assertTrue(
            "a standard walk of this frame should not reproduce the quirked power reading",
            h.fusion.valueOf(MetricKind.POWER) != 350.0,
        )
    }

    // ── Conforming fixture: the override does not fire ───────────────────────────

    @Test
    fun `a conforming rower packet from the same device is parsed normally`() {
        // Flags 0x0004 (total distance only), 5 octets: a shape the fingerprint rejects on both
        // flag word and length, from a device whose name still matches the prefix.
        val h = harness(FtmsUuids.UUID_ROWING_MACHINE_DATA, FusionPersona.ROW_PADDLE)

        BtEngineFtmsParsers.doRower(
            h.emitter,
            bytes(0x05, 0x00, 0x2C, 0x01, 0x00),
            deviceName = "XG-Rower-01",
        )

        // 300 m via the standard uint24, which is what a non-quirked walk produces.
        assertEquals(300.0, h.fusion.valueOf(MetricKind.DISTANCE)!!, 1e-9)
        assertNull(h.fusion.valueOf(MetricKind.ENERGY_RATE))
    }

    @Test
    fun `the fingerprint requires name, flags and length together`() {
        val uuid = FtmsUuids.UUID_ROWING_MACHINE_DATA

        assertNotNull(FtmsQuirkRegistry.find("XG-Rower", uuid, 0x0B3C, 20))
        assertNull("wrong name", FtmsQuirkRegistry.find("Concept2", uuid, 0x0B3C, 20))
        assertNull("no name at all", FtmsQuirkRegistry.find(null, uuid, 0x0B3C, 20))
        assertNull("wrong flags", FtmsQuirkRegistry.find("XG-Rower", uuid, 0x0B3D, 20))
        assertNull("wrong length", FtmsQuirkRegistry.find("XG-Rower", uuid, 0x0B3C, 19))
        assertNull(
            "wrong characteristic",
            FtmsQuirkRegistry.find("XG-Rower", FtmsUuids.UUID_BIKING_DATA, 0x0B3C, 20),
        )
    }

    // ── Cross-contamination ──────────────────────────────────────────────────────

    /**
     * A Schwinn IC4 Indoor Bike frame from the pinned corpus, decoded while the rower quirk is
     * registered. Plan §4c's cross-contamination criterion.
     */
    @Test
    fun `a schwinn ic4 bike packet is unaffected by the registry`() {
        val h = harness(FtmsUuids.UUID_BIKING_DATA, FusionPersona.RIDE)

        BtEngineFtmsParsers.doBike(h.emitter, bytes(68, 2, 94, 11, 240, 0, 122, 0, 84))

        assertEquals(29.1, h.fusion.valueOf(MetricKind.SPEED)!!, 1e-9)
        assertEquals(120.0, h.fusion.valueOf(MetricKind.CADENCE)!!, 1e-9)
        assertEquals(122.0, h.fusion.valueOf(MetricKind.POWER)!!, 1e-9)
        assertEquals(84.0, h.fusion.valueOf(MetricKind.HEART_RATE)!!, 1e-9)
    }

    // ── §11b bounds, per metric, for the untestable family ───────────────────────

    /**
     * Plan §11b: one bounds test per metric for a family FitBridge cannot test on hardware.
     *
     * Mr Captain is exactly that family — no physical unit, evidence from TMIW's pinned corpus
     * only — and it is also a family whose own firmware is demonstrably unreliable, since its
     * elapsed-time octet is known-broken. Bounds are the only thing standing between a
     * misdecoded quirk layout and a user's history, so each metric the override can emit is
     * checked at both ends of its declared range.
     *
     * Cumulative metrics are deliberately absent from this list: `DISTANCE` and `ENERGY` are
     * unbounded by design, because a session total has no ceiling and dropping one sample
     * corrupts a delta chain. Their protection is the sentinel filtering and the parity corpus,
     * not a range.
     */
    @Test
    fun `every bounded metric the mr captain layout emits rejects an implausible value`() {
        val persona = FusionPersona.ROW_PADDLE
        val bounded = listOf(
            Triple(MetricKind.STROKE_RATE, 30.0, 500.0),
            Triple(MetricKind.POWER, 250.0, 40_000.0),
            Triple(MetricKind.HEART_RATE, 140.0, 900.0),
            Triple(MetricKind.ENERGY_RATE, 600.0, 90_000.0),
            Triple(MetricKind.INSTANT_PACE, 4.0, 10_000.0),
        )

        for ((metric, plausible, implausible) in bounded) {
            assertTrue(
                "$metric should accept $plausible for a rower",
                PlausibilityBounds.isPlausible(metric, persona, plausible),
            )
            assertTrue(
                "$metric must reject $implausible - §11b makes bounds the last line of " +
                    "defence for a family with no hardware behind it",
                !PlausibilityBounds.isPlausible(metric, persona, implausible),
            )
        }
    }

    @Test
    fun `an implausible quirk decode is dropped rather than published`() {
        // The layout is fixed, so a firmware that reordered its fields would present as
        // absurd values rather than as a parse failure. Octet 2 is stroke rate in half-units:
        // 0xFE is 127 spm, which no rower sustains.
        val h = harness(FtmsUuids.UUID_ROWING_MACHINE_DATA, FusionPersona.ROW_PADDLE)

        BtEngineFtmsParsers.doRower(
            h.emitter,
            bytes(60, 11, 0xFE, 2, 0, 10, 0, 0, 209, 0, 94, 1, 22, 0, 1, 0, 2, 2, 8, 0),
            deviceName = "XG-Rower-01",
        )

        assertNull(
            "127 spm is outside the stroke-rate bound and must not reach the session",
            h.fusion.latestSample(MetricKind.STROKE_RATE)?.value,
        )
        // The rest of the frame still decodes: one implausible field does not void the packet.
        assertEquals(350.0, h.fusion.latestSample(MetricKind.POWER)!!.value, 1e-9)
    }

    // ── The wiring contract ──────────────────────────────────────────────────────

    @Test
    fun `every registered quirk targets a characteristic whose parser consults the registry`() {
        // The gap this guards: a quirk can be fully specified, evidenced and unit-tested at the
        // registry level, and still never fire, because only `doRower` and `doBike` call
        // `FtmsQuirkRegistry.find`. `doTreadmill`, `doElliptical`, `doStepper` and `doClimber`
        // decode straight from the flag word.
        //
        // Without this assertion the failure is silent: registry tests pass, the quirk looks
        // shipped, and no packet ever reaches it. With it, the build fails and names the walker
        // that needs wiring.
        for (quirk in FtmsQuirkRegistry.all) {
            assertTrue(
                "${quirk.id} targets ${quirk.characteristic}, whose parser does not call " +
                    "FtmsQuirkRegistry.find. Wire that walker to consult the registry and add " +
                    "the characteristic to CHARACTERISTICS_CONSULTING_REGISTRY, or the quirk " +
                    "will never fire on a real packet.",
                quirk.characteristic in FtmsQuirkRegistry.CHARACTERISTICS_CONSULTING_REGISTRY,
            )
        }
    }

    @Test
    fun `each characteristic claimed to consult the registry demonstrably does`() {
        // The converse, and the reason the set is not merely documentation. An entry added here
        // without wiring its parser would let the assertion above pass while the quirk still
        // never fires — so each claimed characteristic must be shown applying a quirk end to
        // end, through the real parser, on a packet its fingerprint matches.
        val demonstrated = mutableSetOf<UUID>()

        // Rower: Mr Captain's layout replacement. Average pace is flagged but absent, so a
        // standard walk would misread the tail; the quirk firing is what makes power correct.
        val rower = harness(FtmsUuids.UUID_ROWING_MACHINE_DATA, FusionPersona.ROW_PADDLE)
        BtEngineFtmsParsers.doRower(rower.emitter, mrCaptainPacket(), deviceName = "XG-070")
        assertNotNull(
            "the rower walker must consult the registry",
            rower.fusion.latestSample(MetricKind.STROKE_RATE),
        )
        demonstrated += FtmsUuids.UUID_ROWING_MACHINE_DATA

        // Bike: the Life Fitness flag correction. Cadence is absent without it and present with
        // it, from the identical packet — the cleanest possible demonstration that the lookup
        // happened.
        val packet = lifeFitnessBikePacket()
        val without = harness(FtmsUuids.UUID_BIKING_DATA, FusionPersona.RIDE)
        BtEngineFtmsParsers.doBike(without.emitter, packet, deviceName = null)
        assertNull(
            "no quirk without an identity to match",
            without.fusion.latestSample(MetricKind.CADENCE),
        )

        val with = harness(FtmsUuids.UUID_BIKING_DATA, FusionPersona.RIDE)
        BtEngineFtmsParsers.doBike(with.emitter, packet, deviceName = "LF Bike")
        assertNotNull(
            "the bike walker must consult the registry",
            with.fusion.latestSample(MetricKind.CADENCE),
        )
        demonstrated += FtmsUuids.UUID_BIKING_DATA

        assertEquals(
            "every characteristic in CHARACTERISTICS_CONSULTING_REGISTRY must be demonstrated " +
                "here, and nothing may claim to consult the registry without proof",
            FtmsQuirkRegistry.CHARACTERISTICS_CONSULTING_REGISTRY,
            demonstrated,
        )
    }

    /** A captured Life Fitness bike frame: flags 0x09FA, 26 octets, cadence bit clear. */
    private fun lifeFitnessBikePacket(): ByteArray {
        val stream = javaClass.classLoader
            ?.getResourceAsStream("fixtures/tmiw/life_fitness_issue_462_indoor_bike_capture.json")
            ?: error("capture corpus missing")
        val fixture = FixtureJson.obj(
            FixtureJson.parse(stream.bufferedReader().use { it.readText() }),
        )
        val case = FixtureJson.array(fixture["cases"])
            .map(FixtureJson::obj)
            .first { FixtureJson.int(it["length"]) == 26 }
        return FixtureJson.array(case["packet"]).map { FixtureJson.int(it).toByte() }.toByteArray()
    }

    /** A Mr Captain rower frame from the pinned TMIW corpus: flags 0x0B3C, 20 octets. */
    private fun mrCaptainPacket(): ByteArray {
        val stream = javaClass.classLoader
            ?.getResourceAsStream("fixtures/tmiw/ftms_mr_captain.json")
            ?: error("mr captain corpus missing")
        val fixture = FixtureJson.obj(
            FixtureJson.parse(stream.bufferedReader().use { it.readText() }),
        )
        val case = FixtureJson.array(fixture["cases"])
            .map(FixtureJson::obj)
            .first { FixtureJson.array(it["packet"]).size == 20 }
        return FixtureJson.array(case["packet"]).map { FixtureJson.int(it).toByte() }.toByteArray()
    }

    // ── Registry invariants ──────────────────────────────────────────────────────

    @Test
    fun `every registered layout accounts for exactly its declared packet length`() {
        assertTrue("registry should not be empty", FtmsQuirkRegistry.all.isNotEmpty())
        // Scoped to layout quirks since 2026-08-12. A flag correction has no layout — it repairs
        // the flag word and lets the standard walk consume the packet — so "layout octets total
        // the packet length" is not merely false for one, it is meaningless. Its equivalent
        // invariant is that the *corrected flag word* accounts for exactly the packet length,
        // which needs a per-characteristic FTMS field table and is asserted in
        // `LifeFitnessCadenceQuirkTest.flag correction widths reconcile the observed packet
        // lengths`. Both kinds are covered; neither is covered by the other's rule.
        for (quirk in FtmsQuirkRegistry.all.filterNot { it.isFlagCorrection }) {
            assertEquals(
                "${quirk.id}: layout octets must total the fingerprinted packet length, " +
                    "otherwise the override is guessing at the tail",
                quirk.packetLength,
                quirk.declaredWidth,
            )
        }

        // Evidence and fingerprint discipline apply to every quirk regardless of kind — a
        // correction is a smaller claim than a layout, not a lesser-evidenced one.
        for (quirk in FtmsQuirkRegistry.all) {
            assertTrue("${quirk.id}: needs recorded evidence", quirk.evidence.isNotBlank())
            assertTrue("${quirk.id}: name prefix must be lowercase", quirk.namePrefix.none { it.isUpperCase() })
            assertTrue("${quirk.id}: must fingerprint at least one flag word", quirk.flagWords.isNotEmpty())
            assertTrue("${quirk.id}: must fingerprint an exact packet length", quirk.packetLength > 0)
        }
    }
}
