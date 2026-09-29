package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import java.util.UUID

/**
 * FTMS bit layouts, opcodes and byte helpers used by the peripheral (provider)
 * role.
 *
 * Ported from TrackMyIndoorWorkout's `dev.jcrystal.bleperipheral.BluetoothIds`,
 * which is the source of truth for this stack. UUIDs are NOT redeclared here -
 * they continue to live in the shared
 * [com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds]
 * registry so the engine (central) and peripheral roles cannot drift apart.
 *
 * Every bit table below is transcribed from *Fitness Machine Service, Bluetooth
 * Service Specification v1.0* (2017-02-14). Section references are given so the
 * values can be re-verified without guessing.
 */
object FtmsSpec {

    // ── Fitness Machine Feature, 0x2ACC (FTMS 4.3.1.1, Table 4.3) ─────────────
    //
    // This field carries measurement capability ONLY. Machine type lives in the
    // advertising Service Data structure, never here.
    object Feature {
        const val AVERAGE_SPEED = 0
        const val CADENCE = 1
        const val TOTAL_DISTANCE = 2
        const val INCLINATION = 3
        const val ELEVATION_GAIN = 4
        const val PACE = 5
        const val STEP_COUNT = 6
        const val RESISTANCE_LEVEL = 7
        const val STRIDE_COUNT = 8
        const val EXPENDED_ENERGY = 9
        const val HEART_RATE = 10
        const val METABOLIC_EQUIVALENT = 11
        const val ELAPSED_TIME = 12
        const val REMAINING_TIME = 13
        const val POWER_MEASUREMENT = 14
        const val FORCE_ON_BELT_AND_POWER = 15
        const val USER_DATA_RETENTION = 16
    }

    // ── Target Setting Features, 0x2ACC upper word (FTMS 4.3.1.2, Table 4.4) ──
    object TargetFeature {
        const val SPEED = 0
        const val INCLINATION = 1
        const val RESISTANCE = 2
        const val POWER = 3
        const val HEART_RATE = 4
        const val EXPENDED_ENERGY = 5
        const val STEP_NUMBER = 6
        const val STRIDE_NUMBER = 7
        const val DISTANCE = 8
        const val TRAINING_TIME = 9
        const val TIME_IN_TWO_HR_ZONES = 10
        const val TIME_IN_THREE_HR_ZONES = 11
        const val TIME_IN_FIVE_HR_ZONES = 12
        const val INDOOR_BIKE_SIMULATION = 13
        const val WHEEL_CIRCUMFERENCE = 14
        const val SPIN_DOWN_CONTROL = 15
        const val TARGETED_CADENCE = 16
    }

    // ── Control Point opcodes (FTMS 4.16) ─────────────────────────────────────
    object Opcode {
        const val REQUEST_CONTROL: Byte = 0x00
        const val RESET: Byte = 0x01
        const val SET_TARGET_SPEED: Byte = 0x02          // uint16, 0.01 km/h
        const val SET_TARGET_INCLINATION: Byte = 0x03    // sint16, 0.1 %
        const val SET_TARGET_RESISTANCE: Byte = 0x04     // uint8/sint16, 0.1 units
        const val SET_TARGET_POWER: Byte = 0x05          // sint16, W
        const val SET_TARGET_HEART_RATE: Byte = 0x06     // uint8,  bpm
        const val START_RESUME: Byte = 0x07
        const val STOP_PAUSE: Byte = 0x08                // uint8: 1 stop, 2 pause
        const val SET_INDOOR_BIKE_SIMULATION: Byte = 0x11
        const val SET_WHEEL_CIRCUMFERENCE: Byte = 0x12
        const val SPIN_DOWN_CONTROL: Byte = 0x13
        const val SET_TARGETED_CADENCE: Byte = 0x14
        const val RESPONSE_CODE: Byte = 0x80.toByte()
    }

    object Result {
        const val SUCCESS: Byte = 0x01
        const val OPCODE_NOT_SUPPORTED: Byte = 0x02
        const val INVALID_PARAMETER: Byte = 0x03
        const val OPERATION_FAILED: Byte = 0x04
        const val CONTROL_NOT_PERMITTED: Byte = 0x05
    }

    // ── Fitness Machine Status opcodes, 0x2ADA (FTMS 4.17) ────────────────────
    object Status {
        const val NO_STATUS: Byte = 0x00
        const val RESET: Byte = 0x01
        const val STOPPED_OR_PAUSED_BY_USER: Byte = 0x02
        const val STOPPED_BY_SAFETY_KEY: Byte = 0x03
        const val STARTED_OR_RESUMED_BY_USER: Byte = 0x04
        const val TARGET_SPEED_CHANGED: Byte = 0x05
        const val TARGET_INCLINE_CHANGED: Byte = 0x06
        const val TARGET_RESISTANCE_CHANGED: Byte = 0x07
        const val TARGET_POWER_CHANGED: Byte = 0x08
        const val TARGET_HEART_RATE_CHANGED: Byte = 0x09
        const val INDOOR_BIKE_SIMULATION_PARAMETERS_CHANGED: Byte = 0x12
        const val WHEEL_CIRCUMFERENCE_CHANGED: Byte = 0x13
        const val CONTROL_PERMISSION_LOST: Byte = 0xFF.toByte()
    }

    // ── Training Status values, 0x2AD3 (FTMS 4.10) ────────────────────────────
    object TrainingStatus {
        const val OTHER: Byte = 0x00
        const val IDLE: Byte = 0x01
        const val MANUAL_MODE_QUICK_START: Byte = 0x0D
        const val PRE_WORKOUT: Byte = 0x0E
        const val POST_WORKOUT: Byte = 0x0F
    }

    /** sint16 "value not available" sentinel used for fields we cannot supply. */
    const val SINT16_NOT_AVAILABLE: Int = 0x7FFF
}

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

// ── Little-endian byte helpers ────────────────────────────────────────────────
//
// Deliberately declared in this package rather than reusing the engine package's
// helpers: the peripheral encoders need u8/u32le too, and keeping one complete
// set here means an encoder file never has to import from two packages and risk
// an ambiguity error.

fun u8(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte())

fun u16le(v: Int): ByteArray =
    byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())

/** Two's-complement 16-bit little-endian. Negative inputs must survive here. */
fun s16le(v: Int): ByteArray {
    val s = v.toShort().toInt()
    return byteArrayOf((s and 0xFF).toByte(), ((s ushr 8) and 0xFF).toByte())
}

fun u24le(v: Int): ByteArray =
    byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
    )

fun u32le(v: Int): ByteArray =
    byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte(),
    )

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
