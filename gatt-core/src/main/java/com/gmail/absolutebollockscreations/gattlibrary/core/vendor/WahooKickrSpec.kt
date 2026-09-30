package com.gmail.absolutebollockscreations.gattlibrary.core.vendor

import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi

/**
 * Legacy Wahoo KICKR control opcodes (written to the KICKR control characteristic under Cycling Power) and the protocol ranges of their parameters. Timeouts and retry limits are central policy and stay there.
 *
 * Provenance: the values below were inherited from external projects named in the member comments and are used as published there; those projects report working hardware behavior, and FitBridge treats them as correct on that feedback. FitBridge has not independently verified a value unless its comment says so.
 */
@GattLibraryInternalApi
public object WahooKickrSpec {
    public const val OPCODE_UNLOCK: Int = 0x20

    public const val OPCODE_RESISTANCE: Int = 0x40

    public const val OPCODE_STANDARD_LEVEL: Int = 0x41

    public const val OPCODE_ERG: Int = 0x42

    public const val OPCODE_SIMULATION: Int = 0x43

    public const val OPCODE_ROLLING_RESISTANCE: Int = 0x44

    public const val OPCODE_WIND_RESISTANCE: Int = 0x45

    public const val OPCODE_GRADE: Int = 0x46

    public const val OPCODE_WIND_SPEED: Int = 0x47

    public const val OPCODE_WHEEL_CIRCUMFERENCE: Int = 0x48

    public const val MIN_STANDARD_LEVEL: Int = 1

    public const val MAX_STANDARD_LEVEL: Int = 9

    public const val MIN_ERG_WATTS: Int = 0

    public const val MAX_ERG_WATTS: Int = 2_000

    public const val MAX_TOTAL_MASS_KG: Double = 327.67

    public const val MAX_CRR: Double = 0.0255

    public const val MAX_CW: Double = 2.55

    public const val MAX_WHEEL_CIRCUMFERENCE_MM: Double = 6_553.5
}
