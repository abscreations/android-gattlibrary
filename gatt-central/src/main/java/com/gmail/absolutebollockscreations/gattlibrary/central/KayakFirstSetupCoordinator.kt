package com.gmail.absolutebollockscreations.gattlibrary.central

enum class KayakFirstSetupState {
    DISCOVERING,
    SUBSCRIBING,
    RESET_A,
    RESET_B,
    HANDSHAKE,
    HANDSHAKE_UNCONFIRMED,
    DISPLAY,
    READY,
    FAILED,
    CANCELLED,
}

internal enum class KayakFirstTransactionKind {
    RESET,
    HANDSHAKE,
    DISPLAY,
    ;

    /**
     * Token the console is expected to answer with.
     *
     * The handshake token depends on the dialect sent, so it is resolved from the variant rather
     * than fixed on the kind.
     */
    fun responseToken(handshakeVariant: KayakFirstHandshakeVariant): String = when (this) {
        RESET -> "1"
        HANDSHAKE -> when (handshakeVariant) {
            KayakFirstHandshakeVariant.LEGACY -> "2"
            KayakFirstHandshakeVariant.BULL -> "21"
        }
        DISPLAY -> "5"
    }
}

internal enum class KayakFirstTransactionResult {
    SUCCESS,
    REJECTED,
    MALFORMED,
    WRITE_FAILED,
    TIMEOUT,
    CANCELLED,
    DISCONNECTED,
}

/** Immutable record of one setup attempt. Payload bytes are produced only by the typed codec. */
internal data class KayakFirstTransaction(
    val connectionId: ConnectionId,
    val kind: KayakFirstTransactionKind,
    val command: KayakFirstCommand,
    val attempt: Int,
    val maximumAttempts: Int,
    val chunks: List<ByteArray>,
    val acknowledgementDeadlineMs: Long? = null,
    val terminalResult: KayakFirstTransactionResult? = null,
)

internal enum class KayakFirstSetupFailure {
    SUBSCRIPTION_FAILED,
    RESET_A_EXHAUSTED,
    RESET_B_EXHAUSTED,
    DISPLAY_EXHAUSTED,
}

internal sealed interface KayakFirstSetupAction {
    data class Send(val transaction: KayakFirstTransaction) : KayakFirstSetupAction
    data object Ready : KayakFirstSetupAction
    data class Failed(val reason: KayakFirstSetupFailure) : KayakFirstSetupAction
}

/**
 * Pure, connection-generation-owned implementation of the D98 setup schedule.
 *
 * Time enters only through method arguments. A command acknowledgement deadline begins after every
 * chunk has been accepted by the shared GATT queue, never when the command is merely planned.
 * Mismatched tokens, stale generations, unsolicited measurements, and responses received during a
 * retry/settle interval cannot complete a transaction.
 */
internal class KayakFirstSetupCoordinator(
    val connectionId: ConnectionId,
    val displayConfiguration: KayakFirstDisplayConfiguration,
    val sportMode: KayakFirstSportMode = KayakFirstSportMode.CANOEING,
    val handshakeVariant: KayakFirstHandshakeVariant = KayakFirstHandshakeVariant.LEGACY,
    val athleteWeightKg: Int = KayakFirstCommandCodec.NEUTRAL_WEIGHT_KG,
    val boatWeightKg: Int = KayakFirstCommandCodec.DEFAULT_BOAT_WEIGHT_KG,
    private val payloadCapacity: Int = KayakFirstCommandCodec.DEFAULT_PAYLOAD_CAPACITY,
) {
    init {
        require(payloadCapacity > 0)
    }

    @Volatile
    var state: KayakFirstSetupState = KayakFirstSetupState.DISCOVERING
        private set

    @Volatile
    var activeTransaction: KayakFirstTransaction? = null
        private set

    private var handshake: KayakFirstCommand.Handshake? = null
    private var acknowledgementDeadlineMs: Long? = null
    private var subscriptionDeadlineMs: Long? = null
    private var retryAtMs: Long? = null
    private var settleUntilMs: Long? = null
    private var nextAttempt = 1

    private val completed = mutableListOf<KayakFirstTransaction>()
    val completedTransactions: List<KayakFirstTransaction>
        get() = synchronized(this) { completed.toList() }

    @Synchronized
    fun beginSubscription(nowMs: Long): Boolean {
        if (state != KayakFirstSetupState.DISCOVERING) return false
        state = KayakFirstSetupState.SUBSCRIBING
        subscriptionDeadlineMs = safeAdd(nowMs, SUBSCRIPTION_DEADLINE_MS)
        return true
    }

    @Synchronized
    fun subscriptionFailed(callbackConnectionId: ConnectionId): List<KayakFirstSetupAction> {
        if (callbackConnectionId != connectionId || state != KayakFirstSetupState.SUBSCRIBING) {
            return emptyList()
        }
        state = KayakFirstSetupState.FAILED
        return listOf(KayakFirstSetupAction.Failed(KayakFirstSetupFailure.SUBSCRIPTION_FAILED))
    }

    @Synchronized
    fun onSubscribed(
        callbackConnectionId: ConnectionId,
        unixEpochSeconds: Long,
        timezoneOffsetMinutes: Int,
        nowMs: Long,
    ): List<KayakFirstSetupAction> {
        if (callbackConnectionId != connectionId || state != KayakFirstSetupState.SUBSCRIBING) {
            return emptyList()
        }
        val deadline = subscriptionDeadlineMs ?: return emptyList()
        if (nowMs >= deadline) {
            subscriptionDeadlineMs = null
            state = KayakFirstSetupState.FAILED
            return listOf(
                KayakFirstSetupAction.Failed(KayakFirstSetupFailure.SUBSCRIPTION_FAILED),
            )
        }
        handshake = when (handshakeVariant) {
            KayakFirstHandshakeVariant.LEGACY -> KayakFirstCommand.LegacyHandshake(
                unixEpochSeconds = unixEpochSeconds,
                timezoneOffsetMinutes = timezoneOffsetMinutes,
                sport = sportMode,
                athleteWeightKg = athleteWeightKg,
            )
            KayakFirstHandshakeVariant.BULL -> KayakFirstCommand.BullHandshake(
                unixEpochSeconds = unixEpochSeconds,
                timezoneOffsetMinutes = timezoneOffsetMinutes,
                athleteWeightKg = athleteWeightKg,
                boatWeightKg = boatWeightKg,
            )
        }
        subscriptionDeadlineMs = null
        state = KayakFirstSetupState.RESET_A
        return issueAttempt(1)
    }

    @Synchronized
    fun onWriteCompleted(
        callbackConnectionId: ConnectionId,
        kind: KayakFirstTransactionKind,
        attempt: Int,
        success: Boolean,
        nowMs: Long,
    ): List<KayakFirstSetupAction> {
        val active = activeTransaction ?: return emptyList()
        if (
            callbackConnectionId != connectionId || active.connectionId != callbackConnectionId ||
            active.kind != kind || active.attempt != attempt || active.terminalResult != null ||
            active.acknowledgementDeadlineMs != null
        ) {
            return emptyList()
        }
        return if (success) {
            acknowledgementDeadlineMs = safeAdd(nowMs, ACK_DEADLINE_MS)
            activeTransaction = active.copy(acknowledgementDeadlineMs = acknowledgementDeadlineMs)
            emptyList()
        } else {
            completeAttempt(KayakFirstTransactionResult.WRITE_FAILED, nowMs)
        }
    }

    @Synchronized
    fun onBinaryResetAcknowledgement(
        callbackConnectionId: ConnectionId,
        nowMs: Long,
    ): List<KayakFirstSetupAction> =
        acceptResponse(
            callbackConnectionId,
            KayakFirstTransactionKind.RESET,
            accepted = true,
            rejectionResult = null,
            nowMs,
        )

    @Synchronized
    fun onTextResponse(
        callbackConnectionId: ConnectionId,
        response: KayakFirstTextResponse,
        nowMs: Long,
    ): List<KayakFirstSetupAction> {
        val kind = when (response.kind) {
            KayakFirstResponseKind.RESET -> KayakFirstTransactionKind.RESET
            KayakFirstResponseKind.HANDSHAKE -> KayakFirstTransactionKind.HANDSHAKE
            KayakFirstResponseKind.DISPLAY_CONFIGURATION -> KayakFirstTransactionKind.DISPLAY
            KayakFirstResponseKind.POLL,
            KayakFirstResponseKind.SESSION,
            KayakFirstResponseKind.KNOWN_OUT_OF_PROFILE,
            -> return emptyList()
        }
        if (
            kind == KayakFirstTransactionKind.HANDSHAKE &&
            response.token != kind.responseToken(handshakeVariant)
        ) {
            // A command-2 line cannot complete a command-21 transaction, or the reverse.
            return emptyList()
        }
        val accepted = when (kind) {
            KayakFirstTransactionKind.RESET,
            KayakFirstTransactionKind.HANDSHAKE,
            -> response.fields.isNotEmpty()
            KayakFirstTransactionKind.DISPLAY -> response.isDisplaySuccess
        }
        val rejection = if (kind == KayakFirstTransactionKind.DISPLAY) {
            KayakFirstTransactionResult.REJECTED
        } else {
            KayakFirstTransactionResult.MALFORMED
        }
        return acceptResponse(callbackConnectionId, kind, accepted, rejection, nowMs)
    }

    @Synchronized
    fun tick(nowMs: Long): List<KayakFirstSetupAction> {
        if (state in TERMINAL_STATES) return emptyList()

        subscriptionDeadlineMs?.let { deadline ->
            if (state == KayakFirstSetupState.SUBSCRIBING && nowMs >= deadline) {
                subscriptionDeadlineMs = null
                state = KayakFirstSetupState.FAILED
                return listOf(
                    KayakFirstSetupAction.Failed(KayakFirstSetupFailure.SUBSCRIPTION_FAILED),
                )
            }
        }

        settleUntilMs?.let { deadline ->
            if (nowMs >= deadline) {
                settleUntilMs = null
                return advanceAfterSettle()
            }
        }
        retryAtMs?.let { deadline ->
            if (nowMs >= deadline) {
                retryAtMs = null
                return issueAttempt(nextAttempt)
            }
        }
        acknowledgementDeadlineMs?.let { deadline ->
            if (nowMs >= deadline) {
                return completeAttempt(KayakFirstTransactionResult.TIMEOUT, nowMs)
            }
        }
        return emptyList()
    }

    @Synchronized
    fun cancel(disconnected: Boolean = false) {
        if (state == KayakFirstSetupState.CANCELLED) return
        activeTransaction?.let { active ->
            completed += active.copy(
                terminalResult = if (disconnected) {
                    KayakFirstTransactionResult.DISCONNECTED
                } else {
                    KayakFirstTransactionResult.CANCELLED
                },
            )
        }
        clearTiming()
        activeTransaction = null
        state = KayakFirstSetupState.CANCELLED
    }

    private fun acceptResponse(
        callbackConnectionId: ConnectionId,
        kind: KayakFirstTransactionKind,
        accepted: Boolean,
        rejectionResult: KayakFirstTransactionResult?,
        nowMs: Long,
    ): List<KayakFirstSetupAction> {
        val active = activeTransaction ?: return emptyList()
        val deadline = acknowledgementDeadlineMs ?: return emptyList()
        if (
            callbackConnectionId != connectionId || active.connectionId != callbackConnectionId ||
            active.kind != kind || nowMs >= deadline
        ) {
            return emptyList()
        }
        if (!accepted) {
            return completeAttempt(requireNotNull(rejectionResult), nowMs)
        }
        completed += active.copy(terminalResult = KayakFirstTransactionResult.SUCCESS)
        activeTransaction = null
        acknowledgementDeadlineMs = null
        subscriptionDeadlineMs = null
        settleUntilMs = safeAdd(nowMs, settleDurationFor(state))
        return emptyList()
    }

    private fun completeAttempt(
        result: KayakFirstTransactionResult,
        nowMs: Long,
    ): List<KayakFirstSetupAction> {
        val active = activeTransaction ?: return emptyList()
        completed += active.copy(terminalResult = result)
        activeTransaction = null
        acknowledgementDeadlineMs = null
        if (active.attempt < active.maximumAttempts) {
            nextAttempt = active.attempt + 1
            retryAtMs = safeAdd(nowMs, RETRY_DELAY_MS)
            return emptyList()
        }

        return if (state == KayakFirstSetupState.HANDSHAKE) {
            state = KayakFirstSetupState.HANDSHAKE_UNCONFIRMED
            settleUntilMs = safeAdd(nowMs, HANDSHAKE_SETTLE_MS)
            emptyList()
        } else {
            val failure = when (state) {
                KayakFirstSetupState.RESET_A -> KayakFirstSetupFailure.RESET_A_EXHAUSTED
                KayakFirstSetupState.RESET_B -> KayakFirstSetupFailure.RESET_B_EXHAUSTED
                else -> KayakFirstSetupFailure.DISPLAY_EXHAUSTED
            }
            state = KayakFirstSetupState.FAILED
            retryAtMs = null
            listOf(KayakFirstSetupAction.Failed(failure))
        }
    }

    private fun advanceAfterSettle(): List<KayakFirstSetupAction> {
        state = when (state) {
            KayakFirstSetupState.RESET_A -> KayakFirstSetupState.RESET_B
            KayakFirstSetupState.RESET_B -> KayakFirstSetupState.HANDSHAKE
            KayakFirstSetupState.HANDSHAKE,
            KayakFirstSetupState.HANDSHAKE_UNCONFIRMED,
            -> KayakFirstSetupState.DISPLAY
            KayakFirstSetupState.DISPLAY -> {
                clearTiming()
                KayakFirstSetupState.READY
            }
            else -> return emptyList()
        }
        return if (state == KayakFirstSetupState.READY) {
            listOf(KayakFirstSetupAction.Ready)
        } else {
            issueAttempt(1)
        }
    }

    private fun issueAttempt(attempt: Int): List<KayakFirstSetupAction> {
        val command = when (state) {
            KayakFirstSetupState.RESET_A,
            KayakFirstSetupState.RESET_B,
            -> KayakFirstCommand.Reset
            KayakFirstSetupState.HANDSHAKE -> requireNotNull(handshake)
            KayakFirstSetupState.DISPLAY ->
                KayakFirstCommand.DisplayConfiguration(displayConfiguration)
            else -> return emptyList()
        }
        val kind = when (state) {
            KayakFirstSetupState.RESET_A,
            KayakFirstSetupState.RESET_B,
            -> KayakFirstTransactionKind.RESET
            KayakFirstSetupState.HANDSHAKE -> KayakFirstTransactionKind.HANDSHAKE
            else -> KayakFirstTransactionKind.DISPLAY
        }
        clearTiming()
        val transaction = KayakFirstTransaction(
            connectionId = connectionId,
            kind = kind,
            command = command,
            attempt = attempt,
            maximumAttempts = MAX_ATTEMPTS,
            chunks = KayakFirstCommandCodec.chunks(command, payloadCapacity),
        )
        activeTransaction = transaction
        return listOf(KayakFirstSetupAction.Send(transaction))
    }

    private fun settleDurationFor(current: KayakFirstSetupState): Long = when (current) {
        KayakFirstSetupState.RESET_A -> RESET_A_SETTLE_MS
        KayakFirstSetupState.RESET_B -> RESET_B_SETTLE_MS
        KayakFirstSetupState.HANDSHAKE -> HANDSHAKE_SETTLE_MS
        KayakFirstSetupState.DISPLAY -> DISPLAY_SETTLE_MS
        else -> 0L
    }

    private fun clearTiming() {
        acknowledgementDeadlineMs = null
        retryAtMs = null
        settleUntilMs = null
    }

    private fun safeAdd(base: Long, delta: Long): Long =
        if (base > Long.MAX_VALUE - delta) Long.MAX_VALUE else base + delta

    companion object {
        const val ACK_DEADLINE_MS = 1_000L
        const val SUBSCRIPTION_DEADLINE_MS = 10_000L
        const val RETRY_DELAY_MS = 1_000L
        const val MAX_ATTEMPTS = 3
        const val RESET_A_SETTLE_MS = 1_000L
        const val RESET_B_SETTLE_MS = 5_000L
        const val HANDSHAKE_SETTLE_MS = 5_000L
        const val DISPLAY_SETTLE_MS = 5_000L

        private val TERMINAL_STATES = setOf(
            KayakFirstSetupState.READY,
            KayakFirstSetupState.FAILED,
            KayakFirstSetupState.CANCELLED,
        )
    }
}
