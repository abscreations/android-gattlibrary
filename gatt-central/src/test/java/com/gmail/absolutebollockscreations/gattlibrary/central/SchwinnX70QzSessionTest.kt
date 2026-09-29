package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Schwinn x70 coverage against the QZ debug-log corpus — evidence record
 * `docs/evidence/phase-7/2026-08-18-schwinn-x70-qz-debug-log-corpus.md`.
 *
 * ## What is different about this corpus, and why it earns its own file
 *
 * Every earlier x70 fixture is **one stream at a time**. `schwinn_170_paired.json` holds the
 * supplemental/sibling pair; `schwinn_x70_270_main.json` and `schwinn_x70_570u_main.json` hold main
 * frames. None of them records *which characteristic a frame arrived on relative to the others*,
 * because nRF Connect logs the characteristic you subscribed to and an HCI snoop needs the ATT
 * handle map to say more.
 *
 * QZ writes every notification with its payload **and its characteristic UUID**, so these fixtures
 * are arrival-ordered across all four notify characteristics. That makes a class of assertion
 * possible that no earlier fixture supports: emission order at link-open, stream interleaving, and
 * agreement between fields carried on *different* characteristics.
 *
 * ## It also falsified the model these decoders were written against
 *
 * §7 recorded, as the phase's central fact, that a 170 emits the paired streams and **no 17-byte
 * frame at all** while a 270/570 emits only the 17-byte stream. Both halves are false, and
 * [`a Schwinn 170 emits all three measurement streams on one connection`] pins the correction so it
 * cannot quietly regress. The decoders themselves needed no change — every structural rule in
 * [SchwinnX70Measurement] holds on this corpus, which is checked here explicitly rather than
 * assumed.
 *
 * ## What these tests still cannot establish
 *
 * FitBridge owns no Schwinn hardware. This is third-party physical evidence and caps at the same
 * tier as the corpora it joins. Two of the five slices come from one console and three from
 * another; a rule that held on both is a rule observed twice, not a specification.
 */
class SchwinnX70QzSessionTest {

    // ── Corpus loading ───────────────────────────────────────────────────────────

    /** One arrival-ordered notification: relative milliseconds, characteristic, payload. */
    private class Record(val tMs: Int, val channel: String, val packet: ByteArray)

    private fun slice(name: String): List<Record> {
        val stream = javaClass.classLoader
            ?.getResourceAsStream("fixtures/hardware/$name")
            ?: error("$name missing")
        val fixture = FixtureJson.obj(FixtureJson.parse(stream.bufferedReader().use { it.readText() }))
        return FixtureJson.array(fixture["cases"]).map { FixtureJson.obj(it) }.map { case ->
            Record(
                tMs = FixtureJson.int(case["tMs"]),
                channel = FixtureJson.string(case["ch"]),
                packet = FixtureJson.array(case["packet"])
                    .map { FixtureJson.int(it).toByte() }.toByteArray(),
            )
        }
    }

    private fun List<Record>.on(channel: String, length: Int? = null) =
        filter { it.channel == channel && (length == null || it.packet.size == length) }

    private val linkOpen170 by lazy { slice("schwinn_x70_170_qz_link_open.json") }
    private val interleaved170 by lazy { slice("schwinn_x70_170_qz_interleaved.json") }
    private val sweep170 by lazy { slice("schwinn_x70_170_qz_resistance_sweep.json") }
    private val interleaved270 by lazy { slice("schwinn_x70_270_qz_interleaved.json") }
    private val heartRate270 by lazy { slice("schwinn_x70_270_qz_heart_rate.json") }

    private val allSlices by lazy {
        mapOf(
            "170 link open" to linkOpen170,
            "170 interleaved" to interleaved170,
            "170 resistance sweep" to sweep170,
            "270 interleaved" to interleaved270,
            "270 heart rate" to heartRate270,
        )
    }

    // ── The falsification, pinned ────────────────────────────────────────────────

    @Test
    fun `a Schwinn 170 emits all three measurement streams on one connection`() {
        // The claim this replaces: "A **170** emits paired 20-byte and 14-byte notifications ... and
        // **no 17-byte stream at all**" (SchwinnX70Measurement KDoc, and §7's central fact).
        //
        // The console behind this slice is the same physical machine as every other 170 artefact in
        // the project — the address in the source log matches the advertisement decoded in the
        // 2026-08-14 evidence record and the device in the Android bugreport from the same issue.
        // The QZ session contains one connection, so these frames cannot come from two machines.
        //
        // The earlier conclusion was a collection artefact: the HCI trace did not carry
        // 5c7d82a0 traffic, and absence in one capture was recorded as absence in the device.
        val main = interleaved170.on("main", 17)
        val supplemental = interleaved170.on("supplemental", 20)
        val sibling = interleaved170.on("sibling", 14)

        assertTrue("a 170 emits 17-byte main frames: found ${main.size}", main.size > 100)
        assertTrue("and still emits the pair", supplemental.size > 100 && sibling.size > 100)

        // Not merely present — structurally valid main frames, so this is not misrouted traffic.
        for (record in main) {
            assertNotNull(
                "a 170's main frame at ${record.tMs} ms must parse as one",
                SchwinnX70Measurement.parseMain(record.packet),
            )
        }
    }

    @Test
    fun `a 270 emits the paired streams the model assigned exclusively to a 170`() {
        // The other half of the same false split, on a second and physically distinct console.
        val supplemental = interleaved270.on("supplemental", 20)
        val sibling = interleaved270.on("sibling", 14)
        assertTrue("a 270 emits the pair", supplemental.size > 100 && sibling.size > 100)
        assertEquals("one supplemental per sibling", supplemental.size, sibling.size)

        for (record in supplemental) {
            assertNotNull(SchwinnX70Measurement.parseSupplemental(record.packet))
        }
        for (record in sibling) {
            assertNotNull(SchwinnX70Measurement.parseSibling(record.packet))
        }
    }

    @Test
    fun `the streams interleave rather than following one another`() {
        // Coexistence in a frame count is weaker than coexistence in arrival order. If the main
        // stream ran before or after the pair — a reconnection, a mode change — the counts would
        // look identical and the conclusion would be wrong.
        for ((label, records) in listOf("170" to interleaved170, "270" to interleaved270)) {
            val letters = records.map {
                when {
                    it.channel == "main" && it.packet.size == 17 -> 'M'
                    it.channel == "supplemental" -> 'S'
                    it.channel == "sibling" -> 'B'
                    else -> '.'
                }
            }
            val transitions = letters.zipWithNext().count { (a, b) ->
                (a == 'M' && (b == 'S' || b == 'B')) || ((a == 'S' || a == 'B') && b == 'M')
            }
            assertTrue("$label: the main stream must interleave with the pair, not bracket it", transitions > 50)

            val mainTimes = records.filter { it.channel == "main" && it.packet.size == 17 }.map { it.tMs }
            val pairTimes = records.filter { it.channel == "supplemental" }.map { it.tMs }
            val overlapMs = minOf(mainTimes.last(), pairTimes.last()) - maxOf(mainTimes.first(), pairTimes.first())
            assertTrue("$label: the two streams must be concurrent for a real span", overlapMs > 10_000)
        }
    }

    // ── Cross-characteristic agreement: the same-device argument ─────────────────

    /** Pairs each supplemental frame with the nearest main frame inside [windowMs]. */
    private fun matchResistance(records: List<Record>, windowMs: Int = 100): List<Pair<Int, Int>> {
        val main = records.on("main", 17)
        return records.on("supplemental", 20).mapNotNull { supplemental ->
            main.minByOrNull { abs(it.tMs - supplemental.tMs) }
                ?.takeIf { abs(it.tMs - supplemental.tMs) < windowMs }
                ?.let { (it.packet[16].toInt() and 0xFF) to (supplemental.packet[18].toInt() and 0xFF) }
        }
    }

    @Test
    fun `main byte 16 and supplemental byte 18 are the same field on the same scale`() {
        // **This is what makes the coexistence finding safe.** Two machines cannot track each
        // other's resistance across a session; one machine reporting the same setting on two
        // characteristics can, and does.
        //
        // On the 270 the agreement is exact. On the 170 it is not quite, and the exception is the
        // informative part: every disagreement is an *adjacent* value captured while the setting
        // was changing, where the two streams updated tens of milliseconds apart. An off-by-one
        // during a transition is two views of one field; an unrelated value would not be adjacent.
        val exact = matchResistance(interleaved270) + matchResistance(heartRate270)
        assertTrue("the 270 slices must contribute a real sample", exact.size > 400)
        assertEquals(
            "on a 270 the two fields never disagree",
            emptyList<Pair<Int, Int>>(),
            exact.filter { it.first != it.second },
        )

        val approximate = matchResistance(interleaved170) + matchResistance(sweep170)
        assertTrue("the 170 slices must contribute a real sample", approximate.size > 250)
        val disagreements = approximate.filter { it.first != it.second }
        assertTrue(
            "the 170's fields agree on all but a handful: ${disagreements.size} of ${approximate.size}",
            disagreements.size * 100 <= approximate.size * 2,
        )
        for ((mainValue, supplementalValue) in disagreements) {
            assertEquals(
                "a disagreement must be an adjacent value mid-change, not an unrelated one",
                1,
                abs(mainValue - supplementalValue),
            )
        }

        // The window matters and the reason is worth stating. Widen it past ~150 ms on the 170 and
        // frames get matched across a resistance change from an era when the main stream had only
        // just started, which produces a disagreement of 3 that says nothing about the fields. The
        // claim is that these are one field, not that two independently-scheduled streams are
        // instantaneously synchronised.
    }

    @Test
    fun `the paired streams share one wrapping sequence counter, on a second console`() {
        // Derived from the 170 fixture and previously a property of one firmware. It holds on a
        // physically different console, which is what makes it a protocol property and what forces
        // any simulator to emit the pair as one act rather than two schedulers.
        for ((label, records) in allSlices) {
            val supplemental = records.on("supplemental", 20)
            val sibling = records.on("sibling", 14)
            if (supplemental.isEmpty()) continue

            val matched = supplemental.count { s ->
                sibling.any { b -> abs(b.tMs - s.tMs) < 200 && b.packet[0] == s.packet[0] }
            }
            // A slice boundary can cut a pair in half, so the last frame is allowed to be unmatched.
            assertTrue(
                "$label: $matched of ${supplemental.size} supplemental frames pair by sequence",
                matched >= supplemental.size - 1,
            )
        }
    }

    // ── Structural rules re-verified on an independent corpus ────────────────────

    @Test
    fun `every captured frame passes its own structural check and no other`() {
        // Parity for the rules in SchwinnX70Measurement against captures neither TMIW nor QZ was
        // used to derive them from — and, in the same pass, the guarantee §7 actually depends on:
        // dispatch is by characteristic, and a frame from one stream must not parse as another.
        var main = 0
        var supplemental = 0
        var sibling = 0
        for ((label, records) in allSlices) {
            for (record in records) {
                when {
                    record.channel == "main" && record.packet.size == 17 -> {
                        assertNotNull("$label main @${record.tMs}", SchwinnX70Measurement.parseMain(record.packet))
                        assertNull(SchwinnX70Measurement.parseSupplemental(record.packet))
                        assertNull(SchwinnX70Measurement.parseSibling(record.packet))
                        main++
                    }
                    record.channel == "supplemental" -> {
                        assertNotNull("$label supp @${record.tMs}", SchwinnX70Measurement.parseSupplemental(record.packet))
                        assertNull(SchwinnX70Measurement.parseMain(record.packet))
                        assertNull(SchwinnX70Measurement.parseSibling(record.packet))
                        supplemental++
                    }
                    record.channel == "sibling" -> {
                        assertNotNull("$label sib @${record.tMs}", SchwinnX70Measurement.parseSibling(record.packet))
                        assertNull(SchwinnX70Measurement.parseMain(record.packet))
                        assertNull(SchwinnX70Measurement.parseSupplemental(record.packet))
                        sibling++
                    }
                }
            }
        }
        assertTrue("the corpus must be large enough to mean something", main > 800 && supplemental > 1000)
        assertEquals("the pair is emitted one for one across every slice", supplemental, sibling)
    }

    @Test
    fun `the console clock advances at 1024 ticks per second`() {
        // TICKS_PER_SECOND was taken from TMIW. Checked here against arrival times on two consoles.
        // Intervals are allowed to disagree: a notification's arrival time is not its emission
        // time, and the counter wraps every 64 seconds. The claim is about the rate, so it is
        // stated as one.
        for ((label, records) in listOf("170" to interleaved170, "270" to heartRate270)) {
            val main = records.on("main", 17)
            var agreed = 0
            var tested = 0
            for ((a, b) in main.zipWithNext()) {
                val seconds = (b.tMs - a.tMs) / 1000.0
                if (seconds <= 0.0 || seconds > 2.0) continue
                val ticks = (readU16(b.packet, 8) - readU16(a.packet, 8) + 65536) % 65536
                tested++
                if (abs(ticks / SchwinnX70Measurement.TICKS_PER_SECOND - seconds) < 0.05) agreed++
            }
            assertTrue("$label: too few usable intervals", tested > 50)
            assertTrue(
                "$label: the clock ran at 1024/s on $agreed of $tested intervals",
                agreed >= tested * 9 / 10,
            )
        }
    }

    // ── Link open: emission order is captured, not chosen ────────────────────────

    @Test
    fun `the link opens with the aux frame, then the five-octet marker, then the pair`() {
        // Both single-connection sessions open identically. A simulator that emits measurement
        // frames first is not reproducing this family, and a central that assumes its first
        // notification is a measurement is wrong on hardware.
        for ((label, records) in listOf("170" to linkOpen170, "270" to interleaved270)) {
            val first = records.first()
            assertEquals("$label: the aux characteristic notifies first", "aux35", first.channel)
            assertEquals(
                "$label: and its payload is the invariant 01 1f 00 00",
                listOf(0x01, 0x1F, 0x00, 0x00),
                first.packet.map { it.toInt() and 0xFF },
            )

            val firstMain = records.first { it.channel == "main" }
            assertEquals("$label: the first main-characteristic frame is the 5-octet marker", 5, firstMain.packet.size)
            assertEquals("$label: whose byte 0 is its own length", 5, firstMain.packet[0].toInt() and 0xFF)
        }
    }

    @Test
    fun `the aux frame is invariant across two consoles and decodes as nothing`() {
        // 35ddd0a0 is absent from BluetoothIds and from every parser. Recorded here so the constant
        // arriving later has a test already waiting, and so nobody mistakes silence for absence.
        val aux = allSlices.values.flatMap { it.on("aux35") }
        assertTrue("the corpus must contain the aux frame", aux.isNotEmpty())
        for (record in aux) {
            assertEquals(4, record.packet.size)
            assertEquals(listOf(0x01, 0x1F, 0x00, 0x00), record.packet.map { it.toInt() and 0xFF })
            assertNull(SchwinnX70Measurement.parseMain(record.packet))
            assertNull(SchwinnX70Measurement.parseSupplemental(record.packet))
            assertNull(SchwinnX70Measurement.parseSibling(record.packet))
        }
    }

    @Test
    fun `the five-octet marker is recognised as a frame rather than counted as corruption`() {
        // 05 09 01 01 00 and 05 09 01 03 00 arrive on the measurement characteristic, self-describe
        // their length in byte 0, and appear in four of the five captured sessions — the first main
        // frame of the link in both single-connection ones.
        //
        // **They used to be counted as corruption.** parseMain requires exactly 17 octets, so
        // mainSamples routed them to rejected(), and rejectedFrames — which feeds the §11 evidence
        // record and diagnostic capture — was wrong on every x70 session that produced a main
        // stream. isStreamMarker fixed that on 2026-08-18. This test is the regression.
        val markers = allSlices.values.flatMap { it.on("main", 5) }
        assertTrue("the marker appears across the corpus", markers.size >= 4)

        val variants = markers.map { it.packet.map { b -> b.toInt() and 0xFF } }.toSet()
        assertTrue(
            "byte 3 varies, so this is a field and not a constant",
            variants.map { it[3] }.toSet().size > 1,
        )

        val handler = SchwinnX70ProtocolHandler()
        for (marker in markers) {
            assertTrue("every captured marker is recognised", SchwinnX70Measurement.isStreamMarker(marker.packet))
            assertEquals(
                "recognised is not decoded: no metric is produced",
                emptyList<SchwinnX70Sample>(),
                handler.samplesForNotification(BluetoothIds.UUID_X70_MEASUREMENT, marker.packet, arrivalMs = 0L),
            )
        }
        assertEquals("and none of them is corruption", 0, handler.rejectedFrameCount())
        assertEquals("they are counted as what they are", markers.size, handler.streamMarkerCount())
    }

    @Test
    fun `recognising the marker does not become dispatch by length`() {
        // The whole point of §7's characteristic-keyed dispatch is that a payload size is not an
        // identity. A marker predicate that accepted any 5-octet frame would reintroduce QZ's
        // length-only defect at a smaller scale, so it checks the invariant octets too.
        val genuine = allSlices.values.flatMap { it.on("main", 5) }.first().packet
        assertTrue(SchwinnX70Measurement.isStreamMarker(genuine))

        val wrongPrefix = genuine.copyOf().also { it[1] = 0x0A }
        assertFalse("a foreign 5-octet frame is not absorbed", SchwinnX70Measurement.isStreamMarker(wrongPrefix))

        val handler = SchwinnX70ProtocolHandler()
        handler.samplesForNotification(BluetoothIds.UUID_X70_MEASUREMENT, wrongPrefix, arrivalMs = 0L)
        assertEquals("it is rejected, as it should be", 1, handler.rejectedFrameCount())
        assertEquals(0, handler.streamMarkerCount())

        for (length in intArrayOf(0, 4, 6, 17)) {
            assertFalse(
                "length $length is not a marker whatever it contains",
                SchwinnX70Measurement.isStreamMarker(ByteArray(length) { 0x05 }),
            )
        }
    }

    @Test
    fun `a console whose energy accumulator goes backwards gets a new baseline`() {
        // The captured 170 session's first main frame carries a total higher than its second — the
        // console reporting a stale value, or resetting, as the workout begins.
        //
        // publishableKcal refusing a negative delta protects that moment and not the session: a
        // baseline stuck on a total the session never had under-reports for the rest of the link,
        // and MetricKind.ENERGY is unbounded so nothing downstream would notice. The handler now
        // re-establishes the baseline instead.
        val handler = SchwinnX70ProtocolHandler()
        val main = interleaved170.on("main", 17)

        val totals = main.map { requireNotNull(SchwinnX70Measurement.parseMain(it.packet)).caloriesRaw }
        assertTrue("the corpus must contain the decrease this test exists for", totals[1] < totals[0])

        for (record in main) {
            handler.samplesForNotification(
                BluetoothIds.UUID_X70_MEASUREMENT,
                record.packet,
                arrivalMs = record.tMs.toLong(),
            )
        }

        assertEquals("exactly one re-baseline, at the start", 1, handler.energyBaselineResetCount())
        assertEquals("and no frame was treated as corruption", 0, handler.rejectedFrameCount())

        // The published total must be measured from the value the session actually started at, not
        // from the stale opening frame. Anything else silently loses the difference.
        val rebaselined = requireNotNull(SchwinnX70Measurement.parseMain(main[1].packet))
        val last = requireNotNull(SchwinnX70Measurement.parseMain(main.last().packet))
        val elapsed = (main.last().tMs - main[1].tMs) / 1000.0
        val expected = requireNotNull(SchwinnX70Measurement.publishableKcal(rebaselined, last, elapsed))
        val stale = requireNotNull(SchwinnX70Measurement.parseMain(main.first().packet))
        val understated = requireNotNull(SchwinnX70Measurement.publishableKcal(stale, last, elapsed))
        assertTrue("the stale baseline would have under-reported", understated < expected)
    }

    @Test
    fun `the four undecodable characteristics are registered and deliberately not subscribed`() {
        // Registering them makes the inventory honest; subscribing to them would be the
        // correct-and-unreachable defect §11 obligation 0 exists to catch — a frame delivered to no
        // consumer. The gap between what the family exposes and what FitBridge consumes is the
        // state of knowledge, and this test pins both halves of it.
        assertEquals(
            "the observed inventory is thirteen characteristics",
            13,
            BluetoothIds.X70_OBSERVED_CHARACTERISTICS.size,
        )
        assertEquals(
            "and they are distinct",
            BluetoothIds.X70_OBSERVED_CHARACTERISTICS.size,
            BluetoothIds.X70_OBSERVED_CHARACTERISTICS.toSet().size,
        )

        val everything = mapOf(
            BluetoothIds.UUID_X70_SERVICE to BluetoothIds.X70_OBSERVED_CHARACTERISTICS.toSet(),
        )
        assertEquals(
            "a console offering all thirteen is subscribed to for exactly the three measurement streams",
            listOf(
                BluetoothIds.UUID_X70_MEASUREMENT,
                BluetoothIds.UUID_X70_SUPPLEMENTAL,
                BluetoothIds.UUID_X70_SIBLING,
            ),
            planSchwinnX70Discovery(everything).map { it.characteristicUuid },
        )

        // And nothing routes them, so a frame arriving on one produces no metric and no rejection.
        val handler = SchwinnX70ProtocolHandler()
        val aux = allSlices.values.flatMap { it.on("aux35") }.first()
        assertEquals(
            emptyList<SchwinnX70Sample>(),
            handler.samplesForNotification(BluetoothIds.UUID_X70_AUX_STATUS, aux.packet, arrivalMs = 0L),
        )
        assertEquals("an unrouted characteristic is not corruption", 0, handler.rejectedFrameCount())
    }

    // ── Heart rate: the offset is confirmed, not merely consistent ───────────────

    @Test
    fun `a paired strap reads through supplemental byte 16`() {
        // The longest-standing Phase 7 debt. Byte 16 was zero across all 698 previously captured
        // frames because no strap was paired, so TMIW's offset was *consistent with* the capture
        // rather than established by it, and the 2026-08-14 record asks for a session with a strap.
        //
        // This is that session. What makes it a heart rate and not a coincidence is the shape: one
        // contiguous block of physiological values with zero on both sides — a strap connecting,
        // reading, and dropping. No accumulator, counter or resistance level behaves that way.
        val supplemental = heartRate270.on("supplemental", 20)
        val readings = supplemental.map {
            requireNotNull(SchwinnX70Measurement.parseSupplemental(it.packet)).heartRateBpm
        }

        val nonZero = readings.withIndex().filter { it.value > 0 }
        assertTrue("the strap is present for a real part of the slice", nonZero.size > 100)

        assertEquals(
            "the readings form one contiguous block",
            nonZero.size,
            nonZero.last().index - nonZero.first().index + 1,
        )
        assertTrue("the slice begins before the strap connected", nonZero.first().index > 0)
        assertTrue("and ends after it dropped", nonZero.last().index < readings.lastIndex)

        val values = nonZero.map { it.value }
        assertTrue(
            "every reading is a plausible heart rate: ${values.min()}..${values.max()}",
            values.all { it in 40..220 },
        )
        assertTrue(
            "and a steady effort does not swing wildly",
            values.max() - values.min() <= 10,
        )

        // The tag that owns the field holds on every frame, so this is the value of tag 0x5A and
        // not an offset that happens to look right.
        for (record in supplemental) {
            assertEquals(
                SchwinnX70Measurement.SUPPLEMENTAL_GUARD,
                record.packet[15].toInt() and 0xFF,
            )
        }
    }

    @Test
    fun `emission distinguishes a strap dropping from a rider whose heart stopped`() {
        // samplesFor170 suppresses a zero BPM because zero means "no strap", not a reading of zero.
        // Before this corpus that rule was exercised only against frames where the field was always
        // zero. Here the same session contains both states, so the transition is testable.
        val supplemental = heartRate270.on("supplemental", 20)
            .map { requireNotNull(SchwinnX70Measurement.parseSupplemental(it.packet)) }
        val sibling = heartRate270.on("sibling", 14)
            .map { requireNotNull(SchwinnX70Measurement.parseSibling(it.packet)) }

        val withStrap = supplemental.first { it.heartRateBpm > 0 }
        val withoutStrap = supplemental.first { it.heartRateBpm == 0 }

        val emitted = SchwinnX70Emission.samplesFor170(sibling.first(), withStrap, cadenceRpm = null)
        val suppressed = SchwinnX70Emission.samplesFor170(sibling.first(), withoutStrap, cadenceRpm = null)

        assertEquals(
            "a live strap publishes one native heart-rate sample",
            listOf(MetricQuality.NATIVE),
            emitted.filter { it.metric == MetricKind.HEART_RATE }.map { it.quality },
        )
        assertEquals(
            "absence publishes nothing rather than a zero",
            emptyList<SchwinnX70Sample>(),
            suppressed.filter { it.metric == MetricKind.HEART_RATE },
        )
    }

    // ── Metrics: what the corpus says about the decoders' arithmetic ─────────────

    @Test
    fun `a 170's main stream yields a plausible cadence, which the 170 was said not to carry`() {
        // The 7.4/7.5 decision recorded that "a 170 publishes no cadence at all", reasoning that
        // neither of its streams carries a revolution count and that deriving rpm from packet
        // arrival would be a rate of packets wearing the units of a rate of pedal strokes.
        //
        // The reasoning was right and the premise was wrong: the main stream carries the crank
        // counter, and a 170 emits the main stream. Cadence is available by the ordinary path.
        val main = sweep170.on("main", 17)
            .map { it.tMs to requireNotNull(SchwinnX70Measurement.parseMain(it.packet)) }
        assertTrue("enough frames to measure", main.size > 50)

        val seconds = (main.last().first - main.first().first) / 1000.0
        val revolutions =
            (main.last().second.cadenceEventCounter - main.first().second.cadenceEventCounter + 65536) % 65536
        val rpm = revolutions / seconds * 60.0

        assertTrue("the window is long enough", seconds > 10.0)
        assertTrue(
            "a 170's crank counter gives $rpm rpm, which must be a real pedalling rate",
            rpm in PlausibilityBounds.rangeFor(MetricKind.CADENCE, FusionPersona.RIDE)!!,
        )
    }

    @Test
    fun `V15a reproduces on two consoles neither project used to derive it`() {
        // Speed and power are zero together and never one without the other. That invariant is what
        // resolved V15a in QZ's favour over 698 frames of one console. It is re-checked here across
        // every slice, including a second physical machine.
        var zeroTogether = 0
        var total = 0
        for ((label, records) in allSlices) {
            for (record in records.on("sibling", 14)) {
                val sibling = requireNotNull(SchwinnX70Measurement.parseSibling(record.packet))
                total++
                val speedZero = sibling.speedKph == 0.0
                val powerZero = sibling.powerWatts == 0.0
                assertEquals(
                    "$label @${record.tMs} ms: speed and power are one measurement, not two",
                    speedZero,
                    powerZero,
                )
                if (speedZero) zeroTogether++
            }
        }
        assertTrue("the corpus contains motion", total - zeroTogether > 500)
        assertTrue("and contains stillness, so the invariant is not vacuous", zeroTogether > 50)
    }

    @Test
    fun `power tracks speed as the same tight power law on a second console`() {
        // A tight power law was the strongest single argument that QZ's offsets are
        // real. A flywheel trainer at fixed resistance sits between the rolling exponent 1 and the
        // aerodynamic 3, and two arbitrary octet pairs do not produce a fit that tight. Recomputed
        // on frames from a different machine.
        val exponents = heartRate270.on("sibling", 14)
            .map { requireNotNull(SchwinnX70Measurement.parseSibling(it.packet)) }
            .filter { it.speedKph > 1.0 && it.powerWatts > 1.0 }
            .map { ln(it.powerWatts) / ln(it.speedKph) }

        assertTrue("enough moving samples", exponents.size > 200)
        val mean = exponents.average()
        val sd = sqrt(exponents.sumOf { (it - mean) * (it - mean) } / exponents.size)

        assertTrue("the exponent is $mean, which must sit between rolling and aerodynamic", mean in 1.0..3.0)
        assertTrue("and the fit is tight: sd $sd", sd < 0.2)
    }

    @Test
    fun `the energy accumulator can decrease, and the guard refuses rather than publishing`() {
        // SchwinnX70Measurement documents the accumulator as monotonic. On the 170 session it is
        // not: the first main frame of the link carries a total higher than the second, so the
        // console appears to reset or to report a stale value as the workout begins.
        //
        // Nothing breaks — publishableKcal returns null on a negative delta — but the guard was
        // written for a misread field and this is ordinary console behaviour, so it deserves a
        // captured regression rather than a synthetic one.
        val main = interleaved170.on("main", 17)
            .map { requireNotNull(SchwinnX70Measurement.parseMain(it.packet)) }

        val decreases = main.zipWithNext().count { (a, b) -> b.caloriesRaw < a.caloriesRaw }
        assertEquals("exactly one decrease, at the start of the session", 1, decreases)

        val first = main.first()
        val second = main.first { it.caloriesRaw < first.caloriesRaw }
        assertNull(
            "a negative delta publishes nothing at all",
            SchwinnX70Measurement.publishableKcal(first, second, elapsedSeconds = 30.0),
        )

        // Once the accumulator passes its starting value again the session publishes normally.
        val recovered = main.last()
        assertTrue("the session climbs well past the stale opening value", recovered.caloriesRaw > first.caloriesRaw)
        val published = SchwinnX70Measurement.publishableKcal(first, recovered, elapsedSeconds = 60.0)
        assertNotNull("and then publishes a real total", published)
        assertTrue("which is positive", published!! > 0.0)
    }

    @Test
    fun `the resistance sweep exercises the whole captured range`() {
        // The earlier 270 corpus holds resistance constant at 4 and the 570u at 10, so no fixture
        // moved the field. This slice runs 17 to 22 on the main stream, which is what makes the
        // cross-characteristic agreement test above meaningful rather than a comparison of two
        // constants.
        val levels = sweep170.on("main", 17)
            .map { requireNotNull(SchwinnX70Measurement.parseMain(it.packet)).resistance }
            .toSortedSet()
        assertTrue("the field moves across the slice", levels.size >= 4)
        assertTrue(
            "and never falls below its minimum legal value",
            levels.all { it >= SchwinnX70PowerModel.MINIMUM_RESISTANCE },
        )
    }

    // ── The handler, driven by a real interleaved session ────────────────────────

    @Test
    fun `the handler consumes a real interleaved session without rejecting a measurement frame`() {
        // Everything above tests the pure decoders. This drives SchwinnX70ProtocolHandler's routing
        // with the arrival order a console actually produced — three streams interleaved, the aux
        // frame first, markers mixed in — which no synthetic test constructs and no earlier fixture
        // could supply.
        val handler = SchwinnX70ProtocolHandler()
        val uuids = mapOf(
            "main" to BluetoothIds.UUID_X70_MEASUREMENT,
            "supplemental" to BluetoothIds.UUID_X70_SUPPLEMENTAL,
            "sibling" to BluetoothIds.UUID_X70_SIBLING,
        )
        val produced = mutableMapOf<MetricKind, Int>()
        var markers = 0

        for (record in interleaved270) {
            if (record.channel == "main" && record.packet.size == 5) markers++
            val uuid = uuids[record.channel] ?: continue
            handler.samplesForNotification(uuid, record.packet, arrivalMs = record.tMs.toLong())
                .forEach { produced.merge(it.metric, 1, Int::plus) }
        }

        assertTrue("speed reaches the emitter", (produced[MetricKind.SPEED] ?: 0) > 100)
        assertTrue("power reaches the emitter", (produced[MetricKind.POWER] ?: 0) > 100)
        // Cadence is emitted per crank revolution, not per frame — roughly once a second against a
        // ~10 Hz stream — so its count is an order of magnitude below the others by design.
        assertTrue("cadence is derived across main frames", (produced[MetricKind.CADENCE] ?: 0) > 10)

        // Nothing in a genuine session is corruption. The markers are recognised and counted
        // separately; if either number moves, a real frame is being dropped.
        assertEquals("no frame of a real session is rejected", 0, handler.rejectedFrameCount())
        assertEquals("and the markers are accounted for", markers, handler.streamMarkerCount())
    }

    private fun readU16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}
