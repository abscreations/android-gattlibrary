package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtEngineState.FtmsCapabilities
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.ProviderAdvertisingReadiness.Decision
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.ProviderAdvertisingReadiness.RANGE_WAIT_MS
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LIB-9e: the bridge waits for every claimed Supported-* Range, but not forever.
 *
 * Engine-only on purpose: after the Stage 2 split this test lives in ble-central, which cannot
 * see the peripheral's `TargetCapabilities`.
 */
class ProviderAdvertisingReadinessTest {

    /** What 0x2ACC alone gives for a treadmill claiming speed, incline and power. */
    private val treadmillFeaturesOnly = FtmsCapabilities(
        featuresRead = true,
        supportsSpeed = true,
        supportsInclination = true,
        supportsPower = true,
    )

    /** The same treadmill once its three range reads have landed (raw spec units). */
    private val treadmillWithRanges = treadmillFeaturesOnly.copy(
        speedMin = 100f, speedMax = 2_000f, speedIncr = 10f, speedRangeKnown = true,
        inclineMin = -30f, inclineMax = 150f, inclineIncr = 5f, inclineRangeKnown = true,
        powerMin = 0f, powerMax = 1_000f, powerIncr = 1f, powerRangeKnown = true,
    )

    @Test
    fun `treadmill with only FEATURES waits for all three claimed ranges`() {
        // The old resistance-only gate returned ready here, and the bridge published no controls.
        assertEquals(
            Decision.WaitForRanges(listOf("speed", "inclination", "power")),
            ProviderAdvertisingReadiness.decide(treadmillFeaturesOnly, rangeWaitMs = 0),
        )
        val partial = treadmillFeaturesOnly.copy(
            speedMin = 100f, speedMax = 2_000f, speedIncr = 10f, speedRangeKnown = true,
        )
        assertEquals(
            Decision.WaitForRanges(listOf("inclination", "power")),
            ProviderAdvertisingReadiness.decide(partial, rangeWaitMs = 1_000),
        )
    }

    @Test
    fun `treadmill with every range is ready`() {
        assertEquals(Decision.Ready, ProviderAdvertisingReadiness.decide(treadmillWithRanges, 0))
    }

    @Test
    fun `missing ranges stop blocking at the timeout and are reported`() {
        assertEquals(
            Decision.WaitForRanges(listOf("speed", "inclination", "power")),
            ProviderAdvertisingReadiness.decide(treadmillFeaturesOnly, RANGE_WAIT_MS - 1),
        )
        val partial = treadmillWithRanges.copy(
            powerMin = null, powerMax = null, powerIncr = null, powerRangeKnown = false,
        )
        assertEquals(
            Decision.ReadyWithoutRanges(listOf("power")),
            ProviderAdvertisingReadiness.decide(partial, RANGE_WAIT_MS),
        )
    }

    @Test
    fun `FEATURES not read waits without a timeout`() {
        assertEquals(
            Decision.WaitForFeatures,
            ProviderAdvertisingReadiness.decide(FtmsCapabilities(), rangeWaitMs = 60_000),
        )
    }

    @Test
    fun `machines claiming nothing or carrying settled ranges are ready at once`() {
        assertEquals(Decision.Ready, ProviderAdvertisingReadiness.decide(sensorOnlyProviderCapabilities(), 0))
        assertEquals(Decision.Ready, ProviderAdvertisingReadiness.decide(wahooKickrProviderCapabilities(), 0))
        assertEquals(Decision.Ready, ProviderAdvertisingReadiness.decide(rideOnProviderCapabilities(), 0))
        // A bike still waits for its resistance range, as the old gate did.
        assertEquals(
            Decision.WaitForRanges(listOf("resistance")),
            ProviderAdvertisingReadiness.decide(
                FtmsCapabilities(featuresRead = true, supportsResistance = true),
                0,
            ),
        )
    }
}
