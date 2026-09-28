package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.capture

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

/**
 * Plan §0.9.5: the ring fills continuously and arming flushes it first.
 *
 * The property under test is that a capture explains a failure rather than
 * merely noticing it — the file has to begin *before* whatever armed it. A
 * capture that starts at the trigger records the symptom and none of the cause.
 */
class CaptureArmingTest {

    /** Collects whatever reaches the capture tag, standing in for CaptureTree. */
    private class RecordingTree : Timber.Tree() {
        val lines = mutableListOf<String>()
        public override fun isLoggable(tag: String?, priority: Int): Boolean =
            tag == BleCapture.TAG

        public override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (tag == BleCapture.TAG) lines += message
        }
    }

    private lateinit var tree: RecordingTree

    @Before
    fun setUp() {
        tree = RecordingTree()
        Timber.plant(tree)
        BleCapture.resetSession()
        BleCapture.setClockForTest { 1_000L }
    }

    @After
    fun tearDown() {
        Timber.uproot(tree)
        BleCapture.resetClock()
        BleCapture.resetSession()
    }

    private fun emitDropped(count: Int) = BleCapture.emit(DroppedEvent(count = count))

    @Test
    fun `disarmed events buffer in memory and reach no file`() {
        repeat(5) { emitDropped(it) }

        assertFalse(BleCapture.isArmed())
        assertEquals(5, BleCapture.bufferedCount())
        assertTrue("nothing may reach disk while disarmed", tree.lines.isEmpty())
    }

    @Test
    fun `arming flushes the run-up before the live stream`() {
        repeat(3) { emitDropped(it) }
        BleCapture.arm(CaptureTrigger.LIVENESS_DEMOTION)
        emitDropped(99)

        assertTrue(BleCapture.isArmed())
        assertEquals(CaptureTrigger.LIVENESS_DEMOTION, BleCapture.armedBy())
        assertEquals("three buffered plus one live", 4, tree.lines.size)
        assertEquals("the ring is emptied by the flush", 0, BleCapture.bufferedCount())

        // Sequence numbers prove ordering: the backlog kept the numbers it was
        // assigned when it happened, and the live event follows.
        val sequences = tree.lines.map { line ->
            Regex("\"seq\":(\\d+)").find(line)!!.groupValues[1].toLong()
        }
        assertEquals(listOf(1L, 2L, 3L, 4L), sequences)
    }

    @Test
    fun `a live event goes straight out without touching the ring`() {
        BleCapture.arm(CaptureTrigger.MANUAL)
        emitDropped(1)

        assertEquals(1, tree.lines.size)
        assertEquals(0, BleCapture.bufferedCount())
    }

    @Test
    fun `arming twice does not restart the file`() {
        emitDropped(1)
        BleCapture.arm(CaptureTrigger.UNMATCHED_DEVICE)
        val afterFirst = tree.lines.size

        BleCapture.arm(CaptureTrigger.PARSER_REJECTION)

        assertEquals("the second trigger is ignored", afterFirst, tree.lines.size)
        assertEquals(
            "the run-up to the first trigger is the interesting part",
            CaptureTrigger.UNMATCHED_DEVICE,
            BleCapture.armedBy(),
        )
    }

    @Test
    fun `disarming stops the file but keeps the ring filling`() {
        BleCapture.arm(CaptureTrigger.MANUAL)
        emitDropped(1)
        BleCapture.disarm("session end")

        emitDropped(2)
        emitDropped(3)

        assertFalse(BleCapture.isArmed())
        assertNull(BleCapture.armedBy())
        assertEquals("only the armed event reached disk", 1, tree.lines.size)
        assertEquals("later events are still retained for a future trigger", 2, BleCapture.bufferedCount())
    }

    @Test
    fun `a second arming after a disarm flushes the new run-up`() {
        BleCapture.arm(CaptureTrigger.MANUAL)
        BleCapture.disarm("manual toggle off")
        emitDropped(7)
        emitDropped(8)
        BleCapture.arm(CaptureTrigger.PARSER_REJECTION)

        assertEquals("both buffered events were flushed", 2, tree.lines.size)
    }

    @Test
    fun `an overflowing ring reports the loss before the backlog it truncates`() {
        // More events than the ring holds, so the oldest are evicted. A reader
        // must be able to tell a truncated history from a quiet one.
        repeat(CaptureRing.DEFAULT_CAPACITY + 25) { emitDropped(it) }
        BleCapture.arm(CaptureTrigger.MANUAL)

        assertEquals(CaptureRing.DEFAULT_CAPACITY + 1, tree.lines.size)
        val first = tree.lines.first()
        assertTrue("the marker precedes the backlog, got $first", first.contains("\"ev\":\"dropped\""))
        assertTrue(first, first.contains("\"n\":25"))
        assertTrue("a synthetic record is marked seq 0", first.contains("\"seq\":0"))
    }

    @Test
    fun `resetting the session clears armed state, ring and labels`() {
        BleCapture.labelFor("AA:BB:CC:DD:EE:FF")
        emitDropped(1)
        BleCapture.arm(CaptureTrigger.MANUAL)

        BleCapture.resetSession()

        assertFalse(BleCapture.isArmed())
        assertEquals(0, BleCapture.bufferedCount())
        assertEquals("labels restart, carrying nothing across sessions", "c1", BleCapture.labelFor("11:22:33:44:55:66"))
    }
}
