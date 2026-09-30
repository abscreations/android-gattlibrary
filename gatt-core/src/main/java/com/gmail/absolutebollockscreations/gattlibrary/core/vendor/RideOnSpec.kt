package com.gmail.absolutebollockscreations.gattlibrary.core.vendor

import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi
import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.WahooUuids

/**
 * Wahoo/Zwift RideOn ("ZAP") wire vocabulary: service and characteristic identifiers, handshake bytes, opcodes, HubRequest data IDs and the gear-ratio table.
 *
 * Provenance: the values below were inherited from external projects named in the member comments and are used as published there; those projects report working hardware behavior, and FitBridge treats them as correct on that feedback. FitBridge has not independently verified a value unless its comment says so.
 */
@GattLibraryInternalApi
public object RideOnSpec {
    public val ORIGINAL_SERVICE: UUID = WahooUuids.UUID_WAHOO_RIDEON_SERVICE

    public val CORE2_SERVICE: UUID = WahooUuids.UUID_WAHOO_RIDEON_CORE2_SERVICE

    public val ASYNC_TX: UUID = WahooUuids.UUID_WAHOO_RIDEON_ASYNC_TX

    public val SYNC_RX: UUID = WahooUuids.UUID_WAHOO_RIDEON_SYNC_RX

    public val SYNC_TX: UUID = WahooUuids.UUID_WAHOO_RIDEON_SYNC_TX

    public val HANDSHAKE_ORIGINAL: ByteArray = "RideOn".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x02, 0x02)

    public val HANDSHAKE_CORE2: ByteArray = "RideOn".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x02, 0x03)

    public const val OPCODE_HUB_REQUEST: Int = 0x00

    public const val OPCODE_RIDING_DATA: Int = 0x03

    public const val OPCODE_HUB_COMMAND: Int = 0x04

    public const val OPCODE_VIRTUAL_SHIFT: Int = 0x3F

    public const val OPCODE_VIRTUAL_SHIFT_RESPONSE: Int = 0x40

    public const val MAX_PACKET_BYTES: Int = 240

    // Two more real opcodes, confirmed in use by qdomyos-zwift (QZ, a mature open-source
    // Zwift-companion app that emulates a RideOn peripheral Zwift actually accepts) in
    // CharacteristicWriteProcessor0003::writeProcess() - not previously modeled here at all.
    // OPCODE_STATUS_MESSAGE (0x2A) is a diagnostic/status string QZ broadcasts twice, over
    // notify, immediately after accepting the handshake. OPCODE_DEVICE_INFO_REQUEST (0x41) is a
    // HubRequest-shaped device-info query real Zwift also sends (FitBridge's own client-side
    // RideOnControlSession sends the identical "41 08 05" bytes as part of its own
    // initialization sequence) - QZ replies to it with a proper OPCODE_HUB_REQUEST_RESPONSE
    // (0x3C) frame; FitBridge previously just accepted it silently with no reply at all.
    public const val OPCODE_STATUS_MESSAGE: Int = 0x2A

    public const val OPCODE_DEVICE_INFO_REQUEST: Int = 0x41

    // Constant marker value (field 1) present in every QZ hub-info reply observed (both the
    // dataId=0 general-info reply and the opcode-0x41 device-info reply) - meaning unconfirmed,
    // reproduced for wire fidelity since real Zwift is only known to accept replies shaped
    // exactly like QZ's.
    public const val HUB_INFO_MARKER: Long = 512L

    // Zwift Click (BC1) button-status broadcast, opcode 0x37. A real Zwift Click is its own BLE
    // peripheral, but it shares the identical service/characteristic UUID triad with the KICKR's
    // own Zwift-proprietary service (both are "ZAP" - Zwift Accessory Protocol - confirmed by
    // zwiftplay/zaplibrary's zwift-play-kickr-core.csv HCI-snoop export, packets 4625/4707/4853:
    // "Zwift Play L", "Zwift Play R" and "KICKR CORE" all use service
    // 0000000119ca465186e5fa29dcdd09d1 with characteristic 0000000219ca465186e5fa29dcdd09d1,
    // i.e. UUID_WAHOO_RIDEON_SERVICE/ASYNC_TX above). ZAP is one shared protocol family
    // multiplexed by this leading opcode byte, not a separate service - see
    // RideOnProtoCodec.clickKeyPadStatus()'s KDoc.
    public const val OPCODE_CLICK_BUTTON_STATUS: Int = 0x37

    // Reply opcode for a HubRequest (OPCODE_HUB_REQUEST), confirmed on the wire from a real
    // Wahoo KICKR Core session (zwiftplay/zaplibrary's zwift-play-kickr-core.csv HCI-snoop
    // export, packet 5838): "3c 08 88 04 12 06 0a 04 40 c0 bb 01" - opcode 0x3C, not 0x00 as
    // might be assumed from the request sharing OPCODE_HUB_REQUEST. See RideOnProtoCodec's
    // hubRequestResponse() KDoc for the nested field layout.
    public const val OPCODE_HUB_REQUEST_RESPONSE: Int = 0x3C

    // HubRequest (opcode OPCODE_HUB_REQUEST) dataId values documented by community reverse
    // engineering of a physical Wahoo KICKR Core (makinolo.com, "Zwift Trainer protocol",
    // 2024-10-20) and confirmed on the wire in the same real capture referenced above: dataId 0
    // is the general information request every session opens with; dataId 520 asks the trainer
    // to report its current gear ratio (that capture shows Zwift polling this repeatedly, right
    // after every HubCommand gear change, throughout a whole ride); dataId 1-7 are reported to
    // return "the current values for the specific fields that are returned when the general info
    // request is made" but neither source publishes the general-info response's own field
    // layout, so 1-7 have no confirmed per-field mapping here.
    public const val HUB_REQUEST_DATA_ID_GENERAL_INFO: Long = 0L

    public const val HUB_REQUEST_DATA_ID_GEAR_RATIO: Long = 520L

    /** Gear-ratio table (x10000 fixed point) the emulated hub reports for virtual shifting. */
    public val GEAR_RATIOS: List<Int> = listOf(
    7500, 8700, 9900, 11100, 12300, 13800, 15300, 16800,
    18600, 20400, 22200, 24000, 26099, 28200, 30300, 32400,
    34900, 37400, 39900, 42399, 45400, 48400, 51400, 54899,
    )
}
