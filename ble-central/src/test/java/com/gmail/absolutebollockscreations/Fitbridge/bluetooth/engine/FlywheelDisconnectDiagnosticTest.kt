package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.capture.CaptureTrigger
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.capture.BleCapture
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlywheelDisconnectDiagnosticTest {
    private val generation = ConnectionId("AA:BB:CC:DD:EE:FF", 7L)

    @Before
    fun setUp() = BleCapture.resetSession()

    @After
    fun tearDown() = BleCapture.resetSession()

    @Test
    fun `quirk window is inclusive only from 25 through 35 seconds`() {
        val cases = listOf(
            24_999L to false,
            25_000L to true,
            35_000L to true,
            35_001L to false,
        )

        cases.forEach { (elapsed, expected) ->
            val diagnostic = activatedDiagnostic(startedAtMs = 10_000L)
            val result = diagnostic.diagnose(
                generation,
                "platform disconnect status=8",
                10_000L + elapsed,
            )
            assertEquals("elapsed=$elapsed", expected, result.matchesConnectionIntervalQuirk)
            assertEquals("elapsed=$elapsed", expected, result.firstReportForGeneration)
            assertEquals(elapsed, result.elapsedMs)
        }
    }

    @Test
    fun `classification requires activation unexpected disconnect and current generation`() {
        val inactive = FlywheelDisconnectDiagnostic().also { it.begin(generation, 0L) }
        assertFalse(inactive.diagnose(generation, "platform disconnect status=8", 30_000L)
            .matchesConnectionIntervalQuirk)

        val active = activatedDiagnostic(0L)
        assertFalse(active.diagnose(generation, "user disconnect", 30_000L)
            .matchesConnectionIntervalQuirk)
        val stale = active.diagnose(generation.copy(linkGeneration = 6L), "platform disconnect status=8", 30_000L)
        assertFalse(stale.matchesConnectionIntervalQuirk)
        assertNull(stale.elapsedMs)
    }

    @Test
    fun `one generation reports once and replacement generation is independent`() {
        val diagnostic = activatedDiagnostic(0L)
        assertTrue(diagnostic.diagnose(generation, "platform disconnect status=8", 30_000L)
            .firstReportForGeneration)
        val repeated = diagnostic.diagnose(generation, "platform disconnect status=8", 30_001L)
        assertTrue(repeated.matchesConnectionIntervalQuirk)
        assertFalse(repeated.firstReportForGeneration)

        val replacement = generation.copy(linkGeneration = 8L)
        diagnostic.begin(replacement, 40_000L)
        diagnostic.markActivated(replacement)
        assertFalse(diagnostic.diagnose(generation, "platform disconnect status=8", 70_000L)
            .matchesConnectionIntervalQuirk)
        assertTrue(diagnostic.diagnose(replacement, "platform disconnect status=8", 70_000L)
            .firstReportForGeneration)
    }

    @Test
    fun `Flywheel trigger has stable redacted wire name`() {
        assertEquals(
            "flywheel_connection_interval_quirk",
            CaptureTrigger.FLYWHEEL_CONNECTION_INTERVAL_QUIRK.wireName,
        )
        assertFalse(CaptureTrigger.FLYWHEEL_CONNECTION_INTERVAL_QUIRK.wireName.contains("AA:BB"))
    }

    @Test
    fun `first diagnosis arms dedicated capture and repeat does not arm again`() {
        val diagnostic = activatedDiagnostic(0L)
        val first = diagnostic.diagnose(generation, "platform disconnect status=8", 30_000L)
        val repeated = diagnostic.diagnose(generation, "platform disconnect status=8", 30_001L)

        assertTrue(armFlywheelDisconnectCapture(first))
        assertEquals(CaptureTrigger.FLYWHEEL_CONNECTION_INTERVAL_QUIRK, BleCapture.armedBy())
        assertFalse(armFlywheelDisconnectCapture(repeated))
    }

    private fun activatedDiagnostic(startedAtMs: Long) = FlywheelDisconnectDiagnostic().also {
        it.begin(generation, startedAtMs)
        assertTrue(it.markActivated(generation))
    }
}
