package com.gmail.absolutebollockscreations.gattlibrary.central

private const val KM_PER_MILE = 1.609344
private const val SOLE_START = 0x5b
private const val SOLE_END = 0x5d

private fun ByteArray.octet(index: Int): Int = this[index].toInt() and 0xff

/** Captures include complete frames and R92's split 20+2 notifications. */
internal class SoleFrameAssembler {
    private var pending = byteArrayOf()
    private var pendingAtMs = 0L

    fun reset() {
        pending = byteArrayOf()
    }

    fun accept(value: ByteArray, nowMs: Long): List<ByteArray> {
        if (value.size > 512) {
            reset()
            return emptyList()
        }
        val hasRecentPrefix = pending.isNotEmpty() && nowMs - pendingAtMs in 0..1_000L
        val bytes = if (hasRecentPrefix) pending + value else value
        reset()
        var offset = 0
        return buildList {
            while (offset < bytes.size) {
                if (bytes.octet(offset) != SOLE_START) {
                    offset++
                    continue
                }
                val size = if (offset + 1 < bytes.size) bytes.octet(offset + 1) + 3 else 4
                if (size !in 4..64) {
                    offset++
                    continue
                }
                val end = offset + size
                if (end > bytes.size) {
                    pending = bytes.copyOfRange(offset, bytes.size)
                    if (!hasRecentPrefix || offset >= bytes.size - value.size) pendingAtMs = nowMs
                    break
                }
                if (bytes.octet(end - 1) == SOLE_END) {
                    add(bytes.copyOfRange(offset, end))
                    offset = end
                } else {
                    offset++
                }
            }
        }
    }
}

/** Field offsets follow the captured Sole frames and the QDomyos-Zwift Sole drivers. */
internal fun soleMeasurements(model: SoleModel, frame: ByteArray): Map<MetricKind, Number> {
    if (frame.size !in 4..64 || frame.octet(0) != SOLE_START ||
        frame.octet(1) + 3 != frame.size || frame.octet(frame.lastIndex) != SOLE_END
    ) return emptyMap()
    fun u16be(offset: Int): Int = (frame.octet(offset) shl 8) or frame.octet(offset + 1)
    return buildMap {
        when {
            !model.bike && frame.size == 18 && frame.octet(2) == 0x06 -> {
                put(MetricKind.SPEED, frame.octet(10) * KM_PER_MILE / 10)
                put(MetricKind.INCLINATION, frame.octet(11))
                frame.octet(9).takeIf { it in 25..240 }?.let { put(MetricKind.HEART_RATE, it) }
            }
            model.bike && frame.size == 22 && frame.octet(2) == 0x06 -> {
                put(MetricKind.ELAPSED, u16be(3))
                put(MetricKind.DISTANCE, u16be(7) * KM_PER_MILE * 10)
                put(MetricKind.CADENCE, frame.octet(10))
                put(MetricKind.SPEED, u16be(11) * KM_PER_MILE / 100)
                put(MetricKind.POWER, u16be(13))
            }
            model.bike && frame.size == 5 && frame.octet(2) == 0x13 ->
                put(MetricKind.RESISTANCE, frame.octet(3))
        }
    }
}

/** Returns true only for an exact Sole short frame or the captured legacy F80 placeholder. */
internal fun handleSoleShortTreadmillData(
    model: SoleModel?,
    legacyF80: Boolean,
    value: ByteArray,
    emitSpeed: (Double) -> Unit,
): Boolean {
    if (value.size != 4 || value.octet(1) != 1) return false
    val speedRaw = value.octet(2) or (value.octet(3) shl 8)
    return when {
        model == SoleModel.F65 && value.octet(0) == 0 -> {
            emitSpeed(speedRaw / 100.0)
            true
        }
        model == SoleModel.F65 && value.octet(0) == 1 && speedRaw == 0 -> true
        model == SoleModel.F80 && legacyF80 && value.octet(0) == 0 && speedRaw == 0 -> true
        else -> false
    }
}
