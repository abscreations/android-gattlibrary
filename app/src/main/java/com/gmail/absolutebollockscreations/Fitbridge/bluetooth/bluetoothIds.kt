// =========================================
// File: bluetoothIds.kt (FitBridge)
// Shared between BtEngine (consumer) and btService (provider)
// =========================================


package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.os.ParcelUuid
import com.gmail.absolutebollockscreations.Fitbridge.ActivityType
import java.util.UUID

/**
 * Central registry of all Bluetooth SIG Assigned Numbers (UUIDs) and
 * FTMS/CSC/CPS opcodes used inside the FitBridge Bluetooth stack.
 *
 * This file is meant to be the **single source of truth** for:
 *  1. Services we *scan* for (to detect device capabilities)
 *  2. Services/characteristics we *enable notifications/indications* on
 *  3. Characteristics we *read* for capability/range info
 *  4. Characteristics we *write* to (FTMS Control Point, HRM Control Point, CSC/CP CP)
 *  5. The numeric **opcodes** and **result codes** for FTMS Control Point
 *  6. The unit **scales** used to encode FTMS CP payloads
 *
 * ──────────────────────────────────────────────────────────────────────────────
 * ORGANIZATION
 * ──────────────────────────────────────────────────────────────────────────────
 *  • [BluetoothIds]         → All Bluetooth UUIDs, opcodes, status codes, and unit scales
 *  • [Flags]                → FTMS data-characteristic flag bitmasks
 *  • [BtServiceProfiles]    → Ancillary service-level enums and constants
 *                              (service modes, FTMS machine personas, device names,
 *                               max clients, default notify interval, FMF presets)
 *
 * Engine and service code should import from here rather than redeclaring IDs.
 */

// ============================================================================
// 1) BluetoothIds — UUIDs, opcodes, status codes, and unit scales
// ============================================================================
object BluetoothIds {

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────
    /** Build a 128-bit UUID from a 16-bit Assigned Number. */
    private fun shortUuid(hex: Int): UUID =
        UUID.fromString(String.format("0000%04X-0000-1000-8000-00805F9B34FB", hex))

    // ─────────────────────────────────────────────────────────────
    // FTMS (Fitness Machine Service 0x1826)
    // ─────────────────────────────────────────────────────────────
    val UUID_FTMS_SERVICE: UUID    = shortUuid(0x1826)
    val FTMS_SERVICE_PARCEL: ParcelUuid = ParcelUuid(UUID_FTMS_SERVICE) // for scan filter

    // FTMS Core Characteristics
    val UUID_FITNESS_MACHINE_FEATURE: UUID       = shortUuid(0x2ACC)
    val UUID_FITNESS_MACHINE_CONTROL_POINT: UUID = shortUuid(0x2AD9)
    val UUID_FITNESS_MACHINE_STATUS: UUID        = shortUuid(0x2ADA)

    /**
     * Training Status (0x2AD3) — Read + Notify.
     *
     * Exposed by the peripheral role so a central can tell an idle machine from
     * a running one without inferring it from the measurement stream. Absent
     * from the previous FitBridge GATT schema entirely.
     */
    val UUID_TRAINING_STATUS: UUID               = shortUuid(0x2AD3)

    // FTMS Data Characteristics
    val UUID_TREADMILL_DATA: UUID       = shortUuid(0x2ACD)
    val UUID_ELLIPTICAL_DATA: UUID      = shortUuid(0x2ACE)
    val UUID_STAIR_STEPPER_DATA: UUID   = shortUuid(0x2ACF)
    val UUID_STAIR_CLIMBER_DATA: UUID   = shortUuid(0x2AD0)
    val UUID_ROWING_MACHINE_DATA: UUID  = shortUuid(0x2AD1)
    val UUID_BIKING_DATA: UUID          = shortUuid(0x2AD2)

    // FTMS Capability Range Characteristics
    val UUID_SUPPORTED_SPEED_RANGE: UUID       = shortUuid(0x2AD4)
    val UUID_SUPPORTED_INCLINATION_RANGE: UUID = shortUuid(0x2AD5)
    val UUID_SUPPORTED_RESISTANCE_RANGE: UUID  = shortUuid(0x2AD6)
    val UUID_SUPPORTED_HEART_RATE_RANGE: UUID  = shortUuid(0x2AD7)
    val UUID_SUPPORTED_POWER_RANGE: UUID       = shortUuid(0x2AD8)

    // ─────────────────────────────────────────────────────────────
    // FTMS Fitness Machine Status (0x2ADA) status opcodes
    // ─────────────────────────────────────────────────────────────
    // These are the values carried in the Fitness Machine Status characteristic,
    // not the Control Point. They are shared between engine (consumer) and service (provider).
    const val STATUS_NO_STATUS: Byte                             = 0x00
    const val STATUS_MACHINE_RESET: Byte                         = 0x01
    const val STATUS_USER_STOPPED_OR_PAUSED: Byte                = 0x02
    const val STATUS_FITNESS_MACHINE_STOPPED_BY_SAFETY_KEY: Byte = 0x03
    const val STATUS_USER_STARTED_OR_RESUMED: Byte               = 0x04
    const val STATUS_TARGET_SPEED_CHANGED: Byte                  = 0x05
    const val STATUS_TARGET_INCLINE_CHANGED: Byte                = 0x06
    const val STATUS_TARGET_RESISTANCE_LEVEL_CHANGED: Byte       = 0x07
    const val STATUS_TARGET_POWER_CHANGED: Byte                  = 0x08
    const val STATUS_TARGET_HEART_RATE_CHANGED: Byte             = 0x09
    const val STATUS_CONTROL_PERMISSION_LOST: Byte               = 0xFF.toByte()

    // ─────────────────────────────────────────────────────────────
    // Heart Rate (Service 0x180D)
    // ─────────────────────────────────────────────────────────────
    val UUID_HRM_SERVICE: UUID         = shortUuid(0x180D)
    val HRM_SERVICE_PARCEL: ParcelUuid = ParcelUuid(UUID_HRM_SERVICE)

    val UUID_HEART_RATE_MEASUREMENT: UUID   = shortUuid(0x2A37) // notify
    val UUID_BODY_SENSOR_LOCATION: UUID     = shortUuid(0x2A38) // read
    val UUID_HEART_RATE_CONTROL_POINT: UUID = shortUuid(0x2A39) // write (optional)

    // ─────────────────────────────────────────────────────────────
    // Cycling Speed & Cadence (Service 0x1816)
    // ─────────────────────────────────────────────────────────────
    val UUID_CSC_SERVICE: UUID         = shortUuid(0x1816)
    val CSC_SERVICE_PARCEL: ParcelUuid = ParcelUuid(UUID_CSC_SERVICE)

    // CSC Characteristics
    val UUID_CSC_MEASUREMENT: UUID  = shortUuid(0x2A5B) // notify
    val UUID_CSC_FEATURE: UUID      = shortUuid(0x2A5C) // read
    val UUID_SENSOR_LOCATION: UUID  = shortUuid(0x2A5D) // read (shared by CSC & CPS)
    val UUID_SC_CONTROL_POINT: UUID = shortUuid(0x2A55) // write / indicate

    // ─────────────────────────────────────────────────────────────
    // Running Speed & Cadence (Service 0x1814)
    // ─────────────────────────────────────────────────────────────
    val UUID_RSC_SERVICE: UUID         = shortUuid(0x1814)
    val RSC_SERVICE_PARCEL: ParcelUuid = ParcelUuid(UUID_RSC_SERVICE)
    val UUID_RSC_MEASUREMENT: UUID     = shortUuid(0x2A53) // notify
    val UUID_RSC_FEATURE: UUID         = shortUuid(0x2A54) // read
    // UUID_SENSOR_LOCATION (0x2A5D) and UUID_SC_CONTROL_POINT (0x2A55) are shared above.

    // ─────────────────────────────────────────────────────────────
    // Cycling Power (Service 0x1818)
    // ─────────────────────────────────────────────────────────────
    val UUID_POWER_SERVICE: UUID        = shortUuid(0x1818)
    val POWER_SERVICE_PARCEL: ParcelUuid = ParcelUuid(UUID_POWER_SERVICE)

    // Alias for clarity on service side
    val UUID_CYCLING_POWER_SERVICE: UUID         = UUID_POWER_SERVICE
    val CYCLING_POWER_SERVICE_PARCEL: ParcelUuid = POWER_SERVICE_PARCEL

    // CPS Characteristics
    val UUID_CYCLING_POWER_MEASUREMENT: UUID   = shortUuid(0x2A63) // notify
    val UUID_CYCLING_POWER_FEATURE: UUID       = shortUuid(0x2A65) // read
    val UUID_CYCLING_POWER_CONTROL_POINT: UUID = shortUuid(0x2A66) // write / indicate

    // ─────────────────────────────────────────────────────────────
    // Concept2 PM (proprietary, base CE06xxxx-43E5-11E4-916C-0800200C9A66)
    // ─────────────────────────────────────────────────────────────
    /**
     * Build a Concept2 PM UUID from its 16-bit alias.
     *
     * The base is quoted from the *Concept2 PM Bluetooth Smart Communication Interface
     * Definition*, revision 1.30 — `docs/PM5_BluetoothSmartInterfaceDefinition.pdf` — and not
     * inferred from TMIW's four-character aliases. Plan §10 forbids deriving a 128-bit UUID
     * from an alias, and §5a requires this confirmation to "actually be done, not assumed".
     * See `docs/evidence/phase-5/2026-08-10-concept2-pm5-uuid-resolution.md`.
     */
    private fun concept2Uuid(alias: Int): UUID =
        UUID.fromString(String.format("CE06%04X-43E5-11E4-916C-0800200C9A66", alias).lowercase())

    val UUID_C2_DISCOVERY_SERVICE: UUID    = concept2Uuid(0x0000)
    val C2_DISCOVERY_SERVICE_PARCEL: ParcelUuid = ParcelUuid(UUID_C2_DISCOVERY_SERVICE)

    val UUID_C2_DEVICE_INFO_SERVICE: UUID  = concept2Uuid(0x0010)
    val UUID_C2_MODEL_NUMBER: UUID         = concept2Uuid(0x0011)
    val UUID_C2_SERIAL_NUMBER: UUID        = concept2Uuid(0x0012)
    val UUID_C2_HARDWARE_REVISION: UUID    = concept2Uuid(0x0013)
    val UUID_C2_FIRMWARE_REVISION: UUID    = concept2Uuid(0x0014)
    val UUID_C2_MANUFACTURER_NAME: UUID    = concept2Uuid(0x0015)
    val UUID_C2_MACHINE_TYPE: UUID         = concept2Uuid(0x0016)
    val UUID_C2_ATT_RECEIVE_MTU: UUID      = concept2Uuid(0x0017)
    val UUID_C2_LL_MAX_TX_RX_BYTES: UUID   = concept2Uuid(0x0018)

    val UUID_C2_PM_CONTROL_SERVICE: UUID   = concept2Uuid(0x0020)
    val UUID_C2_CSAFE_REQUEST: UUID        = concept2Uuid(0x0021) // write, up to 20 B
    val UUID_C2_CSAFE_RESPONSE: UUID       = concept2Uuid(0x0022) // read, up to 20 B

    val UUID_C2_ERG_SERVICE: UUID          = concept2Uuid(0x0030)
    val C2_ERG_SERVICE_PARCEL: ParcelUuid  = ParcelUuid(UUID_C2_ERG_SERVICE)

    val UUID_C2_GENERAL_STATUS: UUID          = concept2Uuid(0x0031) // notify, 19 B
    val UUID_C2_ADDITIONAL_STATUS_1: UUID     = concept2Uuid(0x0032) // notify, 17 B
    val UUID_C2_ADDITIONAL_STATUS_2: UUID     = concept2Uuid(0x0033) // notify, 20 B
    val UUID_C2_STATUS_SAMPLE_RATE: UUID      = concept2Uuid(0x0034) // read/write, 1 B
    val UUID_C2_STROKE_DATA: UUID             = concept2Uuid(0x0035) // notify, 20 B
    val UUID_C2_ADDITIONAL_STROKE_DATA: UUID  = concept2Uuid(0x0036) // notify, 15 B
    val UUID_C2_SPLIT_INTERVAL_DATA: UUID     = concept2Uuid(0x0037) // notify, 18 B
    val UUID_C2_ADDITIONAL_SPLIT_INTERVAL: UUID = concept2Uuid(0x0038) // notify, 19 B
    val UUID_C2_WORKOUT_SUMMARY: UUID         = concept2Uuid(0x0039) // notify, 20 B
    val UUID_C2_ADDITIONAL_WORKOUT_SUMMARY: UUID = concept2Uuid(0x003A) // notify, 19 B
    val UUID_C2_HEART_RATE_BELT_INFO: UUID    = concept2Uuid(0x003B) // notify, 6 B
    val UUID_C2_ADDITIONAL_WORKOUT_SUMMARY_2: UUID = concept2Uuid(0x003C) // notify, 10 B
    val UUID_C2_FORCE_CURVE_DATA: UUID        = concept2Uuid(0x003D) // notify, 2..20 B
    val UUID_C2_ADDITIONAL_STATUS_3: UUID      = concept2Uuid(0x003E) // notify, 12 B
    val UUID_C2_LOGGED_WORKOUT: UUID           = concept2Uuid(0x003F) // notify, 15 B

    val UUID_C2_HEART_RATE_RECEIVE_SERVICE: UUID = concept2Uuid(0x0040)
    val UUID_C2_HEART_RATE_RECEIVE: UUID         = concept2Uuid(0x0041) // write, 20 B

    /**
     * Multiplexed information, up to 20 B: an identification byte followed by that ID's
     * firmware-specific payload. Several multiplexed layouts deliberately differ from their
     * individual characteristic (D41).
     */
    val UUID_C2_MULTIPLEXED_INFORMATION: UUID = concept2Uuid(0x0080)

    // ─────────────────────────────────────────────────────────────
    // Precor Spinner Chrono Power (proprietary, plan §5b)
    // ─────────────────────────────────────────────────────────────
    /**
     * Precor Chrono service and measurement UUIDs.
     *
     * **Written out in full, deliberately.** The Concept2 block above builds its UUIDs from a
     * base and an alias because Concept2's published specification *defines* that base and
     * thirty-odd aliases within it. Nothing comparable exists here: two identifiers were read
     * from a source project and there is no published Precor document behind them. A
     * `precorUuid(alias)` helper would present two extracted facts as if they were an
     * enumerable namespace, which is the §8.4 error in a more respectable costume — writing
     * both constants out means no third UUID can ever be produced by calling the helper with a
     * number nobody has seen.
     *
     * Source: QZ `src/devices/chronobike/chronobike.cpp` — `serviceScanDone()` and
     * `stateChanged()` — with the identical pair independently repeated in
     * `src/devices/truetreadmill/truetreadmill.cpp`. Resolved 2026-08-12; plan §10.
     *
     * The base coincides with Wahoo's `a026xxxx-0a7d-4ab3-97fa-f1500f9feb8b`, which is an
     * observation about two vendors' recorded identifiers and not a rule for deriving a third.
     */
    val UUID_PRECOR_SERVICE: UUID =
        UUID.fromString("a026ee07-0a7d-4ab3-97fa-f1500f9feb8b")
    val PRECOR_SERVICE_PARCEL: ParcelUuid = ParcelUuid(UUID_PRECOR_SERVICE)

    /** Notify, exactly 19 B, prefixed `53 59 16`. */
    val UUID_PRECOR_MEASUREMENT: UUID =
        UUID.fromString("a026e01d-0a7d-4ab3-97fa-f1500f9feb8b")

    // ─────────────────────────────────────────────────────────────
    // Glucose (Service 0x1808)
    // ─────────────────────────────────────────────────────────────
    val UUID_GLUCOSE_SERVICE: UUID         = shortUuid(0x1808)
    val GLUCOSE_SERVICE_PARCEL: ParcelUuid = ParcelUuid(UUID_GLUCOSE_SERVICE)

    // ─────────────────────────────────────────────────────────────
    // Descriptors
    // ─────────────────────────────────────────────────────────────
    /** Client Characteristic Configuration Descriptor (CCCD) */
    val UUID_CCCD: UUID = shortUuid(0x2902)

    // ─────────────────────────────────────────────────────────────
    // FTMS Control Point (0x2AD9) opcodes / response codes
    // ─────────────────────────────────────────────────────────────
    // Opcodes
    const val OPCODE_REQUEST_CONTROL: Byte        = 0x00
    const val OPCODE_RESET: Byte                  = 0x01
    const val OPCODE_SET_TARGET_SPEED: Byte       = 0x02   // 0.01 km/h units (UInt16)
    const val OPCODE_SET_TARGET_INCLINATION: Byte = 0x03   // 0.1 percent grade units (SInt16)
    const val OPCODE_SET_TARGET_RESISTANCE: Byte  = 0x04   // 0.1 level units (UInt8 — 2-byte write)
    const val OPCODE_SET_TARGET_POWER: Byte       = 0x05   // watts (SInt16)
    const val OPCODE_SET_TARGET_HEART_RATE: Byte = 0x06   // bpm (UInt8)
    const val OPCODE_START_RESUME: Byte           = 0x07
    const val OPCODE_STOP_PAUSE: Byte             = 0x08

    // Central-role opcodes added 2026-08-10 with the §9.5.6 upstream simulation work. The
    // provider side has had all four since Phase 0 (`FtmsSpec.kt`); only the central registry
    // was missing, which is the asymmetry §9.5.6 describes.
    const val OPCODE_SET_INDOOR_BIKE_SIMULATION: Byte = 0x11 // wind/grade/crr/cw, 7-byte write
    const val OPCODE_SET_WHEEL_CIRCUMFERENCE: Byte    = 0x12 // 0.1 mm units (UInt16)
    const val OPCODE_SPIN_DOWN_CONTROL: Byte          = 0x13 // calibration, not a target
    const val OPCODE_SET_TARGETED_CADENCE: Byte       = 0x14 // 0.5 /minute units (UInt16)

    // Response framing byte
    // [0]=0x80 (Response Code), [1]=Request OpCode, [2]=Result Code
    const val OPCODE_RESPONSE_CODE: Byte = -0x80 // 0x80 as signed byte

    // Result codes
    const val RESULT_SUCCESS: Byte               = 0x01
    const val RESULT_OPCODE_NOT_SUPPORTED: Byte  = 0x02
    const val RESULT_INVALID_PARAMETER: Byte     = 0x03
    const val RESULT_OPERATION_FAILED: Byte      = 0x04
    const val RESULT_CONTROL_NOT_PERMITTED: Byte = 0x05

    // ─────────────────────────────────────────────────────────────
    // FTMS unit scales for encoding Control Point payloads
    // ─────────────────────────────────────────────────────────────
    /** km/h * 100 → UInt16 */
    const val FTMS_SPEED_SCALE = 100f

    /** degrees * 10 → SInt16 */
    const val FTMS_INCLINE_SCALE = 10f

    /** level * 10 → UInt16 */
    const val FTMS_RESISTANCE_SCALE = 10f

    /** watts * 1 → UInt16 */
    const val FTMS_POWER_SCALE = 1f
}

// ============================================================================
// 2) Flags — FTMS data characteristic flag bitmasks
// ============================================================================
object Flags {

    // ─────────────────────────────────────────────────────────────
    // Treadmill (0x2ACD)
    // ─────────────────────────────────────────────────────────────
    const val TREAD_MOREDATA       = 0x0001
    const val TREAD_AVGSPEED       = 0x0002
    const val TREAD_TOTALDISTANCE  = 0x0004
    const val TREAD_INCLRAMPANGLE  = 0x0008
    const val TREAD_ELEVATIONGAIN  = 0x0010
    const val TREAD_INSTANTPACE    = 0x0020
    const val TREAD_AVGPACE        = 0x0040
    const val TREAD_EXPENERGY      = 0x0080
    const val TREAD_HEARTRATE      = 0x0100
    const val TREAD_METABOLIC      = 0x0200
    const val TREAD_ELAPSEDTIME    = 0x0400
    const val TREAD_REMAININGTIME  = 0x0800
    const val TREAD_BELTFORCEPOWER = 0x1000

    // ─────────────────────────────────────────────────────────────
    // Elliptical (0x2ACE)
    // ─────────────────────────────────────────────────────────────
    const val ELL_MORE        = 0x0001
    const val ELL_AVGSPEED    = 0x0002
    const val ELL_TOTALDIST   = 0x0004
    const val ELL_STEPCOUNT   = 0x0008
    const val ELL_STRIDECOUNT = 0x0010
    const val ELL_ELEVGAIN    = 0x0020
    const val ELL_INCLINATION = 0x0040
    const val ELL_RESISTANCE  = 0x0080
    const val ELL_INSTPOWER   = 0x0100
    const val ELL_AVGPOWER    = 0x0200
    const val ELL_EXPENERGY   = 0x0400
    const val ELL_HEARTRATE   = 0x0800
    const val ELL_METABOLIC   = 0x1000
    const val ELL_ELAPSED     = 0x2000
    const val ELL_REMAINING   = 0x4000

    // ─────────────────────────────────────────────────────────────
    // Rower (0x2AD1)
    // ─────────────────────────────────────────────────────────────
    const val ROW_MORE        = 0x0001
    const val ROW_AVGSTROKE   = 0x0002
    const val ROW_TOTALDIST   = 0x0004
    const val ROW_INSTPACE    = 0x0008
    const val ROW_AVGPACE     = 0x0010
    const val ROW_INSTPOWER   = 0x0020
    const val ROW_AVGPOWER    = 0x0040
    const val ROW_RESISTANCE  = 0x0080
    const val ROW_EXPENERGY   = 0x0100
    const val ROW_HEARTRATE   = 0x0200
    const val ROW_METABOLIC   = 0x0400
    const val ROW_ELAPSED     = 0x0800
    const val ROW_REMAINING   = 0x1000

    // ─────────────────────────────────────────────────────────────
    // Indoor Bike (0x2AD2)
    // ─────────────────────────────────────────────────────────────
    // Spec bit order (FTMS Indoor Bike Data, 0x2AD2):
    //   bit0 More Data, bit1 Average Speed, bit2 Instantaneous Cadence, bit3 Average Cadence, ...
    // NOTE: bits 1 and 2 were previously swapped here. Both the encoder
    // (BtServiceBikeData) and the parser (doBike) emit/consume the fields in the
    // correct spec order (average speed BEFORE cadence), so correcting the two
    // constants fixes both directions with no other change required.
    const val BIKE_MORE       = 0x0001
    const val BIKE_AVGSPEED   = 0x0002
    const val BIKE_CADENCE    = 0x0004
    const val BIKE_AVGCAD     = 0x0008
    const val BIKE_TOTALDIST  = 0x0010
    const val BIKE_RESISTANCE = 0x0020
    const val BIKE_INSTPOWER  = 0x0040
    const val BIKE_AVGPOWER   = 0x0080
    const val BIKE_EXPENERGY  = 0x0100
    const val BIKE_HEARTRATE  = 0x0200
    const val BIKE_METABOLIC  = 0x0400
    const val BIKE_ELAPSED    = 0x0800
    const val BIKE_REMAINING  = 0x1000

    // ─────────────────────────────────────────────────────────────
    // Stepper (0x2ACF)
    // ─────────────────────────────────────────────────────────────
    const val STEP_MORE       = 0x0001
    const val STEP_STEPRATE   = 0x0002
    const val STEP_AVGSTEPR   = 0x0004
    const val STEP_ELEVGAIN   = 0x0008
    const val STEP_EXPENERGY  = 0x0010
    const val STEP_HEARTRATE  = 0x0020
    const val STEP_METABOLIC  = 0x0040
    const val STEP_ELAPSED    = 0x0080
    const val STEP_REMAINING  = 0x0100

    // ─────────────────────────────────────────────────────────────
    // Climber (0x2AD0)
    // ─────────────────────────────────────────────────────────────
    const val CLIMB_MORE      = 0x0001
    const val CLIMB_STEPRATE  = 0x0002
    const val CLIMB_AVGSTEPR  = 0x0004
    const val CLIMB_ELEVGAIN  = 0x0008
    const val CLIMB_STRIDE    = 0x0010
    const val CLIMB_EXPENERGY = 0x0020
    const val CLIMB_HEARTRATE = 0x0040
    const val CLIMB_METABOLIC = 0x0080
    const val CLIMB_ELAPSED   = 0x0100
    const val CLIMB_REMAINING = 0x0200
}

// ============================================================================
// 3) BtServiceProfiles — ancillary service-level enums / constants
//    Shared across BtEngine + btService; NOT Bluetooth IDs themselves
// ============================================================================
object BtServiceProfiles {

    // ─────────────────────────────────────────────────────────────
    // Service modes (two-mode model)
    // ─────────────────────────────────────────────────────────────
    enum class ServiceMode {
        /** Local provider exposes FTMS (0x1826) with FTMS data chars. */
        FTMS,

        /** Local provider exposes CSC (0x1816) + CPS (0x1818) together. */
        CSC_CPS,
    }

    // ─────────────────────────────────────────────────────────────
    // FTMS machine personas (what we emulate on provider side)
    // ─────────────────────────────────────────────────────────────
    enum class FtmsMachineProfile {
        INDOOR_BIKE,
        ELLIPTICAL,
        INDOOR_ROWER,
        STAIR_CLIMBER,
        STAIR_STEPPER,
        TREADMILL,
    }

    // ─────────────────────────────────────────────────────────────
    // CSC+CPS device persona
    // ─────────────────────────────────────────────────────────────
    enum class CscCpsProfile {
        BIKE,
    }

    // ─────────────────────────────────────────────────────────────
    // Device names (advertised local name)
    // ─────────────────────────────────────────────────────────────
    /**
     * Prefix for every advertised local name, following the same convention as
     * TrackMyIndoorWorkout's "TMIW-".
     *
     * The point is attribution on a crowded scan list: a bare "Bike" or
     * "Treadmill" is indistinguishable from a real machine, and because Android
     * publishes the *global adapter name* in the advertisement, an unprefixed
     * persona is easily confused with the phone itself. "FBRG-" makes it obvious
     * which entry is this bridge.
     *
     * Four characters, deliberately: see the budget note on the names below.
     */
    const val DEVICE_NAME_PREFIX: String = "FBRG-"

    const val DEVICE_NAME_FTMS_BIKE: String       = DEVICE_NAME_PREFIX + "Bike"
    const val DEVICE_NAME_FTMS_ELLIPTICAL: String = DEVICE_NAME_PREFIX + "Elliptical"
    const val DEVICE_NAME_FTMS_ROWER: String      = DEVICE_NAME_PREFIX + "Rower"
    const val DEVICE_NAME_FTMS_CLIMBER: String    = DEVICE_NAME_PREFIX + "Climber"
    const val DEVICE_NAME_FTMS_STEPPER: String    = DEVICE_NAME_PREFIX + "Stepper"
    const val DEVICE_NAME_FTMS_TREAD: String      = DEVICE_NAME_PREFIX + "Treadmill"
    const val DEVICE_NAME_CSC_CPS: String         = DEVICE_NAME_FTMS_BIKE

    /**
     * Advertised local name for an FTMS persona.
     *
     * ADVERTISING BUDGET — the reason these names cannot grow. A legacy
     * advertisement is 31 octets and the name costs (2 + length):
     *
     *     3 Flags + (2 + name) + (2 + 2 per 16-bit UUID) + 7 Service Data
     *
     * The longest, "FBRG-Elliptical" at 15 characters, lands on exactly 31/31.
     * Any longer name, or a second advertised UUID on a non-bike persona,
     * overflows and Android rejects the whole advertisement asynchronously.
     * [FtmsAdvertisingPlan] computes this before touching the radio so the
     * failure is deterministic; FtmsAdvertisingPlanTest asserts the budget for
     * every persona.
     *
     * The indoor bike is the only persona that advertises extra UUIDs (CSC and
     * Cycling Power), and "FBRG-Bike" is short enough to absorb them at 29/31.
     */
    fun deviceNameFor(machine: FtmsMachineProfile): String = when (machine) {
        FtmsMachineProfile.INDOOR_BIKE   -> DEVICE_NAME_FTMS_BIKE
        FtmsMachineProfile.ELLIPTICAL    -> DEVICE_NAME_FTMS_ELLIPTICAL
        FtmsMachineProfile.INDOOR_ROWER  -> DEVICE_NAME_FTMS_ROWER
        FtmsMachineProfile.STAIR_CLIMBER -> DEVICE_NAME_FTMS_CLIMBER
        FtmsMachineProfile.STAIR_STEPPER -> DEVICE_NAME_FTMS_STEPPER
        FtmsMachineProfile.TREADMILL     -> DEVICE_NAME_FTMS_TREAD
    }

    // ─────────────────────────────────────────────────────────────
    // FTMS Service Data AD structure (FTMS v1.0 §3.1)
    // ─────────────────────────────────────────────────────────────
    //
    // On air the full structure is six octets:
    //     [len][0x16][UUID lo][UUID hi][Flags][Type lo][Type hi]
    // Android's AdvertiseData.Builder.addServiceData(ParcelUuid, ByteArray)
    // emits the length byte, the 0x16 AD type and the 16-bit UUID itself, so the
    // payload we supply is only the trailing THREE octets: Flags + Machine Type.
    //
    // Flags field (Table 3.2): bit 0 = Fitness Machine Available, bits 1-7 RFU.
    const val FM_FLAG_MACHINE_AVAILABLE: Int = 0x01

    // Fitness Machine Type field (Table 3.3), uint16 little-endian, bits 6-15 RFU.
    //
    // CAUTION: the spec's "Step Climber" (bit 2) is the 0x2ACF Step Climber Data
    // characteristic, which this codebase calls STAIR_STEPPER; the spec's "Stair
    // Climber" (bit 3) is 0x2AD0, which this codebase calls STAIR_CLIMBER. The
    // mapping below is deliberately crossed to match the spec, not the local names.
    const val FM_TYPE_TREADMILL: Int     = 1 shl 0
    const val FM_TYPE_CROSS_TRAINER: Int = 1 shl 1
    const val FM_TYPE_STEP_CLIMBER: Int  = 1 shl 2
    const val FM_TYPE_STAIR_CLIMBER: Int = 1 shl 3
    const val FM_TYPE_ROWER: Int         = 1 shl 4
    const val FM_TYPE_INDOOR_BIKE: Int   = 1 shl 5

    /** Fitness Machine Type bit for an FTMS persona. Exactly one bit is set. */
    fun fitnessMachineTypeFor(machine: FtmsMachineProfile): Int = when (machine) {
        FtmsMachineProfile.TREADMILL     -> FM_TYPE_TREADMILL
        FtmsMachineProfile.ELLIPTICAL    -> FM_TYPE_CROSS_TRAINER
        FtmsMachineProfile.STAIR_STEPPER -> FM_TYPE_STEP_CLIMBER   // 0x2ACF
        FtmsMachineProfile.STAIR_CLIMBER -> FM_TYPE_STAIR_CLIMBER  // 0x2AD0
        FtmsMachineProfile.INDOOR_ROWER  -> FM_TYPE_ROWER
        FtmsMachineProfile.INDOOR_BIKE   -> FM_TYPE_INDOOR_BIKE
    }

    /**
     * Build the 3-octet FTMS Service Data payload for [machine].
     *
     * @param available value of the Fitness Machine Available flag. Pass false to
     *   advertise the service while signalling that no machine is usable yet.
     */
    fun ftmsServiceDataFor(machine: FtmsMachineProfile, available: Boolean = true): ByteArray {
        val type = fitnessMachineTypeFor(machine)
        val flags = if (available) FM_FLAG_MACHINE_AVAILABLE else 0
        return byteArrayOf(
            (flags and 0xFF).toByte(),
            (type and 0xFF).toByte(),
            ((type ushr 8) and 0xFF).toByte(),
        )
    }

    /**
     * Decode the optimistic activity hint carried in FTMS Service Data.
     *
     * Android returns only the three bytes following the 0x1826 UUID:
     * Flags + little-endian Fitness Machine Type. If a device sets multiple
     * known type bits, use the same priority as connected GATT discovery.
     */
    fun activityTypeFromFtmsServiceData(payload: ByteArray?): ActivityType? {
        if (payload == null || payload.size < 3) return null
        val machineTypes = (payload[1].toInt() and 0xFF) or
            ((payload[2].toInt() and 0xFF) shl 8)
        return when {
            machineTypes and FM_TYPE_TREADMILL != 0 -> ActivityType.TREADMILL
            machineTypes and FM_TYPE_ROWER != 0 -> ActivityType.ROWING
            machineTypes and FM_TYPE_INDOOR_BIKE != 0 -> ActivityType.BIKING
            machineTypes and FM_TYPE_CROSS_TRAINER != 0 -> ActivityType.ELLIPTICAL
            machineTypes and FM_TYPE_STEP_CLIMBER != 0 -> ActivityType.STAIRSTEPPER
            machineTypes and FM_TYPE_STAIR_CLIMBER != 0 -> ActivityType.STAIRCLIMBER
            else -> null
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Connection / timing
    // ─────────────────────────────────────────────────────────────
    /**
     * Max number of concurrent GATT clients we will serve.
     * This is non-spec (spec doesn’t limit us) but keeps memory and per-client state sane.
     */
    const val MAX_CLIENTS: Int = 3

    /**
     * Default notify interval used by BtServiceNotifier, in ms.
     * 1 Hz is good enough for most FTMS consumers (Zwift, 3rd party apps, etc.).
     */
    const val DEFAULT_NOTIFY_INTERVAL_MS: Long = 333L

    // ─────────────────────────────────────────────────────────────
    // FTMS Feature presets (0x2ACC FMF bitfields)
    // ─────────────────────────────────────────────────────────────
    //
    // Bluetooth SIG spec — Fitness Machine Feature field (uint32, little endian):
    //      bit  0: Average Speed Supported
    //      bit  1: Cadence Supported
    //      bit  2: Total Distance Supported
    //      bit  3: Inclination Supported
    //      bit  4: Elevation Gain Supported
    //      bit  5: Pace Supported
    //      bit  6: Step Count Supported
    //      bit  7: Resistance Level Supported
    //      bit  8: Stride Count Supported
    //      bit  9: Expended Energy Supported
    //      bit 10: Heart Rate Measurement Supported
    //      bit 11: Metabolic Equivalent Supported
    //      bit 12: Elapsed Time Supported
    //      bit 13: Remaining Time Supported
    //      bit 14: Power Measurement Supported
    //      bit 15: Force on Belt and Power Output Supported
    //      bit 16: User Data Retention Supported
    //
    // IMPORTANT: this field does NOT carry machine type. An earlier revision of
    // this file encoded "Treadmill / Cross Trainer / Stepper / Rower / Indoor Bike"
    // into bits 8..12, which actually mean Stride Count, Expended Energy, Heart Rate,
    // Metabolic Equivalent and Elapsed Time. Machine type belongs in the FTMS
    // Service Data AD structure of the advertisement (Flags uint8 + Fitness Machine
    // Type uint16), which this build does not yet emit.
    //
    //   Target Setting Feature (uint32, little endian):
    //      bit  0: Speed Target Setting          bit  8: Targeted Distance
    //      bit  1: Inclination Target Setting    bit  9: Targeted Training Time
    //      bit  2: Resistance Target Setting     bit 10..12: Targeted Time in HR Zones
    //      bit  3: Power Target Setting          bit 13: Indoor Bike Simulation Params
    //      bit  4: Heart Rate Target Setting     bit 14: Wheel Circumference
    //      bit  5: Targeted Expended Energy      bit 15: Spin Down Control
    //      bit  6: Targeted Step Number          bit 16: Targeted Cadence
    //      bit  7: Targeted Stride Number
    //    TSF is built at runtime in BtServiceAdvertiser.buildFitnessMachineFeature()
    //    from the upstream machine's real capabilities.
    //
    // Each FMF map below is derived from the fields the corresponding
    // BtService*Data encoder actually emits. Keep them in sync when an encoder
    // gains or loses a field, otherwise a strict client may discard data we send.

    private infix fun Int.b(bit: Int): Int = this or (1 shl bit)

    // Fields every encoder emits. Total distance is NOT here: the Stair Climber
    // (0x2AD0) and Step Climber (0x2ACF) data characteristics have no distance
    // field at all, so those personas must not claim bit 2.
    private val BASE_FMF: Int =
        0
            .b(9)   // expended energy
            .b(10)  // heart rate measurement
            .b(11)  // metabolic equivalent
            .b(12)  // elapsed time

    /** TREADMILL — see BtServiceTreadmillData.build(). */
    val FMF_TREADMILL: Int =
        BASE_FMF
            .b(0)   // average speed
            .b(2)   // total distance
            .b(3)   // inclination (incl + ramp angle field)
            .b(4)   // elevation gain
            .b(5)   // pace (instantaneous + average)
            .b(15)  // force on belt and power output

    /** INDOOR BIKE — see BtServiceBikeData.build(). */
    val FMF_INDOOR_BIKE: Int =
        BASE_FMF
            .b(0)   // average speed
            .b(1)   // cadence (instantaneous + average)
            .b(2)   // total distance
            .b(7)   // resistance level
            .b(14)  // power measurement (instantaneous + average)

    /** ELLIPTICAL / CROSS TRAINER — see BtServiceEllipticalData.build(). */
    val FMF_ELLIPTICAL: Int =
        BASE_FMF
            .b(0)   // average speed
            .b(2)   // total distance
            .b(3)   // inclination (incl + ramp angle field)
            .b(4)   // elevation gain
            .b(6)   // step count
            .b(7)   // resistance level
            .b(8)   // stride count
            .b(14)  // power measurement (instantaneous + average)

    /** INDOOR ROWER — see BtServiceRowerData.build(). */
    val FMF_ROWER: Int =
        BASE_FMF
            .b(1)   // cadence (stroke rate, instantaneous + average)
            .b(2)   // total distance
            .b(5)   // pace (instantaneous + average)
            .b(7)   // resistance level
            .b(14)  // power measurement (instantaneous + average)

    /**
     * STAIR CLIMBER (0x2AD0) — see BtServiceStairClimberData.build().
     *
     * Note: Step per Minute and Average Step Rate map to **Step Count Supported
     * (bit 6)**, not Cadence (bit 1) — see FTMS v1.0 Table 4.8. Only Indoor Bike
     * and Rower use the Cadence bit.
     */
    val FMF_STAIR_CLIMBER: Int =
        BASE_FMF
            .b(4)   // elevation gain
            .b(6)   // step count (also covers step rate / average step rate)
            .b(8)   // stride count

    /**
     * STAIR STEPPER / Step Climber (0x2ACF) — see BtServiceStairStepper.build().
     *
     * Same Step Count mapping as the Stair Climber — see FTMS v1.0 Table 4.7.
     */
    val FMF_STAIR_STEPPER: Int =
        BASE_FMF
            .b(4)   // elevation gain
            .b(6)   // step count (also covers step rate / average step rate)

    /**
     * Empty Target Setting Features word — the starting value that
     * [BtServiceAdvertiser.buildFitnessMachineFeature] ORs the upstream machine's
     * real control capabilities into. It is not the final TSF value.
     */
    const val TSF_NONE: Int = 0x0000_0000

    /**
     * Helper to pick FMF bitmap by FTMS machine persona.
     */
    fun fmfFor(machine: FtmsMachineProfile): Int = when (machine) {
        FtmsMachineProfile.INDOOR_BIKE   -> FMF_INDOOR_BIKE
        FtmsMachineProfile.ELLIPTICAL    -> FMF_ELLIPTICAL
        FtmsMachineProfile.INDOOR_ROWER  -> FMF_ROWER
        FtmsMachineProfile.STAIR_CLIMBER -> FMF_STAIR_CLIMBER
        FtmsMachineProfile.STAIR_STEPPER -> FMF_STAIR_STEPPER
        FtmsMachineProfile.TREADMILL     -> FMF_TREADMILL
    }

    // ─────────────────────────────────────────────────────────────
    // Activity / profile / UUID glue (shared engine + service)
    // ─────────────────────────────────────────────────────────────

    /**
     * Map ActivityType (app-facing) to an FTMS machine persona used by
     * the provider side.
     */
    fun ftmsProfileForActivity(type: ActivityType): FtmsMachineProfile? = when (type) {
        ActivityType.TREADMILL    -> FtmsMachineProfile.TREADMILL
        ActivityType.ELLIPTICAL   -> FtmsMachineProfile.ELLIPTICAL
        ActivityType.ROWING       -> FtmsMachineProfile.INDOOR_ROWER
        ActivityType.STAIRCLIMBER -> FtmsMachineProfile.STAIR_CLIMBER
        ActivityType.STAIRSTEPPER -> FtmsMachineProfile.STAIR_STEPPER
        ActivityType.BIKING       -> FtmsMachineProfile.INDOOR_BIKE
        else -> null
    }

    /**
     * Map an FTMS machine persona to its primary FTMS data characteristic UUID.
     */
    fun dataUuidForProfile(profile: FtmsMachineProfile): UUID = when (profile) {
        FtmsMachineProfile.TREADMILL     -> BluetoothIds.UUID_TREADMILL_DATA
        FtmsMachineProfile.ELLIPTICAL    -> BluetoothIds.UUID_ELLIPTICAL_DATA
        FtmsMachineProfile.INDOOR_ROWER  -> BluetoothIds.UUID_ROWING_MACHINE_DATA
        FtmsMachineProfile.STAIR_CLIMBER -> BluetoothIds.UUID_STAIR_CLIMBER_DATA
        FtmsMachineProfile.STAIR_STEPPER -> BluetoothIds.UUID_STAIR_STEPPER_DATA
        FtmsMachineProfile.INDOOR_BIKE   -> BluetoothIds.UUID_BIKING_DATA
    }

    /**
     * Convenience: ActivityType → FTMS data characteristic UUID.
     */
    fun dataUuidForActivity(type: ActivityType): UUID? =
        ftmsProfileForActivity(type)?.let { dataUuidForProfile(it) }
}

// ============================================================================
// 4) Byte helpers (little-endian encoders)
// ============================================================================
fun u16le(v: Int): ByteArray =
    byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())

fun s16le(v: Int): ByteArray {
    val sv = v.toShort()
    return byteArrayOf(
        (sv.toInt() and 0xFF).toByte(),
        ((sv.toInt() ushr 8) and 0xFF).toByte(),
    )
}

fun u24le(v: Int): ByteArray =
    byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
    )

fun byteOf(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte())
