package com.gmail.absolutebollockscreations.gattlibrary.central

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal enum class WahooKickrControlMode { NONE, RESISTANCE, ERG, SIMULATION }

internal enum class WahooKickrCommandOutcome {
    ACKNOWLEDGED,
    ACCEPTED_UNCONFIRMED,
    REJECTED,
    TRANSPORT_FAILED,
}

internal sealed interface WahooKickrControlTarget {
    data class Resistance(val fraction: Double) : WahooKickrControlTarget
    data class Erg(val watts: Int) : WahooKickrControlTarget
    data class Simulation(
        val totalMassKg: Double,
        val crr: Double,
        val cw: Double,
        val gradePercent: Double,
        val windSpeedMps: Double,
    ) : WahooKickrControlTarget
    data class WheelCircumference(val millimetres: Double) : WahooKickrControlTarget
}

internal data class WahooKickrSessionTiming(
    val unlockOverallTimeoutMs: Long = WahooKickrUuids.UNLOCK_OVERALL_TIMEOUT_MS,
    val unlockAttemptIntervalMs: Long = WahooKickrUuids.UNLOCK_MIN_ATTEMPT_INTERVAL_MS,
    val attWriteTimeoutMs: Long = WahooKickrUuids.ATT_WRITE_TIMEOUT_MS,
    val indicationTimeoutMs: Long = WahooKickrUuids.INDICATION_TIMEOUT_MS,
    val ergMinIntervalMs: Long = WahooKickrUuids.ERG_MIN_INTERVAL_MS,
)

/**
 * One serialized control session owned by one exact [ConnectionId] generation.
 *
 * The session never bypasses [GattTransport]. Targets are latest-wins, while a simulation target
 * expands and runs atomically on the single consumer. Unlock is initialization, never a target.
 */
internal class WahooKickrControlSession(
    private val transport: GattTransport,
    private val timing: WahooKickrSessionTiming = WahooKickrSessionTiming(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val onOutcome: (Int, WahooKickrCommandOutcome) -> Unit = { _, _ -> },
    private val onCommandAccepted: (WahooKickrCommand, WahooKickrCommandOutcome) -> Unit = { _, _ -> },
    private val onUnrecognizedResponse: (WahooKickrResponse) -> Unit = {},
    private val onControlGranted: () -> Unit = {},
    private val onInitializationFailed: () -> Unit = {},
) {
    private data class Waiter(
        val opcode: Int,
        val response: CompletableDeferred<WahooKickrResponse>,
    )

    private data class SimulationTuple(val mass: Double, val crr: Double, val cw: Double)

    private val supervisor = SupervisorJob()
    private val scope = CoroutineScope(dispatcher + supervisor)
    private val closed = AtomicBoolean(false)
    private val initializationStarted = AtomicBoolean(false)
    private val pending = AtomicReference<WahooKickrControlTarget?>(null)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val inFlight = AtomicReference<Waiter?>(null)

    @Volatile var controlGranted: Boolean = false
        private set
    @Volatile var mode: WahooKickrControlMode = WahooKickrControlMode.NONE
        private set
    @Volatile private var consumerJob: Job? = null
    @Volatile private var lastAcceptedErgAtMs: Long? = null
    @Volatile private var simulationTuple: SimulationTuple? = null
    @Volatile private var lastWindSpeedMps: Double? = null

    val connectionId: ConnectionId get() = transport.connectionId
    val isClosed: Boolean get() = closed.get()

    /** Called only after this link generation's indication CCCD is confirmed enabled. */
    fun startAfterCccdReady(): Boolean {
        if (closed.get() || !initializationStarted.compareAndSet(false, true)) return false
        scope.launch {
            val unlocked = withTimeoutOrNull(timing.unlockOverallTimeoutMs) {
                repeat(WahooKickrUuids.UNLOCK_MAX_ATTEMPTS) { attempt ->
                    if (execute(WahooKickrCommand.Unlock, allowUnconfirmed = false) ==
                        WahooKickrCommandOutcome.ACKNOWLEDGED
                    ) {
                        return@withTimeoutOrNull true
                    }
                    if (attempt + 1 < WahooKickrUuids.UNLOCK_MAX_ATTEMPTS) {
                        delay(timing.unlockAttemptIntervalMs)
                    }
                }
                false
            } ?: false
            if (!unlocked || closed.get()) {
                controlGranted = false
                if (!closed.get()) onInitializationFailed()
                return@launch
            }
            controlGranted = true
            onControlGranted()
            startConsumer()
        }
        return true
    }

    /** Validate synchronously, then replace any target not yet being executed. */
    fun submit(target: WahooKickrControlTarget): Boolean {
        if (!controlGranted || closed.get() || !isValid(target)) return false
        pending.set(target)
        wake.trySend(Unit)
        return true
    }

    fun clearPending() {
        pending.set(null)
    }

    /** Only a response for this exact generation and current opcode can complete the waiter. */
    fun onIndication(eventConnectionId: ConnectionId, bytes: ByteArray) {
        if (closed.get() || eventConnectionId != connectionId) return
        val waiter = inFlight.get() ?: run {
            onUnrecognizedResponse(WahooKickrResponse.Unknown(bytes.copyOf()))
            return
        }
        when (val parsed = WahooKickrResponseParser.parse(bytes, waiter.opcode)) {
            is WahooKickrResponse.Success,
            is WahooKickrResponse.ExplicitFailure,
            -> waiter.response.complete(parsed)
            is WahooKickrResponse.Unknown,
            is WahooKickrResponse.Unrelated,
            -> onUnrecognizedResponse(parsed)
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        controlGranted = false
        mode = WahooKickrControlMode.NONE
        simulationTuple = null
        lastWindSpeedMps = null
        lastAcceptedErgAtMs = null
        pending.set(null)
        inFlight.getAndSet(null)?.response?.cancel()
        wake.close()
        supervisor.cancel()
        consumerJob = null
    }

    private fun startConsumer() {
        if (consumerJob?.isActive == true) return
        consumerJob = scope.launch {
            while (isActive && !closed.get()) {
                wake.receiveCatching().getOrNull() ?: break
                val target = pending.getAndSet(null) ?: continue
                dispatch(target)
                if (pending.get() != null) wake.trySend(Unit)
            }
        }
    }

    private suspend fun dispatch(target: WahooKickrControlTarget) {
        when (target) {
            is WahooKickrControlTarget.Resistance -> {
                if (accepted(execute(WahooKickrCommand.Resistance(target.fraction)))) {
                    mode = WahooKickrControlMode.RESISTANCE
                }
            }
            is WahooKickrControlTarget.Erg -> {
                val last = lastAcceptedErgAtMs
                if (last != null) {
                    val waitMs = timing.ergMinIntervalMs - (monotonicMs() - last)
                    if (waitMs > 0) delay(waitMs)
                    // The latest target replaces this one during the pacing window.
                    if (pending.get() != null) return
                }
                if (accepted(execute(WahooKickrCommand.Erg(target.watts)))) {
                    mode = WahooKickrControlMode.ERG
                    lastAcceptedErgAtMs = monotonicMs()
                }
            }
            is WahooKickrControlTarget.Simulation -> dispatchSimulation(target)
            is WahooKickrControlTarget.WheelCircumference ->
                execute(WahooKickrCommand.WheelCircumference(target.millimetres))
        }
    }

    private suspend fun dispatchSimulation(target: WahooKickrControlTarget.Simulation) {
        val tuple = SimulationTuple(target.totalMassKg, target.crr, target.cw)
        if (mode != WahooKickrControlMode.SIMULATION || simulationTuple != tuple) {
            if (!accepted(execute(WahooKickrCommand.Simulation(tuple.mass, tuple.crr, tuple.cw)))) {
                return
            }
            mode = WahooKickrControlMode.SIMULATION
            simulationTuple = tuple
        }
        if (!accepted(execute(WahooKickrCommand.Grade(target.gradePercent)))) return
        if (lastWindSpeedMps != target.windSpeedMps) {
            if (!accepted(execute(WahooKickrCommand.WindSpeed(target.windSpeedMps)))) return
            lastWindSpeedMps = target.windSpeedMps
        }
    }

    private suspend fun execute(
        command: WahooKickrCommand,
        allowUnconfirmed: Boolean = true,
    ): WahooKickrCommandOutcome {
        val encoded = WahooKickrCodec.encode(command) as? WahooKickrEncodeResult.Encoded
            ?: return WahooKickrCommandOutcome.REJECTED
        val opcode = encoded.bytes[0].toInt() and 0xFF
        val waiter = Waiter(opcode, CompletableDeferred())
        check(inFlight.compareAndSet(null, waiter)) { "Wahoo writes must be serialized" }
        val attAccepted = try {
            transport.write(
                uuid = WahooKickrUuids.CONTROL,
                payload = encoded.bytes,
                mode = GattWriteMode.WITH_RESPONSE,
                timeoutMs = timing.attWriteTimeoutMs,
                operationName = "WahooKickr:0x${opcode.toString(16)}",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            false
        }
        val outcome = if (!attAccepted) {
            WahooKickrCommandOutcome.TRANSPORT_FAILED
        } else {
            when (withTimeoutOrNull(timing.indicationTimeoutMs) { waiter.response.await() }) {
                is WahooKickrResponse.Success -> WahooKickrCommandOutcome.ACKNOWLEDGED
                is WahooKickrResponse.ExplicitFailure -> WahooKickrCommandOutcome.REJECTED
                else -> if (allowUnconfirmed) {
                    WahooKickrCommandOutcome.ACCEPTED_UNCONFIRMED
                } else {
                    WahooKickrCommandOutcome.REJECTED
                }
            }
        }
        inFlight.compareAndSet(waiter, null)
        onOutcome(opcode, outcome)
        if (accepted(outcome)) onCommandAccepted(command, outcome)
        return outcome
    }

    private fun accepted(outcome: WahooKickrCommandOutcome): Boolean =
        outcome == WahooKickrCommandOutcome.ACKNOWLEDGED ||
            outcome == WahooKickrCommandOutcome.ACCEPTED_UNCONFIRMED

    private fun isValid(target: WahooKickrControlTarget): Boolean {
        val commands = when (target) {
            is WahooKickrControlTarget.Resistance -> listOf(WahooKickrCommand.Resistance(target.fraction))
            is WahooKickrControlTarget.Erg -> listOf(WahooKickrCommand.Erg(target.watts))
            is WahooKickrControlTarget.Simulation -> listOf(
                WahooKickrCommand.Simulation(target.totalMassKg, target.crr, target.cw),
                WahooKickrCommand.Grade(target.gradePercent),
                WahooKickrCommand.WindSpeed(target.windSpeedMps),
            )
            is WahooKickrControlTarget.WheelCircumference ->
                listOf(WahooKickrCommand.WheelCircumference(target.millimetres))
        }
        return commands.all { WahooKickrCodec.encode(it) is WahooKickrEncodeResult.Encoded }
    }
}
