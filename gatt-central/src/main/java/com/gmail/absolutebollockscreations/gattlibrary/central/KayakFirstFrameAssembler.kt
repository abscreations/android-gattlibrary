package com.gmail.absolutebollockscreations.gattlibrary.central

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Why a Kayak First notification could not become a protocol frame. */
internal enum class KayakFirstFrameRejection {
    FRAME_TOO_LONG,
    INVALID_UTF8,
    EMBEDDED_NUL,
    INVALID_BINARY_FRAME,
}

/** One complete result emitted while consuming an arbitrary notification fragment. */
internal sealed interface KayakFirstFrameEvent {
    data object BinaryResetAcknowledgement : KayakFirstFrameEvent
    data class TextLine(val value: String) : KayakFirstFrameEvent
    data class Rejected(val reason: KayakFirstFrameRejection) : KayakFirstFrameEvent
}

/**
 * Connection-generation-owned reassembler for Kayak First's CRLF-delimited text protocol.
 *
 * Notifications are fragments, not frames. One call may complete no line, one line, or several
 * lines. The hardware-observed binary reset response is recognized before UTF-8 decoding; every
 * other frame must be strict UTF-8 and must not contain NUL. [reset] is mandatory at a connection
 * generation boundary so partial bytes cannot leak into a replacement link.
 */
internal class KayakFirstFrameAssembler(
    private val maximumPendingBytes: Int = DEFAULT_MAXIMUM_PENDING_BYTES,
) {
    private val pending = ArrayList<Byte>()

    init {
        require(maximumPendingBytes > 0) { "maximumPendingBytes must be positive" }
    }

    fun accept(fragment: ByteArray): List<KayakFirstFrameEvent> {
        if (fragment.isEmpty() || fragment.all { it == 0.toByte() } || fragment.all { it == 1.toByte() }) {
            return emptyList()
        }
        if (pending.isEmpty()) {
            when {
                fragment.contentEquals(BINARY_RESET_TMIW) ||
                    fragment.contentEquals(BINARY_RESET_CAPTURED) ->
                    return listOf(KayakFirstFrameEvent.BinaryResetAcknowledgement)
                fragment.size >= 2 && fragment[0] == RESET_BYTE && fragment[1] == SOH_BYTE ->
                    return listOf(
                        KayakFirstFrameEvent.Rejected(KayakFirstFrameRejection.INVALID_BINARY_FRAME),
                    )
            }
        }

        val events = mutableListOf<KayakFirstFrameEvent>()
        fragment.forEach { byte ->
            pending += byte
            if (pending.endsWithCrlf()) {
                val line = pending.subList(0, pending.size - CRLF_BYTES).toByteArray()
                pending.clear()
                events += if (line.size > maximumPendingBytes) {
                    KayakFirstFrameEvent.Rejected(KayakFirstFrameRejection.FRAME_TOO_LONG)
                } else {
                    decodeLine(line)
                }
            } else if (
                pending.size > maximumPendingBytes &&
                !(pending.size == maximumPendingBytes + 1 && pending.last() == CR_BYTE)
            ) {
                pending.clear()
                events += KayakFirstFrameEvent.Rejected(KayakFirstFrameRejection.FRAME_TOO_LONG)
            }
        }
        return events
    }

    fun reset() {
        pending.clear()
    }

    fun pendingByteCount(): Int = pending.size

    private fun decodeLine(bytes: ByteArray): KayakFirstFrameEvent {
        if (bytes.any { it == 0.toByte() }) {
            return KayakFirstFrameEvent.Rejected(KayakFirstFrameRejection.EMBEDDED_NUL)
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            KayakFirstFrameEvent.TextLine(decoder.decode(ByteBuffer.wrap(bytes)).toString())
        } catch (_: CharacterCodingException) {
            KayakFirstFrameEvent.Rejected(KayakFirstFrameRejection.INVALID_UTF8)
        }
    }

    private fun List<Byte>.endsWithCrlf(): Boolean =
        size >= CRLF_BYTES && this[size - 2] == CR_BYTE && this[size - 1] == LF_BYTE

    private companion object {
        // 4,096 matches the shipping KayakFit Bull client. A Bull console can carry several
        // 24-field frames plus a partial one in a single notification burst, and a 512-byte cap
        // discarded a legitimate in-flight line as FRAME_TOO_LONG. The cap still exists solely to
        // stop unbounded growth when a terminator never arrives.
        const val DEFAULT_MAXIMUM_PENDING_BYTES = 4_096
        const val CRLF_BYTES = 2
        val CR_BYTE = 0x0D.toByte()
        val LF_BYTE = 0x0A.toByte()
        val RESET_BYTE = 0x31.toByte()
        val SOH_BYTE = 0x01.toByte()
        val BINARY_RESET_TMIW = byteArrayOf(RESET_BYTE, SOH_BYTE)
        val BINARY_RESET_CAPTURED = byteArrayOf(RESET_BYTE, SOH_BYTE, 0x00)
    }
}
