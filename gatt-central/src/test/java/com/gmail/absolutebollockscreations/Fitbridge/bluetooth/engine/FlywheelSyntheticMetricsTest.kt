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
    fun `multi-sample average stays near the integrated instantaneous speeds`() {
        val tracker = FlywheelSyntheticMetrics()
        val arrivals = listOf(1_000L, 2_000L, 3_000L, 4_000L)
        val powers = listOf(100, 200, 300, 400)
        val outputs = arrivals.zip(powers).map { (arrivalMs, powerWatts) ->
            tracker.samples(
                FlywheelMeasurement.Reading(powerWatts, cadenceRpm = 85),
                arrivalMs,
                athleteMassKg = 80.0,
            )
        }
        val integratedSpeeds = outputs.drop(1).map { samples ->
            samples.single { it.metric == MetricKind.SPEED }.value
        }
        val average = outputs.last().single { it.metric == MetricKind.AVERAGE_SPEED }.value

        assertEquals(integratedSpeeds.average(), average, 1e-9)
        assertTrue(average in integratedSpeeds.min()..integratedSpeeds.max())
    }

    @Test
    fun `Run Mode reset starts distance and average speed from a fresh sample`() {
        val handler = FlywheelProtocolHandler()
        val context = ConnectionContext(
            connectionId = ConnectionId("AA:BB:CC:DD:EE:31", 1L),
            address = "AA:BB:CC:DD:EE:31",
            machineConnection = true,
            gatt = null,
            connectionState = kotlinx.coroutines.flow.MutableStateFlow(LinkState.CONNECTED),
            handlers = mutableListOf(handler),
        )
        val reading = FlywheelMeasurement.Reading(powerWatts = 240, cadenceRpm = 88)
        handler.syntheticSamples(reading, 1_000L, 80.0)
        assertTrue(handler.syntheticSamples(reading, 2_000L, 80.0).single {
            it.metric == MetricKind.DISTANCE
        }.value > 0.0)

        resetFlywheelSyntheticSessions(listOf(context))
        val firstRunModeSample = handler.syntheticSamples(reading, 3_000L, 80.0)
        val speed = firstRunModeSample.single { it.metric == MetricKind.SPEED }.value

        assertEquals(0.0, firstRunModeSample.single { it.metric == MetricKind.DISTANCE }.value, 0.0)
        assertEquals(
            speed,
            firstRunModeSample.single { it.metric == MetricKind.AVERAGE_SPEED }.value,
            1e-9,
        )
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
