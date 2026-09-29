package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.BtEngineState.FtmsCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LIB-9e, peripheral side: why the bridge must wait for ranges. A treadmill snapshot taken
 * straight after 0x2ACC publishes no controls; the same treadmill with its ranges read publishes
 * speed, inclination and power.
 */
class TreadmillBridgeCapabilitiesTest {

    private val featuresOnly = FtmsCapabilities(
        featuresRead = true,
        supportsSpeed = true,
        supportsInclination = true,
        supportsPower = true,
    )

    @Test
    fun `features-only treadmill snapshot publishes no controls`() {
        assertEquals(TargetCapabilities.NONE, TargetCapabilities.fromEngineCapabilities(featuresOnly))
    }

    @Test
    fun `treadmill with ranges publishes speed inclination and power`() {
        val withRanges = featuresOnly.copy(
            speedMin = 100f, speedMax = 2_000f, speedIncr = 10f, speedRangeKnown = true,
            inclineMin = -30f, inclineMax = 150f, inclineIncr = 5f, inclineRangeKnown = true,
            powerMin = 0f, powerMax = 1_000f, powerIncr = 1f, powerRangeKnown = true,
        )
        val targets = TargetCapabilities.fromEngineCapabilities(withRanges)
        assertTrue(targets.supportsSpeed)
        assertTrue(targets.supportsInclination)
        assertTrue(targets.supportsPower)
        assertFalse(targets.supportsResistance)
    }

    @Test
    fun `a range that never arrived drops only that control`() {
        val noPowerRange = featuresOnly.copy(
            speedMin = 100f, speedMax = 2_000f, speedIncr = 10f, speedRangeKnown = true,
            inclineMin = -30f, inclineMax = 150f, inclineIncr = 5f, inclineRangeKnown = true,
        )
        val targets = TargetCapabilities.fromEngineCapabilities(noPowerRange)
        assertTrue(targets.supportsSpeed)
        assertTrue(targets.supportsInclination)
        assertFalse(targets.supportsPower)
    }
}
