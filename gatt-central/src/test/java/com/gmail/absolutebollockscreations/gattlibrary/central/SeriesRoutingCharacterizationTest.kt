package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Pins how metrics reach the chart-history series today (LIB-42, step 1 of unit 4d), before the series move
 * into `MeasurementHistory`. Written against the old per-series `StateFlow<List<TimedSample>>` API; LIB-44 (step 3)
 * migrated the reads to `MeasurementHistory` and the same assertions still hold.
 *
 * Deliberately not pinned here, because the history work changed it on purpose: what a session start leaves in the
 * distance series, what clearing the averages does to the average heart-rate zone, and elevation. Those have
 * their own tests in `SessionDataClearTest` and `ElevationSeriesTest`.
 */
class SeriesRoutingCharacterizationTest {
    private val connection = ConnectionId("AA:BB:CC:DD:EE:01", 7L)
    private val characteristic = UUID.randomUUID()
    private var seriesNowMs = 1_785_000_000_000L
    private var arrivalMs = 100L

    private val state = BtEngineState()
    private val fusion = MetricFusion(state, seriesClock = MetricSeriesClock { seriesNowMs })

    private fun submit(metric: MetricKind, value: Double) {
        fusion.submit(
            MetricSample(
                connectionId = connection,
                sourceId = MetricSourceId(connection, MetricProtocol.FTMS, characteristic),
                protocol = MetricProtocol.FTMS,
                sourceClass = MetricSourceClass.FTMS,
                metric = metric,
                arrivalMs = arrivalMs++,
                value = value,
                nativeCumulative = if (metric.cumulative) value else null,
            ),
        )
        seriesNowMs += 1_000L
    }


    @Test fun `each fused metric lands in its own series with the series clock timestamp`() {
        val t0 = seriesNowMs
        submit(MetricKind.SPEED, 12.5)
        submit(MetricKind.CADENCE, 84.0)
        submit(MetricKind.POWER, 211.0)
        submit(MetricKind.RESISTANCE, 8.5)
        submit(MetricKind.INCLINATION, 3.5)
        submit(MetricKind.FORCE_ON_BELT, 42.0)
        submit(MetricKind.DISTANCE, 432.0)

        assertEquals(listOf(t0 to 12.5), state.series(MetricSeriesKind.SPEED_KPH).pairs())
        assertEquals(listOf(t0 + 1_000 to 84.0), state.series(MetricSeriesKind.CADENCE_PER_MIN).pairs())
        assertEquals(listOf(t0 + 2_000 to 211.0), state.series(MetricSeriesKind.POWER_WATTS).pairs())
        assertEquals(listOf(t0 + 3_000 to 8.5), state.series(MetricSeriesKind.RESISTANCE).pairs())
        assertEquals(listOf(t0 + 4_000 to 3.5), state.series(MetricSeriesKind.INCLINE_PERCENT).pairs())
        assertEquals(listOf(t0 + 5_000 to 42.0), state.series(MetricSeriesKind.FORCE_ON_BELT_NEWTONS).pairs())
        assertEquals(listOf(t0 + 6_000 to 432.0), state.series(MetricSeriesKind.DISTANCE_METERS).pairs())
    }

    @Test fun `stroke rate and cadence share one series`() {
        state.appendStrokeRateSample(24.0, tsMillis = 1_000)
        state.appendCadenceSpmSample(60.0, tsMillis = 2_000)

        assertEquals(listOf(1_000L to 24.0, 2_000L to 60.0), state.series(MetricSeriesKind.CADENCE_PER_MIN).pairs())
    }

    @Test fun `heart rate samples and the dropout marker share the heart rate series`() {
        state.appendHeartRateSample(140, tsMillis = 1_000)
        state.appendHeartRateSeriesPoint(0.0, tsMillis = 2_000) // dropout marker: a point, not a reading
        state.appendHeartRateSample(142, tsMillis = 3_000)

        assertEquals(listOf(1_000L to 140.0, 2_000L to 0.0, 3_000L to 142.0), state.series(MetricSeriesKind.HEART_RATE).pairs())
        // The marker touches neither the readout nor the average.
        assertEquals(142, state.heartRate.value)
        assertEquals(141, state.averageHeartRate.value)
    }

    @Test fun `series keep arrival order and are never trimmed`() {
        repeat(5_000) { state.appendPowerSample(100 + it % 7, tsMillis = 1_000L + it) }
        val series = state.series(MetricSeriesKind.POWER_WATTS)
        assertEquals(5_000, series.size)
        assertEquals(1_000L, series[0].tMillis)
        assertEquals(5_999L, series.last()!!.tMillis)
        assertTrue((1 until series.size).all { series.timeAt(it - 1) < series.timeAt(it) })
    }

    @Test fun `running averages do not depend on the series lists`() {
        state.appendPowerSample(100, tsMillis = 1)
        state.appendPowerSample(200, tsMillis = 2)
        state.appendCadenceSpmSample(60.0, tsMillis = 1)
        state.appendCadenceSpmSample(80.0, tsMillis = 2)
        assertEquals(150, state.averagePower.value)
        assertEquals(70.0, state.averageCadence.value!!, 0.0)
    }

    @Test fun `clearing session data empties every series and the averages but keeps totals`() {
        state.setTotalDistanceMeters(432)
        state.setElapsedSeconds(90)
        state.appendHeartRateSample(140, tsMillis = 1)
        state.appendSpeedKphSample(12.5, tsMillis = 1)
        state.appendCadenceSpmSample(80.0, tsMillis = 1)
        state.appendPowerSample(200, tsMillis = 1)
        state.appendInclinePercentSample(3.0, tsMillis = 1)
        state.appendResistanceSample(5.0, tsMillis = 1)
        state.appendForceOnBeltSample(40, tsMillis = 1)
        state.appendDistanceMetersSample(432, tsMillis = 1)
        state.setElevationGains(10.0, 2.0, tsMillis = 1)

        state.clearSessionData()

        assertTrue(state.series(MetricSeriesKind.HEART_RATE).isEmpty())
        assertTrue(state.series(MetricSeriesKind.SPEED_KPH).isEmpty())
        assertTrue(state.series(MetricSeriesKind.CADENCE_PER_MIN).isEmpty())
        assertTrue(state.series(MetricSeriesKind.POWER_WATTS).isEmpty())
        assertTrue(state.series(MetricSeriesKind.INCLINE_PERCENT).isEmpty())
        assertTrue(state.series(MetricSeriesKind.RESISTANCE).isEmpty())
        assertTrue(state.series(MetricSeriesKind.FORCE_ON_BELT_NEWTONS).isEmpty())
        assertTrue(state.series(MetricSeriesKind.DISTANCE_METERS).isEmpty())
        assertTrue(state.series(MetricSeriesKind.ELEVATION_NET_METERS).isEmpty())
        assertEquals(null, state.averageHeartRate.value)
        assertEquals(null, state.avgSpeedKph.value)
        assertEquals(null, state.averageCadence.value)
        assertEquals(null, state.averagePower.value)
        assertEquals(432, state.totalDistanceMeters.value)
        assertEquals(90, state.elapsedTimeSec.value)
    }

    @Test fun `rower speed is the distance change from the earliest point in the five second window`() {
        state.setElapsedSeconds(5)
        state.setTotalDistanceMeters(22)
        state.appendDistanceMetersSample(0, tsMillis = 0)
        state.appendDistanceMetersSample(10, tsMillis = 2_500)
        state.appendDistanceMetersSample(22, tsMillis = 5_000)
        state.updateRowingSpeed(nowMs = 5_000)

        // The oldest point at least 2 s behind the newest is the first in the window: 22 m over 5 s = 15.84 km/h.
        assertEquals(15.84, state.speedKph.value!!, 0.0001)
    }
}
