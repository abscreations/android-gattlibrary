package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * x70 emission — plan §7, driven from the captured corpora.
 *
 * The point of this file is the **provenance split**: a 170 and a 270 produce a similar metric set
 * by opposite routes, and everything that protects a rider depends on those routes being labelled
 * correctly on the way out.
 */
class SchwinnX70EmissionTest {

    private fun corpus(name: String): List<Map<String, Any?>> {
        val stream = javaClass.classLoader?.getResourceAsStream("fixtures/hardware/$name")
            ?: error("$name missing")
        val fixture = FixtureJson.obj(FixtureJson.parse(stream.bufferedReader().use { it.readText() }))
        return FixtureJson.array(fixture["cases"]).map { FixtureJson.obj(it) }
    }

    private fun bytesOf(case: Map<String, Any?>, key: String) =
        FixtureJson.array(case[key]).map { FixtureJson.int(it).toByte() }.toByteArray()

    // ── 170: native speed and power ──────────────────────────────────────────────

    @Test
    fun `every captured sibling frame emits native speed and power inside their bounds`() {
        val speedBound = PlausibilityBounds.rangeFor(MetricKind.SPEED, FusionPersona.RIDE)!!
        val powerBound = PlausibilityBounds.rangeFor(MetricKind.POWER, FusionPersona.RIDE)!!

        for (case in corpus("schwinn_170_paired.json")) {
            val sibling = requireNotNull(SchwinnX70Measurement.parseSibling(bytesOf(case, "sibling")))
            val supplemental = SchwinnX70Measurement.parseSupplemental(bytesOf(case, "supplemental"))
            val samples = SchwinnX70Emission.samplesFor170(sibling, supplemental, cadenceRpm = 72.0)

            val speed = samples.single { it.metric == MetricKind.SPEED }
            val power = samples.single { it.metric == MetricKind.POWER }

            assertEquals("a 170 measures speed", MetricQuality.NATIVE, speed.quality)
            assertEquals("a 170 measures power", MetricQuality.NATIVE, power.quality)
            assertTrue(speed.value == 0.0 || speed.value in speedBound)
            assertTrue(power.value == 0.0 || power.value in powerBound)
        }
    }

    @Test
    fun `a zero heart rate is absence rather than a reading of zero`() {
        // All 698 captured frames report 0 because no strap was paired. Emitting it would put a
        // heart rate of zero into fusion, where PlausibilityBounds would reject it as a decoding
        // anomaly and arm a diagnostic capture — for the most ordinary traffic the machine sends.
        val case = corpus("schwinn_170_paired.json").first()
        val sibling = requireNotNull(SchwinnX70Measurement.parseSibling(bytesOf(case, "sibling")))
        val supplemental = requireNotNull(SchwinnX70Measurement.parseSupplemental(bytesOf(case, "supplemental")))

        assertEquals(0, supplemental.heartRateBpm)
        assertFalse(
            SchwinnX70Emission.samplesFor170(sibling, supplemental, null)
                .any { it.metric == MetricKind.HEART_RATE },
        )

        val withStrap = SchwinnX70Measurement.Supplemental(heartRateBpm = 138, resistance = 4)
        assertEquals(
            138.0,
            SchwinnX70Emission.samplesFor170(sibling, withStrap, null)
                .single { it.metric == MetricKind.HEART_RATE }.value,
            1e-9,
        )
    }

    @Test
    fun `the accumulators are decoded and never emitted`() {
        val case = corpus("schwinn_170_paired.json").last()
        val sibling = requireNotNull(SchwinnX70Measurement.parseSibling(bytesOf(case, "sibling")))
        assertTrue("the accumulators really do carry values", sibling.accumulatorB > 0)

        val values = SchwinnX70Emission.samplesFor170(sibling, null, null).map { it.value }
        assertFalse(
            "two co-varying counters with no ground truth are not a distance",
            values.contains(sibling.accumulatorA.toDouble()) ||
                values.contains(sibling.accumulatorB.toDouble()),
        )
    }

    // ── 270: everything modelled, and labelled as such ───────────────────────────

    @Test
    fun `a main frame emits power and speed as estimates, never as measurements`() {
        // The rule D10 exists for. If these ever came out NATIVE, a 270's modelled number would tie
        // with a 170's measured one on quality and the comparator would fall through to source
        // class, where both are the same family.
        val main = requireNotNull(
            SchwinnX70Measurement.parseMain(bytesOf(corpus("schwinn_x70_270_main.json").first(), "packet")),
        )
        val damped = SchwinnX70PowerModel.DampedPower(
            SchwinnX70PowerModel.powerWatts(cadenceRpm = 85, resistance = main.resistance),
        )
        val samples = SchwinnX70Emission.samplesFor270(main, 85.0, damped, sessionEnergyKcal = 12.0)

        assertEquals(MetricQuality.ESTIMATED, samples.single { it.metric == MetricKind.POWER }.quality)
        assertEquals(MetricQuality.ESTIMATED, samples.single { it.metric == MetricKind.SPEED }.quality)
        assertEquals(MetricQuality.DERIVED, samples.single { it.metric == MetricKind.CADENCE }.quality)
        assertEquals(MetricQuality.NATIVE, samples.single { it.metric == MetricKind.ENERGY }.quality)
    }

    @Test
    fun `no cadence means no power and no speed, rather than a modelled zero`() {
        // Before the second frame arrives there is no cadence, and the model's only inputs are
        // cadence and resistance. Substituting a guess for the missing half would produce a number
        // shaped exactly like a measurement.
        val main = requireNotNull(
            SchwinnX70Measurement.parseMain(bytesOf(corpus("schwinn_x70_270_main.json").first(), "packet")),
        )
        val samples = SchwinnX70Emission.samplesFor270(main, cadenceRpm = null, dampedPower = null, sessionEnergyKcal = null)

        assertTrue("a 270 reports nothing until it can", samples.isEmpty())
    }

    @Test
    fun `a suppressed energy total emits no energy sample at all`() {
        val main = requireNotNull(
            SchwinnX70Measurement.parseMain(bytesOf(corpus("schwinn_x70_270_main.json").first(), "packet")),
        )
        val samples = SchwinnX70Emission.samplesFor270(main, 85.0, null, sessionEnergyKcal = null)
        assertFalse(samples.any { it.metric == MetricKind.ENERGY })
    }

    // ── Cadence from the crank counter ───────────────────────────────────────────

    @Test
    fun `cadence across a whole captured main-stream session is a real pedalling rate`() {
        val cases = corpus("schwinn_x70_270_main.json")
        val first = requireNotNull(SchwinnX70Measurement.parseMain(bytesOf(cases.first(), "packet")))
        val last = requireNotNull(SchwinnX70Measurement.parseMain(bytesOf(cases.last(), "packet")))
        val seconds = (FixtureJson.int(cases.last()["tMs"]) - FixtureJson.int(cases.first()["tMs"])) / 1000.0

        val rpm = requireNotNull(
            SchwinnX70Emission.cadenceRpm(first.cadenceEventCounter, last.cadenceEventCounter, seconds),
        )
        assertTrue(
            "$rpm rpm must be plausible",
            rpm in PlausibilityBounds.rangeFor(MetricKind.CADENCE, FusionPersona.RIDE)!!,
        )
    }

    @Test
    fun `cadence declines to answer rather than reporting a false zero`() {
        // Zero is a claim that the rider stopped. Having no information is a different statement,
        // and collapsing the two would publish a stop that never happened every time a frame is
        // dropped or the clock has not advanced.
        assertNull("no elapsed time", SchwinnX70Emission.cadenceRpm(100, 105, 0.0))
        assertNull("negative elapsed time", SchwinnX70Emission.cadenceRpm(100, 105, -1.0))
        assertNull("a backwards counter is not a sprint", SchwinnX70Emission.cadenceRpm(100, 99, 1.0))

        // **A counter that has not moved is now absence until it has been still long enough to
        // mean something (2026-08-18).** Two seconds is an ordinary gap between revolutions at a
        // slow grind, not a stop, and treating it as one is the defect that made 88 % of derived
        // cadence a false zero.
        assertNull("an unchanged counter is not yet a stop", SchwinnX70Emission.cadenceRpm(100, 100, 2.0))

        // Past the threshold it is a stop, and zero is the right thing to say.
        assertEquals(
            0.0,
            SchwinnX70Emission.cadenceRpm(100, 100, SchwinnX70Emission.STOPPED_AFTER_SECONDS)!!,
            1e-9,
        )
        assertEquals(0.0, SchwinnX70Emission.cadenceRpm(100, 100, 30.0)!!, 1e-9)

        // One revolution at 20 rpm takes three seconds, so the threshold must sit past that or a
        // slow rider is reported as stopped mid-pedal-stroke.
        assertTrue(
            "the stop threshold must be slower than the slowest real pedalling",
            SchwinnX70Emission.STOPPED_AFTER_SECONDS > 3.0,
        )
    }

    @Test
    fun `the counter wrap is handled without inventing a burst`() {
        // uint16 wrap during a normal ride: 65530 -> 6 is 12 revolutions, not 65,512.
        val rpm = requireNotNull(SchwinnX70Emission.cadenceRpm(65530, 6, elapsedSeconds = 10.0))
        assertEquals(72.0, rpm, 1e-9)
    }
}
