package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtServiceProfiles.FtmsMachineProfile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CscControlPointTest {
    @Test
    fun `set cumulative wheel revolutions decodes unsigned little endian`() {
        val request = CscControlPoint.decode(
            byteArrayOf(0x01, 0x78, 0x56, 0x34, 0xF2.toByte()),
        )

        assertEquals(
            CscControlPointRequest.SetCumulativeWheelRevolutions(0xF234_5678L),
            request,
        )
    }

    @Test
    fun `malformed set request is invalid and unknown opcode is unsupported`() {
        assertTrue(
            CscControlPoint.decode(byteArrayOf(0x01, 0x02)) is CscControlPointRequest.Invalid,
        )
        assertEquals(
            CscControlPointRequest.Unsupported(0x7F),
            CscControlPoint.decode(byteArrayOf(0x7F)),
        )
    }

    @Test
    fun `response uses sc control point response opcode`() {
        assertArrayEquals(
            byteArrayOf(0x10, 0x01, 0x01),
            CscControlPoint.response(CscControlPoint.SET_CUMULATIVE_VALUE, CscControlPoint.SUCCESS),
        )
    }
}
