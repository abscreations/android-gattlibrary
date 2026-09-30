package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi
import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.KayakFirstDisplayMetric
import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.KayakFirstHandshakeVariant
import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.KayakFirstSpec

/** Immutable five-slot snapshot shared later by settings, setup, and the simulator. */
data class KayakFirstDisplayConfiguration(
    val slot1: KayakFirstDisplayMetric,
    val slot2: KayakFirstDisplayMetric,
    val slot3: KayakFirstDisplayMetric,
    val slot4: KayakFirstDisplayMetric,
    val slot5: KayakFirstDisplayMetric,
) {
    val slots: List<KayakFirstDisplayMetric>
        get() = listOf(slot1, slot2, slot3, slot4, slot5)

    fun withSlot(index: Int, metric: KayakFirstDisplayMetric): KayakFirstDisplayConfiguration {
        require(index in 0 until SLOT_COUNT)
        val updated = slots.toMutableList().also { it[index] = metric }
        return KayakFirstDisplayConfiguration(
            updated[0], updated[1], updated[2], updated[3], updated[4],
        )
    }

    companion object {
        val DEFAULT = KayakFirstDisplayConfiguration(
            slot1 = KayakFirstDisplayMetric.ELAPSED_TIME,
            slot2 = KayakFirstDisplayMetric.TOTAL_DISTANCE,
            slot3 = KayakFirstDisplayMetric.AVERAGE_STROKE_RATE,
            slot4 = KayakFirstDisplayMetric.AVERAGE_1000M_PACE,
            slot5 = KayakFirstDisplayMetric.TOTAL_EXPENDED_ENERGY,
        )

        fun fromWireCodes(codes: List<Int>): KayakFirstDisplayConfiguration? {
            if (codes.size != SLOT_COUNT) return null
            val metrics = codes.map { KayakFirstDisplayMetric.fromWireCode(it) ?: return null }
            return KayakFirstDisplayConfiguration(
                metrics[0],
                metrics[1],
                metrics[2],
                metrics[3],
                metrics[4],
            )
        }

        const val SLOT_COUNT = 5
    }
}

/** Bounded sport values corroborated by the paired TMIW simulator captures. */
enum class KayakFirstSportMode(val wireCode: Int) {
    CANOEING(1),
    KAYAKING(2),
}

/**
 * Which **Blue** handshake dialect the user picks before connecting.
 *
 * This choice is Blue-only. Orange is resolved from the advertised numeric name before setup runs
 * and never sends a handshake of either dialect — it opens with RESET (`9`) and single-digit timing
 * polls — so an Orange console is not offered this choice at all.
 *
 * There is deliberately no automatic dialect fallback. On the captured consoles command 2 is never
 * answered — three unanswered handshakes followed by an accepted display command is the normal
 * healthy path — so silence cannot distinguish "wrong dialect" from "working console", and a
 * silence-triggered retry would send command 21 to every working Blue console. The user states the
 * dialect instead, once, at connect time.
 */
enum class KayakFirstProtocolChoice(
    val handshakeVariant: KayakFirstHandshakeVariant,
    val sportMode: KayakFirstSportMode,
) {
    /** Legacy Blue console (command-2 handshake), paddled as a kayak. */
    LEGACY_KAYAKING(KayakFirstHandshakeVariant.LEGACY, KayakFirstSportMode.KAYAKING),

    /** Legacy Blue console (command-2 handshake), paddled as a canoe. */
    LEGACY_CANOEING(KayakFirstHandshakeVariant.LEGACY, KayakFirstSportMode.CANOEING),

    /**
     * Modern KayakFirst Blue (Bull), command-21 handshake.
     *
     * Command 21 carries boat weight where the legacy form carries sport, so no sport is
     * transmitted for this choice. The stored value is inert and exists only so one connection
     * path can hold both dialects.
     */
    BULL(KayakFirstHandshakeVariant.BULL, KayakFirstSportMode.CANOEING),
    ;

    val isLegacy: Boolean get() = handshakeVariant == KayakFirstHandshakeVariant.LEGACY
}

/** Commands in the first D98/D99 central compatibility profile. */
@GattLibraryInternalApi
sealed interface KayakFirstCommand {
    data object Reset : KayakFirstCommand

    /** Common surface so setup can hold one handshake without knowing its dialect. */
    sealed interface Handshake : KayakFirstCommand {
        val unixEpochSeconds: Long
        val timezoneOffsetMinutes: Int
        val athleteWeightKg: Int
        val variant: KayakFirstHandshakeVariant
    }

    data class LegacyHandshake(
        override val unixEpochSeconds: Long,
        override val timezoneOffsetMinutes: Int,
        val sport: KayakFirstSportMode = KayakFirstSportMode.CANOEING,
        override val athleteWeightKg: Int = KayakFirstCommandCodec.NEUTRAL_WEIGHT_KG,
    ) : Handshake {
        override val variant = KayakFirstHandshakeVariant.LEGACY

        init {
            require(unixEpochSeconds >= 0L) { "unixEpochSeconds must be non-negative" }
            require(athleteWeightKg in KayakFirstCommandCodec.WEIGHT_RANGE_KG) {
                "athleteWeightKg out of range"
            }
        }
    }

    /**
     * Command-21 handshake observed in the shipping KayakFit Bull client.
     *
     * That client sends the form and proceeds without waiting for a reply, so this establishes the
     * transmitted shape only. It is not evidence of an acknowledgement line; setup therefore treats
     * an unanswered command 21 exactly as it treats an unanswered command 2.
     */
    data class BullHandshake(
        override val unixEpochSeconds: Long,
        override val timezoneOffsetMinutes: Int,
        override val athleteWeightKg: Int = KayakFirstCommandCodec.NEUTRAL_WEIGHT_KG,
        val boatWeightKg: Int = KayakFirstSpec.DEFAULT_BOAT_WEIGHT_KG,
    ) : Handshake {
        override val variant = KayakFirstHandshakeVariant.BULL

        init {
            require(unixEpochSeconds >= 0L) { "unixEpochSeconds must be non-negative" }
            require(athleteWeightKg in KayakFirstCommandCodec.WEIGHT_RANGE_KG) {
                "athleteWeightKg out of range"
            }
            require(boatWeightKg in KayakFirstCommandCodec.BOAT_WEIGHT_RANGE_KG) {
                "boatWeightKg out of range"
            }
        }
    }

    data class DisplayConfiguration(val configuration: KayakFirstDisplayConfiguration) :
        KayakFirstCommand

    data object Poll : KayakFirstCommand
    data object Start : KayakFirstCommand
    data object Stop : KayakFirstCommand
}

/** Pure command serializer and payload chunker. It owns no queue or Bluetooth state. */
@GattLibraryInternalApi
object KayakFirstCommandCodec {
    const val DEFAULT_PAYLOAD_CAPACITY = 20

    /**
     * Fallback athlete mass used when no FitBridge body weight is configured.
     *
     * This matches TMIW's neutral default. When a weight *is* configured, the configured value is
     * transmitted instead: the console uses athlete mass in its own speed/energy model, and a
     * fixed 80 kg makes every displayed derived value wrong for most users.
     */
    const val NEUTRAL_WEIGHT_KG = 80

    val WEIGHT_RANGE_KG = 20..250
    val BOAT_WEIGHT_RANGE_KG = 1..60

    /** Clamps a configured body weight into the transmittable range without inventing a value. */
    fun athleteWeightKgOrNeutral(configuredKg: Int?): Int =
        configuredKg?.takeIf { it in WEIGHT_RANGE_KG } ?: NEUTRAL_WEIGHT_KG

    internal fun encode(command: KayakFirstCommand): ByteArray = when (command) {
        KayakFirstCommand.Reset -> line("1")
        is KayakFirstCommand.LegacyHandshake -> line(
            "2;${command.unixEpochSeconds};${command.timezoneOffsetMinutes};" +
                "${command.athleteWeightKg};${command.sport.wireCode}",
        )
        is KayakFirstCommand.BullHandshake -> line(
            "21;${command.unixEpochSeconds};${command.timezoneOffsetMinutes};" +
                "${command.athleteWeightKg};${command.boatWeightKg}",
        )
        is KayakFirstCommand.DisplayConfiguration -> line(
            "5;${command.configuration.slots.joinToString(";") { it.wireCode.toString() }}",
        )
        KayakFirstCommand.Poll -> line("6")
        KayakFirstCommand.Start -> line("9;1")
        KayakFirstCommand.Stop -> line("9;3")
    }

    internal fun chunks(
        command: KayakFirstCommand,
        payloadCapacity: Int = DEFAULT_PAYLOAD_CAPACITY,
    ): List<ByteArray> {
        require(payloadCapacity > 0) { "payloadCapacity must be positive" }
        return encode(command).asList().chunked(payloadCapacity).map { it.toByteArray() }
    }

    private fun line(value: String): ByteArray = "$value\r\n".toByteArray(Charsets.UTF_8)
}

internal enum class KayakFirstResponseKind {
    RESET,
    HANDSHAKE,
    DISPLAY_CONFIGURATION,
    POLL,
    SESSION,
    KNOWN_OUT_OF_PROFILE,
}

internal enum class KayakFirstLineRejection {
    EMPTY_LINE,
    UNKNOWN_COMMAND,
}

internal data class KayakFirstTextResponse(
    val kind: KayakFirstResponseKind,
    val token: String,
    val fields: List<String>,
) {
    val isDisplaySuccess: Boolean
        get() = kind == KayakFirstResponseKind.DISPLAY_CONFIGURATION && fields == listOf("1")
}

internal sealed interface KayakFirstLineDecodeResult {
    data class Decoded(val response: KayakFirstTextResponse) : KayakFirstLineDecodeResult
    data class Rejected(val reason: KayakFirstLineRejection) : KayakFirstLineDecodeResult
}

/** Classifies a strict-UTF-8 line after framing; measurement validation remains separate. */
internal object KayakFirstResponseDecoder {
    fun decode(line: String): KayakFirstLineDecodeResult {
        if (line.isEmpty()) {
            return KayakFirstLineDecodeResult.Rejected(KayakFirstLineRejection.EMPTY_LINE)
        }
        val parts = line.split(';')
        val token = parts.first()
        val kind = when (token) {
            "1" -> KayakFirstResponseKind.RESET
            // Both handshake dialects acknowledge under their own token. Either one completes the
            // HANDSHAKE transaction; the sent variant decides which token is expected.
            "2", "21" -> KayakFirstResponseKind.HANDSHAKE
            "5" -> KayakFirstResponseKind.DISPLAY_CONFIGURATION
            "6" -> KayakFirstResponseKind.POLL
            "9" -> KayakFirstResponseKind.SESSION
            "3", "8" -> KayakFirstResponseKind.KNOWN_OUT_OF_PROFILE
            else -> return KayakFirstLineDecodeResult.Rejected(KayakFirstLineRejection.UNKNOWN_COMMAND)
        }
        return KayakFirstLineDecodeResult.Decoded(
            KayakFirstTextResponse(kind, token, parts.drop(1)),
        )
    }
}
