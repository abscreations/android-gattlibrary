package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the one session-clear primitive (LIB-44, unit 4d step 3): what `clearSessionData()` discards and keeps,
 * and that a session going active uses it. The three assertions marked "deliberate change" differ from the
 * behaviour before the clear functions were unified.
 */
class SessionDataClearTest {
    private val state = BtEngineState()

    private fun fill() {
        state.setTotalDistanceMeters(432)
        state.setElapsedSeconds(90)
        state.setLapCount(2)
        state.setElevationGains(10.0, 2.0, tsMillis = 1)
        state.appendHeartRateSample(140, tsMillis = 1)
        state.appendHeartRateSample(150, tsMillis = 2)
        state.setAverageHeartRateZone(3)
        state.appendSpeedKphSample(12.5, tsMillis = 1)
        state.appendCadenceSpmSample(80.0, tsMillis = 1)
        state.appendPowerSample(200, tsMillis = 1)
        state.appendInclinePercentSample(3.0, tsMillis = 1)
        state.appendResistanceSample(5.0, tsMillis = 1)
        state.appendForceOnBeltSample(40, tsMillis = 1)
        state.appendDistanceMetersSample(432, tsMillis = 1)
        state.setLapAvgSpeedMps(3.0)
        state.setLapPaceMinPerKm(5.5)
    }

    @Test
    fun `clearSessionData empties all nine series`() {
        fill()
        state.clearSessionData()
        for (kind in MetricSeriesKind.entries) {
            assertTrue("$kind should be empty", state.series(kind).isEmpty())
        }
    }

    @Test
    fun `clearSessionData clears the averages including the average heart rate zone`() {
        fill()
        state.clearSessionData()
        assertNull(state.averageHeartRate.value)
        assertNull(state.averageHeartRateZone.value) // deliberate change: was left stale
        assertNull(state.avgSpeedKph.value)
        assertNull(state.averageCadence.value)
        assertNull(state.averagePower.value)
    }

    @Test
    fun `clearSessionData clears the lap speed and pace derived from the series`() {
        fill()
        state.clearSessionData()
        assertNull(state.lapAvgSpeedMps.value)
        assertNull(state.lapPaceMinPerKm.value)
    }

    @Test
    fun `clearSessionData keeps totals elapsed time laps gains and the active flag`() {
        fill()
        state.setActiveSession(true)
        state.clearSessionData()
        assertEquals(432, state.totalDistanceMeters.value)
        assertEquals(90, state.elapsedTimeSec.value)
        assertEquals(2, state.lapCount.value)
        assertEquals(10.0, state.elevationGainPos.value!!, 0.0)
        assertEquals(2.0, state.elevationGainNeg.value!!, 0.0)
        assertTrue(state.activeSession.value)
    }

    @Test
    fun `averages restart from scratch after a clear`() {
        fill()
        state.clearSessionData()
        state.appendHeartRateSample(100, tsMillis = 10)
        assertEquals(100, state.averageHeartRate.value)
        assertEquals(1, state.series(MetricSeriesKind.HEART_RATE).size)
    }

    @Test
    fun `a session going active starts from clean data including the distance series`() {
        fill()
        state.setActiveSession(true)
        assertTrue(state.series(MetricSeriesKind.DISTANCE_METERS).isEmpty()) // deliberate change: was kept
        assertTrue(state.series(MetricSeriesKind.HEART_RATE).isEmpty())
        assertNull(state.averageHeartRateZone.value)
        assertNull(state.lapAvgSpeedMps.value)
        assertNull(state.lapPaceMinPerKm.value)
        assertEquals(432, state.totalDistanceMeters.value)
    }

    @Test
    fun `setting an already active session active again does not clear`() {
        state.setActiveSession(true)
        state.appendPowerSample(200, tsMillis = 1)
        state.setActiveSession(true)
        assertEquals(1, state.series(MetricSeriesKind.POWER_WATTS).size)
        assertFalse(state.series(MetricSeriesKind.POWER_WATTS).isEmpty())
    }

    @Test
    fun `a history view taken before a clear keeps its data`() {
        fill()
        val before = state.series(MetricSeriesKind.HEART_RATE)
        state.clearSessionData()
        assertEquals(listOf(1L to 140.0, 2L to 150.0), before.pairs())
        assertTrue(state.series(MetricSeriesKind.HEART_RATE).isEmpty())
    }
}
