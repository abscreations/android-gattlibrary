package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import com.gmail.absolutebollockscreations.gattlibrary.simulator.FtmsMockEngine
import com.gmail.absolutebollockscreations.gattlibrary.simulator.FtmsMockTarget
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.server.buildFtmsFeaturePayload

class FtmsRowerSimulatorFeatureTest {
    @Test
    fun `controllable rower publishes speed resistance and power targets with usable ranges`() {
        val engine = FtmsMockEngine(FtmsMachineProfile.INDOOR_ROWER, controlsEnabled = true)
        val targets = engine.controlCapabilities
        val feature = buildFtmsFeaturePayload(FtmsMachineProfile.INDOOR_ROWER, targets)

        // FTMS Target Setting Features bits 0, 2 and 3, in the upper LE32 word.
        assertArrayEquals(byteArrayOf(0x0d, 0, 0, 0), feature.copyOfRange(4, 8))
        assertTrue(targets.speedRange!!.isValidU16)
        assertTrue(targets.resistanceRange!!.isValidS16)
        assertTrue(targets.powerRange!!.isValidS16)
        assertTrue(engine.setTarget(FtmsMockTarget.RESISTANCE, 5.0))
    }

    @Test
    fun `measurement only rower publishes no target features and rejects resistance`() {
        val engine = FtmsMockEngine(FtmsMachineProfile.INDOOR_ROWER, controlsEnabled = false)
        val feature = buildFtmsFeaturePayload(FtmsMachineProfile.INDOOR_ROWER, engine.controlCapabilities)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), feature.copyOfRange(4, 8))
        assertFalse(engine.setTarget(FtmsMockTarget.RESISTANCE, 5.0))
    }
}
