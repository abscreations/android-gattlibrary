package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.KayakFirstDisplayConfiguration
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.KayakFirstDisplayMetric
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

enum class KayakFirstTelemetryMode { GENERATED_WORKOUT, CAPTURED_REPLAY }

enum class KayakFirstSimulatorScenario {
    HAPPY_PATH,
    CAPTURED_FRAGMENTATION,
    CRLF_SPLIT,
    TWO_LINES_ONE_NOTIFICATION,
    BINARY_RESET_RESPONSE,
    SHORT_RESPONSE,
    ROGUE_COMMAND_6,
    CONCATENATED_61,
    MALFORMED_UTF8,
    NUL_BYTE,
    EMPTY_LINE,
    OVERLONG_LINE,
    DELAYED_RESPONSE,
    MISSING_RESPONSE,
    FROZEN_METRICS,
    RESET_METRICS,
    IMPLAUSIBLE_METRICS,
    BACKPRESSURE,
}

/** Deterministic fault payloads; transport timing and disconnect faults remain controller-owned. */
internal object KayakFirstScenarioRenderer {
    fun render(scenario: KayakFirstSimulatorScenario, normal: ByteArray): ByteArray = when (scenario) {
        KayakFirstSimulatorScenario.TWO_LINES_ONE_NOTIFICATION -> normal + normal
        KayakFirstSimulatorScenario.ROGUE_COMMAND_6 -> "6\r\n".toByteArray()
        KayakFirstSimulatorScenario.CONCATENATED_61 -> "61\r\n".toByteArray()
        KayakFirstSimulatorScenario.MALFORMED_UTF8 -> byteArrayOf(0xC3.toByte(), 0x28, 13, 10)
        KayakFirstSimulatorScenario.NUL_BYTE -> byteArrayOf('6'.code.toByte(), 0, 13, 10)
        KayakFirstSimulatorScenario.EMPTY_LINE -> "\r\n".toByteArray()
        KayakFirstSimulatorScenario.OVERLONG_LINE -> ByteArray(300) { '9'.code.toByte() } + byteArrayOf(13, 10)
        KayakFirstSimulatorScenario.IMPLAUSIBLE_METRICS ->
            "6;900000000000;0;0;0;0.00;0;1;0;0;0.00;99.00;0;999;0;0;0;0;0;0;0;9999;0;1\r\n".toByteArray()
        KayakFirstSimulatorScenario.RESET_METRICS -> KayakFirstSimulatorTelemetryEncoder.captured(0)
        else -> normal
    }
}

internal sealed interface KayakFirstSimulatorCommand {
    data object Reset : KayakFirstSimulatorCommand
    data class Handshake(val epochSeconds: Long, val timezoneMinutes: Int) : KayakFirstSimulatorCommand
    data class Display(val configuration: KayakFirstDisplayConfiguration) : KayakFirstSimulatorCommand
    data object Poll : KayakFirstSimulatorCommand
    data object Start : KayakFirstSimulatorCommand
    data object Stop : KayakFirstSimulatorCommand
}

internal sealed interface KayakFirstSimulatorInput {
    data class Command(val value: KayakFirstSimulatorCommand) : KayakFirstSimulatorInput
    data class Rejected(val reason: String) : KayakFirstSimulatorInput
}

/** Independent peripheral-side command parser. Central codecs are intentionally not reused. */
internal class KayakFirstSimulatorCommandAssembler(private val maximumBytes: Int = 256) {
    private val pending = ArrayList<Byte>()

    fun append(bytes: ByteArray): List<KayakFirstSimulatorInput> {
        if (bytes.any { it == 0.toByte() }) {
            pending.clear()
            return listOf(KayakFirstSimulatorInput.Rejected("NUL byte"))
        }
        if (pending.size + bytes.size > maximumBytes) {
            pending.clear()
            return listOf(KayakFirstSimulatorInput.Rejected("line overflow"))
        }
        pending.addAll(bytes.toList())
        val output = mutableListOf<KayakFirstSimulatorInput>()
        while (true) {
            if (pending.size < 2) break
            val delimiter = (0 until pending.size - 1).firstOrNull {
                pending[it] == '\r'.code.toByte() && pending[it + 1] == '\n'.code.toByte()
            } ?: break
            val raw = pending.subList(0, delimiter).toByteArray()
            repeat(delimiter + 2) { pending.removeAt(0) }
            output += decode(raw)
        }
        return output
    }

    fun reset() = pending.clear()

    private fun decode(raw: ByteArray): KayakFirstSimulatorInput {
        val line = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw)).toString()
        }.getOrNull() ?: return KayakFirstSimulatorInput.Rejected("invalid UTF-8")
        if (line.isEmpty()) return KayakFirstSimulatorInput.Rejected("empty line")
        val parts = line.split(';')
        val command = when (parts.first()) {
            "1" -> if (parts.size == 1) KayakFirstSimulatorCommand.Reset else null
            "2" -> if (parts.size == 5 && parts[1].toLongOrNull()?.let { it >= 0 } == true &&
                parts[2].toIntOrNull() != null && parts[3] == "80" && parts[4] == "1"
            ) KayakFirstSimulatorCommand.Handshake(parts[1].toLong(), parts[2].toInt()) else null
            "5" -> KayakFirstDisplayConfiguration.fromWireCodes(parts.drop(1).mapNotNull(String::toIntOrNull))
                ?.takeIf { parts.size == 6 }?.let(KayakFirstSimulatorCommand::Display)
            "6" -> if (parts.size == 1) KayakFirstSimulatorCommand.Poll else null
            "9" -> when (parts.drop(1)) {
                listOf("1") -> KayakFirstSimulatorCommand.Start
                listOf("3") -> KayakFirstSimulatorCommand.Stop
                else -> null
            }
            else -> null
        }
        return command?.let(KayakFirstSimulatorInput::Command)
            ?: KayakFirstSimulatorInput.Rejected("unknown or malformed command")
    }
}

/** Peripheral serializer kept independent from KayakFirstMeasurement's central decoder. */
internal object KayakFirstSimulatorTelemetryEncoder {
    const val PERIOD_MS = 1_000L

    fun generated(sample: FtmsMockSample, running: Boolean): ByteArray {
        val fields = MutableList(24) { "0" }
        fields[0] = "6"
        fields[1] = (900_000_000_000L + sample.elapsedSeconds * 1_000L).toString()
        fields[5] = "0.00"
        fields[9] = (sample.distanceMeters ?: 0).coerceAtLeast(0).toString()
        fields[10] = "0.00"
        fields[11] = "%.2f".format(Locale.US, (sample.speedKph ?: 0.0).coerceAtLeast(0.0) / 3.6)
        fields[12] = "0.00"
        fields[13] = (sample.cadence ?: 0.0).coerceAtLeast(0.0).toInt().toString()
        fields[21] = (sample.powerWatts ?: 0).coerceAtLeast(0).toString()
        fields[22] = sample.elapsedSeconds.coerceAtLeast(0).toString()
        fields[23] = if (running) "1" else "0"
        return (fields.joinToString(";") + "\r\n").toByteArray(Charsets.UTF_8)
    }

    val capturedFrames: List<ByteArray> = listOf(
        "6;900000000000;0;0;0;0.00;0;0;0;0;0.00;0.00;0;0;0;0;0;0;0;0;0;0;0;0\r\n",
        "6;900000010449;0;0;0;0.00;0;1;0;11;0.94;2.75;98;76;229;72;573;180;1147;360;96;82;10;1\r\n",
        "6;900000020101;0;0;0;0.00;0;1;0;38;1.94;3.26;84;90;103;61;258;152;516;305;85;102;20;1\r\n",
    ).map { it.toByteArray(Charsets.UTF_8) }

    fun captured(index: Int): ByteArray = capturedFrames[index.mod(capturedFrames.size)].copyOf()
}

internal data class KayakFirstPanelRow(val slot: Int, val metric: KayakFirstDisplayMetric, val value: String)

internal object KayakFirstPanelPresenter {
    fun rows(
        configuration: KayakFirstDisplayConfiguration,
        sample: FtmsMockSample?,
        imperial: Boolean = false,
    ): List<KayakFirstPanelRow> = configuration.slots.mapIndexed { index, metric ->
        KayakFirstPanelRow(index + 1, metric, value(metric, sample, imperial))
    }

    private fun value(metric: KayakFirstDisplayMetric, sample: FtmsMockSample?, imperial: Boolean): String {
        if (sample == null) return EM_DASH
        val speedKph = when (metric) {
            KayakFirstDisplayMetric.AVERAGE_SPEED,
            KayakFirstDisplayMetric.AVERAGE_200M_PACE,
            KayakFirstDisplayMetric.AVERAGE_500M_PACE,
            KayakFirstDisplayMetric.AVERAGE_1000M_PACE,
            -> sample.averageSpeedKph
            else -> sample.speedKph
        }
        return when (metric) {
            KayakFirstDisplayMetric.ELAPSED_TIME -> sample.elapsedSeconds.takeIf { it > 0 }?.let(::formatElapsed) ?: EM_DASH
            KayakFirstDisplayMetric.TOTAL_DISTANCE -> sample.distanceMeters?.takeIf { it > 0 }?.let { "$it m" } ?: EM_DASH
            KayakFirstDisplayMetric.AVERAGE_SPEED,
            KayakFirstDisplayMetric.INSTANTANEOUS_SPEED,
            -> finitePositive(speedKph)?.let {
                if (imperial) "%.1f mph".format(Locale.US, it / 1.609344)
                else "%.1f km/h".format(Locale.US, it)
            } ?: EM_DASH
            KayakFirstDisplayMetric.AVERAGE_STROKE_RATE -> finitePositive(sample.averageCadence)?.let { "%.0f spm".format(Locale.US, it) } ?: EM_DASH
            KayakFirstDisplayMetric.INSTANTANEOUS_STROKE_RATE -> finitePositive(sample.cadence)?.let { "%.0f spm".format(Locale.US, it) } ?: EM_DASH
            KayakFirstDisplayMetric.AVERAGE_200M_PACE,
            KayakFirstDisplayMetric.INSTANTANEOUS_200M_PACE,
            -> pace(200, speedKph)
            KayakFirstDisplayMetric.AVERAGE_500M_PACE,
            KayakFirstDisplayMetric.INSTANTANEOUS_500M_PACE,
            -> pace(500, speedKph)
            KayakFirstDisplayMetric.AVERAGE_1000M_PACE,
            KayakFirstDisplayMetric.INSTANTANEOUS_1000M_PACE,
            -> pace(1_000, speedKph)
            KayakFirstDisplayMetric.AVERAGE_FORCE,
            KayakFirstDisplayMetric.INSTANTANEOUS_FORCE,
            -> EM_DASH
            KayakFirstDisplayMetric.TOTAL_EXPENDED_ENERGY -> sample.caloriesTotal.takeIf { it > 0 }?.let { "$it kcal" } ?: EM_DASH
            KayakFirstDisplayMetric.INSTANTANEOUS_POWER -> sample.powerWatts?.takeIf { it > 0 }?.let { "$it W" } ?: EM_DASH
            KayakFirstDisplayMetric.AVERAGE_POWER -> sample.averagePowerWatts?.takeIf { it > 0 }?.let { "$it W" } ?: EM_DASH
        }
    }

    private fun finitePositive(value: Double?) = value?.takeIf { it.isFinite() && it > 0.0 }
    private fun pace(metres: Int, speedKph: Double?) = finitePositive(speedKph)
        ?.let { (metres / (it / 3.6)).toInt().toString() + " sec" } ?: EM_DASH
    private fun formatElapsed(seconds: Int): String = if (seconds >= 3600) {
        "%d:%02d:%02d".format(Locale.US, seconds / 3600, seconds / 60 % 60, seconds % 60)
    } else {
        "%02d:%02d".format(Locale.US, seconds / 60, seconds % 60)
    }

    private const val EM_DASH = "—"
}
