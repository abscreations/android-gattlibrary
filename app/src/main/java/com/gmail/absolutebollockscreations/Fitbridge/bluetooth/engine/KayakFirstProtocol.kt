package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

/** The 17 display choices defined by pinned Apache-2.0 TMIW source. */
internal enum class KayakFirstDisplayMetric(val wireCode: Int, val displayLabel: String) {
    ELAPSED_TIME(0, "Elapsed Time"),
    TOTAL_DISTANCE(2, "Total Distance"),
    AVERAGE_SPEED(3, "Average Speed"),
    INSTANTANEOUS_SPEED(4, "Instantaneous Speed"),
    AVERAGE_STROKE_RATE(5, "Average Stroke Rate"),
    INSTANTANEOUS_STROKE_RATE(6, "Inst. Stroke Rate"),
    AVERAGE_200M_PACE(7, "Average 200m Pace (sec)"),
    INSTANTANEOUS_200M_PACE(8, "Inst. 200m Pace (sec)"),
    AVERAGE_500M_PACE(9, "Average 500m Pace (sec)"),
    INSTANTANEOUS_500M_PACE(10, "Inst. 500m Pace (sec)"),
    AVERAGE_1000M_PACE(11, "Average 1000m Pace (sec)"),
    INSTANTANEOUS_1000M_PACE(12, "Inst. 1000m Pace (sec)"),
    AVERAGE_FORCE(13, "Average Force"),
    INSTANTANEOUS_FORCE(14, "Instantaneous Force"),
    TOTAL_EXPENDED_ENERGY(15, "Total Expended Energy"),
    INSTANTANEOUS_POWER(17, "Instantaneous Power"),
    AVERAGE_POWER(18, "Average Power"),
    ;

    companion object {
        private val byWireCode = entries.associateBy(KayakFirstDisplayMetric::wireCode)

        fun fromWireCode(code: Int): KayakFirstDisplayMetric? = byWireCode[code]
    }
}

/** Immutable five-slot snapshot shared later by settings, setup, and the simulator. */
internal data class KayakFirstDisplayConfiguration(
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

/** Commands in the first D98/D99 central compatibility profile. */
internal sealed interface KayakFirstCommand {
    data object Reset : KayakFirstCommand

    data class LegacyHandshake(
        val unixEpochSeconds: Long,
        val timezoneOffsetMinutes: Int,
    ) : KayakFirstCommand {
        init {
            require(unixEpochSeconds >= 0L) { "unixEpochSeconds must be non-negative" }
        }
    }

    data class DisplayConfiguration(val configuration: KayakFirstDisplayConfiguration) :
        KayakFirstCommand

    data object Poll : KayakFirstCommand
    data object Start : KayakFirstCommand
    data object Stop : KayakFirstCommand
}

/** Pure command serializer and payload chunker. It owns no queue or Bluetooth state. */
internal object KayakFirstCommandCodec {
    const val DEFAULT_PAYLOAD_CAPACITY = 20
    const val NEUTRAL_WEIGHT_KG = 80
    const val KAYAKING_SPORT_CODE = 1

    fun encode(command: KayakFirstCommand): ByteArray = when (command) {
        KayakFirstCommand.Reset -> line("1")
        is KayakFirstCommand.LegacyHandshake -> line(
            "2;${command.unixEpochSeconds};${command.timezoneOffsetMinutes};" +
                "$NEUTRAL_WEIGHT_KG;$KAYAKING_SPORT_CODE",
        )
        is KayakFirstCommand.DisplayConfiguration -> line(
            "5;${command.configuration.slots.joinToString(";") { it.wireCode.toString() }}",
        )
        KayakFirstCommand.Poll -> line("6")
        KayakFirstCommand.Start -> line("9;1")
        KayakFirstCommand.Stop -> line("9;3")
    }

    fun chunks(
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
            "2" -> KayakFirstResponseKind.HANDSHAKE
            "5" -> KayakFirstResponseKind.DISPLAY_CONFIGURATION
            "6" -> KayakFirstResponseKind.POLL
            "9" -> KayakFirstResponseKind.SESSION
            "21", "3", "8" -> KayakFirstResponseKind.KNOWN_OUT_OF_PROFILE
            else -> return KayakFirstLineDecodeResult.Rejected(KayakFirstLineRejection.UNKNOWN_COMMAND)
        }
        return KayakFirstLineDecodeResult.Decoded(
            KayakFirstTextResponse(kind, token, parts.drop(1)),
        )
    }
}
