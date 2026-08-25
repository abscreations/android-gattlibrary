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
    val shiftResponseTimeoutMs: Long = 800,
)

internal enum class RideOnShiftOutcome { ACKNOWLEDGED, ACCEPTED_UNCONFIRMED, TRANSPORT_FAILED }

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
    initialShiftSequence: Long = 0,
) {
    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(dispatcher + supervisor)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val negotiationWaiter = AtomicReference<CompletableDeferred<Unit>?>(null)
    private val pendingTarget = AtomicReference<RideOnControlTarget?>(null)
    private val pendingGear = AtomicReference<Int?>(null)
    private val workWake = Channel<Unit>(Channel.CONFLATED)
    private val shiftWaiter = AtomicReference<Pair<Long, CompletableDeferred<Unit>>?>(null)
    @Volatile private var nextShiftSequence = initialShiftSequence.also { require(it in 0..0xFFFF_FFFFL) }
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
        if (decoded is RideOnDecodeResult.Decoded &&
            decoded.packet.opcode == RideOnUuids.OPCODE_VIRTUAL_SHIFT_RESPONSE
        ) {
            val sequence = decoded.packet.fields
                .filterIsInstance<RideOnProtoField.Varint>()
                .firstOrNull { it.number == 2 }
                ?.value
            val waiter = shiftWaiter.get()
            if (sequence != null && waiter?.first == sequence) waiter.second.complete(Unit)
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
        pendingGear.set(null)
        shiftWaiter.getAndSet(null)?.second?.cancel()
        workWake.close()
        supervisor.cancel()
    }

    fun submit(target: RideOnControlTarget): Boolean {
        if (!controlGranted || closed.get() || encodeRideOnTarget(target) == null) return false
        pendingTarget.set(target)
        workWake.trySend(Unit)
        return true
    }

    fun submitVirtualGear(index: Int): Boolean {
        if (!controlGranted || closed.get() || index !in 1..RIDEON_GEAR_RATIOS.size) return false
        pendingGear.set(index)
        workWake.trySend(Unit)
        return true
    }

    private fun startTargetConsumer() = scope.launch {
        while (isActive && !closed.get()) {
            workWake.receiveCatching().getOrNull() ?: break
            val target = pendingTarget.getAndSet(null)
            if (target == null) {
                pendingGear.getAndSet(null)?.let { executeShift(it) }
                if (pendingTarget.get() != null || pendingGear.get() != null) workWake.trySend(Unit)
                continue
            }
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
            if (pendingTarget.get() != null || pendingGear.get() != null) workWake.trySend(Unit)
        }
    }

    private suspend fun executeShift(index: Int): RideOnShiftOutcome {
        val sequence = nextShiftSequence
        val waiter = CompletableDeferred<Unit>()
        val activeWaiter = sequence to waiter
        shiftWaiter.set(activeWaiter)
        val accepted = write(
            RideOnProtoCodec.virtualShift(1, sequence, RIDEON_GEAR_RATIOS[index - 1].toLong()),
            "virtual-shift",
        )
        if (!accepted) {
            shiftWaiter.compareAndSet(activeWaiter, null)
            return RideOnShiftOutcome.TRANSPORT_FAILED
        }
        nextShiftSequence = (sequence + 1) and UINT32_MAX
        val acknowledged = withTimeoutOrNull(timing.shiftResponseTimeoutMs) { waiter.await() } != null
        shiftWaiter.set(null)
        return if (acknowledged) RideOnShiftOutcome.ACKNOWLEDGED
        else RideOnShiftOutcome.ACCEPTED_UNCONFIRMED
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
        const val UINT32_MAX = 0xFFFF_FFFFL
    }
}

internal val RIDEON_GEAR_RATIOS = listOf(
    7500, 8700, 9900, 11100, 12300, 13800, 15300, 16800,
    18600, 20400, 22200, 24000, 26099, 28200, 30300, 32400,
    34900, 37400, 39900, 42399, 45400, 48400, 51400, 54899,
)
