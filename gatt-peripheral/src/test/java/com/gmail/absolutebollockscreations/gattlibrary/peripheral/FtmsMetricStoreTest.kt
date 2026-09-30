package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FtmsMetricStoreTest {

    @Test
    fun `changing samples produce genuine time weighted averages`() {
        val store = FtmsMetricStore()
        store.update(FtmsMetricSnapshot(cadence = 60.0, powerWatts = 100, updatedAtMs = 1_000))
        store.update(FtmsMetricSnapshot(cadence = 120.0, powerWatts = 300, updatedAtMs = 2_000))

        assertEquals(90.0, store.snapshot.averageCadence!!, 0.001)
        assertEquals(200, store.snapshot.averagePowerWatts)
        assertTrue(store.snapshot.averageCadence != store.snapshot.cadence)
        assertTrue(store.snapshot.averagePowerWatts != store.snapshot.powerWatts)
    }

    @Test
    fun `stale gaps are excluded from averages`() {
        val store = FtmsMetricStore()
        store.update(FtmsMetricSnapshot(cadence = 60.0, updatedAtMs = 1_000))
        store.update(FtmsMetricSnapshot(cadence = 120.0, updatedAtMs = 7_000))
        assertEquals(120.0, store.snapshot.averageCadence!!, 0.001)
    }

    @Test
    fun `speed falls back to zero past the hold window instead of going silent`() {
        val store = FtmsMetricStore()
        store.update(FtmsMetricSnapshot(speedKph = 20.0, heartRate = 140, updatedAtMs = 1_000))
        assertEquals(20.0, store.effectiveSpeedKph(6_000)!!, 0.001)
        assertEquals(0.0, store.effectiveSpeedKph(6_001)!!, 0.001)
        // Never seen a speed at all -> nothing to send yet.
        assertNull(FtmsMetricStore().effectiveSpeedKph(6_001))
    }

    @Test
    fun `notification snapshot holds cumulatives and zeros instantaneous when stale`() {
        val store = FtmsMetricStore()
        store.update(
            FtmsMetricSnapshot(
                speedKph = 20.0,
                averageSpeedKph = 18.5,
                paceSecondsPer500m = 90.0,
                averagePaceSecondsPer500m = 97.3,
                cadence = 80.0,
                powerWatts = 200,
                forceOnBeltNewtons = 40,
                heartRate = 140,
                distanceMeters = 1_500,
                elapsedSeconds = 300,
                caloriesTotal = 42,
                positiveElevationGainMeters = 24.5,
                negativeElevationGainMeters = 3.0,
                floors = 8,
                strokeCount = 900,
                updatedAtMs = 1_000,
            ),
        )

        // Fresh: verbatim.
        assertEquals(20.0, store.snapshotForNotification(6_000)!!.speedKph!!, 0.001)

        // Stale but still streaming.
        val stale = store.snapshotForNotification(6_001)!!
        assertEquals(0.0, stale.speedKph!!, 0.001) // instantaneous zeroed
        assertEquals(18.5, stale.averageSpeedKph!!, 0.001) // session average held
        assertEquals(0.0, stale.paceSecondsPer500m!!, 0.001)
        assertEquals(97.3, stale.averagePaceSecondsPer500m!!, 0.001)
        assertEquals(0.0, stale.cadence!!, 0.001)
        assertEquals(0, stale.powerWatts)
        assertEquals(0, stale.forceOnBeltNewtons)
        assertEquals(0, stale.heartRate)
        assertEquals(1_500, stale.distanceMeters) // cumulative held
        assertEquals(300, stale.elapsedSeconds)
        assertEquals(42, stale.caloriesTotal)
        assertEquals(24.5, stale.positiveElevationGainMeters!!, 0.001)
        assertEquals(3.0, stale.negativeElevationGainMeters!!, 0.001)
        assertEquals(8, stale.floors)
        assertEquals(900, stale.strokeCount)

        // Nothing before the first sample.
        assertNull(FtmsMetricStore().snapshotForNotification(6_001))
    }

    @Test
    fun `clear resets rolling averages`() {
        val store = FtmsMetricStore()
        store.update(FtmsMetricSnapshot(cadence = 60.0, updatedAtMs = 1_000))
        store.update(FtmsMetricSnapshot(cadence = 120.0, updatedAtMs = 2_000))
        store.clear()
        store.update(FtmsMetricSnapshot(cadence = 30.0, updatedAtMs = 3_000))
        assertEquals(30.0, store.snapshot.averageCadence!!, 0.001)
    }

    @Test
    fun `treadmill elevation integrates time weighted grade into ascent and descent`() {
        val store = FtmsMetricStore()
        val profile = FtmsMachineProfile.TREADMILL

        store.update(
            FtmsMetricSnapshot(speedKph = 36.0, inclinationPercent = 10.0, updatedAtMs = 1_000),
            profile,
        )
        store.update(
            FtmsMetricSnapshot(speedKph = 36.0, inclinationPercent = 10.0, updatedAtMs = 2_000),
            profile,
        )
        // A linear transition from +10% to -10% has zero net vertical change.
        store.update(
            FtmsMetricSnapshot(speedKph = 36.0, inclinationPercent = -10.0, updatedAtMs = 3_000),
            profile,
        )
        store.update(
            FtmsMetricSnapshot(speedKph = 36.0, inclinationPercent = -10.0, updatedAtMs = 4_000),
            profile,
        )

        // 10 m/s * sin(atan(0.10)) for one second in each direction.
        assertEquals(0.995037, store.snapshot.positiveElevationGainMeters!!, 0.0001)
        assertEquals(0.995037, store.snapshot.negativeElevationGainMeters!!, 0.0001)
        assertEquals(0, store.snapshot.floors)
    }

    @Test
    fun `treadmill preserves native force and derives missing force from processed power`() {
        val native = FtmsMetricStore()
        native.update(
            FtmsMetricSnapshot(
                speedKph = 18.0,
                powerWatts = 250,
                forceOnBeltNewtons = 60,
                updatedAtMs = 1_000,
            ),
            FtmsMachineProfile.TREADMILL,
        )
        assertEquals(60, native.snapshot.forceOnBeltNewtons)

        val synthetic = FtmsMetricStore()
        synthetic.update(
            FtmsMetricSnapshot(speedKph = 18.0, powerWatts = 250, updatedAtMs = 1_000),
            FtmsMachineProfile.TREADMILL,
        )
        // 18 km/h = 5 m/s; F = P / v = 250 / 5.
        assertEquals(50, synthetic.snapshot.forceOnBeltNewtons)
    }

    @Test
    fun `treadmill does not invent infinite force at zero speed`() {
        val store = FtmsMetricStore()
        store.update(
            FtmsMetricSnapshot(speedKph = 0.0, powerWatts = 250, updatedAtMs = 1_000),
            FtmsMachineProfile.TREADMILL,
        )
        assertNull(store.snapshot.forceOnBeltNewtons)

        store.update(
            FtmsMetricSnapshot(speedKph = 0.0, powerWatts = 0, updatedAtMs = 2_000),
            FtmsMachineProfile.TREADMILL,
        )
        assertEquals(0, store.snapshot.forceOnBeltNewtons)
    }

    @Test
    fun `climber elevation derives missing floors at three metres each`() {
        // TrackMyIndoorWorkout additionally reinterprets distanceMeters as
        // elevation for the climbers, because its Dart parser stuffs FTMS
        // Positive Elevation Gain into Record.distance. FitBridge reads
        // elevationGainPos from the machine directly, so no such reinterpretation
        // is ported - only the floors/elevation derivation is shared.
        val store = FtmsMetricStore()
        store.update(
            FtmsMetricSnapshot(positiveElevationGainMeters = 11.0, updatedAtMs = 1_000),
            FtmsMachineProfile.STAIR_CLIMBER,
        )

        assertEquals(11.0, store.snapshot.positiveElevationGainMeters!!, 0.001)
        assertEquals(3, store.snapshot.floors)
    }

    @Test
    fun `climber floors derive missing elevation at three metres each`() {
        val store = FtmsMetricStore()
        store.update(
            FtmsMetricSnapshot(floors = 4, updatedAtMs = 1_000),
            FtmsMachineProfile.STAIR_STEPPER,
        )

        assertEquals(12.0, store.snapshot.positiveElevationGainMeters!!, 0.001)
        assertEquals(4, store.snapshot.floors)
    }
}
