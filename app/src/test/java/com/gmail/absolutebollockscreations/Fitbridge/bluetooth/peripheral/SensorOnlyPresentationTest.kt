package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import com.gmail.absolutebollockscreations.Fitbridge.ActivityType
import com.gmail.absolutebollockscreations.Fitbridge.ConnectedServiceTag
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtEngineState
import com.gmail.absolutebollockscreations.Fitbridge.sensorOnlySessionModes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1's downstream criterion: *"an RSC-only session presents as a downstream FTMS Treadmill
 * with speed, cadence, and distance, and with **no** target-setting capability bits set."*
 *
 * ### Why the target bits are the part that matters
 *
 * Speed, cadence and distance are just data; getting them wrong shows up immediately. The
 * capability bits are a **promise**. A watch reading a speed-target bit is being told it can
 * ask FitBridge to change the belt speed — and in a sensor-only session there is no belt. The
 * user would set a target, see it accepted, and nothing would happen.
 *
 * §9.2 and §6c both come down to the same rule, from opposite directions: advertise a control
 * capability only where a machine exists that can honour it.
 */
class SensorOnlyPresentationTest {

    /** What `BtEngine.sensorOnlyProviderCapabilities()` builds: settled, and supporting nothing. */
    private val sensorOnly = BtEngineState.FtmsCapabilities(
        featuresRead = true,
        supportsSpeed = false,
        supportsInclination = false,
        supportsResistance = false,
        supportsPower = false,
    )

    @Test
    fun `a sensor-only session advertises no target-setting capability at all`() {
        val targets = TargetCapabilities.fromEngineCapabilities(sensorOnly)

        assertEquals(
            "a watch must not be offered control of a machine that does not exist",
            0,
            targets.toTargetFeatureBits(),
        )
        assertFalse(targets.any)
        assertFalse(targets.speed)
        assertFalse(targets.inclination)
        assertFalse(targets.resistance)
        assertFalse(targets.power)
    }

    @Test
    fun `and no supported range is published either`() {
        // A range without a capability bit is not merely redundant - the Supported-* reads
        // would answer with numbers describing nothing.
        val targets = TargetCapabilities.fromEngineCapabilities(sensorOnly)

        assertNull(targets.speedRange)
        assertNull(targets.inclinationRange)
        assertNull(targets.resistanceRange)
        assertNull(targets.powerRange)
    }

    @Test
    fun `a real machine still advertises its own targets`() {
        // The counterpart: this must not become "never advertise targets". These are the
        // ranges the TMIW treadmill actually reported on 2026-08-07 - 0x2AD4 speed 0..3000
        // step 10, 0x2AD5 incline -100..300 step 5 - in raw spec units.
        val machine = BtEngineState.FtmsCapabilities(
            featuresRead = true,
            supportsSpeed = true,
            supportsInclination = true,
            speedMin = 0f, speedMax = 3000f, speedIncr = 10f, speedRangeKnown = true,
            inclineMin = -100f, inclineMax = 300f, inclineIncr = 5f, inclineRangeKnown = true,
        )

        val targets = TargetCapabilities.fromEngineCapabilities(machine)

        assertTrue(targets.speed)
        assertTrue(targets.inclination)
        assertTrue(targets.toTargetFeatureBits() != 0)
        assertEquals(0, targets.speedRange?.minimum)
        assertEquals(3000, targets.speedRange?.maximum)
        assertEquals(-100, targets.inclinationRange?.minimum)
        assertEquals(300, targets.inclinationRange?.maximum)
    }

    @Test
    fun `capabilities with no machine read are not mistaken for settled ones`() {
        // The default FtmsCapabilities - nothing read yet - must stay distinguishable from the
        // sensor-only value. They differ only in featuresRead, and that flag is what stops the
        // advertising loop waiting forever for a machine that is not coming.
        val nothingRead = BtEngineState.FtmsCapabilities()

        assertFalse(nothingRead.featuresRead)
        assertTrue(sensorOnly.featuresRead)
        assertEquals(
            "both advertise nothing; only their readiness differs",
            TargetCapabilities.fromEngineCapabilities(nothingRead).toTargetFeatureBits(),
            TargetCapabilities.fromEngineCapabilities(sensorOnly).toTargetFeatureBits(),
        )
    }

    @Test
    fun `RSC alone makes TREADMILL the offered sensor-only mode`() {
        // The other half of the criterion: the session must be reachable before it can present.
        assertEquals(
            listOf(ActivityType.TREADMILL),
            sensorOnlySessionModes(setOf(ConnectedServiceTag.RSC)),
        )
        // A foot pod beside a strap is still a treadmill session, not a heart-rate one.
        assertEquals(
            listOf(ActivityType.TREADMILL),
            sensorOnlySessionModes(setOf(ConnectedServiceTag.RSC, ConnectedServiceTag.HRM)),
        )
    }
}
