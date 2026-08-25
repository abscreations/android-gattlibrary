package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.WahooKickrCommand
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.WahooKickrUuids

/** Persona policy for post-unlock indications. Unlock itself is always strictly acknowledged. */
internal enum class KickrLegacyAckMode(val evidence: KickrEvidenceLabel) {
    ACKNOWLEDGING_SYNTHETIC(KickrEvidenceLabel.SYN),
    SILENT_CORROBORATED(KickrEvidenceLabel.X),
}

internal sealed interface KickrLegacyDecodeResult {
    data class Decoded(val command: WahooKickrCommand) : KickrLegacyDecodeResult
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
            WahooKickrUuids.OPCODE_UNLOCK -> when {
                !exact(3) -> invalidLength(opcode, 3, value.size)
                value[1].toInt() and 0xFF != 0xEE || value[2].toInt() and 0xFF != 0xFC ->
                    KickrLegacyDecodeResult.Invalid("invalid unlock marker")
                else -> decoded(WahooKickrCommand.Unlock)
            }
            WahooKickrUuids.OPCODE_RESISTANCE -> ifExact(value, 3, opcode) {
                val raw = u16(value, 1)
                if (raw > 0x3FFF) KickrLegacyDecodeResult.Invalid("resistance exceeds 14 bits")
                else decoded(WahooKickrCommand.Resistance(1.0 - raw / 16_383.0))
            }
            WahooKickrUuids.OPCODE_STANDARD_LEVEL -> ifExact(value, 2, opcode) {
                val level = value[1].toInt() and 0xFF
                if (level !in WahooKickrUuids.MIN_STANDARD_LEVEL..WahooKickrUuids.MAX_STANDARD_LEVEL) {
                    KickrLegacyDecodeResult.Invalid("standard level out of range")
                } else decoded(WahooKickrCommand.StandardLevel(level))
            }
            WahooKickrUuids.OPCODE_ERG -> ifExact(value, 3, opcode) {
                val watts = u16(value, 1)
                if (watts !in WahooKickrUuids.MIN_ERG_WATTS..WahooKickrUuids.MAX_ERG_WATTS) {
                    KickrLegacyDecodeResult.Invalid("ERG watts out of range")
                } else decoded(WahooKickrCommand.Erg(watts))
            }
            WahooKickrUuids.OPCODE_SIMULATION -> ifExact(value, 7, opcode) {
                val mass = u16(value, 1) / 100.0
                val crr = u16(value, 3) / 10_000.0
                val cw = u16(value, 5) / 1_000.0
                when {
                    mass <= 0.0 || mass > WahooKickrUuids.MAX_TOTAL_MASS_KG ->
                        KickrLegacyDecodeResult.Invalid("simulation mass out of range")
                    crr > WahooKickrUuids.MAX_CRR -> KickrLegacyDecodeResult.Invalid("CRR out of range")
                    cw > WahooKickrUuids.MAX_CW -> KickrLegacyDecodeResult.Invalid("CW out of range")
                    else -> decoded(WahooKickrCommand.Simulation(mass, crr, cw))
                }
            }
            WahooKickrUuids.OPCODE_ROLLING_RESISTANCE -> ifExact(value, 3, opcode) {
                val crr = u16(value, 1) / 10_000.0
                if (crr > WahooKickrUuids.MAX_CRR) KickrLegacyDecodeResult.Invalid("CRR out of range")
                else decoded(WahooKickrCommand.RollingResistance(crr))
            }
            WahooKickrUuids.OPCODE_WIND_RESISTANCE -> ifExact(value, 3, opcode) {
                val cw = u16(value, 1) / 1_000.0
                if (cw > WahooKickrUuids.MAX_CW) KickrLegacyDecodeResult.Invalid("CW out of range")
                else decoded(WahooKickrCommand.WindResistance(cw))
            }
            WahooKickrUuids.OPCODE_GRADE -> ifExact(value, 3, opcode) {
                decoded(WahooKickrCommand.Grade(u16(value, 1) * 200.0 / 65_535.0 - 100.0))
            }
            WahooKickrUuids.OPCODE_WIND_SPEED -> ifExact(value, 3, opcode) {
                decoded(WahooKickrCommand.WindSpeed(u16(value, 1) / 1_000.0 - 32.768))
            }
            WahooKickrUuids.OPCODE_WHEEL_CIRCUMFERENCE -> ifExact(value, 3, opcode) {
                val circumference = u16(value, 1) / 10.0
                if (circumference < KickrSyntheticModel.MIN_WHEEL_CIRCUMFERENCE_MM) {
                    KickrLegacyDecodeResult.Invalid("wheel circumference out of range")
                } else decoded(WahooKickrCommand.WheelCircumference(circumference))
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

    private fun decoded(command: WahooKickrCommand) = KickrLegacyDecodeResult.Decoded(command)

    private fun u16(value: ByteArray, offset: Int): Int =
        (value[offset].toInt() and 0xFF) or ((value[offset + 1].toInt() and 0xFF) shl 8)
}

internal data class KickrLegacyControlOutcome(
    val accepted: Boolean,
    val response: ByteArray? = null,
    val decoded: KickrLegacyDecodeResult,
)

/** Per-link legacy state. No unlock, route, or parameter survives disconnect. */
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
        if (command == WahooKickrCommand.Unlock) {
            unlocked = true
            return KickrLegacyControlOutcome(true, success(WahooKickrUuids.OPCODE_UNLOCK), decoded)
        }
        if (!unlocked) return KickrLegacyControlOutcome(false, decoded = decoded)
        val applied = apply(command)
        val response = if (applied && ackMode == KickrLegacyAckMode.ACKNOWLEDGING_SYNTHETIC) {
            success(value[0].toInt() and 0xFF)
        } else null
        return KickrLegacyControlOutcome(applied, response, decoded)
    }

    fun reset() {
        unlocked = false
        simulation = KickrSimulationTarget()
    }

    private fun apply(command: WahooKickrCommand): Boolean = when (command) {
        WahooKickrCommand.Unlock -> true
        is WahooKickrCommand.Erg -> model.setTargetPower(command.watts)
        is WahooKickrCommand.Resistance ->
            model.setResistance(KickrSyntheticModel.MIN_RESISTANCE + command.fraction *
                (KickrSyntheticModel.MAX_RESISTANCE - KickrSyntheticModel.MIN_RESISTANCE))
        is WahooKickrCommand.StandardLevel -> model.setResistance(command.level.toDouble())
        is WahooKickrCommand.Simulation -> {
            simulation = simulation.copy(crr = command.crr, cw = command.cw)
            model.setSimulation(simulation)
        }
        is WahooKickrCommand.RollingResistance -> {
            simulation = simulation.copy(crr = command.crr); model.setSimulation(simulation)
        }
        is WahooKickrCommand.WindResistance -> {
            simulation = simulation.copy(cw = command.cw); model.setSimulation(simulation)
        }
        is WahooKickrCommand.Grade -> {
            simulation = simulation.copy(gradePercent = command.percent); model.setSimulation(simulation)
        }
        is WahooKickrCommand.WindSpeed -> {
            simulation = simulation.copy(windMps = command.metresPerSecond); model.setSimulation(simulation)
        }
        is WahooKickrCommand.WheelCircumference -> model.setWheelCircumference(command.millimetres)
    }

    private fun success(opcode: Int): ByteArray = byteArrayOf(0x01, opcode.toByte(), 0x01, 0x00)
}
