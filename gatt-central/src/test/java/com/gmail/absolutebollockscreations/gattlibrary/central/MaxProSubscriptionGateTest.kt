package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.*
import org.junit.Test

class MaxProSubscriptionGateTest {
    @Test fun `only confirmed CCCD can arm readiness and the delayed one shot version query`() {
        val gate = MaxProSubscriptionGate(0)
        assertFalse(gate.subscriptionsReady(1))
        assertFalse(gate.takeFirmwareQuery(5000))
        gate.descriptorCompleted(true)
        assertTrue(gate.subscriptionsReady(100))
        assertFalse(gate.subscriptionsReady(200))
        assertFalse(gate.takeFirmwareQuery(2099))
        assertTrue(gate.takeFirmwareQuery(2100))
        assertFalse(gate.takeFirmwareQuery(2200))
        assertFalse(gate.timedOut(100_000))
    }

    @Test fun `subscription failure and timeout cannot send startup commands`() {
        val gate = MaxProSubscriptionGate(0)
        gate.descriptorCompleted(false)
        assertFalse(gate.subscriptionsReady(100))
        assertFalse(gate.timedOut(9999))
        assertTrue(gate.timedOut(10_000))
        gate.descriptorCompleted(true)
        assertFalse(gate.subscriptionsReady(10_001))
        assertFalse(gate.takeFirmwareQuery(20_000))
    }

    @Test fun `a failed descriptor cancels query and resubscription never repeats an attempted query`() {
        val gate = MaxProSubscriptionGate(0)
        gate.descriptorCompleted(true)
        assertTrue(gate.subscriptionsReady(100))
        gate.descriptorCompleted(false)
        assertFalse(gate.takeFirmwareQuery(2100))
        gate.descriptorCompleted(true)
        assertTrue(gate.subscriptionsReady(2200))
        assertTrue(gate.takeFirmwareQuery(4200))
        gate.descriptorCompleted(false)
        gate.descriptorCompleted(true)
        assertTrue(gate.subscriptionsReady(4300))
        assertFalse(gate.takeFirmwareQuery(6300))
        val replacement = MaxProSubscriptionGate(6400)
        assertFalse(replacement.ready)
        assertFalse(replacement.takeFirmwareQuery(9000))
    }
}
