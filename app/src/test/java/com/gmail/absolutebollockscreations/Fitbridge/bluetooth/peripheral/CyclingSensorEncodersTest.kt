package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtServiceProfiles.FtmsMachineProfile

import org.junit.Assert.assertArrayEquals
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
}
