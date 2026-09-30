package com.gmail.absolutebollockscreations.gattlibrary.server

import android.bluetooth.BluetoothProfile
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryUpstreamTest {
    private fun sample(
        elapsed: Int = 1,
        speedKph: Double? = null,
        power: Int? = null,
        avgPower: Int? = null,
        avgCadence: Double? = null,
        cadence: Double? = null,
        count: Int? = null,
        kind: MovementCountKind? = null,
    ) = SimulatedUpstreamSample(
        elapsedSeconds = elapsed, heartRate = 120, caloriesTotal = 2, caloriesPerHour = 300,
        speedKph = speedKph, powerWatts = power, averagePowerWatts = avgPower, averageCadence = avgCadence,
        cadence = cadence, movementCount = count, movementCountKind = kind,
    )

    @Test
    fun `there is never a physical link`() {
        val up = InMemoryUpstream()
        assertFalse(up.machineLinkAttached)
        assertEquals(BluetoothProfile.STATE_DISCONNECTED, up.machineConnectionState.value)
        assertTrue(up.connectedCentralAddresses().isEmpty())
        assertFalse(up.supportsPaceMeasurement())
        assertTrue(InMemoryUpstream(pacedMachine = true).supportsPaceMeasurement())
    }

    @Test
    fun `a metric the persona does not produce stays untouched but averages are overwritten`() {
        val up = InMemoryUpstream()
        up.publish(sample(speedKph = 20.0, power = 150, avgPower = 140, avgCadence = 80.0))
        up.publish(sample(elapsed = 2)) // persona produced nothing optional this time
        val r = up.currentReadings()
        assertEquals(20.0, r.speedKph!!, 0.0)
        assertEquals(150, r.instantPower)
        assertNull(r.averagePower)
        assertNull(r.averageCadence)
        assertEquals(2, r.elapsedSeconds)
    }

    @Test
    fun `end session returns to an idle machine`() {
        val up = InMemoryUpstream()
        up.publish(sample(speedKph = 20.0, power = 150))
        up.endSession()
        assertEquals(UpstreamReadings(), up.currentReadings())
    }

    @Test
    fun `movement count routes by kind and only strokes set stroke rate`() {
        val up = InMemoryUpstream()
        up.publish(sample(cadence = 24.0, count = 9, kind = MovementCountKind.STROKES))
        assertEquals(9, up.currentReadings().strokeCount)
        assertEquals(24.0, up.currentReadings().strokeRate!!, 0.0)
        up.endSession()
        up.publish(sample(cadence = 50.0, count = 12, kind = MovementCountKind.STEPS))
        assertEquals(12, up.currentReadings().totalSteps)
        assertNull(up.currentReadings().strokeRate)
        up.endSession()
        up.publish(sample(count = 12, kind = MovementCountKind.STRIDES))
        assertEquals(12.0, up.currentReadings().totalStrides!!, 0.0)
        up.endSession()
        up.publish(sample(count = 12, kind = null))
        assertNull(up.currentReadings().strokeCount)
    }

    @Test
    fun `sampler copies readings into the store using the persona profile`() {
        val up = InMemoryUpstream(elapsedRealtimeMs = { 1234L })
        val store = FtmsMetricStore()
        var profile: FtmsMachineProfile? = FtmsMachineProfile.INDOOR_ROWER
        val sampler = up.createMetricSampler(store) { profile }

        up.publish(sample(speedKph = 12.0, cadence = 24.0, count = 5, kind = MovementCountKind.STROKES))
        sampler.sampleOnce()

        assertEquals(150.0, store.snapshot.paceSecondsPer500m!!, 1e-9)
        assertEquals(24.0, store.snapshot.cadence!!, 0.0)
        assertEquals(5, store.snapshot.strokeCount)
        assertEquals(1234L, store.snapshot.updatedAtMs)

        profile = FtmsMachineProfile.INDOOR_BIKE // same readings, different persona
        sampler.sampleOnce()
        assertNull(store.snapshot.paceSecondsPer500m)
        assertNull(store.snapshot.strokeCount)
    }

    @Test
    fun `external heart rate carries a timestamp and clears to zero`() {
        var now = 5_000L
        val up = InMemoryUpstream(wallClockMs = { now })
        assertEquals(0L, up.lastExternalHeartRateAtMs)
        up.setExternalHeartRate(140)
        assertEquals(140, up.externalHeartRate.value)
        assertEquals(5_000L, up.lastExternalHeartRateAtMs)
        now = 9_000L
        up.setExternalHeartRate(null)
        assertNull(up.externalHeartRate.value)
        assertEquals(0L, up.lastExternalHeartRateAtMs)
    }

    @Test
    fun `control sink accepts session procedures and refuses targets`() {
        val sink = InMemoryUpstream().controlSink
        assertTrue(sink.requestControl())
        assertFalse(sink.setTargetSpeedKph(10.0))
        assertFalse(sink.canControl)
        sink.beginSession(TargetCapabilities.NONE)
        sink.endSession()
    }
}
