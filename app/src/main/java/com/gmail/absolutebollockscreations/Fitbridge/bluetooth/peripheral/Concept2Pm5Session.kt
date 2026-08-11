package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import java.util.UUID

/** Pure per-link PM5 state: subscriptions, rate and deterministic mux position. */
class Concept2Pm5Session {
    private val subscriptions = mutableSetOf<UUID>()
    private var heartRateInputBytes: ByteArray? = null
    var sampleRate: Int = DEFAULT_SAMPLE_RATE
        private set
    var muxPosition: Int = 0
        private set

    val periodMs: Long get() = PERIODS_MS.getValue(sampleRate)

    fun setSampleRate(value: Int): Boolean {
        if (value !in PERIODS_MS) return false
        sampleRate = value
        return true
    }

    fun setSubscribed(uuid: UUID, enabled: Boolean) {
        if (enabled) subscriptions += uuid else subscriptions -= uuid
    }

    fun isSubscribed(uuid: UUID): Boolean = uuid in subscriptions

    val hasSubscriptions: Boolean get() = subscriptions.isNotEmpty()

    val heartRateInput: ByteArray?
        get() = heartRateInputBytes?.copyOf()

    /** C1 retains a valid client-to-PM HR packet; C5 will apply it to virtual monitor state. */
    fun setHeartRateInput(value: ByteArray): Boolean {
        if (value.size != HEART_RATE_INPUT_SIZE) return false
        val type = value[0].toInt() and 0xFF
        if (type !in 0..1) return false
        heartRateInputBytes = value.copyOf()
        return true
    }

    fun nextMuxId(enabledIds: List<Int>): Int? {
        if (!isSubscribed(BluetoothIds.UUID_C2_MULTIPLEXED_INFORMATION)) return null
        val eligible = enabledIds.filter { id ->
            val individual = uuidForId(id)
            individual == null || !isSubscribed(individual)
        }
        if (eligible.isEmpty()) return null
        val result = eligible[muxPosition % eligible.size]
        muxPosition = (muxPosition + 1) % eligible.size
        return result
    }

    fun reset() {
        subscriptions.clear(); sampleRate = DEFAULT_SAMPLE_RATE; muxPosition = 0
        heartRateInputBytes = null
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE = 1
        val PERIODS_MS = mapOf(0 to 1000L, 1 to 500L, 2 to 250L, 3 to 100L)

        fun uuidForId(id: Int): UUID? = when (id) {
            0x31 -> BluetoothIds.UUID_C2_GENERAL_STATUS
            0x32 -> BluetoothIds.UUID_C2_ADDITIONAL_STATUS_1
            0x33 -> BluetoothIds.UUID_C2_ADDITIONAL_STATUS_2
            0x35 -> BluetoothIds.UUID_C2_STROKE_DATA
            0x36 -> BluetoothIds.UUID_C2_ADDITIONAL_STROKE_DATA
            0x37 -> BluetoothIds.UUID_C2_SPLIT_INTERVAL_DATA
            0x38 -> BluetoothIds.UUID_C2_ADDITIONAL_SPLIT_INTERVAL
            0x39 -> BluetoothIds.UUID_C2_WORKOUT_SUMMARY
            0x3A -> BluetoothIds.UUID_C2_ADDITIONAL_WORKOUT_SUMMARY
            0x3B -> BluetoothIds.UUID_C2_HEART_RATE_BELT_INFO
            0x3C -> BluetoothIds.UUID_C2_ADDITIONAL_WORKOUT_SUMMARY_2
            0x3E -> BluetoothIds.UUID_C2_ADDITIONAL_STATUS_3
            0x3F -> BluetoothIds.UUID_C2_LOGGED_WORKOUT
            else -> null
        }

        private const val HEART_RATE_INPUT_SIZE = 20
    }
}
