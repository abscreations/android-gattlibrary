package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import timber.log.Timber

/** A machine adapter eligible for one physical link generation. */
internal sealed interface AdapterCandidate {
    val adapterId: String
    val evidence: MatchEvidence
    val rank: Int
    val requiresSetup: Boolean
    val livenessTimeoutMs: Long
}

internal data class MachineAdapterCandidate(
    override val adapterId: String,
    override val evidence: MatchEvidence,
    override val rank: Int,
    override val requiresSetup: Boolean,
    override val livenessTimeoutMs: Long,
) : AdapterCandidate {
    init {
        require(adapterId.isNotBlank())
        require(livenessTimeoutMs >= 0L)
    }
}

internal enum class AdapterPolicyState {
    NOT_STARTED,
    SETUP_REQUIRED,
    SUBSCRIPTION_PENDING,
    AWAITING_DATA,
    READY,
    SETUP_FAILED,
    CONNECTED_NO_DATA,
}

internal data class AdapterDemotion(
    val connectionId: ConnectionId,
    val fromAdapterId: String,
    val toAdapterId: String?,
    val reason: String,
    val timeoutMs: Long,
    val evidence: MatchEvidence,
)

internal fun interface AdapterDemotionRecorder {
    fun record(demotion: AdapterDemotion)
}

private val structuralDemotionRecorder = AdapterDemotionRecorder { demotion ->
    val evidence = demotion.evidence.facts.joinToString(prefix = "[", postfix = "]") { fact ->
        "${fact.kind}:${fact.structuralValue}:w${fact.weight}:${fact.collisionRisk}:${fact.note}"
    }
    Timber.tag("BLECAP").i(
        "event=demote conn=%s#%d from=%s to=%s reason=%s timeoutMs=%d evidence=%s",
        demotion.connectionId.address,
        demotion.connectionId.linkGeneration,
        demotion.fromAdapterId,
        demotion.toAdapterId ?: "none",
        demotion.reason,
        demotion.timeoutMs,
        evidence,
    )
}

internal data class AdapterPolicySnapshot(
    val connectionId: ConnectionId,
    val selected: AdapterCandidate?,
    val state: AdapterPolicyState,
    val livenessDeadlineMs: Long?,
    val demotionUsed: Boolean,
    val detail: String?,
)

/**
 * Per-link adapter selection state machine. Timers begin only after the selected adapter reports
 * subscription completion. A link generation receives at most one fallback promotion.
 */
internal class MachineAdapterPolicy(
    private val connectionId: ConnectionId,
    candidates: List<AdapterCandidate>,
    private val demotionRecorder: AdapterDemotionRecorder = structuralDemotionRecorder,
) {
    private val lock = Any()
    private val orderedCandidates = candidates.sortedWith(
        compareByDescending<AdapterCandidate> { it.evidence.rankingWeight }
            .thenByDescending { it.rank }
            .thenBy { it.adapterId },
    )
    private var selectedIndex = -1
    private var state = AdapterPolicyState.NOT_STARTED
    private var deadlineMs: Long? = null
    private var demotionUsed = false
    private var detail: String? = null

    init {
        require(candidates.map { it.adapterId }.distinct().size == candidates.size) {
            "Adapter IDs must be unique per link generation"
        }
    }

    fun start(): AdapterPolicySnapshot = synchronized(lock) {
        if (state != AdapterPolicyState.NOT_STARTED) return@synchronized snapshotLocked()
        if (orderedCandidates.isEmpty()) {
            state = AdapterPolicyState.CONNECTED_NO_DATA
            detail = "No adapter candidates matched"
            return@synchronized snapshotLocked()
        }
        selectedIndex = 0
        enterSelectedCandidateLocked()
        snapshotLocked()
    }

    fun setupSucceeded(eventConnectionId: ConnectionId): Boolean = synchronized(lock) {
        if (!owns(eventConnectionId) || state != AdapterPolicyState.SETUP_REQUIRED) {
            return@synchronized false
        }
        state = AdapterPolicyState.SUBSCRIPTION_PENDING
        detail = null
        true
    }

    fun setupFailed(eventConnectionId: ConnectionId, reason: String): Boolean = synchronized(lock) {
        require(reason.isNotBlank())
        if (!owns(eventConnectionId) || state != AdapterPolicyState.SETUP_REQUIRED) {
            return@synchronized false
        }
        state = AdapterPolicyState.SETUP_FAILED
        deadlineMs = null
        detail = reason
        true
    }

    fun subscriptionsCompleted(eventConnectionId: ConnectionId, nowMs: Long): Boolean =
        synchronized(lock) {
            require(nowMs >= 0L)
            if (!owns(eventConnectionId) || state != AdapterPolicyState.SUBSCRIPTION_PENDING) {
                return@synchronized false
            }
            val timeout = selectedLocked()?.livenessTimeoutMs ?: return@synchronized false
            state = AdapterPolicyState.AWAITING_DATA
            deadlineMs = if (timeout == 0L) null else saturatingAdd(nowMs, timeout)
            detail = null
            true
        }

    fun metricSampleArrived(
        eventConnectionId: ConnectionId,
        adapterId: String,
        arrivalMs: Long,
    ): Boolean = synchronized(lock) {
        require(arrivalMs >= 0L)
        val selected = selectedLocked()
        if (!owns(eventConnectionId) || selected?.adapterId != adapterId) return@synchronized false
        if (state == AdapterPolicyState.READY) return@synchronized true
        if (state != AdapterPolicyState.AWAITING_DATA) return@synchronized false
        if (deadlineMs?.let { arrivalMs >= it } == true) return@synchronized false
        state = AdapterPolicyState.READY
        deadlineMs = null
        detail = null
        true
    }

    fun checkLiveness(nowMs: Long): AdapterPolicySnapshot = synchronized(lock) {
        require(nowMs >= 0L)
        val deadline = deadlineMs
        if (state != AdapterPolicyState.AWAITING_DATA || deadline == null || nowMs < deadline) {
            return@synchronized snapshotLocked()
        }
        val current = selectedLocked() ?: return@synchronized terminalNoDataLocked(
            "Selected adapter disappeared",
        )
        if (demotionUsed) {
            return@synchronized terminalNoDataLocked(
                "Fallback adapter ${current.adapterId} produced no data",
            )
        }

        val nextIndex = selectedIndex + 1
        val next = orderedCandidates.getOrNull(nextIndex)
        val demotion = AdapterDemotion(
            connectionId = connectionId,
            fromAdapterId = current.adapterId,
            toAdapterId = next?.adapterId,
            reason = "No MetricSample after subscription completion",
            timeoutMs = current.livenessTimeoutMs,
            evidence = current.evidence,
        )
        runCatching { demotionRecorder.record(demotion) }
            .onFailure { error ->
                Timber.tag("BLECAP").e(error, "event=demote_record_failed conn=%s#%d",
                    connectionId.address, connectionId.linkGeneration)
            }
        demotionUsed = true
        deadlineMs = null
        if (next == null) {
            return@synchronized terminalNoDataLocked(
                "Adapter ${current.adapterId} produced no data and no fallback matched",
            )
        }
        selectedIndex = nextIndex
        enterSelectedCandidateLocked()
        snapshotLocked()
    }

    fun snapshot(): AdapterPolicySnapshot = synchronized(lock) { snapshotLocked() }

    private fun enterSelectedCandidateLocked() {
        val selected = selectedLocked()
        state = if (selected?.requiresSetup == true) {
            AdapterPolicyState.SETUP_REQUIRED
        } else {
            AdapterPolicyState.SUBSCRIPTION_PENDING
        }
        deadlineMs = null
        detail = null
    }

    private fun terminalNoDataLocked(reason: String): AdapterPolicySnapshot {
        state = AdapterPolicyState.CONNECTED_NO_DATA
        deadlineMs = null
        detail = reason
        return snapshotLocked()
    }

    private fun selectedLocked(): AdapterCandidate? = orderedCandidates.getOrNull(selectedIndex)

    private fun snapshotLocked() = AdapterPolicySnapshot(
        connectionId = connectionId,
        selected = selectedLocked(),
        state = state,
        livenessDeadlineMs = deadlineMs,
        demotionUsed = demotionUsed,
        detail = detail,
    )

    private fun owns(eventConnectionId: ConnectionId): Boolean = eventConnectionId == connectionId

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
