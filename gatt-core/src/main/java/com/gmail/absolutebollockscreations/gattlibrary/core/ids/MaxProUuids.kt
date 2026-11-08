package com.gmail.absolutebollockscreations.gattlibrary.core.ids

import java.util.UUID

/** Captured MAXPRO serial service. Match the product name too: FFF0 is widely reused. */
public object MaxProUuids {
    /** Captured service-data key in the advertisement, distinct from the GATT service. */
    public val ADVERTISEMENT_DATA: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    public val SERVICE: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    public val TELEMETRY: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
    public val COMMAND: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
}
