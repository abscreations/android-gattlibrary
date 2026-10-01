package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Test

class RowerPaceConversionTest {

    @Test
    fun `rower wire pace is converted from seconds per 500m to seconds per km`() {
        assertEquals(256.0, rowerSecondsPer500mToSecondsPerKm(128.0), 0.0)
    }

    @Test
    fun `rower pace conversion preserves the corresponding speed`() {
        val secondsPerKm = rowerSecondsPer500mToSecondsPerKm(128.0)
        val speedKph = 3_600.0 / secondsPerKm

        assertEquals(14.0625, speedKph, 0.0001)
    }

    @Test
    fun `rower central to peripheral pace conversion is a round trip`() {
        val upstreamSecondsPer500m = 128.0
        val engineMinutesPerKm =
            rowerSecondsPer500mToSecondsPerKm(upstreamSecondsPer500m) / 60.0
        val downstreamSecondsPer500m = engineMinutesPerKm * 60.0 / 2.0

        assertEquals(upstreamSecondsPer500m, downstreamSecondsPer500m, 0.0)
    }

    @Test
    fun `engine pace is stored as canonical minutes per km`() {
        val state = BtEngineState()
        state.setInstantPace(4.0)
        state.setAvgPace(4.2)

        assertEquals(4.0, state.instantPaceMinPerKm.value!!, 0.0)
        assertEquals(4.2, state.averagePaceMinPerKm.value!!, 0.0)
    }

    @Test
    fun `distance-derived rower speed and average keep refreshing`() {
        val state = BtEngineState()

        state.setTotalDistanceMeters(10)
        state.setElapsedSeconds(2)
        state.appendDistanceMetersSample(0, tsMillis = 0)
        state.appendDistanceMetersSample(10, tsMillis = 2_500)
        state.updateRowingSpeed(nowMs = 2_500)

        assertEquals(14.4, state.speedKph.value!!, 0.0001)
        assertEquals(18.0, state.avgSpeedKph.value!!, 0.0001)

        state.setTotalDistanceMeters(22)
        state.setElapsedSeconds(5)
        state.appendDistanceMetersSample(22, tsMillis = 5_000)
        state.updateRowingSpeed(nowMs = 5_000)

        assertEquals(15.84, state.speedKph.value!!, 0.0001)
        assertEquals(15.84, state.avgSpeedKph.value!!, 0.0001)
        assertEquals(2, state.series(MetricSeriesKind.SPEED_KPH).size)
    }

    @Test
    fun `stroke rate samples populate the rowing graph series`() {
        val state = BtEngineState()

        state.appendStrokeRateSample(24.0, tsMillis = 1_000)
        state.appendStrokeRateSample(26.0, tsMillis = 2_000)

        assertEquals(26.0, state.strokeRate.value!!, 0.0)
        assertEquals(26.0, state.peakStrokeRate.value!!, 0.0)
        assertEquals(25.0, state.averageCadence.value!!, 0.0)
        assertEquals(2, state.series(MetricSeriesKind.CADENCE_PER_MIN).size)
        assertEquals(24.0, state.series(MetricSeriesKind.CADENCE_PER_MIN)[0].value, 0.0)
        assertEquals(26.0, state.series(MetricSeriesKind.CADENCE_PER_MIN).last()!!.value, 0.0)
    }

    @Test
    fun `energy rate prefers per-hour and falls back to per-minute`() {
        assertEquals(725, ftmsEnergyRateKcalPerHour(725, 12))
        assertEquals(720, ftmsEnergyRateKcalPerHour(0xFFFF, 12))
        assertEquals(null, ftmsEnergyRateKcalPerHour(0xFFFF, 0xFF))
    }
}
