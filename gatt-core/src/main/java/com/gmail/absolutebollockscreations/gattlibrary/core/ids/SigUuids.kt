package com.gmail.absolutebollockscreations.gattlibrary.core.ids

import java.util.UUID

/**
 * Bluetooth SIG Assigned Numbers used by the supported sensors and machines: Device Information 0x180A, Heart Rate 0x180D,
 * Battery 0x180F, Running Speed and Cadence 0x1814, Cycling Speed and Cadence 0x1816, Cycling Power 0x1818,
 * Glucose 0x1808, User Data 0x181C, Bond Management 0x181E, and the CCCD descriptor 0x2902, with their characteristics.
 * FTMS lives in [com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids].
 *
 * Moved from central `BluetoothIds` (LIB-19b); names unchanged.
 */
public object SigUuids {
    // Additional standard services present in every captured legacy Life Fitness console.
    public val UUID_DEVICE_INFORMATION_SERVICE: UUID = shortUuid(0x180A)
    public val UUID_BOND_MANAGEMENT_SERVICE: UUID = shortUuid(0x181E)
    public val UUID_BOND_MANAGEMENT_CONTROL_POINT: UUID = shortUuid(0x2AA4)
    public val UUID_BOND_MANAGEMENT_FEATURE: UUID = shortUuid(0x2AA5)
    public val UUID_MANUFACTURER_NAME: UUID = shortUuid(0x2A29)
    public val UUID_MODEL_NUMBER: UUID = shortUuid(0x2A24)
    public val UUID_SERIAL_NUMBER: UUID = shortUuid(0x2A25)
    public val UUID_HARDWARE_REVISION: UUID = shortUuid(0x2A27)
    public val UUID_FIRMWARE_REVISION: UUID = shortUuid(0x2A26)
    public val UUID_SOFTWARE_REVISION: UUID = shortUuid(0x2A28)
    public val UUID_PNP_ID: UUID = shortUuid(0x2A50)
    public val UUID_USER_DATA_SERVICE: UUID = shortUuid(0x181C)

    // ─────────────────────────────────────────────────────────────
    // Heart Rate (Service 0x180D)
    // ─────────────────────────────────────────────────────────────
    public val UUID_HRM_SERVICE: UUID         = shortUuid(0x180D)

    public val UUID_HEART_RATE_MEASUREMENT: UUID   = shortUuid(0x2A37) // notify
    public val UUID_BODY_SENSOR_LOCATION: UUID     = shortUuid(0x2A38) // read
    public val UUID_HEART_RATE_CONTROL_POINT: UUID = shortUuid(0x2A39) // write (optional)

    // ─────────────────────────────────────────────────────────────
    // Cycling Speed & Cadence (Service 0x1816)
    // ─────────────────────────────────────────────────────────────
    public val UUID_CSC_SERVICE: UUID         = shortUuid(0x1816)

    // CSC Characteristics
    public val UUID_CSC_MEASUREMENT: UUID  = shortUuid(0x2A5B) // notify
    public val UUID_CSC_FEATURE: UUID      = shortUuid(0x2A5C) // read
    public val UUID_SENSOR_LOCATION: UUID  = shortUuid(0x2A5D) // read (shared by CSC & CPS)
    public val UUID_SC_CONTROL_POINT: UUID = shortUuid(0x2A55) // write / indicate

    // ─────────────────────────────────────────────────────────────
    // Battery (Service 0x180F)
    // ─────────────────────────────────────────────────────────────
    // Present on the captured Stryd footpods, which notify the level rather than
    // only exposing it for reading.
    public val UUID_BATTERY_SERVICE: UUID = shortUuid(0x180F)
    public val UUID_BATTERY_LEVEL: UUID   = shortUuid(0x2A19) // read / notify

    // ─────────────────────────────────────────────────────────────
    // Running Speed & Cadence (Service 0x1814)
    // ─────────────────────────────────────────────────────────────
    public val UUID_RSC_SERVICE: UUID         = shortUuid(0x1814)
    public val UUID_RSC_MEASUREMENT: UUID     = shortUuid(0x2A53) // notify
    public val UUID_RSC_FEATURE: UUID         = shortUuid(0x2A54) // read
    // UUID_SENSOR_LOCATION (0x2A5D) and UUID_SC_CONTROL_POINT (0x2A55) are shared above.

    // ─────────────────────────────────────────────────────────────
    // Cycling Power (Service 0x1818)
    // ─────────────────────────────────────────────────────────────
    public val UUID_POWER_SERVICE: UUID        = shortUuid(0x1818)

    // Alias for clarity on service side
    public val UUID_CYCLING_POWER_SERVICE: UUID         = UUID_POWER_SERVICE

    // CPS Characteristics
    public val UUID_CYCLING_POWER_MEASUREMENT: UUID   = shortUuid(0x2A63) // notify
    public val UUID_CYCLING_POWER_FEATURE: UUID       = shortUuid(0x2A65) // read
    public val UUID_CYCLING_POWER_CONTROL_POINT: UUID = shortUuid(0x2A66) // write / indicate

    // ─────────────────────────────────────────────────────────────
    // User Data Service characteristics (SIG 0x181C), written during Life Fitness setup
    // ─────────────────────────────────────────────────────────────
    /**
     * **These are ordinary SIG characteristics and they carry the personal data**, which is the
     * part of Phase 6 easiest to get wrong. The seven proprietary Life Fitness UUIDs are control
     * plumbing and vendor tokens; a user's name, email, age, gender, weight and height go to
     * these standard ones on the Life Fitness user service.
     *
     * A redaction registry that covers only the proprietary UUIDs would therefore protect the
     * tokens and publish the person. All of them are registered in `SensitiveCharacteristics`.
     */
    public val UUID_USER_FIRST_NAME: UUID = shortUuid(0x2A8A)
    public val UUID_USER_LAST_NAME: UUID  = shortUuid(0x2A90)
    public val UUID_USER_EMAIL: UUID      = shortUuid(0x2A87)
    public val UUID_USER_AGE: UUID        = shortUuid(0x2A80)
    public val UUID_USER_GENDER: UUID     = shortUuid(0x2A8C)
    public val UUID_USER_LANGUAGE: UUID   = shortUuid(0x2AA2)
    public val UUID_USER_WEIGHT: UUID     = shortUuid(0x2A98)
    public val UUID_USER_HEIGHT: UUID     = shortUuid(0x2A8E)
    public val UUID_USER_DATE_OF_BIRTH: UUID = shortUuid(0x2A85)
    public val UUID_USER_VO2_MAX: UUID = shortUuid(0x2A96)
    public val UUID_USER_MAX_RECOMMENDED_HEART_RATE: UUID = shortUuid(0x2A91)

    /** Every user-data characteristic the Life Fitness transcript writes, in transcript order. */
    public val USER_PROFILE_CHARACTERISTICS: List<UUID> = listOf(
        UUID_USER_FIRST_NAME,
        UUID_USER_LAST_NAME,
        UUID_USER_EMAIL,
        UUID_USER_AGE,
        UUID_USER_GENDER,
        UUID_USER_LANGUAGE,
        UUID_USER_WEIGHT,
        UUID_USER_HEIGHT,
    )

    // ─────────────────────────────────────────────────────────────
    // Glucose (Service 0x1808)
    // ─────────────────────────────────────────────────────────────
    public val UUID_GLUCOSE_SERVICE: UUID         = shortUuid(0x1808)

    // ─────────────────────────────────────────────────────────────
    // Descriptors
    // ─────────────────────────────────────────────────────────────
    /** Client Characteristic Configuration Descriptor (CCCD) */
    public val UUID_CCCD: UUID = shortUuid(0x2902)
}
