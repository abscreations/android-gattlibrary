package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LIB-76: gatt-peripheral's ProviderAdvertisingReadinessTest decides over public [DeviceCapabilities]
 * literals that are meant to be the canonical form of the engine's provider fixtures. This pins that
 * they still are, so a change to a provider fixture fails here rather than leaving the peripheral test
 * checking a stale value. The literals are duplicated on purpose; keep the two copies identical.
 */
class ProviderCapabilityLiteralsTest {
    @Test
    fun `provider fixtures convert to the literals the peripheral readiness test uses`() {
        assertEquals(DeviceCapabilities(featuresRead = true), sensorOnlyProviderCapabilities().toDeviceCapabilities(false))
        assertEquals(DeviceCapabilities(featuresRead = true), flywheelProviderCapabilities().toDeviceCapabilities(false))
        assertEquals(
            DeviceCapabilities(
                featuresRead = true,
                supportsResistanceTarget = true,
                supportsPowerTarget = true,
                supportsIndoorBikeSimulation = true,
                supportsWheelCircumference = true,
                resistanceRange = ControlRange(0f, 100f, 0.1f),
                powerRangeWatts = ControlRange(0f, 2_000f, 1f),
            ),
            wahooKickrProviderCapabilities().toDeviceCapabilities(false),
        )
    }

    @Test
    fun `the treadmill readiness literals are the canonical form of the central test's raw fixture`() {
        val raw = BtEngineState.FtmsCapabilities(
            featuresRead = true, supportsSpeed = true, supportsInclination = true, supportsPower = true,
            speedMin = 100f, speedMax = 2_000f, speedIncr = 10f, speedRangeKnown = true,
            inclineMin = -30f, inclineMax = 150f, inclineIncr = 5f, inclineRangeKnown = true,
            powerMin = 0f, powerMax = 1_000f, powerIncr = 1f, powerRangeKnown = true,
        )
        assertEquals(
            DeviceCapabilities(
                featuresRead = true,
                supportsSpeedTarget = true,
                supportsInclineTarget = true,
                supportsPowerTarget = true,
                speedRangeKph = ControlRange(1f, 20f, 0.1f),
                inclineRangePercent = ControlRange(-3f, 15f, 0.5f),
                powerRangeWatts = ControlRange(0f, 1_000f, 1f),
            ),
            raw.toDeviceCapabilities(false),
        )
    }
}
