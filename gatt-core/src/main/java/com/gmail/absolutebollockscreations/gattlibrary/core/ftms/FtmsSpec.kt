package com.gmail.absolutebollockscreations.gattlibrary.core.ftms

/**
 * Fitness Machine Service (FTMS) protocol vocabulary: bit positions, opcodes and status codes.
 *
 * One definition for every module (L23, LIB-19a). It merges what central's `BluetoothIds`
 * (opcodes, result codes, status codes) and peripheral's `FtmsSpec` (the same, plus feature bits
 * and training status) each carried; the values were compared and agreed, and this object holds
 * the union.
 *
 * Every table is transcribed from *Fitness Machine Service, Bluetooth Service Specification
 * v1.0* (2017-02-14); section references are given so values can be re-verified. Field layouts
 * and scales stay private to each side (Q1); only the wire vocabulary lives here.
 */
public object FtmsSpec {

    // ── Fitness Machine Feature, 0x2ACC (FTMS 4.3.1.1, Table 4.3) ─────────────
    //
    // This field carries measurement capability ONLY. Machine type lives in the
    // advertising Service Data structure, never here.
    public object Feature {
        public const val AVERAGE_SPEED: Int = 0
        public const val CADENCE: Int = 1
        public const val TOTAL_DISTANCE: Int = 2
        public const val INCLINATION: Int = 3
        public const val ELEVATION_GAIN: Int = 4
        public const val PACE: Int = 5
        public const val STEP_COUNT: Int = 6
        public const val RESISTANCE_LEVEL: Int = 7
        public const val STRIDE_COUNT: Int = 8
        public const val EXPENDED_ENERGY: Int = 9
        public const val HEART_RATE: Int = 10
        public const val METABOLIC_EQUIVALENT: Int = 11
        public const val ELAPSED_TIME: Int = 12
        public const val REMAINING_TIME: Int = 13
        public const val POWER_MEASUREMENT: Int = 14
        public const val FORCE_ON_BELT_AND_POWER: Int = 15
        public const val USER_DATA_RETENTION: Int = 16
    }

    // ── Target Setting Features, 0x2ACC upper word (FTMS 4.3.1.2, Table 4.4) ──
    public object TargetFeature {
        public const val SPEED: Int = 0
        public const val INCLINATION: Int = 1
        public const val RESISTANCE: Int = 2
        public const val POWER: Int = 3
        public const val HEART_RATE: Int = 4
        public const val EXPENDED_ENERGY: Int = 5
        public const val STEP_NUMBER: Int = 6
        public const val STRIDE_NUMBER: Int = 7
        public const val DISTANCE: Int = 8
        public const val TRAINING_TIME: Int = 9
        public const val TIME_IN_TWO_HR_ZONES: Int = 10
        public const val TIME_IN_THREE_HR_ZONES: Int = 11
        public const val TIME_IN_FIVE_HR_ZONES: Int = 12
        public const val INDOOR_BIKE_SIMULATION: Int = 13
        public const val WHEEL_CIRCUMFERENCE: Int = 14
        public const val SPIN_DOWN_CONTROL: Int = 15
        public const val TARGETED_CADENCE: Int = 16
    }

    // ── Control Point opcodes (FTMS 4.16) ─────────────────────────────────────
    public object Opcode {
        public const val REQUEST_CONTROL: Byte = 0x00
        public const val RESET: Byte = 0x01
        public const val SET_TARGET_SPEED: Byte = 0x02          // uint16, 0.01 km/h
        public const val SET_TARGET_INCLINATION: Byte = 0x03    // sint16, 0.1 %
        public const val SET_TARGET_RESISTANCE: Byte = 0x04     // uint8/sint16, 0.1 units
        public const val SET_TARGET_POWER: Byte = 0x05          // sint16, W
        public const val SET_TARGET_HEART_RATE: Byte = 0x06     // uint8,  bpm
        public const val START_RESUME: Byte = 0x07
        public const val STOP_PAUSE: Byte = 0x08                // uint8: 1 stop, 2 pause
        public const val SET_INDOOR_BIKE_SIMULATION: Byte = 0x11
        public const val SET_WHEEL_CIRCUMFERENCE: Byte = 0x12
        public const val SPIN_DOWN_CONTROL: Byte = 0x13
        public const val SET_TARGETED_CADENCE: Byte = 0x14
        public const val RESPONSE_CODE: Byte = 0x80.toByte()
    }

    public object Result {
        public const val SUCCESS: Byte = 0x01
        public const val OPCODE_NOT_SUPPORTED: Byte = 0x02
        public const val INVALID_PARAMETER: Byte = 0x03
        public const val OPERATION_FAILED: Byte = 0x04
        public const val CONTROL_NOT_PERMITTED: Byte = 0x05
    }

    // ── Fitness Machine Status opcodes, 0x2ADA (FTMS 4.17) ────────────────────
    public object Status {
        public const val NO_STATUS: Byte = 0x00
        public const val RESET: Byte = 0x01
        public const val STOPPED_OR_PAUSED_BY_USER: Byte = 0x02
        public const val STOPPED_BY_SAFETY_KEY: Byte = 0x03
        public const val STARTED_OR_RESUMED_BY_USER: Byte = 0x04
        public const val TARGET_SPEED_CHANGED: Byte = 0x05
        public const val TARGET_INCLINE_CHANGED: Byte = 0x06
        public const val TARGET_RESISTANCE_CHANGED: Byte = 0x07
        public const val TARGET_POWER_CHANGED: Byte = 0x08
        public const val TARGET_HEART_RATE_CHANGED: Byte = 0x09
        public const val INDOOR_BIKE_SIMULATION_PARAMETERS_CHANGED: Byte = 0x12
        public const val WHEEL_CIRCUMFERENCE_CHANGED: Byte = 0x13
        public const val CONTROL_PERMISSION_LOST: Byte = 0xFF.toByte()
    }

    // ── Training Status values, 0x2AD3 (FTMS 4.10) ────────────────────────────
    public object TrainingStatus {
        public const val OTHER: Byte = 0x00
        public const val IDLE: Byte = 0x01
        public const val MANUAL_MODE_QUICK_START: Byte = 0x0D
        public const val PRE_WORKOUT: Byte = 0x0E
        public const val POST_WORKOUT: Byte = 0x0F
    }

    /** sint16 "value not available" sentinel used for fields we cannot supply. */
    public const val SINT16_NOT_AVAILABLE: Int = 0x7FFF

    // ── Control Point parameter units (FTMS 4.16.2) ───────────────────────────
    /** km/h * 100 -> UInt16 (Set Target Speed). */
    public const val SPEED_SCALE: Float = 100f

    /** percent * 10 -> SInt16 (Set Target Inclination). */
    public const val INCLINE_SCALE: Float = 10f

    /** level * 10 (Set Target Resistance Level). */
    public const val RESISTANCE_SCALE: Float = 10f

    /** watts * 1 -> SInt16 (Set Target Power). */
    public const val POWER_SCALE: Float = 1f
}
