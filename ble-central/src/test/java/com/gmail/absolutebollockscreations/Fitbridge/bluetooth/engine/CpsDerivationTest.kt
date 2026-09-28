package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CpsDerivationTest {
    @Test
    fun `wheel uses 2048 Hz while crank uses 1024 Hz`() {
        val tracker = CpsMeasurementTracker()
        val derivation = CpsDerivationState()
        tracker.accept(hex("30 00 64 00 64 00 00 00 00 08 C8 00 00 04"))
        val update = tracker.accept(hex("30 00 65 00 66 00 00 00 00 10 CA 00 00 08"))!!
        val metrics = derivation.derive(update, wheelCircumferenceMm = 2_100)
        assertEquals(15.12, metrics.single { it.metric == MetricKind.SPEED }.value, 0.0001)
        assertEquals(120.0, metrics.single { it.metric == MetricKind.CADENCE }.value, 0.0001)
        assertEquals(4.2, metrics.single { it.metric == MetricKind.DISTANCE }.value, 0.0001)
    }

    @Test
    fun `implausible event rates emit no derived metric`() {
        val tracker = CpsMeasurementTracker()
        val derivation = CpsDerivationState()
        tracker.accept(hex("30 00 64 00 00 00 00 00 00 00 00 00 00 00"))
        val update = tracker.accept(hex("30 00 64 00 64 00 00 00 01 00 64 00 01 00"))!!
        assertTrue(derivation.derive(update, 2_100).isEmpty())
    }

    @Test
    fun `energy conversion uses CPS kilojoules not raw kilocalories`() {
        assertEquals(23.9005736, cpsKilojoulesToKilocalories(100), 0.000001)
    }

    private fun hex(value: String): ByteArray = value.trim().split(Regex("\\s+"))
        .map { it.toInt(16).toByte() }.toByteArray()
}
