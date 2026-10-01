package com.gmail.absolutebollockscreations.gattlibrary.server

import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The single engine-units to FTMS-units projection that both the central pump and the in-memory upstream use. */
class UpstreamReadingsTest {

    private fun project(r: UpstreamReadings, profile: FtmsMachineProfile?) = r.toSnapshot(profile, updatedAtMs = 7)

    @Test
    fun `rower pace comes from speed first then from engine pace`() {
        val fromSpeed = project(UpstreamReadings(speedKph = 12.0, instantPaceMinPerKm = 99.0), FtmsMachineProfile.INDOOR_ROWER)
        assertEquals(150.0, fromSpeed.paceSecondsPer500m!!, 1e-9) // 1800 / 12

        val fromPace = project(UpstreamReadings(instantPaceMinPerKm = 2.0), FtmsMachineProfile.INDOOR_ROWER)
        assertEquals(60.0, fromPace.paceSecondsPer500m!!, 1e-9) // 2 min/km = 60 s per 500 m
    }

    @Test
    fun `rower average pace follows the same rule on the average fields`() {
        val fromSpeed = project(UpstreamReadings(averageSpeedKph = 9.0, averagePaceMinPerKm = 99.0), FtmsMachineProfile.INDOOR_ROWER)
        assertEquals(200.0, fromSpeed.averagePaceSecondsPer500m!!, 1e-9)

        val fromPace = project(UpstreamReadings(averagePaceMinPerKm = 2.5), FtmsMachineProfile.INDOOR_ROWER)
        assertEquals(75.0, fromPace.averagePaceSecondsPer500m!!, 1e-9)
    }

    @Test
    fun `non-positive or non-finite speed and pace never become a pace`() {
        for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(project(UpstreamReadings(speedKph = bad), FtmsMachineProfile.INDOOR_ROWER).paceSecondsPer500m)
            assertNull(project(UpstreamReadings(instantPaceMinPerKm = bad), FtmsMachineProfile.INDOOR_ROWER).paceSecondsPer500m)
        }
    }

    @Test
    fun `pace is only produced for the rower`() {
        val r = UpstreamReadings(speedKph = 12.0, averageSpeedKph = 12.0)
        for (profile in FtmsMachineProfile.values().filter { it != FtmsMachineProfile.INDOOR_ROWER }) {
            assertNull(profile.name, project(r, profile).paceSecondsPer500m)
            assertNull(profile.name, project(r, profile).averagePaceSecondsPer500m)
        }
    }

    @Test
    fun `rower cadence prefers stroke rate and falls back to cadence, others use cadence only`() {
        val both = UpstreamReadings(strokeRate = 24.0, instantCadence = 60.0)
        assertEquals(24.0, project(both, FtmsMachineProfile.INDOOR_ROWER).cadence!!, 0.0)
        assertEquals(60.0, project(UpstreamReadings(instantCadence = 60.0), FtmsMachineProfile.INDOOR_ROWER).cadence!!, 0.0)
        assertEquals(60.0, project(both, FtmsMachineProfile.INDOOR_BIKE).cadence!!, 0.0)
        assertNull(project(UpstreamReadings(strokeRate = 24.0), FtmsMachineProfile.INDOOR_BIKE).cadence)
    }

    @Test
    fun `movement count is routed per persona and rounded for strides`() {
        val r = UpstreamReadings(strokeCount = 11, totalStrides = 20.6, totalSteps = 33)
        assertEquals(11, project(r, FtmsMachineProfile.INDOOR_ROWER).strokeCount)
        assertEquals(21, project(r, FtmsMachineProfile.ELLIPTICAL).strokeCount)
        assertEquals(21, project(r, FtmsMachineProfile.STAIR_CLIMBER).strokeCount)
        assertEquals(33, project(r, FtmsMachineProfile.STAIR_STEPPER).strokeCount)
        assertNull(project(r, FtmsMachineProfile.TREADMILL).strokeCount)
        assertNull(project(r, FtmsMachineProfile.INDOOR_BIKE).strokeCount)
    }

    @Test
    fun `calories per minute is the hourly rate over sixty and absent stays absent`() {
        assertEquals(10, project(UpstreamReadings(caloriesPerHour = 600), null).caloriesPerMinute)
        assertNull(project(UpstreamReadings(), null).caloriesPerMinute)
    }

    @Test
    fun `resistance keeps its fractional part`() {
        assertEquals(0.7f, project(UpstreamReadings(resistance = 0.7), null).resistance!!, 0.0f)
    }

    @Test
    fun `native treadmill pace is carried only for the treadmill`() {
        val native = TreadmillNativePaceInput(instantaneousRaw = 12, averageRaw = 10)
        val r = UpstreamReadings(treadmillNativePace = native)
        assertEquals(native, project(r, FtmsMachineProfile.TREADMILL).treadmillNativePace)
        assertNull(project(r, FtmsMachineProfile.INDOOR_BIKE).treadmillNativePace)
        assertNull(project(r, null).treadmillNativePace)
    }

    @Test
    fun `an empty reading projects to an empty snapshot apart from its timestamp`() {
        assertEquals(FtmsMetricSnapshot.EMPTY.copy(updatedAtMs = 7), project(UpstreamReadings(), FtmsMachineProfile.INDOOR_BIKE))
    }
}
