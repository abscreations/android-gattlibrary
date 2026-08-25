package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

/** RideOn constants only. Merely referencing these values never activates the protocol. */
internal object RideOnUuids {
    val ORIGINAL_SERVICE = BluetoothIds.UUID_WAHOO_RIDEON_SERVICE
    val CORE2_SERVICE = BluetoothIds.UUID_WAHOO_RIDEON_CORE2_SERVICE
    val ASYNC_TX = BluetoothIds.UUID_WAHOO_RIDEON_ASYNC_TX
    val SYNC_RX = BluetoothIds.UUID_WAHOO_RIDEON_SYNC_RX
    val SYNC_TX = BluetoothIds.UUID_WAHOO_RIDEON_SYNC_TX

    val HANDSHAKE_ORIGINAL = "RideOn".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x02, 0x02)
    val HANDSHAKE_CORE2 = "RideOn".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x02, 0x03)

    const val OPCODE_HUB_REQUEST = 0x00
    const val OPCODE_RIDING_DATA = 0x03
    const val OPCODE_HUB_COMMAND = 0x04
    const val OPCODE_VIRTUAL_SHIFT = 0x3F
    const val OPCODE_VIRTUAL_SHIFT_RESPONSE = 0x40
    const val MAX_PACKET_BYTES = 240
}
