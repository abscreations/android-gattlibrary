package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.KayakFirstOrangeCommand
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.KayakFirstOrangePhysics
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.KayakFirstOrangePhysicsResult
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.KayakFirstOrangeTimingTuple
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong

internal object KayakFirstOrangeSimulatorProtocol {
    const val ADVERTISED_NAME = "17100001"
    const val CAPTURED_T_MIN_US = 65_008L
    const val CAPTURED_T_H_US = 335_046L
    const val CAPTURED_T_MAX_US = 70_009L
    const val CAPTURED_T_V_US = 340_048L
    const val SEQUENCE_DIGITS = 5
    const val TIMING_DIGITS = 10

    fun decodeCommand(value: ByteArray): KayakFirstOrangeCommand? {
        if (value.size != 1) return null
        return KayakFirstOrangeCommand.entries.firstOrNull { it.wire == value[0] }
    }

    fun capturedTuple(): KayakFirstOrangeTimingTuple = KayakFirstOrangeTimingTuple(
        tMinSeconds = CAPTURED_T_MIN_US / 1_000_000.0,
        tHSeconds = CAPTURED_T_H_US / 1_000_000.0,
        tMaxSeconds = CAPTURED_T_MAX_US / 1_000_000.0,
        tVSeconds = CAPTURED_T_V_US / 1_000_000.0,
    )

    /**
     * Produces a physically consistent E2 tuple from the shared workout state.
     *
     * T_MAX stays at the captured 70.009 ms calibration point. T_H/T_V share one stroke period,
     * using the captured pull fraction. T_MIN is solved from the manufacturer's power equation,
     * so running the tuple back through [KayakFirstOrangePhysics] reproduces the requested power.
     */
    fun dynamicTuple(powerWatts: Double, cadenceSpm: Double): KayakFirstOrangeTimingTuple? {
        if (!powerWatts.isFinite() || powerWatts < 0.0) return null
        if (!cadenceSpm.isFinite() || cadenceSpm <= 0.0) return null
        val strokePeriod = 60.0 / cadenceSpm
        val capturedPullFraction = CAPTURED_T_H_US.toDouble() /
            (CAPTURED_T_H_US + CAPTURED_T_V_US).toDouble()
        val tH = strokePeriod * capturedPullFraction
        val tV = strokePeriod - tH
        val tMax = CAPTURED_T_MAX_US / 1_000_000.0
        val omegaMin = 2.0 * PI / tMax
        val omegaMaxSquared = omegaMin.pow(2.0) +
            2.0 * powerWatts * tH / KayakFirstOrangePhysics.FLYWHEEL_INERTIA
        val tMin = 2.0 * PI / kotlin.math.sqrt(omegaMaxSquared)
        return KayakFirstOrangeTimingTuple(tMin, tH, tMax, tV)
    }

    fun timingMicroseconds(
        command: KayakFirstOrangeCommand,
        tuple: KayakFirstOrangeTimingTuple,
        auxiliaryRpm: Double,
    ): Long? = when (command) {
        KayakFirstOrangeCommand.T_MIN -> tuple.tMinSeconds.toMicroseconds()
        KayakFirstOrangeCommand.T_H -> tuple.tHSeconds.toMicroseconds()
        KayakFirstOrangeCommand.T_MAX -> tuple.tMaxSeconds.toMicroseconds()
        KayakFirstOrangeCommand.T_V -> tuple.tVSeconds.toMicroseconds()
        KayakFirstOrangeCommand.AUXILIARY -> auxiliaryRpm.coerceAtLeast(0.0).roundToLong()
        KayakFirstOrangeCommand.RESET -> null
    }

    /**
     * Sequence for a response, modelled on the real transcript.
     *
     * The captured device does **not** count responses or commands. Its sequence is a free-running
     * counter that advances with the flywheel stroke, so the several commands answered inside one
     * stroke period all carry the same value, one command can return the same value twice in a
     * row, and values are skipped when a stroke completes between polls. Counting per response —
     * the simulator's first model — produced a strictly increasing stream that no console emits,
     * so the deterministic profile never exercised the central's real duplicate and repeat paths.
     *
     * The counter restarts at zero on RESET. The captured session began mid-workout at 438, which
     * says nothing about the value a reset leaves behind.
     */
    fun sequenceFor(tuple: KayakFirstOrangeTimingTuple, elapsedSinceResetMs: Long): Long {
        val strokePeriodSeconds = tuple.tHSeconds + tuple.tVSeconds
        if (strokePeriodSeconds <= 0.0 || elapsedSinceResetMs <= 0L) return 0L
        return floor(elapsedSinceResetMs / 1_000.0 / strokePeriodSeconds).toLong()
    }

    /** The device zero-pads both the sequence and the microsecond value on the wire. */
    fun response(command: KayakFirstOrangeCommand, sequence: Long, value: Long): ByteArray {
        val commandLine = command.wire.toInt().toChar()
        val sequenceLine = sequence.toString().padStart(SEQUENCE_DIGITS, '0')
        val timingLine = value.toString().padStart(TIMING_DIGITS, '0')
        return "$commandLine\n$sequenceLine\n$timingLine\n".toByteArray(Charsets.US_ASCII)
    }

    fun resetResponse(): ByteArray = "OK\n".toByteArray(Charsets.US_ASCII)

    /**
     * Fault scenarios that mean something on the Orange wire.
     *
     * Orange is LF-delimited three-line triples with no command 6, no display configuration and no
     * binary reset acknowledgement, so the Blue-only faults are deliberately absent rather than
     * silently rendered as a happy path.
     */
    val SUPPORTED_SCENARIOS: List<KayakFirstSimulatorScenario> = listOf(
        KayakFirstSimulatorScenario.HAPPY_PATH,
        KayakFirstSimulatorScenario.CAPTURED_FRAGMENTATION,
        KayakFirstSimulatorScenario.CRLF_SPLIT,
        KayakFirstSimulatorScenario.SHORT_RESPONSE,
        KayakFirstSimulatorScenario.MALFORMED_UTF8,
        KayakFirstSimulatorScenario.NUL_BYTE,
        KayakFirstSimulatorScenario.EMPTY_LINE,
        KayakFirstSimulatorScenario.OVERLONG_LINE,
        KayakFirstSimulatorScenario.DELAYED_RESPONSE,
        KayakFirstSimulatorScenario.MISSING_RESPONSE,
        KayakFirstSimulatorScenario.FROZEN_METRICS,
        KayakFirstSimulatorScenario.RESET_METRICS,
        KayakFirstSimulatorScenario.IMPLAUSIBLE_METRICS,
        KayakFirstSimulatorScenario.BACKPRESSURE,
    )

    fun supports(scenario: KayakFirstSimulatorScenario): Boolean = scenario in SUPPORTED_SCENARIOS

    /**
     * Counter faults: the bytes stay well formed and the *sequence* is what misbehaves.
     *
     * FROZEN_METRICS holds the stroke counter still, so the central sees a repeating tuple and must
     * suppress it downstream. RESET_METRICS drives it back to zero, which is the regression the
     * session must reject outright — the one sequence fault the transcript proves is not normal.
     */
    fun scenarioSequence(
        scenario: KayakFirstSimulatorScenario,
        naturalSequence: Long,
        frozenSequence: Long?,
    ): Long = when (scenario) {
        KayakFirstSimulatorScenario.FROZEN_METRICS -> frozenSequence ?: naturalSequence
        KayakFirstSimulatorScenario.RESET_METRICS -> 0L
        else -> naturalSequence
    }

    /**
     * Byte-level faults. Returns null when the scenario is "answer nothing at all".
     *
     * Each fault targets one documented rule of the Orange framing or numeric grammar so a central
     * regression names the rule it broke.
     */
    fun renderResponse(
        scenario: KayakFirstSimulatorScenario,
        command: KayakFirstOrangeCommand,
        sequence: Long,
        timingMicroseconds: Long,
    ): ByteArray? {
        val commandLine = command.wire.toInt().toChar()
        val sequenceLine = sequence.toString().padStart(SEQUENCE_DIGITS, '0')
        val timingLine = timingMicroseconds.toString().padStart(TIMING_DIGITS, '0')
        return when (scenario) {
            KayakFirstSimulatorScenario.MISSING_RESPONSE -> null
            // Truncated triple: the assembler must hold the partial response, not emit it.
            KayakFirstSimulatorScenario.SHORT_RESPONSE ->
                "$commandLine\n$sequenceLine\n".toByteArray(Charsets.US_ASCII)
            // Non-ASCII in a line the assembler must reject rather than decode.
            KayakFirstSimulatorScenario.MALFORMED_UTF8 ->
                byteArrayOf(command.wire, LF, 0xC3.toByte(), 0x28, LF) +
                    "$timingLine\n".toByteArray(Charsets.US_ASCII)
            KayakFirstSimulatorScenario.NUL_BYTE ->
                byteArrayOf(command.wire, LF, 0x00, LF) +
                    "$timingLine\n".toByteArray(Charsets.US_ASCII)
            // A bare delimiter ahead of a well-formed triple: rejected without consuming it.
            KayakFirstSimulatorScenario.EMPTY_LINE ->
                "\n$commandLine\n$sequenceLine\n$timingLine\n".toByteArray(Charsets.US_ASCII)
            KayakFirstSimulatorScenario.OVERLONG_LINE ->
                "$commandLine\n${"9".repeat(OVERLONG_LINE_DIGITS)}\n$timingLine\n"
                    .toByteArray(Charsets.US_ASCII)
            // Eleven digits exceeds the ten-digit microsecond grammar.
            KayakFirstSimulatorScenario.IMPLAUSIBLE_METRICS ->
                "$commandLine\n$sequenceLine\n99999999999\n".toByteArray(Charsets.US_ASCII)
            else -> response(command, sequence, timingMicroseconds)
        }
    }

    private const val OVERLONG_LINE_DIGITS = 48
    private val LF = '\n'.code.toByte()

    fun oracle(
        tuple: KayakFirstOrangeTimingTuple,
        athleteWeightKg: Double = 80.0,
    ): KayakFirstOrangePhysicsResult? = KayakFirstOrangePhysics.calculate(tuple, athleteWeightKg)

    private fun Double.toMicroseconds(): Long = (this * 1_000_000.0).roundToLong()
}
