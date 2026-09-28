package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CscMeasurementTest {

    @Test
    fun `wheel-only frame decodes unsigned little-endian fields`() {
        val reading = CscMeasurement.parse(hex("01 78 56 34 F2 EF BE"))!!
        val wheel = reading.wheel!!

        assertEquals(0xF2345678L, wheel.cumulativeRevolutions)
        assertEquals(0xBEEF, wheel.lastEventTimeTicks)
        assertNull(reading.crank)
    }

    @Test
    fun `crank-only frame starts immediately after flags`() {
        val reading = CscMeasurement.parse(hex("02 FE FF 80 FF"))!!
        val crank = reading.crank!!

        assertNull(reading.wheel)
        assertEquals(0xFFFE, crank.cumulativeRevolutions)
        assertEquals(0xFF80, crank.lastEventTimeTicks)
    }

    @Test
    fun `combined frame decodes crank after complete wheel field`() {
        val reading = CscMeasurement.parse(hex("03 04 03 02 01 06 05 08 07 0A 09"))!!
        val wheel = reading.wheel!!
        val crank = reading.crank!!

        assertEquals(0x01020304L, wheel.cumulativeRevolutions)
        assertEquals(0x0506, wheel.lastEventTimeTicks)
        assertEquals(0x0708, crank.cumulativeRevolutions)
        assertEquals(0x090A, crank.lastEventTimeTicks)
    }

    @Test
    fun `reserved flags do not alter the known field layout`() {
        val reading = CscMeasurement.parse(hex("83 01 00 00 00 02 00 03 00 04 00 FF"))!!

        assertEquals(1L, reading.wheel!!.cumulativeRevolutions)
        assertEquals(3, reading.crank!!.cumulativeRevolutions)
    }

    @Test
    fun `flags-only frame with no supported fields is structurally valid`() {
        assertEquals(CscMeasurement.Reading(), CscMeasurement.parse(hex("00")))
    }

    @Test
    fun `every wheel truncation boundary rejects the whole frame`() {
        val complete = hex("01 01 00 00 00 02 00")
        for (length in 0 until complete.size) {
            assertNull("length=$length", CscMeasurement.parse(complete.copyOf(length)))
        }
    }

    @Test
    fun `every crank truncation boundary rejects the whole frame`() {
        val complete = hex("02 01 00 02 00")
        for (length in 0 until complete.size) {
            assertNull("length=$length", CscMeasurement.parse(complete.copyOf(length)))
        }
    }

    @Test
    fun `combined truncation rejects rather than returning a wheel prefix`() {
        val complete = hex("03 01 00 00 00 02 00 03 00 04 00")
        for (length in 0 until complete.size) {
            assertNull("length=$length", CscMeasurement.parse(complete.copyOf(length)))
        }
    }

    @Test
    fun `first observation establishes baselines and ordinary updates produce deltas`() {
        val tracker = CscMeasurementTracker()
        val first = tracker.accept(hex("03 64 00 00 00 00 04 C8 00 00 08"))!!
        val second = tracker.accept(hex("03 67 00 00 00 00 08 CA 00 00 0C"))!!
        val wheelDelta = second.wheelDelta!!
        val crankDelta = second.crankDelta!!

        assertNull(first.wheelDelta)
        assertNull(first.crankDelta)
        assertEquals(3L, wheelDelta.revolutions)
        assertEquals(1024, wheelDelta.eventTimeTicks)
        assertEquals(2, crankDelta.revolutions)
        assertEquals(1024, crankDelta.eventTimeTicks)
    }

    @Test
    fun `wheel decrease contributes zero and becomes the new baseline`() {
        val tracker = CscMeasurementTracker()
        tracker.accept(hex("01 64 00 00 00 00 10"))
        val decrease = tracker.accept(hex("01 05 00 00 00 00 14"))!!.wheelDelta!!
        val after = tracker.accept(hex("01 07 00 00 00 00 18"))!!.wheelDelta!!

        assertEquals(0L, decrease.revolutions)
        assertTrue(decrease.rebaselinedAfterDecrease)
        assertEquals(2L, after.revolutions)
        assertFalse(after.rebaselinedAfterDecrease)
    }

    @Test
    fun `held maximum wheel value does not roll over`() {
        val tracker = CscMeasurementTracker()
        tracker.accept(hex("01 FF FF FF FF 00 F0"))
        val held = tracker.accept(hex("01 FF FF FF FF 00 F4"))!!.wheelDelta!!

        assertEquals(0L, held.revolutions)
        assertFalse(held.rebaselinedAfterDecrease)
    }

    @Test
    fun `wheel and crank event times roll over independently`() {
        val tracker = CscMeasurementTracker()
        tracker.accept(hex("03 01 00 00 00 F0 FF 01 00 F8 FF"))
        val update = tracker.accept(hex("03 02 00 00 00 10 00 02 00 08 00"))!!

        assertEquals(32, update.wheelDelta!!.eventTimeTicks)
        assertEquals(16, update.crankDelta!!.eventTimeTicks)
    }

    @Test
    fun `crank counter rolls over at sixteen bits`() {
        val tracker = CscMeasurementTracker()
        tracker.accept(hex("02 FE FF 00 10"))
        val update = tracker.accept(hex("02 01 00 00 14"))!!.crankDelta!!

        assertEquals(3, update.revolutions)
        assertEquals(1024, update.eventTimeTicks)
    }

    @Test
    fun `truncated combined frame cannot partially advance wheel baseline`() {
        val tracker = CscMeasurementTracker()
        tracker.accept(hex("03 0A 00 00 00 00 10 14 00 00 20"))

        assertNull(tracker.accept(hex("03 0F 00 00 00 00 14 15")))

        val update = tracker.accept(hex("03 10 00 00 00 00 18 16 00 00 28"))!!
        assertEquals(6L, update.wheelDelta!!.revolutions)
        assertEquals(2, update.crankDelta!!.revolutions)
    }

    @Test
    fun `new tracker or reset re-establishes baselines after reconnect`() {
        val tracker = CscMeasurementTracker()
        tracker.accept(hex("01 64 00 00 00 00 10"))
        tracker.reset()

        assertNull(tracker.accept(hex("01 02 00 00 00 00 02"))!!.wheelDelta)
        assertEquals(
            1L,
            tracker.accept(hex("01 03 00 00 00 00 06"))!!.wheelDelta!!.revolutions,
        )
    }

    private fun hex(value: String): ByteArray = value
        .trim()
        .split(Regex("\\s+"))
        .filter(String::isNotEmpty)
        .map { it.toInt(16).toByte() }
        .toByteArray()
}
