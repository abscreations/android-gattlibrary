package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Concept2 frame → canonical metric mapping, plan §5a.
 *
 * The conversions are the point. Speed is m/s on the wire and km/h canonically; pace is seconds
 * per 500 m on the wire and minutes per kilometre canonically — two factors in opposite
 * directions, and getting one without the other produces a number that looks entirely
 * reasonable. That is exactly the shape of the treadmill-pace defect Phase 4 found, so it is
 * pinned here rather than trusted.
 */
class Concept2EmissionTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { (v[it] and 0xFF).toByte() }

    private fun samplesOf(reading: Concept2Measurement.Reading) =
        concept2Samples(reading).associateBy { it.metric }

    // ── Conversions ──────────────────────────────────────────────────────────────

    @Test
    fun `speed converts from metres per second to kilometres per hour`() {
        val r = Concept2Measurement.additionalStatus1(
            bytes(139, 47, 0, 164, 8, 40, 255, 123, 88, 132, 103, 0, 0, 0, 0, 0, 0),
        )!!
        // 2.212 m/s
        assertEquals(2.212 * 3.6, samplesOf(r)[MetricKind.SPEED]!!.value, 1e-9)
        assertEquals(7.9632, samplesOf(r)[MetricKind.SPEED]!!.value, 1e-9)
    }

    @Test
    fun `pace converts from seconds per 500 metres to minutes per kilometre`() {
        val r = Concept2Measurement.additionalStatus1(
            bytes(139, 47, 0, 164, 8, 40, 255, 123, 88, 132, 103, 0, 0, 0, 0, 0, 0),
        )!!
        // 226.51 s/500 m -> 453.02 s/km -> 7.5503 min/km.
        assertEquals(226.51, r.paceSecondsPer500m!!, 1e-9)
        assertEquals(453.02 / 60.0, samplesOf(r)[MetricKind.INSTANT_PACE]!!.value, 1e-9)
    }

    @Test
    fun `speed and pace are mutually consistent to within the PM's own disagreement`() {
        // A cross-check on both conversions at once, since a wrong factor in either would show
        // up as a large mismatch. It is deliberately loose: the PM computes Speed and Current
        // Pace independently rather than deriving one from the other, so they agree closely but
        // are not exact reciprocals. On the pinned vector, 60/7.9632 = 7.5347 min/km against a
        // reported 7.5503 — about 0.2 %, which is the machine's own smoothing, not a decode
        // error. A missing factor of two or sixty would be off by 100 % or more.
        val r = Concept2Measurement.additionalStatus1(
            bytes(139, 47, 0, 164, 8, 40, 255, 123, 88, 132, 103, 0, 0, 0, 0, 0, 0),
        )!!
        val s = samplesOf(r)
        val speedKph = s[MetricKind.SPEED]!!.value
        val paceMinPerKm = s[MetricKind.INSTANT_PACE]!!.value

        val impliedPace = 60.0 / speedKph
        val relativeError = kotlin.math.abs(impliedPace - paceMinPerKm) / paceMinPerKm
        assertTrue(
            "speed $speedKph km/h implies $impliedPace min/km but pace reads $paceMinPerKm; " +
                "a discrepancy this large means a conversion factor is wrong, not smoothing",
            relativeError < 0.02,
        )
    }

    @Test
    fun `a stopped erg emits no pace rather than an infinity`() {
        val stopped = bytes(139, 47, 0, 0, 0, 0, 255, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val s = samplesOf(Concept2Measurement.additionalStatus1(stopped)!!)

        assertNull("zero seconds per 500 m has no finite pace", s[MetricKind.INSTANT_PACE])
        assertEquals(0.0, s[MetricKind.SPEED]!!.value, 1e-9)
        assertEquals(0.0, s[MetricKind.STROKE_RATE]!!.value, 1e-9)
    }

    // ── Cumulative metrics ───────────────────────────────────────────────────────

    @Test
    fun `distance and energy carry their native cumulative counters`() {
        val gs = Concept2Measurement.generalStatus(
            bytes(0x8B, 0x2F, 0x00, 0xA8, 0x61, 0x00, 1, 0, 1, 1, 2, 0, 0, 0, 0x64, 0, 0, 0, 0),
        )!!
        val distance = samplesOf(gs)[MetricKind.DISTANCE]!!
        assertEquals(2500.0, distance.value, 1e-9)
        assertEquals(
            "fusion needs the raw counter to tell a wrap from a reset",
            2500.0,
            distance.nativeCumulative!!,
            1e-9,
        )

        val s2 = Concept2Measurement.additionalStatus2(
            bytes(245, 46, 0, 0, 19, 0, 12, 0, 132, 103, 19, 0, 12, 0, 0, 0, 0, 179, 0, 0),
        )!!
        val energy = samplesOf(s2)[MetricKind.ENERGY]!!
        assertEquals(12.0, energy.value, 1e-9)
        assertEquals(12.0, energy.nativeCumulative!!, 1e-9)
    }

    @Test
    fun `instantaneous metrics carry no cumulative counter`() {
        val s2 = Concept2Measurement.additionalStatus2(
            bytes(245, 46, 0, 0, 19, 0, 12, 0, 132, 103, 19, 0, 12, 0, 0, 0, 0, 179, 0, 0),
        )!!
        assertNull(samplesOf(s2)[MetricKind.AVERAGE_POWER]!!.nativeCumulative)
    }

    // ── The shared time base ─────────────────────────────────────────────────────

    @Test
    fun `every sample carries the frame's own elapsed time as event time`() {
        val gs = Concept2Measurement.generalStatus(
            bytes(0x8B, 0x2F, 0x00, 0xA8, 0x61, 0x00, 1, 0, 1, 1, 2, 0, 0, 0, 0x64, 0, 0, 0, 0),
        )!!
        // 12171 centiseconds -> 121710 ms.
        for (sample in concept2Samples(gs)) {
            assertEquals(121_710L, sample.eventMs)
        }
    }

    @Test
    fun `frames from different characteristics at the same instant share an event time`() {
        // The §5a improvement over TMIW, expressed as the property that matters: two
        // characteristics reporting the same instant produce samples fusion can align, rather
        // than two arrival times that merely happen to be close.
        val elapsed = bytes(0x8B, 0x2F, 0x00)
        val gs = Concept2Measurement.generalStatus(
            elapsed + bytes(0xA8, 0x61, 0x00, 1, 0, 1, 1, 2, 0, 0, 0, 0x64, 0, 0, 0, 0),
        )!!
        val stroke = Concept2Measurement.additionalStrokeData(
            elapsed + bytes(0xCB, 0x00, 0xBC, 0x02, 0x58, 0x00, 0, 0, 0, 0, 0, 0),
        )!!

        val distanceEvent = samplesOf(gs)[MetricKind.DISTANCE]!!.eventMs
        val powerEvent = samplesOf(stroke)[MetricKind.POWER]!!.eventMs
        assertEquals(distanceEvent, powerEvent)
    }

    // ── Completeness ─────────────────────────────────────────────────────────────

    @Test
    fun `a frame carrying only the elapsed prefix emits nothing`() {
        val prefixOnly = Concept2Measurement.generalStatus(bytes(0x8B, 0x2F, 0x00))!!
        assertTrue(concept2Samples(prefixOnly).isEmpty())
    }

    @Test
    fun `the invalid heart-rate marker never reaches emission`() {
        // Filtered at decode because the specification names 255 invalid, so there is nothing
        // for this layer to guard against — asserted so that stays true.
        val r = Concept2Measurement.additionalStatus1(
            bytes(139, 47, 0, 164, 8, 40, 255, 123, 88, 132, 103, 0, 0, 0, 0, 0, 0),
        )!!
        assertNull(samplesOf(r)[MetricKind.HEART_RATE])

        val withHr = Concept2Measurement.additionalStatus1(
            bytes(139, 47, 0, 164, 8, 40, 142, 123, 88, 132, 103, 0, 0, 0, 0, 0, 0),
        )!!
        assertEquals(142.0, samplesOf(withHr)[MetricKind.HEART_RATE]!!.value, 1e-9)
    }

    @Test
    fun `emitted values sit inside the rower plausibility bounds`() {
        val persona = FusionPersona.ROW_PADDLE
        val r = Concept2Measurement.additionalStatus1(
            bytes(139, 47, 0, 164, 8, 40, 142, 123, 88, 132, 103, 0, 0, 0, 0, 0, 0),
        )!!
        for (sample in concept2Samples(r)) {
            assertTrue(
                "${sample.metric} = ${sample.value} must be plausible for a rower",
                PlausibilityBounds.isPlausible(sample.metric, persona, sample.value),
            )
        }
    }
}
