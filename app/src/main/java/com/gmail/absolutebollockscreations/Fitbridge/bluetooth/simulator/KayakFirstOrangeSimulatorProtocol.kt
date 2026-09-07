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

    /**
     * The one captured hardware tuple, kept as the generator's calibration point rather than as
     * replayable data: T_MAX is used verbatim, and T_H/T_V set the pull fraction every generated
     * tuple is shaped by. Deleting these would leave nothing to calibrate generation against.
     */
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

    /** The calibration tuple itself, as a reference vector. Not a replay mode. */
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

    fun oracle(
        tuple: KayakFirstOrangeTimingTuple,
        athleteWeightKg: Double = 80.0,
    ): KayakFirstOrangePhysicsResult? = KayakFirstOrangePhysics.calculate(tuple, athleteWeightKg)

    private fun Double.toMicroseconds(): Long = (this * 1_000_000.0).roundToLong()
}
