package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KayakFirstEmissionTest {
    @Test
    fun `accepted reading maps only the five evidenced native metrics`() {
        val reading = KayakFirstMeasurement.Reading(
            distanceMetres = 152.0,
            speedMetresPerSecond = 3.36,
            cadenceSpm = 99,
            powerWatts = 103,
            elapsedSeconds = 69,
            diagnosticStatusRaw = "5",
            fieldCount = 24,
        )

        val samples = kayakFirstSamples(reading)

        assertEquals(
            listOf(
                MetricKind.DISTANCE,
                MetricKind.SPEED,
                MetricKind.CADENCE,
                MetricKind.POWER,
                MetricKind.ELAPSED,
            ),
            samples.map(KayakFirstSample::metric),
        )
        listOf(152.0, 12.096, 99.0, 103.0, 69.0).zip(samples).forEach { (expected, sample) ->
            assertEquals(expected, sample.value, 1e-9)
        }
        assertEquals(152.0, samples.first().nativeCumulative ?: error("missing native total"), 0.0)
        samples.drop(1).forEach { assertNull(it.nativeCumulative) }
        assertTrue(samples.all { it.sourceClass == MetricSourceClass.VENDOR_NATIVE })
        assertTrue(samples.all { it.quality == MetricQuality.NATIVE })
        assertTrue(samples.none { it.metric == MetricKind.STROKE_RATE })
        assertTrue(samples.none { it.metric == MetricKind.ENERGY })
    }

    @Test
    fun `zero motion is emitted rather than leaving stale movement values`() {
        val reading = KayakFirstMeasurement.Reading(
            distanceMetres = 0.0,
            speedMetresPerSecond = 0.0,
            cadenceSpm = 0,
            powerWatts = 0,
            elapsedSeconds = 0,
            diagnosticStatusRaw = "0",
            fieldCount = 24,
        )

        val samples = kayakFirstSamples(reading)

        assertEquals(5, samples.size)
        assertTrue(samples.all { it.value == 0.0 })
        assertEquals(0.0, samples.first().nativeCumulative ?: error("missing native total"), 0.0)
    }
}
