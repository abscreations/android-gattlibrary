package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.BtEngineState
import com.gmail.absolutebollockscreations.gattlibrary.central.CycleSensorEvents
import com.gmail.absolutebollockscreations.gattlibrary.central.MeasurementSnapshot
import com.gmail.absolutebollockscreations.gattlibrary.central.TreadmillRawPace
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import com.gmail.absolutebollockscreations.gattlibrary.server.CpsNativeEventInput
import com.gmail.absolutebollockscreations.gattlibrary.server.CscNativeEventInput
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsMetricStore
import com.gmail.absolutebollockscreations.gattlibrary.server.TreadmillNativePaceInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pump's production input is central's public [MeasurementSnapshot] (L24, unit 4a), with no
 * engine state behind it. These tests drive the pump only through that snapshot.
 */
class EngineMetricPumpSnapshotSourceTest {

    private val events = CycleSensorEvents(100L, 2048, 50, 1024)

    private val full = MeasurementSnapshot(
        speedKph = 12.0, averageSpeedKph = 10.0, paceMinPerKm = 4.0, averagePaceMinPerKm = 5.0,
        cadence = 60.0, averageCadence = 58.0, strokeRate = 24.0,
        powerWatts = 210, averagePowerWatts = 190, forceOnBeltNewtons = 90,
        heartRate = 150, distanceMeters = 1234, elapsedSeconds = 321,
        energyKcal = 40, energyRateKcalPerHour = 600, resistance = 7.5, inclinationPercent = 2.0,
        ascentMeters = 12.0, descentMeters = 3.0, floors = 4,
        strokeCount = 77, strideCount = 20.6, stepCount = 33,
        cscEvents = events, cpsEvents = events, treadmillRawPace = TreadmillRawPace(12, 10),
    )

    private fun sample(profile: FtmsMachineProfile, snapshot: MeasurementSnapshot): FtmsMetricStore {
        val store = FtmsMetricStore()
        EngineMetricPump({ snapshot }, store) { profile }.sampleOnce()
        return store
    }

    @Test
    fun `an empty snapshot gives an empty store snapshot`() {
        val s = sample(FtmsMachineProfile.INDOOR_BIKE, MeasurementSnapshot.EMPTY).snapshot
        assertNull(s.speedKph)
        assertNull(s.powerWatts)
        assertNull(s.heartRate)
    }

    @Test
    fun `rower reads pace from speed and stroke rate and stroke count`() {
        val s = sample(FtmsMachineProfile.INDOOR_ROWER, full).snapshot
        assertEquals(150.0, s.paceSecondsPer500m!!, 1e-9) // 1800 / 12
        assertEquals(180.0, s.averagePaceSecondsPer500m!!, 1e-9) // 1800 / 10
        assertEquals(24.0, s.cadence!!, 0.0)
        assertEquals(77, s.strokeCount)
    }

    @Test
    fun `bike uses cadence not stroke rate and carries no pace or count`() {
        val s = sample(FtmsMachineProfile.INDOOR_BIKE, full).snapshot
        assertEquals(60.0, s.cadence!!, 0.0)
        assertNull(s.paceSecondsPer500m)
        assertNull(s.strokeCount)
    }

    @Test
    fun `scalar readings map to their snapshot fields`() {
        val s = sample(FtmsMachineProfile.INDOOR_BIKE, full).snapshot
        assertEquals(12.0, s.speedKph!!, 0.0)
        assertEquals(10.0, s.averageSpeedKph!!, 0.0)
        assertEquals(58.0, s.averageCadence!!, 0.0)
        assertEquals(210, s.powerWatts)
        assertEquals(190, s.averagePowerWatts)
        assertEquals(90, s.forceOnBeltNewtons)
        assertEquals(150, s.heartRate)
        assertEquals(1234, s.distanceMeters)
        assertEquals(321, s.elapsedSeconds)
        assertEquals(40, s.caloriesTotal)
        assertEquals(600, s.caloriesPerHour)
        assertEquals(10, s.caloriesPerMinute)
        assertEquals(7.5f, s.resistance!!, 0.0f)
        assertEquals(2.0, s.inclinationPercent!!, 0.0)
        assertEquals(4, s.floors)
    }

    @Test
    fun `stride count rounds for elliptical and step count routes to the stair stepper`() {
        assertEquals(21, sample(FtmsMachineProfile.ELLIPTICAL, full).snapshot.strokeCount)
        assertEquals(33, sample(FtmsMachineProfile.STAIR_STEPPER, full).snapshot.strokeCount)
    }

    @Test
    fun `native cycle events are carried as the server's CSC and CPS inputs`() {
        val s = sample(FtmsMachineProfile.INDOOR_BIKE, full).snapshot
        assertEquals(CscNativeEventInput(100L, 2048, 50, 1024), s.cscNativeEvents)
        assertEquals(CpsNativeEventInput(100L, 2048, 50, 1024), s.cpsNativeEvents)
    }

    @Test
    fun `treadmill raw pace is carried for the treadmill only`() {
        assertEquals(
            TreadmillNativePaceInput(12, 10),
            sample(FtmsMachineProfile.TREADMILL, full).snapshot.treadmillNativePace,
        )
        assertNull(sample(FtmsMachineProfile.INDOOR_BIKE, full).snapshot.treadmillNativePace)
    }

    @Test
    fun `rower pace from engine pace is canonical minutes per km`() {
        // History (LIB-34): the pump once read the display-unit pace flow as if it were min/km, so with
        // imperial units a rower with no speed reported pace 1.609344 times too slow.
        val state = BtEngineState().apply { setInstantPace(2.0) } // 2.0 min/km
        val store = FtmsMetricStore()
        EngineMetricPump(state, store) { FtmsMachineProfile.INDOOR_ROWER }.sampleOnce()
        assertEquals(60.0, store.snapshot.paceSecondsPer500m!!, 1e-9) // 2 min/km = 60 s per 500 m
    }

    @Test
    fun `each sample reads the source afresh`() {
        var current = MeasurementSnapshot(powerWatts = 100)
        val store = FtmsMetricStore()
        val pump = EngineMetricPump({ current }, store) { FtmsMachineProfile.INDOOR_BIKE }
        pump.sampleOnce()
        assertEquals(100, store.snapshot.powerWatts)
        current = MeasurementSnapshot(powerWatts = 250)
        pump.sampleOnce()
        assertEquals(250, store.snapshot.powerWatts)
    }
}
