package com.gmail.absolutebollockscreations.gattlibrary.core.ftms

import java.util.UUID

/**
 * Fitness Machine Service (0x1826) and its characteristic UUIDs, from the Bluetooth SIG Assigned
 * Numbers and *Fitness Machine Service v1.0*.
 *
 * Every FTMS client, server and simulator uses these; the values are protocol facts. The
 * Android `ParcelUuid` wrapper used for scan filters stays in central (core carries no Android
 * types). Moved from central's `BluetoothIds` (LIB-19a).
 */
public object FtmsUuids {
    private fun shortUuid(hex: Int): UUID =
        UUID.fromString(String.format("0000%04X-0000-1000-8000-00805F9B34FB", hex))

    /** Fitness Machine Service. */
    public val UUID_FTMS_SERVICE: UUID = shortUuid(0x1826)

    // Core characteristics
    public val UUID_FITNESS_MACHINE_FEATURE: UUID = shortUuid(0x2ACC)
    public val UUID_FITNESS_MACHINE_CONTROL_POINT: UUID = shortUuid(0x2AD9)
    public val UUID_FITNESS_MACHINE_STATUS: UUID = shortUuid(0x2ADA)

    /** Training Status (0x2AD3), Read + Notify. */
    public val UUID_TRAINING_STATUS: UUID = shortUuid(0x2AD3)

    // Machine data characteristics
    public val UUID_TREADMILL_DATA: UUID = shortUuid(0x2ACD)
    public val UUID_ELLIPTICAL_DATA: UUID = shortUuid(0x2ACE)
    public val UUID_STAIR_STEPPER_DATA: UUID = shortUuid(0x2ACF)
    public val UUID_STAIR_CLIMBER_DATA: UUID = shortUuid(0x2AD0)
    public val UUID_ROWING_MACHINE_DATA: UUID = shortUuid(0x2AD1)
    public val UUID_BIKING_DATA: UUID = shortUuid(0x2AD2)

    // Supported range characteristics
    public val UUID_SUPPORTED_SPEED_RANGE: UUID = shortUuid(0x2AD4)
    public val UUID_SUPPORTED_INCLINATION_RANGE: UUID = shortUuid(0x2AD5)
    public val UUID_SUPPORTED_RESISTANCE_RANGE: UUID = shortUuid(0x2AD6)
    public val UUID_SUPPORTED_HEART_RATE_RANGE: UUID = shortUuid(0x2AD7)
    public val UUID_SUPPORTED_POWER_RANGE: UUID = shortUuid(0x2AD8)

    /** Primary machine-data characteristic for [profile]. */
    public fun dataUuidForProfile(profile: FtmsMachineProfile): UUID = when (profile) {
        FtmsMachineProfile.TREADMILL -> UUID_TREADMILL_DATA
        FtmsMachineProfile.ELLIPTICAL -> UUID_ELLIPTICAL_DATA
        FtmsMachineProfile.INDOOR_ROWER -> UUID_ROWING_MACHINE_DATA
        FtmsMachineProfile.STAIR_CLIMBER -> UUID_STAIR_CLIMBER_DATA
        FtmsMachineProfile.STAIR_STEPPER -> UUID_STAIR_STEPPER_DATA
        FtmsMachineProfile.INDOOR_BIKE -> UUID_BIKING_DATA
    }
}
