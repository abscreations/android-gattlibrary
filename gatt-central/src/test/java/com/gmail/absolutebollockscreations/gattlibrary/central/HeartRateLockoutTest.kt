package com.gmail.absolutebollockscreations.gattlibrary.central

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A connected strap owns heart rate.
 *
 * ### The defect
 *
 * Reported 2026-08-06: a chest strap reading 54-58 bpm and a TrackMyIndoorWorkout
 * tablet reporting 120-140 were both connected, and the graph alternated between
 * them.
 *
 * Nothing was ranked wrongly. `priorityRank` already places
 * [MetricSourceClass.HRM] above [MetricSourceClass.FTMS], so a fresh strap always
 * won. But ranking only decides between candidates that survive the freshness
 * filter, and `HEART_RATE_FRESHNESS_MS` is 4 s while a BLE strap notifies about
 * once a second. One or two missed notifications dropped the strap out of the
 * contest entirely, and the machine became the best *remaining* candidate until
 * the next strap packet arrived.
 *
 * The fix excludes machine heart rate rather than ranking it lower, so a stale
 * strap cannot hand the metric over.
 */
class HeartRateLockoutTest {

    private val strapConn = ConnectionId("AA:BB:CC:DD:EE:01", 1L)
    private val machineConn = ConnectionId("AA:BB:CC:DD:EE:02", 1L)

    private val strapChar: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val machineChar: UUID = UUID.fromString("00002ace-0000-1000-8000-00805f9b34fb")

    private class Harness {
        val state = BtEngineState()
        val fusion = MetricFusion(state)
    }

    private fun Harness.strap(bpm: Double, atMs: Long) = fusion.submit(
        MetricSample(
            connectionId = strapConn,
            sourceId = MetricSourceId(strapConn, MetricProtocol.HRM, strapChar),
            protocol = MetricProtocol.HRM,
            sourceClass = MetricSourceClass.HRM,
            metric = MetricKind.HEART_RATE,
            arrivalMs = atMs,
            value = bpm,
        ),
    )

    private fun Harness.machine(bpm: Double, atMs: Long) = fusion.submit(
        MetricSample(
            connectionId = machineConn,
            sourceId = MetricSourceId(machineConn, MetricProtocol.FTMS, machineChar),
            protocol = MetricProtocol.FTMS,
            sourceClass = MetricSourceClass.FTMS,
            metric = MetricKind.HEART_RATE,
            arrivalMs = atMs,
            value = bpm,
        ),
    )

    private fun Harness.hr(): Int? = state.heartRate.value

    private fun Harness.series(): List<Double> = state.hrSeries.value.map { it.v }

    @Test
    fun `a machine cannot take heart rate from a connected strap`() {
        val h = Harness()
        h.strap(56.0, 1_000)
        assertEquals(56, h.hr())

        // Interleaved machine packets must change nothing.
        h.machine(130.0, 1_100)
        h.machine(135.0, 1_800)
        assertEquals("the machine must not win while a strap is connected", 56, h.hr())
    }

    @Test
    fun `a strap dropout does not hand heart rate to the machine`() {
        // The actual reported bug. Before the fix the strap aged out of the
        // freshness filter and the machine's 130 was projected.
        val h = Harness()
        h.strap(56.0, 1_000)
        h.machine(130.0, 6_000)

        assertTrue(
            "the machine's value must never reach the graph",
            h.series().none { it > 100.0 },
        )
    }

    @Test
    fun `a silent strap blanks the readout and marks the graph with zero`() {
        val h = Harness()
        h.strap(56.0, 1_000)
        assertEquals(56, h.hr())

        // Past HEART_RATE_FRESHNESS_MS with machine traffic still arriving.
        h.machine(130.0, 6_000)

        assertNull("the readout must not assert a heart rate of zero", h.hr())
        assertEquals("the gap is visible on the chart", 0.0, h.series().last(), 0.0001)
    }

    @Test
    fun `a dropout is marked once, not once per machine packet`() {
        // Machine data arrives faster than 1 Hz. Marking per packet would fill
        // the series with zeros and make a gap indistinguishable from a long
        // flat run at zero.
        val h = Harness()
        h.strap(56.0, 1_000)
        h.machine(130.0, 6_000)
        h.machine(131.0, 6_700)
        h.machine(132.0, 7_400)

        assertEquals("exactly one zero marker", 1, h.series().count { it == 0.0 })
    }

    @Test
    fun `the strap reclaims heart rate when it comes back`() {
        val h = Harness()
        h.strap(56.0, 1_000)
        h.machine(130.0, 6_000)
        assertNull(h.hr())

        h.strap(57.0, 6_500)
        assertEquals(57, h.hr())
    }

    @Test
    fun `a second dropout is marked again`() {
        val h = Harness()
        h.strap(56.0, 1_000)
        h.machine(130.0, 6_000)
        h.strap(57.0, 6_500)
        h.machine(131.0, 12_000)

        assertEquals("each dropout gets its own marker", 2, h.series().count { it == 0.0 })
    }

    @Test
    fun `the machine still supplies heart rate before any strap reading`() {
        // The seconds between clipping a strap on and it finding a pulse must not
        // read as a fault. Lockout begins at the strap's first sample, not at its
        // connection.
        val h = Harness()
        h.machine(130.0, 1_000)
        assertEquals(130, h.hr())
    }

    @Test
    fun `the machine supplies heart rate again once the strap disconnects`() {
        val h = Harness()
        h.strap(56.0, 1_000)
        h.machine(130.0, 1_100)
        assertEquals(56, h.hr())

        // onDisconnected purges the strap's candidates, which is what makes
        // "a strap candidate exists" mean "a strap is connected".
        h.fusion.onDisconnected(strapConn, nowMs = 1_200)
        h.machine(131.0, 1_300)

        assertEquals("with no strap, the machine is the best source again", 131, h.hr())
    }

    @Test
    fun `a dropout does not drag the session average down`() {
        // A gap is missing data, not a zero-bpm interval.
        val h = Harness()
        h.strap(56.0, 1_000)
        h.strap(58.0, 2_000)
        val averageBefore = h.state.averageHeartRate.value

        h.machine(130.0, 8_000)

        assertEquals("a strap dropout must not enter the average", averageBefore, h.state.averageHeartRate.value)
    }
}
