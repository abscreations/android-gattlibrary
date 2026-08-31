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

    // Two more real opcodes, confirmed in use by qdomyos-zwift (QZ, a mature open-source
    // Zwift-companion app that emulates a RideOn peripheral Zwift actually accepts) in
    // CharacteristicWriteProcessor0003::writeProcess() - not previously modeled here at all.
    // OPCODE_STATUS_MESSAGE (0x2A) is a diagnostic/status string QZ broadcasts twice, over
    // notify, immediately after accepting the handshake. OPCODE_DEVICE_INFO_REQUEST (0x41) is a
    // HubRequest-shaped device-info query real Zwift also sends (FitBridge's own client-side
    // RideOnControlSession sends the identical "41 08 05" bytes as part of its own
    // initialization sequence) - QZ replies to it with a proper OPCODE_HUB_REQUEST_RESPONSE
    // (0x3C) frame; FitBridge previously just accepted it silently with no reply at all.
    const val OPCODE_STATUS_MESSAGE = 0x2A
    const val OPCODE_DEVICE_INFO_REQUEST = 0x41

    // Constant marker value (field 1) present in every QZ hub-info reply observed (both the
    // dataId=0 general-info reply and the opcode-0x41 device-info reply) - meaning unconfirmed,
    // reproduced for wire fidelity since real Zwift is only known to accept replies shaped
    // exactly like QZ's.
    const val HUB_INFO_MARKER = 512L

    // Zwift Click (BC1) button-status broadcast, opcode 0x37. A real Zwift Click is its own BLE
    // peripheral, but it shares the identical service/characteristic UUID triad with the KICKR's
    // own Zwift-proprietary service (both are "ZAP" - Zwift Accessory Protocol - confirmed by
    // zwiftplay/zaplibrary's zwift-play-kickr-core.csv HCI-snoop export, packets 4625/4707/4853:
    // "Zwift Play L", "Zwift Play R" and "KICKR CORE" all use service
    // 0000000119ca465186e5fa29dcdd09d1 with characteristic 0000000219ca465186e5fa29dcdd09d1,
    // i.e. UUID_WAHOO_RIDEON_SERVICE/ASYNC_TX above). ZAP is one shared protocol family
    // multiplexed by this leading opcode byte, not a separate service - see
    // RideOnProtoCodec.clickKeyPadStatus()'s KDoc.
    const val OPCODE_CLICK_BUTTON_STATUS = 0x37

    // Reply opcode for a HubRequest (OPCODE_HUB_REQUEST), confirmed on the wire from a real
    // Wahoo KICKR Core session (zwiftplay/zaplibrary's zwift-play-kickr-core.csv HCI-snoop
    // export, packet 5838): "3c 08 88 04 12 06 0a 04 40 c0 bb 01" - opcode 0x3C, not 0x00 as
    // might be assumed from the request sharing OPCODE_HUB_REQUEST. See RideOnProtoCodec's
    // hubRequestResponse() KDoc for the nested field layout.
    const val OPCODE_HUB_REQUEST_RESPONSE = 0x3C

    // HubRequest (opcode OPCODE_HUB_REQUEST) dataId values documented by community reverse
    // engineering of a physical Wahoo KICKR Core (makinolo.com, "Zwift Trainer protocol",
    // 2024-10-20) and confirmed on the wire in the same real capture referenced above: dataId 0
    // is the general information request every session opens with; dataId 520 asks the trainer
    // to report its current gear ratio (that capture shows Zwift polling this repeatedly, right
    // after every HubCommand gear change, throughout a whole ride); dataId 1-7 are reported to
    // return "the current values for the specific fields that are returned when the general info
    // request is made" but neither source publishes the general-info response's own field
    // layout, so 1-7 have no confirmed per-field mapping here.
    const val HUB_REQUEST_DATA_ID_GENERAL_INFO = 0L
    const val HUB_REQUEST_DATA_ID_GEAR_RATIO = 520L

    // HubRidingData (OPCODE_RIDING_DATA) fields 5 and 6, present on every frame in that same
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
