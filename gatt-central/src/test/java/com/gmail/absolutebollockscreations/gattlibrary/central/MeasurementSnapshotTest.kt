package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The engine-state to public-snapshot projection (unit 4a, L24). */
class MeasurementSnapshotTest {

    @Test
    fun `an idle engine projects to the empty snapshot`() {
        assertEquals(MeasurementSnapshot.EMPTY, BtEngineState().toMeasurementSnapshot())
    }

    @Test
    fun `every field is read from its own engine flow`() {
        // Distinct values per field, so a crossed or swapped mapping fails here.
        val s = BtEngineState()
        // Imperial display units must not leak into the canonical fields (see the pace test below).
        s.setUnits(1)
        s.setSpeedKph(21.5)
        s.setAvgSpeedKph(20.25)
        s.setInstantPace(2.75)
        s.setAvgPace(2.9)
        s.setInstantCadence(88.0)
        s.setAverageCadence(85.5)
        s.setStrokeRate(24.0)
        s.setInstantPower(210)
        s.setAveragePower(195)
        s.setForceOnBelt(120)
        s.setHeartRate(151)
        s.setHeartRateZone(3)
        s.setTotalDistanceMeters(4321)
        s.setElapsedSeconds(987)
        s.setExpEnergyTotal(321)
        s.setExpEnergyRateKcalPerHour(610)
        s.setResistance(7.5)
        s.setInclination(3.5)
        s.setElevationGains(12.5, 4.25)
        s.setTotalFloors(9)
        s.setStrokeCount(77)
        s.setTotalStrides(1234.0)
        s.setTotalSteps(2345)

        assertEquals(
            MeasurementSnapshot(
                speedKph = 21.5, averageSpeedKph = 20.25, paceMinPerKm = 2.75, averagePaceMinPerKm = 2.9,
                cadence = 88.0, averageCadence = 85.5, strokeRate = 24.0,
                powerWatts = 210, averagePowerWatts = 195, forceOnBeltNewtons = 120,
                heartRate = 151, heartRateZone = 3, distanceMeters = 4321, elapsedSeconds = 987,
                energyKcal = 321, energyRateKcalPerHour = 610, resistance = 7.5, inclinationPercent = 3.5,
                ascentMeters = 12.5, descentMeters = 4.25, floors = 9,
                strokeCount = 77, strideCount = 1234.0, stepCount = 2345,
            ),
            s.toMeasurementSnapshot(),
        )
    }

    @Test
    fun `pace is canonical minutes per km whatever the display units are`() {
        // BtEngineState.instPace/avgPace are display-unit paces (min/mi when units == 1). Reading them
        // made the snapshot, and the bridge pump before it, wrong by a factor of 1.609344 on imperial.
        val metric = BtEngineState().apply { setUnits(0); setInstantPace(4.0); setAvgPace(5.0) }
        val imperial = BtEngineState().apply { setUnits(1); setInstantPace(4.0); setAvgPace(5.0) }
        for (s in listOf(metric, imperial)) {
            assertEquals(4.0, s.toMeasurementSnapshot().paceMinPerKm!!, 1e-9)
            assertEquals(5.0, s.toMeasurementSnapshot().averagePaceMinPerKm!!, 1e-9)
        }
        // Switching units after the fact re-emits the display pace only; canonical must not move.
        metric.setUnits(1)
        assertEquals(4.0, metric.toMeasurementSnapshot().paceMinPerKm!!, 1e-9)
    }

    @Test
    fun `a metric that was never supplied stays absent rather than zero`() {
        val s = BtEngineState()
        s.setInstantPower(0) // a genuine zero is kept
        val m = s.toMeasurementSnapshot()
        assertEquals(0, m.powerWatts)
        assertNull(m.speedKph)
        assertNull(m.heartRate)
        assertNull(m.resistance)
    }

    @Test
    fun `selected CSC events are projected with their counters and times`() {
        val s = BtEngineState()
        s.cscNativeEvents.publish(
            ConnectionId("AA:01", 1L),
            CscMeasurement.Reading(
                wheel = CscMeasurement.WheelEvent(100L, 2048),
                crank = CscMeasurement.CrankEvent(50, 1024),
            ),
            setOf(BtEngineState.CscRole.SPEED, BtEngineState.CscRole.CADENCE),
        )
        assertEquals(CycleSensorEvents(100L, 2048, 50, 1024), s.toMeasurementSnapshot().cscEvents)
        assertNull(s.toMeasurementSnapshot().cpsEvents)
    }

    @Test
    fun `CSC events from an unassigned role are not projected`() {
        val s = BtEngineState()
        s.cscNativeEvents.publish(
            ConnectionId("AA:01", 1L),
            CscMeasurement.Reading(wheel = CscMeasurement.WheelEvent(100L, 2048)),
            setOf(BtEngineState.CscRole.CADENCE),
        )
        assertNull(s.toMeasurementSnapshot().cscEvents)
    }

    @Test
    fun `treadmill raw pace is projected verbatim`() {
        val s = BtEngineState()
        s.treadmillNativePace.publish(ConnectionId("AA:01", 1L), 12, null)
        assertEquals(TreadmillRawPace(12, null), s.toMeasurementSnapshot().treadmillRawPace)
    }

    @Test
    fun `native stores bump their revision on every mutation so consumers can tell`() {
        val s = BtEngineState()
        val id = ConnectionId("AA:01", 1L)
        val before = s.treadmillNativePace.revision.value
        s.treadmillNativePace.publish(id, 12, 10)
        s.treadmillNativePace.remove(id)
        s.treadmillNativePace.clear()
        assertEquals(before + 3, s.treadmillNativePace.revision.value)
        assertTrue(s.cscNativeEvents.revision.value >= 0)
        assertTrue(s.cpsNativeEvents.revision.value >= 0)
    }

    @Test
    fun `the change list covers every source the snapshot reads`() {
        // 24 engine flows + 3 native-store revisions. Adding a snapshot field without a ping would
        // make the client miss updates, so the count is pinned to force that decision.
        assertEquals(27, BtEngineState().measurementChanges().size)
    }
}
