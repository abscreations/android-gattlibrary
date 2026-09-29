package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per Unit 9.8 of `docs/fe-c-ble_central_implementation.md`, updated by the 2026-08-20 FE-C <->
 * FTMS control handshake review: [FeCCapabilities.publish] claims a known Supported-* range only
 * when FEC3 exists and page 54 advertises that specific mode. The range is the ANT+ FE-C page's
 * encodable domain, not page 54's device-specific maximum-resistance-in-newtons value.
 */
class FeCCapabilitiesTest {

    private val allModes = FeCPage.FeCapabilities(1_200, true, true, true)

    @Test
    fun `control available publishes resistance, power, inclination and simulation support`() {
        val state = BtEngineState()
        FeCCapabilities.publish(state, controlAvailable = true, page = allModes)

        assertTrue(state.featuresRead)
        assertTrue(state.supportsResistance)
        assertTrue(state.supportsPower)
        assertTrue(state.supportsIndoorBikeSimulation)
        assertTrue(state.supportsInclination)
    }

    /**
     * FE-C truly has no speed-target page - unlike inclination, there is nothing for
     * [FeCCapabilities.publish]'s `controlAvailable` to unlock here.
     */
    @Test
    fun `control available still declines speed - no page exists for it at all`() {
        val state = BtEngineState()
        FeCCapabilities.publish(state, controlAvailable = true, page = allModes)

        assertFalse(state.supportsSpeed)
    }

    @Test
    fun `no control channel publishes every control flag false and every range unknown`() {
        val state = BtEngineState()
        FeCCapabilities.publish(state, controlAvailable = false)

        assertTrue(state.featuresRead)
        assertFalse(state.supportsResistance)
        assertFalse(state.supportsPower)
        assertFalse(state.supportsIndoorBikeSimulation)
        assertFalse(state.supportsSpeed)
        assertFalse(state.supportsInclination)
        assertFalse(state.resistanceRangeKnown)
        assertFalse(state.powerRangeKnown)
        assertFalse(state.inclineRangeKnown)
    }

    /**
     * The exact spec-defined bounds this project can now honestly advertise downstream: page 48
     * (0-100%, 0.5% steps -> FTMS's 0.1-level-per-unit raw units), page 49 (0-4000 W, FTMS's own
     * Target Power characteristic is 1 W/unit regardless of FE-C's finer 0.25 W step), and page
     * 51's Grade field (-200.00%..+200.00% -> FTMS's 0.1%-per-unit raw units).
     */
    @Test
    fun `control available claims the spec's own encodable bounds, in FTMS raw units`() {
        val state = BtEngineState()
        FeCCapabilities.publish(state, controlAvailable = true, page = allModes)

        assertTrue(state.resistanceRangeKnown)
        assertEquals(0f, state.resistanceMin)
        assertEquals(1000f, state.resistanceMax)
        assertEquals(5f, state.resistanceIncr)

        assertTrue(state.powerRangeKnown)
        assertEquals(0f, state.powerMin)
        assertEquals(4000f, state.powerMax)
        assertEquals(1f, state.powerIncr)

        assertTrue(state.inclineRangeKnown)
        assertEquals(-2000f, state.inclineMin)
        assertEquals(2000f, state.inclineMax)
        assertEquals(1f, state.inclineIncr)
    }

    /** The same bounds, reachable through the merged snapshot [TargetCapabilities] actually reads. */
    @Test
    fun `the merged ftmsCapabilities snapshot carries the same bounds`() {
        val state = BtEngineState()
        FeCCapabilities.publish(state, controlAvailable = true, page = allModes)

        val caps = state.ftmsCapabilities
        assertTrue(caps.supportsResistance)
        assertTrue(caps.resistanceRangeKnown)
        assertEquals(1000f, caps.resistanceMax)
        assertTrue(caps.supportsPower)
        assertTrue(caps.powerRangeKnown)
        assertEquals(4000f, caps.powerMax)
        assertTrue(caps.supportsInclination)
        assertTrue(caps.inclineRangeKnown)
        assertEquals(2000f, caps.inclineMax)
    }

    @Test
    fun `FEC3 alone leaves controls unresolved until page 54 arrives`() {
        val state = BtEngineState()
        FeCCapabilities.publish(state, controlAvailable = true)

        assertFalse(state.featuresRead)
        assertFalse(state.supportsResistance)
        assertFalse(state.supportsPower)
        assertFalse(state.supportsInclination)
    }

    @Test
    fun `page 54 gates each control mode independently`() {
        val state = BtEngineState()
        FeCCapabilities.publish(
            state,
            controlAvailable = true,
            page = FeCPage.FeCapabilities(700, true, false, false),
        )

        assertTrue(state.featuresRead)
        assertTrue(state.supportsResistance)
        assertFalse(state.supportsPower)
        assertFalse(state.supportsIndoorBikeSimulation)
        assertFalse(state.supportsInclination)
    }
}
