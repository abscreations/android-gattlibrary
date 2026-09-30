package com.gmail.absolutebollockscreations.gattlibrary.core.ids

import java.util.UUID

/**
 * ANT+ FE-C over BLE (Tacx transport) identifiers plus the ANT serial envelope, data page and equipment-type vocabulary. Provenance is in each member comment.
 *
 * Moved from central `BluetoothIds` (LIB-19b); names unchanged.
 */
public object FecBleUuids {
    // ─────────────────────────────────────────────────────────────
    // FE-C over BLE (Tacx transport; ANT+ FE-C carried over a Nordic-UART-derived service)
    // ─────────────────────────────────────────────────────────────
    /**
     * FE-C over BLE primary service, per `docs/How-to FE-C over BLE v1_0_0.pdf`.
     *
     * **Generic FE-C-over-BLE protocol identity, not a Tacx-model-specific one.** Page 16 of the
     * ANT+ FE-C payload declares equipment type, so one GATT surface serves all seven defined
     * equipment personas without a model-specific driver. See
     * `docs/fe-c-ble_simulator_implementation.md` and `docs/fe-c-ble_central_implementation.md`.
     */
    public val UUID_FEC_SERVICE: UUID =
        UUID.fromString("6E40FEC1-B5A3-F393-E0A9-E50E24DCCA9E")

    /** Notify + CCCD. Machine -> central: one complete ANT serial message per notification. */
    public val UUID_FEC_MEASUREMENT: UUID =
        UUID.fromString("6E40FEC2-B5A3-F393-E0A9-E50E24DCCA9E")

    /** Write without response. Central -> machine: FE-C control pages 48/49/50/51/55. */
    public val UUID_FEC_CONTROL: UUID =
        UUID.fromString("6E40FEC3-B5A3-F393-E0A9-E50E24DCCA9E")

    // ANT serial envelope (sync + length + msg-id + channel + 8 page bytes + XOR checksum).
    public const val FEC_ANT_SYNC: Int = 0xA4
    public const val FEC_ANT_MSG_BROADCAST_DATA: Int = 0x4E
    public const val FEC_ANT_MSG_ACKNOWLEDGED_DATA: Int = 0x4F
    public const val FEC_DEFAULT_CHANNEL: Int = 0x05

    // FE-C data pages (machine -> central).
    public const val FEC_PAGE_GENERAL_FE_DATA: Int = 0x10
    public const val FEC_PAGE_GENERAL_SETTINGS: Int = 0x11
    public const val FEC_PAGE_GENERAL_METABOLIC_DATA: Int = 0x12
    public const val FEC_PAGE_SPECIFIC_TREADMILL_DATA: Int = 0x13
    public const val FEC_PAGE_SPECIFIC_ELLIPTICAL_DATA: Int = 0x14
    public const val FEC_PAGE_SPECIFIC_ROWER_DATA: Int = 0x16
    public const val FEC_PAGE_SPECIFIC_CLIMBER_DATA: Int = 0x17
    public const val FEC_PAGE_SPECIFIC_NORDIC_SKIER_DATA: Int = 0x18
    public const val FEC_PAGE_SPECIFIC_TRAINER_DATA: Int = 0x19
    public const val FEC_PAGE_SPECIFIC_TRAINER_TORQUE_DATA: Int = 0x1A
    public const val FEC_PAGE_FE_CAPABILITIES: Int = 0x36
    public const val FEC_PAGE_MANUFACTURER_INFORMATION: Int = 0x50
    public const val FEC_PAGE_PRODUCT_INFORMATION: Int = 0x51

    // FE-C control pages (central -> machine).
    public const val FEC_PAGE_BASIC_RESISTANCE: Int = 0x30
    public const val FEC_PAGE_TARGET_POWER: Int = 0x31
    public const val FEC_PAGE_WIND_RESISTANCE: Int = 0x32
    public const val FEC_PAGE_TRACK_RESISTANCE: Int = 0x33
    public const val FEC_PAGE_USER_CONFIGURATION: Int = 0x37
    public const val FEC_PAGE_REQUEST_DATA_PAGE: Int = 0x46

    /**
     * Page 16 byte 1, low 5 bits. The simulator emits all seven defined equipment personas, and
     * the central side must likewise decode whatever a real machine reports. See
     * `docs/fe-c-ble_central_implementation.md` §2.4/§9.
     *
     * Per the official ANT+ FE-C spec (D000001231 Rev 5.0, Table 8-7), wire value 21 is
     * "Reserved - Do Not Use" for this field, NOT Stationary Bike: Stationary Bike shares
     * equipment type 25 with Trainer (the spec's own wording is "Trainer/StationaryBike"), and
     * a stationary bike's specific data is required to use data page 25 (Specific Trainer Data)
     * too - data page 21 (the *page*, a different "21" from this equipment-type byte) is
     * separately deprecated/reserved for Stationary Bike and shall not be transmitted (§8.6.3).
     * [FEC_EQUIPMENT_STATIONARY_BIKE] previously used the reserved value 21 here, which is
     * itself a spec violation independent of that deprecated page - fixed to the shared wire
     * value 25 discovered while implementing spec-accurate pages 19/22.
     */
    public const val FEC_EQUIPMENT_TREADMILL: Int = 19
    public const val FEC_EQUIPMENT_ELLIPTICAL: Int = 20
    public const val FEC_EQUIPMENT_RESERVED_21: Int = 21
    public const val FEC_EQUIPMENT_ROWER: Int = 22
    public const val FEC_EQUIPMENT_CLIMBER: Int = 23
    public const val FEC_EQUIPMENT_NORDIC_SKIER: Int = 24
    public const val FEC_EQUIPMENT_TRAINER: Int = 25

    /** Shares [FEC_EQUIPMENT_TRAINER]'s wire value 25 - see the class doc above. */
    public const val FEC_EQUIPMENT_STATIONARY_BIKE: Int = FEC_EQUIPMENT_TRAINER
}
