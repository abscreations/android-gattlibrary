package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CyclingSensorEncodersTest {
    private val revolutions = CyclingRevolutionSnapshot(
        cumulativeWheelRevolutions = 0x0102_0304L,
        lastWheelEventTime = 0x0506,
        cumulativeCrankRevolutions = 0x0708,
        lastCrankEventTime = 0x090A,
    )

    @Test
    fun `csc measurement advertises and encodes wheel then crank data`() {
        assertArrayEquals(
            byteArrayOf(0x03, 0x04, 0x03, 0x02, 0x01, 0x06, 0x05, 0x08, 0x07, 0x0A, 0x09),
            CyclingSensorEncoders.cscMeasurement(revolutions),
        )
    }

    @Test
    fun `cycling power measurement includes signed power and crank cadence data`() {
        assertArrayEquals(
            byteArrayOf(0x20, 0x00, 0xFA.toByte(), 0x00, 0x08, 0x07, 0x0A, 0x09),
            CyclingSensorEncoders.cyclingPowerMeasurement(250, revolutions),
        )
    }

    @Test
    fun `feature and location characteristics match emitted fields`() {
        assertArrayEquals(byteArrayOf(0x03, 0x00), CyclingSensorEncoders.cscFeature())
        assertArrayEquals(
            byteArrayOf(0x08, 0x00, 0x00, 0x00),
            CyclingSensorEncoders.cyclingPowerFeature(),
        )
        assertArrayEquals(byteArrayOf(0x0D), CyclingSensorEncoders.sensorLocation())
    }

    @Test
    fun `native wheel and synthetic crank fields merge independently and encode byte exact`() {
        val merged = revolutions.withNativeEvents(
            CscNativeEventInput(
                cumulativeWheelRevolutions = 0xFEDC_BA98L,
                lastWheelEventTimeTicks = 0xFFFF,
            ),
        )

        assertArrayEquals(
            byteArrayOf(
                0x03,
                0x98.toByte(), 0xBA.toByte(), 0xDC.toByte(), 0xFE.toByte(),
                0xFF.toByte(), 0xFF.toByte(),
                0x08, 0x07,
                0x0A, 0x09,
            ),
            CyclingSensorEncoders.cscMeasurement(merged),
        )
    }

    @Test
    fun `native crank and synthetic wheel fields merge independently`() {
        assertEquals(
            CyclingRevolutionSnapshot(
                cumulativeWheelRevolutions = 0x0102_0304L,
                lastWheelEventTime = 0x0506,
                cumulativeCrankRevolutions = 0xFFFE,
                lastCrankEventTime = 0xFFFF,
            ),
            revolutions.withNativeEvents(
                CscNativeEventInput(
                    cumulativeCrankRevolutions = 0xFFFE,
                    lastCrankEventTimeTicks = 0xFFFF,
                ),
            ),
        )
    }

    @Test
    fun `incomplete native field pair falls back to the complete synthetic pair`() {
        assertEquals(
            revolutions,
            revolutions.withNativeEvents(
                CscNativeEventInput(cumulativeWheelRevolutions = 99),
            ),
        )
    }
}
