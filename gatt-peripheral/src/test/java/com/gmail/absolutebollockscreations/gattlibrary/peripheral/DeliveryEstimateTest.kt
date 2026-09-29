package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Separating what was measured from what can be believed.
 *
 * `onNotificationSent` fires when Android's stack accepts a notification into its
 * controller buffer. Nothing in the Android API reports transmission. So the
 * latency FitBridge reported for three sessions was acceptance latency — and
 * acceptance latency *improves* as the buffer fills, which is why the metrics
 * read healthiest exactly when the link was most congested:
 *
 * > `staleness max=166ms, ack max=29ms, superseded=0, failed=0`
 *
 * while a sniffer measured 7.6 notifications per second produced and 2.2 per
 * second reaching the air.
 *
 * The correction is a physical bound, not a heuristic: a peripheral transmits
 * only during a connection event, so a packet cannot be on the air sooner than
 * the next one. Acceptance faster than a single interval therefore *proves* the
 * packet is still queued.
 */
class DeliveryEstimateTest {

    @Test
    fun `acceptance faster than one interval is provably still buffered`() {
        // The observed case: 23ms acceptance on a 990ms link. The packet cannot
        // have been transmitted - the radio has not had an opportunity yet.
        val e = estimateDelivery(
            measuredStalenessMs = 166,
            stackAcceptMs = 23,
            connectionIntervalMs = 990,
        )

        assertEquals(DeliveryConfidence.BUFFERED, e.confidence)
        assertEquals("the measurement itself is unchanged", 166, e.measuredStalenessMs)
        assertEquals("worst case adds one whole interval", 1_156, e.stalenessUpperBoundMs)
    }

    @Test
    fun `acceptance of at least one interval implies a real transmission`() {
        // The stack could only have taken this long if the previous packet had to
        // go out first, so the figure reflects the air.
        val e = estimateDelivery(
            measuredStalenessMs = 1_020,
            stackAcceptMs = 990,
            connectionIntervalMs = 990,
        )

        assertEquals(DeliveryConfidence.TRANSMITTED, e.confidence)
        assertEquals(
            "nothing to add - the wait already happened",
            e.measuredStalenessMs,
            e.stalenessUpperBoundMs,
        )
    }

    @Test
    fun `an unknown interval means the acceptance cannot be interpreted`() {
        // Before any connection update there is no bound to apply. Reporting the
        // measurement as if it were sound would repeat the original mistake.
        val e = estimateDelivery(
            measuredStalenessMs = 120,
            stackAcceptMs = 5,
            connectionIntervalMs = 0,
        )

        assertEquals(DeliveryConfidence.UNKNOWN, e.confidence)
        assertEquals(120, e.stalenessUpperBoundMs)
    }

    @Test
    fun `the upper bound is never below the measurement`() {
        for (interval in intArrayOf(0, 15, 30, 100, 990, 4_000)) {
            for (accept in longArrayOf(0, 1, 23, 500, 990, 5_000)) {
                val e = estimateDelivery(200, accept, interval)
                assertTrue(
                    "interval=$interval accept=$accept",
                    e.stalenessUpperBoundMs >= e.measuredStalenessMs,
                )
            }
        }
    }

    @Test
    fun `a fast link makes acceptance meaningful again`() {
        // At a 30ms interval, a 40ms acceptance spans a real connection event, so
        // the measurement stands on its own. The distinction is about the link,
        // not about the number being small.
        val e = estimateDelivery(
            measuredStalenessMs = 45,
            stackAcceptMs = 40,
            connectionIntervalMs = 30,
        )

        assertEquals(DeliveryConfidence.TRANSMITTED, e.confidence)
        assertEquals(45, e.stalenessUpperBoundMs)
    }

    @Test
    fun `the boundary case counts as transmitted`() {
        // Exactly one interval is enough time for a transmission opportunity to
        // have occurred, so it is not evidence of buffering.
        assertEquals(
            DeliveryConfidence.TRANSMITTED,
            estimateDelivery(100, 990, 990).confidence,
        )
        assertEquals(
            DeliveryConfidence.BUFFERED,
            estimateDelivery(100, 989, 990).confidence,
        )
    }

    @Test
    fun `the reported 2026-08-05 figures would have been flagged`() {
        // Regression guard against the specific reading that misled three
        // sessions: had this existed, every one of those acknowledgements would
        // have been classified BUFFERED rather than reported as healthy.
        val observedAcks = longArrayOf(19, 22, 23, 25, 29, 33)
        for (ack in observedAcks) {
            assertEquals(
                "ack=${ack}ms on a 990ms link",
                DeliveryConfidence.BUFFERED,
                estimateDelivery(166, ack, 990).confidence,
            )
        }
    }
}
