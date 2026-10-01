package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Pins how metrics reach the chart-history series today (LIB-42, step 1 of unit 4d), before the series move
 * into `MeasurementHistory`. Written against the old per-series `StateFlow<List<TimedSample>>` API; step 3
 * migrates the reads to the new API and the same assertions must still hold.
 *
 * Deliberately not pinned, because the history work changes it on purpose: what a session start leaves in the
 * distance series, what clearing the averages does to the average heart-rate zone, and elevation (never fed in
 * production today; it gets its own tests when fusion feeds it).
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

    private fun List<BtEngineState.TimedSample>.pairs() = map { it.tMillis to it.v }

    @Test fun `each fused metric lands in its own series with the series clock timestamp`() {
        val t0 = seriesNowMs
        submit(MetricKind.SPEED, 12.5)
        submit(MetricKind.CADENCE, 84.0)
        submit(MetricKind.POWER, 211.0)
        submit(MetricKind.RESISTANCE, 8.5)
        submit(MetricKind.INCLINATION, 3.5)
        submit(MetricKind.FORCE_ON_BELT, 42.0)
        submit(MetricKind.DISTANCE, 432.0)

        assertEquals(listOf(t0 to 12.5), state.speedSeriesKph.value.pairs())
        assertEquals(listOf(t0 + 1_000 to 84.0), state.cadenceSeriesSpm.value.pairs())
        assertEquals(listOf(t0 + 2_000 to 211.0), state.powerSeriesW.value.pairs())
        assertEquals(listOf(t0 + 3_000 to 8.5), state.resistanceSeries.value.pairs())
        assertEquals(listOf(t0 + 4_000 to 3.5), state.inclineSeriesPercent.value.pairs())
        assertEquals(listOf(t0 + 5_000 to 42.0), state.forceOnBeltSeries.value.pairs())
        assertEquals(listOf(t0 + 6_000 to 432.0), state.distanceSeriesM.value.pairs())
    }

    @Test fun `stroke rate and cadence share one series and the alias exposes it`() {
        state.appendStrokeRateSample(24.0, tsMillis = 1_000)
        state.appendCadenceSpmSample(60.0, tsMillis = 2_000)

        assertEquals(listOf(1_000L to 24.0, 2_000L to 60.0), state.cadenceSeriesSpm.value.pairs())
        assertEquals(state.cadenceSeriesSpm.value, state.strokeRateSeriesSpm.value)
    }

    @Test fun `heart rate samples and the dropout marker share the heart rate series`() {
        state.appendHeartRateSample(140, tsMillis = 1_000)
        state.appendHeartRateSeriesPoint(0.0, tsMillis = 2_000) // dropout marker: a point, not a reading
        state.appendHeartRateSample(142, tsMillis = 3_000)

        assertEquals(listOf(1_000L to 140.0, 2_000L to 0.0, 3_000L to 142.0), state.hrSeries.value.pairs())
        // The marker touches neither the readout nor the average.
        assertEquals(142, state.heartRate.value)
        assertEquals(141, state.averageHeartRate.value)
    }

    @Test fun `series keep arrival order and are never trimmed`() {
        repeat(5_000) { state.appendPowerSample(100 + it % 7, tsMillis = 1_000L + it) }
        val series = state.powerSeriesW.value
        assertEquals(5_000, series.size)
        assertEquals(1_000L, series.first().tMillis)
        assertEquals(5_999L, series.last().tMillis)
        assertTrue(series.zipWithNext().all { (a, b) -> a.tMillis < b.tMillis })
    }

    @Test fun `running averages do not depend on the series lists`() {
        state.appendPowerSample(100, tsMillis = 1)
        state.appendPowerSample(200, tsMillis = 2)
        state.appendCadenceSpmSample(60.0, tsMillis = 1)
        state.appendCadenceSpmSample(80.0, tsMillis = 2)
        assertEquals(150, state.averagePower.value)
        assertEquals(70.0, state.averageCadence.value!!, 0.0)
    }

    @Test fun `clearing time series empties every series and the averages but keeps totals`() {
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

        state.clearTimeSeries(keepTotals = true)

        assertTrue(state.hrSeries.value.isEmpty())
        assertTrue(state.speedSeriesKph.value.isEmpty())
        assertTrue(state.cadenceSeriesSpm.value.isEmpty())
        assertTrue(state.powerSeriesW.value.isEmpty())
        assertTrue(state.inclineSeriesPercent.value.isEmpty())
        assertTrue(state.resistanceSeries.value.isEmpty())
        assertTrue(state.forceOnBeltSeries.value.isEmpty())
        assertTrue(state.distanceSeriesM.value.isEmpty())
        assertTrue(state.elevationNetSeries.value.isEmpty())
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
