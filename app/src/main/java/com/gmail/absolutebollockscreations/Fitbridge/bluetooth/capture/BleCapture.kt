package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.capture

import java.util.concurrent.atomic.AtomicLong
import timber.log.Timber

/**
 * The emission seam for the diagnostic capture channel (plan §0.9.2).
 *
 * Everything that wants to record a capture event calls [emit]. The event is
 * stamped, encoded, and written to Timber on the reserved [TAG]. Routing is
 * Timber's job from there: `CaptureTree` accepts only this tag and every
 * human-log tree rejects it, so capture lines and human lines can never end up
 * in each other's files.
 *
 * Two rules from §0.9.2 are load-bearing:
 *
 * - **Not gated by `DebugLevel`.** `FitBridgeApp.shouldLog()` governs the human
 *   log. A capture is armed or not by its own switch, and must not vanish
 *   because someone set `DebugLevel = 0` — the moment a capture matters most is
 *   usually the moment nobody thought to turn logging up.
 * - **Capture events never appear in the human log.** A one-line summary does,
 *   so the two files can be correlated after the fact.
 *
 * Stamping happens here rather than in the tree because §0.9.5's ring buffer
 * will hold events that reach disk seconds after they occurred; a timestamp
 * applied at write time would misreport when the thing happened.
 */
internal object BleCapture {

    /** Reserved Timber tag. Nothing else may use it. */
    const val TAG: String = "BLECAP"

    private val sequence = AtomicLong(0L)
    private val labels = ConnectionLabels()

    @Volatile
    private var clock: () -> Long = System::currentTimeMillis

    /**
     * Session-scoped label for a device address, so no MAC reaches an event body
     * (§0.9.6). Callers pass the address; only the label is ever emitted.
     */
    fun labelFor(address: String): String = labels.labelFor(address)

    /** Stamps, encodes, and publishes one event. Never throws. */
    fun emit(event: CaptureEvent) {
        val record = CaptureRecord(
            atMs = clock(),
            seq = sequence.incrementAndGet(),
            event = event,
        )
        runCatching { Timber.tag(TAG).i(CaptureJson.encode(record)) }
    }

    /**
     * Human-log breadcrumb. Written on the ordinary tag on purpose: it is the
     * only thing that lets someone reading the human log know a capture exists
     * and roughly when it started or stopped.
     */
    fun summary(message: String, vararg args: Any?) {
        Timber.tag(SUMMARY_TAG).i(message, *args)
    }

    /** Clears session-scoped state. Called when a capture session ends. */
    fun resetSession() {
        labels.clear()
        sequence.set(0L)
    }

    /** Test seam for a deterministic clock. */
    internal fun setClockForTest(source: () -> Long) {
        clock = source
    }

    internal fun resetClock() {
        clock = System::currentTimeMillis
    }

    private const val SUMMARY_TAG = "BleCapture"
}
