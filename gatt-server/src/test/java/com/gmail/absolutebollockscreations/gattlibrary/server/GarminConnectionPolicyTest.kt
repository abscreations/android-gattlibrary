package com.gmail.absolutebollockscreations.gattlibrary.server

import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GarminConnectionPolicyTest {
    @Test
    fun `compatibility preference pins a nameless Garmin connection`() {
        assertTrue(shouldPinGarminConnection(true, null))
    }

    @Test
    fun `cached Garmin name pins without the preference`() {
        assertTrue(shouldPinGarminConnection(false, "Garmin Edge"))
    }

    @Test
    fun `captured Garmin public address pins without name or preference`() {
        assertTrue(shouldPinGarminConnection(false, null, "38:F9:F5:19:6D:58"))
    }

    @Test
    fun `unrelated downstream client is not pinned`() {
        assertFalse(shouldPinGarminConnection(false, "Pixel Tablet", "00:11:22:33:44:55"))
        assertFalse(shouldPinGarminConnection(false, null))
    }
}
