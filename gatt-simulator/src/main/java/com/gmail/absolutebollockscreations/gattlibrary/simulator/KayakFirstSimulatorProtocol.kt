package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi

import com.gmail.absolutebollockscreations.gattlibrary.central.KayakFirstDisplayConfiguration
import com.gmail.absolutebollockscreations.gattlibrary.central.KayakFirstDerivedMetrics
import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.KayakFirstDisplayMetric
import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.KayakFirstHandshakeVariant
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale
import kotlin.math.roundToInt
import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.KayakFirstSpec

internal fun kayakFirstIdleSample() = FtmsMockSample(
    elapsedSeconds = 0,
    heartRate = 0,
    caloriesTotal = 0,
    caloriesPerHour = 0,
    caloriesPerMinute = 0,
    speedKph = 0.0,
    averageSpeedKph = 0.0,
    distanceMeters = 0,
    cadence = 0.0,
    averageCadence = 0.0,
    powerWatts = 0,
    averagePowerWatts = 0,
    movementCount = 0,
)

internal sealed interface KayakFirstSimulatorCommand {
    data object Reset : KayakFirstSimulatorCommand
    /**
     * Either handshake dialect.
     *
     * [sport] is populated by the legacy command-2 form and [boatWeightKg] by the Bull command-21
     * form; each is null for the other dialect. The simulator accepts both so central-side dialect
     * selection and official-client interoperability can both be exercised.
     */
    data class Handshake(
        val epochSeconds: Long,
        val timezoneMinutes: Int,
        val bodyWeightKg: Double,
        val sport: Int? = null,
        val boatWeightKg: Double? = null,
        val variant: KayakFirstHandshakeVariant = KayakFirstHandshakeVariant.LEGACY,
    ) : KayakFirstSimulatorCommand
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
                parts[2].toIntOrNull() != null &&
                parts[3].toDoubleOrNull()?.let { it.isFinite() && it in 20.0..250.0 } == true &&
                parts[4].toIntOrNull()?.let { it in 1..2 } == true
            ) KayakFirstSimulatorCommand.Handshake(
                epochSeconds = parts[1].toLong(),
                timezoneMinutes = parts[2].toInt(),
                bodyWeightKg = parts[3].toDouble(),
                sport = parts[4].toInt(),
            ) else null
            "21" -> if (parts.size == 5 && parts[1].toLongOrNull()?.let { it >= 0 } == true &&
                parts[2].toIntOrNull() != null &&
                parts[3].toDoubleOrNull()?.let { it.isFinite() && it in 20.0..250.0 } == true &&
                parts[4].toDoubleOrNull()?.let { it.isFinite() && it in 1.0..60.0 } == true
            ) KayakFirstSimulatorCommand.Handshake(
                epochSeconds = parts[1].toLong(),
                timezoneMinutes = parts[2].toInt(),
                bodyWeightKg = parts[3].toDouble(),
                boatWeightKg = parts[4].toDouble(),
                variant = KayakFirstHandshakeVariant.BULL,
            ) else null
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

    /**
     * Renders one command-6 record.
     *
     * [activePaddling] is field 7 and means *the paddler is working*, a session state the console
     * holds at 1 for the whole of an effort and clears for coast and stop frames. It is not a
     * per-stroke edge: a caller that raises it only when a new stroke lands turns a continuous
     * effort into a flag that toggles at the stroke period, and every consumer that gates on
     * field 7 — instantaneous speed, instantaneous cadence, and the derived stroke count — then
     * loses whatever fraction of frames fall between strokes.
     *
     * [coverageSeconds] is field 23 and is the separate, correct place to say that a frame carries
     * nothing new. A repeated datapoint covers zero seconds and is weighed as zero; it is not
     * inactive.
     */
    fun generated(
        sample: FtmsMockSample,
        running: Boolean,
        activePaddling: Boolean = running,
        coverageSeconds: Int = if (running) 1 else 0,
    ): ByteArray {
        val fields = MutableList(24) { "0" }
        fields[0] = "6"
        fields[1] = (900_000_000_000L + sample.elapsedSeconds * 1_000L).toString()
        fields[5] = "0.00"
        fields[7] = if (activePaddling) "1" else "0"
        fields[9] = (sample.distanceMeters ?: 0).coerceAtLeast(0).toString()
        val averageSpeedMps = (sample.averageSpeedKph ?: sample.speedKph ?: 0.0).coerceAtLeast(0.0) / 3.6
        val speedMps = (sample.speedKph ?: 0.0).coerceAtLeast(0.0) / 3.6
        fields[10] = "%.2f".format(Locale.US, averageSpeedMps)
        fields[11] = "%.2f".format(Locale.US, speedMps)
        fields[12] = (sample.averageCadence ?: sample.cadence ?: 0.0).coerceAtLeast(0.0).roundToInt().toString()
        fields[13] = (sample.cadence ?: 0.0).coerceAtLeast(0.0).roundToInt().toString()
        fun pace(distanceMetres: Int, metresPerSecond: Double): String =
            if (metresPerSecond > 0.0) (distanceMetres / metresPerSecond).roundToInt().toString() else "0"
        fields[14] = pace(200, averageSpeedMps)
        fields[15] = pace(200, speedMps)
        fields[16] = pace(500, averageSpeedMps)
        fields[17] = pace(500, speedMps)
        fields[18] = pace(1_000, averageSpeedMps)
        fields[19] = pace(1_000, speedMps)
        // C6[20]/[21] are average/instantaneous pull force in newtons, never power. The values are
        // inverted from the shared snapshot's watts through the same force/length/cadence relation
        // the central uses, so a simulator-to-central round trip reproduces the snapshot's power
        // instead of asserting an independent force model.
        fields[20] = forceNewtons(sample.averagePowerWatts, sample.averageCadence ?: sample.cadence)
        fields[21] = forceNewtons(sample.powerWatts, sample.cadence)
        fields[22] = sample.elapsedSeconds.coerceAtLeast(0).toString()
        // C6[23] is the coverage window in seconds for this datapoint. It is the span of session
        // time this record newly accounts for, so a poll that re-reads a datapoint already sent
        // declares zero rather than repeating the previous window's width.
        fields[23] = coverageSeconds.coerceAtLeast(0).toString()
        return (fields.joinToString(";") + "\r\n").toByteArray(Charsets.UTF_8)
    }

    /**
     * Inverts `P = F x L x cadence / 60` so generated force stays consistent with generated power.
     *
     * The result is a whole newton because that is what consoles put on the wire: every captured
     * command-6 frame carries integer force, and the shipping KayakFit client types fields 20/21
     * as integers and drops any frame whose force contains a decimal point. Emitting `"%.2f"` here
     * made that client reject five consecutive packets and time out, while FitBridge's own
     * decimal-tolerant parser accepted them — a divergence only a second client could find.
     */
    private fun forceNewtons(powerWatts: Int?, cadenceSpm: Double?): String {
        val watts = (powerWatts ?: 0).coerceAtLeast(0).toDouble()
        val cadence = (cadenceSpm ?: 0.0).coerceAtLeast(0.0)
        if (watts <= 0.0 || cadence <= 0.0) return "0"
        val force = watts * 60.0 / (KayakFirstSpec.DEFAULT_PULL_LENGTH_M * cadence)
        return force.roundToInt().coerceAtLeast(0).toString()
    }
}

@GattLibraryInternalApi
data class KayakFirstPanelRow(val slot: Int, val metric: KayakFirstDisplayMetric, val value: String)

@GattLibraryInternalApi
object KayakFirstPanelPresenter {
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
