package com.gmail.absolutebollockscreations.gattlibrary.core.ids

import java.util.UUID

/**
 * Flywheel Sports identifiers (Nordic UART transport; gymnasticon v1.4.1, MIT). Provenance is in each member comment.
 *
 * Moved from central `BluetoothIds` (LIB-19b); names unchanged.
 */
public object FlywheelUuids {
    // ─────────────────────────────────────────────────────────────
    // Flywheel Sports (Nordic UART transport; gymnasticon v1.4.1, MIT)
    // ─────────────────────────────────────────────────────────────
    /** Exact served/advertised service. Do not confuse this with FE-C's `6E40FEC1-...`. */
    public val UUID_FLYWHEEL_SERVICE: UUID =
        UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")

    /** Writable transport endpoint. Discovered for identity only; FitBridge sends no commands. */
    public val UUID_FLYWHEEL_RX: UUID =
        UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")

    /** Notify + CCCD statistics endpoint. */
    public val UUID_FLYWHEEL_TX: UUID =
        UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
}
