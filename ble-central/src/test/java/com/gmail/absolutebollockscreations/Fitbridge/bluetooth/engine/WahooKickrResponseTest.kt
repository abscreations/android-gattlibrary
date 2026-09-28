package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WahooKickrResponseTest {
    @Test
    fun `independently observed positive form is success`() {
        val response = WahooKickrResponseParser.parse(
            byteArrayOf(0x01, 0x42, 0x01, 0x00, 0x55),
            expectedOpcode = 0x42,
        )
        assertTrue(response is WahooKickrResponse.Success)
        assertEquals(0x42, (response as WahooKickrResponse.Success).opcode)
    }

    @Test
    fun `truncated and wrong-prefix packets remain unknown`() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(0x01), byteArrayOf(0x02, 0x42, 1, 0))) {
            assertTrue(WahooKickrResponseParser.parse(bytes, 0x42) is WahooKickrResponse.Unknown)
        }
    }

    @Test
    fun `a different echoed opcode is unrelated and cannot acknowledge current command`() {
        val response = WahooKickrResponseParser.parse(byteArrayOf(1, 0x43, 1, 0), 0x42)
        assertTrue(response is WahooKickrResponse.Unrelated)
        response as WahooKickrResponse.Unrelated
        assertEquals(0x42, response.expectedOpcode)
        assertEquals(0x43, response.echoedOpcode)
    }

    @Test
    fun `correlated unfamiliar status is unknown not invented failure`() {
        val response = WahooKickrResponseParser.parse(byteArrayOf(1, 0x42, 2, 7), 0x42)
        assertTrue(response is WahooKickrResponse.Unknown)
        assertFalse(response is WahooKickrResponse.ExplicitFailure)
    }

    @Test
    fun `parser retains a defensive copy of raw evidence`() {
        val source = byteArrayOf(1, 0x42, 1, 0)
        val response = WahooKickrResponseParser.parse(source, 0x42)
        source[0] = 0x7F
        assertArrayEquals(byteArrayOf(1, 0x42, 1, 0), response.raw)
    }
}
