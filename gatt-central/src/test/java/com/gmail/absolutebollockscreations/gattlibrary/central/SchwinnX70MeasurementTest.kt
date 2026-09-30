package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.SchwinnX70Spec

/**
 * Schwinn x70 decoder coverage — plan §7, §11a, §11b.
 *
 * ## What this file can and cannot establish, stated up front
 *
 * The 17-byte parity here is real: eight captured vectors, and the two stateless fields reproduce
 * TMIW's asserted values exactly. **The 14-byte coverage is not parity with anything.** No vector
 * exists, so those tests pin FitBridge's own arithmetic against itself — they would pass just as
 * cheerfully if every offset were wrong. They are here to make a correction after the Explore the
 * World session a single edit, not to suggest the values are verified. D72 ships them knowingly.
 *
 * The division of labour matches §5.1: captured vectors and their expectations live in
 * `fixtures/tmiw/schwinn_x70.json`; truncation, guards and saturation are FitBridge-authored and
 * live here.
 */
class SchwinnX70MeasurementTest {

    private fun frame(vararg v: Int) = ByteArray(v.size) { (v[it] and 0xFF).toByte() }

    private fun corpus(): List<Map<String, Any?>> {
        val stream = javaClass.classLoader
            ?.getResourceAsStream("fixtures/tmiw/schwinn_x70.json")
            ?: error("corpus missing")
        val fixture = FixtureJson.obj(FixtureJson.parse(stream.bufferedReader().use { it.readText() }))
        return FixtureJson.array(fixture["cases"]).map { FixtureJson.obj(it) }
    }

    private fun packetOf(case: Map<String, Any?>) =
        FixtureJson.array(case["packet"]).map { FixtureJson.int(it).toByte() }.toByteArray()

    // ── 270 / 570 main frame: parity against the captured corpus ─────────────────

    @Test
    fun `every captured vector decodes to the values TMIW asserts`() {
        // The confirmation that makes the 17-byte layout trustworthy. Time and calories are
        // stateless — they depend on no previous frame — so a single captured packet is enough to
        // check them, which is exactly why they survive vectors that broke everything else.
        val cases = corpus()
        assertEquals("the corpus holds all eight captured vectors", 8, cases.size)

        for (case in cases) {
            val label = FixtureJson.string(case["name"])
            val reading = requireNotNull(SchwinnX70Measurement.parseMain(packetOf(case))) {
                "$label must decode"
            }

            assertEquals("$label cadence counter", FixtureJson.int(case["cadenceEventCounter"]), reading.cadenceEventCounter)
            assertEquals("$label time ticks", FixtureJson.int(case["timeTicks"]), reading.timeTicks)
            assertEquals("$label elapsed seconds", FixtureJson.int(case["elapsedSeconds"]), reading.elapsedSeconds)
            assertEquals("$label elapsed millis", FixtureJson.int(case["elapsedMillis"]), reading.elapsedMillis)
            assertEquals("$label raw calories", FixtureJson.int(case["caloriesRaw"]).toLong(), reading.caloriesRaw)
            assertEquals("$label resistance", FixtureJson.int(case["resistance"]), reading.resistance)
        }
    }

    @Test
    fun `resistance is never below its minimum legal value in captured data`() {
        for (case in corpus()) {
            val reading = requireNotNull(SchwinnX70Measurement.parseMain(packetOf(case)))
            assertTrue(
                "${FixtureJson.string(case["name"])} resistance ${reading.resistance} must be at least 1",
                reading.resistance >= 1,
            )
        }
    }

    @Test
    fun `the corpus carries no cadence, power or speed expectation`() {
        // A guard against a future contributor helpfully "completing" the corpus from TMIW's
        // commented-out assertions. Those assert cadence 0 in all eight cases and power up to
        // 4538 W at 102.6 km/h, which is model output from a broken input rather than observation:
        // the vectors are sparse samples from long sessions and the tick counter wraps every 64
        // seconds, so no valid inter-frame delta exists. Porting them would pin a defect.
        for (case in corpus()) {
            for (forbidden in listOf("cadenceRpm", "powerWatts", "speedKph")) {
                assertFalse(
                    "${FixtureJson.string(case["name"])} must not carry a $forbidden expectation",
                    case.containsKey(forbidden),
                )
            }
        }
    }

    // ── Calories: D73 unblocks publication on captured evidence ──────────────────

    private fun sessionCorpus(name: String): Pair<Map<String, Any?>, List<Map<String, Any?>>> {
        val stream = javaClass.classLoader
            ?.getResourceAsStream("fixtures/hardware/$name")
            ?: error("$name missing")
        val fixture = FixtureJson.obj(FixtureJson.parse(stream.bufferedReader().use { it.readText() }))
        return fixture to FixtureJson.array(fixture["cases"]).map { FixtureJson.obj(it) }
    }

    @Test
    fun `both captured sessions accumulate energy at a physically coherent rate`() {
        // The evidence D73 rests on. §7 refused to publish these because 2^21 had no provenance and
        // reproducing TMIW's expectations was circular. Two real sessions are not circular.
        val expected = mapOf(
            "schwinn_x70_270_main.json" to 100.0..600.0,
            "schwinn_x70_570u_main.json" to 200.0..800.0,
        )

        for ((name, band) in expected) {
            val (_, cases) = sessionCorpus(name)
            val first = requireNotNull(SchwinnX70Measurement.parseMain(bytesOf(cases.first(), "packet")))
            val last = requireNotNull(SchwinnX70Measurement.parseMain(bytesOf(cases.last(), "packet")))
            val seconds = (FixtureJson.int(cases.last()["tMs"]) - FixtureJson.int(cases.first()["tMs"])) / 1000.0
            val rate = (last.caloriesKcal - first.caloriesKcal) / seconds * 3600.0

            assertTrue("$name rate $rate kcal/h must be a real effort", rate in band)
            assertEquals(
                "$name must survive the publication guard",
                last.caloriesKcal - first.caloriesKcal,
                SchwinnX70Measurement.publishableKcal(first, last, seconds)!!,
                1e-9,
            )
        }
    }

    @Test
    fun `joining a session already in progress does not suppress a correct total`() {
        // The defect the first version of this guard had. It divided the console's *cumulative*
        // total by the observation window, so connecting to a machine seventy kcal into a ride
        // produced an enormous apparent rate through no fault of the divisor, and the guard
        // suppressed a correct value on every mid-session join. The 570u corpus is a mid-session
        // slice and is exactly that case.
        val (_, cases) = sessionCorpus("schwinn_x70_570u_main.json")
        val first = requireNotNull(SchwinnX70Measurement.parseMain(bytesOf(cases.first(), "packet")))
        val last = requireNotNull(SchwinnX70Measurement.parseMain(bytesOf(cases.last(), "packet")))
        val seconds = (FixtureJson.int(cases.last()["tMs"]) - FixtureJson.int(cases.first()["tMs"])) / 1000.0

        assertTrue("this slice starts well into the ride", first.caloriesKcal > 20.0)
        val published = requireNotNull(SchwinnX70Measurement.publishableKcal(first, last, seconds))
        assertTrue("what is published is the session's own energy, not the machine's history", published < 10.0)
    }

    @Test
    fun `an accumulator that goes backwards publishes nothing`() {
        val (_, cases) = sessionCorpus("schwinn_x70_570u_main.json")
        val first = requireNotNull(SchwinnX70Measurement.parseMain(bytesOf(cases.first(), "packet")))
        val last = requireNotNull(SchwinnX70Measurement.parseMain(bytesOf(cases.last(), "packet")))

        assertNull(
            "a counter running backwards is a wrapped or misread field, not a workout",
            SchwinnX70Measurement.publishableKcal(last, first, 60.0),
        )
    }

    @Test
    fun `the accumulator only ever increases in captured data`() {
        // A cumulative total that goes backwards is the shape a wrong field width produces. The
        // top two octets are zero in every captured frame, so this also guards the uint48 read.
        for (name in listOf("schwinn_x70_270_main.json", "schwinn_x70_570u_main.json")) {
            val (_, cases) = sessionCorpus(name)
            var previous = -1L
            for (case in cases) {
                val reading = requireNotNull(SchwinnX70Measurement.parseMain(bytesOf(case, "packet")))
                assertTrue("$name energy must not go backwards", reading.caloriesRaw >= previous)
                previous = reading.caloriesRaw
            }
        }
    }

    @Test
    fun `an implausible rate suppresses the total rather than publishing it`() {
        // `MetricKind.ENERGY` is deliberately unbounded, so nothing downstream would catch a wrong
        // energy figure — the V8 work found exactly that hazard. The guard lives here or nowhere.
        // A doubled divisor, the most likely single mistake, is what this is sized to catch.
        val (_, cases) = sessionCorpus("schwinn_x70_570u_main.json")
        val first = requireNotNull(SchwinnX70Measurement.parseMain(bytesOf(cases.first(), "packet")))
        val last = requireNotNull(SchwinnX70Measurement.parseMain(bytesOf(cases.last(), "packet")))

        // The captured slice is 5.24 kcal over 37.4 s — about 504 kcal/h, comfortably published,
        // and it cannot trip the guard however the window is chosen. That is worth stating rather
        // than working around: **the guard is sized so that real captured data never approaches
        // it**, so provoking it needs a reading no console in evidence has produced.
        assertEquals(
            "the real slice publishes",
            last.caloriesKcal - first.caloriesKcal,
            SchwinnX70Measurement.publishableKcal(first, last, 37.4)!!,
            1e-9,
        )

        // A field read at the wrong offset is the realistic failure: shift the accumulator one
        // octet left and every value is multiplied by 256, which is what this represents.
        val misread = SchwinnX70Measurement.Main(
            cadenceEventCounter = last.cadenceEventCounter,
            timeTicks = last.timeTicks,
            caloriesRaw = last.caloriesRaw * 256,
            resistance = last.resistance,
        )
        assertNull(
            "a 256x scale error must not reach a session history",
            SchwinnX70Measurement.publishableKcal(first, misread, 37.4),
        )

        assertEquals(
            "a window too short to rate-check is published unchecked",
            last.caloriesKcal - first.caloriesKcal,
            SchwinnX70Measurement.publishableKcal(first, last, elapsedSeconds = 5.0)!!,
            1e-9,
        )
    }

    // ── 270 / 570 main frame: adversarial ────────────────────────────────────────

    @Test
    fun `a frame of any length other than 17 is rejected`() {
        val valid = packetOf(corpus().first())
        for (length in 0..24) {
            if (length == SchwinnX70Spec.MAIN_FRAME_LENGTH) continue
            val truncated = ByteArray(length) { index -> if (index < valid.size) valid[index] else 0 }
            assertNull("a $length-octet frame is not a main frame", SchwinnX70Measurement.parseMain(truncated))
        }
    }

    @Test
    fun `each prefix octet is checked independently`() {
        // A check that only tests byte 0 passes a broken parser, so all three are corrupted
        // separately. Byte 0 matters most: under V17 it is the length, and a frame claiming a
        // length it does not have is the one case worth rejecting outright.
        for (position in 0..2) {
            val corrupted = packetOf(corpus().first())
            corrupted[position] = (corrupted[position].toInt() + 1).toByte()
            assertNull(
                "corrupting prefix octet $position must reject the frame",
                SchwinnX70Measurement.parseMain(corrupted),
            )
            assertFalse(SchwinnX70Measurement.isMainFrame(corrupted))
        }
    }

    @Test
    fun `the six-octet calorie accumulator reads its maximum without sign extension`() {
        // A uint48 built from signed Kotlin bytes is the shape that silently produces a negative
        // cumulative total. 2^48-1 must survive as a positive Long.
        val saturated = frame(
            17, 32, 0, 0,
            0xFF, 0xFF,                          // cadence counter
            0, 0,
            0xFF, 0xFF,                          // time
            0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,  // calories
            0xFF,                                // resistance
        )
        val reading = requireNotNull(SchwinnX70Measurement.parseMain(saturated))

        assertEquals(65535, reading.cadenceEventCounter)
        assertEquals(65535, reading.timeTicks)
        assertEquals(281474976710655L, reading.caloriesRaw)
        assertEquals(255, reading.resistance)
        assertTrue("a uint48 must not sign-extend", reading.caloriesRaw > 0)
    }

    @Test
    fun `an all-zero payload behind a valid prefix decodes to zeroes`() {
        // A console sitting idle before anyone mounts it. Processable, every field legitimately 0
        // — including resistance, which the wire may report below its documented minimum. Clamping
        // belongs to the consumer; the decoder reports what arrived.
        val idle = frame(17, 32, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val reading = requireNotNull(SchwinnX70Measurement.parseMain(idle))

        assertEquals(0, reading.cadenceEventCounter)
        assertEquals(0, reading.timeTicks)
        assertEquals(0L, reading.caloriesRaw)
        assertEquals(0, reading.resistance)
    }

    // ── 170 supplemental frame (V16) ─────────────────────────────────────────────

    private fun pairedCorpus(): List<Map<String, Any?>> {
        val stream = javaClass.classLoader
            ?.getResourceAsStream("fixtures/hardware/schwinn_170_paired.json")
            ?: error("170 corpus missing")
        val fixture = FixtureJson.obj(FixtureJson.parse(stream.bufferedReader().use { it.readText() }))
        return FixtureJson.array(fixture["cases"]).map { FixtureJson.obj(it) }
    }

    private fun bytesOf(case: Map<String, Any?>, key: String) =
        FixtureJson.array(case[key]).map { FixtureJson.int(it).toByte() }.toByteArray()

    @Test
    fun `every captured supplemental frame decodes, and V16 was never a conflict`() {
        // V16 registered TMIW's byte-16 heart rate against QZ's byte-18 resistance as a divergence.
        // The capture shows they do not overlap and each is its own tag's value.
        val cases = pairedCorpus()
        assertEquals(300, cases.size)

        val resistances = mutableSetOf<Int>()
        for (case in cases) {
            val reading = requireNotNull(SchwinnX70Measurement.parseSupplemental(bytesOf(case, "supplemental")))
            resistances += reading.resistance
            assertEquals("no strap was paired in this session", 0, reading.heartRateBpm)
        }
        assertTrue("resistance is plausible across the run", resistances.all { it in 1..25 })
    }

    @Test
    fun `every invariant tag is verified, not merely the one TMIW checks`() {
        // QZ dispatches on length alone, so any 20-octet notification would be read as this frame.
        // TMIW checks exactly one octet. Both work on this firmware; neither would survive a
        // console with a different field set, which the tag structure makes a real possibility.
        val valid = bytesOf(pairedCorpus().first(), "supplemental")
        assertTrue(SchwinnX70Measurement.isSupplementalFrame(valid))

        for ((offset, _) in SchwinnX70Measurement.SUPPLEMENTAL_TAGS) {
            val corrupted = valid.copyOf()
            corrupted[offset] = (corrupted[offset].toInt() + 1).toByte()
            assertNull(
                "corrupting the tag at $offset must reject the frame",
                SchwinnX70Measurement.parseSupplemental(corrupted),
            )
        }
        for (length in listOf(0, 14, 17, 19, 21)) {
            assertNull("a $length-octet frame is not supplemental", SchwinnX70Measurement.parseSupplemental(ByteArray(length)))
        }
    }

    // ── 170 sibling frame — V15a, resolved in QZ's favour ────────────────────────

    @Test
    fun `every captured sibling frame yields physically coherent speed and power`() {
        // The evidence that closed V15a. Ranges, and the fact that both fields are zero together
        // and never one without the other, across 300 consecutive paired frames.
        val readings = pairedCorpus().map {
            requireNotNull(SchwinnX70Measurement.parseSibling(bytesOf(it, "sibling")))
        }

        assertTrue("speed stays inside the RIDE bound", readings.all { r ->
            r.speedKph == 0.0 || r.speedKph in PlausibilityBounds.rangeFor(MetricKind.SPEED, FusionPersona.RIDE)!!
        })
        assertTrue("power stays inside the RIDE bound", readings.all { r ->
            r.powerWatts == 0.0 || r.powerWatts in PlausibilityBounds.rangeFor(MetricKind.POWER, FusionPersona.RIDE)!!
        })
        assertTrue("nothing approaches the 655 W ceiling", readings.all { it.powerWatts < 300.0 })
        assertEquals(
            "speed and power are zero together or not at all — they are one measurement",
            0,
            readings.count { (it.speedKph == 0.0) != (it.powerWatts == 0.0) },
        )
    }

    @Test
    fun `power tracks speed as a tight power law, which is why the offsets are believed`() {
        // The strongest single argument that these octets are speed and power rather than two
        // arbitrary pairs: across the run, ln(power)/ln(speed) is nearly constant. That ratio is
        // not the exponent and it shifts with the unit (the field is decoded as mph since
        // 2026-08-19), so the band below is deliberately wide; the unit-invariant statistic is the
        // log-log slope, near 2.2.
        // A flywheel trainer at fixed resistance sits between the rolling exponent 1 and the
        // aerodynamic 3. Two unrelated fields do not produce this.
        val moving = pairedCorpus()
            .mapNotNull { SchwinnX70Measurement.parseSibling(bytesOf(it, "sibling")) }
            .filter { it.speedKph > 1.0 && it.powerWatts > 0.0 }
        assertTrue("the run contains moving samples", moving.size > 100)

        val exponents = moving.map { kotlin.math.ln(it.powerWatts) / kotlin.math.ln(it.speedKph) }
        val mean = exponents.average()
        assertTrue("exponent $mean is between rolling and aerodynamic", mean in 1.2..2.2)
        assertTrue(
            "the fit is tight, not incidental",
            exponents.all { kotlin.math.abs(it - mean) < 0.5 },
        )
    }

    @Test
    fun `a sibling frame with a foreign tag set is rejected rather than misread`() {
        // The hazard the tag structure creates: a console emitting one extra field shifts every
        // offset after it, and a length-only check would read the wrong quantity as power.
        val valid = bytesOf(pairedCorpus().first(), "sibling")
        assertTrue(SchwinnX70Measurement.isSiblingFrame(valid))

        for ((offset, _) in SchwinnX70Measurement.SIBLING_TAGS) {
            val corrupted = valid.copyOf()
            corrupted[offset] = (corrupted[offset].toInt() + 1).toByte()
            assertNull(
                "corrupting the tag at $offset must reject the frame",
                SchwinnX70Measurement.parseSibling(corrupted),
            )
        }
        for (length in listOf(0, 13, 15, 17, 20)) {
            assertNull("a $length-octet frame is not a sibling frame", SchwinnX70Measurement.parseSibling(ByteArray(length)))
        }
    }

    // ── The three shapes must never be confused (plan §7) ────────────────────────

    @Test
    fun `no frame shape parses as another`() {
        val main = packetOf(corpus().first())
        val case = pairedCorpus().first()
        val supplemental = bytesOf(case, "supplemental")
        val sibling = bytesOf(case, "sibling")

        assertNull(SchwinnX70Measurement.parseSupplemental(main))
        assertNull(SchwinnX70Measurement.parseSibling(main))
        assertNull(SchwinnX70Measurement.parseMain(supplemental))
        assertNull(SchwinnX70Measurement.parseSibling(supplemental))
        assertNull(SchwinnX70Measurement.parseMain(sibling))
        assertNull(SchwinnX70Measurement.parseSupplemental(sibling))
    }

    // ── V17: the framing hypothesis ──────────────────────────────────────────────

    @Test
    fun `both known initialization commands are length-prefixed and sum to zero mod 256`() {
        // The arithmetic behind V17, kept executable. TMIW writes the first, QZ the second, and
        // QZ's is the one a physical console was observed accepting. Two independently authored
        // commands satisfying both rules is not coincidence.
        val tmiw = frame(0x05, 0x03, 0xD9, 0x00, 0x1F)
        val qz = frame(0x07, 0x01, 0xD3, 0x00, 0x1F, 0x05, 0x01)

        assertTrue("TMIW's command is well formed", SchwinnX70Measurement.isWellFormedCommand(tmiw))
        assertTrue("QZ's command is well formed", SchwinnX70Measurement.isWellFormedCommand(qz))
        assertEquals(0x100, tmiw.sumOf { it.toInt() and 0xFF })
        assertEquals(0x100, qz.sumOf { it.toInt() and 0xFF })
    }

    @Test
    fun `the framing rule rejects a corrupted command rather than repairing it`() {
        // It validates and never generates. A synthesised command would be a third value for a
        // field where FitBridge has two and no way to choose — the mistake V13 records against the
        // Life Fitness vendor token.
        assertFalse(SchwinnX70Measurement.isWellFormedCommand(frame(0x05, 0x03, 0xD9, 0x00, 0x20)))
        assertFalse("a wrong length prefix fails", SchwinnX70Measurement.isWellFormedCommand(frame(0x06, 0x03, 0xD9, 0x00, 0x1F)))
        assertFalse(SchwinnX70Measurement.isWellFormedCommand(ByteArray(0)))
    }

    @Test
    fun `V17's length prefix holds on the main stream and not on the 170's`() {
        // **Confirmed and bounded by the captures, which is the useful outcome.**
        //
        // Confirmed: every main frame begins 0x11 = 17, and the 570u session's single 5-octet
        // notification begins 0x05. Two frame sizes on one characteristic, both self-describing.
        // TMIW's "magic number 17" is a length field.
        //
        // Falsified for the 170: byte 0 of both its streams is a wrapping sequence counter running
        // 0..255, not 14 or 20. I predicted 0x0E and 0x14 and was wrong. The rule is a property of
        // the main characteristic, not of the family, and generalising it would have produced a
        // frame check that rejects every genuine 170 packet.
        assertTrue(SchwinnX70Measurement.FRAME_LENGTH_IS_FIRST_OCTET)
        for (case in corpus()) {
            val packet = packetOf(case)
            assertEquals(
                "${FixtureJson.string(case["name"])} byte 0 is the frame length",
                packet.size,
                packet[0].toInt() and 0xFF,
            )
        }
        assertEquals(SchwinnX70Spec.MAIN_FRAME_LENGTH, SchwinnX70Spec.MAIN_PREFIX[0])

        val sequences = pairedCorpus().map {
            requireNotNull(SchwinnX70Measurement.parseSibling(bytesOf(it, "sibling"))).sequence
        }
        assertTrue(
            "the 170's first octet counts frames rather than describing length",
            sequences.take(8) == listOf(0, 1, 2, 3, 4, 5, 6, 7),
        )
    }

    @Test
    fun `consecutive captured frames yield a plausible cadence, which TMIW's corpus could not`() {
        // The other thing the user's captures fixed. TMIW's disabled vectors are sparse samples
        // from long sessions and the tick counter wraps every 64 seconds, so no valid inter-frame
        // delta exists — which is why its asserted cadence is 0 and its asserted power reaches
        // 4538 W. With consecutive frames the counter behaves exactly as a crank revolution
        // counter should.
        val stream = javaClass.classLoader
            ?.getResourceAsStream("fixtures/hardware/schwinn_x70_270_main.json")
            ?: error("270 corpus missing")
        val fixture = FixtureJson.obj(FixtureJson.parse(stream.bufferedReader().use { it.readText() }))
        val cases = FixtureJson.array(fixture["cases"]).map { FixtureJson.obj(it) }
        assertEquals("the whole 270 session", 113, cases.size)

        val first = requireNotNull(SchwinnX70Measurement.parseMain(bytesOf(cases.first(), "packet")))
        val last = requireNotNull(SchwinnX70Measurement.parseMain(bytesOf(cases.last(), "packet")))
        val elapsedSeconds =
            (FixtureJson.int(cases.last()["tMs"]) - FixtureJson.int(cases.first()["tMs"])) / 1000.0
        val revolutions = (last.cadenceEventCounter - first.cadenceEventCounter + 65536) % 65536
        val rpm = revolutions / elapsedSeconds * 60.0

        assertTrue("the session is long enough to measure", elapsedSeconds > 15.0)
        assertTrue(
            "cadence over the whole run is $rpm rpm, which must be a real pedalling rate",
            rpm in PlausibilityBounds.rangeFor(MetricKind.CADENCE, FusionPersona.RIDE)!!,
        )
    }
}
