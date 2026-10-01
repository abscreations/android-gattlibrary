package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Plan §11b — a sample outside its declared plausible range is dropped, not published.
 *
 * ### What these tests are actually defending
 *
 * D25 ships families nobody has connected to, and §1.5 says plainly that bounds checking is
 * what protects a user in that arrangement. So the ceilings matter in **both** directions and
 * both are tested here:
 *
 * - a mis-scaled or sentinel value must be rejected, or the whole scheme is decoration;
 * - a real athlete must never be rejected, because a dropped sample is harder to notice than a
 *   wrong one. A wrong number is visible and arguable; a missing one just is not there.
 *
 * The second is the easier failure to ship and the harder one to detect, so every bounded
 * metric below has an "a real session passes" case as well as a rejection case.
 */
class PlausibilityBoundsTest {

    private val link = ConnectionId("AA:00:00:00:00:01", 1)
    private val source = MetricSourceId(link, MetricProtocol.RSC, UUID(0, 1))

    private fun sample(
        metric: MetricKind,
        value: Double,
        sourceClass: MetricSourceClass = MetricSourceClass.RSC,
    ) = MetricSample(
        connectionId = link,
        sourceId = source,
        protocol = MetricProtocol.RSC,
        sourceClass = sourceClass,
        metric = metric,
        arrivalMs = 1_000,
        value = value,
        nativeCumulative = if (metric.cumulative) value else null,
        quality = MetricQuality.NATIVE,
        confidence = 1.0,
    )

    private fun ok(metric: MetricKind, persona: FusionPersona, v: Double) =
        PlausibilityBounds.isPlausible(metric, persona, v)

    // ── the two cases that came from real hardware ───────────────────────────────────────

    @Test
    fun `divergence V4's 921 km per hour is rejected`() {
        // RscMeasurement decodes 0xFFFF speed literally, because 0x2A53 defines no
        // not-available sentinel and inventing one to match TMIW would be the wrong kind of
        // corroboration. That decision was recorded as defensible *only* once something
        // rejected the result. This is that something.
        val speed = 65535 / 256.0 * 3.6
        assertEquals(921.5859375, speed, 1e-9)

        assertFalse("V4 is only defensible if this is dropped", ok(MetricKind.SPEED, FusionPersona.RUN, speed))
        assertFalse(ok(MetricKind.SPEED, FusionPersona.RIDE, speed))
        assertFalse(ok(MetricKind.SPEED, FusionPersona.ROW_PADDLE, speed))
    }

    @Test
    fun `the 115 min per km pace seen on hardware is rejected`() {
        // Session 8af3: 0.52 km/h during a walk-start transient became 115.3 min/km and was
        // published into the user's session.
        assertFalse(ok(MetricKind.INSTANT_PACE, FusionPersona.RUN, 115.3153153153153))
        // A slow but real walk still passes: 6 km/h is 10 min/km.
        assertTrue(ok(MetricKind.INSTANT_PACE, FusionPersona.RUN, 10.0))
        // As does a genuinely slow hike at 4 km/h.
        assertTrue(ok(MetricKind.INSTANT_PACE, FusionPersona.RUN, 15.0))
    }

    // ── speed ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `speed bounds admit every real session and reject scaling errors`() {
        // A sprint averages about 37 km/h over 100 m; nobody exceeds 45 on foot.
        assertTrue("a treadmill sprint interval", ok(MetricKind.SPEED, FusionPersona.RUN, 25.0))
        assertTrue("faster than any human, still admitted", ok(MetricKind.SPEED, FusionPersona.RUN, 44.0))
        assertFalse("a ten-times error on a 10 km/h run", ok(MetricKind.SPEED, FusionPersona.RUN, 100.0))

        // The persona split is the whole point: 60 km/h is absurd on a treadmill and ordinary
        // on a descent.
        assertFalse(ok(MetricKind.SPEED, FusionPersona.RUN, 60.0))
        assertTrue(ok(MetricKind.SPEED, FusionPersona.RIDE, 60.0))
        assertTrue("a fast descent", ok(MetricKind.SPEED, FusionPersona.RIDE, 100.0))
        assertFalse(ok(MetricKind.SPEED, FusionPersona.RIDE, 200.0))

        assertTrue("a racing eight does about 22", ok(MetricKind.SPEED, FusionPersona.ROW_PADDLE, 22.0))
        assertFalse(ok(MetricKind.SPEED, FusionPersona.ROW_PADDLE, 60.0))
    }

    @Test
    fun `average speed is bounded exactly like instantaneous speed`() {
        FusionPersona.entries.forEach { persona ->
            assertEquals(
                PlausibilityBounds.rangeFor(MetricKind.SPEED, persona),
                PlausibilityBounds.rangeFor(MetricKind.AVERAGE_SPEED, persona),
            )
        }
    }

    // ── cadence ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `running cadence admits both step and stride reporting while Q21 is open`() {
        // FitBridge does not yet know which unit a given device means - the Garmin HRM-Pro
        // appears to report strides, the corpus devices steps. Until Q21 closes, the bound
        // must not assume either, so it clears a step-reporting sprinter at ~200 /min.
        assertTrue("stride-reporting, running", ok(MetricKind.CADENCE, FusionPersona.RUN, 83.0))
        assertTrue("step-reporting, running", ok(MetricKind.CADENCE, FusionPersona.RUN, 180.0))
        assertTrue("step-reporting, sprinting", ok(MetricKind.CADENCE, FusionPersona.RUN, 260.0))
        assertFalse(ok(MetricKind.CADENCE, FusionPersona.RUN, 400.0))

        assertTrue("track sprint", ok(MetricKind.CADENCE, FusionPersona.RIDE, 140.0))
        assertFalse(ok(MetricKind.CADENCE, FusionPersona.RIDE, 600.0))
    }

    @Test
    fun `stroke rate is bounded independently of cadence`() {
        assertTrue("a racing rate", ok(MetricKind.STROKE_RATE, FusionPersona.ROW_PADDLE, 36.0))
        assertFalse(ok(MetricKind.STROKE_RATE, FusionPersona.ROW_PADDLE, 300.0))
    }

    // ── power ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `power admits a track sprinter and rejects the case section 1-5 names`() {
        // §1.5 item 5's own example: "a rower does not sustain 3000 W".
        assertTrue("a track sprinter's few-second peak", ok(MetricKind.POWER, FusionPersona.RIDE, 2400.0))
        assertFalse(ok(MetricKind.POWER, FusionPersona.ROW_PADDLE, 3500.0))
        assertFalse("a uint16 sentinel", ok(MetricKind.POWER, FusionPersona.RIDE, 65535.0))
    }

    // ── geometry and effort ──────────────────────────────────────────────────────────────

    @Test
    fun `inclination is signed and bounded both ways`() {
        assertTrue("a treadmill decline", ok(MetricKind.INCLINATION, FusionPersona.RUN, -10.0))
        assertTrue("a steep climb", ok(MetricKind.INCLINATION, FusionPersona.RUN, 30.0))
        assertFalse("a sign or scale error", ok(MetricKind.INCLINATION, FusionPersona.RUN, 300.0))
        assertFalse(ok(MetricKind.INCLINATION, FusionPersona.RUN, -300.0))
    }

    @Test
    fun `heart rate keeps its existing wire bounds and is persona-independent`() {
        FusionPersona.entries.forEach { persona ->
            assertTrue(ok(MetricKind.HEART_RATE, persona, 60.0))
            assertTrue(ok(MetricKind.HEART_RATE, persona, 200.0))
            assertFalse("255 is reserved on the wire", ok(MetricKind.HEART_RATE, persona, 255.0))
        }
    }

    // ── what is deliberately unbounded ───────────────────────────────────────────────────

    @Test
    fun `cumulative metrics are not bounded above`() {
        // Their ceiling depends on session length, not physiology, and dropping one sample
        // corrupts a delta chain rather than merely omitting a reading. If they need
        // protection it belongs in the accumulator as a rate-of-change rule.
        listOf(
            MetricKind.DISTANCE, MetricKind.ENERGY, MetricKind.ELAPSED,
            MetricKind.STROKE_COUNT, MetricKind.TOTAL_STEPS, MetricKind.TOTAL_STRIDES,
            MetricKind.ELEVATION_GAIN_POSITIVE,
        ).forEach { metric ->
            assertNull(
                "$metric must stay unbounded - see the note in PlausibilityBounds",
                PlausibilityBounds.rangeFor(metric, FusionPersona.RUN),
            )
            assertTrue(ok(metric, FusionPersona.RUN, 1_000_000.0))
        }
    }

    @Test
    fun `resistance is a vendor level with no shared meaning and stays unbounded`() {
        assertNull(PlausibilityBounds.rangeFor(MetricKind.RESISTANCE, FusionPersona.RIDE))
    }

    @Test
    fun `every metric is classified deliberately, none by omission`() {
        // A new MetricKind added without a decision here would silently inherit "unbounded".
        // This does not force a bound; it forces the author to have looked.
        val bounded = MetricKind.entries.filter {
            PlausibilityBounds.rangeFor(it, FusionPersona.RUN) != null
        }
        assertTrue("at least the metrics named in §1.5 must be bounded", bounded.size >= 10)
        MetricKind.entries.forEach { metric ->
            FusionPersona.entries.forEach { persona ->
                // The `when` in rangeFor is exhaustive over MetricKind, so reaching here at all
                // proves the metric was enumerated rather than falling through a default.
                PlausibilityBounds.rangeFor(metric, persona)
            }
        }
    }

    @Test
    fun `a bound reads back in a form fit for a rejection log`() {
        assertNotNull(PlausibilityBounds.describe(MetricKind.SPEED, FusionPersona.RUN))
        assertEquals("unbounded", PlausibilityBounds.describe(MetricKind.DISTANCE, FusionPersona.RUN))
    }

    // ── fusion integration ───────────────────────────────────────────────────────────────

    @Test
    fun `an implausible sample is rejected by fusion and never published`() {
        val state = BtEngineState()
        val fusion = MetricFusion(state)
        fusion.setPersona(FusionPersona.RUN, 0)

        val outcome = fusion.submit(sample(MetricKind.SPEED, 921.5859375))

        assertEquals(FusionSubmission.REJECTED_IMPLAUSIBLE, outcome)
        assertNull("no source may be selected from a rejected sample", fusion.selectedSource(MetricKind.SPEED))
        assertTrue("and nothing may reach the series", state.series(MetricSeriesKind.SPEED_KPH).isEmpty())
    }

    @Test
    fun `a plausible sample from the same source still passes`() {
        // The rejection must be about the value, not the source. A device that sends one bad
        // packet keeps working.
        val state = BtEngineState()
        val fusion = MetricFusion(state)
        fusion.setPersona(FusionPersona.RUN, 0)

        fusion.submit(sample(MetricKind.SPEED, 921.5859375))
        val outcome = fusion.submit(sample(MetricKind.SPEED, 11.2).copy(arrivalMs = 2_000))

        assertEquals(FusionSubmission.ACCEPTED, outcome)
        assertEquals(source, fusion.selectedSource(MetricKind.SPEED))
    }

    @Test
    fun `the persona in force at arrival decides, not the metric alone`() {
        val fusion = MetricFusion(BtEngineState())

        fusion.setPersona(FusionPersona.RUN, 0)
        assertEquals(
            FusionSubmission.REJECTED_IMPLAUSIBLE,
            fusion.submit(sample(MetricKind.SPEED, 60.0, MetricSourceClass.FTMS)),
        )

        fusion.setPersona(FusionPersona.RIDE, 1_000)
        assertEquals(
            "60 km/h is ordinary on a bike",
            FusionSubmission.ACCEPTED,
            fusion.submit(sample(MetricKind.SPEED, 60.0, MetricSourceClass.FTMS).copy(arrivalMs = 2_000)),
        )
    }
}
