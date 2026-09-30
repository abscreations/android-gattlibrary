package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsSpec

/**
 * One schema table shared by parsing and byte-level tests.
 *
 * Exact lengths are intentional. Accepting a valid prefix with trailing bytes
 * makes malformed procedures ambiguous and has produced device-specific client
 * behavior; unsupported opcodes remain absent instead of acquiring a guessed
 * length.
 */
internal object FtmsControlPointSchema {
    fun expectedLength(opcode: Byte): Int? = when (opcode) {
        FtmsSpec.Opcode.REQUEST_CONTROL,
        FtmsSpec.Opcode.RESET,
        FtmsSpec.Opcode.START_RESUME,
        -> 1

        FtmsSpec.Opcode.STOP_PAUSE,
        FtmsSpec.Opcode.SET_TARGET_HEART_RATE,
        -> 2

        // Legacy FTMS uses UINT8 at 0.1 resolution. The handler additionally
        // accepts the newer SINT16 form when a range exceeds 25.5.
        FtmsSpec.Opcode.SET_TARGET_RESISTANCE -> 2

        FtmsSpec.Opcode.SET_TARGET_SPEED,
        FtmsSpec.Opcode.SET_TARGET_INCLINATION,
        FtmsSpec.Opcode.SET_TARGET_POWER,
        FtmsSpec.Opcode.SET_WHEEL_CIRCUMFERENCE,
        -> 3

        FtmsSpec.Opcode.SET_INDOOR_BIKE_SIMULATION -> 7
        else -> null
    }
}

// Little-endian byte helpers moved to gatt-core `core.bytes.LittleEndian` (LIB-18g). The
// encoders use its explicit `…Wrapping` forms, which keep exactly the masking behavior the
// removed u8/u16le/s16le/u24le/u32le had.

internal fun ByteArray.toHexString(): String =
    if (isEmpty()) "[]" else joinToString(" ") { "%02X".format(it) }

/**
 * Master switch for the peripheral's verbose diagnostic logging.
 *
 * When true, the advertiser, notifier and connection emit per-tick encoding and
 * per-fragment notification traces. Developer-only compile-time switch: it is
 * deliberately not connected to a preference, so release users cannot enable
 * packet/MAC traces accidentally.
 */
internal const val FTMS_VERBOSE_LOGGING: Boolean = false

/**
 * Connect-time ATT tracing. Unlike [FTMS_VERBOSE_LOGGING] (per-packet/MAC spam),
 * this logs only the low-frequency GATT-server events during connection setup:
 * MTU exchange and every characteristic/descriptor read/write, with the matched
 * connection and the status we return. Purpose: diagnose a central that connects
 * at the ACL level but never completes GATT setup (e.g. Garmin Connect IQ never
 * firing onConnectedStateChanged). Silence here across the whole connect window
 * means the stall is in stack-level service discovery, which the peripheral app
 * never sees; GATT_FAILURE/NOT_PERMITTED returns mean the request reached us but
 * was refused.
 */
internal const val FTMS_CONNECT_TRACE: Boolean = true

/** Human-readable name for the common GATT status codes we return. */
internal fun gattStatusName(status: Int): String = when (status) {
    0x00 -> "SUCCESS"
    0x01 -> "INVALID_HANDLE"
    0x02 -> "READ_NOT_PERMITTED"
    0x03 -> "WRITE_NOT_PERMITTED"
    0x05 -> "INSUFFICIENT_AUTHENTICATION"
    0x06 -> "REQUEST_NOT_SUPPORTED"
    0x07 -> "INVALID_OFFSET"
    0x0D -> "INVALID_ATTRIBUTE_LENGTH"
    0x0F -> "INSUFFICIENT_ENCRYPTION"
    0x0101 -> "GATT_FAILURE"
    else -> "0x${status.toString(16)}"
}

/** Human-readable native status from a GATT server disconnection callback. */
internal fun disconnectStatusName(status: Int): String = when (status) {
    0x00 -> "SUCCESS"
    0x08 -> "CONNECTION_TIMEOUT"
    0x13 -> "REMOTE_USER_TERMINATED_CONNECTION"
    0x16 -> "CONNECTION_TERMINATED_BY_LOCAL_HOST"
    0x22 -> "LL_RESPONSE_TIMEOUT"
    0x23 -> "LL_PROCEDURE_COLLISION"
    0x24 -> "LMP_PDU_NOT_ALLOWED"
    0x3E -> "CONNECTION_FAILED_TO_ESTABLISH"
    0x85 -> "GATT_ERROR"
    else -> "0x${status.toString(16)}"
}

internal fun shortUuidLabel(u: UUID?): String = when (u) {
    null -> "null"
    else -> u.toString().let { s ->
        if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) s.substring(4, 8)
        else s
    }
}
