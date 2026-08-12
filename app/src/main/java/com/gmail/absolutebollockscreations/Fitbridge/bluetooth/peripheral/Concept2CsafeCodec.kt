package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

/** Specification-derived CSAFE framing used only by the simulated PM5 peripheral. */
object Concept2CsafeCodec {
    const val MAX_WIRE_FRAME_SIZE = 120

    data class Command(val identifier: Int, val data: ByteArray)

    sealed interface DecodeResult {
        data class Success(val commands: List<Command>) : DecodeResult
        data class Failure(val reason: FailureReason) : DecodeResult
    }

    enum class FailureReason {
        FRAME_TOO_LARGE,
        INVALID_DELIMITERS,
        EXTENDED_FRAME_UNSUPPORTED,
        INVALID_BYTE_STUFFING,
        BAD_CHECKSUM,
        TRUNCATED_COMMAND,
    }

    fun decodeStandard(wire: ByteArray): DecodeResult {
        if (wire.size > MAX_WIRE_FRAME_SIZE) return DecodeResult.Failure(FailureReason.FRAME_TOO_LARGE)
        if (wire.firstOrNull()?.u8() == EXTENDED_START) {
            return DecodeResult.Failure(FailureReason.EXTENDED_FRAME_UNSUPPORTED)
        }
        if (wire.size < 3 || wire.first().u8() != STANDARD_START || wire.last().u8() != STOP) {
            return DecodeResult.Failure(FailureReason.INVALID_DELIMITERS)
        }

        val unstuffed = ArrayList<Byte>(wire.size - 2)
        var index = 1
        while (index < wire.lastIndex) {
            val value = wire[index].u8()
            when (value) {
                STUFF -> {
                    if (index + 1 >= wire.lastIndex) {
                        return DecodeResult.Failure(FailureReason.INVALID_BYTE_STUFFING)
                    }
                    val replacement = when (wire[index + 1].u8()) {
                        0 -> EXTENDED_START
                        1 -> STANDARD_START
                        2 -> STOP
                        3 -> STUFF
                        else -> return DecodeResult.Failure(FailureReason.INVALID_BYTE_STUFFING)
                    }
                    unstuffed += replacement.toByte()
                    index += 2
                }
                EXTENDED_START, STANDARD_START, STOP ->
                    return DecodeResult.Failure(FailureReason.INVALID_BYTE_STUFFING)
                else -> {
                    unstuffed += wire[index]
                    index++
                }
            }
        }
        if (unstuffed.isEmpty()) return DecodeResult.Failure(FailureReason.BAD_CHECKSUM)

        val suppliedChecksum = unstuffed.removeAt(unstuffed.lastIndex).u8()
        val contents = unstuffed.toByteArray()
        if (xor(contents) != suppliedChecksum) return DecodeResult.Failure(FailureReason.BAD_CHECKSUM)
        return when (val parsed = parseCommands(contents)) {
            is CommandParse.Success -> DecodeResult.Success(parsed.commands)
            CommandParse.Truncated -> DecodeResult.Failure(FailureReason.TRUNCATED_COMMAND)
        }
    }

    fun parseCommandBytes(contents: ByteArray): DecodeResult = when (val parsed = parseCommands(contents)) {
        is CommandParse.Success -> DecodeResult.Success(parsed.commands)
        CommandParse.Truncated -> DecodeResult.Failure(FailureReason.TRUNCATED_COMMAND)
    }

    fun encodeStandard(contents: ByteArray): ByteArray? {
        val stuffed = ArrayList<Byte>(contents.size + 4)
        stuffed += STANDARD_START.toByte()
        (contents + xor(contents).toByte()).forEach { byte ->
            when (byte.u8()) {
                EXTENDED_START -> { stuffed += STUFF.toByte(); stuffed += 0.toByte() }
                STANDARD_START -> { stuffed += STUFF.toByte(); stuffed += 1.toByte() }
                STOP -> { stuffed += STUFF.toByte(); stuffed += 2.toByte() }
                STUFF -> { stuffed += STUFF.toByte(); stuffed += 3.toByte() }
                else -> stuffed += byte
            }
        }
        stuffed += STOP.toByte()
        return stuffed.toByteArray().takeIf { it.size <= MAX_WIRE_FRAME_SIZE }
    }

    private sealed interface CommandParse {
        data class Success(val commands: List<Command>) : CommandParse
        object Truncated : CommandParse
    }

    private fun parseCommands(contents: ByteArray): CommandParse {
        val commands = mutableListOf<Command>()
        var index = 0
        while (index < contents.size) {
            val identifier = contents[index++].u8()
            if (identifier >= 0x80) {
                commands += Command(identifier, byteArrayOf())
                continue
            }
            if (index >= contents.size) return CommandParse.Truncated
            val length = contents[index++].u8()
            if (length > contents.size - index) return CommandParse.Truncated
            commands += Command(identifier, contents.copyOfRange(index, index + length))
            index += length
        }
        return CommandParse.Success(commands)
    }

    private fun xor(bytes: ByteArray): Int = bytes.fold(0) { result, byte -> result xor byte.u8() }
    private fun Byte.u8(): Int = toInt() and 0xFF

    private const val EXTENDED_START = 0xF0
    private const val STANDARD_START = 0xF1
    private const val STOP = 0xF2
    private const val STUFF = 0xF3
}

/**
 * Per-link C2a transport and captured MyWhoosh handshake state.
 *
 * This has no reference to BtEngine or any physical-control path. C3 will replace the handshake
 * fields with device-global virtual PM state; the autonomous measurement engine remains unchanged.
 */
class Concept2CsafeLink {
    data class HandshakeState(
        val workoutType: Int? = null,
        val screenType: Int? = null,
        val screenValue: Int? = null,
    )

    sealed interface RequestResult {
        data class Accepted(val response: ByteArray) : RequestResult
        data class Rejected(val reason: RejectionReason) : RequestResult
    }

    enum class RejectionReason { MALFORMED, UNSUPPORTED, TOO_SOON }

    private var responseBytes: ByteArray? = null
    private var toggle = false
    private var lastAcceptedAtMs: Long? = null

    var handshakeState = HandshakeState()
        private set

    @get:Synchronized
    val response: ByteArray?
        get() = responseBytes?.copyOf()

    @Synchronized
    fun accept(wire: ByteArray, nowMs: Long): RequestResult {
        val previous = lastAcceptedAtMs
        if (previous != null && nowMs - previous < MIN_INTER_FRAME_MS) {
            return RequestResult.Rejected(RejectionReason.TOO_SOON)
        }
        val decoded = Concept2CsafeCodec.decodeStandard(wire)
        val commands = when (decoded) {
            is Concept2CsafeCodec.DecodeResult.Success -> decoded.commands
            is Concept2CsafeCodec.DecodeResult.Failure -> {
                val reason = if (decoded.reason == Concept2CsafeCodec.FailureReason.EXTENDED_FRAME_UNSUPPORTED) {
                    RejectionReason.UNSUPPORTED
                } else {
                    RejectionReason.MALFORMED
                }
                return RequestResult.Rejected(reason)
            }
        }
        if (commands.isEmpty()) return RequestResult.Rejected(RejectionReason.UNSUPPORTED)

        var nextState = handshakeState
        val responseContents = mutableListOf<Byte>()
        responseContents += (if (toggle) STATUS_READY_WITH_TOGGLE else STATUS_READY).toByte()
        commands.forEach { outer ->
            if (outer.identifier != PM_CONFIGURATION_WRAPPER) {
                return RequestResult.Rejected(RejectionReason.UNSUPPORTED)
            }
            val nested = when (val nestedResult = Concept2CsafeCodec.parseCommandBytes(outer.data)) {
                is Concept2CsafeCodec.DecodeResult.Success -> nestedResult.commands
                is Concept2CsafeCodec.DecodeResult.Failure ->
                    return RequestResult.Rejected(RejectionReason.MALFORMED)
            }
            if (nested.isEmpty() || nested.size > 0xFF) {
                return RequestResult.Rejected(RejectionReason.UNSUPPORTED)
            }
            nested.forEach { command ->
                nextState = applyCapturedCommand(nextState, command)
                    ?: return RequestResult.Rejected(RejectionReason.UNSUPPORTED)
            }
            responseContents += PM_CONFIGURATION_WRAPPER.toByte()
            responseContents += nested.size.toByte()
            nested.forEach { responseContents += it.identifier.toByte() }
        }

        val encoded = Concept2CsafeCodec.encodeStandard(responseContents.toByteArray())
            ?: return RequestResult.Rejected(RejectionReason.MALFORMED)
        handshakeState = nextState
        responseBytes = encoded
        lastAcceptedAtMs = nowMs
        toggle = !toggle
        return RequestResult.Accepted(encoded.copyOf())
    }

    @Synchronized
    fun reset() {
        responseBytes = null
        toggle = false
        lastAcceptedAtMs = null
        handshakeState = HandshakeState()
    }

    private fun applyCapturedCommand(
        state: HandshakeState,
        command: Concept2CsafeCodec.Command,
    ): HandshakeState? = when (command.identifier) {
        SET_WORKOUT_TYPE -> {
            if (!command.data.contentEquals(byteArrayOf(WORKOUT_TYPE_JUST_ROW_SPLITS.toByte()))) null
            else state.copy(workoutType = WORKOUT_TYPE_JUST_ROW_SPLITS)
        }
        SET_SCREEN_STATE -> {
            if (command.data.size != 2 || command.data[0].u8() != SCREEN_TYPE_WORKOUT) return null
            val value = command.data[1].u8()
            if (value !in setOf(SCREEN_PREPARE_TO_ROW, SCREEN_TERMINATE_WORKOUT)) null
            else state.copy(screenType = SCREEN_TYPE_WORKOUT, screenValue = value)
        }
        else -> null
    }

    private fun Byte.u8(): Int = toInt() and 0xFF

    companion object {
        const val MIN_INTER_FRAME_MS = 50L
        private const val STATUS_READY = 0x01
        private const val STATUS_READY_WITH_TOGGLE = 0x81
        private const val PM_CONFIGURATION_WRAPPER = 0x76
        private const val SET_WORKOUT_TYPE = 0x01
        private const val SET_SCREEN_STATE = 0x13
        private const val WORKOUT_TYPE_JUST_ROW_SPLITS = 0x01
        private const val SCREEN_TYPE_WORKOUT = 0x01
        private const val SCREEN_PREPARE_TO_ROW = 0x01
        private const val SCREEN_TERMINATE_WORKOUT = 0x02
    }
}
