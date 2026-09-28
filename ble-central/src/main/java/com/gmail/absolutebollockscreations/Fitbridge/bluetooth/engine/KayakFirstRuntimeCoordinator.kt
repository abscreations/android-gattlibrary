package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

internal enum class KayakFirstRuntimeTransactionKind { POLL, START, STOP }

internal data class KayakFirstRuntimeTransaction(
    val connectionId: ConnectionId,
    val kind: KayakFirstRuntimeTransactionKind,
    val command: KayakFirstCommand,
    val sequence: Long,
    val chunks: List<ByteArray>,
    val acknowledgementDeadlineMs: Long? = null,
)

internal sealed interface KayakFirstRuntimeAction {
    data class Send(val transaction: KayakFirstRuntimeTransaction) : KayakFirstRuntimeAction
    data class ConnectedNoData(val consecutiveMisses: Int) : KayakFirstRuntimeAction
}

/**
 * Pure generation-owned poll and local-session scheduler.
 *
 * One FFE1 transaction may exist at a time. Polls use a fixed one-second cadence, never retry and
 * never catch up. Start/stop are accepted only as explicit local actions and are sent once.
 */
internal class KayakFirstRuntimeCoordinator(
    val connectionId: ConnectionId,
    private val payloadCapacity: Int = KayakFirstCommandCodec.DEFAULT_PAYLOAD_CAPACITY,
) {
    @Volatile
    var activeTransaction: KayakFirstRuntimeTransaction? = null
        private set

    @Volatile
    var sessionActive: Boolean = false
        private set

    @Volatile
    var consecutivePollMisses: Int = 0
        private set

    private var running = false
    private var nextPollAtMs: Long? = null
    private var nextSequence = 1L
    private var pendingSessionCommand: KayakFirstRuntimeTransactionKind? = null
    private var noDataReported = false

    @Synchronized
    fun start(nowMs: Long): List<KayakFirstRuntimeAction> {
        if (running) return emptyList()
        running = true
        nextPollAtMs = nowMs
        return tick(nowMs)
    }

    @Synchronized
    fun requestStart(): Boolean {
        if (!running || sessionActive || pendingSessionCommand != null) return false
        pendingSessionCommand = KayakFirstRuntimeTransactionKind.START
        return true
    }

    @Synchronized
    fun requestStop(): Boolean {
        if (!running || !sessionActive || pendingSessionCommand != null) return false
        pendingSessionCommand = KayakFirstRuntimeTransactionKind.STOP
        return true
    }

    @Synchronized
    fun onWriteCompleted(
        callbackConnectionId: ConnectionId,
        kind: KayakFirstRuntimeTransactionKind,
        sequence: Long,
        success: Boolean,
        nowMs: Long,
    ): List<KayakFirstRuntimeAction> {
        val active = activeTransaction ?: return emptyList()
        if (callbackConnectionId != connectionId || active.connectionId != callbackConnectionId ||
            active.kind != kind || active.sequence != sequence ||
            active.acknowledgementDeadlineMs != null
        ) {
            return emptyList()
        }
        if (!success) return finishMissOrFailure(active.kind, nowMs)
        activeTransaction = active.copy(
            acknowledgementDeadlineMs = safeAdd(nowMs, ACKNOWLEDGEMENT_TIMEOUT_MS),
        )
        return emptyList()
    }

    @Synchronized
    fun onMeasurement(
        callbackConnectionId: ConnectionId,
        nowMs: Long,
    ): List<KayakFirstRuntimeAction> {
        if (callbackConnectionId != connectionId || !running) return emptyList()
        if (activeTransaction?.kind == KayakFirstRuntimeTransactionKind.POLL) {
            activeTransaction = null
        }
        consecutivePollMisses = 0
        noDataReported = false
        nextPollAtMs = safeAdd(nowMs, POLL_INTERVAL_MS)
        return issuePendingSessionOrEmpty()
    }

    @Synchronized
    fun onSessionResponse(
        callbackConnectionId: ConnectionId,
        responseFields: List<String>,
        nowMs: Long,
    ): List<KayakFirstRuntimeAction> {
        if (callbackConnectionId != connectionId || !running) return emptyList()
        val active = activeTransaction ?: return emptyList()
        val accepted = when (active.kind) {
            KayakFirstRuntimeTransactionKind.START -> responseFields.firstOrNull() == "1"
            KayakFirstRuntimeTransactionKind.STOP -> responseFields.firstOrNull() == "3"
            KayakFirstRuntimeTransactionKind.POLL -> false
        }
        if (!accepted) return emptyList()
        sessionActive = active.kind == KayakFirstRuntimeTransactionKind.START
        activeTransaction = null
        nextPollAtMs = safeAdd(nowMs, POLL_INTERVAL_MS)
        return emptyList()
    }

    @Synchronized
    fun tick(nowMs: Long): List<KayakFirstRuntimeAction> {
        if (!running) return emptyList()
        val active = activeTransaction
        if (active != null) {
            val deadline = active.acknowledgementDeadlineMs
            return if (deadline != null && nowMs >= deadline) {
                finishMissOrFailure(active.kind, nowMs)
            } else {
                emptyList()
            }
        }
        issuePendingSessionOrEmpty().takeIf { it.isNotEmpty() }?.let { return it }
        val due = nextPollAtMs ?: return emptyList()
        if (nowMs < due) return emptyList()
        return listOf(issue(KayakFirstRuntimeTransactionKind.POLL))
    }

    @Synchronized
    fun cancel(disconnected: Boolean = false) {
        running = false
        activeTransaction = null
        pendingSessionCommand = null
        nextPollAtMs = null
        sessionActive = false
        if (disconnected) consecutivePollMisses = 0
        noDataReported = false
    }

    private fun issuePendingSessionOrEmpty(): List<KayakFirstRuntimeAction> {
        if (activeTransaction != null) return emptyList()
        val kind = pendingSessionCommand ?: return emptyList()
        pendingSessionCommand = null
        return listOf(issue(kind))
    }

    private fun issue(kind: KayakFirstRuntimeTransactionKind): KayakFirstRuntimeAction.Send {
        val command = when (kind) {
            KayakFirstRuntimeTransactionKind.POLL -> KayakFirstCommand.Poll
            KayakFirstRuntimeTransactionKind.START -> KayakFirstCommand.Start
            KayakFirstRuntimeTransactionKind.STOP -> KayakFirstCommand.Stop
        }
        val transaction = KayakFirstRuntimeTransaction(
            connectionId = connectionId,
            kind = kind,
            command = command,
            sequence = nextSequence++,
            chunks = KayakFirstCommandCodec.chunks(command, payloadCapacity),
        )
        // This is local app session intent, not a claim that the console acknowledged a control.
        // Updating when the one-shot command is issued keeps Stop reachable even if Start's
        // optional response is lost; neither command is retried automatically.
        when (kind) {
            KayakFirstRuntimeTransactionKind.START -> sessionActive = true
            KayakFirstRuntimeTransactionKind.STOP -> sessionActive = false
            KayakFirstRuntimeTransactionKind.POLL -> Unit
        }
        activeTransaction = transaction
        return KayakFirstRuntimeAction.Send(transaction)
    }

    private fun finishMissOrFailure(
        kind: KayakFirstRuntimeTransactionKind,
        nowMs: Long,
    ): List<KayakFirstRuntimeAction> {
        activeTransaction = null
        nextPollAtMs = safeAdd(nowMs, POLL_INTERVAL_MS)
        if (kind != KayakFirstRuntimeTransactionKind.POLL) return emptyList()
        consecutivePollMisses += 1
        if (consecutivePollMisses < CONSECUTIVE_MISS_LIMIT || noDataReported) return emptyList()
        noDataReported = true
        return listOf(KayakFirstRuntimeAction.ConnectedNoData(consecutivePollMisses))
    }

    private fun safeAdd(value: Long, increment: Long): Long =
        if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment

    companion object {
        const val POLL_INTERVAL_MS = 1_000L
        const val ACKNOWLEDGEMENT_TIMEOUT_MS = 1_000L
        const val CONSECUTIVE_MISS_LIMIT = 3
    }
}
