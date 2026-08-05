package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkRecoveryTest {
    private val target = LinkRecoveryTarget(
        recoveryKey = "sensor:AA:BB",
        address = "AA:BB",
        machineConnection = false,
        activeServices = setOf(BtEngineState.BtServiceType.HeartRate),
    )

    @Test
    fun `unintentional recovery is bounded and follows configured backoff`() {
        val recovery = InSessionLinkRecovery(backoffMs = listOf(100, 200, 400))
        recovery.beginSession(listOf(target))

        val first = recovery.onUnexpectedDisconnect(target.recoveryKey, nowMs = 1_000)!!
        assertEquals(1, first.attempt)
        assertEquals(1_100, first.dueAtMs)
        assertTrue(recovery.shouldRun(first))

        val second = recovery.onAttemptFailed(first, nowMs = 1_100)!!
        assertEquals(2, second.attempt)
        assertEquals(1_300, second.dueAtMs)
        assertFalse(recovery.shouldRun(first))

        val third = recovery.onAttemptFailed(second, nowMs = 1_300)!!
        assertEquals(3, third.attempt)
        assertEquals(1_700, third.dueAtMs)
        assertNull(recovery.onAttemptFailed(third, nowMs = 1_700))
        assertNull(recovery.onUnexpectedDisconnect(target.recoveryKey, nowMs = 2_000))
    }

    @Test
    fun `successful reconnect resets the bounded attempt sequence`() {
        val recovery = InSessionLinkRecovery(backoffMs = listOf(10, 20))
        recovery.registerSelection(target)
        val first = recovery.onUnexpectedDisconnect(target.recoveryKey, 0)!!

        recovery.onConnected(target.recoveryKey)
        assertFalse(recovery.shouldRun(first))
        assertEquals(1, recovery.onUnexpectedDisconnect(target.recoveryKey, 100)!!.attempt)
    }

    @Test
    fun `user disconnect and session end prevent every retry`() {
        val recovery = InSessionLinkRecovery(backoffMs = listOf(10))
        recovery.registerSelection(target)
        val pending = recovery.onUnexpectedDisconnect(target.recoveryKey, 0)!!

        recovery.cancelTarget(target.recoveryKey)
        assertFalse(recovery.shouldRun(pending))
        assertNull(recovery.onUnexpectedDisconnect(target.recoveryKey, 20))

        recovery.registerSelection(target)
        val secondPending = recovery.onUnexpectedDisconnect(target.recoveryKey, 30)!!
        recovery.endSession()
        assertFalse(recovery.isSessionActive())
        assertFalse(recovery.shouldRun(secondPending))
        assertNull(recovery.onUnexpectedDisconnect(target.recoveryKey, 50))
    }
}
