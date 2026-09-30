package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import android.bluetooth.BluetoothDevice
import com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds
import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids

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

    /**
     * Whether this link should use the compact two-frame Cross Trainer layout
     * instead of strict More Data fragmentation.
     *
     * A client that never raises the ATT MTU leaves 20 usable octets, which is
     * not enough for a populated Cross Trainer record. Strict fragmentation then
     * splits every record across three or four notifications joined by the More
     * Data bit — and reassembling those is an obscure corner of the FTMS spec
     * that thin clients routinely do not implement.
     *
     * Evidence: a 2026-08-06 session where a Connect IQ data field subscribed at
     * MTU 23, received 3.4 frames per record for sixteen seconds without ever
     * speaking again, then dropped the link cleanly. FitBridge's own metrics were
     * healthy throughout — staleness under 170 ms, no failed or superseded
     * records — so the link was fine and the client simply could not use what it
     * was being sent.
     *
     * Keyed on MTU rather than on device identity because the constraint is the
     * payload budget, not the vendor. Any client that leaves the MTU near the BLE
     * minimum has the same problem and gets the same treatment.
     */
    val prefersCompactFraming: Boolean
        get() = prefersCompactFramingAt(attMtu)

    fun onMtuChanged(mtu: Int) {
        attMtu = mtu.coerceIn(DEFAULT_ATT_MTU, MAX_ATT_MTU)
    }

    /**
     * Negotiated connection interval in milliseconds, or 0 while unknown.
     *
     * This is the link's actual capacity: the peripheral gets one transmission
     * opportunity per connection event, so a record cannot reach the client more
     * often than this no matter how fast it is produced.
     */
    @Volatile
    var connectionIntervalMs: Int = 0
        private set

    /** Peripheral latency, in connection events the peripheral may skip. */
    @Volatile
    var slaveLatency: Int = 0
        private set

    /** Supervision timeout in milliseconds, or 0 while unknown. */
    @Volatile
    var supervisionTimeoutMs: Int = 0
        private set

    /**
     * Records a connection-parameter update.
     *
     * **Nothing calls this today.** `BluetoothGattServerCallback` has no
     * `onConnectionUpdated` — that callback exists only on the central-role
     * `BluetoothGattCallback` — so Android gives a GATT server no supported way
     * to observe the parameters of a link it is serving. This is kept as the
     * single plug-in point for the day one appears, and is exercised by tests so
     * it cannot rot. Until then [assumedConnectionIntervalMs] supplies an
     * estimate instead.
     *
     * [intervalUnits] and [timeoutUnits] arrive in the Bluetooth wire units —
     * 1.25 ms and 10 ms respectively — which is why they are converted here
     * rather than at the call site.
     */
    fun onConnectionUpdated(intervalUnits: Int, latency: Int, timeoutUnits: Int) {
        if (intervalUnits > 0) {
            connectionIntervalMs = (intervalUnits * CONNECTION_INTERVAL_UNIT_US / 1000)
                .coerceAtLeast(1)
        }
        if (latency >= 0) slaveLatency = latency
        supervisionTimeoutMs = if (timeoutUnits > 0) timeoutUnits * 10 else supervisionTimeoutMs
    }

    /**
     * Shortest useful interval between measurement records on this link.
     *
     * Evidence, from a sniffer capture on 2026-08-06: the client negotiated a
     * 990 ms connection interval, FitBridge emitted 7.6 notifications per second,
     * and 2.2 per second reached the air. The surplus sat in Android's controller
     * buffer and delayed *everything else* — a CCCD write took 4.9 s to be
     * answered, a Control Point response indication 14.9 s, a characteristic read
     * 15.8 s. The client gave up long before those arrived.
     *
     * Producing faster than the link drains does not deliver data sooner; it only
     * builds a queue in front of the client's own requests.
     */
    val emissionFloorMs: Long
        get() = emissionFloorMs(assumedConnectionIntervalMs, slaveLatency)

    /**
     * The interval used for pacing: measured if it ever becomes available,
     * otherwise **estimated from the ATT MTU**.
     *
     * This is an assumption, not an observation, and it is worth being blunt
     * about that. Android exposes no connection parameters to a GATT server, so
     * the only signal available is the one thing the client did tell us: how much
     * payload it was willing to accept. A client that never raises the MTU above
     * the BLE minimum is, empirically, also a client that negotiates a long
     * connection interval — the Connect IQ watch measured on 2026-08-06 did both,
     * at MTU 23 and 990 ms.
     *
     * The estimate is deliberately a little slower than that measurement, since
     * pacing too slowly costs freshness while pacing too fast costs the client's
     * own ATT traffic — and the second failure is the one that broke the link.
     *
     * A client that negotiates a real MTU gets no estimate at all: 0 means "no
     * opinion", and the notifier keeps its configured period.
     */
    val assumedConnectionIntervalMs: Int
        get() = if (connectionIntervalMs > 0) {
            connectionIntervalMs
        } else {
            assumedIntervalForMtu(attMtu)
        }

    fun onCccdWrite(characteristic: UUID, dataUuid: UUID, mode: CccdMode) {
        subscriptions.write(characteristic, mode)
        recompute(dataUuid)
    }

    fun cccdMode(characteristic: UUID): CccdMode = subscriptions.mode(characteristic)

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
        // Connection parameters are negotiated per link. Carrying a previous
        // client's interval into a new session would pace the new one to a link
        // that no longer exists.
        connectionIntervalMs = 0
        slaveLatency = 0
        supervisionTimeoutMs = 0
    }

    fun countPacket() {
        packetsSent++
    }

    /** Compact one-line dump of subscription + link state for logging. */
    fun describe(): String =
        "session=$sessionId mtu=$attMtu payloadCap=$maxNotifyPayload " +
            "~interval=${assumedConnectionIntervalMs}ms floor=${emissionFloorMs}ms ftms=$dataSubscribed " +
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
            subscriptions.mode(FtmsUuids.UUID_FITNESS_MACHINE_STATUS) == CccdMode.NOTIFY
        controlPointSubscribed =
            subscriptions.mode(FtmsUuids.UUID_FITNESS_MACHINE_CONTROL_POINT) == CccdMode.INDICATE
        trainingStatusSubscribed =
            subscriptions.mode(FtmsUuids.UUID_TRAINING_STATUS) == CccdMode.NOTIFY
        cscMeasurementSubscribed =
            subscriptions.mode(SigUuids.UUID_CSC_MEASUREMENT) == CccdMode.NOTIFY
        cyclingPowerMeasurementSubscribed =
            subscriptions.mode(SigUuids.UUID_CYCLING_POWER_MEASUREMENT) == CccdMode.NOTIFY
        cscControlPointSubscribed =
            subscriptions.mode(SigUuids.UUID_SC_CONTROL_POINT) == CccdMode.INDICATE
    }

    companion object {
        const val DEFAULT_ATT_MTU = 23
        const val MAX_ATT_MTU = 517

        /**
         * Below this ATT MTU, records are packed compactly rather than fragmented.
         *
         * 30 leaves 27 payload octets, comfortably above the 20 a populated Cross
         * Trainer record needs in its first frame. Anything at or above this can
         * carry a full record without relying on the client reassembling More Data
         * continuations. Deliberately a little above the BLE minimum of 23 so a
         * client that negotiates only a token increase is still treated as
         * constrained.
         */
        const val COMPACT_FRAMING_MTU_THRESHOLD = 30

        /**
         * The compact-framing decision as a pure function.
         *
         * Extracted so it is testable: `PeripheralConnection` needs a real
         * `BluetoothDevice`, which cannot be constructed in a JVM unit test, and
         * a rule that changes what goes on the wire should not be untestable for
         * want of a stub.
         */
        fun prefersCompactFramingAt(attMtu: Int): Boolean =
            attMtu < COMPACT_FRAMING_MTU_THRESHOLD

        /** Bluetooth connection-interval unit: 1.25 ms, expressed in microseconds. */
        const val CONNECTION_INTERVAL_UNIT_US = 1_250

        /**
         * Interval assumed for a client that never raised its ATT MTU.
         *
         * 1000 ms, chosen just above the 990 ms measured from a Connect IQ watch
         * on 2026-08-06. Erring slow is deliberate: pacing too slowly costs
         * freshness, while pacing too fast costs the client's own ATT traffic —
         * and that second failure is what actually broke the link, delaying a
         * Control Point response by 14.9 s.
         */
        const val ASSUMED_LOW_MTU_INTERVAL_MS = 1_000

        /**
         * Interval estimate from the only signal a GATT server actually gets.
         *
         * Returns 0 — "no opinion" — for any client that negotiated a real MTU,
         * so a phone keeps the configured period rather than being throttled on
         * a guess.
         */
        fun assumedIntervalForMtu(attMtu: Int): Int =
            if (prefersCompactFramingAt(attMtu)) ASSUMED_LOW_MTU_INTERVAL_MS else 0

        /**
         * Upper bound on the derived floor.
         *
         * A genuinely slow link should be paced slowly, so this is deliberately
         * generous rather than tuned. It exists only so an implausible reported
         * interval cannot wedge the pump into near-silence.
         */
        const val MAX_EMISSION_FLOOR_MS = 5_000L

        /**
         * The pacing decision as a pure function.
         *
         * One transmission opportunity per connection event, and a peripheral
         * permitted to skip [slaveLatency] events may go that many further
         * without transmitting — so the shortest interval at which a record can
         * actually reach the client is `interval × (latency + 1)`.
         *
         * Returns 0 when the interval is unknown, meaning "no opinion": the
         * caller keeps its configured floor. Extracted because the alternative is
         * a rule that changes the on-air rate and is untestable for want of a
         * `BluetoothDevice` stub.
         */
        fun emissionFloorMs(connectionIntervalMs: Int, slaveLatency: Int): Long {
            if (connectionIntervalMs <= 0) return 0L
            val events = (slaveLatency.coerceAtLeast(0) + 1).toLong()
            return (connectionIntervalMs.toLong() * events).coerceAtMost(MAX_EMISSION_FLOOR_MS)
        }

        /** ATT notification header: opcode (1) + handle (2). */
        /** ATT notification header: opcode plus handle. Shared with the encoders. */
        const val ATT_NOTIFY_OVERHEAD = 3

        /** Floor payload, i.e. the MTU-23 case every client supports. */
        private const val MIN_NOTIFY_PAYLOAD = 20
    }
}
