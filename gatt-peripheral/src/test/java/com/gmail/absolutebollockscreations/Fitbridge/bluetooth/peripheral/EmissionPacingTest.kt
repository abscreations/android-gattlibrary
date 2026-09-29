package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Emission is paced to the negotiated connection interval.
 *
 * ### The evidence
 *
 * A sniffer capture on 2026-08-06 measured a client that negotiated a **990 ms**
 * connection interval. FitBridge emitted **7.6 notifications per second**, and
 * **2.2 per second reached the air**. The surplus sat in Android's controller
 * buffer and delayed everything else on the link:
 *
 * | Transaction | Round trip |
 * |---|---|
 * | characteristic read | 991 ms |
 * | CCCD write (measurement) | 990 ms |
 * | CCCD write (control point) | **4.95 s** |
 * | Control Point response indication | **14.85 s** |
 * | characteristic read | **15.84 s** |
 *
 * The client gave up long before those arrived. Producing faster than the link
 * drains never delivers data sooner; it only builds a queue in front of the
 * client's own requests.
 *
 * ### Why the existing metrics did not catch it
 *
 * `onNotificationSent` fires when Android accepts a packet into its buffer, not
 * when the radio transmits it. Every latency figure the notifier reports
 * therefore measured buffer acceptance, and read "healthy" — ack under 30 ms,
 * nothing superseded — while the on-air rate was a third of the production rate.
 */
class EmissionPacingTest {

    private fun floor(intervalMs: Int, latency: Int = 0): Long =
        PeripheralConnection.emissionFloorMs(intervalMs, latency)

    @Test
    fun `an unknown interval yields no opinion`() {
        // Before any connection update the caller keeps its configured period;
        // guessing a floor from nothing would be worse than not pacing at all.
        assertEquals(0L, floor(0))
        assertEquals(0L, floor(-1))
    }

    @Test
    fun `the observed garmin link paces to its interval`() {
        assertEquals(990L, floor(990))
    }

    @Test
    fun `peripheral latency extends the floor`() {
        // A peripheral permitted to skip events may go that many further without
        // transmitting, so the reachable interval is interval x (latency + 1).
        assertEquals(1_980L, floor(990, latency = 1))
        assertEquals(2_970L, floor(990, latency = 2))
    }

    @Test
    fun `a fast link does not raise the floor above the configured period`() {
        // A phone at a 30 ms interval can carry far more than FitBridge produces,
        // so pacing must not become a floor of its own - the caller takes the
        // maximum of the two.
        val linkFloor = floor(30)
        assertTrue(
            "a fast link must not out-vote the configured period",
            linkFloor < FtmsPeripheralNotifier.DEFAULT_PERIOD_MS,
        )
        assertEquals(
            FtmsPeripheralNotifier.DEFAULT_PERIOD_MS,
            maxOf(FtmsPeripheralNotifier.DEFAULT_PERIOD_MS, linkFloor),
        )
    }

    @Test
    fun `an implausible interval cannot wedge the pump`() {
        // A genuinely slow link should be paced slowly, so the cap is generous
        // rather than tuned. It exists only so a garbage value cannot silence
        // the stream indefinitely.
        assertEquals(PeripheralConnection.MAX_EMISSION_FLOOR_MS, floor(60_000))
        assertEquals(PeripheralConnection.MAX_EMISSION_FLOOR_MS, floor(990, latency = 500))
    }

    @Test
    fun `a low-mtu client is paced from an assumed interval, not a measured one`() {
        // Android exposes no connection parameters to a GATT server, so the MTU
        // is the only signal available. Stated as an assumption because it is
        // one: the watch measured on 2026-08-06 was at MTU 23 and 990 ms, and
        // the estimate errs slow deliberately.
        assertEquals(
            PeripheralConnection.ASSUMED_LOW_MTU_INTERVAL_MS,
            PeripheralConnection.assumedIntervalForMtu(23),
        )
        assertTrue(
            "the assumption must not be faster than the interval it models",
            PeripheralConnection.ASSUMED_LOW_MTU_INTERVAL_MS >= 990,
        )
    }

    @Test
    fun `a client with a real mtu is not throttled on a guess`() {
        assertEquals(0, PeripheralConnection.assumedIntervalForMtu(247))
        assertEquals(0, PeripheralConnection.assumedIntervalForMtu(
            PeripheralConnection.COMPACT_FRAMING_MTU_THRESHOLD))
    }

    @Test
    fun `wire units are converted to milliseconds`() {
        // The Bluetooth connection interval unit is 1.25 ms. 792 units is the
        // 990 ms the observed client actually negotiated.
        assertEquals(1_250, PeripheralConnection.CONNECTION_INTERVAL_UNIT_US)
        assertEquals(990, 792 * PeripheralConnection.CONNECTION_INTERVAL_UNIT_US / 1000)
    }

    @Test
    fun `pacing at the observed interval matches the observed on-air capacity`() {
        // The sniffer measured 2.2 notifications per second on a 990 ms link,
        // which is one two-frame record per connection event. Pacing to 990 ms
        // produces exactly that, instead of the 7.6 per second that caused the
        // backlog.
        val recordsPerSecond = 1000.0 / floor(990)
        assertTrue("expected about one record per second, got $recordsPerSecond",
            recordsPerSecond in 0.9..1.1)
    }
}
