package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.Concept2Measurement
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.Concept2Pm5Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Concept2Pm5EncoderTest {
    private val sample = Concept2Pm5MockEngine(Concept2Machine.ROW_ERG).tick(12.34)

    @Test
    fun `core individual packets have exact baseline lengths and round trip`() {
        val general = Concept2Pm5Encoder.individual(BluetoothIds.UUID_C2_GENERAL_STATUS, sample)!!
        val status1 = Concept2Pm5Encoder.individual(BluetoothIds.UUID_C2_ADDITIONAL_STATUS_1, sample)!!
        val status2 = Concept2Pm5Encoder.individual(BluetoothIds.UUID_C2_ADDITIONAL_STATUS_2, sample)!!
        val stroke = Concept2Pm5Encoder.individual(BluetoothIds.UUID_C2_ADDITIONAL_STROKE_DATA, sample)!!
        assertEquals(listOf(19, 17, 20, 15), listOf(general.size, status1.size, status2.size, stroke.size))

        assertEquals(sample.distanceDecimetres / 10.0, Concept2Measurement.generalStatus(general)!!.distanceMetres!!, 0.001)
        assertEquals(sample.speedMillimetresPerSecond / 1000.0, Concept2Measurement.additionalStatus1(status1)!!.speedMetresPerSecond!!, 0.001)
        assertEquals(sample.averagePowerWatts, Concept2Measurement.additionalStatus2(status2)!!.averagePowerWatts)
        assertEquals(sample.currentPowerWatts, Concept2Measurement.additionalStrokeData(stroke)!!.strokePowerWatts)
    }

    @Test
    fun `multiplexed layouts stay within MTU 23 and round trip through dispatch`() {
        listOf(0x31 to 20, 0x32 to 20, 0x33 to 19, 0x36 to 18).forEach { (id, length) ->
            val frame = Concept2Pm5Encoder.multiplexed(id, sample)!!
            assertEquals(id, frame[0].toInt() and 0xFF)
            assertEquals(length, frame.size)
            assertEquals(sample.elapsedCentiseconds, Concept2Measurement.multiplexed(frame)!!.elapsedCentiseconds)
        }
        assertNull(Concept2Pm5Encoder.multiplexed(0x7F, sample))
    }

    @Test
    fun `complete baseline packets use documented individual and mux widths`() {
        val individualLengths = linkedMapOf(
            BluetoothIds.UUID_C2_STROKE_DATA to 20,
            BluetoothIds.UUID_C2_SPLIT_INTERVAL_DATA to 18,
            BluetoothIds.UUID_C2_ADDITIONAL_SPLIT_INTERVAL to 19,
            BluetoothIds.UUID_C2_WORKOUT_SUMMARY to 20,
            BluetoothIds.UUID_C2_ADDITIONAL_WORKOUT_SUMMARY to 19,
            BluetoothIds.UUID_C2_HEART_RATE_BELT_INFO to 6,
            BluetoothIds.UUID_C2_ADDITIONAL_WORKOUT_SUMMARY_2 to 10,
        )
        individualLengths.forEach { (uuid, length) ->
            assertEquals(length, Concept2Pm5Encoder.individual(uuid, sample)!!.size)
        }
        val muxLengths = mapOf(0x35 to 19, 0x37 to 19, 0x38 to 20, 0x39 to 19, 0x3A to 19, 0x3B to 7, 0x3C to 11)
        muxLengths.forEach { (id, length) ->
            assertEquals(length, Concept2Pm5Encoder.multiplexed(id, sample)!!.size)
        }
    }

    @Test
    fun `device and status packets carry each persona machine type`() {
        Concept2Machine.entries.forEach { machine ->
            val snapshot = Concept2Pm5MockEngine(machine).tick(1.0)
            val expected = machine.wireMachineType
            val packets = listOf(
                Concept2Pm5Encoder.individual(BluetoothIds.UUID_C2_ADDITIONAL_STATUS_1, snapshot)!!,
                Concept2Pm5Encoder.multiplexed(0x32, snapshot)!!,
                Concept2Pm5Encoder.individual(BluetoothIds.UUID_C2_ADDITIONAL_SPLIT_INTERVAL, snapshot)!!,
                Concept2Pm5Encoder.multiplexed(0x38, snapshot)!!,
            )
            packets.forEach { packet ->
                assertEquals(expected, packet.last().toInt() and 0xFF)
            }
        }
    }

    @Test
    fun `force curve sequence is ordered bounded and lossless`() {
        val frames = Concept2Pm5Encoder.forceCurve(sample)
        assertEquals(3, frames.size)
        assertEquals(listOf(0, 1, 2), frames.map { it[1].toInt() and 0xFF })
        assertTrue(frames.all { it.size <= 20 })
        assertEquals(24, frames.sumOf { it[0].toInt() and 0x0F })
        assertTrue(frames.all { (it[0].toInt() ushr 4) == frames.size })
    }

    @Test
    fun `integer encoders saturate high and low inputs instead of wrapping`() {
        val extreme = sample.copy(
            elapsedCentiseconds = Int.MAX_VALUE,
            distanceDecimetres = -1,
            speedMillimetresPerSecond = Int.MAX_VALUE,
            heartRate = -50,
        )
        val general = Concept2Pm5Encoder.generalStatus(extreme)
        assertEquals(0xFF, general[0].toInt() and 0xFF)
        assertEquals(0, general[3].toInt() and 0xFF)
        val status = Concept2Pm5Encoder.individual(BluetoothIds.UUID_C2_ADDITIONAL_STATUS_1, extreme)!!
        assertEquals(0xFF, status[3].toInt() and 0xFF)
        assertEquals(0, status[6].toInt() and 0xFF)
    }

    @Test
    fun `sample rate and subscriptions are connection scoped`() {
        val first = Concept2Pm5Session()
        val second = Concept2Pm5Session()
        assertTrue(first.setSampleRate(3))
        assertFalse(first.setSampleRate(4))
        assertEquals(100L, first.periodMs)
        assertEquals(500L, second.periodMs)

        first.setSubscribed(BluetoothIds.UUID_C2_MULTIPLEXED_INFORMATION, true)
        first.setSubscribed(BluetoothIds.UUID_C2_GENERAL_STATUS, true)
        assertEquals(0x32, first.nextMuxId(listOf(0x31, 0x32)))
        assertFalse(second.isSubscribed(BluetoothIds.UUID_C2_MULTIPLEXED_INFORMATION))
        first.reset()
        assertEquals(500L, first.periodMs)
        assertFalse(first.isSubscribed(BluetoothIds.UUID_C2_GENERAL_STATUS))
    }

    @Test
    fun `heart rate receive input is exact width typed copied and connection scoped`() {
        val first = Concept2Pm5Session()
        val second = Concept2Pm5Session()
        val input = ByteArray(20).also { it[0] = 1 }

        assertTrue(first.setHeartRateInput(input))
        input[1] = 0x55
        assertEquals(0, first.heartRateInput!![1].toInt())
        assertNull(second.heartRateInput)
        assertFalse(first.setHeartRateInput(ByteArray(19)))
        assertFalse(first.setHeartRateInput(ByteArray(20).also { it[0] = 2 }))
        first.reset()
        assertNull(first.heartRateInput)
    }

    @Test
    fun `every PM5 persona remains coherent for thirty simulated minutes`() {
        Concept2Machine.entries.forEach { machine ->
            val engine = Concept2Pm5MockEngine(machine, Concept2Scenario.INTERVALS)
            var previous = engine.snapshot()
            repeat(3_600) {
                val current = engine.tick(0.5)
                assertTrue(current.elapsedCentiseconds >= previous.elapsedCentiseconds)
                assertTrue(current.distanceDecimetres >= previous.distanceDecimetres)
                assertTrue(current.speedMillimetresPerSecond in 1..65_535)
                assertTrue(current.currentPowerWatts in 0..65_535)
                val reciprocalPace = 500_000.0 / current.speedMillimetresPerSecond * 100.0
                assertEquals(reciprocalPace, current.currentPaceCentiseconds.toDouble(), 1.0)
                assertTrue(current.strokeState in 0..4)
                previous = current
            }
            assertEquals(180_000, previous.elapsedCentiseconds)
        }
    }
}
