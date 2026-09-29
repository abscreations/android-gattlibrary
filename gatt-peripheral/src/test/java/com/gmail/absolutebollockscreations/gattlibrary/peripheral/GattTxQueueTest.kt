package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds
import com.gmail.absolutebollockscreations.gattlibrary.central.BtServiceProfiles.FtmsMachineProfile

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GattTxQueueTest {

    private val characteristic = UUID.fromString("00002ad2-0000-1000-8000-00805f9b34fb")

    /**
     * Blocks until the queue reports at least the given pending depths.
     *
     * `send` suspends only after it has enqueued, but it is dispatched on a
     * different thread, so a test that enqueues and then immediately drives the
     * queue is racing its own setup.
     */
    private suspend fun awaitQueued(queue: GattTxQueue, control: Int, data: Int) {
        repeat(200) {
            val stats = queue.stats()
            if (stats.queuedControlPayloads >= control && stats.queuedDataPayloads >= data) return
            delay(5)
        }
        throw AssertionError("queue never accepted control=$control data=$data")
    }

    @Test
    fun `only one operation is posted until its callback arrives`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val posted = Channel<GattTxQueue.Request>(Channel.UNLIMITED)
        val queue = GattTxQueue(scope, dataTimeoutMs = 1_000, controlTimeoutMs = 1_000) {
            posted.trySend(it).isSuccess
        }

        val first = async { queue.send(1, "AA", characteristic, byteArrayOf(1), false, GattTxQueue.Priority.DATA, "first") }
        posted.receive()
        val second = async { queue.send(1, "AA", characteristic, byteArrayOf(2), false, GattTxQueue.Priority.DATA, "second") }
        delay(50)
        assertFalse(second.isCompleted)

        queue.onNotificationSent("AA", true)
        assertTrue(first.await())
        posted.receive()
        queue.onNotificationSent("AA", true)
        assertTrue(second.await())
        scope.cancel()
    }

    @Test
    fun `control traffic overtakes queued data but not the in-flight operation`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val posted = Channel<GattTxQueue.Request>(Channel.UNLIMITED)
        val queue = GattTxQueue(scope, dataTimeoutMs = 1_000, controlTimeoutMs = 1_000) {
            posted.trySend(it).isSuccess
        }

        val first = async { queue.send(1, "AA", characteristic, byteArrayOf(1), false, GattTxQueue.Priority.DATA, "first") }
        assertTrue(posted.receive().purpose == "first")
        val data = async { queue.send(1, "AA", characteristic, byteArrayOf(2), false, GattTxQueue.Priority.DATA, "data") }
        val control = async { queue.send(1, "AA", characteristic, byteArrayOf(3), true, GattTxQueue.Priority.CONTROL, "control") }

        // Both sends run on another dispatcher, so wait until the queue has
        // actually accepted them before releasing the in-flight operation.
        // Without this the drain can run while only one is queued, and the
        // ordering assertion below becomes a race rather than a test of priority.
        awaitQueued(queue, control = 1, data = 1)

        queue.onNotificationSent("AA", true)
        assertTrue(first.await())
        assertTrue(posted.receive().purpose == "control")
        queue.onNotificationSent("AA", true)
        assertTrue(control.await())
        assertTrue(posted.receive().purpose == "data")
        queue.onNotificationSent("AA", true)
        assertTrue(data.await())
        scope.cancel()
    }

    @Test
    fun `callback from another device cannot complete the current operation`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val posted = Channel<GattTxQueue.Request>(Channel.UNLIMITED)
        val queue = GattTxQueue(scope, dataTimeoutMs = 1_000, controlTimeoutMs = 1_000) {
            posted.trySend(it).isSuccess
        }

        val result = async { queue.send(1, "AA", characteristic, byteArrayOf(1), false, GattTxQueue.Priority.DATA, "data") }
        posted.receive()
        queue.onNotificationSent("BB", true)
        delay(50)
        assertFalse(result.isCompleted)
        queue.onNotificationSent("AA", true)
        assertTrue(result.await())
        scope.cancel()
    }

    @Test
    fun `timeout and session cancellation fail pending work`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val posted = Channel<GattTxQueue.Request>(Channel.UNLIMITED)
        val queue = GattTxQueue(scope, dataTimeoutMs = 40, controlTimeoutMs = 40) {
            posted.trySend(it).isSuccess
        }

        val timedOut = async { queue.send(1, "AA", characteristic, byteArrayOf(1), false, GattTxQueue.Priority.DATA, "timeout") }
        posted.receive()
        assertFalse(timedOut.await())

        val cancelled = async { queue.send(2, "BB", characteristic, byteArrayOf(2), false, GattTxQueue.Priority.DATA, "cancel") }
        posted.receive()
        queue.cancelSession(2)
        assertFalse(cancelled.await())
        scope.cancel()
    }

    @Test
    fun `data resumes after a late control confirmation that remains inside the bounded window`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val posted = Channel<GattTxQueue.Request>(Channel.UNLIMITED)
            val queue = GattTxQueue(
                scope,
                dataTimeoutMs = 250,
                controlTimeoutMs = 250,
            ) { posted.trySend(it).isSuccess }

            val control = async {
                queue.send(
                    1,
                    "AA",
                    characteristic,
                    byteArrayOf(1),
                    true,
                    GattTxQueue.Priority.CONTROL,
                    "delayed-control",
                )
            }
            assertTrue(posted.receive().purpose == "delayed-control")
            delay(150)
            queue.onNotificationSent("AA", true)
            assertTrue(control.await())

            val data = async {
                queue.send(
                    1,
                    "AA",
                    characteristic,
                    byteArrayOf(2),
                    false,
                    GattTxQueue.Priority.DATA,
                    "data-after-delayed-control",
                )
            }
            assertTrue(posted.receive().purpose == "data-after-delayed-control")
            queue.onNotificationSent("AA", true)
            assertTrue(data.await())
            scope.cancel()
        }

    /**
     * Pins the deliberate decision documented in [GattTxQueue]: there is no "swallow the next
     * acknowledgement after a timeout" guard, so a confirmation that arrives after the queue gave
     * up is applied to whatever payload is in flight when it lands.
     *
     * This is characterisation, not endorsement. Android's `onNotificationSent` carries only a
     * device and a status, nothing that correlates it to the operation that caused it, so the queue
     * cannot tell a late confirmation from the current payload's. The alternative — discarding the
     * next acknowledgement — makes the next payload time out too, which is worse for the blackout
     * this queue exists to bound. If that trade is ever revisited, this test is what must change,
     * and changing it should be a decision rather than an accident.
     *
     * No capture has yet shown a post-timeout confirmation actually occurring: the 2026-08-05
     * client never confirmed at all, and the 2026-09-07 and three-persona clients both confirmed
     * inside the bounded window. The behaviour is pinned because it is unobserved, not because it
     * is known to be benign.
     */
    @Test
    fun `a confirmation arriving after the timeout is applied to the next payload in flight`() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val posted = Channel<GattTxQueue.Request>(Channel.UNLIMITED)
            val queue = GattTxQueue(
                scope,
                dataTimeoutMs = 5_000,
                controlTimeoutMs = 120,
            ) { posted.trySend(it).isSuccess }

            // A control indication the client never confirms in time.
            val abandoned = async {
                queue.send(
                    1,
                    "AA",
                    characteristic,
                    byteArrayOf(1),
                    true,
                    GattTxQueue.Priority.CONTROL,
                    "abandoned-control",
                )
            }
            assertTrue(posted.receive().purpose == "abandoned-control")
            assertFalse(abandoned.await())

            // The next payload goes out and is never acknowledged on its own behalf.
            val next = async {
                queue.send(
                    1,
                    "AA",
                    characteristic,
                    byteArrayOf(2),
                    false,
                    GattTxQueue.Priority.DATA,
                    "payload-after-abandoned-control",
                )
            }
            assertTrue(posted.receive().purpose == "payload-after-abandoned-control")

            // The abandoned indication's confirmation finally arrives. It completes the payload
            // now in flight, which reports delivered without the client having confirmed it.
            queue.onNotificationSent("AA", true)
            assertTrue(next.await())

            val stats = queue.stats()
            assertEquals(1L, stats.failed)
            assertEquals(1L, stats.delivered)
            scope.cancel()
        }

    /**
     * The out-of-phase state above does not accumulate: the queue is one payload behind, and the
     * following payload is acknowledged normally rather than inheriting another stray.
     */
    @Test
    fun `the queue does not stay out of phase after a misattributed confirmation`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val posted = Channel<GattTxQueue.Request>(Channel.UNLIMITED)
        val queue = GattTxQueue(
            scope,
            dataTimeoutMs = 5_000,
            controlTimeoutMs = 120,
        ) { posted.trySend(it).isSuccess }

        val abandoned = async {
            queue.send(1, "AA", characteristic, byteArrayOf(1), true, GattTxQueue.Priority.CONTROL, "abandoned")
        }
        assertTrue(posted.receive().purpose == "abandoned")
        assertFalse(abandoned.await())

        val first = async {
            queue.send(1, "AA", characteristic, byteArrayOf(2), false, GattTxQueue.Priority.DATA, "first")
        }
        assertTrue(posted.receive().purpose == "first")
        queue.onNotificationSent("AA", true)
        assertTrue(first.await())

        val second = async {
            queue.send(1, "AA", characteristic, byteArrayOf(3), false, GattTxQueue.Priority.DATA, "second")
        }
        assertTrue(posted.receive().purpose == "second")
        queue.onNotificationSent("AA", true)
        assertTrue(second.await())
        scope.cancel()
    }
}
