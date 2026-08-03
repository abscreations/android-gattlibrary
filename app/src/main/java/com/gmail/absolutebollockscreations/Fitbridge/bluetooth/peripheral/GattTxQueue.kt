package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Serialises every GATT server notification and indication.
 *
 * Android's onNotificationSent callback does not identify the characteristic,
 * so correctness depends on there being exactly one stack operation in flight
 * across the entire GATT server. Control responses are prioritised over periodic
 * measurement traffic, but never overtake an already-posted operation.
 *
 * The previous FitBridge notifier called `notifyCharacteristicChanged` directly
 * from its tick loop and from the Control Point handler with no serialisation at
 * all, so a Control Point indication could be posted while a measurement
 * notification was still in flight. On a slow link that either dropped the
 * indication or corrupted the ordering the client relies on.
 */
internal class GattTxQueue(
    scope: CoroutineScope,
    /**
     * How long to wait for the onNotificationSent acknowledgement of a
     * *periodic data* notification before giving up on it.
     *
     * Data is unreliable-by-design: each tick carries the latest snapshot, so a
     * dropped or unacknowledged packet is simply superseded by the next one. The
     * timeout must still accommodate slow negotiated connection intervals:
     * Garmin clients may use a 990 ms interval, so 2.5 seconds permits a
     * notification to cross the next event even when one event is missed. We
     * still wait for *some* ack so we do not overrun the stack's small
     * notification buffer under normal conditions.
     */
    private val dataTimeoutMs: Long = DEFAULT_DATA_TIMEOUT_MS,
    /**
     * Acknowledgement wait for *control* traffic (Control Point indications,
     * Machine Status, Training Status). These are reliable and ordered, so we
     * wait much longer for the confirmation before declaring failure.
     */
    private val controlTimeoutMs: Long = DEFAULT_CONTROL_TIMEOUT_MS,
    private val maxPending: Int = DEFAULT_MAX_PENDING,
    private val post: (Request) -> Boolean,
) {

    enum class Priority { CONTROL, DATA }

    data class Request(
        val sessionId: Long,
        val deviceAddress: String,
        val characteristicUuid: UUID,
        val payload: ByteArray,
        val confirm: Boolean,
        val priority: Priority,
        val purpose: String,
        internal val completion: CompletableDeferred<Boolean> = CompletableDeferred(),
        internal val acknowledgement: CompletableDeferred<Boolean> = CompletableDeferred(),
    )

    private val lock = Any()
    private val control = ArrayDeque<Request>()
    private val data = ArrayDeque<Request>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var accepting = true

    @Volatile
    private var current: Request? = null

    init {
        scope.launch {
            while (isActive) {
                wake.receive()
                drain()
            }
        }
    }

    suspend fun send(
        sessionId: Long,
        deviceAddress: String,
        characteristicUuid: UUID,
        payload: ByteArray,
        confirm: Boolean,
        priority: Priority,
        purpose: String,
    ): Boolean {
        val request = Request(
            sessionId = sessionId,
            deviceAddress = deviceAddress,
            characteristicUuid = characteristicUuid,
            payload = payload.copyOf(),
            confirm = confirm,
            priority = priority,
            purpose = purpose,
        )

        val accepted = synchronized(lock) {
            if (!accepting) return@synchronized false
            val pending = control.size + data.size
            if (pending >= maxPending) {
                if (priority == Priority.CONTROL && data.isNotEmpty()) {
                    data.removeFirst().completion.complete(false)
                } else {
                    return@synchronized false
                }
            }
            if (priority == Priority.CONTROL) control.addLast(request) else data.addLast(request)
            true
        }
        if (!accepted) return false
        wake.trySend(Unit)

        return try {
            request.completion.await()
        } catch (cancelled: CancellationException) {
            cancel(request)
            throw cancelled
        }
    }

    /** Completes only the one globally in-flight operation. */
    fun onNotificationSent(deviceAddress: String, success: Boolean) {
        val request = current ?: return
        if (!request.deviceAddress.equals(deviceAddress, ignoreCase = true)) return
        request.acknowledgement.complete(success)
    }

    fun cancelSession(sessionId: Long) {
        val removed = mutableListOf<Request>()
        synchronized(lock) {
            control.removeMatching(sessionId, removed)
            data.removeMatching(sessionId, removed)
            current?.takeIf { it.sessionId == sessionId }?.acknowledgement?.complete(false)
        }
        removed.forEach { it.completion.complete(false) }
    }

    fun cancelAll() {
        val removed = mutableListOf<Request>()
        synchronized(lock) {
            accepting = false
            while (control.isNotEmpty()) removed += control.removeFirst()
            while (data.isNotEmpty()) removed += data.removeFirst()
            current?.acknowledgement?.complete(false)
        }
        removed.forEach { it.completion.complete(false) }
    }

    fun resume() {
        accepting = true
    }

    private suspend fun drain() {
        while (true) {
            val request = synchronized(lock) {
                when {
                    control.isNotEmpty() -> control.removeFirst()
                    data.isNotEmpty() -> data.removeFirst()
                    else -> null
                }?.also { current = it }
            } ?: return

            val posted = runCatching { post(request) }.getOrDefault(false)
            val ackTimeout = when (request.priority) {
                Priority.CONTROL -> controlTimeoutMs
                Priority.DATA -> dataTimeoutMs
            }
            val delivered = if (posted) {
                withTimeoutOrNull(ackTimeout) { request.acknowledgement.await() } ?: false
            } else {
                false
            }

            synchronized(lock) {
                if (current === request) current = null
            }
            request.completion.complete(delivered)
        }
    }

    private fun cancel(request: Request) {
        synchronized(lock) {
            if (control.remove(request) || data.remove(request)) {
                request.completion.complete(false)
            } else if (current === request) {
                request.acknowledgement.complete(false)
            }
        }
    }

    private fun ArrayDeque<Request>.removeMatching(sessionId: Long, removed: MutableList<Request>) {
        val iterator = iterator()
        while (iterator.hasNext()) {
            val request = iterator.next()
            if (request.sessionId == sessionId) {
                iterator.remove()
                removed += request
            }
        }
    }

    companion object {
        /** Covers two 990 ms Garmin connection intervals plus callback jitter. */
        const val DEFAULT_DATA_TIMEOUT_MS = 2_500L
        const val DEFAULT_CONTROL_TIMEOUT_MS = 5_000L
        const val DEFAULT_MAX_PENDING = 32
    }
}
