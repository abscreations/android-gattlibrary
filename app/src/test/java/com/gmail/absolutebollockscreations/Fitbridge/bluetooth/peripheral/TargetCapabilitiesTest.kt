package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtEngineState
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtServiceProfiles.FtmsMachineProfile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetCapabilitiesTest {

    @Test
    fun `a target bit requires a valid range of the correct wire width`() {
        val t = FtmsSpec.TargetFeature
        assertEquals(0, TargetCapabilities(speed = true).toTargetFeatureBits())
        assertEquals(
            0,
            TargetCapabilities(speed = true, speedRange = FtmsTargetRange(-1, 100, 1))
                .toTargetFeatureBits(),
        )
        assertTrue(
            TargetCapabilities(speed = true, speedRange = FtmsTargetRange(0, 100, 10))
                .toTargetFeatureBits() and (1 shl t.SPEED) != 0,
        )
    }

    /**
     * Every Supported-* Range must be republished in the units it was read in.
     *
     * [BtEngineState.FtmsCapabilities] stores raw spec units, and the
     * characteristics use the same units, so the bridge is a pass-through.
     * Inclination and speed used to rescale as though the source were in public
     * units while resistance and power did not.
     *
     * Values are the TrackMyIndoorWorkout simulator's, read off the wire on
     * 2026-08-06: speed `0000b80b0a00`, inclination `9cff2c010500`,
     * resistance `0a002c010a00`, power `0100e8030100`.
     */
    private val simulator = BtEngineState.FtmsCapabilities(
        featuresRead = true,
        supportsSpeed = true, speedMin = 0f, speedMax = 3000f, speedIncr = 10f,
        speedRangeKnown = true,
        supportsInclination = true, inclineMin = -100f, inclineMax = 300f, inclineIncr = 5f,
        inclineRangeKnown = true,
        supportsResistance = true, resistanceMin = 10f, resistanceMax = 300f, resistanceIncr = 10f,
        resistanceRangeKnown = true,
        supportsPower = true, powerMin = 1f, powerMax = 1000f, powerIncr = 1f,
        powerRangeKnown = true,
    )

    @Test
    fun `every supported range is republished in the units it was read in`() {
        val caps = TargetCapabilities.fromEngineCapabilities(simulator)

        assertEquals(FtmsTargetRange(0, 3000, 10), caps.speedRange)
        assertEquals(FtmsTargetRange(-100, 300, 5), caps.inclinationRange)
        assertEquals(FtmsTargetRange(10, 300, 10), caps.resistanceRange)
        assertEquals(FtmsTargetRange(1, 1000, 1), caps.powerRange)
    }

    @Test
    fun `the inclination a watch can request matches the machine's real limits`() {
        // Before the fix this advertised -100.0..300.0 % in 5.0 % steps. A watch
        // obeying that snapped a requested 1.0 % to 0.0 and 3.0 % to 5.0, and
        // let the user scroll to 110.0 % because the maximum claimed 300 %.
        // FitBridge then clamped 110.0 to the machine's real 30.0 on the way
        // upstream, which is the 1.0 -> 0.0, 3.0 -> 5.0, 10.0 -> 30.0 round trip
        // that was reported. Resistance was unaffected because it was never
        // rescaled, which is why only incline appeared broken.
        val incline = TargetCapabilities.fromEngineCapabilities(simulator).inclinationRange!!

        assertEquals("minimum is -10.0 %", -100, incline.minimum)
        assertEquals("maximum is 30.0 %", 300, incline.maximum)
        assertEquals("step is 0.5 %", 5, incline.increment)

        // 1.0 % and 3.0 % are now reachable exactly rather than snapping away.
        assertEquals(10, incline.clampAndSnap(10))
        assertEquals(30, incline.clampAndSnap(30))
        assertEquals(100, incline.clampAndSnap(100))
        // 110.0 % is no longer offerable at all; it clamps to the real maximum.
        assertEquals(300, incline.clampAndSnap(1100))
    }

    @Test
    fun `the speed cap is applied in wire units, not kilometres per hour`() {
        // A raw 3000 is 30.00 km/h. Compared against a bare 60 it looked like a
        // 3000 km/h machine, was clamped to 60, then multiplied by 100 - so a
        // 30 km/h machine was advertised as capable of 60.00 km/h.
        assertEquals(3000, TargetCapabilities.fromEngineCapabilities(simulator).speedRange!!.maximum)

        val absurd = simulator.copy(speedMax = 99_000f)
        assertEquals(6000, TargetCapabilities.fromEngineCapabilities(absurd).speedRange!!.maximum)
    }

    @Test
    fun `supported resistance range remains three signed 16 bit values`() {
        val range = FtmsTargetRange(-25, 250, 5)
        val payload = range.encodeS16()
        assertEquals(6, payload.size)
        assertEquals(0xE7, payload[0].toInt() and 0xFF)
        assertEquals(0xFF, payload[1].toInt() and 0xFF)
    }
}
