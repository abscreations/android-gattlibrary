package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.BtEngineState.FtmsCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.central.DeviceCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.central.flywheelProviderCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.central.sensorOnlyProviderCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.central.toDeviceCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.central.wahooKickrProviderCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.ProviderAdvertisingReadiness.Decision
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.ProviderAdvertisingReadiness.RANGE_WAIT_MS
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LIB-9e over the public capabilities (unit 4b step 4a-2). Every case is the central readiness
 * test's case with its raw fixture converted through `toDeviceCapabilities`, with the same expected
 * decision, so the canonical gate is shown to decide exactly as the raw gate did.
 */
class ProviderAdvertisingReadinessTest {

    private fun FtmsCapabilities.canonical() = toDeviceCapabilities(paceMeasurement = false)

    private val treadmillFeaturesOnly = FtmsCapabilities(
        featuresRead = true,
        supportsSpeed = true,
        supportsInclination = true,
        supportsPower = true,
    )
    private val treadmillWithRanges = treadmillFeaturesOnly.copy(
        speedMin = 100f, speedMax = 2_000f, speedIncr = 10f, speedRangeKnown = true,
        inclineMin = -30f, inclineMax = 150f, inclineIncr = 5f, inclineRangeKnown = true,
        powerMin = 0f, powerMax = 1_000f, powerIncr = 1f, powerRangeKnown = true,
    )

    @Test
    fun `treadmill with only FEATURES waits for all three claimed ranges`() {
        assertEquals(
            Decision.WaitForRanges(listOf("speed", "inclination", "power")),
            ProviderAdvertisingReadiness.decide(treadmillFeaturesOnly.canonical(), rangeWaitMs = 0),
        )
        val partial = treadmillFeaturesOnly.copy(
            speedMin = 100f, speedMax = 2_000f, speedIncr = 10f, speedRangeKnown = true,
        )
        assertEquals(
            Decision.WaitForRanges(listOf("inclination", "power")),
            ProviderAdvertisingReadiness.decide(partial.canonical(), rangeWaitMs = 1_000),
        )
    }

    @Test
    fun `treadmill with every range is ready`() {
        assertEquals(Decision.Ready, ProviderAdvertisingReadiness.decide(treadmillWithRanges.canonical(), 0))
    }

    @Test
    fun `missing ranges stop blocking at the timeout and are reported`() {
        assertEquals(
            Decision.WaitForRanges(listOf("speed", "inclination", "power")),
            ProviderAdvertisingReadiness.decide(treadmillFeaturesOnly.canonical(), RANGE_WAIT_MS - 1),
        )
        val partial = treadmillWithRanges.copy(
            powerMin = null, powerMax = null, powerIncr = null, powerRangeKnown = false,
        )
        assertEquals(
            Decision.ReadyWithoutRanges(listOf("power")),
            ProviderAdvertisingReadiness.decide(partial.canonical(), RANGE_WAIT_MS),
        )
    }

    @Test
    fun `FEATURES not read waits without a timeout`() {
        assertEquals(
            Decision.WaitForFeatures,
            ProviderAdvertisingReadiness.decide(DeviceCapabilities.NONE, rangeWaitMs = 60_000),
        )
        assertEquals(
            Decision.WaitForFeatures,
            ProviderAdvertisingReadiness.decide(FtmsCapabilities().canonical(), rangeWaitMs = 60_000),
        )
    }

    @Test
    fun `machines claiming nothing or carrying settled ranges are ready at once`() {
        assertEquals(Decision.Ready, ProviderAdvertisingReadiness.decide(sensorOnlyProviderCapabilities().canonical(), 0))
        assertEquals(Decision.Ready, ProviderAdvertisingReadiness.decide(flywheelProviderCapabilities().canonical(), 0))
        assertEquals(Decision.Ready, ProviderAdvertisingReadiness.decide(wahooKickrProviderCapabilities().canonical(), 0))
        assertEquals(Decision.Ready, ProviderAdvertisingReadiness.decide(SENSOR_ONLY_CAPABILITIES, 0))
        // A bike still waits for its resistance range, as the old gate did.
        assertEquals(
            Decision.WaitForRanges(listOf("resistance")),
            ProviderAdvertisingReadiness.decide(
                FtmsCapabilities(featuresRead = true, supportsResistance = true).canonical(),
                0,
            ),
        )
    }

    @Test
    fun `the peripheral's sensor-only capabilities equal the engine's once converted`() {
        assertEquals(sensorOnlyProviderCapabilities().canonical(), SENSOR_ONLY_CAPABILITIES)
    }
}
