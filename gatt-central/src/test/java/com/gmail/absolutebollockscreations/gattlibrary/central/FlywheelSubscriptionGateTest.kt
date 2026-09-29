package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FlywheelSubscriptionGateTest {
    @Test
    fun `setup readiness accepts only the owning generation before its deadline`() {
        val current = connection(generation = 8)
        val stale = connection(generation = 7)
        val gate = FlywheelSubscriptionGate(current, timeoutMs = 1_000)

        assertTrue(gate.begin(nowMs = 100))
        assertFalse(gate.markReady(stale, nowMs = 200))
        assertEquals(FlywheelSetupState.SUBSCRIBING, gate.state)
        assertTrue(gate.markReady(current, nowMs = 999))
        assertEquals(FlywheelSetupState.READY, gate.state)
        assertFalse(gate.markReady(current, nowMs = 1_000))
    }

    @Test
    fun `setup timeout is bounded and cannot become ready afterward`() {
        val id = connection(generation = 1)
        val gate = FlywheelSubscriptionGate(id, timeoutMs = 500)
        gate.begin(nowMs = 1_000)

        assertFalse(gate.tick(nowMs = 1_499))
        assertTrue(gate.tick(nowMs = 1_500))
        assertEquals(FlywheelSetupState.FAILED, gate.state)
        assertFalse(gate.markReady(id, nowMs = 1_501))
        assertFalse(gate.tick(nowMs = Long.MAX_VALUE))
    }

    @Test
    fun `teardown cancels setup and reconnect uses fresh generation state`() {
        val first = FlywheelSubscriptionGate(connection(1), timeoutMs = 1_000)
        first.begin(0)
        first.cancel()
        assertEquals(FlywheelSetupState.CANCELLED, first.state)
        assertFalse(first.markReady(connection(1), 1))

        val second = FlywheelSubscriptionGate(connection(2), timeoutMs = 1_000)
        assertNotSame(first, second)
        assertEquals(FlywheelSetupState.IDLE, second.state)
        assertTrue(second.begin(10))
        assertTrue(second.markReady(connection(2), 11))
        assertEquals(FlywheelSetupState.READY, second.state)
    }

    private fun connection(generation: Long) = ConnectionId("AA:BB:CC:DD:EE:12", generation)
}
