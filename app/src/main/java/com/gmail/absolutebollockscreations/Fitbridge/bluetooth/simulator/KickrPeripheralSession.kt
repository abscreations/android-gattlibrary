package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.RideOnServiceForm
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.RideOnUuids
import java.util.ArrayDeque
import java.util.UUID
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.FtmsControlPointHandler
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.PeripheralConnection

internal enum class KickrControlRoute { FTMS, LEGACY_WAHOO, ZWIFT_PROPRIETARY }

/** Which Zwift Click button a simulator UI tap represents - see KickrPeripheralSession.sendZwiftClickShift()'s KDoc. */
enum class ZwiftClickDirection { UP, DOWN }

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
    zwiftServiceForm: RideOnServiceForm? = null,
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
) {
    private val subscriptions = mutableMapOf<UUID, PeripheralConnection.CccdMode>()
    private val queue = ArrayDeque<KickrTxFrame>()
    val ftms = KickrFtmsControlProcessor(model)
    val legacy = legacyAckMode?.let { KickrLegacyControlServer(model, it) }
    val zwift = zwiftServiceForm?.let { ZwiftTrainerPeripheralServer(it, model) }

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

    @Synchronized
    fun handleZwiftControl(value: ByteArray): ZwiftTrainerServerOutcome? {
        val server = zwift ?: return null
        if (route !in setOf(null, KickrControlRoute.ZWIFT_PROPRIETARY)) return null
        val bothTxSubscribed =
            subscription(RideOnUuids.ASYNC_TX) == PeripheralConnection.CccdMode.NOTIFY &&
                subscription(RideOnUuids.SYNC_TX) == PeripheralConnection.CccdMode.INDICATE
        val outcome = server.handle(value, bothTxSubscribed)
        if (outcome.negotiated && !acquire(KickrControlRoute.ZWIFT_PROPRIETARY)) return null
        if (outcome.accepted) enqueueZwiftFrames(outcome)
        return outcome
    }

    @Synchronized
    fun enqueueZwiftTelemetry() {
        val server = zwift ?: return
        if (!server.controlReady ||
            subscription(RideOnUuids.ASYNC_TX) != PeripheralConnection.CccdMode.NOTIFY ||
            subscription(RideOnUuids.SYNC_TX) != PeripheralConnection.CccdMode.INDICATE
        ) return
        enqueueZwiftFrame(server.telemetryFrame())
    }

    /**
     * Emit one Zwift Click button tap: a pressed frame immediately followed by a released frame,
     * both over notify (ASYNC_TX) - see ZwiftTrainerPeripheralServer.clickButtonFrame()'s KDoc.
     * zwiftplay/zaplibrary's real-hardware `ClickNotification.diff()` only reports a "key
     * pressed" event to the app on the transition back to released, so a single UI tap has to be
     * these two frames, not one.
     *
     * Gated on the same bothTxSubscribed/controlReady conditions as [enqueueZwiftTelemetry] - a
     * real Zwift Click is technically its own BLE peripheral, but FitBridge models it as extra
     * opcodes on the KICKR/Zwift Hub persona's already-negotiated RideOn connection rather than
     * standing up a second simulated peripheral (see RideOnUuids.OPCODE_CLICK_BUTTON_STATUS's
     * KDoc for why the shared service/characteristic UUIDs make that a faithful shortcut, not a
     * protocol violation).
     */
    @Synchronized
    fun sendZwiftClickShift(direction: ZwiftClickDirection): Boolean {
        val server = zwift ?: return false
        if (!server.controlReady ||
            subscription(RideOnUuids.ASYNC_TX) != PeripheralConnection.CccdMode.NOTIFY ||
            subscription(RideOnUuids.SYNC_TX) != PeripheralConnection.CccdMode.INDICATE
        ) return false
        val up = direction == ZwiftClickDirection.UP
        val down = direction == ZwiftClickDirection.DOWN
        enqueueZwiftFrame(server.clickButtonFrame(upPressed = up, downPressed = down))
        enqueueZwiftFrame(server.clickButtonFrame(upPressed = false, downPressed = false))
        return true
    }

    private fun enqueueZwiftFrames(outcome: ZwiftTrainerServerOutcome) =
        outcome.frames.forEach(::enqueueZwiftFrame)

    private fun enqueueZwiftFrame(frame: com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.ZwiftTrainerServerFrame) {
        enqueue(
            KickrTxFrame(
                if (frame.async) RideOnUuids.ASYNC_TX else RideOnUuids.SYNC_TX,
                frame.value,
                indicate = !frame.async,
            ),
        )
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
        zwift?.reset()
    }

    val pendingCount: Int get() = queue.size

    companion object { const val DEFAULT_QUEUE_CAPACITY = 16 }
}
