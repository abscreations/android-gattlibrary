package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.KickrFtmsControlProcessor
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.KickrLegacyAckMode
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.KickrLegacyControlServer
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.KickrLegacyControlOutcome
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.KickrSyntheticModel
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
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
    legacyAckMode: KickrLegacyAckMode? = null,
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
) {
    private val subscriptions = mutableMapOf<UUID, PeripheralConnection.CccdMode>()
    private val queue = ArrayDeque<KickrTxFrame>()
    val ftms = KickrFtmsControlProcessor(model)
    val legacy = legacyAckMode?.let { KickrLegacyControlServer(model, it) }

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

    @Synchronized
    fun handleLegacyControl(value: ByteArray): KickrLegacyControlOutcome? {
        val server = legacy ?: return null
        if (subscription(BluetoothIds.UUID_WAHOO_KICKR_CONTROL) != PeripheralConnection.CccdMode.INDICATE ||
            route !in setOf(null, KickrControlRoute.LEGACY_WAHOO)
        ) return null
        val outcome = server.handle(value)
        if (!outcome.accepted || !acquire(KickrControlRoute.LEGACY_WAHOO)) return outcome
        outcome.response?.let { response ->
            enqueueCoalesced(KickrTxFrame(BluetoothIds.UUID_WAHOO_KICKR_CONTROL, response, true))
        }
        return outcome
    }

    @Synchronized
    fun handleFtmsControl(value: ByteArray): com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.FtmsControlPointHandler.Outcome? {
        if (subscription(BluetoothIds.UUID_FITNESS_MACHINE_CONTROL_POINT) != PeripheralConnection.CccdMode.INDICATE ||
            route !in setOf(null, KickrControlRoute.FTMS) ||
            !ftms.session.tryBeginProcedure()
        ) return null
        val outcome = ftms.handle(value)
        val requestControlSucceeded =
            value.firstOrNull()?.toInt() == 0x00 && outcome.response.getOrNull(2)?.toInt() == 0x01
        if (requestControlSucceeded && !acquire(KickrControlRoute.FTMS)) {
            ftms.session.finishProcedure()
            return null
        }
        enqueue(KickrTxFrame(BluetoothIds.UUID_FITNESS_MACHINE_CONTROL_POINT, outcome.response, true))
        outcome.status?.let { status ->
            if (subscription(BluetoothIds.UUID_FITNESS_MACHINE_STATUS) == PeripheralConnection.CccdMode.NOTIFY) {
                enqueue(KickrTxFrame(BluetoothIds.UUID_FITNESS_MACHINE_STATUS, status, false))
            }
        }
        if (outcome.trainingStatusChanged &&
            subscription(BluetoothIds.UUID_TRAINING_STATUS) == PeripheralConnection.CccdMode.NOTIFY
        ) {
            enqueue(KickrTxFrame(BluetoothIds.UUID_TRAINING_STATUS, ftms.session.trainingStatusPayload(), false))
        }
        return outcome
    }

    fun enqueue(frame: KickrTxFrame) {
        if (queue.size >= queueCapacity) {
            queue.removeFirst()
            droppedFrames++
        }
        queue.addLast(frame.copy(value = frame.value.copyOf()))
    }

    fun enqueueCoalesced(frame: KickrTxFrame) {
        queue.removeIf { pending ->
            pending.characteristicUuid == frame.characteristicUuid &&
                pending.value.getOrNull(1) == frame.value.getOrNull(1)
        }
        enqueue(frame)
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
        legacy?.reset()
    }

    val pendingCount: Int get() = queue.size

    companion object { const val DEFAULT_QUEUE_CAPACITY = 16 }
}
