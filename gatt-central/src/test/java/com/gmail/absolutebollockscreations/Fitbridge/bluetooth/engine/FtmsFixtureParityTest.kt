package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Plan §5.5 consumer for the generated FTMS corpus: 58 packets from 14 families through
 * FitBridge's six FTMS parsers, held against TrackMyIndoorWorkout's own decoded output.
 *
 * The corpus is generated, not transcribed — `tools/extract_tmiw_vectors.py` plus a scratch
 * Dart emitter run against TMIW at the pinned commit (D39, D40). Every value in `expect` was
 * produced by TMIW executing its decoder.
 *
 * ## Only direct parses are compared
 *
 * TMIW's `RecordWithSport` is a *post-derivation* record with some fields aliased onto
 * unrelated names. FitBridge's `MetricSample` values are what the parser read off the wire.
 * Comparing the two by field name produces a suite that passes while proving very little,
 * which is precisely the failure §5.6 exists to catch. Three concrete cases, all verified
 * against TMIW source rather than assumed:
 *
 *  - **Treadmill `resistance` is Force on Belt.** `TreadmillDeviceDescriptor
 *    .processForceAndPowerFlag` assigns the sint16 newton belt-force field to
 *    `resistanceMetric`. FitBridge emits it as [MetricKind.FORCE_ON_BELT] and emits no
 *    resistance for a treadmill at all. A name-to-name mapping would compare newtons against
 *    resistance levels and report agreement.
 *  - **Rower `speedKmh` is derived, not parsed.** FTMS Rower Data (`0x2AD1`) has no speed
 *    field. TMIW computes speed from pace — `1800 / 52992 = 0.033967`, exactly the value in
 *    `concept2_old_ftms-0`. FitBridge derives rower speed from its distance series instead, so
 *    comparing the two compares derivations, not parsers.
 *  - **`pace` scales differ.** TMIW carries a raw wire-ish value (52992); FitBridge's
 *    [MetricKind.INSTANT_PACE] is minutes per kilometre.
 *
 * Those fields are listed in [QUARANTINE] with their reason and are asserted *not* to be
 * silently forgotten: [quarantine entries are all still present in the corpus] fails if a
 * quarantined field stops appearing, so the exclusions cannot rot into stale noise.
 *
 * Comparing derived values is worth doing — it is a real question about whether FitBridge's
 * derivations agree with TMIW's — but it is a different question from parser parity and is
 * not smuggled in here.
 *
 * ## Plausibility bounds are part of the answer
 *
 * Samples are read back through [MetricFusion], so [PlausibilityBounds] applies. That is
 * deliberate: rejecting an implausible value is FitBridge's observable behaviour, not a test
 * artefact. Where a bound legitimately drops a value TMIW keeps, the case is recorded as a
 * divergence rather than worked around — see [BOUNDS_DIVERGENCES].
 */
class FtmsFixtureParityTest {

    private val corpora = listOf(
        "ftms_bowflex_ic7",
        "ftms_concept2_old_ftms",
        "ftms_cross_trainer",
        "ftms_kayak_pro",
        "ftms_lifepro_flexstride_pro",
        "ftms_matrix_bike",
        "ftms_matrix_treadmill",
        "ftms_mr_captain",
        "ftms_schwinn_ic4",
        "ftms_stages_sb20",
        "ftms_step_climber",
        "ftms_technogym_skillrow",
        "ftms_true_treadmill",
        "ftms_yesoul_s3",
    )

    /** TMIW field -> why it is not compared. Keys are checked against the corpus below. */
    private val QUARANTINE = mapOf(
        "pace" to "TMIW's scale is not minutes per kilometre; conversion unresolved",
        "strokeCount" to
            "TMIW folds strokes, strides and revolutions into one field; FitBridge separates " +
            "STROKE_COUNT from TOTAL_STRIDES per persona",
        "preciseCadence" to "duplicate of cadence at higher precision; cadence is compared",
        "caloriesPerMinute" to
            "FitBridge folds per-minute into a single ENERGY_RATE in kcal/h; per-hour is compared",
        "sport" to "descriptor metadata, not a decoded metric",
    )

    /**
     * Fields quarantined only for a specific characteristic, where the same name means
     * different things per persona.
     */
    private val QUARANTINE_BY_CHARACTERISTIC = mapOf(
        "2ad1" to mapOf(
            "speedKmh" to "rower speed is derived from pace by TMIW; FTMS 0x2AD1 has no speed field",
            "cadence" to
                "on a rower TMIW's cadence is the int-truncated stroke rate (101 for 101.5); " +
                "preciseCadence carries the exact half-unit value and is compared instead",
        ),
        "2acd" to mapOf(
            "resistance" to "TMIW aliases Force on Belt (sint16 N) onto resistance; FitBridge emits FORCE_ON_BELT",
        ),
    )

    /** Fields quarantined globally but meaningful for one characteristic. */
    private val UNQUARANTINE_BY_CHARACTERISTIC = mapOf(
        "2ad1" to setOf("preciseCadence"),
    )

    /**
     * Fields FitBridge decodes correctly and TMIW simply does not record, so `expect` is null
     * against a real FitBridge value. Not a parser disagreement — an absence in the reference.
     */
    private val REFERENCE_OMISSIONS = mapOf(
        "inclination" to
            "TMIW does not populate inclination for these personas — the Matrix override lists " +
            "it as present-but-ignored and steps over the octets. FitBridge decodes it per the " +
            "0x2ACD/0x2ACE specification (sint16, exponent -1).",
    )

    /**
     * Cases where FitBridge's plausibility bounds legitimately reject a value TMIW records.
     * Keyed by TMIW field. Each is a deliberate divergence under §11b, not a tolerance.
     */
    private val BOUNDS_DIVERGENCES = mapOf(
        "heartRate" to "0 bpm is outside the 1..254 window; a zero HR is absence, not a reading",
        "preciseCadence" to
            "octet 2 of concept2_old_ftms-0 is 203, so 101.5 spm. STROKE_RATE is bounded at 60 " +
            "and no rower strokes at 101 spm; §11b rejecting it is the bound working.",
    )

    /**
     * Families that are *expected* to disagree until Phase 4c implements their quirk.
     *
     * Empty since 4c landed the Mr Captain layout override. It previously held
     * `ftms.mr_captain`, which reorders and resizes the interior of the expended-energy block,
     * drops average pace despite its flag, and carries a one-octet broken elapsed field — none
     * of it recoverable by a conforming walk. It now parses through
     * [FtmsQuirkRegistry] and is held to full parity below.
     *
     * Kept rather than deleted because Phase 4c still owes CardioStrong IB50 and the Life
     * Fitness cadence, and either may arrive with corpus vectors before its quirk.
     */
    private val PENDING_QUIRK = emptySet<String>()

    /**
     * Device names for families whose parsing depends on a Phase 4c fingerprint.
     *
     * Only supplied where a quirk needs it. Everything else parses with a null name, which
     * disables quirk matching entirely — so a registry entry cannot quietly start affecting
     * families it was never evidenced against.
     */
    private val DEVICE_NAME_BY_FAMILY = mapOf(
        "ftms.mr_captain" to "XG-Rower",
    )

    private fun load(name: String): Map<String, Any?> {
        val stream = javaClass.classLoader?.getResourceAsStream("fixtures/tmiw/$name.json")
        assertNotNull("fixture $name.json is missing from test resources", stream)
        val text = stream!!.bufferedReader().use { it.readText() }
        return FixtureJson.obj(FixtureJson.parse(text))
    }

    private fun packetOf(case: Map<String, Any?>): ByteArray =
        FixtureJson.array(case["packet"]).map { FixtureJson.int(it).toByte() }.toByteArray()

    private fun personaFor(sport: String): FusionPersona = when (sport) {
        "Ride" -> FusionPersona.RIDE
        "Run", "Elliptical", "StairStepper", "StairClimber" -> FusionPersona.RUN
        "Rowing", "Kayaking" -> FusionPersona.ROW_PADDLE
        else -> error("unmapped sport '$sport'")
    }

    private fun characteristicUuid(short: String): UUID =
        UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")

    private fun parse(
        short: String,
        emitter: MetricEmitter,
        packet: ByteArray,
        deviceName: String? = null,
    ) {
        when (short) {
            "2acd" -> BtEngineFtmsParsers.doTreadmill(emitter, packet)
            "2ace" -> BtEngineFtmsParsers.doElliptical(emitter, packet)
            "2acf" -> BtEngineFtmsParsers.doStepper(emitter, packet)
            "2ad0" -> BtEngineFtmsParsers.doClimber(emitter, packet)
            "2ad1" -> BtEngineFtmsParsers.doRower(emitter, packet, deviceName)
            "2ad2" -> BtEngineFtmsParsers.doBike(emitter, packet)
            else -> error("no FitBridge parser for characteristic 0x$short")
        }
    }

    /**
     * TMIW field name -> the FitBridge metric that is the same wire field.
     *
     * [short] matters because TMIW reuses one record field for different wire fields across
     * personas. On a rower (`0x2AD1`) `cadence` carries Stroke Rate — `RowerDeviceDescriptor`
     * assigns `cadence: strokeRate?.toInt()` — which FitBridge emits as
     * [MetricKind.STROKE_RATE]. Mapping it to [MetricKind.CADENCE] compares a populated field
     * against one the rower parser never emits, and reads as a parser failure when it is a
     * naming difference.
     */
    private fun metricFor(field: String, short: String): MetricKind? = when (field) {
        "speedKmh" -> MetricKind.SPEED
        "cadence" -> MetricKind.CADENCE
        "preciseCadence" -> if (short == "2ad1") MetricKind.STROKE_RATE else null
        "distanceM" -> MetricKind.DISTANCE
        "powerW" -> MetricKind.POWER
        "calories" -> MetricKind.ENERGY
        "caloriesPerHour" -> MetricKind.ENERGY_RATE
        "heartRate" -> MetricKind.HEART_RATE
        "elapsedS" -> MetricKind.ELAPSED
        "resistance" -> MetricKind.RESISTANCE
        "inclination" -> MetricKind.INCLINATION
        else -> null
    }

    @Test
    fun `every FTMS corpus decodes as TrackMyIndoorWorkout does, for directly parsed fields`() {
        var casesChecked = 0
        var fieldsChecked = 0
        val mismatches = mutableListOf<String>()

        for (name in corpora) {
            val fixture = load(name)
            val family = FixtureJson.string(fixture["family"])
            if (family in PENDING_QUIRK) continue
            val short = FixtureJson.string(fixture["characteristic"]).substring(4, 8)
            val persona = personaFor(FixtureJson.string(fixture["sport"]))
            val perCharacteristic = QUARANTINE_BY_CHARACTERISTIC[short].orEmpty()

            for (raw in FixtureJson.array(fixture["cases"])) {
                val case = FixtureJson.obj(raw)
                val label = "$family/${FixtureJson.string(case["name"])}"
                val expect = case["expect"] ?: continue

                val state = BtEngineState()
                val fusion = MetricFusion(state)
                fusion.setPersona(persona)
                val emitter = MetricEmitter(
                    fusion = fusion,
                    sourceId = MetricSourceId(
                        ConnectionId("fixture:$family", 1L),
                        MetricProtocol.FTMS,
                        characteristicUuid(short),
                    ),
                    sourceClass = MetricSourceClass.FTMS,
                    arrivalMs = 1_000L,
                )

                parse(short, emitter, packetOf(case), DEVICE_NAME_BY_FAMILY[family])
                casesChecked++

                val unquarantined = UNQUARANTINE_BY_CHARACTERISTIC[short].orEmpty()
                for ((field, reference) in FixtureJson.obj(expect)) {
                    if (field !in unquarantined && (field in QUARANTINE || field in perCharacteristic)) {
                        continue
                    }
                    val metric = metricFor(field, short) ?: continue

                    val actual = fusion.latestSample(metric)?.value
                    val expected = (reference as? Number)?.toDouble()
                    fieldsChecked++

                    if (expected == null && actual == null) continue
                    if (expected != null && actual != null &&
                        kotlin.math.abs(expected - actual) <= 1e-6 * maxOf(1.0, kotlin.math.abs(expected))
                    ) {
                        continue
                    }
                    // TMIW declines to record the field; FitBridge decodes it per spec.
                    if (expected == null && field in REFERENCE_OMISSIONS) continue
                    // §11b bound rejects a value TMIW keeps. Only excused when the bound is
                    // genuinely what dropped it, so a parser regression cannot hide here.
                    if (expected != null && actual == null && field in BOUNDS_DIVERGENCES &&
                        !PlausibilityBounds.isPlausible(metric, persona, expected)
                    ) {
                        continue
                    }
                    mismatches += "$label $field: TMIW=$expected FitBridge=$actual"
                }
            }
        }

        assertTrue(
            "Parser disagreements with TMIW (${mismatches.size}):\n" +
                mismatches.joinToString("\n"),
            mismatches.isEmpty(),
        )
        // Of 58 corpus cases, 7 are truncated packets TMIW refuses outright — covered by the
        // divergence test below — leaving 51. Since Phase 4c, mr_captain parses through its
        // registry override and is held to full parity like every other family.
        //
        // Exact counts rather than lower bounds, so a corpus that silently shrinks fails here
        // instead of quietly testing less.
        assertEquals("cases exercised", 51, casesChecked)
        assertEquals("directly-parsed fields compared", 489, fieldsChecked)
    }

    /**
     * The seven cases TMIW will not decode at all, and what FitBridge does instead.
     *
     * `FitnessMachineDescriptor.isDataProcessable` ends with `dataLength >= byteCounter`: if the
     * flags demand more octets than the packet carries, TMIW rejects the whole packet and
     * records nothing. Five KayakPro and two Concept2 vectors in the pinned corpus are exactly
     * that shape.
     *
     * FitBridge decodes the fields that *are* present and stops at the first field that is not
     * (D38). That is a deliberate divergence and a favourable one — partial data beats none —
     * but only because the abort guarantees nothing past the truncation is invented. Before
     * D38 these packets would have kept walking and produced values from misaligned octets,
     * which is worse than TMIW's refusal, not better.
     *
     * So this test pins both halves: something is decoded, and the fields beyond the
     * truncation point are absent.
     */
    @Test
    fun `truncated packets decode partially where TMIW decodes nothing`() {
        var covered = 0

        for (name in corpora) {
            val fixture = load(name)
            val short = FixtureJson.string(fixture["characteristic"]).substring(4, 8)
            val persona = personaFor(FixtureJson.string(fixture["sport"]))

            for (raw in FixtureJson.array(fixture["cases"])) {
                val case = FixtureJson.obj(raw)
                if (case["expect"] != null) continue
                val label = "${FixtureJson.string(fixture["family"])}/${FixtureJson.string(case["name"])}"
                covered++

                val fusion = MetricFusion(BtEngineState()).also { it.setPersona(persona) }
                parse(short, emitterFor(fusion, short, name, 1_000L), packetOf(case))

                // The leading fields are genuinely present, so something must come out.
                val decoded = MetricKind.values().filter { fusion.latestSample(it) != null }
                assertTrue(
                    "$label: TMIW rejects this packet; FitBridge should still decode the " +
                        "fields ahead of the truncation, but decoded nothing",
                    decoded.isNotEmpty(),
                )

                // Both shapes truncate inside the five-octet expended-energy block, so the
                // rate and everything the walk would have reached after it must be absent.
                assertEquals(
                    "$label: energy rate needs octets the packet does not carry",
                    null,
                    fusion.latestSample(MetricKind.ENERGY_RATE)?.value,
                )
                assertEquals(
                    "$label: elapsed time sits past the truncation point",
                    null,
                    fusion.latestSample(MetricKind.ELAPSED)?.value,
                )
            }
        }

        assertEquals("truncated cases covered", 7, covered)
    }

    /**
     * A quarantine entry that no longer matches anything in the corpus is stale, and a stale
     * exclusion is worse than none: it looks like a considered decision while hiding nothing.
     */
    @Test
    fun `quarantine entries all still appear in the corpus`() {
        val seen = mutableSetOf<String>()
        for (name in corpora) {
            for (raw in FixtureJson.array(load(name)["cases"])) {
                val expect = FixtureJson.obj(raw)["expect"] ?: continue
                seen += FixtureJson.obj(expect).keys
            }
        }
        val stale = QUARANTINE.keys.filterNot { it in seen } +
            QUARANTINE_BY_CHARACTERISTIC.values.flatMap { it.keys }.filterNot { it in seen }
        assertTrue("quarantine entries no longer present in the corpus: $stale", stale.isEmpty())
    }

    /**
     * D37's falsifying criterion. Stages SB20 and Yesoul S3 rotate disjoint metric packets
     * across distinct flag words; TMIW clones a descriptor per flag key to cope. §4b was
     * amended to fixtures-only on the claim that FitBridge needs no equivalent because its
     * parsers hold no cross-packet state.
     *
     * If offsets from one layout leaked into the next, this test would fail and the amendment
     * would be wrong. It is the criterion, not a formality.
     */
    @Test
    fun `rotating flag words do not carry offsets between packets`() {
        for (name in listOf("ftms_stages_sb20", "ftms_yesoul_s3")) {
            val fixture = load(name)
            val short = FixtureJson.string(fixture["characteristic"]).substring(4, 8)
            val persona = personaFor(FixtureJson.string(fixture["sport"]))
            val cases = FixtureJson.array(fixture["cases"]).map { FixtureJson.obj(it) }

            // Decode each packet in a fresh fusion, then again through one shared fusion in
            // corpus order. A stateless walk gives the same per-packet answers either way.
            val isolated = cases.map { case ->
                val fusion = MetricFusion(BtEngineState()).also { it.setPersona(persona) }
                parse(short, emitterFor(fusion, short, name, 1_000L), packetOf(case))
                MetricKind.values().associateWith { fusion.latestSample(it)?.value }
            }

            val shared = MetricFusion(BtEngineState()).also { it.setPersona(persona) }
            cases.forEachIndexed { i, case ->
                // Fresh arrival time per packet so freshness never excludes the newest sample.
                parse(short, emitterFor(shared, short, name, 1_000L + i), packetOf(case))
                val label = "$name/${FixtureJson.string(case["name"])}"
                for ((metric, isolatedValue) in isolated[i]) {
                    if (isolatedValue == null) continue
                    assertEquals(
                        "$label $metric differs when replayed in sequence - a layout leaked",
                        isolatedValue,
                        shared.latestSample(metric)?.value,
                    )
                }
            }
        }
    }

    private fun emitterFor(
        fusion: MetricFusion,
        short: String,
        family: String,
        arrivalMs: Long,
    ) = MetricEmitter(
        fusion = fusion,
        sourceId = MetricSourceId(
            ConnectionId("fixture:$family", 1L),
            MetricProtocol.FTMS,
            characteristicUuid(short),
        ),
        sourceClass = MetricSourceClass.FTMS,
        arrivalMs = arrivalMs,
    )
}
