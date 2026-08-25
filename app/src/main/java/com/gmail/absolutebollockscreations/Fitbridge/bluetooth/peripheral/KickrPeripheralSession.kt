package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.KickrFtmsControlProcessor
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.KickrSyntheticModel
import java.util.ArrayDeque
import java.util.UUID

internal enum class KickrControlRoute { FTMS, LEGACY_WAHOO, ZWIFT_PROPRIETARY }

internal data class KickrTxFrame(
    val characteristicUuid: UUID,
    val value: ByteArray,
    val indicate: Boolean,
)

/** Android-free per-link generation state used by the GATT callback and exhaustive tests. */
internal class KickrPeripheralSession(
    val generation: Long,
    val model: KickrSyntheticModel,
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
) {
    private val subscriptions = mutableMapOf<UUID, PeripheralConnection.CccdMode>()
    private val queue = ArrayDeque<KickrTxFrame>()
    val ftms = KickrFtmsControlProcessor(model)

    var route: KickrControlRoute? = null
        private set
    var inFlight: KickrTxFrame? = null
        private set
    var droppedFrames: Long = 0
        private set

    init {
        require(generation > 0)
        require(queueCapacity > 0)
    }

    fun subscribe(characteristic: UUID, mode: PeripheralConnection.CccdMode) {
        if (mode == PeripheralConnection.CccdMode.DISABLED) subscriptions.remove(characteristic)
        else subscriptions[characteristic] = mode
    }

    fun subscription(characteristic: UUID): PeripheralConnection.CccdMode =
        subscriptions[characteristic] ?: PeripheralConnection.CccdMode.DISABLED

    fun acquire(candidate: KickrControlRoute): Boolean {
        val current = route
        if (current == null) route = candidate
        return current == null || current == candidate
    }

    fun enqueue(frame: KickrTxFrame) {
        if (queue.size >= queueCapacity) {
            queue.removeFirst()
            droppedFrames++
        }
        queue.addLast(frame.copy(value = frame.value.copyOf()))
    }

    fun beginNext(): KickrTxFrame? {
        if (inFlight != null) return null
        return queue.pollFirst()?.also { inFlight = it }
    }

    fun completeInFlight(): KickrTxFrame? {
        val completed = inFlight
        inFlight = null
        return completed
    }

    fun clear() {
        subscriptions.clear()
        queue.clear()
        inFlight = null
        route = null
        ftms.disconnect()
    }

    val pendingCount: Int get() = queue.size

    companion object { const val DEFAULT_QUEUE_CAPACITY = 16 }
}
