package com.gmail.absolutebollockscreations.gattlibrary.core.ids

import java.util.UUID

/**
 * Wahoo KICKR legacy control fingerprints and RideOn identifiers. Provenance (corroborated sources and retained capture inventory) is in each member comment.
 *
 * Moved from central `BluetoothIds` (LIB-19b); names unchanged.
 */
public object WahooUuids {
    // ─────────────────────────────────────────────────────────────
    // Wahoo KICKR proprietary control fingerprints (Phase 10)
    // ─────────────────────────────────────────────────────────────
    // Full UUIDs are transcribed from the corroborated sources and retained capture inventory
    // recorded in docs/KICKR_PROPRIETARY_GATT_PORT_DESIGN.md. The two EE services are marker
    // evidence only. Legacy commands belong exclusively to A026E005 under CPS.
    public val UUID_WAHOO_KICKR_MARKER_EE01: UUID =
        UUID.fromString("a026ee01-0a7d-4ab3-97fa-f1500f9feb8b")
    public val UUID_WAHOO_KICKR_MARKER_EE03: UUID =
        UUID.fromString("a026ee03-0a7d-4ab3-97fa-f1500f9feb8b")
    public val UUID_WAHOO_KICKR_MARKER_EE06: UUID =
        UUID.fromString("a026ee06-0a7d-4ab3-97fa-f1500f9feb8b")
    public val UUID_WAHOO_KICKR_MARKER_EE0B: UUID =
        UUID.fromString("a026ee0b-0a7d-4ab3-97fa-f1500f9feb8b")
    public val UUID_WAHOO_KICKR_CONTROL: UUID =
        UUID.fromString("a026e005-0a7d-4ab3-97fa-f1500f9feb8b")
    public val UUID_WAHOO_KICKR_E002: UUID =
        UUID.fromString("a026e002-0a7d-4ab3-97fa-f1500f9feb8b")
    public val UUID_WAHOO_KICKR_E004: UUID =
        UUID.fromString("a026e004-0a7d-4ab3-97fa-f1500f9feb8b")
    public val UUID_WAHOO_KICKR_E00A: UUID =
        UUID.fromString("a026e00a-0a7d-4ab3-97fa-f1500f9feb8b")
    public val UUID_WAHOO_KICKR_E023: UUID =
        UUID.fromString("a026e023-0a7d-4ab3-97fa-f1500f9feb8b")
    public val UUID_WAHOO_KICKR_E018: UUID =
        UUID.fromString("a026e018-0a7d-4ab3-97fa-f1500f9feb8b")
    public val UUID_WAHOO_KICKR_E037: UUID =
        UUID.fromString("a026e037-0a7d-4ab3-97fa-f1500f9feb8b")

    // Recorded now for inventory/diagnostics only. RideOn activation and writes are a separate
    // future backend and must not be inferred from these constants existing.
    public val UUID_WAHOO_RIDEON_SERVICE: UUID =
        UUID.fromString("00000001-19ca-4651-86e5-fa29dcdd09d1")
    public val UUID_WAHOO_RIDEON_CORE2_SERVICE: UUID = shortUuid(0xFC82)
    public val UUID_WAHOO_RIDEON_ASYNC_TX: UUID =
        UUID.fromString("00000002-19ca-4651-86e5-fa29dcdd09d1")
    public val UUID_WAHOO_RIDEON_SYNC_RX: UUID =
        UUID.fromString("00000003-19ca-4651-86e5-fa29dcdd09d1")
    public val UUID_WAHOO_RIDEON_SYNC_TX: UUID =
        UUID.fromString("00000004-19ca-4651-86e5-fa29dcdd09d1")
}
