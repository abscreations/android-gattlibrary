package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.server.CyclingRevolutionSnapshot
import com.gmail.absolutebollockscreations.gattlibrary.server.CyclingSensorEncoders
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.server.CpsNativeEventInput

class CpsNativeEventsTest {
    @Test
    fun `downstream CPS preserves native wheel and crank while keeping signed power`() {
        val payload = CyclingSensorEncoders.cyclingPowerMeasurement(
            -3,
            CyclingRevolutionSnapshot(cumulativeCrankRevolutions = 99, lastCrankEventTime = 100),
            CpsNativeEventInput(0x01020304, 0x0506, 0x0708, 0x090A),
        )
        assertArrayEquals(
            hex("30 00 FD FF 04 03 02 01 06 05 08 07 0A 09"),
            payload,
        )
    }

    private fun hex(value: String): ByteArray = value.trim().split(Regex("\\s+"))
        .map { it.toInt(16).toByte() }.toByteArray()
}
