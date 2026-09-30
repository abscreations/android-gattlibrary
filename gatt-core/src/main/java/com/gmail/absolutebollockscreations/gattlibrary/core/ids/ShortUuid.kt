package com.gmail.absolutebollockscreations.gattlibrary.core.ids

import java.util.UUID

/** 128-bit UUID on the Bluetooth SIG base from a 16-bit Assigned Number. */
internal fun shortUuid(hex: Int): UUID =
    UUID.fromString(String.format("0000%04X-0000-1000-8000-00805F9B34FB", hex))
