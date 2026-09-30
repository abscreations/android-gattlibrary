package com.gmail.absolutebollockscreations.gattlibrary.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D26 safety default for the injected peripheral settings (LIB-6/S6). */
class PeripheralConfigTest {

    @Test
    fun `default config keeps treadmill speed control off`() {
        assertFalse(PeripheralConfig().treadmillSpeedControlsEnabled())
    }

    @Test
    fun `treadmill speed control is read live on every call`() {
        var enabled = false
        val config = PeripheralConfig(treadmillSpeedControlsEnabled = { enabled })
        assertFalse(config.treadmillSpeedControlsEnabled())
        enabled = true
        assertTrue(config.treadmillSpeedControlsEnabled())
        enabled = false
        assertFalse(config.treadmillSpeedControlsEnabled())
    }
}
