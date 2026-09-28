package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KayakFirstEmissionTest {
    @Test
    fun `accepted reading maps only definitive native metrics`() {
        val reading = KayakFirstMeasurement.Reading(
            distanceMetres = 152.0,
            speedMetresPerSecond = 3.36,
            cadenceSpm = 99,
            forceNewtons = 103.0,
            elapsedSeconds = 69,
            averageSpeedMetresPerSecond = 2.57,
            averageCadenceSpm = 87,
            averageForceNewtons = 87.0,
            diagnosticStatusRaw = "5",
            fieldCount = 24,
        )

        val samples = kayakFirstSamples(reading)

        assertEquals(
            listOf(
                MetricKind.DISTANCE,
                MetricKind.SPEED,
                MetricKind.AVERAGE_SPEED,
                MetricKind.CADENCE,
                MetricKind.AVERAGE_CADENCE,
                MetricKind.ELAPSED,
            ),
            samples.map(KayakFirstSample::metric),
        )
        listOf(152.0, 12.096, 9.252, 99.0, 87.0, 69.0)
            .zip(samples).forEach { (expected, sample) ->
            assertEquals(expected, sample.value, 1e-9)
        }
        assertEquals(152.0, samples.first().nativeCumulative ?: error("missing native total"), 0.0)
        samples.drop(1).forEach { assertNull(it.nativeCumulative) }
        assertTrue(samples.all { it.sourceClass == MetricSourceClass.VENDOR_NATIVE })
        assertTrue(samples.all { it.quality == MetricQuality.NATIVE })
        assertTrue(samples.none { it.metric == MetricKind.STROKE_RATE })
        assertTrue(samples.none { it.metric == MetricKind.POWER })
        assertTrue(samples.none { it.metric == MetricKind.AVERAGE_POWER })
        assertTrue(samples.none { it.metric == MetricKind.ENERGY })
    }

    @Test
    fun `zero motion is emitted rather than leaving stale movement values`() {
        val reading = KayakFirstMeasurement.Reading(
            distanceMetres = 0.0,
            speedMetresPerSecond = 0.0,
            cadenceSpm = 0,
            forceNewtons = 0.0,
            elapsedSeconds = 0,
            diagnosticStatusRaw = "0",
            fieldCount = 24,
        )

        val samples = kayakFirstSamples(reading)

        assertEquals(6, samples.size)
        assertTrue(samples.all { it.value == 0.0 })
        assertEquals(0.0, samples.first().nativeCumulative ?: error("missing native total"), 0.0)
    }

    @Test
    fun `derived stroke count integrates evidenced cadence over protocol elapsed time`() {
        val counter = KayakFirstDerivedStrokeCounter()
        fun reading(elapsed: Long, cadence: Int) = KayakFirstMeasurement.Reading(
            distanceMetres = 0.0,
            speedMetresPerSecond = 0.0,
            cadenceSpm = cadence,
            forceNewtons = 0.0,
            elapsedSeconds = elapsed,
            diagnosticStatusRaw = "1",
            fieldCount = 24,
        )

        assertEquals(0.0, counter.sample(reading(0, 0), 0).value, 0.0)
        val tenSeconds = counter.sample(reading(10, 60), 10)
        assertEquals(5.0, tenSeconds.value, 0.0)
        assertEquals(MetricKind.STROKE_COUNT, tenSeconds.metric)
        assertEquals(MetricSourceClass.DERIVED, tenSeconds.sourceClass)
        assertEquals(MetricQuality.DERIVED, tenSeconds.quality)
        assertEquals(15.0, counter.sample(reading(20, 60), 10).value, 0.0)

        val held = reading(30, 60).copy(activePaddling = false)
        assertEquals(15.0, counter.sample(held, 10).value, 0.0)

        counter.reset()
        // Reset clears the accumulation and the cadence history. A frame representing no time
        // contributes nothing...
        assertEquals(0.0, counter.sample(reading(100, 90), 0).value, 0.0)
        // ...and the next represented interval integrates from zero again rather than resuming the
        // pre-reset total. The zero-second frame still establishes the cadence reference, so ten
        // seconds at a steady 90 spm is 15 strokes.
        assertEquals(15.0, counter.sample(reading(110, 90), 10).value, 0.0)
    }

    /**
     * Field 7 gates the instantaneous metrics, and the session gate deliberately does not.
     *
     * These look like the same question and are not. `KayakFirstActivityGate` answers "is the
     * session under way", and holds through a five-second gap so a brief pause does not fragment
     * an effort — which is why average power and energy integrate against it. Field 7 answers
     * "are this frame's instantaneous fields live", and the physical console makes that a separate
     * question: on a coast or stop frame it clears field 7 while **holding the previous
     * instantaneous cadence, speed and force verbatim** rather than zeroing them
     * (`s1-max-distance` and `s1-coast-frozen-distance` in the QZ hardware corpus both read
     * cadence 95, speed 2.94, force 90).
     *
     * So publishing speed and cadence through the session gate would emit up to five seconds of
     * stale values as live, and integrating strokes through it would invent strokes from a frozen
     * cadence. The raw bit is the correct, fail-closed test here, and this test exists to stop a
     * future reader from "fixing" the apparent inconsistency.
     */
    @Test
    fun `instantaneous metrics are suppressed on a held frame even mid-session`() {
        val held = KayakFirstMeasurement.Reading(
            distanceMetres = 152.0,
            speedMetresPerSecond = 2.94,
            cadenceSpm = 95,
            forceNewtons = 90.0,
            elapsedSeconds = 69,
            diagnosticStatusRaw = "1",
            fieldCount = 24,
        ).copy(activePaddling = false)

        val metrics = kayakFirstSamples(held).map { it.metric }
        assertTrue(MetricKind.SPEED !in metrics)
        assertTrue(MetricKind.CADENCE !in metrics)
        // Cumulative and average fields stay: they are not invalidated by an inactive frame.
        assertTrue(MetricKind.DISTANCE in metrics)
        assertTrue(MetricKind.ELAPSED in metrics)
        assertTrue(MetricKind.AVERAGE_SPEED in metrics)
        assertTrue(MetricKind.AVERAGE_CADENCE in metrics)

        // And the stroke counter must not integrate a frozen cadence.
        val counter = KayakFirstDerivedStrokeCounter()
        assertEquals(0.0, counter.sample(held, 10).value, 0.0)
    }
}
