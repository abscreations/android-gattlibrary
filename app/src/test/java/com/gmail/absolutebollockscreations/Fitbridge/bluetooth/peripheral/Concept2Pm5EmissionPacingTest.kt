package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Concept2Pm5EmissionPacingTest {
    @Test
    fun `unknown interval keeps the selected PM5 sample period`() {
        val pacing = Concept2Pm5EmissionPacing()

        assertEquals(250L, pacing.effectivePeriodMs(250L))
        assertEquals(0L, pacing.negotiatedIntervalMs)
    }

    @Test
    fun `reported Garmin interval floors transmission without changing sample period`() {
        val pacing = Concept2Pm5EmissionPacing()

        assertTrue(pacing.recordNegotiated(intervalUnits = 792, latency = 0, timeoutUnits = 400))
        assertEquals(990L, pacing.negotiatedIntervalMs)
        assertEquals(4_000L, pacing.supervisionTimeoutMs)
        assertEquals(990L, pacing.effectivePeriodMs(selectedSamplePeriodMs = 250L))
    }

    @Test
    fun `faster reported link does not override selected sample period`() {
        val pacing = Concept2Pm5EmissionPacing()

        assertTrue(pacing.recordNegotiated(intervalUnits = 24, latency = 0, timeoutUnits = 400))
        assertEquals(30L, pacing.negotiatedIntervalMs)
        assertEquals(250L, pacing.effectivePeriodMs(selectedSamplePeriodMs = 250L))
    }

    @Test
    fun `invalid report is ignored rather than converted into a heuristic`() {
        val pacing = Concept2Pm5EmissionPacing()

        assertFalse(pacing.recordNegotiated(intervalUnits = 0, latency = 0, timeoutUnits = 400))
        assertFalse(pacing.recordNegotiated(intervalUnits = 792, latency = 500, timeoutUnits = 400))
        assertFalse(pacing.recordNegotiated(intervalUnits = 792, latency = 0, timeoutUnits = 0))
        assertEquals(0L, pacing.negotiatedIntervalMs)
        assertEquals(250L, pacing.effectivePeriodMs(250L))
    }

    @Test
    fun `transactional response goes ahead of queued measurements`() {
        val queue = ArrayDeque<String>()
        enqueuePm5ByPriority(queue, "measurement-1", transactional = false)
        enqueuePm5ByPriority(queue, "measurement-2", transactional = false)

        enqueuePm5ByPriority(queue, "csafe-response", transactional = true)

        assertEquals(listOf("csafe-response", "measurement-1", "measurement-2"), queue.toList())
    }
}
