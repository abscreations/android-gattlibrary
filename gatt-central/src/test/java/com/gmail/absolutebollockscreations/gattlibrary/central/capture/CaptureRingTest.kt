package com.gmail.absolutebollockscreations.gattlibrary.central.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.core.capture.CaptureRecord
import com.gmail.absolutebollockscreations.gattlibrary.core.capture.DroppedEvent

/** Eviction and drain behaviour of the §0.9.5 ring, without threads. */
class CaptureRingTest {

    private fun record(seq: Long) =
        CaptureRecord(atMs = seq, seq = seq, event = DroppedEvent(count = seq.toInt()))

    private fun sequences(records: List<CaptureRecord>) = records.map { it.seq }

    @Test
    fun `records come back oldest first`() {
        val ring = CaptureRing(capacity = 8)
        (1L..4L).forEach { ring.offer(record(it)) }

        assertEquals(listOf(1L, 2L, 3L, 4L), sequences(ring.drain()))
    }

    @Test
    fun `a full ring evicts the oldest, keeping what is nearest the trigger`() {
        val ring = CaptureRing(capacity = 3)
        (1L..5L).forEach { ring.offer(record(it)) }

        assertEquals(3, ring.size())
        assertEquals("the newest three survive", listOf(3L, 4L, 5L), sequences(ring.drain()))
        assertEquals("two evictions counted", 2, ring.evictedCount())
    }

    @Test
    fun `draining empties the ring`() {
        val ring = CaptureRing(capacity = 4)
        (1L..3L).forEach { ring.offer(record(it)) }

        assertEquals(3, ring.drain().size)
        assertEquals(0, ring.size())
        assertTrue("a second drain yields nothing", ring.drain().isEmpty())
    }

    @Test
    fun `the eviction count survives a drain until reset`() {
        // The marker is written at arming time, after the drain, so the count
        // must not be cleared by draining.
        val ring = CaptureRing(capacity = 2)
        (1L..5L).forEach { ring.offer(record(it)) }

        ring.drain()
        assertEquals(3, ring.evictedCount())
        ring.resetEvicted()
        assertEquals(0, ring.evictedCount())
    }

    @Test
    fun `clear resets contents and eviction count together`() {
        val ring = CaptureRing(capacity = 2)
        (1L..5L).forEach { ring.offer(record(it)) }

        ring.clear()

        assertEquals(0, ring.size())
        assertEquals(0, ring.evictedCount())
    }

    @Test
    fun `a capacity of one keeps only the newest`() {
        val ring = CaptureRing(capacity = 1)
        (1L..3L).forEach { ring.offer(record(it)) }

        assertEquals(listOf(3L), sequences(ring.drain()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a non-positive capacity is rejected`() {
        CaptureRing(capacity = 0)
    }

    @Test
    fun `the default capacity matches the plan`() {
        assertEquals(2_000, CaptureRing.DEFAULT_CAPACITY)
    }
}
