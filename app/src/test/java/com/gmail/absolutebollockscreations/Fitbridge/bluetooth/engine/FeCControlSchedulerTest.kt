package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.UUID

/**
 * Real-time tests for [FeCControlScheduler]'s cadence and coalescing, per plan §16.6/§9.9.
 *
 * These use a short real [intervalMs] and real (small) [delay] waits rather than
 * `kotlinx-coroutines-test`'s virtual-time dispatchers - this project's `kotlinx-coroutines-test`
 * dependency does not resolve `StandardTestDispatcher` in this module's build, so this avoids
 * that entirely rather than fighting a build-configuration problem. The margins below are
 * generous multiples of [intervalMs] specifically so a slow CI machine does not turn this into a
 * flaky test.
 */
class FeCControlSchedulerTest {

    private class RecordingTransport : GattTransport {
        override val connectionId = ConnectionId("AA:BB:CC:DD:EE:FF", 1L)
        val writes: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())

        override fun enableNotifications(uuid: UUID): Boolean = true

        override suspend fun write(
            uuid: UUID,
            payload: ByteArray,
            mode: GattWriteMode,
            timeoutMs: Long,
            operationName: String,
        ): Boolean {
            assertEquals(GattWriteMode.WITHOUT_RESPONSE, mode)
            assertEquals(BluetoothIds.UUID_FEC_CONTROL, uuid)
            writes.add(payload)
            return true
        }
    }

    private val intervalMs = 40L

    @Test
    fun `a submitted target is dispatched as its encoded frame on a tick`() = runBlocking {
        val transport = RecordingTransport()
        val scheduler = FeCControlScheduler(transport, minIntervalMs = intervalMs)
        scheduler.start()

        scheduler.submit(FeCControlTarget.BasicResistance(50.0))
        delay(intervalMs * 4)
        scheduler.stop()

        assertEquals(1, transport.writes.size)
        assertArrayEquals(FeCControlEncoder.encodeBasicResistance(50.0), transport.writes.single())
    }

    @Test
    fun `a data-page request is dispatched through the same bounded FEC3 scheduler`() = runBlocking {
        val transport = RecordingTransport()
        val scheduler = FeCControlScheduler(transport, minIntervalMs = intervalMs)
        scheduler.submit(FeCControlTarget.RequestDataPage(BluetoothIds.FEC_PAGE_FE_CAPABILITIES))
        scheduler.start()
        delay(intervalMs * 4)
        scheduler.stop()

        assertEquals(1, transport.writes.size)
        assertArrayEquals(
            FeCControlEncoder.encodeRequestDataPage(BluetoothIds.FEC_PAGE_FE_CAPABILITIES),
            transport.writes.single(),
        )
    }

    @Test
    fun `two submissions before the next tick coalesce to only the latest`() = runBlocking {
        val transport = RecordingTransport()
        val scheduler = FeCControlScheduler(transport, minIntervalMs = intervalMs)
        scheduler.start()

        scheduler.submit(FeCControlTarget.Erg(100.0))
        scheduler.submit(FeCControlTarget.Erg(200.0)) // overwrites the first before any tick fires
        delay(intervalMs * 4)
        scheduler.stop()

        assertEquals(
            "only the latest submission before a tick should ever reach the wire",
            1,
            transport.writes.size,
        )
        assertArrayEquals(FeCControlEncoder.encodeTargetPower(200.0), transport.writes.single())
    }

    /**
     * Polls rather than sleeping a fixed amount: the scheduler's very first tick after [start]
     * races against [submit] (there is no guaranteed initial delay before the loop's first
     * pending-target check), so a write can legitimately land almost immediately after start.
     * That race makes "how long did the *first* write take" unobservable deterministically -
     * see the steady-state test below, which sidesteps it entirely by measuring the gap between
     * a *second* write and the first instead.
     */
    private suspend fun waitForWriteCount(transport: RecordingTransport, count: Int) {
        val deadlineNanos = System.nanoTime() + intervalMs * 20 * 1_000_000L
        while (transport.writes.size < count && System.nanoTime() < deadlineNanos) {
            delay(5)
        }
    }

    @Test
    fun `steady-state writes are gated by roughly the minimum interval`() = runBlocking {
        val transport = RecordingTransport()
        val scheduler = FeCControlScheduler(transport, minIntervalMs = intervalMs)
        scheduler.start()

        // The first write's timing is racy against start() by design (see waitForWriteCount's
        // doc) - only the gap to the *next* write, submitted once the loop is already ticking
        // in steady state, is guaranteed to cross a full delay(minIntervalMs).
        scheduler.submit(FeCControlTarget.Erg(100.0))
        waitForWriteCount(transport, 1)
        assertEquals(1, transport.writes.size)
        val firstWriteAtNanos = System.nanoTime()

        scheduler.submit(FeCControlTarget.Erg(200.0))
        waitForWriteCount(transport, 2)
        scheduler.stop()

        assertEquals(2, transport.writes.size)
        val elapsedMs = (System.nanoTime() - firstWriteAtNanos) / 1_000_000L
        assertTrue(
            "the second write must be gated by roughly one full tick after the first (elapsed=${elapsedMs}ms, interval=${intervalMs}ms)",
            elapsedMs >= intervalMs / 2,
        )
    }

    @Test
    fun `a Sim target dispatches wind resistance then track resistance, in that order`() = runBlocking {
        val transport = RecordingTransport()
        val scheduler = FeCControlScheduler(transport, minIntervalMs = intervalMs)
        scheduler.start()

        scheduler.submit(
            FeCControlTarget.Sim(
                windSpeedMps = -2.0,
                gradePercent = 3.0,
                rollingResistanceCoefficient = 0.004,
                windResistanceCoefficient = 0.51,
            ),
        )
        delay(intervalMs * 4)
        scheduler.stop()

        assertEquals(2, transport.writes.size)
        assertArrayEquals(
            FeCControlEncoder.encodeWindResistance(
                windResistanceCoefficient = 0.51,
                windSpeedKmh = -2.0 * 3.6,
                draftingFactor = null,
            ),
            transport.writes[0],
        )
        assertArrayEquals(
            FeCControlEncoder.encodeTrackResistance(gradePercent = 3.0, rollingResistanceCoefficient = 0.004),
            transport.writes[1],
        )
    }

    /**
     * Regression for the inclination-only write path
     * ([FeCBleMachineControlEndpoint.setTargetInclinationPercent]): a [FeCControlTarget.Sim]
     * with only [FeCControlTarget.Sim.gradePercent] set must dispatch page 50 with every field
     * at the spec's "use device default" sentinel, and page 51 with grade set but Crr also at
     * its sentinel - never a fabricated wind or rolling-resistance value.
     */
    @Test
    fun `a Sim target with only grade set dispatches all-default wind resistance and a sentinel Crr`() = runBlocking {
        val transport = RecordingTransport()
        val scheduler = FeCControlScheduler(transport, minIntervalMs = intervalMs)
        scheduler.start()

        scheduler.submit(
            FeCControlTarget.Sim(
                windSpeedMps = null,
                gradePercent = 4.5,
                rollingResistanceCoefficient = null,
                windResistanceCoefficient = null,
            ),
        )
        delay(intervalMs * 4)
        scheduler.stop()

        assertEquals(2, transport.writes.size)
        assertArrayEquals(FeCControlEncoder.encodeWindResistance(), transport.writes[0])
        assertArrayEquals(
            FeCControlEncoder.encodeTrackResistance(gradePercent = 4.5, rollingResistanceCoefficient = null),
            transport.writes[1],
        )
    }

    @Test
    fun `an out-of-range target is dropped for that tick without crashing the loop`() = runBlocking {
        val transport = RecordingTransport()
        val scheduler = FeCControlScheduler(transport, minIntervalMs = intervalMs)
        scheduler.start()

        scheduler.submit(FeCControlTarget.BasicResistance(-5.0)) // out of 0..100
        delay(intervalMs * 4)
        assertEquals(0, transport.writes.size)

        // The loop must still be alive and able to serve the next legal submission.
        scheduler.submit(FeCControlTarget.BasicResistance(50.0))
        delay(intervalMs * 4)
        scheduler.stop()
        assertEquals(1, transport.writes.size)
    }

    @Test
    fun `stop prevents any further writes after it returns`() = runBlocking {
        val transport = RecordingTransport()
        val scheduler = FeCControlScheduler(transport, minIntervalMs = intervalMs)
        scheduler.start()

        scheduler.submit(FeCControlTarget.Erg(150.0))
        scheduler.stop()
        assertTrue(!scheduler.isRunning)
        // The loop's first pending-target check intentionally has no initial delay (lines 79-85),
        // so the target may already have reached the transport before stop wins the race. The
        // lifecycle guarantee we can observe deterministically is that stop returns a stable count.
        val writesAtStop = transport.writes.size

        delay(intervalMs * 4)
        assertEquals(writesAtStop, transport.writes.size)
    }

    @Test
    fun `start is idempotent`() = runBlocking {
        val transport = RecordingTransport()
        val scheduler = FeCControlScheduler(transport, minIntervalMs = intervalMs)
        scheduler.start()
        scheduler.start()

        scheduler.submit(FeCControlTarget.Erg(75.0))
        delay(intervalMs * 4)
        scheduler.stop()

        assertEquals(
            "a second start() must not spawn a competing loop that double-dispatches",
            1,
            transport.writes.size,
        )
    }
}
