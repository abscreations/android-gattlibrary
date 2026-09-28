package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlywheelRecoveryTest {
    private val target = LinkRecoveryTarget(
        recoveryKey = recoveryKey("AA:BB:CC:DD:EE:FF", machineConnection = true),
        address = "AA:BB:CC:DD:EE:FF",
        machineConnection = true,
        activeServices = setOf(BtEngineState.BtServiceType.FTMS),
    )

    @Test
    fun `Flywheel unexpected disconnect uses generic bounded recovery`() {
        val recovery = InSessionLinkRecovery()
        recovery.registerSelection(target)

        val first = recovery.onUnexpectedDisconnect(target.recoveryKey, 10_000L)!!
        val second = recovery.onAttemptFailed(first, first.dueAtMs)!!
        val third = recovery.onAttemptFailed(second, second.dueAtMs)!!

        assertEquals(listOf(1, 2, 3), listOf(first.attempt, second.attempt, third.attempt))
        assertEquals(listOf(11_000L, 13_500L, 18_500L), listOf(first.dueAtMs, second.dueAtMs, third.dueAtMs))
        assertNull(recovery.onAttemptFailed(third, third.dueAtMs))
    }

    @Test
    fun `deselection and session end cancel Flywheel recovery`() {
        val recovery = InSessionLinkRecovery()
        recovery.registerSelection(target)
        val pending = recovery.onUnexpectedDisconnect(target.recoveryKey, 0L)!!
        recovery.cancelTarget(target.recoveryKey)
        assertFalse(recovery.shouldRun(pending))

        recovery.registerSelection(target)
        val replacement = recovery.onUnexpectedDisconnect(target.recoveryKey, 1_000L)!!
        assertTrue(recovery.shouldRun(replacement))
        recovery.endSession()
        assertFalse(recovery.shouldRun(replacement))
    }
}
