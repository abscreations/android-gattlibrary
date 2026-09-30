package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.core.bytes.LittleEndian.u16
import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.WahooKickrSpec

/**
 * Persona policy for control-response indications.
 *
 * Unlock (`0x20 EE FC`) is still decoded, still applied, and still strictly acknowledged when a
 * client sends it - some clients (Incyclist's adapter, per
 * `docs/KICKR_PROPRIETARY_GATT_PORT_DESIGN.md`) always send it first. It is no longer a
 * *precondition* for every other opcode, though: a real-world interop capture (Kinomap against
 * this simulator, 2026-08-26) showed a client driving Resistance/Erg/Simulation/Grade/Wheel
 * Circumference for a full ~7-minute session without ever sending Unlock, which every one of
 * those writes then failed - Android surfaces `GATT_FAILURE` on the wire as ATT `0x01 Invalid
 * Handle` (see `docs/evidence/phase-9/2026-08-21-garmin-tacx-neo-interoperability.md`), which
 * reads exactly like a broken GATT registration and is not. The working assumption, absent a
 * documented case of a real KICKR rejecting an un-unlocked command, is that unlock-then-control
 * is one observed client convention, not something every real KICKR enforces - so this server
 * now accepts legacy control opcodes with or without a prior Unlock.
 */
internal enum class KickrLegacyAckMode(val evidence: KickrEvidenceLabel) {
    ACKNOWLEDGING_SYNTHETIC(KickrEvidenceLabel.SYN),
    SILENT_CORROBORATED(KickrEvidenceLabel.X),
}

internal sealed interface KickrLegacyDecodeResult {
    data class Decoded(val command: KickrLegacyCommand) : KickrLegacyDecodeResult
    data class Invalid(val reason: String) : KickrLegacyDecodeResult
    data class Unsupported(val opcode: Int) : KickrLegacyDecodeResult
}

/** Reverse of the production encoder, independently length/range checked before every access. */
internal object KickrLegacyCommandDecoder {
    fun decode(value: ByteArray): KickrLegacyDecodeResult {
        if (value.isEmpty()) return KickrLegacyDecodeResult.Invalid("empty command")
        val opcode = value[0].toInt() and 0xFF
        fun exact(size: Int): Boolean = value.size == size
        return when (opcode) {
            WahooKickrSpec.OPCODE_UNLOCK -> when {
                !exact(3) -> invalidLength(opcode, 3, value.size)
                value[1].toInt() and 0xFF != 0xEE || value[2].toInt() and 0xFF != 0xFC ->
                    KickrLegacyDecodeResult.Invalid("invalid unlock marker")
                else -> decoded(KickrLegacyCommand.Unlock)
            }
            WahooKickrSpec.OPCODE_RESISTANCE -> ifExact(value, 3, opcode) {
                val raw = u16(value, 1)
                if (raw > 0x3FFF) KickrLegacyDecodeResult.Invalid("resistance exceeds 14 bits")
                else decoded(KickrLegacyCommand.Resistance(1.0 - raw / 16_383.0))
            }
            WahooKickrSpec.OPCODE_STANDARD_LEVEL -> ifExact(value, 2, opcode) {
                val level = value[1].toInt() and 0xFF
                if (level !in WahooKickrSpec.MIN_STANDARD_LEVEL..WahooKickrSpec.MAX_STANDARD_LEVEL) {
                    KickrLegacyDecodeResult.Invalid("standard level out of range")
                } else decoded(KickrLegacyCommand.StandardLevel(level))
            }
            WahooKickrSpec.OPCODE_ERG -> ifExact(value, 3, opcode) {
                val watts = u16(value, 1)
                if (watts !in WahooKickrSpec.MIN_ERG_WATTS..WahooKickrSpec.MAX_ERG_WATTS) {
                    KickrLegacyDecodeResult.Invalid("ERG watts out of range")
                } else decoded(KickrLegacyCommand.Erg(watts))
            }
            WahooKickrSpec.OPCODE_SIMULATION -> ifExact(value, 7, opcode) {
                val mass = u16(value, 1) / 100.0
                val crr = u16(value, 3) / 10_000.0
                val cw = u16(value, 5) / 1_000.0
                when {
                    mass <= 0.0 || mass > WahooKickrSpec.MAX_TOTAL_MASS_KG ->
                        KickrLegacyDecodeResult.Invalid("simulation mass out of range")
                    crr > WahooKickrSpec.MAX_CRR -> KickrLegacyDecodeResult.Invalid("CRR out of range")
                    cw > WahooKickrSpec.MAX_CW -> KickrLegacyDecodeResult.Invalid("CW out of range")
                    else -> decoded(KickrLegacyCommand.Simulation(mass, crr, cw))
                }
            }
            WahooKickrSpec.OPCODE_ROLLING_RESISTANCE -> ifExact(value, 3, opcode) {
                val crr = u16(value, 1) / 10_000.0
                if (crr > WahooKickrSpec.MAX_CRR) KickrLegacyDecodeResult.Invalid("CRR out of range")
                else decoded(KickrLegacyCommand.RollingResistance(crr))
            }
            WahooKickrSpec.OPCODE_WIND_RESISTANCE -> ifExact(value, 3, opcode) {
                val cw = u16(value, 1) / 1_000.0
                if (cw > WahooKickrSpec.MAX_CW) KickrLegacyDecodeResult.Invalid("CW out of range")
                else decoded(KickrLegacyCommand.WindResistance(cw))
            }
            WahooKickrSpec.OPCODE_GRADE -> ifExact(value, 3, opcode) {
                decoded(KickrLegacyCommand.Grade(u16(value, 1) * 200.0 / 65_535.0 - 100.0))
            }
            WahooKickrSpec.OPCODE_WIND_SPEED -> ifExact(value, 3, opcode) {
                decoded(KickrLegacyCommand.WindSpeed(u16(value, 1) / 1_000.0 - 32.768))
            }
            WahooKickrSpec.OPCODE_WHEEL_CIRCUMFERENCE -> ifExact(value, 3, opcode) {
                val circumference = u16(value, 1) / 10.0
                if (circumference < KickrSyntheticModel.MIN_WHEEL_CIRCUMFERENCE_MM) {
                    KickrLegacyDecodeResult.Invalid("wheel circumference out of range")
                } else decoded(KickrLegacyCommand.WheelCircumference(circumference))
            }
            else -> KickrLegacyDecodeResult.Unsupported(opcode)
        }
    }

    private inline fun ifExact(
        value: ByteArray,
        expected: Int,
        opcode: Int,
        block: () -> KickrLegacyDecodeResult,
    ): KickrLegacyDecodeResult = if (value.size == expected) block() else invalidLength(opcode, expected, value.size)

    private fun invalidLength(opcode: Int, expected: Int, actual: Int) =
        KickrLegacyDecodeResult.Invalid("opcode 0x${opcode.toString(16)} requires $expected bytes, got $actual")

    private fun decoded(command: KickrLegacyCommand) = KickrLegacyDecodeResult.Decoded(command)
}

internal data class KickrLegacyControlOutcome(
    val accepted: Boolean,
    val response: ByteArray? = null,
    val decoded: KickrLegacyDecodeResult,
)

/**
 * Per-link legacy state. No unlock, route, or parameter survives disconnect.
 *
 * [unlocked] is diagnostic, not a gate: it starts `false`, flips `true` the moment any legacy
 * opcode is actually applied (an explicit Unlock or the first real control command - see
 * [handle]), and reports "has this link exercised legacy control since it connected/reset" for
 * [KickrPeripheralAdvertiser.diagnostics]'s `LEGACY_LOCKED`/`LEGACY_UNLOCKED` stage label.
 */
internal class KickrLegacyControlServer(
    private val model: KickrSyntheticModel,
    val ackMode: KickrLegacyAckMode,
) {
    var unlocked: Boolean = false
        private set
    private var simulation = KickrSimulationTarget()

    fun handle(value: ByteArray): KickrLegacyControlOutcome {
        val decoded = KickrLegacyCommandDecoder.decode(value)
        if (decoded !is KickrLegacyDecodeResult.Decoded) return KickrLegacyControlOutcome(false, decoded = decoded)
        val command = decoded.command
        if (command == KickrLegacyCommand.Unlock) {
            unlocked = true
            return KickrLegacyControlOutcome(true, success(WahooKickrSpec.OPCODE_UNLOCK), decoded)
        }
        // No unlock precondition here - see this class's KDoc. A client that skips Unlock and
        // goes straight to control still reaches [apply] below; [unlocked] flips true as a
        // side effect of that first successful command, same as it would after an explicit
        // Unlock, so diagnostics still read "unlocked" once real control is actually happening.
        val applied = apply(command)
        if (applied) unlocked = true
        val response = if (applied && ackMode == KickrLegacyAckMode.ACKNOWLEDGING_SYNTHETIC) {
            success(value[0].toInt() and 0xFF)
        } else null
        return KickrLegacyControlOutcome(applied, response, decoded)
    }

    fun reset() {
        unlocked = false
        simulation = KickrSimulationTarget()
    }

    private fun apply(command: KickrLegacyCommand): Boolean = when (command) {
        KickrLegacyCommand.Unlock -> true
        is KickrLegacyCommand.Erg -> model.setTargetPower(command.watts, KickrControlSource.LEGACY_KICKR)
        is KickrLegacyCommand.Resistance ->
            model.setResistance(
                KickrSyntheticModel.MIN_RESISTANCE + command.fraction *
                    (KickrSyntheticModel.MAX_RESISTANCE - KickrSyntheticModel.MIN_RESISTANCE),
                KickrControlSource.LEGACY_KICKR,
            )
        is KickrLegacyCommand.StandardLevel ->
            model.setResistance(command.level.toDouble(), KickrControlSource.LEGACY_KICKR)
        is KickrLegacyCommand.Simulation -> {
            simulation = simulation.copy(
                crr = command.crr,
                cw = command.cw,
                totalMassKg = command.totalMassKg,
            )
            model.setSimulation(simulation, KickrControlSource.LEGACY_KICKR)
        }
        is KickrLegacyCommand.RollingResistance -> {
            simulation = simulation.copy(crr = command.crr)
            model.setSimulation(simulation, KickrControlSource.LEGACY_KICKR)
        }
        is KickrLegacyCommand.WindResistance -> {
            simulation = simulation.copy(cw = command.cw)
            model.setSimulation(simulation, KickrControlSource.LEGACY_KICKR)
        }
        is KickrLegacyCommand.Grade -> {
            simulation = simulation.copy(gradePercent = command.percent)
            model.setSimulation(simulation, KickrControlSource.LEGACY_KICKR)
        }
        is KickrLegacyCommand.WindSpeed -> {
            simulation = simulation.copy(windMps = command.metresPerSecond)
            model.setSimulation(simulation, KickrControlSource.LEGACY_KICKR)
        }
        is KickrLegacyCommand.WheelCircumference -> model.setWheelCircumference(command.millimetres)
    }

    private fun success(opcode: Int): ByteArray = byteArrayOf(0x01, opcode.toByte(), 0x01, 0x00)
}
