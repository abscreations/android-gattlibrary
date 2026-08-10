package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CpsMeasurementTest {
    @Test
    fun `mandatory power is signed`() {
        assertEquals(-3, CpsMeasurement.parse(hex("00 00 FD FF"))?.instantaneousPowerWatts)
        assertEquals(Short.MIN_VALUE.toInt(), CpsMeasurement.parse(hex("00 00 00 80"))?.instantaneousPowerWatts)
        assertEquals(Short.MAX_VALUE.toInt(), CpsMeasurement.parse(hex("00 00 FF 7F"))?.instantaneousPowerWatts)
    }

    @Test
    fun `reference and offset indicator flags consume no payload`() {
        val reading = CpsMeasurement.parse(hex("0A 10 64 00"))!!
        assertEquals(0x100A, reading.flags)
        assertEquals(100, reading.instantaneousPowerWatts)
        assertTrue(reading.offsetCompensationIndicator)
    }

    @Test
    fun `reserved flags and trailing bytes do not shift known fields`() {
        val reading = CpsMeasurement.parse(hex("00 E0 2A 00 AA BB CC"))!!
        assertEquals(42, reading.instantaneousPowerWatts)
        assertNull(reading.wheel)
        assertNull(reading.crank)
    }

    @Test
    fun `complete optional field walk preserves every boundary`() {
        val reading = CpsMeasurement.parse(
            hex(
                "FF 1F 9C FF 64 20 00 " +
                    "04 03 02 01 06 05 08 07 0A 09 " +
                    "F4 01 0C FE 40 00 E0 FF BC 3A 12 " +
                    "2D 01 90 01 34 12",
            ),
        )!!
        assertEquals(-100, reading.instantaneousPowerWatts)
        assertEquals(50.0, reading.pedalPowerBalancePercent!!, 0.0001)
        assertTrue(reading.pedalPowerBalanceRightReference)
        assertEquals(1.0, reading.accumulatedTorqueNewtonMetres!!, 0.0001)
        assertTrue(reading.accumulatedTorqueCrankSource)
        assertEquals(0x01020304L, reading.wheel!!.cumulativeRevolutions)
        assertEquals(0x0506, reading.wheel.lastEventTimeTicks)
        assertEquals(0x0708, reading.crank!!.cumulativeRevolutions)
        assertEquals(0x090A, reading.crank.lastEventTimeTicks)
        assertEquals(500, reading.maximumForceNewtons)
        assertEquals(-500, reading.minimumForceNewtons)
        assertEquals(2.0, reading.maximumTorqueNewtonMetres!!, 0.0001)
        assertEquals(-1.0, reading.minimumTorqueNewtonMetres!!, 0.0001)
        assertEquals(0xABC, reading.maximumAngleDegrees)
        assertEquals(0x123, reading.minimumAngleDegrees)
        assertEquals(301, reading.topDeadSpotAngleDegrees)
        assertEquals(400, reading.bottomDeadSpotAngleDegrees)
        assertEquals(0x1234, reading.accumulatedEnergyKilojoules)
        assertTrue(reading.offsetCompensationIndicator)
    }

    @Test
    fun `every truncation boundary rejects the whole declared frame`() {
        val complete = hex(
            "FF 1F 9C FF 64 20 00 " +
                "04 03 02 01 06 05 08 07 0A 09 " +
                "F4 01 0C FE 40 00 E0 FF BC 3A 12 " +
                "2D 01 90 01 34 12",
        )
        for (length in 0 until complete.size) {
            assertNull("length=$length", CpsMeasurement.parse(complete.copyOf(length)))
        }
    }

    @Test
    fun `tracker handles event time and revolution rollover without mutating on malformed input`() {
        val tracker = CpsMeasurementTracker()
        tracker.accept(hex("30 00 64 00 FE FF FF FF F0 FF FE FF F8 FF"))
        assertNull(tracker.accept(hex("30 00 65")))
        val update = tracker.accept(hex("30 00 66 00 01 00 00 00 10 00 01 00 08 00"))!!
        assertEquals(3L, update.wheelDelta!!.revolutions)
        assertEquals(32, update.wheelDelta.eventTimeTicks)
        assertEquals(3, update.crankDelta!!.revolutions)
        assertEquals(16, update.crankDelta.eventTimeTicks)
        assertTrue(update.wheelDelta.counterRolledOver)
    }

    @Test
    fun `ordinary wheel decrease rebaselines instead of inventing billions of revolutions`() {
        val tracker = CpsMeasurementTracker()
        tracker.accept(hex("10 00 64 00 64 00 00 00 00 10"))
        val reset = tracker.accept(hex("10 00 64 00 02 00 00 00 00 14"))!!.wheelDelta!!
        assertEquals(0L, reset.revolutions)
        assertTrue(reset.rebaselinedAfterDecrease)
        assertFalse(reset.counterRolledOver)
    }

    @Test
    fun `reset establishes fresh baselines after reconnect`() {
        val tracker = CpsMeasurementTracker()
        tracker.accept(hex("20 00 64 00 64 00 00 10"))
        tracker.reset()
        assertNull(tracker.accept(hex("20 00 65 00 02 00 00 02"))?.crankDelta)
        assertEquals(1, tracker.accept(hex("20 00 66 00 03 00 00 06"))?.crankDelta?.revolutions)
    }

    private fun hex(value: String): ByteArray = value.trim().split(Regex("\\s+"))
        .filter(String::isNotEmpty).map { it.toInt(16).toByte() }.toByteArray()
}
