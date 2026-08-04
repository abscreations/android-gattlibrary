package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GattDiscoveryAttemptGateTest {
    @Test
    fun `only one service discovery attempt can be in flight`() {
        val gate = GattDiscoveryAttemptGate(maxAttempts = 3)

        assertEquals(1, gate.tryStartAttempt())
        assertNull(gate.tryStartAttempt())

        assertTrue(gate.releaseForRetry())
        assertEquals(2, gate.tryStartAttempt())
    }

    @Test
    fun `service discovery retries stop at the configured limit`() {
        val gate = GattDiscoveryAttemptGate(maxAttempts = 3)

        assertEquals(1, gate.tryStartAttempt())
        assertTrue(gate.releaseForRetry())
        assertEquals(2, gate.tryStartAttempt())
        assertTrue(gate.releaseForRetry())
        assertEquals(3, gate.tryStartAttempt())

        assertFalse(gate.releaseForRetry())
        assertNull(gate.tryStartAttempt())
    }

    @Test
    fun `completion blocks retries until the gate is reset`() {
        val gate = GattDiscoveryAttemptGate(maxAttempts = 3)

        assertEquals(1, gate.tryStartAttempt())
        gate.complete()
        assertFalse(gate.releaseForRetry())
        assertNull(gate.tryStartAttempt())

        gate.reset()
        assertEquals(1, gate.tryStartAttempt())
    }
}
