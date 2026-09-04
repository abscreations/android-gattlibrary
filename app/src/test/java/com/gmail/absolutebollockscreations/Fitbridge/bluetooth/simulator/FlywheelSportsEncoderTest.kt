package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.FlywheelDecodeResult
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.FlywheelMeasurement
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FlywheelSportsEncoderTest {
    @Test
    fun `encoder produces captured shape and round trips power and cadence`() {
        val frame = FlywheelSportsEncoder.encode(sample(power = 290, cadence = 90.0))

        assertEquals(FlywheelSportsEncoder.FRAME_SIZE, frame.size)
        assertEquals(1_000L, FlywheelSportsEncoder.PERIOD_MS)
        assertArrayEquals(byteArrayOf(0xff.toByte(), 0x1f, 0x0c), frame.copyOfRange(0, 3))
        assertEquals(0x01, frame[3].toInt() and 0xff)
        assertEquals(0x22, frame[4].toInt() and 0xff)
        assertEquals(90, frame[12].toInt() and 0xff)
        val reading = (FlywheelMeasurement.decode(frame) as FlywheelDecodeResult.Decoded).reading
        assertEquals(FlywheelMeasurement.Reading(290, 90), reading)
    }

    @Test
    fun `wire fields saturate rather than wrap`() {
        val high = FlywheelSportsEncoder.encode(sample(power = Int.MAX_VALUE, cadence = Double.MAX_VALUE))
        val low = FlywheelSportsEncoder.encode(sample(power = -1, cadence = -1.0))

        assertEquals(0xff, high[3].toInt() and 0xff)
        assertEquals(0xff, high[4].toInt() and 0xff)
        assertEquals(0xff, high[12].toInt() and 0xff)
        assertEquals(0, low[3].toInt() and 0xff)
        assertEquals(0, low[4].toInt() and 0xff)
        assertEquals(0, low[12].toInt() and 0xff)
    }

    private fun sample(power: Int, cadence: Double) = FtmsMockSample(
        elapsedSeconds = 1,
        heartRate = 100,
        caloriesTotal = 1,
        caloriesPerHour = 1,
        caloriesPerMinute = 1,
        speedKph = 20.0,
        distanceMeters = 10,
        cadence = cadence,
        powerWatts = power,
        averagePowerWatts = power,
    )
}
