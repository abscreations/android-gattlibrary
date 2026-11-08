package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.*
import org.junit.Test

class MaxProForceTrackerTest {
    private fun side(reps: Int, level: Int) =
        MaxProV1Decoder.side(MaxProRawSide(level * 10, 1000, 100, 20, 30, reps))

    @Test fun `baseline counters and duplicate packets never add force samples`() {
        val tracker = MaxProForceTracker()
        assertEquals(MaxProForceSummary(), tracker.observe(side(8, 1)))
        repeat(10) { tracker.observe(side(8, 6)) }
        assertEquals(0L, tracker.summary.observedPulls)
        tracker.observe(side(9, 1))
        repeat(10) { tracker.observe(side(9, 6)) }
        assertEquals(1L, tracker.summary.observedPulls)
        assertEquals(6.5, tracker.summary.lastPounds!!, 1e-9)
    }

    @Test fun `last mean and maximum count each observed pull once and reset on counter decrease`() {
        val tracker = MaxProForceTracker()
        tracker.reset(0)
        tracker.observe(side(1, 1))
        tracker.observe(side(2, 6))
        tracker.observe(side(3, 2))
        assertEquals(8.0, tracker.summary.lastPounds!!, 1e-9)
        assertEquals((6.5 + 12 + 8) / 3, tracker.summary.averagePounds!!, 1e-9)
        assertEquals(12.0, tracker.summary.maximumPounds!!, 1e-9)
        tracker.observe(side(0, 1))
        assertEquals(MaxProForceSummary(), tracker.summary)
        tracker.observe(side(1, 2))
        assertEquals(1L, tracker.summary.observedPulls)
        tracker.reset(1)
        assertEquals(MaxProForceSummary(), tracker.summary)
    }

    @Test fun `a counter gap supplies one observed pull and an invalid knob supplies none`() {
        val tracker = MaxProForceTracker()
        tracker.reset(1)
        tracker.observe(side(5, 6))
        assertEquals(1L, tracker.summary.observedPulls)
        tracker.observe(side(6, 0))
        assertEquals(1L, tracker.summary.observedPulls)
        tracker.observe(side(7, 1))
        assertEquals(2L, tracker.summary.observedPulls)
    }

    @Test fun `only completed frames are interpreted and failed logging cannot prevent state updates`() {
        val store = MaxProStore()
        val id = ConnectionId("AA:00", 1)
        val frame = "{EVT,0F0100640A08A00A852230E00060A08400945913C10006}"
        store.begin(id)
        val seen = mutableListOf<MaxProState>()
        store.feed(id, frame.take(20).toByteArray(), 1) { seen.add(it) }
        assertTrue(seen.isEmpty())
        store.feed(id, frame.drop(20).toByteArray(), 2) { seen.add(it) }
        assertEquals(1, seen.size)
        assertEquals(100, seen.single().raw!!.batteryRaw)
        store.resetActivity()
        store.feed(id, frame.toByteArray(), 3) { error("sink failed") }
        assertEquals(2L, store.state.value.completedFrames)
        assertEquals(0L, store.state.value.leftForce.observedPulls)
        store.begin(ConnectionId("AA:00", 2))
        assertEquals(MaxProForceSummary(), store.state.value.leftForce)
    }

    @Test fun `interpretation names raw and scaled units and has no address or unknown text payload`() {
        val store = MaxProStore()
        val id = ConnectionId("AA:BB:CC:DD:EE:FF", 1)
        store.begin(id)
        store.feed(id, "{EVT,0F0100640A08A00A852230E00060A08400945913C10006}".toByteArray(), 100)
        val event = maxProInterpretation("c1", store.state.value)
        assertEquals("c1", event.conn)
        assertEquals(52.0, event.values["maximum_notification_bytes"]!!, 0.0)
        assertEquals(1.0, event.values["notifications_received"]!!, 0.0)
        assertEquals(100.0, event.values["battery_raw"]!!, 0.0)
        assertEquals(782.0, event.values["left_acceleration_raw"]!!, 0.0)
        assertEquals(1425.0, event.values["right_velocity_raw"]!!, 0.0)
        assertEquals(1680.0, event.values["left_pull_time_ms"]!!, 0.0)
        assertFalse(event.toString().contains(id.address))
        store.feed(id, "{EVT,ZZ}".toByteArray(), 101)
        assertTrue(maxProInterpretation("c1", store.state.value).values.isEmpty())
    }
}
