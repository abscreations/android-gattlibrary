package com.gmail.absolutebollockscreations.gattlibrary.core.ids

import java.util.UUID

/**
 * Zwift Hub support-service identifiers (inventory only). Provenance is in each member comment.
 *
 * Moved from central `BluetoothIds` (LIB-19b); names unchanged.
 */
public object ZwiftUuids {
    // Physical Zwift Hub QZ discovery, repeated 2025-10-22. Inventory only: the captures do not
    // establish either proprietary service's application protocol.
    public val UUID_ZWIFT_HUB_SUPPORT_SERVICE: UUID =
        UUID.fromString("c4630001-003f-4cec-8994-e489b04d857f")
    public val UUID_ZWIFT_HUB_SUPPORT_READ: UUID =
        UUID.fromString("c4632b03-003f-4cec-8994-e489b04d857f")
    public val UUID_ZWIFT_HUB_SUPPORT_NOTIFY: UUID =
        UUID.fromString("c4632b05-003f-4cec-8994-e489b04d857f")
    public val UUID_ZWIFT_HUB_SUPPORT_CONTROL: UUID =
        UUID.fromString("c4632b04-003f-4cec-8994-e489b04d857f")
}
