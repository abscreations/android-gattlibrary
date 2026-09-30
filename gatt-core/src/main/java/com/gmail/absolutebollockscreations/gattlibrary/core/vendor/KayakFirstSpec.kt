package com.gmail.absolutebollockscreations.gattlibrary.core.vendor

import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi

/**
 * Kayak First console constants: default boat mass, default handle pull length and the E2 4.2.0 flywheel/drag model constants.
 *
 * Provenance: the values below were inherited from external projects named in the member comments and are used as published there; those projects report working hardware behavior, and FitBridge treats them as correct on that feedback. FitBridge has not independently verified a value unless its comment says so.
 */
@GattLibraryInternalApi
public object KayakFirstSpec {
    /** Boat mass sent when nothing is configured; equals the E2 4.2.0 hull constant. */
    public const val DEFAULT_BOAT_WEIGHT_KG: Int = 12

    /**
     * Effective handle pull length used by the force/cadence power model.
     *
     * `0.600 m` is the KayakFit shipped calibration, fitted so that
     * `force x length x cadence / 60` reproduces the console's own displayed power. It is a
     * FitBridge/KayakFit model parameter, not a KayakFirst-published constant.
     */
    public const val DEFAULT_PULL_LENGTH_M: Double = 0.600

    /** E2 4.2.0 manufacturer equation constant recovered from the official Orange application. */
    public const val FLYWHEEL_INERTIA: Double = 0.02527962

    /** E2 4.2.0 manufacturer equation constant recovered from the official Orange application. */
    public const val FORCE_RADIUS_METRES: Double = 0.027

    /** E2 4.2.0 manufacturer equation constant recovered from the official Orange application. */
    public const val DRAG_COEFFICIENT: Double = 0.1419262

    /** E2 4.2.0 manufacturer equation constant recovered from the official Orange application. */
    public const val BOAT_MASS_OFFSET_KG: Double = 12.0
}
