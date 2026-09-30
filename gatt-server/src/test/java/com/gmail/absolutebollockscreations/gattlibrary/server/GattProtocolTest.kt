package com.gmail.absolutebollockscreations.gattlibrary.server

import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GattProtocolTest {

    @Test
    fun `read offsets distinguish end from past end`() {
        val payload = byteArrayOf(1, 2, 3)
        val suffix = GattProtocol.readSlice(payload, 1)
        assertEquals(GattProtocol.SUCCESS, suffix.status)
        assertArrayEquals(byteArrayOf(2, 3), suffix.value)

        val end = GattProtocol.readSlice(payload, 3)
        assertEquals(GattProtocol.SUCCESS, end.status)
        assertArrayEquals(ByteArray(0), end.value)

        val past = GattProtocol.readSlice(payload, 4)
        assertEquals(GattProtocol.INVALID_OFFSET, past.status)
        assertNull(past.value)

        val negative = GattProtocol.readSlice(payload, -1)
        assertEquals(GattProtocol.INVALID_OFFSET, negative.status)
        assertNull(negative.value)
    }

    @Test
    fun `CCCD accepts only values supported by characteristic properties`() {
        assertEquals(
            PeripheralConnection.CccdMode.DISABLED,
            GattProtocol.decodeCccd(byteArrayOf(0, 0), supportsNotify = false, supportsIndicate = false),
        )
        assertEquals(
            PeripheralConnection.CccdMode.NOTIFY,
            GattProtocol.decodeCccd(byteArrayOf(1, 0), supportsNotify = true, supportsIndicate = false),
        )
        assertNull(GattProtocol.decodeCccd(byteArrayOf(1, 0), supportsNotify = false, supportsIndicate = true))
        assertEquals(
            PeripheralConnection.CccdMode.INDICATE,
            GattProtocol.decodeCccd(byteArrayOf(2, 0), supportsNotify = false, supportsIndicate = true),
        )
        assertNull(GattProtocol.decodeCccd(byteArrayOf(2, 0), supportsNotify = true, supportsIndicate = false))
    }

    @Test
    fun `CCCD rejects malformed lengths and RFU bits`() {
        assertNull(GattProtocol.decodeCccd(byteArrayOf(), true, true))
        assertNull(GattProtocol.decodeCccd(byteArrayOf(1), true, true))
        assertNull(GattProtocol.decodeCccd(byteArrayOf(1, 0, 0), true, true))
        assertNull(GattProtocol.decodeCccd(byteArrayOf(3, 0), true, true))
        assertNull(GattProtocol.decodeCccd(byteArrayOf(0, 1), true, true))
    }

    @Test
    fun `Control Point accepts write requests and Garmin write commands`() {
        val request = GattProtocol.validateControlPointWrite(
            responseNeeded = true,
            preparedWrite = false,
            offset = 0,
            controlPointSubscribed = true,
        )
        assertEquals(GattProtocol.SUCCESS, request.status)
        assertEquals(true, request.sendAttResponse)

        val command = GattProtocol.validateControlPointWrite(
            responseNeeded = false,
            preparedWrite = false,
            offset = 0,
            controlPointSubscribed = true,
        )
        assertEquals(GattProtocol.SUCCESS, command.status)
        assertEquals(false, command.sendAttResponse)
    }

    @Test
    fun `Control Point transport does not bypass procedure validation`() {
        for (responseNeeded in listOf(true, false)) {
            assertEquals(
                GattProtocol.REQUEST_NOT_SUPPORTED,
                GattProtocol.validateControlPointWrite(
                    responseNeeded,
                    preparedWrite = true,
                    offset = 0,
                    controlPointSubscribed = true,
                ).status,
            )
            assertEquals(
                GattProtocol.INVALID_OFFSET,
                GattProtocol.validateControlPointWrite(
                    responseNeeded,
                    preparedWrite = false,
                    offset = 1,
                    controlPointSubscribed = true,
                ).status,
            )
            assertEquals(
                GattProtocol.CCCD_IMPROPERLY_CONFIGURED,
                GattProtocol.validateControlPointWrite(
                    responseNeeded,
                    preparedWrite = false,
                    offset = 0,
                    controlPointSubscribed = false,
                ).status,
            )
        }
    }
}
