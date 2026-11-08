package com.gmail.absolutebollockscreations.gattlibrary.central

/** Timing bounds are client policy, not a claimed device handshake requirement. */
internal class MaxProSubscriptionGate(private val startedAtMs: Long) {
    private var confirmed = false
    private var queryAtMs: Long? = null
    private var queryAttempted = false
    var ready: Boolean = false
        private set

    fun descriptorCompleted(success: Boolean) {
        confirmed = success
        if (!success) { ready = false; queryAtMs = null }
    }

    fun subscriptionsReady(nowMs: Long): Boolean {
        if (!confirmed || ready || timedOut(nowMs)) return false
        ready = true
        if (!queryAttempted) queryAtMs = nowMs + 2000L
        return true
    }

    fun timedOut(nowMs: Long): Boolean = !ready && nowMs - startedAtMs >= 10_000L

    fun takeFirmwareQuery(nowMs: Long): Boolean {
        val deadline = queryAtMs ?: return false
        if (!ready || queryAttempted || nowMs < deadline) return false
        queryAttempted = true
        queryAtMs = null
        return true
    }
}
