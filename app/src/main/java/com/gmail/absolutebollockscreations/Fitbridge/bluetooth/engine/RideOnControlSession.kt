package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal enum class RideOnServiceForm { ORIGINAL, CORE2 }

internal data class RideOnSessionTiming(
    val negotiationTimeoutMs: Long = 3_000,
    val retryIntervalMs: Long = 250,
    val writeTimeoutMs: Long = 1_000,
    val ergMinIntervalMs: Long = 2_000,
)

/** Pure readiness gate: no negotiation write is legal before both receive surfaces are subscribed. */
internal class RideOnSubscriptionGate(private val connectionId: ConnectionId) {
    private val ready = mutableSetOf<UUID>()
    @Synchronized fun markReady(eventConnectionId: ConnectionId, characteristic: UUID): Boolean {
        if (eventConnectionId != connectionId) return false
        if (characteristic != RideOnUuids.ASYNC_TX && characteristic != RideOnUuids.SYNC_TX) return false
        ready += characteristic
        return ready.contains(RideOnUuids.ASYNC_TX) && ready.contains(RideOnUuids.SYNC_TX)
    }
}

/** Generation-owned negotiation/initialization session. Production binding is deferred to 2.6. */
internal class RideOnControlSession(
    private val transport: GattTransport,
    private val serviceForm: RideOnServiceForm,
    private val timing: RideOnSessionTiming = RideOnSessionTiming(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onRidingData: (RideOnDecodedPacket) -> Unit = {},
    private val onControlGranted: () -> Unit = {},
    private val onInitializationFailed: () -> Unit = {},
) {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(dispatcher + supervisor)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val negotiationWaiter = AtomicReference<CompletableDeferred<Unit>?>(null)
    private val pendingTarget = AtomicReference<RideOnControlTarget?>(null)
    private val targetWake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var lastErgAcceptedAtMs: Long? = null

    @Volatile var controlGranted = false
        private set
    val connectionId get() = transport.connectionId
    val isClosed get() = closed.get()

    fun startAfterSubscriptionsReady(): Boolean {
        if (closed.get() || !started.compareAndSet(false, true)) return false
        scope.launch {
            val negotiated = withTimeoutOrNull(timing.negotiationTimeoutMs) {
                repeat(MAX_NEGOTIATION_ATTEMPTS) { attempt ->
                    val waiter = CompletableDeferred<Unit>()
                    negotiationWaiter.set(waiter)
                    val written = write(handshake(), "negotiate")
                    if (written && withTimeoutOrNull(attemptBudgetMs()) { waiter.await() } != null) {
                        negotiationWaiter.compareAndSet(waiter, null)
                        return@withTimeoutOrNull true
                    }
                    negotiationWaiter.compareAndSet(waiter, null)
                    if (attempt + 1 < MAX_NEGOTIATION_ATTEMPTS) delay(timing.retryIntervalMs)
                }
                false
            } ?: false
            if (!negotiated || closed.get() ||
                !write(INITIALIZATION, "initialize") ||
                !write(RideOnProtoCodec.hubRequest(0), "general-information")
            ) {
                failInitialization()
                return@launch
            }
            controlGranted = true
            onControlGranted()
            startTargetConsumer()
        }
        return true
    }

    fun onPacket(eventConnectionId: ConnectionId, characteristic: UUID, bytes: ByteArray) {
        if (closed.get() || eventConnectionId != connectionId) return
        if (characteristic != RideOnUuids.ASYNC_TX && characteristic != RideOnUuids.SYNC_TX) return
        val decoded = RideOnProtoCodec.decode(bytes)
        if (decoded is RideOnDecodeResult.Decoded &&
            decoded.packet.opcode == RideOnUuids.OPCODE_RIDING_DATA
        ) {
            onRidingData(decoded.packet)
        }
        val acceptable = bytes.contentEquals(handshake()) ||
            (serviceForm == RideOnServiceForm.CORE2 && decoded is RideOnDecodeResult.Decoded)
        if (acceptable) negotiationWaiter.get()?.complete(Unit)
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        controlGranted = false
        negotiationWaiter.getAndSet(null)?.cancel()
        pendingTarget.set(null)
        targetWake.close()
        supervisor.cancel()
    }

    fun submit(target: RideOnControlTarget): Boolean {
        if (!controlGranted || closed.get() || encodeRideOnTarget(target) == null) return false
        pendingTarget.set(target)
        targetWake.trySend(Unit)
        return true
    }

    private fun startTargetConsumer() = scope.launch {
        while (isActive && !closed.get()) {
            targetWake.receiveCatching().getOrNull() ?: break
            val target = pendingTarget.getAndSet(null) ?: continue
            if (target is RideOnControlTarget.Erg) {
                lastErgAcceptedAtMs?.let { last ->
                    val remaining = timing.ergMinIntervalMs - (System.nanoTime() / 1_000_000L - last)
                    if (remaining > 0) delay(remaining)
                    if (pendingTarget.get() != null) continue
                }
            }
            val encoded = encodeRideOnTarget(target) ?: continue
            if (write(encoded, "target")) {
                if (target is RideOnControlTarget.Erg) lastErgAcceptedAtMs = System.nanoTime() / 1_000_000L
            }
            if (pendingTarget.get() != null) targetWake.trySend(Unit)
        }
    }

    private suspend fun write(payload: ByteArray, label: String): Boolean = runCatching {
        transport.write(
            RideOnUuids.SYNC_RX,
            payload,
            GattWriteMode.WITHOUT_RESPONSE,
            timing.writeTimeoutMs,
            "RideOn:$label",
        )
    }.getOrDefault(false)

    private fun handshake() = when (serviceForm) {
        RideOnServiceForm.ORIGINAL -> RideOnUuids.HANDSHAKE_ORIGINAL
        RideOnServiceForm.CORE2 -> RideOnUuids.HANDSHAKE_CORE2
    }

    private fun attemptBudgetMs(): Long =
        ((timing.negotiationTimeoutMs - timing.retryIntervalMs) / MAX_NEGOTIATION_ATTEMPTS)
            .coerceAtLeast(1)

    private fun failInitialization() {
        controlGranted = false
        if (!closed.get()) onInitializationFailed()
    }

    private companion object {
        const val MAX_NEGOTIATION_ATTEMPTS = 2
        val INITIALIZATION = byteArrayOf(0x41, 0x08, 0x05)
    }
}
