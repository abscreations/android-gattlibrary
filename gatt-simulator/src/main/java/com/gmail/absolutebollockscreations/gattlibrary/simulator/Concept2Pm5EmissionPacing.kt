package com.gmail.absolutebollockscreations.gattlibrary.simulator

import java.util.ArrayDeque

/**
 * Per-client PM5 emission pacing derived only from connection parameters reported by Android.
 * Unknown or malformed parameters deliberately produce no pacing opinion; there is no inferred
 * Garmin, MTU, acknowledgement-cadence, or device-name fallback here.
 */
internal class Concept2Pm5EmissionPacing {
    var negotiatedIntervalMs: Long = 0L
        private set
    var peripheralLatency: Int = 0
        private set
    var supervisionTimeoutMs: Long = 0L
        private set

    fun recordNegotiated(intervalUnits: Int, latency: Int, timeoutUnits: Int): Boolean {
        if (intervalUnits !in MIN_INTERVAL_UNITS..MAX_INTERVAL_UNITS ||
            latency !in MIN_LATENCY..MAX_LATENCY ||
            timeoutUnits !in MIN_TIMEOUT_UNITS..MAX_TIMEOUT_UNITS
        ) return false

        negotiatedIntervalMs = unitsToMillisecondsCeiling(intervalUnits, CONNECTION_INTERVAL_UNIT_US)
        peripheralLatency = latency
        supervisionTimeoutMs = timeoutUnits * SUPERVISION_TIMEOUT_UNIT_MS
        return true
    }

    fun effectivePeriodMs(selectedSamplePeriodMs: Long): Long =
        maxOf(selectedSamplePeriodMs, negotiatedIntervalMs)

    companion object {
        internal const val CONNECTION_INTERVAL_UNIT_US = 1_250L
        internal const val SUPERVISION_TIMEOUT_UNIT_MS = 10L
        private const val MIN_INTERVAL_UNITS = 6
        private const val MAX_INTERVAL_UNITS = 3_200
        private const val MIN_LATENCY = 0
        private const val MAX_LATENCY = 499
        private const val MIN_TIMEOUT_UNITS = 10
        private const val MAX_TIMEOUT_UNITS = 3_200

        private fun unitsToMillisecondsCeiling(units: Int, unitUs: Long): Long =
            (units * unitUs + 999L) / 1_000L
    }
}

/** Places transactional traffic ahead of queued measurements without interrupting an in-flight frame. */
internal fun <T> enqueuePm5ByPriority(queue: ArrayDeque<T>, value: T, transactional: Boolean) {
    if (transactional) queue.addFirst(value) else queue.addLast(value)
}
