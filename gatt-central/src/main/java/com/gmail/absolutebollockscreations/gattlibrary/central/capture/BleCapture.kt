package com.gmail.absolutebollockscreations.gattlibrary.central.capture

import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi
import java.util.concurrent.atomic.AtomicLong
import timber.log.Timber

/**
 * The emission seam and arming switch for the diagnostic capture channel
 * (plan §0.9.2, §0.9.5).
 *
 * Everything that wants to record a capture event calls [emit]. Where the event
 * goes depends on whether capture is armed:
 *
 * - **Disarmed** — into [CaptureRing], in memory, no I/O. This is the default and
 *   the steady state.
 * - **Armed** — straight out on the reserved [TAG], where `CaptureTree` picks it
 *   up and every human-log tree rejects it.
 *
 * [arm] flushes the ring before opening the live stream, so the file begins
 * *before* whatever triggered it. A capture that started at the trigger would
 * record the symptom and none of the cause.
 *
 * Two rules from §0.9.2 are load-bearing:
 *
 * - **Not gated by `DebugLevel`.** `FitBridgeApp.shouldLog()` governs the human
 *   log. A capture is armed or not by its own switch, and must not vanish
 *   because someone set `DebugLevel = 0` — the moment a capture matters most is
 *   usually the moment nobody thought to turn logging up.
 * - **Capture events never appear in the human log.** A one-line summary does,
 *   so the two files can be correlated.
 *
 * Stamping happens here rather than in the tree because the ring holds events
 * that may reach disk seconds later; a timestamp applied at write time would
 * misreport when the thing happened.
 */
@GattLibraryInternalApi
object BleCapture {

    /**
     * Reserved Timber tag for the central role.
     *
     * Retained as a constant because it is the original tag and several call
     * sites and tests name it directly. [CaptureRole.tag] is the general form.
     */
    const val TAG: String = "BLECAP"

    private val lock = Any()
    private val sequence = AtomicLong(0L)
    private val labels = ConnectionLabels()
    private val ring = CaptureRing()

    /** Guarded by [lock] for the arm/emit decision; volatile for cheap reads. */
    @Volatile
    private var armed = false

    @Volatile
    private var armedBy: CaptureTrigger? = null

    @Volatile
    private var clock: () -> Long = System::currentTimeMillis

    /** True while events are reaching disk. */
    fun isArmed(): Boolean = armed

    /** What armed the current capture, or null when disarmed. */
    fun armedBy(): CaptureTrigger? = armedBy

    /**
     * Session-scoped label for a device address, so no MAC reaches an event body
     * (§0.9.6). Callers pass the address; only the label is ever emitted.
     */
    fun labelFor(address: String): String = labels.labelFor(address)

    /**
     * Records one event. Never throws, and never blocks on I/O — disarmed it is
     * an in-memory append, armed it is a non-blocking enqueue inside
     * `RotatingLogSink`. Safe to call from a GATT callback thread.
     */
    fun emit(event: CaptureEvent, role: CaptureRole = CaptureRole.CENTRAL) {
        val record = CaptureRecord(
            atMs = clock(),
            // One counter across both roles on purpose: interleaving the two
            // files by seq reconstructs cause and effect across the bridge - the
            // upstream notification that produced a sample, and the downstream
            // notification that carried it onward.
            seq = sequence.incrementAndGet(),
            event = event,
            role = role,
        )
        synchronized(lock) {
            if (armed) publishLocked(record) else ring.offer(record)
        }
    }

    /**
     * Begins writing to disk, flushing the ring first.
     *
     * Idempotent: a second trigger while already armed is ignored rather than
     * restarting the file, because the run-up to the *first* trigger is usually
     * the interesting part.
     *
     * The ring flush happens while holding [lock], so a concurrent [emit] cannot
     * overtake the backlog and produce out-of-order sequence numbers. That costs
     * a few milliseconds of contention once per arming — acceptable, since every
     * publish is a non-blocking enqueue and arming happens at most once a
     * session.
     */
    fun arm(trigger: CaptureTrigger) {
        // Plain vars with initialisers rather than vals assigned inside the
        // lambda: an early non-local return past a `val` assignment is exactly
        // the definite-assignment shape that has bitten this file before.
        var flushed = 0
        var dropped = 0
        synchronized(lock) {
            if (armed) return
            armed = true
            armedBy = trigger
            dropped = ring.evictedCount()
            val backlog = ring.drain()
            ring.resetEvicted()
            if (dropped > 0) {
                // Reported before the backlog it refers to, so a reader knows the
                // history is truncated before reading any of it.
                publishLocked(
                    CaptureRecord(
                        atMs = clock(),
                        // Sequence zero marks a synthetic record: generated by the
                        // channel rather than by anything that happened on the air.
                        seq = 0L,
                        event = DroppedEvent(count = dropped),
                    ),
                )
            }
            backlog.forEach { publishLocked(it) }
            flushed = backlog.size
        }
        summary(
            "capture armed by %s; flushed %d buffered event(s)%s",
            trigger.wireName,
            flushed,
            if (dropped > 0) ", $dropped older event(s) had already been evicted" else "",
        )
    }

    /**
     * Stops writing to disk. The ring keeps filling, so a later trigger still has
     * history to flush.
     */
    fun disarm(reason: String) {
        var was: CaptureTrigger? = null
        synchronized(lock) {
            if (!armed) return
            armed = false
            was = armedBy
            armedBy = null
        }
        summary("capture disarmed (%s); was armed by %s", reason, was?.wireName ?: "unknown")
    }

    /**
     * Human-log breadcrumb. Written on an ordinary tag on purpose: it is the only
     * thing that lets someone reading the human log know a capture exists.
     */
    fun summary(message: String, vararg args: Any?) {
        Timber.tag(SUMMARY_TAG).i(message, *args)
    }

    /** Clears session-scoped state. Called when a capture session ends. */
    fun resetSession() {
        synchronized(lock) {
            armed = false
            armedBy = null
            ring.clear()
            labels.clear()
            sequence.set(0L)
        }
    }

    /** Buffered-but-unwritten event count. Diagnostic and test seam. */
    fun bufferedCount(): Int = synchronized(lock) { ring.size() }

    private fun publishLocked(record: CaptureRecord) {
        runCatching { Timber.tag(record.role.tag).i(CaptureJson.encode(record)) }
    }

    /** Test seam for a deterministic clock. */
    @GattLibraryInternalApi
    fun setClockForTest(source: () -> Long) {
        clock = source
    }

    @GattLibraryInternalApi
    fun resetClock() {
        clock = System::currentTimeMillis
    }

    private const val SUMMARY_TAG = "BleCapture"
}
