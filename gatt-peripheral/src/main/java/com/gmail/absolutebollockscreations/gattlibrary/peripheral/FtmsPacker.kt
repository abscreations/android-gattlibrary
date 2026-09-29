package com.gmail.absolutebollockscreations.gattlibrary.peripheral

/**
 * One optional field of an FTMS machine data characteristic.
 *
 * @param bit   flag bit index that declares this field present
 * @param bytes already-encoded payload, little-endian
 */
data class FtmsField(val bit: Int, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is FtmsField && other.bit == bit && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = 31 * bit + bytes.contentHashCode()
}

/** How to handle a logical record that is larger than one ATT value. */
enum class FtmsPayloadMode {
    /** Prefer one useful record for clients that do not reassemble FTMS fragments. */
    COMPATIBILITY_COMPACT,

    /** Preserve every field using the FTMS More Data continuation form. */
    STRICT_FRAGMENTATION,
}

enum class FtmsPackOutcome { COMPLETE, COMPACTED, FRAGMENTED }

/**
 * Assembles FTMS machine data notifications, fragmenting with the More Data bit
 * when the record does not fit one ATT payload.
 *
 * The previous per-sport encoders (`BtServiceBikeData` and friends) built a
 * single flat packet with every populated field and let the stack truncate it at
 * the negotiated MTU. On a Connect IQ watch, which stays at MTU 23, that silently
 * dropped the tail of every record.
 *
 * Rules implemented (FTMS v1.0 4.4.1.1 / 4.5.1.1 / ... and 4.19):
 *
 *  - Flag bit 0 is "More Data" and is INVERTED relative to every other bit:
 *    0 means the mandatory field(s) ARE present, 1 means this is a continuation
 *    packet carrying only optional fields. It does not mean "more packets
 *    follow".
 *  - The mandatory field appears only in the packet whose More Data bit is 0.
 *  - Each packet's flags describe only the fields in THAT packet.
 *  - Optional fields must appear in ascending flag-bit order.
 *  - When a record is split, Elapsed Time is repeated in every notification so a
 *    client can correlate fragments.
 *
 * Payload capacity is supplied from the connection's negotiated ATT MTU. The
 * default remains 20 octets (ATT_MTU 23 minus 3).
 */
object FtmsPacker {

    const val MAX_PAYLOAD = 20

    /**
     * @param flagsWidth   2 for every characteristic except Cross Trainer
     *                     (0x2ACE), which uses a 3-octet flags field
     * @param mandatory    field(s) present when More Data == 0; empty means the
     *                     characteristic has no mandatory field
     * @param optionals    MUST already be sorted ascending by [FtmsField.bit]
     * @param elapsedField repeated in every fragment when splitting occurs; pass
     *                     null if elapsed time is unavailable
     * @param priorityBits highest-value optional fields first. Used only by
     *                     compatibility compaction; wire order remains by bit.
     */
    fun pack(
        flagsWidth: Int,
        mandatory: ByteArray,
        optionals: List<FtmsField>,
        elapsedField: FtmsField? = null,
        maxPayload: Int = MAX_PAYLOAD,
        mode: FtmsPayloadMode = FtmsPayloadMode.STRICT_FRAGMENTATION,
        priorityBits: List<Int> = optionals.map { it.bit },
        onOutcome: (FtmsPackOutcome) -> Unit = {},
    ): List<ByteArray> {
        require(flagsWidth == 2 || flagsWidth == 3) { "flagsWidth must be 2 or 3, was $flagsWidth" }
        require(optionals.zipWithNext().all { (a, b) -> a.bit < b.bit }) {
            "optional fields must be strictly ascending by flag bit: " +
                optionals.joinToString { it.bit.toString() }
        }
        // A client that negotiates a larger ATT MTU can take the whole record in
        // one notification, which is the only form the non-reassembling parsers
        // in the field decode correctly. At MTU 23 this floors at 20 and we
        // fragment as before.
        val cap = maxPayload.coerceAtLeast(MAX_PAYLOAD)

        // Fast path: does everything fit in one packet?
        val singleSize = flagsWidth + mandatory.size + optionals.sumOf { it.bytes.size }
        if (singleSize <= cap) {
            var flags = 0
            optionals.forEach { flags = flags or (1 shl it.bit) }
            val out = ByteArray(singleSize)
            writeFlags(out, flags, flagsWidth)
            var o = flagsWidth
            mandatory.copyInto(out, o); o += mandatory.size
            optionals.forEach { f -> f.bytes.copyInto(out, o); o += f.bytes.size }
            onOutcome(FtmsPackOutcome.COMPLETE)
            return listOf(out)
        }

        if (mode == FtmsPayloadMode.COMPATIBILITY_COMPACT) {
            val budget = cap - flagsWidth - mandatory.size
            require(budget >= 0) { "mandatory fields do not fit in a $cap octet payload" }

            val byBit = optionals.associateBy { it.bit }
            val orderedPriorities = (priorityBits + optionals.map { it.bit }).distinct()
            var remaining = budget
            val chosen = mutableListOf<FtmsField>()
            for (bit in orderedPriorities) {
                val field = byBit[bit] ?: continue
                if (field.bytes.size <= remaining) {
                    chosen += field
                    remaining -= field.bytes.size
                }
            }
            chosen.sortBy { it.bit }

            var flags = 0
            chosen.forEach { flags = flags or (1 shl it.bit) }
            val size = flagsWidth + mandatory.size + chosen.sumOf { it.bytes.size }
            val out = ByteArray(size)
            writeFlags(out, flags, flagsWidth)
            var offset = flagsWidth
            mandatory.copyInto(out, offset)
            offset += mandatory.size
            chosen.forEach { field ->
                field.bytes.copyInto(out, offset)
                offset += field.bytes.size
            }
            onOutcome(FtmsPackOutcome.COMPACTED)
            return listOf(out)
        }

        // Fragmented.
        //
        // First packet carries the mandatory field(s) with More Data clear;
        // continuation packets set More Data and omit them.
        //
        // Elapsed Time must be present in EVERY notification of a split record
        // (FTMS revision D05r06 added that requirement so a client can correlate
        // fragments), so it is pulled out of the ordinary stream and injected
        // into each packet rather than being consumed in bit order.
        val stream = if (elapsedField != null) {
            optionals.filter { it.bit != elapsedField.bit }
        } else {
            optionals
        }

        val packets = mutableListOf<ByteArray>()
        var cursor = 0
        var first = true

        while (first || cursor < stream.size) {
            val header = flagsWidth + if (first) mandatory.size else 0
            var budget = cap - header - (elapsedField?.bytes?.size ?: 0)

            val chosen = mutableListOf<FtmsField>()
            while (cursor < stream.size) {
                val f = stream[cursor]
                if (f.bytes.size > budget) break
                chosen += f
                budget -= f.bytes.size
                cursor++
            }

            if (!first && chosen.isEmpty() && cursor < stream.size) {
                // A single field larger than the whole budget. Cannot happen with
                // the current field set (max 5 octets), but fail loudly rather
                // than spin forever if a future field breaks the assumption.
                error(
                    "field with bit ${stream[cursor].bit} does not fit in a $cap octet payload"
                )
            }

            val all = if (elapsedField != null) {
                (chosen + elapsedField).sortedBy { it.bit }
            } else {
                chosen
            }

            var flags = if (first) 0 else 1 // More Data
            all.forEach { flags = flags or (1 shl it.bit) }

            val size = flagsWidth + (if (first) mandatory.size else 0) + all.sumOf { it.bytes.size }
            val out = ByteArray(size)
            writeFlags(out, flags, flagsWidth)
            var o = flagsWidth
            if (first) {
                mandatory.copyInto(out, o); o += mandatory.size
            }
            all.forEach { f -> f.bytes.copyInto(out, o); o += f.bytes.size }
            packets += out

            first = false
        }

        onOutcome(FtmsPackOutcome.FRAGMENTED)
        return packets
    }

    private fun writeFlags(out: ByteArray, flags: Int, width: Int) {
        out[0] = (flags and 0xFF).toByte()
        out[1] = ((flags ushr 8) and 0xFF).toByte()
        if (width == 3) out[2] = ((flags ushr 16) and 0xFF).toByte()
    }
}
