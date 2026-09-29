package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the conflating, payload-atomic behavior of [GattTxQueue].
 *
 * The defect these tests pin: the previous FIFO held 32 pending frames and, once
 * full, rejected the *newest* send while retaining the oldest. A Garmin watch on
 * a 990 ms connection interval therefore rendered data that fell progressively
 * further behind. Conflation inverts that - the newest record always wins.
 */
class GattTxQueueConflationTest {

    private val ftmsData: UUID = UUID.fromString("00002ad2-0000-1000-8000-00805f9b34fb")
    private val cscData: UUID = UUID.fromString("00002a5b-0000-1000-8000-00805f9b34fb")

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class Harness {
        val posted = Channel<GattTxQueue.Request>(Channel.UNLIMITED)
        fun post(request: GattTxQueue.Request): Boolean = posted.trySend(request).isSuccess
    }

    private fun CoroutineScope.dataSend(
        queue: GattTxQueue,
        address: String,
        characteristic: UUID,
        purpose: String,
        frames: List<ByteArray>,
    ): Deferred<GattTxQueue.Outcome> = async {
        queue.sendPayload(
            sessionId = 1,
            deviceAddress = address,
            characteristicUuid = characteristic,
            frames = frames,
            confirm = false,
            priority = GattTxQueue.Priority.DATA,
            purpose = purpose,
        )
    }

    @Test
    fun `the newest record wins and the stale one is superseded not failed`(): Unit = runBlocking {
        val scope = scope()
        val h = Harness()
        val queue = GattTxQueue(scope, dataTimeoutMs = 2_000, controlTimeoutMs = 2_000, post = h::post)

        try {
            // Occupy the single in-flight slot.
            val inFlight = scope.dataSend(queue, "AA", ftmsData, "r1", listOf(byteArrayOf(1)))
            assertEquals("r1", withTimeout(2_000) { h.posted.receive() }.purpose)

            // Two more arrive while the link is busy. Only the newest survives.
            val stale = scope.dataSend(queue, "AA", ftmsData, "r2", listOf(byteArrayOf(2)))
            delay(50)
            val newest = scope.dataSend(queue, "AA", ftmsData, "r3", listOf(byteArrayOf(3)))

            assertEquals(
                "the stale record is superseded, not failed",
                GattTxQueue.Outcome.SUPERSEDED,
                withTimeout(2_000) { stale.await() },
            )

            queue.onNotificationSent("AA", true)
            assertEquals(GattTxQueue.Outcome.DELIVERED, withTimeout(2_000) { inFlight.await() })

            val next = withTimeout(2_000) { h.posted.receive() }
            assertEquals("only the newest record reaches the wire", "r3", next.purpose)
            queue.onNotificationSent("AA", true)
            assertEquals(GattTxQueue.Outcome.DELIVERED, withTimeout(2_000) { newest.await() })

            // Nothing else was posted: r2 never touched the stack.
            assertTrue(h.posted.tryReceive().isFailure)
            assertEquals(1L, queue.stats().superseded)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `queue depth per slot never exceeds one pending payload`(): Unit = runBlocking {
        val scope = scope()
        val h = Harness()
        val queue = GattTxQueue(scope, dataTimeoutMs = 2_000, controlTimeoutMs = 2_000, post = h::post)

        try {
            val inFlight = scope.dataSend(queue, "AA", ftmsData, "held", listOf(byteArrayOf(0)))
            withTimeout(2_000) { h.posted.receive() }

            // Sequenced rather than fired in a batch: async dispatch order is not
            // guaranteed, and this test is about depth, not about which record wins.
            val pending = mutableListOf<Deferred<GattTxQueue.Outcome>>()
            repeat(50) { i ->
                pending += scope.dataSend(queue, "AA", ftmsData, "r$i", listOf(byteArrayOf(i.toByte())))
                delay(5)
            }

            assertEquals("at most one payload waits per slot", 1, queue.stats().queuedDataPayloads)
            assertEquals(49L, queue.stats().superseded)

            queue.onNotificationSent("AA", true)
            withTimeout(2_000) { inFlight.await() }
            assertEquals("r49", withTimeout(2_000) { h.posted.receive() }.purpose)
            queue.onNotificationSent("AA", true)

            val outcomes = pending.map { withTimeout(2_000) { it.await() } }
            assertEquals(
                "exactly one record survived",
                1,
                outcomes.count { it == GattTxQueue.Outcome.DELIVERED },
            )
            assertEquals(
                "every other record was superseded, none failed",
                49,
                outcomes.count { it == GattTxQueue.Outcome.SUPERSEDED },
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a fragmented record is sent whole and is never interrupted`(): Unit = runBlocking {
        val scope = scope()
        val h = Harness()
        val queue = GattTxQueue(scope, dataTimeoutMs = 2_000, controlTimeoutMs = 2_000, post = h::post)

        try {
            val record = scope.dataSend(
                queue, "AA", ftmsData, "two-frame",
                listOf(byteArrayOf(0x01, 0x11), byteArrayOf(0x00, 0x22)),
            )
            val first = withTimeout(2_000) { h.posted.receive() }
            assertEquals(0, first.frameIndex)
            assertEquals(2, first.frameCount)

            // A newer record arrives mid-payload. It must not displace the frame
            // still in flight, nor slip between the two frames.
            val newer = scope.dataSend(queue, "AA", ftmsData, "newer", listOf(byteArrayOf(9)))
            delay(50)
            assertFalse("the in-flight record must not be superseded", record.isCompleted)

            queue.onNotificationSent("AA", true)
            val second = withTimeout(2_000) { h.posted.receive() }
            assertEquals("the second frame follows the first", "two-frame", second.purpose)
            assertEquals(1, second.frameIndex)

            queue.onNotificationSent("AA", true)
            assertEquals(GattTxQueue.Outcome.DELIVERED, withTimeout(2_000) { record.await() })

            assertEquals("newer", withTimeout(2_000) { h.posted.receive() }.purpose)
            queue.onNotificationSent("AA", true)
            assertEquals(GattTxQueue.Outcome.DELIVERED, withTimeout(2_000) { newer.await() })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a failed frame abandons the rest of its record`(): Unit = runBlocking {
        val scope = scope()
        val h = Harness()
        val queue = GattTxQueue(scope, dataTimeoutMs = 100, controlTimeoutMs = 100, post = h::post)

        try {
            val record = scope.dataSend(
                queue, "AA", ftmsData, "torn",
                listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3)),
            )
            withTimeout(2_000) { h.posted.receive() }
            // Never acknowledge: the first frame times out.
            assertEquals(GattTxQueue.Outcome.FAILED, withTimeout(2_000) { record.await() })
            // Frames two and three were never posted - no partial record on the wire.
            delay(100)
            assertTrue(h.posted.tryReceive().isFailure)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `separate characteristics get separate slots`(): Unit = runBlocking {
        val scope = scope()
        val h = Harness()
        val queue = GattTxQueue(scope, dataTimeoutMs = 2_000, controlTimeoutMs = 2_000, post = h::post)

        try {
            val inFlight = scope.dataSend(queue, "AA", ftmsData, "hold", listOf(byteArrayOf(0)))
            withTimeout(2_000) { h.posted.receive() }

            scope.dataSend(queue, "AA", ftmsData, "ftms", listOf(byteArrayOf(1)))
            scope.dataSend(queue, "AA", cscData, "csc", listOf(byteArrayOf(2)))
            delay(100)

            assertEquals("FTMS and CSC do not share a slot", 2, queue.stats().queuedDataPayloads)
            assertEquals("neither superseded the other", 0L, queue.stats().superseded)

            queue.onNotificationSent("AA", true)
            withTimeout(2_000) { inFlight.await() }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a slow client does not starve another client`(): Unit = runBlocking {
        val scope = scope()
        val h = Harness()
        val queue = GattTxQueue(scope, dataTimeoutMs = 2_000, controlTimeoutMs = 2_000, post = h::post)

        try {
            val slowHold = scope.dataSend(queue, "AA", ftmsData, "aa-hold", listOf(byteArrayOf(0)))
            withTimeout(2_000) { h.posted.receive() }

            scope.dataSend(queue, "AA", ftmsData, "aa-next", listOf(byteArrayOf(1)))
            scope.dataSend(queue, "BB", ftmsData, "bb-next", listOf(byteArrayOf(2)))
            delay(100)

            queue.onNotificationSent("AA", true)
            withTimeout(2_000) { slowHold.await() }

            // AA's slot filled first, so it drains first; the point is that BB is
            // reached on the very next drain rather than queueing behind a backlog.
            val order = listOf(
                withTimeout(2_000) { h.posted.receive() }.also { queue.onNotificationSent(it.deviceAddress, true) }.purpose,
                withTimeout(2_000) { h.posted.receive() }.also { queue.onNotificationSent(it.deviceAddress, true) }.purpose,
            )
            assertTrue("both clients were served: $order", order.containsAll(listOf("aa-next", "bb-next")))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `control traffic is never conflated`(): Unit = runBlocking {
        val scope = scope()
        val h = Harness()
        val queue = GattTxQueue(scope, dataTimeoutMs = 2_000, controlTimeoutMs = 2_000, post = h::post)

        try {
            val hold = scope.dataSend(queue, "AA", ftmsData, "hold", listOf(byteArrayOf(0)))
            withTimeout(2_000) { h.posted.receive() }

            // Sequenced so the FIFO assertion below tests the queue rather than
            // the dispatcher's launch order.
            val controls = mutableListOf<Deferred<GattTxQueue.Outcome>>()
            for (i in 1..4) {
                controls += scope.async {
                    queue.sendPayload(
                        sessionId = 1,
                        deviceAddress = "AA",
                        characteristicUuid = ftmsData,
                        frames = listOf(byteArrayOf(i.toByte())),
                        confirm = true,
                        priority = GattTxQueue.Priority.CONTROL,
                        purpose = "ctl$i",
                    )
                }
                delay(20)
            }
            assertEquals("every control payload is retained", 4, queue.stats().queuedControlPayloads)
            assertEquals(0L, queue.stats().superseded)

            queue.onNotificationSent("AA", true)
            withTimeout(2_000) { hold.await() }

            val seen = mutableListOf<String>()
            repeat(4) {
                seen += withTimeout(2_000) { h.posted.receive() }.purpose
                queue.onNotificationSent("AA", true)
            }
            assertEquals("control keeps FIFO order", listOf("ctl1", "ctl2", "ctl3", "ctl4"), seen)
            controls.forEach { assertEquals(GattTxQueue.Outcome.DELIVERED, withTimeout(2_000) { it.await() }) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a timeout affects only its own payload and the queue keeps working`(): Unit = runBlocking {
        val scope = scope()
        val h = Harness()
        val queue = GattTxQueue(scope, dataTimeoutMs = 80, controlTimeoutMs = 80, post = h::post)

        try {
            val abandoned = scope.dataSend(queue, "AA", ftmsData, "no-ack", listOf(byteArrayOf(1)))
            withTimeout(2_000) { h.posted.receive() }
            assertEquals(GattTxQueue.Outcome.FAILED, withTimeout(2_000) { abandoned.await() })

            // The link, the session, and the queue are all still usable. Nothing in
            // GattTxQueue can disconnect a client; a stalled acknowledgement costs
            // one record, not the connection.
            val next = scope.dataSend(queue, "AA", ftmsData, "after", listOf(byteArrayOf(2)))
            assertEquals("after", withTimeout(2_000) { h.posted.receive() }.purpose)
            queue.onNotificationSent("AA", true)
            assertEquals(GattTxQueue.Outcome.DELIVERED, withTimeout(2_000) { next.await() })
            assertEquals(1L, queue.stats().failed)
            assertEquals(1L, queue.stats().delivered)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the control timeout covers five Garmin connection intervals plus jitter`() {
        // The 2026-09-07 rowing capture measured 3.93s from indication to
        // confirmation on a Garmin watch negotiating a 990ms interval. Cover five
        // intervals plus bounded Android callback jitter while remaining finite.
        assertTrue(
            "control timeout must cover five 990ms intervals",
            GattTxQueue.DEFAULT_CONTROL_TIMEOUT_MS >= 5 * 990L,
        )
        assertTrue(
            "control timeout must remain bounded",
            GattTxQueue.DEFAULT_CONTROL_TIMEOUT_MS <= 6_000L,
        )
        assertTrue(
            "data timeout must cover five 990ms intervals",
            GattTxQueue.DEFAULT_DATA_TIMEOUT_MS >= 5 * 990L,
        )
        assertTrue(
            "data timeout must remain bounded",
            GattTxQueue.DEFAULT_DATA_TIMEOUT_MS <= 6_000L,
        )
    }

    @Test
    fun `acknowledgement latency is recorded for delivered payloads`(): Unit = runBlocking {
        val scope = scope()
        val h = Harness()
        var clock = 0L
        val queue = GattTxQueue(
            scope,
            dataTimeoutMs = 2_000,
            controlTimeoutMs = 2_000,
            nowMs = { clock },
            post = h::post,
        )

        try {
            val sent = scope.dataSend(queue, "AA", ftmsData, "timed", listOf(byteArrayOf(1)))
            withTimeout(2_000) { h.posted.receive() }
            clock = 990
            queue.onNotificationSent("AA", true)
            assertEquals(GattTxQueue.Outcome.DELIVERED, withTimeout(2_000) { sent.await() })

            val stats = queue.stats()
            assertEquals(990L, stats.lastAckLatencyMs)
            assertEquals(990L, stats.maxAckLatencyMs)
            assertEquals(990L, stats.meanAckLatencyMs)
        } finally {
            scope.cancel()
        }
    }
}
