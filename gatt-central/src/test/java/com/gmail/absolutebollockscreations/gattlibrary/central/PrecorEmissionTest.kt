package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Precor Chrono canonical-metric projection, plan §5b and §11.6.
 *
 * Where [PrecorMeasurementTest] asserts that FitBridge reads the wire the way TMIW does, this
 * asserts the places FitBridge deliberately does *not* behave like TMIW — which is the half of
 * a port that is easy to leave untested because nothing upstream describes it.
 */
class PrecorEmissionTest {

    private fun frame(vararg v: Int) = ByteArray(v.size) { (v[it] and 0xFF).toByte() }

    /** Vector 2: heart rate 77, speed 22.83, cadence 35, distance 7644, cal 124, power 94. */
    private val active =
        frame(83, 89, 22, 248, 4, 77, 235, 8, 94, 1, 220, 29, 0, 124, 0, 103, 0, 94, 0)

    /** Vector 1: identical shape, heart rate 0 — no strap paired. */
    private val noStrap =
        frame(83, 89, 22, 110, 2, 0, 200, 8, 84, 1, 80, 14, 0, 57, 0, 47, 0, 90, 0)

    private fun samplesOf(data: ByteArray) =
        precorSamples(requireNotNull(PrecorMeasurement.parse(data)))

    @Test
    fun `an active frame projects six metrics with correct units`() {
        val byKind = samplesOf(active).associateBy { it.metric }

        assertEquals(6, byKind.size)
        assertEquals(22.83, byKind.getValue(MetricKind.SPEED).value, 1e-9)
        assertEquals(35.0, byKind.getValue(MetricKind.CADENCE).value, 1e-9)
        assertEquals(94.0, byKind.getValue(MetricKind.POWER).value, 1e-9)
        assertEquals(7644.0, byKind.getValue(MetricKind.DISTANCE).value, 1e-9)
        assertEquals(124.0, byKind.getValue(MetricKind.ENERGY).value, 1e-9)
        assertEquals(77.0, byKind.getValue(MetricKind.HEART_RATE).value, 1e-9)
    }

    @Test
    fun `heart rate zero is absence and is not emitted`() {
        val kinds = samplesOf(noStrap).map { it.metric }

        assertFalse(
            "zero means no strap paired, not a heart rate of zero",
            MetricKind.HEART_RATE in kinds,
        )
        // Everything else on the same frame still arrives; suppressing one field must not
        // suppress the packet.
        assertTrue(MetricKind.SPEED in kinds)
        assertTrue(MetricKind.CADENCE in kinds)
        assertEquals(5, kinds.size)
    }

    @Test
    fun `zero speed cadence and power are emitted, because zero is a reading`() {
        // The distinction the heart-rate case turns on. A stationary bike really is doing
        // 0 km/h at 0 rpm; withholding those would leave a stale value fresh in fusion and
        // show a user still moving after they stopped.
        val stationary =
            frame(83, 89, 22, 4, 5, 67, 0, 0, 0, 0, 24, 30, 0, 125, 0, 104, 0, 0, 0)
        val byKind = samplesOf(stationary).associateBy { it.metric }

        assertEquals(0.0, byKind.getValue(MetricKind.SPEED).value, 1e-9)
        assertEquals(0.0, byKind.getValue(MetricKind.CADENCE).value, 1e-9)
        assertEquals(0.0, byKind.getValue(MetricKind.POWER).value, 1e-9)
        assertEquals(67.0, byKind.getValue(MetricKind.HEART_RATE).value, 1e-9)
    }

    @Test
    fun `distance and energy carry a native cumulative value and nothing else does`() {
        for (sample in samplesOf(active)) {
            val expectsCumulative =
                sample.metric == MetricKind.DISTANCE || sample.metric == MetricKind.ENERGY
            assertEquals(
                "${sample.metric} cumulative carriage",
                expectsCumulative,
                sample.nativeCumulative != null,
            )
        }
    }

    @Test
    fun `elapsed time becomes protocol time and is never published as a metric`() {
        val samples = samplesOf(active)

        assertFalse(
            "§8.5 — a machine's elapsed time is not authoritative for a FitBridge session",
            samples.any { it.metric == MetricKind.ELAPSED },
        )
        assertTrue(
            "but every sample from one frame shares that frame's protocol timestamp",
            samples.all { it.eventMs == 1_272_000L },
        )
    }

    @Test
    fun `the unidentified field reaches no metric`() {
        // Vector 2's bytes 15-16 hold 103. Nothing in the projection may carry that value, in
        // any unit, however persuasive the cumulative-work reading is.
        val values = samplesOf(active).map { it.value }
        assertFalse(103.0 in values)
    }

    @Test
    fun `the frame produces no average, pace, resistance or stroke metric`() {
        // TMIW's descriptor sets these to null for this device and its own tests assert that.
        // Asserted here so a later contributor adding a derived average does it deliberately.
        val kinds = samplesOf(active).map { it.metric }.toSet()
        val absent = setOf(
            MetricKind.AVERAGE_SPEED,
            MetricKind.AVERAGE_CADENCE,
            MetricKind.AVERAGE_POWER,
            MetricKind.INSTANT_PACE,
            MetricKind.AVERAGE_PACE,
            MetricKind.ENERGY_RATE,
            MetricKind.RESISTANCE,
            MetricKind.STROKE_COUNT,
            MetricKind.STROKE_RATE,
        )
        assertTrue(
            "unexpected metrics: ${kinds intersect absent}",
            (kinds intersect absent).isEmpty(),
        )
    }
}
