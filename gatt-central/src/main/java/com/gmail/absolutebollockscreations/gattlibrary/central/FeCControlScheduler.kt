package com.gmail.absolutebollockscreations.gattlibrary.central

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.FecBleUuids

/**
 * One coalesced target for FEC3, per Unit 9.9 of `docs/fe-c-ble_central_implementation.md`.
 *
 * [mode] exists as a distinct property (rather than just discriminating on the sealed subtype at
 * call sites) because callers above [FeCControlScheduler] — in particular a future UI layer —
 * need to answer "what kind of control is currently active" without pattern-matching on an
 * internal type. [None] is the scheduler's own idle state, not a wire command: submitting it
 * clears whatever was pending without dispatching anything.
 */
internal sealed interface FeCControlTarget {
    val mode: FeCControlMode

    data object None : FeCControlTarget {
        override val mode: FeCControlMode = FeCControlMode.NONE
    }

    data class RequestDataPage(
        val requestedPage: Int,
        val transmissionCount: Int = 2,
    ) : FeCControlTarget {
        override val mode: FeCControlMode = FeCControlMode.CAPABILITY_REQUEST
    }

    /** Page 48. [percent] is `0.0..100.0`; out-of-range values are caught at dispatch, not here. */
    data class BasicResistance(val percent: Double) : FeCControlTarget {
        override val mode: FeCControlMode = FeCControlMode.BASIC_RESISTANCE
    }

    /** Page 49. [watts] must be finite and non-negative; enforced at dispatch. */
    data class Erg(val watts: Double) : FeCControlTarget {
        override val mode: FeCControlMode = FeCControlMode.ERG
    }

    /**
     * Pages 50 + 51, sent back-to-back as one logical update — mirrors
     * [FtmsMachineControlEndpoint]'s single `0x11` opcode as closely as two separate FE-C pages
     * can. Field names and units match [BtEngine.setIndoorBikeSimulation] exactly
     * so a caller does not need a second unit-conversion table for FE-C.
     *
     * [gradePercent] is the only field every caller of this target actually has: a full
     * [BtEngine.setIndoorBikeSimulation] call supplies all four, but an
     * inclination-only write ([FeCBleMachineControlEndpoint.setTargetInclinationPercent]) has
     * only a grade. The other three are nullable for exactly that case - `null` maps to page
     * 50/51's own spec-defined "use the device's default" sentinel (`0xFF`) at the encoder
     * layer, never to an invented value.
     */
    data class Sim(
        val windSpeedMps: Double?,
        val gradePercent: Double,
        val rollingResistanceCoefficient: Double?,
        val windResistanceCoefficient: Double?,
    ) : FeCControlTarget {
        override val mode: FeCControlMode = FeCControlMode.SIM
    }
}

internal enum class FeCControlMode { NONE, CAPABILITY_REQUEST, BASIC_RESISTANCE, ERG, SIM }

/**
 * Enforces FEC3's write cadence and coalesces bursty target updates into the latest one, per
 * plan §9.9.
 *
 * ## Why coalescing, not queuing
 *
 * A riding app can call `setIndoorBikeSimulation` many times a second as a route's gradient
 * changes continuously. Queuing every call would let FEC3 fall further and further behind real
 * time under load - the trainer would eventually be resisting a hill the rider left seconds ago.
 * [submit] instead replaces whatever was pending: only the most recent target a caller asked for
 * is ever live, which is what "control the trainer's current target" means for a device with no
 * write acknowledgement to pace against.
 *
 * ## Why a fixed tick rather than send-immediately-then-wait
 *
 * A fixed [minIntervalMs] tick (default 250ms) is simpler to reason about than a
 * last-write-timestamp gate reset by every `submit`, and it has the same effect: FEC3 never sees
 * writes closer together than the tick, and a value that arrives between ticks is not lost, only
 * coalesced into the next one.
 *
 * ## Lifecycle
 *
 * [start]/[stop] are idempotent and cheap to call repeatedly - [FeCOverBleProtocolHandler] calls
 * [start] once FEC2 is subscribed and FEC3 is present, and [stop] on disconnect or when this
 * link's `AdapterLivenessCoordinator`-selected adapter is no longer FE-C (a demotion). [stop]
 * drops whatever was pending; a coalesced target is by definition stale the moment control of
 * the link changes hands.
 *
 * [dispatcher] exists so tests can inject a deterministic test dispatcher instead of the real
 * [Dispatchers.IO] this class uses in production - the same reason [GattTransport] takes its
 * `BtEngine` dependency through an interface rather than a concrete type.
 */
internal class FeCControlScheduler(
    private val transport: GattTransport,
    private val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val supervisorJob = SupervisorJob()
    private val scope = CoroutineScope(dispatcher + supervisorJob)
    private val pending = AtomicReference<FeCControlTarget?>(null)

    @Volatile
    private var loopJob: Job? = null

    val isRunning: Boolean
        get() = loopJob?.isActive == true

    /** Replaces any not-yet-dispatched target. Safe to call before [start] or after [stop]. */
    fun submit(target: FeCControlTarget) {
        pending.set(target)
    }

    fun start() {
        if (isRunning) return
        loopJob = scope.launch {
            while (isActive) {
                val target = pending.getAndSet(null)
                if (target != null && target != FeCControlTarget.None) {
                    dispatch(target)
                }
                delay(minIntervalMs)
            }
        }
    }

    /** Stops the tick loop and discards whatever target was pending. */
    fun stop() {
        loopJob?.cancel()
        loopJob = null
        pending.set(null)
    }

    private suspend fun dispatch(target: FeCControlTarget) {
        val frames = try {
            framesFor(target)
        } catch (e: IllegalArgumentException) {
            Timber.tag(BtEngineState.TAG).w(
                e,
                "FeCControlScheduler: %s rejected an out-of-range value, dropping this tick",
                target.mode,
            )
            return
        }
        for (frame in frames) {
            // stop() cancels this coroutine but does not join it, so without this check a frame
            // can still be handed to the transport after stop() has returned - and for a Sim
            // target, which writes two frames, a demotion between them would leave the trainer
            // holding half an update. Cancellation is only observed at suspension points, and
            // transport.write() enqueues before it suspends, so the check has to happen here.
            currentCoroutineContext().ensureActive()
            transport.write(
                uuid = FecBleUuids.UUID_FEC_CONTROL,
                payload = frame,
                mode = GattWriteMode.WITHOUT_RESPONSE,
                timeoutMs = WRITE_TIMEOUT_MS,
                operationName = "FeCControlScheduler:${target.mode}",
            )
        }
    }

    private fun framesFor(target: FeCControlTarget): List<ByteArray> = when (target) {
        is FeCControlTarget.RequestDataPage -> listOf(
            FeCControlEncoder.encodeRequestDataPage(target.requestedPage, target.transmissionCount),
        )
        is FeCControlTarget.BasicResistance ->
            listOf(FeCControlEncoder.encodeBasicResistance(target.percent))
        is FeCControlTarget.Erg ->
            listOf(FeCControlEncoder.encodeTargetPower(target.watts))
        is FeCControlTarget.Sim -> listOf(
            FeCControlEncoder.encodeWindResistance(
                windResistanceCoefficient = target.windResistanceCoefficient,
                windSpeedKmh = target.windSpeedMps?.let { it * 3.6 },
                draftingFactor = null,
            ),
            FeCControlEncoder.encodeTrackResistance(
                gradePercent = target.gradePercent.coerceIn(-200.0, 200.0),
                rollingResistanceCoefficient = target.rollingResistanceCoefficient,
            ),
        )
        FeCControlTarget.None -> emptyList()
    }

    companion object {
        const val DEFAULT_MIN_INTERVAL_MS = 250L
        private const val WRITE_TIMEOUT_MS = 2_000L
    }
}
