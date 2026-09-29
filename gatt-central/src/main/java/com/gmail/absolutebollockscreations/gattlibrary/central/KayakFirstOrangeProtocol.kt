package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.FitBridgeBleInternalApi

import kotlin.math.PI
import kotlin.math.pow

enum class KayakFirstGeneration { ORANGE, BLUE }

fun kayakFirstGeneration(localName: String?): KayakFirstGeneration? {
    if (localName == null || !localName.matches(Regex("^[0-9]{8}$"))) return null
    return if (localName.substring(0, 2).toInt() < 18) {
        KayakFirstGeneration.ORANGE
    } else {
        KayakFirstGeneration.BLUE
    }
}

@FitBridgeBleInternalApi
enum class KayakFirstOrangeCommand(val wire: Byte) {
    T_MIN('1'.code.toByte()),
    T_H('2'.code.toByte()),
    T_MAX('3'.code.toByte()),
    T_V('4'.code.toByte()),
    AUXILIARY('5'.code.toByte()),
    RESET('9'.code.toByte());

    fun bytes(): ByteArray = byteArrayOf(wire)

    companion object {
        fun fromLine(line: String): KayakFirstOrangeCommand? =
            entries.firstOrNull { line.length == 1 && it.wire == line[0].code.toByte() }
    }
}

internal sealed interface KayakFirstOrangeFrameEvent {
    data class Line(val value: String) : KayakFirstOrangeFrameEvent
    data object TooLong : KayakFirstOrangeFrameEvent
    data object InvalidAscii : KayakFirstOrangeFrameEvent
}

/** Orange is LF-delimited (not Blue's CRLF protocol); fragments remain link-generation local. */
internal class KayakFirstOrangeLineAssembler(
    private val maximumLineBytes: Int = 32,
) {
    private val pending = ArrayList<Byte>()
    private var discarding = false

    init {
        require(maximumLineBytes > 0)
    }

    fun accept(fragment: ByteArray): List<KayakFirstOrangeFrameEvent> {
        val events = mutableListOf<KayakFirstOrangeFrameEvent>()
        fragment.forEach { byte ->
            if (discarding) {
                if (byte == LF) discarding = false
                return@forEach
            }
            if (byte == LF) {
                val bytes = pending.toByteArray()
                pending.clear()
                val line = if (bytes.lastOrNull() == CR) bytes.copyOf(bytes.size - 1) else bytes
                events += if (line.any { it.toInt() !in 0x20..0x7e }) {
                    KayakFirstOrangeFrameEvent.InvalidAscii
                } else {
                    KayakFirstOrangeFrameEvent.Line(line.toString(Charsets.US_ASCII))
                }
            } else if (pending.size >= maximumLineBytes) {
                pending.clear()
                discarding = true
                events += KayakFirstOrangeFrameEvent.TooLong
            } else {
                pending += byte
            }
        }
        return events
    }

    fun reset() {
        pending.clear()
        discarding = false
    }

    private companion object {
        val CR = '\r'.code.toByte()
        val LF = '\n'.code.toByte()
    }
}

@FitBridgeBleInternalApi
data class KayakFirstOrangeTimingResponse(
    val command: KayakFirstOrangeCommand,
    val sequence: Long,
    val timingMicroseconds: Long,
) {
    val timingSeconds: Double get() = timingMicroseconds / 1_000_000.0
}

@FitBridgeBleInternalApi
sealed interface KayakFirstOrangeResponse {
    data object ResetOk : KayakFirstOrangeResponse
    data class Timing(val value: KayakFirstOrangeTimingResponse) : KayakFirstOrangeResponse
    data object Rejected : KayakFirstOrangeResponse
}

/** Strict three-line response grammar: command, unsigned sequence, positive timing in microseconds. */
@FitBridgeBleInternalApi
class KayakFirstOrangeResponseParser {
    private val fields = mutableListOf<String>()

    fun accept(rawLine: String): KayakFirstOrangeResponse? {
        // The captured device pads the sequence line with trailing spaces on its first responses
        // (`"00438     "`). Nothing else in the transcript carries padding, and a trailing space on
        // an unsigned decimal cannot create a false accept, so trailing spaces are tolerated and
        // nothing else is. Leading signs, embedded spaces and every other lexical rule stand.
        val line = rawLine.trimEnd(' ')
        if (line == "OK" && fields.isEmpty()) return KayakFirstOrangeResponse.ResetOk
        when (fields.size) {
            0 -> if (KayakFirstOrangeCommand.fromLine(line) == null) {
                return KayakFirstOrangeResponse.Rejected
            }
            1 -> if (line.parseUnsignedLongStrict() == null) {
                fields.clear()
                return KayakFirstOrangeResponse.Rejected
            }
        }
        fields += line
        if (fields.size < 3) return null
        val complete = fields.toList()
        fields.clear()
        val command = KayakFirstOrangeCommand.fromLine(complete[0])
            ?: return KayakFirstOrangeResponse.Rejected
        if (command == KayakFirstOrangeCommand.RESET) return KayakFirstOrangeResponse.Rejected
        val sequence = complete[1].parseUnsignedLongStrict()
            ?: return KayakFirstOrangeResponse.Rejected
        val timing = complete[2].parseUnsignedLongStrict(maxDigits = 10)
            ?: return KayakFirstOrangeResponse.Rejected
        if (timing !in MIN_TIMING_US..MAX_TIMING_US) return KayakFirstOrangeResponse.Rejected
        return KayakFirstOrangeResponse.Timing(
            KayakFirstOrangeTimingResponse(command, sequence, timing),
        )
    }

    fun reset() = fields.clear()

    private fun String.parseUnsignedLongStrict(maxDigits: Int = 19): Long? {
        if (isEmpty() || length > maxDigits || any { it !in '0'..'9' }) return null
        return toLongOrNull()
    }

    private companion object {
        const val MIN_TIMING_US = 1L
        const val MAX_TIMING_US = 10_000_000L
    }
}

@FitBridgeBleInternalApi
data class KayakFirstOrangeTimingTuple(
    val tMinSeconds: Double,
    val tHSeconds: Double,
    val tMaxSeconds: Double,
    val tVSeconds: Double,
)

@FitBridgeBleInternalApi
data class KayakFirstOrangePhysicsResult(
    val powerWatts: Double,
    val forceNewtons: Double,
    val speedMetresPerSecond: Double,
    val strokeRateSpm: Double,
    val pace500Seconds: Double?,
)

/** E2 4.2.0 manufacturer equations recovered from the official Orange application. */
@FitBridgeBleInternalApi
object KayakFirstOrangePhysics {
    const val FLYWHEEL_INERTIA = 0.02527962
    const val FORCE_RADIUS_METRES = 0.027
    const val DRAG_COEFFICIENT = 0.1419262
    const val BOAT_MASS_OFFSET_KG = 12.0

    fun calculate(
        timing: KayakFirstOrangeTimingTuple,
        athleteWeightKg: Double?,
    ): KayakFirstOrangePhysicsResult? {
        val values = listOf(
            timing.tMinSeconds,
            timing.tHSeconds,
            timing.tMaxSeconds,
            timing.tVSeconds,
        )
        if (values.any { !it.isFinite() || it <= 0.0 }) return null
        if (timing.tMaxSeconds < timing.tMinSeconds) return null
        val mass = athleteWeightKg?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val omegaMax = 2.0 * PI / timing.tMinSeconds
        val omegaMin = 2.0 * PI / timing.tMaxSeconds
        val power = (0.5 * FLYWHEEL_INERTIA * omegaMax.pow(2.0) -
            0.5 * FLYWHEEL_INERTIA * omegaMin.pow(2.0)) / timing.tHSeconds
        val force = FLYWHEEL_INERTIA * (omegaMax - omegaMin) /
            (FORCE_RADIUS_METRES * timing.tHSeconds)
        if (!power.isFinite() || !force.isFinite() || power < 0.0 || force < 0.0) return null
        val effectiveMass = mass + BOAT_MASS_OFFSET_KG
        val speed = if (power == 0.0) 0.0 else {
            (power / (DRAG_COEFFICIENT * effectiveMass.pow(2.0 / 3.0))).pow(1.0 / 3.0)
        }
        val strokeRate = 60.0 / (timing.tHSeconds + timing.tVSeconds)
        if (!speed.isFinite() || !strokeRate.isFinite()) return null
        return KayakFirstOrangePhysicsResult(
            powerWatts = power,
            forceNewtons = force,
            speedMetresPerSecond = speed,
            strokeRateSpm = strokeRate,
            pace500Seconds = speed.takeIf { it > 0.0 }?.let { 500.0 / it },
        )
    }
}

@FitBridgeBleInternalApi
data class KayakFirstOrangeReading(
    val sequence: Long,
    val elapsedSeconds: Double,
    /**
     * Seconds this tuple represents, not the cumulative clock.
     *
     * Orange has no coverage field: the timing protocol reports one flywheel stroke period per
     * command and says nothing about how much time a tuple covers, so the represented duration is
     * the real interval since the previous accepted tuple, which is also the basis this session
     * already integrates distance, strokes and work on. The first tuple of a session represents
     * nothing, because there is no previous instant to measure from.
     */
    val representedSeconds: Double,
    val distanceMetres: Double,
    val strokeCount: Double,
    val averagePowerWatts: Double,
    val averageSpeedMetresPerSecond: Double,
    val averageStrokeRateSpm: Double,
    val physics: KayakFirstOrangePhysicsResult,
)

/** Owns the reset/poll sequence and only publishes complete, non-duplicate 1/2/3/4 tuples. */
@FitBridgeBleInternalApi
class KayakFirstOrangeSession(
    private val athleteWeightKg: () -> Double?,
) {
    private val timings = mutableMapOf<KayakFirstOrangeCommand, KayakFirstOrangeTimingResponse>()
    private val lastSequenceByCommand = mutableMapOf<KayakFirstOrangeCommand, Long>()
    private var expected = KayakFirstOrangeCommand.RESET
    private var lastTupleKey: List<Long>? = null
    private var lastReadingAtMs: Long? = null
    private var elapsedSeconds = 0.0
    private var distanceMetres = 0.0
    private var strokeCount = 0.0
    private var workWattSeconds = 0.0

    fun start(): KayakFirstOrangeCommand = KayakFirstOrangeCommand.RESET

    /** Some legacy UART bridges emit no reset acknowledgement; polling remains interoperable. */
    fun assumeResetComplete(): KayakFirstOrangeCommand? {
        if (expected != KayakFirstOrangeCommand.RESET) return null
        expected = KayakFirstOrangeCommand.T_MIN
        return expected
    }

    fun onResponse(response: KayakFirstOrangeResponse, nowMs: Long): Pair<KayakFirstOrangeCommand?, KayakFirstOrangeReading?> {
        if (response is KayakFirstOrangeResponse.Rejected) return null to null
        if (response is KayakFirstOrangeResponse.ResetOk) {
            if (expected != KayakFirstOrangeCommand.RESET) return null to null
            expected = KayakFirstOrangeCommand.T_MIN
            return expected to null
        }
        response as KayakFirstOrangeResponse.Timing
        val value = response.value
        if (value.command != expected || value.command == KayakFirstOrangeCommand.AUXILIARY) {
            return null to null
        }
        val previousSequence = lastSequenceByCommand[value.command]
        // Reject only a regression, never a repeat. The real Orange transcript shows the sequence
        // is a shared counter advancing with the flywheel stroke, so one command legitimately
        // returns the same value twice in a row: per-command sequences are non-decreasing but not
        // strictly increasing. Rejecting equality also stalled `expected`, costing the whole
        // following cycle — on the captured transcript that dropped 7 responses and cascaded into
        // 22 more, yielding 20 tuples where 26 are available. Genuine duplicate tuples are still
        // suppressed downstream by [lastTupleKey], which is what that guard is for.
        if (previousSequence != null && value.sequence < previousSequence) return null to null
        lastSequenceByCommand[value.command] = value.sequence
        timings[value.command] = value
        expected = when (value.command) {
            KayakFirstOrangeCommand.T_MIN -> KayakFirstOrangeCommand.T_H
            KayakFirstOrangeCommand.T_H -> KayakFirstOrangeCommand.T_MAX
            KayakFirstOrangeCommand.T_MAX -> KayakFirstOrangeCommand.T_V
            KayakFirstOrangeCommand.T_V -> KayakFirstOrangeCommand.T_MIN
            else -> return null to null
        }
        if (value.command != KayakFirstOrangeCommand.T_V) return expected to null
        val ordered = POLL_COMMANDS.map { timings[it] ?: return expected to null }
        val key = ordered.map { it.sequence }
        if (key == lastTupleKey) return expected to null
        lastTupleKey = key
        val physics = KayakFirstOrangePhysics.calculate(
            KayakFirstOrangeTimingTuple(
                ordered[0].timingSeconds,
                ordered[1].timingSeconds,
                ordered[2].timingSeconds,
                ordered[3].timingSeconds,
            ),
            athleteWeightKg(),
        ) ?: return expected to null
        var representedSeconds = 0.0
        lastReadingAtMs?.let { previous ->
            if (nowMs > previous) {
                val dt = (nowMs - previous) / 1_000.0
                representedSeconds = dt
                distanceMetres += physics.speedMetresPerSecond * dt
                strokeCount += physics.strokeRateSpm * dt / 60.0
                workWattSeconds += physics.powerWatts * dt
                elapsedSeconds += dt
            }
        }
        lastReadingAtMs = nowMs
        return expected to KayakFirstOrangeReading(
            sequence = key.maxOrNull() ?: 0L,
            elapsedSeconds = elapsedSeconds,
            representedSeconds = representedSeconds,
            distanceMetres = distanceMetres,
            strokeCount = strokeCount,
            averagePowerWatts = if (elapsedSeconds > 0.0) workWattSeconds / elapsedSeconds else physics.powerWatts,
            averageSpeedMetresPerSecond = if (elapsedSeconds > 0.0) distanceMetres / elapsedSeconds else physics.speedMetresPerSecond,
            averageStrokeRateSpm = if (elapsedSeconds > 0.0) strokeCount * 60.0 / elapsedSeconds else physics.strokeRateSpm,
            physics = physics,
        )
    }

    fun reset() {
        timings.clear()
        lastSequenceByCommand.clear()
        expected = KayakFirstOrangeCommand.RESET
        lastTupleKey = null
        lastReadingAtMs = null
        elapsedSeconds = 0.0
        distanceMetres = 0.0
        strokeCount = 0.0
        workWattSeconds = 0.0
    }

    private companion object {
        val POLL_COMMANDS = listOf(
            KayakFirstOrangeCommand.T_MIN,
            KayakFirstOrangeCommand.T_H,
            KayakFirstOrangeCommand.T_MAX,
            KayakFirstOrangeCommand.T_V,
        )
    }
}
