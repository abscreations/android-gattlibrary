package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtServiceProfiles.FtmsMachineProfile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetCapabilitiesTest {

    @Test
    fun `a target bit requires a valid range of the correct wire width`() {
        val t = FtmsSpec.TargetFeature
        assertEquals(0, TargetCapabilities(speed = true).toTargetFeatureBits())
        assertEquals(
            0,
            TargetCapabilities(speed = true, speedRange = FtmsTargetRange(-1, 100, 1))
                .toTargetFeatureBits(),
        )
        assertTrue(
            TargetCapabilities(speed = true, speedRange = FtmsTargetRange(0, 100, 10))
                .toTargetFeatureBits() and (1 shl t.SPEED) != 0,
        )
    }

    @Test
    fun `supported resistance range remains three signed 16 bit values`() {
        val range = FtmsTargetRange(-25, 250, 5)
        val payload = range.encodeS16()
        assertEquals(6, payload.size)
        assertEquals(0xE7, payload[0].toInt() and 0xFF)
        assertEquals(0xFF, payload[1].toInt() and 0xFF)
    }
}
