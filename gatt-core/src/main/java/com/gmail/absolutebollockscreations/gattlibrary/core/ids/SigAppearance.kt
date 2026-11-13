package com.gmail.absolutebollockscreations.gattlibrary.core.ids

/**
 * Bluetooth SIG GAP Appearance values (Assigned Numbers, Appearance Values, category Cycling 0x0480) that
 * FitBridge uses to infer a CSC sensor's roles from its advertisement before its feature word is read.
 *
 * Moved from central's top-level `APPEARANCE_CYCLING_*` constants (LIB-80, L23: SIG facts live in core).
 */
public object SigAppearance {
    /** Cycling: Speed Sensor. */
    public const val CYCLING_SPEED_SENSOR: Int = 0x0482

    /** Cycling: Cadence Sensor. */
    public const val CYCLING_CADENCE_SENSOR: Int = 0x0483

    /** Cycling: Speed and Cadence Sensor. */
    public const val CYCLING_SPEED_CADENCE_SENSOR: Int = 0x0485
}
