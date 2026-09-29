package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.peripheral.u8


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
        val workoutDurationType: Int? = null,
        val workoutDuration: Int? = null,
        val splitDurationType: Int? = null,
        val splitDuration: Int? = null,
        val restDurationSeconds: Int? = null,
        val intervalType: Int? = null,
        val programmingEnabled: Boolean = false,
        val targetStrokeRate: Int? = null,
        val targetHeartRateBpm: Int? = null,
        val targetPaceCentiseconds: Int? = null,
        val targetPowerWatts: Int? = null,
        val targetCaloriesPerHour: Int? = null,
        val selectedIntervalIndex: Int? = null,
        val configuredIntervals: Map<Int, Concept2WorkoutInterval> = emptyMap(),
        val workoutProgram: Concept2WorkoutProgram? = null,
    )

    sealed interface RequestResult {
        data class Accepted(val response: ByteArray) : RequestResult
        data class Rejected(val reason: RejectionReason) : RequestResult
    }

    enum class RejectionReason { MALFORMED, UNSUPPORTED, TOO_SOON }

    private var responseBytes: ByteArray? = null
    private var toggle = false
    private var lastAcceptedAtMs: Long? = null
    private var workoutTerminationPending = false

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
        val transportPrefix = wire.firstOrNull()?.u8() == ERGDATA_BLE_ENVELOPE &&
            wire.getOrNull(1)?.u8() == STANDARD_START
        val frame = if (transportPrefix) wire.copyOfRange(1, wire.size) else wire
        val decoded = Concept2CsafeCodec.decodeStandard(frame)
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
            if (outer.identifier == SET_TARGET_HEART_RATE) {
                if (outer.data.size != 1) return RequestResult.Rejected(RejectionReason.MALFORMED)
                val target = outer.data[0].u8()
                if (target !in 1..254) return RequestResult.Rejected(RejectionReason.UNSUPPORTED)
                nextState = nextState.copy(targetHeartRateBpm = target)
                return@forEach
            }
            if (outer.identifier !in setOf(PM_CONFIGURATION_WRAPPER, PM_DATA_WRAPPER)) {
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
                nextState = applyCommand(nextState, outer.identifier, command)
                    ?: return RequestResult.Rejected(RejectionReason.UNSUPPORTED)
            }
            responseContents += outer.identifier.toByte()
            responseContents += nested.size.toByte()
            nested.forEach { responseContents += it.identifier.toByte() }
        }

        nextState = nextState.copy(workoutProgram = buildProgram(nextState))
        if (nextState.screenType == SCREEN_TYPE_WORKOUT &&
            nextState.screenValue == SCREEN_TERMINATE_WORKOUT
        ) {
            responseContents[0] = (if (toggle) STATUS_WORKOUT_END_WITH_TOGGLE else STATUS_WORKOUT_END).toByte()
        }
        val encodedFrame = Concept2CsafeCodec.encodeStandard(responseContents.toByteArray())
            ?: return RequestResult.Rejected(RejectionReason.MALFORMED)
        // The leading 0x01 belongs to ErgData's request-side BLE envelope. Physical PM5 response
        // notifications begin directly with the CSAFE F1 delimiter and never echo that envelope.
        val encoded = encodedFrame
        if (nextState.screenType == SCREEN_TYPE_WORKOUT &&
            nextState.screenValue == SCREEN_TERMINATE_WORKOUT &&
            (handshakeState.screenType != SCREEN_TYPE_WORKOUT ||
                handshakeState.screenValue != SCREEN_TERMINATE_WORKOUT)
        ) {
            workoutTerminationPending = true
        }
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
        workoutTerminationPending = false
        handshakeState = HandshakeState()
    }

    /** Consume the accepted Workout/Terminate Workout command exactly once for this link. */
    @Synchronized
    fun consumeWorkoutTerminationRequest(): Boolean {
        val pending = workoutTerminationPending
        workoutTerminationPending = false
        return pending
    }

    private fun applyCommand(
        state: HandshakeState,
        wrapper: Int,
        command: Concept2CsafeCodec.Command,
    ): HandshakeState? {
        if (wrapper == PM_DATA_WRAPPER) {
            return when (command.identifier) {
                SET_GAME_PARAMS -> {
                    if (command.data.size != GAME_PARAMS_SIZE) null
                    else command.data[GAME_PARAMS_TARGET_STROKE_RATE].u8().let { target ->
                        state.copy(targetStrokeRate = target.takeIf { it > 0 })
                    }
                }
                else -> null
            }
        }
        return when (command.identifier) {
        SET_WORKOUT_TYPE -> {
            val type = command.data.singleOrNull()?.u8() ?: return null
            if (type !in SUPPORTED_WORKOUT_TYPES) null
            else if (type == state.workoutType) state
            else if (type in VARIABLE_WORKOUT_TYPES && state.workoutType in VARIABLE_WORKOUT_TYPES) {
                state.copy(workoutType = type)
            }
            else if (type in VARIABLE_WORKOUT_TYPES && state.selectedIntervalIndex != null) {
                // The PM5 examples select interval zero before declaring type 8. Keep that active
                // draft intact; clearing the selector here makes the later Configure unreachable.
                state.copy(workoutType = type, configuredIntervals = emptyMap(), workoutProgram = null)
            }
            else state.copy(
                workoutType = type,
                workoutDurationType = null,
                workoutDuration = null,
                splitDurationType = null,
                splitDuration = null,
                restDurationSeconds = null,
                intervalType = null,
                programmingEnabled = false,
                selectedIntervalIndex = null,
                configuredIntervals = emptyMap(),
                workoutProgram = null,
            )
        }
        SET_WORKOUT_DURATION -> {
            if (command.data.size != 5) return null
            val durationType = command.data[0].u8()
            val duration = u32be(command.data, 1) ?: return null
            if (durationType !in SUPPORTED_DURATION_TYPES || duration == 0 ||
                state.splitDurationType?.let { it != durationType } == true
            ) null
            else state.copy(workoutDurationType = durationType, workoutDuration = duration)
        }
        SET_SPLIT_DURATION -> {
            if (command.data.size != 5) return null
            val durationType = command.data[0].u8()
            val duration = u32be(command.data, 1) ?: return null
            val undefinedVariableSentinel = state.workoutType == WORKOUT_TYPE_VARIABLE_UNDEFINED_REST &&
                duration == 0
            if (durationType !in SUPPORTED_DURATION_TYPES ||
                (!undefinedVariableSentinel && duration == 0) ||
                (!undefinedVariableSentinel && state.workoutDurationType?.let { it != durationType } == true)
            ) null
            else state.copy(splitDurationType = durationType, splitDuration = duration)
        }
        SET_REST_DURATION -> {
            if (command.data.size != 2) return null
            val seconds = (command.data[0].u8() shl 8) or command.data[1].u8()
            state.copy(restDurationSeconds = seconds)
        }
        SET_INTERVAL_TYPE -> {
            val type = command.data.singleOrNull()?.u8() ?: return null
            if (type !in 0..9) null else state.copy(intervalType = type)
        }
        CONFIGURE_WORKOUT -> {
            val enabled = command.data.singleOrNull()?.u8() ?: return null
            if (enabled !in 0..1) null
            else if (enabled == 1 && state.workoutType in VARIABLE_WORKOUT_TYPES) {
                val index = state.selectedIntervalIndex ?: return null
                val interval = buildVariableInterval(state) ?: return null
                state.copy(
                    programmingEnabled = true,
                    configuredIntervals = state.configuredIntervals + (index to interval),
                )
            } else state.copy(programmingEnabled = enabled == 1)
        }
        SET_TARGET_PACE -> {
            if (command.data.size != 4) null
            else u32be(command.data, 0)?.let { state.copy(targetPaceCentiseconds = it.takeIf { value -> value > 0 }) }
        }
        SET_TARGET_AVG_WATTS -> {
            if (command.data.size != 2) null
            else u16be(command.data).let { state.copy(targetPowerWatts = it.takeIf { value -> value > 0 }) }
        }
        SET_TARGET_CALS_PER_HOUR -> {
            if (command.data.size != 2) null
            else u16be(command.data).let { state.copy(targetCaloriesPerHour = it.takeIf { value -> value > 0 }) }
        }
        SET_WORKOUT_INTERVAL_COUNT -> {
            val index = command.data.singleOrNull()?.u8() ?: return null
            if (index !in 0 until MAX_VARIABLE_INTERVALS) null else state.copy(
                selectedIntervalIndex = index,
                workoutDurationType = null,
                workoutDuration = null,
                splitDurationType = null,
                splitDuration = null,
                restDurationSeconds = null,
                intervalType = null,
                targetPaceCentiseconds = null,
                targetPowerWatts = null,
                targetCaloriesPerHour = null,
                targetStrokeRate = null,
                targetHeartRateBpm = null,
            )
        }
        SET_SCREEN_STATE -> {
            if (command.data.size != 2 || command.data[0].u8() != SCREEN_TYPE_WORKOUT) return null
            val value = command.data[1].u8()
            if (value !in setOf(SCREEN_PREPARE_TO_ROW, SCREEN_TERMINATE_WORKOUT)) null
            else state.copy(screenType = SCREEN_TYPE_WORKOUT, screenValue = value)
        }
        else -> null
        }
    }

    private fun buildProgram(state: HandshakeState): Concept2WorkoutProgram? {
        if (state.screenValue == SCREEN_TERMINATE_WORKOUT) return null
        val type = state.workoutType ?: return null
        if (type == WORKOUT_TYPE_JUST_ROW_SPLITS) return null
        if (!state.programmingEnabled || state.screenValue != SCREEN_PREPARE_TO_ROW) return state.workoutProgram
        if (type in VARIABLE_WORKOUT_TYPES) {
            val entries = state.configuredIntervals.toSortedMap()
            if (entries.isEmpty() || entries.keys.toList() != (0 until entries.size).toList()) return null
            val intervals = entries.values.toList()
            val first = intervals.first()
            return Concept2WorkoutProgram(
                workoutType = type,
                goalKind = first.goalKind,
                goalValue = first.goalValue,
                restSeconds = first.restSeconds,
                targetPaceCentiseconds = first.targetPaceCentiseconds,
                targetPowerWatts = first.targetPowerWatts,
                targetCaloriesPerHour = first.targetCaloriesPerHour,
                targetStrokeRate = first.targetStrokeRate,
                targetHeartRateBpm = first.targetHeartRateBpm,
                intervals = intervals,
            )
        }
        val kind = goalKind(state.workoutDurationType) ?: return null
        if (!workoutTypeMatches(type, kind)) return null
        val duration = state.workoutDuration ?: return null
        if (type in INTERVAL_WORKOUT_TYPES && state.restDurationSeconds == null) return null
        return Concept2WorkoutProgram(
            workoutType = type,
            goalKind = kind,
            goalValue = duration,
            restSeconds = state.restDurationSeconds,
            targetPaceCentiseconds = state.targetPaceCentiseconds,
            targetPowerWatts = state.targetPowerWatts,
            targetCaloriesPerHour = state.targetCaloriesPerHour,
            targetStrokeRate = state.targetStrokeRate,
            targetHeartRateBpm = state.targetHeartRateBpm,
            splitGoalKind = goalKind(state.splitDurationType),
            splitGoalValue = state.splitDuration,
        )
    }

    private fun buildVariableInterval(state: HandshakeState): Concept2WorkoutInterval? {
        val intervalType = state.intervalType ?: return null
        val kind = when (intervalType) {
            0, 3 -> Concept2WorkoutGoalKind.TIME
            1, 4 -> Concept2WorkoutGoalKind.DISTANCE
            6, 7 -> Concept2WorkoutGoalKind.CALORIES
            else -> return null
        }
        val expectedDurationType = when (kind) {
            Concept2WorkoutGoalKind.TIME -> DURATION_TIME
            Concept2WorkoutGoalKind.DISTANCE -> DURATION_DISTANCE
            Concept2WorkoutGoalKind.CALORIES -> DURATION_CALORIES
        }
        if (state.workoutDurationType != expectedDurationType) return null
        val duration = state.workoutDuration ?: return null
        val undefinedRest = intervalType in setOf(3, 4, 7) || state.workoutType == 9
        if (!undefinedRest && state.restDurationSeconds == null) return null
        return Concept2WorkoutInterval(
            goalKind = kind,
            goalValue = duration,
            restSeconds = if (undefinedRest) null else state.restDurationSeconds,
            targetPaceCentiseconds = state.targetPaceCentiseconds,
            targetPowerWatts = state.targetPowerWatts,
            targetCaloriesPerHour = state.targetCaloriesPerHour,
            targetStrokeRate = state.targetStrokeRate,
            targetHeartRateBpm = state.targetHeartRateBpm,
        )
    }

    private fun workoutTypeMatches(type: Int, kind: Concept2WorkoutGoalKind): Boolean = when (kind) {
        Concept2WorkoutGoalKind.TIME -> type in setOf(4, 5, 6)
        Concept2WorkoutGoalKind.DISTANCE -> type in setOf(2, 3, 7)
        Concept2WorkoutGoalKind.CALORIES -> type in setOf(10, 12)
    }

    private fun goalKind(durationType: Int?): Concept2WorkoutGoalKind? = when (durationType) {
        DURATION_TIME -> Concept2WorkoutGoalKind.TIME
        DURATION_DISTANCE -> Concept2WorkoutGoalKind.DISTANCE
        DURATION_CALORIES -> Concept2WorkoutGoalKind.CALORIES
        else -> null
    }

    private fun u32be(bytes: ByteArray, offset: Int): Int? {
        var value = 0L
        repeat(4) { value = (value shl 8) or bytes[offset + it].u8().toLong() }
        return value.takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    private fun u16be(bytes: ByteArray): Int = (bytes[0].u8() shl 8) or bytes[1].u8()

    private fun Byte.u8(): Int = toInt() and 0xFF

    companion object {
        const val MIN_INTER_FRAME_MS = 50L
        private const val STATUS_READY = 0x01
        private const val STATUS_READY_WITH_TOGGLE = 0x81
        private const val STATUS_WORKOUT_END = 0x09
        private const val STATUS_WORKOUT_END_WITH_TOGGLE = 0x89
        private const val PM_CONFIGURATION_WRAPPER = 0x76
        private const val PM_DATA_WRAPPER = 0x77
        private const val SET_WORKOUT_TYPE = 0x01
        private const val SET_WORKOUT_DURATION = 0x03
        private const val SET_REST_DURATION = 0x04
        private const val SET_SPLIT_DURATION = 0x05
        private const val SET_TARGET_PACE = 0x06
        private const val SET_SCREEN_STATE = 0x13
        private const val CONFIGURE_WORKOUT = 0x14
        private const val SET_TARGET_AVG_WATTS = 0x15
        private const val SET_TARGET_CALS_PER_HOUR = 0x16
        private const val SET_INTERVAL_TYPE = 0x17
        private const val SET_WORKOUT_INTERVAL_COUNT = 0x18
        private const val SET_GAME_PARAMS = 0x38
        private const val SET_TARGET_HEART_RATE = 0x30
        private const val WORKOUT_TYPE_JUST_ROW_SPLITS = 0x01
        private const val WORKOUT_TYPE_VARIABLE_UNDEFINED_REST = 0x09
        private const val SCREEN_TYPE_WORKOUT = 0x01
        private const val SCREEN_PREPARE_TO_ROW = 0x01
        private const val SCREEN_TERMINATE_WORKOUT = 0x02
        private const val ERGDATA_BLE_ENVELOPE = 0x01
        private const val STANDARD_START = 0xF1
        private const val DURATION_TIME = 0x00
        private const val DURATION_CALORIES = 0x40
        private const val DURATION_DISTANCE = 0x80
        private const val GAME_PARAMS_SIZE = 22
        private const val GAME_PARAMS_TARGET_STROKE_RATE = 21
        private val SUPPORTED_DURATION_TYPES = setOf(DURATION_TIME, DURATION_CALORIES, DURATION_DISTANCE)
        private val INTERVAL_WORKOUT_TYPES = setOf(6, 7, 12)
        private const val MAX_VARIABLE_INTERVALS = 50
        private val VARIABLE_WORKOUT_TYPES = setOf(8, 9)
        private val SUPPORTED_WORKOUT_TYPES = setOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12)
    }
}
