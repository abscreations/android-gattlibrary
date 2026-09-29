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
 * Serialises every GATT server notification and indication, and keeps downstream
 * measurement data fresh by conflating it.
 *
 * Android's onNotificationSent callback does not identify the characteristic, so
 * correctness depends on there being exactly one stack operation in flight across
 * the entire GATT server. That constraint is unchanged.
 *
 * ### Why this became a conflating queue
 *
 * The previous version was a FIFO with `maxPending = 32`. When a client consumed
 * notifications more slowly than the notifier produced them — a Garmin watch on a
 * 990 ms connection interval against a 700 ms tick — the queue filled with stale
 * snapshots and then **rejected the newest send while retaining the oldest 32**.
 * The watch therefore displayed data that fell further behind for as long as the
 * backlog persisted, which is precisely inverted from what an unreliable
 * measurement stream wants.
 *
 * Measurement data is unreliable-by-design: every payload carries a complete
 * snapshot, so the only payload worth sending is the most recent one. This queue
 * therefore holds **at most one pending payload per (device, characteristic)
 * slot**. A newer payload for the same slot replaces the pending one, which
 * completes [Outcome.SUPERSEDED] — a normal outcome, not a failure.
 *
 * ### Invariants
 *
 * - One stack operation in flight globally, as before.
 * - **A payload is atomic.** An FTMS record fragmented into several More Data
 *   frames is one queue item. Once its first frame is posted it runs to
 *   completion; it is never interrupted, replaced, or partially superseded,
 *   because More Data framing is stateful and a half-sent record corrupts the
 *   client's reassembly.
 * - **Only the queued payload is replaced, never the in-flight one.**
 * - **Control traffic is never conflated.** Control Point indications, Machine
 *   Status, and Training Status are transactional; they keep a reliable FIFO with
 *   priority over data.
 * - Slot rotation is fair: a drained slot re-enters at the back, so a slow client
 *   cannot starve a fast one. A superseded payload keeps its queue position,
 *   since it has already been waiting.
 * - **No timeout here affects connection state.** A timeout abandons the wait for
 *   one acknowledgement and completes that payload [Outcome.FAILED]; the link,
 *   the session, and every other slot are untouched. Nothing in this class can
 *   disconnect a client.
 */
internal class GattTxQueue(
    scope: CoroutineScope,
    /**
     * How long to wait for the onNotificationSent acknowledgement of one *data*
     * frame before abandoning it.
     *
     * This is stall recovery, not a link timeout. Android occasionally never
     * delivers the callback; with a single in-flight slot that would otherwise
     * block the client's entire stream, so the wait is deliberately short. A
     * long value would convert one lost callback into a correspondingly long
     * freeze on that client.
     *
     * 2.5 s covers two 990 ms Garmin connection intervals plus callback jitter.
     */
    private val dataTimeoutMs: Long = DEFAULT_DATA_TIMEOUT_MS,
    /**
     * Acknowledgement wait for *control* traffic.
     *
     * An indication is not confirmed until the client answers, and the single
     * in-flight slot is held for the whole wait — so this value is also the worst
     * case for **how long one unconfirmed indication can stop the measurement
     * stream to that client**. A 2026-08-05 capture showed exactly that: a Garmin
     * watch wrote REQUEST_CONTROL, never confirmed the response indication, and
     * received nothing at all for the full timeout before dropping the link.
     *
     * Lower is therefore better for data continuity, but the 2026-09-07 Garmin
     * rowing capture shows that a watch negotiating a 990 ms interval can take
     * about four intervals (3.93 s) to confirm. The bounded default therefore
     * covers five such intervals plus callback jitter. This deliberately accepts
     * a longer possible blackout rather than timing out a known healthy client,
     * losing acknowledgement correlation, and then failing its session setup.
     */
    private val controlTimeoutMs: Long = DEFAULT_CONTROL_TIMEOUT_MS,
    /**
     * Cap on queued *control* payloads. Data needs no such cap: its depth is
     * structurally bounded at one per slot.
     */
    private val maxPendingControl: Int = DEFAULT_MAX_PENDING_CONTROL,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val post: (Request) -> Boolean,
) {

    enum class Priority { CONTROL, DATA }

    /**
     * How a payload finished.
     *
     * [SUPERSEDED] is the expected outcome for measurement data on a slow link
     * and must not be logged or counted as an error. Distinguishing it from
     * [FAILED] is what lets the notifier tell "the link is slow, we skipped an
     * update" from "the client went away".
     */
    enum class Outcome { DELIVERED, SUPERSEDED, FAILED }

    /** One frame handed to the stack. A payload may consist of several. */
    data class Request(
        val sessionId: Long,
        val deviceAddress: String,
        val characteristicUuid: UUID,
        val payload: ByteArray,
        val confirm: Boolean,
        val priority: Priority,
        val purpose: String,
        val frameIndex: Int = 0,
        val frameCount: Int = 1,
    ) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /** Queue statistics, for diagnosing downstream lag without a packet capture. */
    data class Stats(
        val delivered: Long,
        val superseded: Long,
        val failed: Long,
        val lastAckLatencyMs: Long,
        val maxAckLatencyMs: Long,
        val meanAckLatencyMs: Long,
        val queuedDataPayloads: Int,
        val queuedControlPayloads: Int,
    )

    private class Item(
        val sessionId: Long,
        val deviceAddress: String,
        val characteristicUuid: UUID,
        val frames: List<ByteArray>,
        val confirm: Boolean,
        val priority: Priority,
        val purpose: String,
        val completion: CompletableDeferred<Outcome> = CompletableDeferred(),
    )

    private val lock = Any()
    private val control = ArrayDeque<Item>()

    /**
     * At most one pending data payload per slot, keyed by device + characteristic.
     * [LinkedHashMap] iteration order gives the fair rotation described above:
     * replacing an existing key preserves its position, while a drained slot is
     * removed and re-added at the back when it next fills.
     */
    private val dataSlots = LinkedHashMap<String, Item>()

    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var accepting = true

    /** The in-flight payload. Never replaced; only cancelled. */
    private var current: Item? = null

    /** Acknowledgement for the single in-flight *frame* of [current]. */
    private var frameAck: CompletableDeferred<Boolean>? = null

    /*
     * There is deliberately no "swallow the next acknowledgement after a timeout"
     * guard here, and one should not be re-added without new evidence.
     *
     * The reasoning that suggested one: Android's onNotificationSent carries only
     * a device and a status, nothing that correlates it to the operation that
     * caused it, so a confirmation arriving after we gave up is indistinguishable
     * from the next payload's and would report a delivery that never happened.
     *
     * The reasoning against, which wins: discarding the next acknowledgement
     * makes the *next* payload time out too. In the case this queue exists to
     * bound - an unconfirmed control indication - that turns a 2 s measurement
     * blackout into 2 s plus a full data timeout, which is worse than the defect
     * being fixed. And in the only case actually observed (2026-08-05, where the
     * client never confirmed at all and then dropped the link) the guard would
     * have been pure cost with no benefit.
     *
     * A mis-attributed acknowledgement is self-limiting instead: it puts the
     * queue one payload out of phase, and the first premature post then fails
     * because the stack is still busy, which resynchronises it. Measurement data
     * is superseded every cycle regardless.
     */

    private var deliveredCount = 0L
    private var supersededCount = 0L
    private var failedCount = 0L
    private var lastAckLatencyMs = 0L
    private var maxAckLatencyMs = 0L
    private var ackLatencySamples = 0L
    private var ackLatencyTotalMs = 0L

    init {
        scope.launch {
            while (isActive) {
                wake.receive()
                drain()
            }
        }
    }

    /**
     * Enqueues one atomic payload.
     *
     * For [Priority.DATA] this replaces any payload already waiting in the same
     * slot; the replaced payload completes [Outcome.SUPERSEDED].
     */
    suspend fun sendPayload(
        sessionId: Long,
        deviceAddress: String,
        characteristicUuid: UUID,
        frames: List<ByteArray>,
        confirm: Boolean,
        priority: Priority,
        purpose: String,
    ): Outcome {
        if (frames.isEmpty()) return Outcome.FAILED
        val item = Item(
            sessionId = sessionId,
            deviceAddress = deviceAddress,
            characteristicUuid = characteristicUuid,
            frames = frames.map { it.copyOf() },
            confirm = confirm,
            priority = priority,
            purpose = purpose,
        )

        var replaced: Item? = null
        val accepted = synchronized(lock) {
            if (!accepting) return@synchronized false
            if (priority == Priority.CONTROL) {
                if (control.size >= maxPendingControl) return@synchronized false
                control.addLast(item)
            } else {
                // put() on an existing key keeps the slot's queue position: this
                // payload inherits the wait already served by the one it replaces.
                replaced = dataSlots.put(slotKey(deviceAddress, characteristicUuid), item)
                if (replaced != null) supersededCount++
            }
            true
        }
        replaced?.completion?.complete(Outcome.SUPERSEDED)
        if (!accepted) return Outcome.FAILED
        wake.trySend(Unit)

        return try {
            item.completion.await()
        } catch (cancelled: CancellationException) {
            cancel(item)
            throw cancelled
        }
    }

    /** Single-frame convenience. Returns true only for [Outcome.DELIVERED]. */
    suspend fun send(
        sessionId: Long,
        deviceAddress: String,
        characteristicUuid: UUID,
        payload: ByteArray,
        confirm: Boolean,
        priority: Priority,
        purpose: String,
    ): Boolean = sendPayload(
        sessionId = sessionId,
        deviceAddress = deviceAddress,
        characteristicUuid = characteristicUuid,
        frames = listOf(payload),
        confirm = confirm,
        priority = priority,
        purpose = purpose,
    ) == Outcome.DELIVERED

    /** Completes the one globally in-flight frame. */
    fun onNotificationSent(deviceAddress: String, success: Boolean) {
        val ack = synchronized(lock) {
            val item = current ?: return
            if (!item.deviceAddress.equals(deviceAddress, ignoreCase = true)) return
            frameAck
        } ?: return
        ack.complete(success)
    }

    fun cancelSession(sessionId: Long) {
        val removed = mutableListOf<Item>()
        var abort: CompletableDeferred<Boolean>? = null
        synchronized(lock) {
            control.removeMatching(sessionId, removed)
            val iterator = dataSlots.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.value.sessionId == sessionId) {
                    removed += entry.value
                    iterator.remove()
                }
            }
            if (current?.sessionId == sessionId) abort = frameAck
        }
        abort?.complete(false)
        removed.forEach { it.completion.complete(Outcome.FAILED) }
    }

    fun cancelAll() {
        val removed = mutableListOf<Item>()
        var abort: CompletableDeferred<Boolean>? = null
        synchronized(lock) {
            accepting = false
            while (control.isNotEmpty()) removed += control.removeFirst()
            removed += dataSlots.values
            dataSlots.clear()
            abort = frameAck
        }
        abort?.complete(false)
        removed.forEach { it.completion.complete(Outcome.FAILED) }
    }

    fun resume() {
        accepting = true
    }

    fun stats(): Stats = synchronized(lock) {
        Stats(
            delivered = deliveredCount,
            superseded = supersededCount,
            failed = failedCount,
            lastAckLatencyMs = lastAckLatencyMs,
            maxAckLatencyMs = maxAckLatencyMs,
            meanAckLatencyMs = if (ackLatencySamples == 0L) 0L else ackLatencyTotalMs / ackLatencySamples,
            queuedDataPayloads = dataSlots.size,
            queuedControlPayloads = control.size,
        )
    }

    private suspend fun drain() {
        while (true) {
            val item = synchronized(lock) {
                val next = when {
                    control.isNotEmpty() -> control.removeFirst()
                    dataSlots.isNotEmpty() -> {
                        val key = dataSlots.keys.first()
                        dataSlots.remove(key)
                    }

                    else -> null
                }
                current = next
                next
            } ?: return

            val outcome = sendFrames(item)

            synchronized(lock) {
                if (current === item) current = null
                frameAck = null
            }
            item.completion.complete(outcome)
        }
    }

    /**
     * Posts every frame of one payload in order, awaiting each acknowledgement.
     *
     * Deliberately has no supersession check: once started, a payload runs to
     * completion. Abandoning it midway would leave a More Data continuation
     * dangling and desynchronise the client's reassembly.
     */
    private suspend fun sendFrames(item: Item): Outcome {
        val timeout = when (item.priority) {
            Priority.CONTROL -> controlTimeoutMs
            Priority.DATA -> dataTimeoutMs
        }
        val startedAtMs = nowMs()
        var failed = false

        for ((index, frame) in item.frames.withIndex()) {
            val ack = CompletableDeferred<Boolean>()
            // A cancellation between frames must not be papered over by handing
            // this payload a fresh acknowledgement slot.
            val stillCurrent = synchronized(lock) {
                if (current !== item) return@synchronized false
                frameAck = ack
                true
            }
            if (!stillCurrent) {
                failed = true
                break
            }

            val request = Request(
                sessionId = item.sessionId,
                deviceAddress = item.deviceAddress,
                characteristicUuid = item.characteristicUuid,
                payload = frame,
                confirm = item.confirm,
                priority = item.priority,
                purpose = item.purpose,
                frameIndex = index,
                frameCount = item.frames.size,
            )
            val posted = runCatching { post(request) }.getOrDefault(false)
            val acknowledged = if (posted) {
                withTimeoutOrNull(timeout) { ack.await() } ?: false
            } else {
                false
            }
            if (!acknowledged) {
                failed = true
                break
            }
        }

        val elapsed = nowMs() - startedAtMs
        synchronized(lock) {
            if (failed) {
                failedCount++
            } else {
                deliveredCount++
                lastAckLatencyMs = elapsed
                if (elapsed > maxAckLatencyMs) maxAckLatencyMs = elapsed
                ackLatencySamples++
                ackLatencyTotalMs += elapsed
            }
        }
        return if (failed) Outcome.FAILED else Outcome.DELIVERED
    }

    private fun cancel(item: Item) {
        var abort: CompletableDeferred<Boolean>? = null
        synchronized(lock) {
            val key = slotKey(item.deviceAddress, item.characteristicUuid)
            when {
                control.remove(item) -> Unit
                dataSlots[key] === item -> dataSlots.remove(key)
                current === item -> abort = frameAck
                else -> return@synchronized
            }
        }
        abort?.complete(false)
        item.completion.complete(Outcome.FAILED)
    }

    private fun slotKey(deviceAddress: String, characteristicUuid: UUID): String =
        "${deviceAddress.lowercase()}|$characteristicUuid"

    private fun ArrayDeque<Item>.removeMatching(sessionId: Long, removed: MutableList<Item>) {
        val iterator = iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.sessionId == sessionId) {
                iterator.remove()
                removed += item
            }
        }
    }

    companion object {
        /** Five 990 ms Garmin intervals plus bounded Android callback jitter. */
        const val DEFAULT_DATA_TIMEOUT_MS = 5_500L

        /** Five 990 ms Garmin intervals plus bounded Android callback jitter. */
        const val DEFAULT_CONTROL_TIMEOUT_MS = 5_500L

        const val DEFAULT_MAX_PENDING_CONTROL = 16
    }
}
