package com.gmail.absolutebollockscreations.gattlibrary.simulator

import java.util.TreeMap
import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.LifeFitnessUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids

/** Pure, per-link server-side mirror of the Phase 6 Life Fitness setup transcript. */
internal class LifeFitnessSetupServer {
    enum class Stage { CONNECTED, SESSION_OPEN, PROFILE_RECEIVING, MAGIC_ACCEPTED, UNLOCKED }

    data class Result(
        val accepted: Boolean,
        val statusNotification: ByteArray? = null,
        val stage: Stage,
    )

    private val requiredProfiles = listOf(
        SigUuids.UUID_USER_FIRST_NAME,
        SigUuids.UUID_USER_LAST_NAME,
        SigUuids.UUID_USER_EMAIL,
        SigUuids.UUID_USER_AGE,
        SigUuids.UUID_USER_GENDER,
        SigUuids.UUID_USER_LANGUAGE,
        LifeFitnessUuids.UUID_LF_VENDOR_TOKEN_1,
        LifeFitnessUuids.UUID_LF_VENDOR_TOKEN_2,
        SigUuids.UUID_USER_WEIGHT,
        SigUuids.UUID_USER_HEIGHT,
    )
    private val received = LinkedHashSet<UUID>()
    @Volatile
    var stage: Stage = Stage.CONNECTED
        private set

    @Synchronized
    fun write(characteristic: UUID, value: ByteArray): Result {
        if (characteristic == LifeFitnessUuids.UUID_LF_CONTROL_1) return control1(value)
        if (characteristic == LifeFitnessUuids.UUID_LF_CONTROL_2) return control2(value)
        if (characteristic !in requiredProfiles || stage !in setOf(Stage.SESSION_OPEN, Stage.PROFILE_RECEIVING)) {
            return reject()
        }
        if (!validProfile(characteristic, value)) return reject()
        val expected = requiredProfiles.getOrNull(received.size)
        if (characteristic != expected || !received.add(characteristic)) return reject()
        stage = Stage.PROFILE_RECEIVING
        return accept()
    }

    private fun control1(value: ByteArray): Result = when {
        stage == Stage.CONNECTED && value.contentEquals(byteArrayOf(0x01)) -> {
            stage = Stage.SESSION_OPEN
            accept(byteArrayOf(0x07))
        }
        stage == Stage.MAGIC_ACCEPTED && value.contentEquals(byteArrayOf(0x02)) -> {
            stage = Stage.UNLOCKED
            accept(byteArrayOf(0x04, 0x01))
        }
        else -> reject()
    }

    private fun control2(value: ByteArray): Result {
        val allProfiles = received.size == requiredProfiles.size
        val valid = value.contentEquals(byteArrayOf(0x00, 0x00, 0x00, 0x01, 0xB8.toByte(), 0x5B, 0x5D))
        if (stage != Stage.PROFILE_RECEIVING || !allProfiles || !valid) return reject()
        stage = Stage.MAGIC_ACCEPTED
        return accept()
    }

    private fun validProfile(uuid: UUID, value: ByteArray): Boolean = when (uuid) {
        LifeFitnessUuids.UUID_LF_VENDOR_TOKEN_1 -> validVendorToken(value)
        LifeFitnessUuids.UUID_LF_VENDOR_TOKEN_2 -> value.size == 1 && value[0].toInt() in 0..1
        SigUuids.UUID_USER_AGE -> value.size <= 2
        SigUuids.UUID_USER_GENDER -> value.isEmpty() || value.size == 1
        SigUuids.UUID_USER_WEIGHT,
        SigUuids.UUID_USER_HEIGHT,
        -> value.isEmpty() || value.size == 2
        SigUuids.UUID_USER_LANGUAGE -> value.isEmpty() || value.size in 1..8
        else -> value.size <= MAX_PROFILE_BYTES
    }

    private fun validVendorToken(value: ByteArray): Boolean {
        return value.size in SUPPORTED_VENDOR_TOKEN_BYTES && value.all(::isAsciiHex)
    }

    private fun isAsciiHex(value: Byte): Boolean = when (value.toInt() and 0xFF) {
        in '0'.code..'9'.code,
        in 'a'.code..'f'.code,
        in 'A'.code..'F'.code,
        -> true
        else -> false
    }

    private fun accept(status: ByteArray? = null) = Result(true, status, stage)
    private fun reject() = Result(false, stage = stage)

    @Synchronized
    fun clearSensitiveState() {
        received.clear()
        stage = Stage.CONNECTED
    }

    companion object {
        private const val MAX_PROFILE_BYTES = 256
        private val SUPPORTED_VENDOR_TOKEN_BYTES = setOf(20, 32)
    }
}

/**
 * Serializes one client's writes by the ATT transaction IDs assigned by Android's GATT server.
 * Non-write requests are recorded as completed barriers so a read or descriptor operation cannot
 * leave a legitimate gap between two writes. The first write establishes the sequence baseline.
 */
internal class LifeFitnessRequestSequencer<T>(
    private val process: (T) -> Unit,
) {
    private sealed interface Entry<out T> {
        data class Write<T>(val value: T) : Entry<T>
        data object CompletedRequest : Entry<Nothing>
    }

    private val lock = Any()
    private val pending = TreeMap<Int, Entry<T>>()
    private var lastProcessedRequestId: Int? = null
    private var processing = false
    private var active = true

    fun enqueueWrite(requestId: Int, value: T) {
        synchronized(lock) {
            if (!active || pending.containsKey(requestId)) return
            if (lastProcessedRequestId == null) {
                lastProcessedRequestId = requestId - 1
            }
            if (requestId <= lastProcessedRequestId!!) return
            pending[requestId] = Entry.Write(value)
        }
        drain()
    }

    fun observeCompletedRequest(requestId: Int) {
        synchronized(lock) {
            val last = lastProcessedRequestId ?: return
            if (!active || requestId <= last || pending.containsKey(requestId)) return
            pending[requestId] = Entry.CompletedRequest
        }
        drain()
    }

    fun clear() {
        synchronized(lock) {
            active = false
            pending.clear()
        }
    }

    private fun drain() {
        synchronized(lock) {
            if (!active || processing) return
            processing = true
        }
        try {
            while (true) {
                val entry = synchronized(lock) {
                    if (!active) return
                    val nextId = (lastProcessedRequestId ?: return) + 1
                    val next = pending.remove(nextId) ?: return
                    lastProcessedRequestId = nextId
                    next
                }
                if (entry is Entry.Write) process(entry.value)
            }
        } finally {
            val retry = synchronized(lock) {
                processing = false
                if (!active) {
                    false
                } else {
                    val nextId = (lastProcessedRequestId ?: return@synchronized false) + 1
                    pending.containsKey(nextId)
                }
            }
            if (retry) drain()
        }
    }
}

/** Delivery rules kept separate from Android callbacks so setup replies and stream gates stay testable. */
internal object LifeFitnessNotificationPolicy {
    fun setupStatusReply(result: LifeFitnessSetupServer.Result): ByteArray? =
        if (result.accepted) result.statusNotification?.copyOf() else null

    fun mayStreamMeasurement(
        stage: LifeFitnessSetupServer.Stage,
        measurementUuid: UUID,
        subscriptions: Set<UUID>,
    ): Boolean = stage == LifeFitnessSetupServer.Stage.UNLOCKED && measurementUuid in subscriptions
}
