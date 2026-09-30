package com.gmail.absolutebollockscreations.gattlibrary.core.vendor

import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi

/**
 * Keiser M-Series advertisement identifiers: Bluetooth SIG company identifier 0x0102 and the canonical manufacturer-field framing.
 *
 * Provenance: the values below were inherited from external projects named in the member comments and are used as published there; those projects report working hardware behavior, and FitBridge treats them as correct on that feedback. FitBridge has not independently verified a value unless its comment says so.
 */
@GattLibraryInternalApi
public object KeiserMSeriesSpec {
    /** Bluetooth SIG company identifier assigned to Keiser, little-endian on the wire as `02 01`. */
    public const val COMPANY_ID: Int = 0x0102

    /** The invariant first two octets of the canonical field. */
    public val MAGIC_PREFIX: IntArray = intArrayOf(0x02, 0x01)

    /** Canonical length once byte 18 is present. */
    public const val CURRENT_FIELD_LENGTH: Int = 19

    /** Canonical length for firmware that predates the resistance byte. */
    public const val LEGACY_FIELD_LENGTH: Int = 18
}
