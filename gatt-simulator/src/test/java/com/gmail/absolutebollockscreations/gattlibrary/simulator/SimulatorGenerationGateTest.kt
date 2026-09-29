package com.gmail.absolutebollockscreations.gattlibrary.simulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatorGenerationGateTest {
    @Test
    fun ordinary_persona_is_ready_at_subscription() {
        assertFalse(simulatorGenerationReady(false, false, false, false))
        assertTrue(simulatorGenerationReady(false, false, true, false))
    }

    @Test
    fun kayak_first_requires_subscription_and_completed_display_setup() {
        assertFalse(simulatorGenerationReady(false, true, false, false))
        assertFalse(simulatorGenerationReady(false, true, false, true))
        assertFalse(simulatorGenerationReady(false, true, true, false))
        assertTrue(simulatorGenerationReady(false, true, true, true))
    }

    @Test
    fun keiser_is_ready_without_a_gatt_subscription() {
        assertTrue(simulatorGenerationReady(true, false, false, false))
    }

    @Test
    fun kayak_first_is_idle_at_setup_ready_and_advances_only_while_started() {
        assertEquals(
            KayakFirstGenerationState(ready = false, advancing = false),
            kayakFirstGenerationState(subscribed = true, setupStage = "handshake", running = false),
        )
        assertEquals(
            KayakFirstGenerationState(ready = true, advancing = false),
            kayakFirstGenerationState(subscribed = true, setupStage = "ready", running = false),
        )
        assertEquals(
            KayakFirstGenerationState(ready = true, advancing = true),
            kayakFirstGenerationState(subscribed = true, setupStage = "ready", running = true),
        )
        assertEquals(
            KayakFirstGenerationState(ready = false, advancing = false),
            kayakFirstGenerationState(subscribed = false, setupStage = "ready", running = true),
        )
    }

    @Test
    fun generation_stays_idle_until_subscription_then_remains_armed() {
        val gate = SimulatorGenerationGate()

        assertFalse(gate.observe(subscribed = false))
        assertTrue(gate.observe(subscribed = true))
        assertTrue(gate.observe(subscribed = false))
    }

    @Test
    fun reset_requires_a_new_subscription() {
        val gate = SimulatorGenerationGate()
        gate.observe(subscribed = true)

        gate.reset()

        assertFalse(gate.observe(subscribed = false))
    }
}
