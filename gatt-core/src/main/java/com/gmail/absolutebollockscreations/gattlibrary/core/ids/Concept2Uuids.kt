package com.gmail.absolutebollockscreations.gattlibrary.core.ids

import java.util.UUID

/**
 * Concept2 PM5 proprietary GATT identifiers (base `CE06xxxx-43E5-11E4-916C-0800200C9A66`). Provenance is in each member comment.
 *
 * Moved from central `BluetoothIds` (LIB-19b); names unchanged.
 */
public object Concept2Uuids {
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

    public val UUID_C2_DISCOVERY_SERVICE: UUID    = concept2Uuid(0x0000)

    public val UUID_C2_DEVICE_INFO_SERVICE: UUID  = concept2Uuid(0x0010)
    public val UUID_C2_MODEL_NUMBER: UUID         = concept2Uuid(0x0011)
    public val UUID_C2_SERIAL_NUMBER: UUID        = concept2Uuid(0x0012)
    public val UUID_C2_HARDWARE_REVISION: UUID    = concept2Uuid(0x0013)
    public val UUID_C2_FIRMWARE_REVISION: UUID    = concept2Uuid(0x0014)
    public val UUID_C2_MANUFACTURER_NAME: UUID    = concept2Uuid(0x0015)
    public val UUID_C2_MACHINE_TYPE: UUID         = concept2Uuid(0x0016)
    public val UUID_C2_ATT_RECEIVE_MTU: UUID      = concept2Uuid(0x0017)
    public val UUID_C2_LL_MAX_TX_RX_BYTES: UUID   = concept2Uuid(0x0018)

    public val UUID_C2_PM_CONTROL_SERVICE: UUID   = concept2Uuid(0x0020)
    public val UUID_C2_CSAFE_REQUEST: UUID        = concept2Uuid(0x0021) // write, up to 20 B
    public val UUID_C2_CSAFE_RESPONSE: UUID       = concept2Uuid(0x0022) // read + notify for ErgData compatibility

    public val UUID_C2_ERG_SERVICE: UUID          = concept2Uuid(0x0030)

    public val UUID_C2_GENERAL_STATUS: UUID          = concept2Uuid(0x0031) // notify, 19 B
    public val UUID_C2_ADDITIONAL_STATUS_1: UUID     = concept2Uuid(0x0032) // notify, 17 B
    public val UUID_C2_ADDITIONAL_STATUS_2: UUID     = concept2Uuid(0x0033) // notify, 20 B
    public val UUID_C2_STATUS_SAMPLE_RATE: UUID      = concept2Uuid(0x0034) // read/write, 1 B
    public val UUID_C2_STROKE_DATA: UUID             = concept2Uuid(0x0035) // notify, 20 B
    public val UUID_C2_ADDITIONAL_STROKE_DATA: UUID  = concept2Uuid(0x0036) // notify, 15 B
    public val UUID_C2_SPLIT_INTERVAL_DATA: UUID     = concept2Uuid(0x0037) // notify, 18 B
    public val UUID_C2_ADDITIONAL_SPLIT_INTERVAL: UUID = concept2Uuid(0x0038) // notify, 19 B
    public val UUID_C2_WORKOUT_SUMMARY: UUID         = concept2Uuid(0x0039) // notify, 20 B
    public val UUID_C2_ADDITIONAL_WORKOUT_SUMMARY: UUID = concept2Uuid(0x003A) // notify, 19 B
    public val UUID_C2_HEART_RATE_BELT_INFO: UUID    = concept2Uuid(0x003B) // notify, 6 B
    public val UUID_C2_ADDITIONAL_WORKOUT_SUMMARY_2: UUID = concept2Uuid(0x003C) // notify, 10 B
    public val UUID_C2_FORCE_CURVE_DATA: UUID        = concept2Uuid(0x003D) // notify, 2..20 B
    public val UUID_C2_ADDITIONAL_STATUS_3: UUID      = concept2Uuid(0x003E) // notify, 12 B
    public val UUID_C2_LOGGED_WORKOUT: UUID           = concept2Uuid(0x003F) // notify, 15 B

    public val UUID_C2_HEART_RATE_RECEIVE_SERVICE: UUID = concept2Uuid(0x0040)
    public val UUID_C2_HEART_RATE_RECEIVE: UUID         = concept2Uuid(0x0041) // write, 20 B

    /**
     * Multiplexed information, up to 20 B: an identification byte followed by that ID's
     * firmware-specific payload. Several multiplexed layouts deliberately differ from their
     * individual characteristic (D41).
     */
    public val UUID_C2_MULTIPLEXED_INFORMATION: UUID = concept2Uuid(0x0080)
}
