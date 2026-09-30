package com.gmail.absolutebollockscreations.gattlibrary.central.capture

import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi
import java.util.ArrayDeque
import com.gmail.absolutebollockscreations.gattlibrary.core.capture.CaptureRecord

/**
 * Bounded in-memory buffer of the most recent capture events (plan §0.9.5).
 *
 * Always-on disk capture is a battery and storage cost users should not pay by
 * default, but a capture that begins at the moment of a trigger is usually
 * useless: what matters is the run-up. So events accumulate here at all times
 * with no I/O, and arming flushes the ring *first* so the file starts before the
 * thing that armed it.
 *
 * Oldest-first eviction: the newest events are the ones nearest the trigger, so
 * they are the ones worth keeping. Every eviction is counted and reported as a
 * `dropped` event when the ring is flushed, so a reader can tell a quiet period
 * from a truncated one.
 *
 * **Not thread-safe.** [BleCapture] owns the lock; this class is deliberately a
 * plain data structure so its eviction and drain behavior can be tested without
 * threads.
 */
internal class CaptureRing(private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val items = ArrayDeque<CaptureRecord>(capacity)

    private var evicted = 0

    /** Adds a record, evicting the oldest if the ring is full. */
    fun offer(record: CaptureRecord) {
        if (items.size >= capacity) {
            items.removeFirst()
            evicted++
        }
        items.addLast(record)
    }

    /** Buffered records, oldest first. Clears the ring. */
    fun drain(): List<CaptureRecord> {
        if (items.isEmpty()) return emptyList()
        val drained = ArrayList<CaptureRecord>(items)
        items.clear()
        return drained
    }

    /** Eviction count since the last [resetEvicted], for the `dropped` marker. */
    fun evictedCount(): Int = evicted

    fun resetEvicted() {
        evicted = 0
    }

    fun size(): Int = items.size

    fun clear() {
        items.clear()
        evicted = 0
    }

    companion object {
        /**
         * ~2000 events per §0.9.5. At roughly 150 bytes of eventual JSON per
         * event that is on the order of 300 KB of retained objects — the cost of
         * being able to explain a failure rather than merely notice it.
         */
        const val DEFAULT_CAPACITY = 2_000
    }
}

/**
 * Why a capture began. Written into the human-log summary so the two files can
 * be correlated, and named in the plan's §0.9.5 trigger table.
 */
@GattLibraryInternalApi
enum class CaptureTrigger(val wireName: String) {
    /** Debugging-menu toggle (§0.9.8). */
    MANUAL("manual"),

    /**
     * A §0.7a liveness demotion. §0.9.1 calls this the highest-value event in
     * the system: the matcher was wrong and FitBridge now knows it.
     */
    LIVENESS_DEMOTION("liveness_demotion"),

    /** Connected to a device that matched no adapter candidate — the triage case. */
    UNMATCHED_DEVICE("unmatched_device"),

    /** A parser threw or rejected a packet on a bounds check. */
    PARSER_REJECTION("parser_rejection"),

    /** A vendor initialization sequence failed. Phases 6, 8, 12–16. */
    VENDOR_SETUP_FAILURE("vendor_setup_failure"),

    /** Flywheel's documented connection-parameter disconnect around 30 seconds (Phase 12.5). */
    FLYWHEEL_CONNECTION_INTERVAL_QUIRK("flywheel_connection_interval_quirk"),
}
