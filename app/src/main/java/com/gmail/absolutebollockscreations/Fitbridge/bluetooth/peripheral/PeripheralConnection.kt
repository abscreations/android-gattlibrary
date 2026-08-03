package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import android.bluetooth.BluetoothDevice
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import java.util.UUID

/**
 * Per-connection state for one downstream client.
 *
 * The peripheral serves multiple centrals at once; the advertiser keeps one of
 * these per attached client, keyed by address. Each carries its own CCCD
 * subscriptions, negotiated MTU, and Request Control procedure state, so clients
 * never share subscription or control ownership.
 *
 * Replaces `BtServiceConnections.ClientSession`, which tracked only two
 * booleans (`notifyFtmsData`, `notifyFtmsStatus`) for the whole FTMS service and
 * had no MTU at all - so every client was assumed to accept a 20-octet payload
 * and the Control Point / Training Status subscriptions were invisible.
 *
 * Not thread-safe by itself; all mutation happens on the single GATT callback
 * thread. Fields read by the notifier coroutine are @Volatile.
 */
class PeripheralConnection(
    val device: BluetoothDevice,
    val sessionId: Long,
) {

    enum class CccdMode(val wireValue: ByteArray) {
        DISABLED(byteArrayOf(0x00, 0x00)),
        NOTIFY(byteArrayOf(0x01, 0x00)),
        INDICATE(byteArrayOf(0x02, 0x00)),
    }

    /**
     * Exact connection-scoped CCCD state, keyed by characteristic UUID.
     *
     * The bond-tolerant policy intentionally starts this map empty for every
     * connection, including bonded peers. Clients must subscribe again after
     * reconnecting; no bonded identity or CCCD state is persisted.
     */
    private val subscriptions = SessionCccdState()

    @Volatile
    var dataSubscribed: Boolean = false
        private set

    @Volatile
    var statusSubscribed: Boolean = false
        private set

    @Volatile
    var controlPointSubscribed: Boolean = false
        private set

    @Volatile
    var trainingStatusSubscribed: Boolean = false
        private set

    @Volatile
    var cscMeasurementSubscribed: Boolean = false
        private set

    @Volatile
    var cyclingPowerMeasurementSubscribed: Boolean = false
        private set

    @Volatile
    var cscControlPointSubscribed: Boolean = false
        private set

    @Volatile
    private var cscProcedureInProgress: Boolean = false

    val anyMeasurementSubscribed: Boolean
        get() = dataSubscribed || cscMeasurementSubscribed || cyclingPowerMeasurementSubscribed

    /** Procedure ownership, operational state, and accepted targets for this link only. */
    val procedure = FtmsProcedureSession()

    var packetsSent: Long = 0
        private set

    /**
     * Negotiated ATT MTU for this link. The client always drives the MTU
     * exchange; the peripheral can only observe the result. Defaults to the BLE
     * minimum until the client asks for more. Connect IQ watches stay at 23; a
     * phone central typically negotiates up to ~247-517.
     */
    @Volatile
    var attMtu: Int = DEFAULT_ATT_MTU
        private set

    /** Largest notification value this link can carry: ATT_MTU - 3. */
    val maxNotifyPayload: Int
        get() = (attMtu - ATT_NOTIFY_OVERHEAD).coerceAtLeast(MIN_NOTIFY_PAYLOAD)

    fun onMtuChanged(mtu: Int) {
        attMtu = mtu.coerceIn(DEFAULT_ATT_MTU, MAX_ATT_MTU)
    }

    fun onCccdWrite(characteristic: UUID, dataUuid: UUID, mode: CccdMode) {
        subscriptions.write(characteristic, mode)
        recompute(dataUuid)
    }

    fun cccdMode(characteristic: UUID): CccdMode = subscriptions.mode(characteristic)

    fun isSubscribed(characteristic: UUID): Boolean = cccdMode(characteristic) != CccdMode.DISABLED

    fun resetSession() {
        subscriptions.clear()
        dataSubscribed = false
        statusSubscribed = false
        controlPointSubscribed = false
        trainingStatusSubscribed = false
        cscMeasurementSubscribed = false
        cyclingPowerMeasurementSubscribed = false
        cscControlPointSubscribed = false
        cscProcedureInProgress = false
        procedure.resetForDisconnect()
        attMtu = DEFAULT_ATT_MTU
    }

    fun countPacket() {
        packetsSent++
    }

    /** Compact one-line dump of subscription + link state for logging. */
    fun describe(): String =
        "session=$sessionId mtu=$attMtu payloadCap=$maxNotifyPayload ftms=$dataSubscribed " +
            "csc=$cscMeasurementSubscribed cps=$cyclingPowerMeasurementSubscribed " +
            "status=$statusSubscribed cp=$controlPointSubscribed scCp=$cscControlPointSubscribed " +
            "trainingStatus=$trainingStatusSubscribed " +
            "control=${procedure.hasControl} state=${procedure.operationalState} " +
            "sent=$packetsSent"

    fun tryBeginCscProcedure(): Boolean {
        if (cscProcedureInProgress) return false
        cscProcedureInProgress = true
        return true
    }

    fun finishCscProcedure() {
        cscProcedureInProgress = false
    }

    private fun recompute(dataUuid: UUID) {
        dataSubscribed = subscriptions.mode(dataUuid) == CccdMode.NOTIFY
        statusSubscribed =
            subscriptions.mode(BluetoothIds.UUID_FITNESS_MACHINE_STATUS) == CccdMode.NOTIFY
        controlPointSubscribed =
            subscriptions.mode(BluetoothIds.UUID_FITNESS_MACHINE_CONTROL_POINT) == CccdMode.INDICATE
        trainingStatusSubscribed =
            subscriptions.mode(BluetoothIds.UUID_TRAINING_STATUS) == CccdMode.NOTIFY
        cscMeasurementSubscribed =
            subscriptions.mode(BluetoothIds.UUID_CSC_MEASUREMENT) == CccdMode.NOTIFY
        cyclingPowerMeasurementSubscribed =
            subscriptions.mode(BluetoothIds.UUID_CYCLING_POWER_MEASUREMENT) == CccdMode.NOTIFY
        cscControlPointSubscribed =
            subscriptions.mode(BluetoothIds.UUID_SC_CONTROL_POINT) == CccdMode.INDICATE
    }

    companion object {
        const val DEFAULT_ATT_MTU = 23
        const val MAX_ATT_MTU = 517

        /** ATT notification header: opcode (1) + handle (2). */
        private const val ATT_NOTIFY_OVERHEAD = 3

        /** Floor payload, i.e. the MTU-23 case every client supports. */
        private const val MIN_NOTIFY_PAYLOAD = 20
    }
}
