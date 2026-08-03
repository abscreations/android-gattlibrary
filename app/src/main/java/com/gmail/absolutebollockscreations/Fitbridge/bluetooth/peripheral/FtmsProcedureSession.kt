package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

/**
 * Mutable FTMS procedure state owned by exactly one GATT connection generation.
 *
 * Intentionally independent of Android types so the transition rules can be
 * exhaustively unit tested. Nothing in this object survives a disconnect:
 * control is granted to a connection, not to a Bluetooth address or bond. The
 * previous `BtServiceConnections` kept control on a `ClientSession` keyed by
 * address, so a bonded peer reconnecting on the same address could inherit
 * control it had never re-requested.
 */
class FtmsProcedureSession {

    enum class OperationalState {
        IDLE,
        RUNNING,
        PAUSED,
        STOPPED,
    }

    /**
     * Last targets accepted by the real control sink.
     *
     * These values are diagnostics/state reflection, never optimistic requests:
     * [FtmsControlPointHandler] mutates them only after the downstream operation
     * succeeds.
     */
    data class AcceptedTargets(
        var speedKph: Double? = null,
        var inclinationPercent: Double? = null,
        var resistance: Double? = null,
        var powerWatts: Int? = null,
        var heartRateBpm: Int? = null,
        var indoorBikeSimulation: IndoorBikeSimulation? = null,
    ) {
        fun clear() {
            speedKph = null
            inclinationPercent = null
            resistance = null
            powerWatts = null
            heartRateBpm = null
            indoorBikeSimulation = null
        }
    }

    data class IndoorBikeSimulation(
        val windMps: Double,
        val gradePercent: Double,
        val crr: Double,
        val cw: Double,
    )

    var hasControl: Boolean = false
        internal set

    var operationalState: OperationalState = OperationalState.IDLE
        internal set

    val acceptedTargets = AcceptedTargets()

    @Volatile
    private var proceduresInFlight: Int = 0

    val procedureInFlight: Boolean
        get() = proceduresInFlight > 0

    val trainingStatus: Byte
        get() = when (operationalState) {
            OperationalState.RUNNING -> FtmsSpec.TrainingStatus.MANUAL_MODE_QUICK_START
            OperationalState.IDLE,
            OperationalState.PAUSED,
            OperationalState.STOPPED,
            -> FtmsSpec.TrainingStatus.IDLE
        }

    /** Flags=0 means no optional status string follows. */
    fun trainingStatusPayload(): ByteArray = byteArrayOf(0x00, trainingStatus)

    fun resetForDisconnect() {
        // A bonded peer may reconnect with the same address. Clear every
        // connection-scoped privilege and target so that identity reuse cannot
        // inherit control from the previous GATT session.
        proceduresInFlight = 0
        hasControl = false
        operationalState = OperationalState.IDLE
        acceptedTargets.clear()
    }

    internal fun resetByProcedure() {
        operationalState = OperationalState.IDLE
        acceptedTargets.clear()
    }

    @Synchronized
    fun tryBeginProcedure(): Boolean {
        // Strict FTMS clients wait for each response indication before writing
        // again. Garmin Connect IQ clients on a ~990 ms interval may submit a
        // second target while the first response is still behind a measurement
        // notification. Accept a small pipeline here; GattTxQueue still emits
        // the response indications one at a time and in request order.
        if (proceduresInFlight >= MAX_PIPELINED_PROCEDURES) return false
        proceduresInFlight++
        return true
    }

    @Synchronized
    fun finishProcedure() {
        if (proceduresInFlight > 0) proceduresInFlight--
    }

    companion object {
        /** Bounded protection against a client flooding Write Commands. */
        const val MAX_PIPELINED_PROCEDURES = 8
    }
}
