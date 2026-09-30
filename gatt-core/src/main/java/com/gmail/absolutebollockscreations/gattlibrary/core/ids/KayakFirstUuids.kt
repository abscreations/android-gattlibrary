package com.gmail.absolutebollockscreations.gattlibrary.core.ids

import java.util.UUID

/**
 * Kayak First paddle-console serial-bridge identifiers. Provenance is in each member comment.
 *
 * Moved from central `BluetoothIds` (LIB-19b); names unchanged.
 */
public object KayakFirstUuids {
    // ─────────────────────────────────────────────────────────────
    // Kayak First paddle console (SIG-base serial bridge; Phase 8)
    // ─────────────────────────────────────────────────────────────
    /** Full service UUID observed in the physical console inventory and advertisement. */
    public val UUID_KAYAK_FIRST_SERVICE: UUID =
        UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")

    /** Same-parent bidirectional endpoint: write without response plus notify and CCCD. */
    public val UUID_KAYAK_FIRST_IO: UUID =
        UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")
}
