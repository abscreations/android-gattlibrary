package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit 4b step 2a: pins the units of the capability snapshot before it is published.
 *
 * `FtmsCapabilities` holds the ranges in raw FTMS wire units (speed 0.01 km/h, incline 0.1 %,
 * resistance 0.1 level, power W), while the legacy fields on `BtEngineState` hold the same ranges
 * in display units (km/h, %, level, W). The public `DeviceCapabilities` will be in the latter, so
 * the conversion between the two has to be exact.
 */
class CapabilityUnitsCharacterizationTest {

    private fun u16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun range(min: Int, max: Int, inc: Int) = u16(min) + u16(max) + u16(inc)

    @Test
    fun `features claim controls, indoor bike simulation, and pace, and ignore spin down in the snapshot`() {
        val state = BtEngineState()
        // measurement word bit 5 = pace; target word: speed|incline|resistance|power, bit 13 sim, bit 15 spin down
        val target = 0x0F or (1 shl 13) or (1 shl 15)
        val features = byteArrayOf(
            0x20, 0, 0, 0,
            (target and 0xFF).toByte(), ((target shr 8) and 0xFF).toByte(), 0, 0,
        )
        val flags = BtEngineCapabilities.applyFitnessMachineFeatures(state, features)!!
        assertTrue(flags.supportsSpeed && flags.supportsInclination && flags.supportsResistance && flags.supportsPower)
        val caps = state.ftmsCapabilities
        assertTrue(caps.featuresRead)
        assertTrue(caps.supportsSpeed && caps.supportsInclination && caps.supportsResistance && caps.supportsPower)
        assertTrue(caps.supportsIndoorBikeSimulation)
        assertTrue(state.supportsPaceMeasurement)
        assertTrue(state.supportsSpinDown)
        assertFalse(caps.supportsWheelCircumference)
        assertFalse(caps.speedRangeKnown)
    }

    @Test
    fun `a short features payload changes nothing`() {
        val state = BtEngineState()
        assertNull(BtEngineCapabilities.applyFitnessMachineFeatures(state, byteArrayOf(0, 0, 0, 0)))
        assertFalse(state.ftmsCapabilities.featuresRead)
    }

    @Test
    fun `speed range is raw 0_01 km per h in the snapshot and km per h in the legacy fields`() {
        val state = BtEngineState()
        BtEngineCapabilities.applySupportedSpeedRange(state, range(100, 2_000, 10))
        val c = state.ftmsCapabilities
        assertEquals(100f, c.speedMin!!, 0f)
        assertEquals(2_000f, c.speedMax!!, 0f)
        assertEquals(10f, c.speedIncr!!, 0f)
        assertTrue(c.speedRangeKnown)
        assertEquals(1.0f, state.speedMin, 0f)
        assertEquals(20.0f, state.speedMax, 0f)
        assertEquals(0.1f, state.speedIncr, 0f)
    }

    @Test
    fun `incline range is raw 0_1 percent, signed, in the snapshot`() {
        val state = BtEngineState()
        BtEngineCapabilities.applySupportedInclinationRange(state, range(0xFFE2, 150, 5)) // -30
        val c = state.ftmsCapabilities
        assertEquals(-30f, c.inclineMin!!, 0f)
        assertEquals(150f, c.inclineMax!!, 0f)
        assertEquals(5f, c.inclineIncr!!, 0f)
        assertEquals(-3.0f, state.inclineMin, 0f)
        assertEquals(15.0f, state.inclineMax, 0f)
        assertEquals(0.5f, state.inclineIncr, 0f)
    }

    @Test
    fun `resistance range is raw 0_1 level in the snapshot`() {
        val state = BtEngineState()
        BtEngineCapabilities.applySupportedResistanceRange(state, range(10, 1_000, 10))
        val c = state.ftmsCapabilities
        assertEquals(10f, c.resistanceMin!!, 0f)
        assertEquals(1_000f, c.resistanceMax!!, 0f)
        assertEquals(10f, c.resistanceIncr!!, 0f)
        assertEquals(1.0f, state.resistanceMin, 0f)
        assertEquals(100.0f, state.resistanceMax, 0f)
        assertEquals(1.0f, state.resistanceIncr, 0f)
    }

    @Test
    fun `power range is watts in both`() {
        val state = BtEngineState()
        BtEngineCapabilities.applySupportedPowerRange(state, range(0, 2_000, 1))
        val c = state.ftmsCapabilities
        assertEquals(0f, c.powerMin!!, 0f)
        assertEquals(2_000f, c.powerMax!!, 0f)
        assertEquals(1f, c.powerIncr!!, 0f)
        assertEquals(2_000f, state.powerMax, 0f)
    }

    @Test
    fun `a reversed range is swapped`() {
        val state = BtEngineState()
        BtEngineCapabilities.applySupportedSpeedRange(state, range(2_000, 100, 10))
        assertEquals(100f, state.ftmsCapabilities.speedMin!!, 0f)
        assertEquals(2_000f, state.ftmsCapabilities.speedMax!!, 0f)
    }

    @Test
    fun `a short range payload changes nothing`() {
        val state = BtEngineState()
        BtEngineCapabilities.applySupportedPowerRange(state, byteArrayOf(0, 0, 1, 0, 1))
        assertFalse(state.ftmsCapabilities.powerRangeKnown)
        assertNull(state.ftmsCapabilities.powerMax)
    }

    @Test
    fun `snapshot is merge-only until reset`() {
        val state = BtEngineState()
        BtEngineCapabilities.applySupportedPowerRange(state, range(0, 2_000, 1))
        BtEngineCapabilities.applyFitnessMachineFeatures(state, byteArrayOf(0, 0, 0, 0, 0x01, 0, 0, 0))
        // a later features read does not erase the range already known
        assertTrue(state.ftmsCapabilities.powerRangeKnown)
        assertEquals(2_000f, state.ftmsCapabilities.powerMax!!, 0f)

        state.resetFtmsCapabilities()
        assertFalse(state.ftmsCapabilities.featuresRead)
        assertNull(state.ftmsCapabilities.powerMax)
        assertFalse(state.supportsPaceMeasurement)
    }

    @Test
    fun `a non-finite or oversized increment is clamped in the snapshot`() {
        val state = BtEngineState()
        BtEngineCapabilities.applySupportedSpeedRange(state, range(100, 2_000, 0xFFFF))
        assertEquals(65_535f, state.ftmsCapabilities.speedIncr!!, 0f)
    }
}
