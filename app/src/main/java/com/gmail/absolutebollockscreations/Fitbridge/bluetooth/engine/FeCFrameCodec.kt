package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

/**
 * One decoded ANT serial message carried inside a FEC2 notification.
 *
 * [data] is the FE-C payload as ANT+ defines it: the data page number in `data[0]` followed
 * by up to 7 payload bytes, i.e. `bytes[4 until checksumIndex]` of the wire frame with the
 * leading `channel` byte stripped off. Phase 9 assumes one complete ANT frame per BLE
 * notification (plan §2.2/§2.3) - there is no reassembly buffer here.
 */
internal data class AntSerialFrame(
    val messageType: Int,
    val channel: Int,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AntSerialFrame) return false
        return messageType == other.messageType && channel == other.channel && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = messageType
        result = 31 * result + channel
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/** Structured rejection reasons for [FeCFrameCodec.decode], per plan §14 diagnostics. */
internal enum class FeCFrameRejection {
    TOO_SHORT,
    BAD_SYNC,
    BAD_LENGTH,
    BAD_CHECKSUM,
    UNSUPPORTED_MESSAGE_TYPE,
    SHORT_KNOWN_PAGE,
}

internal sealed interface FeCFrameDecodeResult {
    data class Decoded(val frame: AntSerialFrame) : FeCFrameDecodeResult
    data class Rejected(val reason: FeCFrameRejection) : FeCFrameDecodeResult
}

/**
 * Encodes and decodes the ANT serial envelope FE-C over BLE carries inside FEC2/FEC3
 * notify/write payloads.
 *
 * Wire layout (`docs/How-to FE-C over BLE v1_0_0.pdf` page 5, plan §2.3): `sync(1) |
 * length=N(1) | msgType(1) | channel(1) | payload(N-1 bytes, page + up to 7 data bytes) |
 * checksum(1)`. Total size is `N+4` bytes; the checksum sits at index `N+3`. `N` is the ANT
 * "length" field - the byte count from `channel` through the last payload byte, NOT
 * including sync, length, msgType, or the checksum itself.
 *
 * The checksum is a plain XOR over every byte from `sync` through the last payload byte
 * (indices `0 until N+3`), with **no modulo applied to the running value**. Incyclist's
 * TypeScript FE-C client applies `% 0xFF` to the accumulator on every step, which is a
 * documented defect (`docs/fe-c-ble_central_implementation.md` §3.2, D-series decisions) -
 * XOR never needs a modulo, and applying one silently corrupts the checksum for any byte
 * sequence where it changes the result. This implementation does not carry that behavior
 * over. Verified against the Tacx PDF's worked example: `A4 09 4F 05 33 FF FF FF FF 20 4E 42
 * F8` XORs (over the first 12 bytes) to `0xF8`, matching the trailing checksum byte.
 */
internal object FeCFrameCodec {

    /** ANT+ broadcast/acknowledged data messages carry a fixed page(1) + payload(7) = 8 bytes. */
    private const val ANT_STANDARD_PAGE_LENGTH = 8

    private val KNOWN_PAGES = setOf(
        BluetoothIds.FEC_PAGE_GENERAL_FE_DATA,
        BluetoothIds.FEC_PAGE_SPECIFIC_TRAINER_DATA,
        BluetoothIds.FEC_PAGE_PRODUCT_INFORMATION,
    )

    fun decode(bytes: ByteArray): FeCFrameDecodeResult {
        if (bytes.size < 5) return FeCFrameDecodeResult.Rejected(FeCFrameRejection.TOO_SHORT)

        val sync = bytes[0].toInt() and 0xFF
        if (sync != BluetoothIds.FEC_ANT_SYNC) {
            return FeCFrameDecodeResult.Rejected(FeCFrameRejection.BAD_SYNC)
        }

        val n = bytes[1].toInt() and 0xFF
        val expectedTotalSize = n + 4
        if (bytes.size != expectedTotalSize || n < 2) {
            // n < 2 would leave no room for a channel byte, i.e. an internally inconsistent
            // length even if the overall byte count happened to line up.
            return FeCFrameDecodeResult.Rejected(FeCFrameRejection.BAD_LENGTH)
        }

        val messageType = bytes[2].toInt() and 0xFF
        if (messageType != BluetoothIds.FEC_ANT_MSG_BROADCAST_DATA &&
            messageType != BluetoothIds.FEC_ANT_MSG_ACKNOWLEDGED_DATA
        ) {
            return FeCFrameDecodeResult.Rejected(FeCFrameRejection.UNSUPPORTED_MESSAGE_TYPE)
        }

        val checksumIndex = n + 3
        var computedChecksum = 0
        for (i in 0 until checksumIndex) {
            computedChecksum = computedChecksum xor (bytes[i].toInt() and 0xFF)
        }
        val declaredChecksum = bytes[checksumIndex].toInt() and 0xFF
        if (computedChecksum != declaredChecksum) {
            return FeCFrameDecodeResult.Rejected(FeCFrameRejection.BAD_CHECKSUM)
        }

        val channel = bytes[3].toInt() and 0xFF
        val data = bytes.copyOfRange(4, checksumIndex)

        val page = data.getOrNull(0)?.toInt()?.and(0xFF)
        if (page != null && page in KNOWN_PAGES && data.size < ANT_STANDARD_PAGE_LENGTH) {
            return FeCFrameDecodeResult.Rejected(FeCFrameRejection.SHORT_KNOWN_PAGE)
        }

        return FeCFrameDecodeResult.Decoded(AntSerialFrame(messageType, channel, data))
    }

    /**
     * Encodes a full ANT serial message for FEC3. [payload] is the FE-C page payload
     * (page number followed by up to 7 data bytes) - callers are the `FeCControlEncoder`
     * functions, not general-purpose code.
     */
    fun encode(messageType: Int, channel: Int, payload: ByteArray): ByteArray {
        val n = payload.size + 1 // channel byte + payload
        val out = ByteArray(n + 4)
        out[0] = BluetoothIds.FEC_ANT_SYNC.toByte()
        out[1] = n.toByte()
        out[2] = messageType.toByte()
        out[3] = channel.toByte()
        payload.copyInto(out, destinationOffset = 4)
        var checksum = 0
        for (i in 0 until (n + 3)) {
            checksum = checksum xor (out[i].toInt() and 0xFF)
        }
        out[n + 3] = checksum.toByte()
        return out
    }
}
