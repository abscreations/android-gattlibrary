package com.gmail.absolutebollockscreations.gattlibrary.server

import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsSpec

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
public class FtmsProcedureSession {

    public enum class OperationalState {
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
    public data class AcceptedTargets(
        var speedKph: Double? = null,
        var inclinationPercent: Double? = null,
        var resistance: Double? = null,
        var powerWatts: Int? = null,
        var heartRateBpm: Int? = null,
        var indoorBikeSimulation: IndoorBikeSimulation? = null,
        var wheelCircumferenceMm: Double? = null,
    ) {
        public fun clear() {
            speedKph = null
            inclinationPercent = null
            resistance = null
            powerWatts = null
            heartRateBpm = null
            indoorBikeSimulation = null
            wheelCircumferenceMm = null
        }
    }

    public data class IndoorBikeSimulation(
        val windMps: Double,
        val gradePercent: Double,
        val crr: Double,
        val cw: Double,
    )

    public var hasControl: Boolean = false
        @GattLibraryInternalApi set

    public var operationalState: OperationalState = OperationalState.IDLE
        @GattLibraryInternalApi set

    public val acceptedTargets: AcceptedTargets = AcceptedTargets()

    @Volatile
    private var proceduresInFlight: Int = 0

    public val procedureInFlight: Boolean
        get() = proceduresInFlight > 0

    public val trainingStatus: Byte
        get() = when (operationalState) {
            OperationalState.RUNNING -> FtmsSpec.TrainingStatus.MANUAL_MODE_QUICK_START
            OperationalState.IDLE,
            OperationalState.PAUSED,
            OperationalState.STOPPED,
            -> FtmsSpec.TrainingStatus.IDLE
        }

    /** Flags=0 means no optional status string follows. */
    public fun trainingStatusPayload(): ByteArray = byteArrayOf(0x00, trainingStatus)

    public fun resetForDisconnect() {
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
    public fun tryBeginProcedure(): Boolean {
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
    public fun finishProcedure() {
        if (proceduresInFlight > 0) proceduresInFlight--
    }

    public companion object {
        /** Bounded protection against a client flooding Write Commands. */
        public const val MAX_PIPELINED_PROCEDURES: Int = 8
    }
}
