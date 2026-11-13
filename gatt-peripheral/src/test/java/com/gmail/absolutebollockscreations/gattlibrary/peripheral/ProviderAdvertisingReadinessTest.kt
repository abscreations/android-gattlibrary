package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.ControlRange
import com.gmail.absolutebollockscreations.gattlibrary.central.DeviceCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.ProviderAdvertisingReadiness.Decision
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.ProviderAdvertisingReadiness.RANGE_WAIT_MS
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LIB-9e over the public capabilities (unit 4b step 4a-2). Every case is the central readiness
 * test's case with the same expected decision, so the canonical gate is shown to decide exactly as
 * the raw gate did.
 *
 * Inputs are public [DeviceCapabilities] literals (LIB-76). They are the exact canonical conversions
 * of the central test's raw fixtures (raw speed 0.01 km/h, incline 0.1 %, resistance 0.1 level,
 * power 1 W). gatt-central's ProviderCapabilityLiteralsTest pins that the engine's provider fixtures
 * still convert to [SENSOR_ONLY], [FLYWHEEL] and [WAHOO_KICKR], so the two sides cannot drift.
 */
class ProviderAdvertisingReadinessTest {

    private val treadmillFeaturesOnly = DeviceCapabilities(
        featuresRead = true,
        supportsSpeedTarget = true,
        supportsInclineTarget = true,
        supportsPowerTarget = true,
    )
    private val speedRange = ControlRange(1f, 20f, 0.1f) // raw 100..2000 step 10
    private val treadmillWithRanges = treadmillFeaturesOnly.copy(
        speedRangeKph = speedRange,
        inclineRangePercent = ControlRange(-3f, 15f, 0.5f), // raw -30..150 step 5
        powerRangeWatts = ControlRange(0f, 1_000f, 1f),
    )

    @Test
    fun `treadmill with only FEATURES waits for all three claimed ranges`() {
        assertEquals(
            Decision.WaitForRanges(listOf("speed", "inclination", "power")),
            ProviderAdvertisingReadiness.decide(treadmillFeaturesOnly, rangeWaitMs = 0),
        )
        val partial = treadmillFeaturesOnly.copy(speedRangeKph = speedRange)
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
        val partial = treadmillWithRanges.copy(powerRangeWatts = null)
        assertEquals(
            Decision.ReadyWithoutRanges(listOf("power")),
            ProviderAdvertisingReadiness.decide(partial, RANGE_WAIT_MS),
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
            ProviderAdvertisingReadiness.decide(DeviceCapabilities(), rangeWaitMs = 60_000),
        )
    }

    @Test
    fun `machines claiming nothing or carrying settled ranges are ready at once`() {
        assertEquals(Decision.Ready, ProviderAdvertisingReadiness.decide(SENSOR_ONLY, 0))
        assertEquals(Decision.Ready, ProviderAdvertisingReadiness.decide(FLYWHEEL, 0))
        assertEquals(Decision.Ready, ProviderAdvertisingReadiness.decide(WAHOO_KICKR, 0))
        assertEquals(Decision.Ready, ProviderAdvertisingReadiness.decide(SENSOR_ONLY_CAPABILITIES, 0))
        // A bike still waits for its resistance range, as the old gate did.
        assertEquals(
            Decision.WaitForRanges(listOf("resistance")),
            ProviderAdvertisingReadiness.decide(
                DeviceCapabilities(featuresRead = true, supportsResistanceTarget = true),
                0,
            ),
        )
    }

    @Test
    fun `the peripheral's sensor-only capabilities equal the engine's once converted`() {
        // Joined with gatt-central's ProviderCapabilityLiteralsTest, which pins that the engine's
        // sensor-only fixture converts to SENSOR_ONLY.
        assertEquals(SENSOR_ONLY, SENSOR_ONLY_CAPABILITIES)
    }

    private companion object {
        /** Canonical `sensorOnlyProviderCapabilities()`: settled, claims nothing. */
        val SENSOR_ONLY = DeviceCapabilities(featuresRead = true)

        /** Canonical `flywheelProviderCapabilities()`: settled, measurement only. */
        val FLYWHEEL = DeviceCapabilities(featuresRead = true)

        /** Canonical `wahooKickrProviderCapabilities()`: raw resistance 0..1000 step 1, power 0..2000 W step 1. */
        val WAHOO_KICKR = DeviceCapabilities(
            featuresRead = true,
            supportsResistanceTarget = true,
            supportsPowerTarget = true,
            supportsIndoorBikeSimulation = true,
            supportsWheelCircumference = true,
            resistanceRange = ControlRange(0f, 100f, 0.1f),
            powerRangeWatts = ControlRange(0f, 2_000f, 1f),
        )
    }
}
