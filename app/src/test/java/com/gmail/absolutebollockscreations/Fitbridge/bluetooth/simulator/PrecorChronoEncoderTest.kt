package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.PrecorMeasurement
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PrecorChronoEncoderTest {
    @Test
    fun `captured notification sequence is measurement then auxiliary at one hertz`() {
        val sample = sample(
            elapsedSeconds = 446,
            heartRate = 0,
            caloriesTotal = 9,
            speedKph = 17.69,
            distanceMeters = 1_918,
            cadence = 62.0,
            powerWatts = 38,
            averagePowerWatts = 16,
        )

        val notifications = PrecorChronoEncoder.encodeNotifications(sample)

        assertEquals(1_000L, PrecorChronoEncoder.PAIR_PERIOD_MS)
        assertEquals(2, notifications.size)
        assertArrayEquals(
            byteArrayOf(
                0x53, 0x59, 0x16, 0xBE.toByte(), 0x01, 0x00, 0xE9.toByte(), 0x06,
                0x6C, 0x02, 0x7E, 0x07, 0x00, 0x09, 0x00, 0x07, 0x00, 0x26, 0x00,
            ),
            notifications[0],
        )
        assertArrayEquals(byteArrayOf(0x24, 0x08, 0x00), notifications[1])
    }

    @Test
    fun `representative bike sample has the exact documented layout and round trips`() {
        val frame = PrecorChronoEncoder.encode(
            sample(
                elapsedSeconds = 3_661,
                heartRate = 151,
                caloriesTotal = 321,
                speedKph = 32.45,
                distanceMeters = 123_456,
                cadence = 87.6,
                powerWatts = 234,
                averagePowerWatts = 201,
            ),
        )

        assertArrayEquals(
            byteArrayOf(
                0x53, 0x59, 0x16,
                0x4D, 0x0E,
                0x97.toByte(),
                0xAD.toByte(), 0x0C,
                0x6C, 0x03,
                0x40, 0xE2.toByte(), 0x01,
                0x41, 0x01,
                0xDF.toByte(), 0x02,
                0xEA.toByte(), 0x00,
            ),
            frame,
        )

        val reading = requireNotNull(PrecorMeasurement.parse(frame))
        assertEquals(3_661, reading.elapsedSeconds)
        assertEquals(151, reading.heartRateBpm)
        assertEquals(32.45, reading.speedKph, 0.0)
        assertEquals(87.6, reading.cadenceRpm, 0.0)
        assertEquals(123_456, reading.distanceMetres)
        assertEquals(321, reading.calories)
        assertEquals(735, reading.unidentified15to16)
        assertEquals(234, reading.powerWatts)
    }

    @Test
    fun `wire fields saturate instead of wrapping`() {
        val reading = requireNotNull(
            PrecorMeasurement.parse(
                PrecorChronoEncoder.encode(
                    sample(
                        elapsedSeconds = Int.MAX_VALUE,
                        heartRate = Int.MAX_VALUE,
                        caloriesTotal = Int.MAX_VALUE,
                        speedKph = Double.MAX_VALUE,
                        distanceMeters = Int.MAX_VALUE,
                        cadence = Double.MAX_VALUE,
                        powerWatts = Int.MAX_VALUE,
                        averagePowerWatts = Int.MAX_VALUE,
                    ),
                ),
            ),
        )

        assertEquals(0xFFFF, reading.elapsedSeconds)
        assertEquals(0xFF, reading.heartRateBpm)
        assertEquals(655.35, reading.speedKph, 0.0)
        assertEquals(6_553.5, reading.cadenceRpm, 0.0)
        assertEquals(0xFFFFFF, reading.distanceMetres)
        assertEquals(0xFFFF, reading.calories)
        assertEquals(0xFFFF, reading.unidentified15to16)
        assertEquals(0xFFFF, reading.powerWatts)
    }

    private fun sample(
        elapsedSeconds: Int,
        heartRate: Int,
        caloriesTotal: Int,
        speedKph: Double,
        distanceMeters: Int,
        cadence: Double,
        powerWatts: Int,
        averagePowerWatts: Int,
    ) = FtmsMockSample(
        elapsedSeconds = elapsedSeconds,
        heartRate = heartRate,
        caloriesTotal = caloriesTotal,
        caloriesPerHour = 0,
        caloriesPerMinute = 0,
        speedKph = speedKph,
        distanceMeters = distanceMeters,
        cadence = cadence,
        powerWatts = powerWatts,
        averagePowerWatts = averagePowerWatts,
    )
}
