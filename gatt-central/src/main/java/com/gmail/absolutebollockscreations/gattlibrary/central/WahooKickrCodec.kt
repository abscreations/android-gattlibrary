package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi

import kotlin.math.roundToInt
import com.gmail.absolutebollockscreations.gattlibrary.core.bytes.LittleEndian.u16Wrapping

@GattLibraryInternalApi
sealed interface WahooKickrCommand {
    data object Unlock : WahooKickrCommand
    data class Resistance(val fraction: Double) : WahooKickrCommand
    data class StandardLevel(val level: Int) : WahooKickrCommand
    data class Erg(val watts: Int) : WahooKickrCommand
    data class Simulation(
        val totalMassKg: Double,
        val crr: Double,
        val cw: Double,
    ) : WahooKickrCommand
    data class RollingResistance(val crr: Double) : WahooKickrCommand
    data class WindResistance(val cw: Double) : WahooKickrCommand
    data class Grade(val percent: Double) : WahooKickrCommand
    data class WindSpeed(val metresPerSecond: Double) : WahooKickrCommand
    data class WheelCircumference(val millimetres: Double) : WahooKickrCommand
}

@GattLibraryInternalApi
data class WahooKickrValidationError(
    val field: String,
    val reason: String,
)

@GattLibraryInternalApi
sealed interface WahooKickrEncodeResult {
    data class Encoded(val bytes: ByteArray) : WahooKickrEncodeResult
    data class Invalid(val error: WahooKickrValidationError) : WahooKickrEncodeResult
}

/** Pure legacy KICKR encoder. It validates engineering values and has no transport side effects. */
@GattLibraryInternalApi
object WahooKickrCodec {
    fun encode(command: WahooKickrCommand): WahooKickrEncodeResult = when (command) {
        WahooKickrCommand.Unlock -> encoded(
            WahooKickrUuids.OPCODE_UNLOCK,
            0xEE,
            0xFC,
        )
        is WahooKickrCommand.Resistance -> finite(command.fraction, "resistance") {
            val raw = ((1.0 - command.fraction.coerceIn(0.0, 1.0)) * 16_383.0).roundToInt()
            encoded(WahooKickrUuids.OPCODE_RESISTANCE, u16Wrapping(raw))
        }
        is WahooKickrCommand.StandardLevel -> range(
            command.level,
            WahooKickrUuids.MIN_STANDARD_LEVEL..WahooKickrUuids.MAX_STANDARD_LEVEL,
            "standardLevel",
        ) { encoded(WahooKickrUuids.OPCODE_STANDARD_LEVEL, command.level) }
        is WahooKickrCommand.Erg -> range(
            command.watts,
            WahooKickrUuids.MIN_ERG_WATTS..WahooKickrUuids.MAX_ERG_WATTS,
            "watts",
        ) { encoded(WahooKickrUuids.OPCODE_ERG, i16(command.watts)) }
        is WahooKickrCommand.Simulation -> encodeSimulation(command)
        is WahooKickrCommand.RollingResistance -> coefficient(
            command.crr,
            WahooKickrUuids.MAX_CRR,
            "crr",
        ) { encoded(WahooKickrUuids.OPCODE_ROLLING_RESISTANCE, i16((it * 10_000).roundToInt())) }
        is WahooKickrCommand.WindResistance -> coefficient(
            command.cw,
            WahooKickrUuids.MAX_CW,
            "cw",
        ) { encoded(WahooKickrUuids.OPCODE_WIND_RESISTANCE, i16((it * 1_000).roundToInt())) }
        is WahooKickrCommand.Grade -> finite(command.percent, "grade") {
            val clamped = command.percent.coerceIn(-100.0, 100.0)
            val raw = ((clamped + 100.0) * 65_535.0 / 200.0).roundToInt()
            encoded(WahooKickrUuids.OPCODE_GRADE, u16Wrapping(raw))
        }
        is WahooKickrCommand.WindSpeed -> finite(command.metresPerSecond, "windSpeed") {
            val clamped = command.metresPerSecond.coerceIn(-32.768, 32.767)
            val raw = ((clamped + 32.768) * 1_000.0).roundToInt()
            encoded(WahooKickrUuids.OPCODE_WIND_SPEED, u16Wrapping(raw))
        }
        is WahooKickrCommand.WheelCircumference -> {
            val value = command.millimetres
            if (!value.isFinite() || value < 0.1 ||
                value > WahooKickrUuids.MAX_WHEEL_CIRCUMFERENCE_MM
            ) {
                invalid("wheelCircumference", "must be finite and in 0.1..6553.5 mm")
            } else {
                encoded(
                    WahooKickrUuids.OPCODE_WHEEL_CIRCUMFERENCE,
                    u16Wrapping((value * 10.0).roundToInt()),
                )
            }
        }
    }

    private fun encodeSimulation(
        command: WahooKickrCommand.Simulation,
    ): WahooKickrEncodeResult {
        if (!command.totalMassKg.isFinite() || command.totalMassKg <= 0.0 ||
            command.totalMassKg > WahooKickrUuids.MAX_TOTAL_MASS_KG
        ) {
            return invalid("totalMassKg", "must be finite and in 0.0..327.67 kg")
        }
        return coefficient(command.crr, WahooKickrUuids.MAX_CRR, "crr") { crr ->
            coefficient(command.cw, WahooKickrUuids.MAX_CW, "cw") { cw ->
                encoded(
                    WahooKickrUuids.OPCODE_SIMULATION,
                    i16((command.totalMassKg * 100.0).roundToInt()),
                    i16((crr * 10_000.0).roundToInt()),
                    i16((cw * 1_000.0).roundToInt()),
                )
            }
        }
    }

    private inline fun finite(
        value: Double,
        field: String,
        block: () -> WahooKickrEncodeResult,
    ): WahooKickrEncodeResult =
        if (value.isFinite()) block() else invalid(field, "must be finite")

    private inline fun coefficient(
        value: Double,
        maximum: Double,
        field: String,
        block: (Double) -> WahooKickrEncodeResult,
    ): WahooKickrEncodeResult =
        if (value.isFinite() && value in 0.0..maximum) block(value)
        else invalid(field, "must be finite and in 0.0..$maximum")

    private inline fun range(
        value: Int,
        accepted: IntRange,
        field: String,
        block: () -> WahooKickrEncodeResult,
    ): WahooKickrEncodeResult =
        if (value in accepted) block() else invalid(field, "must be in $accepted")

    private fun encoded(opcode: Int, vararg fields: Any): WahooKickrEncodeResult.Encoded {
        val bytes = ArrayList<Byte>(1 + fields.size * 2)
        bytes += opcode.toByte()
        fields.forEach { field ->
            when (field) {
                is Int -> bytes += field.toByte()
                is ByteArray -> field.forEach(bytes::add)
                else -> error("Unsupported encoded field ${field::class.java.simpleName}")
            }
        }
        return WahooKickrEncodeResult.Encoded(bytes.toByteArray())
    }

    private fun i16(value: Int): ByteArray = u16Wrapping(value and 0xFFFF)

    private fun invalid(field: String, reason: String) = WahooKickrEncodeResult.Invalid(
        WahooKickrValidationError(field, reason),
    )
}
