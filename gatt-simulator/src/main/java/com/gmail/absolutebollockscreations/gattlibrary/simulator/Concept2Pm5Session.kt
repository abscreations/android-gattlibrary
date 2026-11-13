package com.gmail.absolutebollockscreations.gattlibrary.simulator

import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.Concept2Uuids

/** Pure per-link PM5 state: subscriptions, rate and deterministic mux position. */
@SimulatorProbeApi
public class Concept2Pm5Session {
    private val subscriptions = mutableSetOf<UUID>()
    private val csafe = Concept2CsafeLink()
    private var heartRateInputBytes: ByteArray? = null
    private var lastAdditionalStatus3Bytes: ByteArray? = null
    public var sampleRate: Int = DEFAULT_SAMPLE_RATE
        private set
    public var muxPosition: Int = 0
        private set

    public val periodMs: Long get() = PERIODS_MS.getValue(sampleRate)

    public val csafeResponse: ByteArray? get() = csafe.response
    public val csafeHandshakeState: Concept2CsafeLink.HandshakeState get() = csafe.handshakeState
    public val csafeWorkoutProgram: Concept2WorkoutProgram? get() = csafe.handshakeState.workoutProgram

    public fun acceptCsafeRequest(value: ByteArray, nowMs: Long): Concept2CsafeLink.RequestResult =
        csafe.accept(value, nowMs)

    public fun notificationForAcceptedCsafe(result: Concept2CsafeLink.RequestResult): ByteArray? =
        (result as? Concept2CsafeLink.RequestResult.Accepted)?.response
            ?.takeIf { isSubscribed(Concept2Uuids.UUID_C2_CSAFE_RESPONSE) }

    public fun consumeWorkoutTerminationRequest(): Boolean = csafe.consumeWorkoutTerminationRequest()

    /** Clear workout configuration/response state without dropping this client's CCCDs. */
    public fun resetCsafeWorkoutState(): Unit = csafe.reset()

    public fun setSampleRate(value: Int): Boolean {
        if (value !in PERIODS_MS) return false
        sampleRate = value
        return true
    }

    @Synchronized
    public fun setSubscribed(uuid: UUID, enabled: Boolean) {
        if (enabled) subscriptions += uuid else subscriptions -= uuid
    }

    @Synchronized
    public fun isSubscribed(uuid: UUID): Boolean = uuid in subscriptions

    public val hasSubscriptions: Boolean
        @Synchronized get() = subscriptions.isNotEmpty()

    public val hasWorkoutMeasurementSubscription: Boolean
        @Synchronized get() = subscriptions.any(WORKOUT_MEASUREMENT_UUIDS::contains)

    public val heartRateInput: ByteArray?
        get() = heartRateInputBytes?.copyOf()

    /** C1 retains a valid client-to-PM HR packet; C5 will apply it to virtual monitor state. */
    public fun setHeartRateInput(value: ByteArray): Boolean {
        if (value.size != HEART_RATE_INPUT_SIZE) return false
        val type = value[0].toInt() and 0xFF
        if (type !in 0..1) return false
        heartRateInputBytes = value.copyOf()
        return true
    }

    @Synchronized
    public fun nextMuxId(enabledIds: List<Int>): Int? {
        if (!isSubscribed(Concept2Uuids.UUID_C2_MULTIPLEXED_INFORMATION)) return null
        val eligible = enabledIds.filter { id ->
            val individual = uuidForId(id)
            individual == null || !isSubscribed(individual)
        }
        if (eligible.isEmpty()) return null
        val result = eligible[muxPosition % eligible.size]
        muxPosition = (muxPosition + 1) % eligible.size
        return result
    }

    /** Per-link edge detector for the status stream; forced emissions represent its rate tick. */
    @Synchronized
    public fun shouldEmitAdditionalStatus3(value: ByteArray, force: Boolean = false): Boolean {
        val changed = lastAdditionalStatus3Bytes?.contentEquals(value) != true
        if (!force && !changed) return false
        lastAdditionalStatus3Bytes = value.copyOf()
        return true
    }

    @Synchronized
    public fun reset() {
        subscriptions.clear(); sampleRate = DEFAULT_SAMPLE_RATE; muxPosition = 0
        heartRateInputBytes = null
        lastAdditionalStatus3Bytes = null
        csafe.reset()
    }

    public companion object {
        public const val DEFAULT_SAMPLE_RATE: Int = 1
        public val PERIODS_MS: Map<Int, Long> = mapOf(0 to 1000L, 1 to 500L, 2 to 250L, 3 to 100L)

        private val WORKOUT_MEASUREMENT_UUIDS = setOf(
            Concept2Uuids.UUID_C2_MULTIPLEXED_INFORMATION,
            Concept2Uuids.UUID_C2_GENERAL_STATUS,
            Concept2Uuids.UUID_C2_ADDITIONAL_STATUS_1,
            Concept2Uuids.UUID_C2_ADDITIONAL_STATUS_2,
            Concept2Uuids.UUID_C2_STROKE_DATA,
            Concept2Uuids.UUID_C2_ADDITIONAL_STROKE_DATA,
            Concept2Uuids.UUID_C2_SPLIT_INTERVAL_DATA,
            Concept2Uuids.UUID_C2_ADDITIONAL_SPLIT_INTERVAL,
            Concept2Uuids.UUID_C2_FORCE_CURVE_DATA,
            FtmsUuids.UUID_ROWING_MACHINE_DATA,
        )

        public fun uuidForId(id: Int): UUID? = when (id) {
            0x31 -> Concept2Uuids.UUID_C2_GENERAL_STATUS
            0x32 -> Concept2Uuids.UUID_C2_ADDITIONAL_STATUS_1
            0x33 -> Concept2Uuids.UUID_C2_ADDITIONAL_STATUS_2
            0x35 -> Concept2Uuids.UUID_C2_STROKE_DATA
            0x36 -> Concept2Uuids.UUID_C2_ADDITIONAL_STROKE_DATA
            0x37 -> Concept2Uuids.UUID_C2_SPLIT_INTERVAL_DATA
            0x38 -> Concept2Uuids.UUID_C2_ADDITIONAL_SPLIT_INTERVAL
            0x39 -> Concept2Uuids.UUID_C2_WORKOUT_SUMMARY
            0x3A -> Concept2Uuids.UUID_C2_ADDITIONAL_WORKOUT_SUMMARY
            0x3B -> Concept2Uuids.UUID_C2_HEART_RATE_BELT_INFO
            0x3C -> Concept2Uuids.UUID_C2_ADDITIONAL_WORKOUT_SUMMARY_2
            0x3E -> Concept2Uuids.UUID_C2_ADDITIONAL_STATUS_3
            0x3F -> Concept2Uuids.UUID_C2_LOGGED_WORKOUT
            else -> null
        }

        private const val HEART_RATE_INPUT_SIZE = 20
    }
}
