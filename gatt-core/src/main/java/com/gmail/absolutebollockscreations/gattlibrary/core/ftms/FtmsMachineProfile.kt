package com.gmail.absolutebollockscreations.gattlibrary.core.ftms

/**
 * The six FTMS machine kinds this stack models, one per machine-data characteristic.
 *
 * Moved from central's `BtServiceProfiles.FtmsMachineProfile` (LIB-19a).
 */
public enum class FtmsMachineProfile {
    INDOOR_BIKE,
    ELLIPTICAL,
    INDOOR_ROWER,
    STAIR_CLIMBER,
    STAIR_STEPPER,
    TREADMILL,
}

/**
 * FTMS Service Data advertising structure (FTMS v1.0 section 3.1).
 *
 * On air the structure is `[len][0x16][UUID lo][UUID hi][Flags][Type lo][Type hi]`; Android adds
 * the length, AD type and UUID itself, so the payload is only Flags plus the machine-type field.
 */
public object FtmsMachineType {
    /** Flags field (Table 3.2): bit 0 = Fitness Machine Available, bits 1-7 RFU. */
    public const val FM_FLAG_MACHINE_AVAILABLE: Int = 0x01

    // Fitness Machine Type field (Table 3.3), uint16 little-endian, bits 6-15 RFU.
    //
    // CAUTION: the spec's "Step Climber" (bit 2) is the 0x2ACF Step Climber Data characteristic,
    // which this codebase calls STAIR_STEPPER; the spec's "Stair Climber" (bit 3) is 0x2AD0,
    // which this codebase calls STAIR_CLIMBER. The mapping is deliberately crossed to match the
    // spec, not the local names.
    public const val FM_TYPE_TREADMILL: Int = 1 shl 0
    public const val FM_TYPE_CROSS_TRAINER: Int = 1 shl 1
    public const val FM_TYPE_STEP_CLIMBER: Int = 1 shl 2
    public const val FM_TYPE_STAIR_CLIMBER: Int = 1 shl 3
    public const val FM_TYPE_ROWER: Int = 1 shl 4
    public const val FM_TYPE_INDOOR_BIKE: Int = 1 shl 5

    /** Fitness Machine Type bit for [machine]. Exactly one bit is set. */
    public fun fitnessMachineTypeFor(machine: FtmsMachineProfile): Int = when (machine) {
        FtmsMachineProfile.TREADMILL -> FM_TYPE_TREADMILL
        FtmsMachineProfile.ELLIPTICAL -> FM_TYPE_CROSS_TRAINER
        FtmsMachineProfile.STAIR_STEPPER -> FM_TYPE_STEP_CLIMBER // 0x2ACF
        FtmsMachineProfile.STAIR_CLIMBER -> FM_TYPE_STAIR_CLIMBER // 0x2AD0
        FtmsMachineProfile.INDOOR_ROWER -> FM_TYPE_ROWER
        FtmsMachineProfile.INDOOR_BIKE -> FM_TYPE_INDOOR_BIKE
    }
}
