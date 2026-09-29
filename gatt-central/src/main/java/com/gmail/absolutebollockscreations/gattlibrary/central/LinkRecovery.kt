package com.gmail.absolutebollockscreations.gattlibrary.central

internal data class LinkRecoveryTarget(
    val recoveryKey: String,
    val address: String,
    val machineConnection: Boolean,
    val activeServices: Set<BtEngineState.BtServiceType>,
    val cscRequestedRoles: Set<BtEngineState.CscRole>? = null,
) {
    init {
        require(recoveryKey.isNotBlank())
        require(address.isNotBlank())
        require(activeServices.isNotEmpty())
    }
}

internal data class LinkRecoveryAttempt(
    val target: LinkRecoveryTarget,
    val attempt: Int,
    val dueAtMs: Long,
)

/** Pure, bounded policy for memory-only recovery during the current FitBridge session. */
internal class InSessionLinkRecovery(
    private val backoffMs: List<Long> = listOf(1_000L, 2_500L, 5_000L),
) {
    private data class Entry(
        var target: LinkRecoveryTarget,
        var attempts: Int = 0,
        var scheduledAttempt: Int? = null,
    )

    private val lock = Any()
    private val entries = linkedMapOf<String, Entry>()
    private var sessionActive = false

    init {
        require(backoffMs.isNotEmpty())
        require(backoffMs.all { it >= 0L })
    }

    fun beginSession(targets: Iterable<LinkRecoveryTarget> = emptyList()) = synchronized(lock) {
        sessionActive = true
        targets.forEach { target -> entries[target.recoveryKey] = Entry(target) }
    }

    fun registerSelection(target: LinkRecoveryTarget) = synchronized(lock) {
        sessionActive = true
        entries[target.recoveryKey] = Entry(target)
    }

    fun updateTarget(target: LinkRecoveryTarget) = synchronized(lock) {
        entries[target.recoveryKey]?.target = target
    }

    fun onConnected(recoveryKey: String) = synchronized(lock) {
        entries[recoveryKey]?.apply {
            attempts = 0
            scheduledAttempt = null
        }
        Unit
    }

    fun onUnexpectedDisconnect(recoveryKey: String, nowMs: Long): LinkRecoveryAttempt? =
        synchronized(lock) { nextAttempt(recoveryKey, nowMs) }

    fun onAttemptFailed(
        failedAttempt: LinkRecoveryAttempt,
        nowMs: Long,
    ): LinkRecoveryAttempt? = synchronized(lock) {
        val entry = entries[failedAttempt.target.recoveryKey] ?: return@synchronized null
        if (!sessionActive || entry.scheduledAttempt != failedAttempt.attempt) {
            return@synchronized null
        }
        nextAttempt(failedAttempt.target.recoveryKey, nowMs)
    }

    fun shouldRun(attempt: LinkRecoveryAttempt): Boolean = synchronized(lock) {
        sessionActive && entries[attempt.target.recoveryKey]?.let { entry ->
            entry.scheduledAttempt == attempt.attempt && entry.target == attempt.target
        } == true
    }

    fun cancelTarget(recoveryKey: String) = synchronized(lock) {
        entries.remove(recoveryKey)
        Unit
    }

    fun endSession() = synchronized(lock) {
        sessionActive = false
        entries.clear()
    }

    fun isSessionActive(): Boolean = synchronized(lock) { sessionActive }

    private fun nextAttempt(recoveryKey: String, nowMs: Long): LinkRecoveryAttempt? {
        if (!sessionActive || nowMs < 0L) return null
        val entry = entries[recoveryKey] ?: return null
        if (entry.attempts >= backoffMs.size) {
            entry.scheduledAttempt = null
            return null
        }
        val attemptNumber = entry.attempts + 1
        entry.attempts = attemptNumber
        entry.scheduledAttempt = attemptNumber
        return LinkRecoveryAttempt(
            target = entry.target,
            attempt = attemptNumber,
            dueAtMs = nowMs + backoffMs[attemptNumber - 1],
        )
    }
}

internal fun recoveryKey(address: String, machineConnection: Boolean): String =
    "${if (machineConnection) "machine" else "sensor"}:${canonicalConnectionAddress(address)}"
