package com.gmail.absolutebollockscreations.gattlibrary.core.ids

import java.util.UUID

/**
 * Nordic Secure DFU (inventory only; FitBridge never performs DFU) and Nordic UART identifiers. Provenance is in each member comment.
 *
 * Moved from central `BluetoothIds` (LIB-19b); names unchanged.
 */
public object NordicUuids {
    // Physical CORE 2 QZ discovery, 2025-12-04. Inventory only: FitBridge never performs DFU.
    public val UUID_SECURE_DFU_SERVICE: UUID = shortUuid(0xFE59)
    public val UUID_SECURE_DFU_CONTROL: UUID =
        UUID.fromString("8ec90003-f315-4f60-9fb8-838830daea50")
    public val UUID_NORDIC_UART_SERVICE: UUID =
        UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    public val UUID_NORDIC_UART_RX: UUID =
        UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    public val UUID_NORDIC_UART_TX: UUID =
        UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
}
