package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Replay of 447 physically captured Life Fitness packets through FitBridge's FTMS parsers.
 *
 * ## What this can and cannot assert
 *
 * The capture corpora carry **no decoded expectations**, deliberately: the only decoder
 * available to produce them would be FitBridge's own, and a golden generated from the thing
 * under test proves nothing. So this is not a parity test and does not pretend to be one.
 * [FtmsFixtureParityTest] and [LifeFitnessFixtureParityTest] hold parity against TMIW's values.
 *
 * What 447 real packets *can* establish is everything that does not require knowing the right
 * answer: that no packet throws, that no flag/length combination walks off the end of a buffer,
 * that every emitted metric is a physically possible value, and that a session's cumulative
 * series behave like a session's. That is a weaker claim than parity and a much stronger one
 * than "the specification says this layout exists".
 *
 * ## Why it is worth doing anyway
 *
 * Phase 4 ran a generated 58-case corpus through these same parsers and found five real defects
 * — the offset-desync walk, treadmill pace at the wrong width *and* the wrong semantics, the
 * `0xFFFF` energy sentinel, the `0x7FFF` sint16 sentinels, and a missing control-response
 * branch. None had been caught by hardware, because no FitBridge-testable machine set the
 * relevant flags.
 *
 * These captures reach two characteristics that have never met an observed packet in this
 * project's history: **Cross Trainer `0x2ACE`** and **Stair Climber `0x2AD0`**. Their §5.4
 * coverage was authored from the specification. This is the first time either parser has been
 * shown a byte a real machine emitted.
 *
 * ## Bounds are the assertion, not a side effect
 *
 * Samples go through [MetricFusion], so [PlausibilityBounds] applies and an implausible value is
 * dropped rather than published. §1.5 names bounds as the thing that actually protects a user
 * for a family nobody can test, and Life Fitness is exactly that family. A parser that
 * mis-scales a field on real data will either produce a value outside its bound — which this
 * test detects as a rejection — or a plausible wrong number, which it cannot detect and only
 * parity can. The distinction is stated rather than glossed.
 */
class LifeFitnessCaptureReplayTest {

    private data class Corpus(
        val fixture: String,
        val characteristic: String,
        val persona: FusionPersona,
        val expectedCases: Int,
    )

    private val corpora = listOf(
        Corpus("life_fitness_issue_462_cross_trainer_capture", "2ace", FusionPersona.RUN, 77),
        Corpus("life_fitness_issue_462_stair_climber_capture", "2ad0", FusionPersona.RUN, 76),
        Corpus("life_fitness_issue_462_indoor_bike_capture", "2ad2", FusionPersona.RIDE, 115),
        Corpus("life_fitness_issue_462_treadmill_capture", "2acd", FusionPersona.RUN, 179),
    )

    private companion object {
        /**
         * Metrics FitBridge computes rather than reads, whose presence depends on another
         * metric's value rather than on the flag word.
         *
         * Both are derived from speed in `MetricFusion` and emitted only when speed is above
         * zero, because a stationary user has no finite minutes-per-kilometre. That is why
         * [PlausibilityBounds] starts pace at 1.0 rather than 0.0, and why these two cannot
         * participate in a per-fingerprint intermittency check.
         */
        val DERIVED_METRICS = setOf(MetricKind.INSTANT_PACE, MetricKind.AVERAGE_PACE)

        /**
         * Each derived pace and the metric it is computed from.
         *
         * They are **different sources**, which is easy to miss and was missed here: a machine
         * standing still mid-session reports instantaneous speed 0 while its session average
         * stays positive, so instantaneous pace disappears and average pace does not.
         */
        val PACE_SOURCES = listOf(
            MetricKind.INSTANT_PACE to MetricKind.SPEED,
            MetricKind.AVERAGE_PACE to MetricKind.AVERAGE_SPEED,
        )
    }

    private fun load(name: String): Map<String, Any?> {
        val stream = javaClass.classLoader?.getResourceAsStream("fixtures/tmiw/$name.json")
        assertNotNull("fixture $name.json is missing from test resources", stream)
        return FixtureJson.obj(FixtureJson.parse(stream!!.bufferedReader().use { it.readText() }))
    }

    private fun cases(name: String) =
        FixtureJson.array(load(name)["cases"]).map(FixtureJson::obj)

    private fun packetOf(case: Map<String, Any?>): ByteArray =
        FixtureJson.array(case["packet"]).map { FixtureJson.int(it).toByte() }.toByteArray()

    private fun parse(short: String, emitter: MetricEmitter, packet: ByteArray) {
        when (short) {
            "2acd" -> BtEngineFtmsParsers.doTreadmill(emitter, packet)
            "2ace" -> BtEngineFtmsParsers.doElliptical(emitter, packet)
            "2ad0" -> BtEngineFtmsParsers.doClimber(emitter, packet)
            "2ad2" -> BtEngineFtmsParsers.doBike(emitter, packet)
            else -> error("no FitBridge parser for characteristic 0x$short")
        }
    }

    /** A fresh fusion per packet, so one packet's samples cannot mask another's absence. */
    private fun replayOne(corpus: Corpus, packet: ByteArray): MetricFusion {
        val state = BtEngineState()
        val fusion = MetricFusion(state).also { it.setPersona(corpus.persona) }
        val emitter = MetricEmitter(
            fusion = fusion,
            sourceId = MetricSourceId(
                ConnectionId("capture:${corpus.fixture}", 1L),
                MetricProtocol.FTMS,
                UUID.fromString("0000${corpus.characteristic}-0000-1000-8000-00805f9b34fb"),
            ),
            sourceClass = MetricSourceClass.FTMS,
            arrivalMs = 1_000L,
        )
        parse(corpus.characteristic, emitter, packet)
        return fusion
    }

    // ── The replay ───────────────────────────────────────────────────────────────

    @Test
    fun `every captured packet parses without throwing`() {
        // The bounds-check obligation, stated against real bytes. A flag word claiming a field
        // the packet is too short to hold is exactly the D38 offset-desync shape, and 447 real
        // packets across six flag words are a better probe for it than any hand-written case.
        var replayed = 0

        for (corpus in corpora) {
            val cases = cases(corpus.fixture)
            assertEquals("${corpus.fixture} case count", corpus.expectedCases, cases.size)

            for (case in cases) {
                val sequence = FixtureJson.int(case["sequence"])
                try {
                    replayOne(corpus, packetOf(case))
                } catch (t: Throwable) {
                    throw AssertionError(
                        "${corpus.fixture} sequence $sequence (${FixtureJson.string(case["flags"])}, " +
                            "${FixtureJson.int(case["length"])} B) threw ${t::class.java.simpleName}: ${t.message}",
                        t,
                    )
                }
                replayed++
            }
        }

        assertEquals("all four personas replayed", 447, replayed)
    }

    @Test
    fun `every captured packet produces at least one metric`() {
        // A parser that silently emits nothing for a real machine's packet is a dead session,
        // and it is the failure mode a non-crash test alone would miss entirely.
        for (corpus in corpora) {
            for (case in cases(corpus.fixture)) {
                val fusion = replayOne(corpus, packetOf(case))
                val produced = MetricKind.values().count { fusion.latestSample(it) != null }
                assertTrue(
                    "${corpus.fixture} sequence ${FixtureJson.int(case["sequence"])} produced no metrics",
                    produced > 0,
                )
            }
        }
    }

    @Test
    fun `no parsed field is intermittent within one flag and length family`() {
        // The §11b obligation against physical data. Bounds are enforced inside MetricFusion, so
        // a rejected sample is simply absent — there is no rejection callback to assert on. The
        // signature of a mis-scaled field is therefore *intermittency*: a metric present for
        // some packets of a fingerprint family and missing from others.
        //
        // **The first version of this test asserted that of every metric, and it was wrong.**
        // It failed on cross trainer `0x27BE`/33, where 49 packets carry INSTANT_PACE and one
        // does not. The cause is not a bounds rejection: `MetricFusion` derives pace from speed
        // and emits it only `if (value > 0.0)`, and exactly one packet in that capture has the
        // machine at rest with speed 0.00 km/h. Absent pace at zero speed is correct — there is
        // no finite minutes-per-kilometre for a stationary user, which is also why the bound
        // starts at 1.0 rather than 0.0.
        //
        // So the premise "for a fixed flag word the field set is fixed" holds only for fields
        // read off the wire. [DERIVED_METRICS] are computed from another metric's *value*, so
        // their presence legitimately varies within a family and they are excluded — narrowly,
        // by name, with the derivation cited, rather than by loosening the assertion for
        // everything.
        for (corpus in corpora) {
            val byFingerprint = cases(corpus.fixture).groupBy {
                FixtureJson.string(it["flags"]) to FixtureJson.int(it["length"])
            }

            for ((fingerprint, group) in byFingerprint) {
                val metricPresence = group.map { case ->
                    val fusion = replayOne(corpus, packetOf(case))
                    MetricKind.values()
                        .filterNot { it in DERIVED_METRICS }
                        .filterTo(mutableSetOf()) { fusion.latestSample(it) != null }
                }
                val everSeen = metricPresence.reduce { a, b -> (a + b).toMutableSet() }
                val alwaysSeen = metricPresence.reduce { a, b -> a.intersect(b).toMutableSet() }

                assertEquals(
                    "${corpus.fixture} $fingerprint: a wire-parsed metric appears intermittently " +
                        "within one flag/length family. For a fixed flag word the field set is " +
                        "fixed, so this means a value was rejected by its plausibility bound — " +
                        "${everSeen - alwaysSeen}",
                    everSeen,
                    alwaysSeen,
                )
            }
        }
    }

    @Test
    fun `each derived pace is gated on its own source speed, not on speed in general`() {
        // Keeps the [DERIVED_METRICS] exclusion honest: it is justified only if each pace really
        // is a function of a speed that can be zero while the packet is otherwise complete.
        //
        // **The first version of this asserted both paces vanish at zero instantaneous speed,
        // and that was wrong.** AVERAGE_PACE is derived from AVERAGE_SPEED, which is a session
        // figure and stays non-zero while the user pauses. The failing packet is a real one: a
        // cross trainer at rest mid-session, instantaneous speed 0.00, average speed still
        // positive. Two gates, two sources — conflating them made the test claim something the
        // machine's own data contradicts.
        //
        // Asserted as a biconditional over every packet, which is stronger than the single-case
        // version and cannot be satisfied by a coincidence.
        for (corpus in corpora) {
            for (case in cases(corpus.fixture)) {
                val fusion = replayOne(corpus, packetOf(case))
                val label = "${corpus.fixture} sequence ${FixtureJson.int(case["sequence"])}"

                for ((pace, source) in PACE_SOURCES) {
                    val sourceValue = fusion.latestSample(source)?.value
                    val paceEmitted = fusion.latestSample(pace) != null

                    // Two independent conditions, and the second was missing from the previous
                    // version of this test. `MetricFusion` emits the derivation when the source
                    // is above zero; `PlausibilityBounds` then drops the result if it falls
                    // outside 1.0–60.0 min/km. Treadmill sequence 173 is the case that exposed
                    // it: 0.8 km/h derives to 75 min/km, which the bound rejects deliberately —
                    // below 1 km/h is a user stepping on or off, not a pace.
                    val bound = PlausibilityBounds.rangeFor(pace, corpus.persona)!!
                    val expected = sourceValue != null &&
                        sourceValue > 0.0 &&
                        (60.0 / sourceValue) in bound

                    assertEquals(
                        "$label: $pace present iff $source > 0 and the derived pace is inside " +
                            "$bound (source=$sourceValue, derived=" +
                            "${sourceValue?.let { if (it > 0) 60.0 / it else null }})",
                        expected,
                        paceEmitted,
                    )
                }
            }
        }
    }

    @Test
    fun `the corpus exercises both pace-suppression paths, so the rule is not vacuous`() {
        // A biconditional that never sees a false case proves nothing. These captures contain
        // both reasons a pace can be absent, which is why they were worth replaying:
        //
        //  - a machine at rest, source speed exactly 0 — no derivation attempted;
        //  - a machine barely moving, source speed 0.8 km/h — derived to 75 min/km and rejected
        //    by the plausibility bound.
        //
        // The second is a genuine bounds rejection on physical data, and finding one was the
        // stated purpose of this replay. That it turned out to be correct behaviour rather than
        // a defect is a result, not a disappointment.
        var atRest = 0
        var belowBound = 0

        for (corpus in corpora) {
            for (case in cases(corpus.fixture)) {
                val speed = replayOne(corpus, packetOf(case)).latestSample(MetricKind.SPEED)?.value
                    ?: continue
                val paceBound = PlausibilityBounds.rangeFor(MetricKind.INSTANT_PACE, corpus.persona)!!
                if (speed == 0.0) atRest++
                else if ((60.0 / speed) !in paceBound) belowBound++
            }
        }

        assertTrue("no packet with the machine at rest", atRest > 0)
        assertTrue("no packet whose derived pace falls outside its bound", belowBound > 0)
    }

    @Test
    fun `the cross trainer and stair climber parsers meet observed packets for the first time`() {
        // Recorded as an assertion rather than a comment because it is the specific claim this
        // corpus adds: before it, 0x2ACE and 0x2AD0 had only specification-authored coverage.
        for (corpus in corpora.filter { it.characteristic in setOf("2ace", "2ad0") }) {
            val cases = cases(corpus.fixture)
            assertTrue("${corpus.fixture} must carry packets", cases.isNotEmpty())

            for (case in cases) {
                val fusion = replayOne(corpus, packetOf(case))
                val speed = fusion.latestSample(MetricKind.SPEED)?.value
                if (speed != null) {
                    assertTrue(
                        "${corpus.fixture} sequence ${FixtureJson.int(case["sequence"])} speed $speed",
                        speed in PlausibilityBounds.rangeFor(MetricKind.SPEED, corpus.persona)!!,
                    )
                }
            }
        }
    }

    @Test
    fun `the capture corpora carry no decoded expectations`() {
        // Guards the boundary that keeps this test honest. If someone later adds an `expect`
        // block to a capture fixture, it can only have come from FitBridge's own decoder, and
        // parity against it would be circular. The extraction note says so; this enforces it.
        for (corpus in corpora) {
            val fixture = load(corpus.fixture)
            assertTrue(
                "${corpus.fixture} must state that it carries no goldens",
                FixtureJson.string(fixture["expectations"]).contains("Raw physical replay corpus"),
            )
            for (case in cases(corpus.fixture)) {
                assertTrue(
                    "${corpus.fixture} sequence ${FixtureJson.int(case["sequence"])} " +
                        "must not carry decoded expectations",
                    case["expect"] == null,
                )
            }
        }
    }
}
