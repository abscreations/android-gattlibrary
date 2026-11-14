package com.gmail.absolutebollockscreations.gattlibrary.simulator

/**
 * Synthetic F65 proprietary UART responder.
 *
 * The F65 fixture has no captured vendor transcript, so this is a provisional behavior model.
 * The handshake and reply shapes come from the separately observed F65 HCI samples (labeled
 * F80 files 2 and 3, a different unit from the fixture's FitBridge capture) and the QDomyos-Zwift
 * F65 initialization order at revision 89f1213e. No QZ code is copied.
 *
 * - `5B 01 F0 5D` returns the captured F65 identity reply and an idle state report.
 * - State (`03`) and `02` frames are echoed, as in the capture.
 * - Other client commands are acknowledged as `5B 04 00 <cmd> 4F 4B 5D` ("OK").
 * - Client acknowledgements (`5B 04 00 <x> 4F 4B 5D`) get no reply. The final handshake
 *   acknowledgement (`<x> = 40`) opens the workout stream, matching where the capture first shows
 *   18-byte workout frames.
 */
internal class SoleF65VendorResponder {
    var started: Boolean = false
        private set

    fun reset() {
        started = false
    }

    fun accept(value: ByteArray): List<ByteArray> {
        if (value.size !in 4..64 || value.octet(0) != FRAME_START || value.octet(value.lastIndex) != FRAME_END ||
            value.octet(1) + 3 != value.size
        ) return emptyList()
        return when (val command = value.octet(2)) {
            IDENTIFY -> if (value.size == 4) listOf(IDENTITY_REPLY.copyOf(), IDLE_STATE.copyOf()) else emptyList()
            ACK -> {
                if (value.size == 7 && value.octet(3) == HANDSHAKE_COMPLETE) started = true
                emptyList()
            }
            STATE, ECHO -> listOf(value.copyOf())
            else -> listOf(byteArrayOf(0x5b, 0x04, 0x00, command.toByte(), 0x4f, 0x4b, 0x5d))
        }
    }

    private companion object {
        const val FRAME_START = 0x5b
        const val FRAME_END = 0x5d
        const val ACK = 0x00
        const val ECHO = 0x02
        const val STATE = 0x03
        const val IDENTIFY = 0xf0
        const val HANDSHAKE_COMPLETE = 0x40
        val IDENTITY_REPLY = "5b08f0910000dc0a0f125d".hexToByteArray()
        val IDLE_STATE = "5b0203015d".hexToByteArray()

        fun ByteArray.octet(index: Int): Int = this[index].toInt() and 0xff
    }
}
