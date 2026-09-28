package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

/** Length-safe interpretation of one indication while a particular opcode is in flight. */
internal sealed interface WahooKickrResponse {
    val raw: ByteArray

    data class Success(val opcode: Int, override val raw: ByteArray) : WahooKickrResponse

    /** Reserved for captured negative status values; the initial parser deliberately emits none. */
    data class ExplicitFailure(
        val opcode: Int,
        val status: Int,
        override val raw: ByteArray,
    ) : WahooKickrResponse

    data class Unknown(override val raw: ByteArray) : WahooKickrResponse

    data class Unrelated(
        val expectedOpcode: Int,
        val echoedOpcode: Int,
        override val raw: ByteArray,
    ) : WahooKickrResponse
}

internal object WahooKickrResponseParser {
    fun parse(bytes: ByteArray, expectedOpcode: Int): WahooKickrResponse {
        val raw = bytes.copyOf()
        if (raw.size < 2 || raw[0].toInt() and 0xFF != 0x01) {
            return WahooKickrResponse.Unknown(raw)
        }
        val echoedOpcode = raw[1].toInt() and 0xFF
        if (echoedOpcode != expectedOpcode) {
            return WahooKickrResponse.Unrelated(expectedOpcode, echoedOpcode, raw)
        }
        return if (raw.size >= 4 &&
            raw[2].toInt() and 0xFF == 0x01 &&
            raw[3].toInt() and 0xFF == 0x00
        ) {
            WahooKickrResponse.Success(echoedOpcode, raw)
        } else {
            // No negative status table is corroborated. A correlated but unfamiliar response is
            // diagnostic evidence and must not be promoted to success or invented failure.
            WahooKickrResponse.Unknown(raw)
        }
    }
}
