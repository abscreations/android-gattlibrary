package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KayakFirstRuntimeCoordinatorTest {
    private val connectionId = ConnectionId("AA:BB:CC:DD:EE:FF", 8)

    @Test
    fun `polling is bounded to one transaction with no catch up burst`() {
        val coordinator = KayakFirstRuntimeCoordinator(connectionId)
        val first = coordinator.start(1_000).single() as KayakFirstRuntimeAction.Send
        assertEquals(KayakFirstRuntimeTransactionKind.POLL, first.transaction.kind)
        assertEquals("6\r\n", first.transaction.chunks.single().toString(Charsets.UTF_8))

        assertTrue(coordinator.tick(5_000).isEmpty())
        coordinator.onWriteCompleted(connectionId, first.transaction.kind, first.transaction.sequence, true, 5_000)
        val missed = coordinator.tick(6_000)
        assertTrue(missed.isEmpty())
        assertEquals(1, coordinator.consecutivePollMisses)

        val next = coordinator.tick(7_000).single() as KayakFirstRuntimeAction.Send
        assertEquals(KayakFirstRuntimeTransactionKind.POLL, next.transaction.kind)
        assertTrue(coordinator.tick(20_000).isEmpty())
    }

    @Test
    fun `three consecutive poll misses report connected no data once`() {
        val coordinator = KayakFirstRuntimeCoordinator(connectionId)
        var now = 0L
        repeat(3) { miss ->
            val send = if (miss == 0) {
                coordinator.start(now).single()
            } else {
                coordinator.tick(now).single()
            } as KayakFirstRuntimeAction.Send
            coordinator.onWriteCompleted(
                connectionId, send.transaction.kind, send.transaction.sequence, true, now,
            )
            now += KayakFirstRuntimeCoordinator.ACKNOWLEDGEMENT_TIMEOUT_MS
            val actions = coordinator.tick(now)
            if (miss < 2) assertTrue(actions.isEmpty()) else {
                assertEquals(KayakFirstRuntimeAction.ConnectedNoData(3), actions.single())
            }
            now += KayakFirstRuntimeCoordinator.POLL_INTERVAL_MS
        }
        assertTrue(coordinator.tick(now).isNotEmpty())
    }

    @Test
    fun `measurement clears a poll and resets misses`() {
        val coordinator = KayakFirstRuntimeCoordinator(connectionId)
        val first = coordinator.start(0).single() as KayakFirstRuntimeAction.Send
        coordinator.onWriteCompleted(connectionId, first.transaction.kind, first.transaction.sequence, true, 0)
        coordinator.tick(1_000)
        assertEquals(1, coordinator.consecutivePollMisses)

        val poll = coordinator.tick(2_000).single() as KayakFirstRuntimeAction.Send
        coordinator.onWriteCompleted(connectionId, poll.transaction.kind, poll.transaction.sequence, true, 2_000)
        coordinator.onMeasurement(connectionId, 2_100)
        assertEquals(0, coordinator.consecutivePollMisses)
        assertTrue(coordinator.tick(3_099).isEmpty())
        assertTrue(coordinator.tick(3_100).single() is KayakFirstRuntimeAction.Send)
    }

    @Test
    fun `local start and stop are single shot and generation correlated`() {
        val coordinator = KayakFirstRuntimeCoordinator(connectionId)
        val initialPoll = coordinator.start(0).single() as KayakFirstRuntimeAction.Send
        coordinator.onWriteCompleted(
            connectionId, initialPoll.transaction.kind, initialPoll.transaction.sequence, true, 0,
        )

        assertTrue(coordinator.requestStart())
        assertFalse(coordinator.requestStart())
        val start = coordinator.onMeasurement(connectionId, 10).single() as KayakFirstRuntimeAction.Send
        assertEquals("9;1\r\n", start.transaction.chunks.single().toString(Charsets.UTF_8))
        coordinator.onWriteCompleted(connectionId, start.transaction.kind, start.transaction.sequence, true, 10)
        assertTrue(coordinator.onSessionResponse(ConnectionId(connectionId.address, 9), listOf("1"), 20).isEmpty())
        assertTrue(coordinator.sessionActive)
        coordinator.onSessionResponse(connectionId, listOf("1"), 20)
        assertTrue(coordinator.sessionActive)

        assertTrue(coordinator.requestStop())
        val stop = coordinator.tick(21).single() as KayakFirstRuntimeAction.Send
        assertEquals("9;3\r\n", stop.transaction.chunks.single().toString(Charsets.UTF_8))
        assertFalse(coordinator.sessionActive)
        coordinator.onWriteCompleted(connectionId, stop.transaction.kind, stop.transaction.sequence, true, 21)
        coordinator.tick(1_021)
        assertFalse(coordinator.sessionActive)
        val afterStopTimeout = coordinator.tick(50_000).single() as KayakFirstRuntimeAction.Send
        assertEquals(KayakFirstRuntimeTransactionKind.POLL, afterStopTimeout.transaction.kind)
    }

    @Test
    fun `cancel stops polling and clears local session state`() {
        val coordinator = KayakFirstRuntimeCoordinator(connectionId)
        coordinator.start(0)
        coordinator.cancel(disconnected = true)
        assertTrue(coordinator.tick(Long.MAX_VALUE).isEmpty())
        assertFalse(coordinator.requestStart())
        assertFalse(coordinator.sessionActive)
    }
}
