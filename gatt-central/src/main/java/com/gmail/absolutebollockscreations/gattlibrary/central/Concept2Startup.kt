package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.FitBridgeBleInternalApi

/** GATT property bits are Bluetooth-defined; no command is issued without a subscribable reply. */
internal fun concept2StartupWriteMode(
    requestProperties: Int,
    responseProperties: Int,
    hasResponseCccd: Boolean,
): StackEntry.WriteMode? {
    if (!hasResponseCccd || responseProperties and 0x10 == 0) return null
    return when {
        requestProperties and 0x08 != 0 -> StackEntry.WriteMode.WITH_RESPONSE
        requestProperties and 0x04 != 0 -> StackEntry.WriteMode.WITHOUT_RESPONSE
        else -> null
    }
}

/** One user-authorized physical setup command; never a generic CSAFE control transport. */
@FitBridgeBleInternalApi
class Concept2StartupHandshake {
    enum class State { NEW, AWAITING_ACK, ACKNOWLEDGED, FAILED, TIMED_OUT }
    @Volatile
    var state = State.NEW
        private set
    private var issuedAtMs = 0L

    @Synchronized
    fun issue(nowMs: Long): ByteArray? {
        if (state != State.NEW) return null
        issuedAtMs = nowMs
        state = State.AWAITING_ACK
        // Concept2 CSAFE definition rev 0.27, p80: JustRow splits + Prepare to Row.
        // Leading 01 is ErgData's captured request-side BLE envelope, outside the checksum.
        return byteArrayOf(0x01, 0xF1.toByte(), 0x76, 0x07, 0x01, 0x01, 0x01,
            0x13, 0x02, 0x01, 0x01, 0x61, 0xF2.toByte())
    }

    @Synchronized
    fun writeCompleted(success: Boolean) {
        if (!success && state == State.AWAITING_ACK) state = State.FAILED
    }

    @Synchronized
    fun acceptAcknowledgement(bytes: ByteArray): Boolean {
        if (state != State.AWAITING_ACK || bytes.size != 8) return false
        val u = bytes.map { it.toInt() and 0xFF }
        if (u[0] != 0xF1 || u[7] != 0xF2 || u[1] !in setOf(0x01, 0x81, 0x09, 0x89) ||
            u[2] != 0x76 || u[3] != 2 || u[4] != 1 || u[5] != 0x13 ||
            (u[1] xor u[2] xor u[3] xor u[4] xor u[5]) != u[6]
        ) return false
        state = State.ACKNOWLEDGED
        return true
    }

    @Synchronized
    fun tick(nowMs: Long) {
        if (state == State.AWAITING_ACK && nowMs - issuedAtMs >= ACK_TIMEOUT_MS) {
            state = State.TIMED_OUT
        }
    }

    companion object {
        // FitBridge policy bound, not a claimed physical PM5 response latency.
        const val ACK_TIMEOUT_MS = 10_000L
    }
}

/** Fusion-owned origin for the user's retained-counter rule; parser output stays unmodified. */
internal class Concept2DistanceOrigin {
    private var initialized = false
    var floorMetres = 0.0
        private set

    fun align(rawMetres: Double): Double {
        if (!initialized) {
            floorMetres = rawMetres.takeIf { it > 50.0 } ?: 0.0
            initialized = true
        } else if (rawMetres < floorMetres) {
            // The physical capture resets retained counters on first motion. Do not keep
            // subtracting an old workout's floor after the new native counter starts at zero.
            floorMetres = 0.0
        }
        return (rawMetres - floorMetres).coerceAtLeast(0.0)
    }
}
