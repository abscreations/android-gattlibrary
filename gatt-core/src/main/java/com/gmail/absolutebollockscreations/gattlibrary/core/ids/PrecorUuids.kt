package com.gmail.absolutebollockscreations.gattlibrary.core.ids

import java.util.UUID

/**
 * Precor Spinner Chrono Power proprietary GATT identifiers. Provenance is in each member comment.
 *
 * Moved from central `BluetoothIds` (LIB-19b); names unchanged.
 */
public object PrecorUuids {
    // ─────────────────────────────────────────────────────────────
    // Precor Spinner Chrono Power (proprietary, plan §5b)
    // ─────────────────────────────────────────────────────────────
    /**
     * Precor Chrono service and measurement UUIDs.
     *
     * **Written out in full, deliberately.** The Concept2 block above builds its UUIDs from a
     * base and an alias because Concept2's published specification *defines* that base and
     * thirty-odd aliases within it. Nothing comparable exists here: two identifiers were read
     * from a source project and there is no published Precor document behind them. A
     * `precorUuid(alias)` helper would present two extracted facts as if they were an
     * enumerable namespace, which is the §8.4 error in a more respectable costume — writing
     * both constants out means no third UUID can ever be produced by calling the helper with a
     * number nobody has seen.
     *
     * Source: QZ `src/devices/chronobike/chronobike.cpp` — `serviceScanDone()` and
     * `stateChanged()` — with the identical pair independently repeated in
     * `src/devices/truetreadmill/truetreadmill.cpp`. Resolved 2026-08-12; plan §10.
     *
     * The base coincides with Wahoo's `a026xxxx-0a7d-4ab3-97fa-f1500f9feb8b`, which is an
     * observation about two vendors' recorded identifiers and not a rule for deriving a third.
     */
    public val UUID_PRECOR_SERVICE: UUID =
        UUID.fromString("a026ee07-0a7d-4ab3-97fa-f1500f9feb8b")

    /** Notify, exactly 19 B, prefixed `53 59 16`. */
    public val UUID_PRECOR_MEASUREMENT: UUID =
        UUID.fromString("a026e01d-0a7d-4ab3-97fa-f1500f9feb8b")
}
