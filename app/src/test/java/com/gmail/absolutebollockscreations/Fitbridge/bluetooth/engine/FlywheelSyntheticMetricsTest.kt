package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlywheelSyntheticMetricsTest {
    @Test
    fun `power produces estimated speed average speed and integrated distance`() {
        val tracker = FlywheelSyntheticMetrics()
        val reading = FlywheelMeasurement.Reading(powerWatts = 290, cadenceRpm = 90)
        val first = tracker.samples(reading, 1_000L, 80.0)
        val second = tracker.samples(reading, 2_000L, 80.0)
        val speed = first.single { it.metric == MetricKind.SPEED }

        assertTrue(speed.value > 0.0)
        assertEquals(MetricQuality.ESTIMATED, speed.quality)
        assertEquals(MetricSourceClass.DERIVED, speed.sourceClass)
        assertEquals(speed.value, first.single { it.metric == MetricKind.AVERAGE_SPEED }.value, 1e-9)
        assertEquals(
            speed.value / 3.6,
            second.single { it.metric == MetricKind.DISTANCE }.value,
            1e-9,
        )
        assertEquals(
            second.single { it.metric == MetricKind.DISTANCE }.value,
            second.single { it.metric == MetricKind.DISTANCE }.cumulative!!,
            0.0,
        )
    }

    @Test
    fun `stale gap rebases time without fabricating distance`() {
        val tracker = FlywheelSyntheticMetrics()
        val reading = FlywheelMeasurement.Reading(powerWatts = 200, cadenceRpm = 80)
        tracker.samples(reading, 1_000L, 80.0)
        val before = tracker.samples(reading, 2_000L, 80.0)
            .single { it.metric == MetricKind.DISTANCE }.value
        val after = tracker.samples(reading, 5_001L, 80.0)
            .single { it.metric == MetricKind.DISTANCE }.value

        assertEquals(before, after, 0.0)
    }

    @Test
    fun `new tracker starts a fresh generation accumulator`() {
        val reading = FlywheelMeasurement.Reading(powerWatts = 200, cadenceRpm = 80)
        val old = FlywheelSyntheticMetrics()
        old.samples(reading, 1_000L, 80.0)
        assertTrue(old.samples(reading, 2_000L, 80.0).single {
            it.metric == MetricKind.DISTANCE
        }.value > 0.0)

        assertEquals(
            0.0,
            FlywheelSyntheticMetrics().samples(reading, 3_000L, 80.0).single {
                it.metric == MetricKind.DISTANCE
            }.value,
            0.0,
        )
    }
}
