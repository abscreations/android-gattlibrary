package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FtmsResistanceStatusDecoderTest {

    @Test
    fun `decodes legacy uint8 target resistance status`() {
        assertEquals(
            10f,
            decodeTargetResistanceStatus(byteArrayOf(0x07, 0x64))!!,
            0.001f,
        )
    }

    @Test
    fun `decodes sint16 target resistance status`() {
        assertEquals(
            -2.5f,
            decodeTargetResistanceStatus(
                byteArrayOf(0x07, 0xE7.toByte(), 0xFF.toByte()),
            )!!,
            0.001f,
        )
    }

    @Test
    fun `rejects malformed target resistance status`() {
        assertNull(decodeTargetResistanceStatus(byteArrayOf(0x07)))
        assertNull(decodeTargetResistanceStatus(byteArrayOf(0x07, 0x64, 0x00, 0x00)))
        assertNull(decodeTargetResistanceStatus(byteArrayOf(0x08, 0x64)))
    }
}
