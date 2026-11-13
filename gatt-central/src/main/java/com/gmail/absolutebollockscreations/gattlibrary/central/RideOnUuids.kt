package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi
import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.RideOnSpec

/**
 * RideOn layout constants that stay private to the decoder side (observed scales, not protocol
 * vocabulary; Q1). The protocol vocabulary moved to `core.vendor.RideOnSpec` (LIB-19c).
 */
@GattLibraryInternalApi
internal object RideOnUuids {

    // HubRidingData (RideOnSpec.OPCODE_RIDING_DATA) fields 5 and 6, present on every frame in that same
    // real KICKR Core capture but never documented anywhere found. Field 6 was constant for the
    // whole session (one observed value: 32972) except for a single step change mid-ride to
    // 39044 that did not correlate with any other field, so its meaning is unconfirmed - treated
    // here as a fixed device parameter. Field 5 tracked field 3 (speedX100) at an extremely
    // tight, session-independent ratio (mean 29.00, stdev 0.02 across ~300 samples spanning both
    // field-6 values) - reproduced here as a derived "legacy" speed metric. Neither field
    // appears to be required: our own capture showed real Zwift completing negotiation and
    // driving the simulator correctly without them. Included only for wire fidelity with the
    // real hardware session, not because either value is known to matter.
    const val RIDING_DATA_FIELD5_SPEED_MULTIPLIER = 29.0
    const val RIDING_DATA_FIELD6_OBSERVED_CONSTANT = 32_972L
}
