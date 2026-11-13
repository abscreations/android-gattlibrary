package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi
import kotlin.math.roundToLong
import com.gmail.absolutebollockscreations.gattlibrary.core.bytes.ByteCursor
import com.gmail.absolutebollockscreations.gattlibrary.core.bytes.ByteCursorFailure
import com.gmail.absolutebollockscreations.gattlibrary.core.bytes.ProtobufVarint
import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.RideOnSpec

@GattLibraryInternalApi
sealed interface RideOnProtoField {
    val number: Int
    data class Varint(override val number: Int, val value: Long) : RideOnProtoField
    data class Fixed64(override val number: Int, val bytes: ByteArray) : RideOnProtoField
    data class Bytes(override val number: Int, val value: ByteArray) : RideOnProtoField
    data class Fixed32(override val number: Int, val bytes: ByteArray) : RideOnProtoField
}

@GattLibraryInternalApi
data class RideOnDecodedPacket(val opcode: Int, val fields: List<RideOnProtoField>)

@GattLibraryInternalApi
sealed interface RideOnDecodeResult {
    data class Decoded(val packet: RideOnDecodedPacket) : RideOnDecodeResult
    data class Invalid(val reason: String) : RideOnDecodeResult
}

@GattLibraryInternalApi
data class RideOnSimulationParam(
    val windMpsX100: Int,
    val gradePercentX100: Int,
    val aerodynamicCoefficientX10000: Long,
    val rollingResistanceX100000: Long,
)

@GattLibraryInternalApi
data class RideOnPhysicalParam(
    val gearRatioX10000: Long? = null,
    val bicycleMassKgX100: Long,
    val riderMassKgX100: Long,
)

/** Small protobuf-v2 wire codec for only the independently authored RideOn schema. */
@GattLibraryInternalApi
internal object RideOnProtoCodec {
    fun decode(packet: ByteArray): RideOnDecodeResult {
        if (packet.isEmpty()) return RideOnDecodeResult.Invalid("missing opcode")
        if (packet.size > RideOnSpec.MAX_PACKET_BYTES) {
            return RideOnDecodeResult.Invalid("packet exceeds ${RideOnSpec.MAX_PACKET_BYTES} bytes")
        }
        val reader = Reader(packet, 1)
        val fields = mutableListOf<RideOnProtoField>()
        while (reader.remaining > 0) {
            val key = reader.varint() ?: return RideOnDecodeResult.Invalid(reader.error)
            if (key == 0L || key > Int.MAX_VALUE) return RideOnDecodeResult.Invalid("invalid field key")
            val number = (key ushr 3).toInt()
            if (number == 0) return RideOnDecodeResult.Invalid("field number zero")
            when ((key and 0x07).toInt()) {
                0 -> fields += RideOnProtoField.Varint(
                    number,
                    reader.varint() ?: return RideOnDecodeResult.Invalid(reader.error),
                )
                1 -> fields += RideOnProtoField.Fixed64(
                    number,
                    reader.bytes(8) ?: return RideOnDecodeResult.Invalid(reader.error),
                )
                2 -> {
                    val length = reader.varint()
                        ?: return RideOnDecodeResult.Invalid(reader.error)
                    if (length > Int.MAX_VALUE || length > reader.remaining.toLong()) {
                        return RideOnDecodeResult.Invalid("length exceeds remaining packet")
                    }
                    fields += RideOnProtoField.Bytes(
                        number,
                        reader.bytes(length.toInt())
                            ?: return RideOnDecodeResult.Invalid(reader.error),
                    )
                }
                5 -> fields += RideOnProtoField.Fixed32(
                    number,
                    reader.bytes(4) ?: return RideOnDecodeResult.Invalid(reader.error),
                )
                else -> return RideOnDecodeResult.Invalid("unsupported wire type ${key and 0x07}")
            }
        }
        return RideOnDecodeResult.Decoded(
            RideOnDecodedPacket(packet[0].toInt() and 0xFF, fields),
        )
    }

    fun hubRequest(dataId: Long): ByteArray = packet(
        RideOnSpec.OPCODE_HUB_REQUEST,
        uint32Field(1, dataId),
    )

    fun hubCommand(
        targetPowerWatts: Long? = null,
        simulation: RideOnSimulationParam? = null,
        physical: RideOnPhysicalParam? = null,
    ): ByteArray {
        val body = mutableListOf<Byte>()
        targetPowerWatts?.let { body += uint32Field(3, it).toList() }
        simulation?.let {
            val nested = sint32Field(1, it.windMpsX100) +
                sint32Field(2, it.gradePercentX100) +
                uint32Field(3, it.aerodynamicCoefficientX10000) +
                uint32Field(4, it.rollingResistanceX100000)
            body += bytesField(4, nested).toList()
        }
        physical?.let {
            var nested = byteArrayOf()
            it.gearRatioX10000?.let { ratio -> nested += uint32Field(2, ratio) }
            nested += uint32Field(4, it.bicycleMassKgX100)
            nested += uint32Field(5, it.riderMassKgX100)
            body += bytesField(5, nested).toList()
        }
        return packet(RideOnSpec.OPCODE_HUB_COMMAND, body.toByteArray())
    }

    fun virtualShift(commandType: Long, sequence: Long, gearRatioX10000: Long): ByteArray {
        val payload = uint32Field(7, gearRatioX10000)
        val body = uint32Field(1, commandType) + uint32Field(2, sequence) + bytesField(4, payload)
        return packet(RideOnSpec.OPCODE_VIRTUAL_SHIFT, body)
    }

    fun virtualShiftResponse(sequence: Long): ByteArray = packet(
        RideOnSpec.OPCODE_VIRTUAL_SHIFT_RESPONSE,
        uint32Field(2, sequence),
    )

    /**
     * Reply to a HubRequest, opcode [RideOnSpec.OPCODE_HUB_REQUEST_RESPONSE] (0x3C) - not
     * [RideOnSpec.OPCODE_HUB_REQUEST] (0x00) itself, despite the shared "HubRequest" name.
     * Confirmed against a real Wahoo KICKR Core reply for dataId 520 (gear ratio) in
     * zwiftplay/zaplibrary's zwift-play-kickr-core.csv HCI-snoop export, packet 5838:
     * `3c 08 88 04 12 06 0a 04 40 c0 bb 01` decodes as { 1: dataId(520), 2: Bytes{ 1: Bytes{
     * 8: value(24000) } } } - the dataId echoed back at the top level, then the answer wrapped
     * two levels deep with the leaf field number depending on which dataId was asked about (8
     * for gear ratio). Only dataId 520's leaf field number is confirmed; see
     * [hubRequestGearRatio].
     */
    fun hubRequestResponse(dataId: Long, leafFieldNumber: Int, value: Long): ByteArray {
        val leaf = uint32Field(leafFieldNumber, value)
        val wrapped = bytesField(1, leaf)
        return packet(
            RideOnSpec.OPCODE_HUB_REQUEST_RESPONSE,
            uint32Field(1, dataId) + bytesField(2, wrapped),
        )
    }

    /** [hubRequestResponse] for [RideOnSpec.HUB_REQUEST_DATA_ID_GEAR_RATIO] - see its KDoc. */
    fun hubRequestGearRatio(gearRatioX10000: Long): ByteArray =
        hubRequestResponse(RideOnSpec.HUB_REQUEST_DATA_ID_GEAR_RATIO, GEAR_RATIO_LEAF_FIELD, gearRatioX10000)

    /**
     * Status/diagnostic message broadcast, opcode [RideOnSpec.OPCODE_STATUS_MESSAGE] (0x2A).
     * Reverse engineered from qdomyos-zwift's CharacteristicWriteProcessor0003::writeProcess(),
     * which sends two of these - `statusMessage(3, "ATX 04, STX 04")` then
     * `statusMessage(3, "RIDE_ON(2)")` - immediately after accepting the RideOn handshake, both
     * over notify. Bytes verified field-for-field against QZ's literal hex constants
     * (`2a08031211220f4154582030342c2053545820303400` and
     * `2a0803120d220b524944455f4f4e28322900`): `{1: code, 2: {4: text + trailing NUL}}`. field1's
     * meaning is unconfirmed (constant 3 in both QZ messages); field2.field4 is a
     * null-terminated ASCII string.
     */
    fun statusMessage(code: Long, text: String): ByteArray {
        val nested = bytesField(4, text.toByteArray(Charsets.US_ASCII) + byteArrayOf(0))
        return packet(RideOnSpec.OPCODE_STATUS_MESSAGE, uint32Field(1, code) + bytesField(2, nested))
    }

    /**
     * Shared field layout for both hub-info replies below - reverse engineered from QZ's
     * `general_info_reply` (dataId=0 HubRequest) and `device_info_reply` (opcode 0x41 request)
     * hex constants. field1 is the constant [RideOnSpec.HUB_INFO_MARKER]; field2 is 4 opaque
     * bytes (QZ used `00 04 00 0c` for general info, `05 00 05 01` for device info - meaning
     * unconfirmed); field3 is a device-name string (present but empty in QZ's general-info
     * reply, "KICKR CORE\u0000" in its device-info reply); field6 is a serial-like string
     * (always present, non-empty in both); field7 is a short string (empty in general info, "1"
     * in device info); field8 is a {major, minor}-shaped version pair.
     */
    private fun hubInfoFields(
        opaque: ByteArray,
        deviceName: String,
        serial: String,
        shortField: String,
        versionMajor: Long,
        versionMinor: Long,
    ): ByteArray {
        require(opaque.size == 4) { "opaque field must be exactly 4 bytes" }
        // field3 follows QZ's C-string convention (a trailing NUL) when a name is present;
        // an empty name stays a genuine zero-length field, matching QZ's general-info reply.
        val nameBytes = if (deviceName.isEmpty()) {
            ByteArray(0)
        } else {
            deviceName.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        }
        return uint32Field(1, RideOnSpec.HUB_INFO_MARKER) +
            bytesField(2, opaque) +
            bytesField(3, nameBytes) +
            bytesField(6, serial.toByteArray(Charsets.US_ASCII)) +
            bytesField(7, shortField.toByteArray(Charsets.US_ASCII)) +
            bytesField(8, uint32Field(1, versionMajor) + uint32Field(2, versionMinor))
    }

    /**
     * Reply to the dataId=0 "general information" HubRequest, opcode
     * [RideOnSpec.OPCODE_HUB_REQUEST_RESPONSE] (0x3C). Real Zwift never advances a session past
     * negotiation on FitBridge's previous reply here (a bare RidingData/telemetry frame) even
     * though the RidingData shape and byte content were independently confirmed correct -
     * qdomyos-zwift (QZ), a mature open-source RideOn peripheral real Zwift does accept, replies
     * to this same request with a structured 0x3C frame instead. Verified field-for-field
     * against QZ's `general_info_reply` hex constant
     * (`3c0800122408800412040004000c1a00320f42412d4534333732443932374244453a00420408011053`):
     * `{1: 0, 2: {1:512, 2:<4 bytes>, 3:"", 6:<serial>, 7:"", 8:{1:1, 2:83}}}` - a single level of
     * nesting under field2, unlike [hubDeviceInfoReply]'s extra wrapper.
     *
     * [dataId] defaults to 0 (general info) but is otherwise echoed back verbatim in field 1: a
     * 2026-08-30 real-Zwift capture showed it retry an undocumented HubRequest dataId (16, not
     * general-info's 0 or the gear-ratio dataId's 520) three times, ~1s apart, then abandon the
     * RideOn negotiation entirely - it never got anything back over indicate for that dataId, only
     * FitBridge's ordinary periodic RidingData notify ticks landing nearby in time by coincidence.
     * Every other HubRequest dataId this server doesn't have a specific reply for now gets this
     * same structured 0x3C frame, with its own dataId echoed, sent over indicate rather than a
     * bare notify - the content is unconfirmed for a dataId other than 0, but acknowledging on
     * the channel and in the frame shape Zwift is actually waiting on matters more than getting
     * every field byte-exact for an ID nobody has documented.
     */
    fun hubGeneralInfoReply(
        serial: String,
        opaque: ByteArray = byteArrayOf(0x00, 0x04, 0x00, 0x0c),
        versionMajor: Long = 1L,
        versionMinor: Long = 83L,
        dataId: Long = 0L,
    ): ByteArray {
        val inner = hubInfoFields(opaque, deviceName = "", serial = serial, shortField = "", versionMajor, versionMinor)
        return packet(RideOnSpec.OPCODE_HUB_REQUEST_RESPONSE, uint32Field(1, dataId) + bytesField(2, inner))
    }

    /**
     * Reply to the opcode [RideOnSpec.OPCODE_DEVICE_INFO_REQUEST] (0x41) device-info request -
     * also opcode [RideOnSpec.OPCODE_HUB_REQUEST_RESPONSE] (0x3C). FitBridge previously did not
     * reply to this request at all (it only advanced the negotiation state machine). Verified
     * field-for-field against QZ's `device_info_reply` hex constant
     * (`3c080012320a3008800412040500050` + `11a0b4b49434b5220434f524500320f` +
     * `3430323431383030393834000000003a01314204080110140`, decoded after trimming an odd
     * trailing hex nibble): `{1: 0, 2: {1: {1:512, 2:<4 bytes>, 3:"KICKR CORE\u0000",
     * 6:<serial>, 7:"1", 8:{1:1, 2:20}}}}` - one extra level of nesting under field2 compared to
     * [hubGeneralInfoReply].
     */
    fun hubDeviceInfoReply(
        deviceName: String,
        serial: String,
        shortField: String,
        opaque: ByteArray = byteArrayOf(0x05, 0x00, 0x05, 0x01),
        versionMajor: Long = 1L,
        versionMinor: Long = 20L,
    ): ByteArray {
        val inner = hubInfoFields(opaque, deviceName, serial, shortField, versionMajor, versionMinor)
        val wrapped = bytesField(1, inner)
        return packet(RideOnSpec.OPCODE_HUB_REQUEST_RESPONSE, uint32Field(1, 0) + bytesField(2, wrapped))
    }

    /**
     * Zwift Click button-state broadcast, opcode [RideOnSpec.OPCODE_CLICK_BUTTON_STATUS]
     * (0x37). Reverse engineered from qdomyos-zwift's `zwift_hub.proto`
     * (`ClickKeyPadStatus{ optional PlayButtonStatus Button_Plus = 1; Button_Minus = 2; }`,
     * `enum PlayButtonStatus { ON = 0; OFF = 1; }`) and independently confirmed against
     * zwiftplay/zaplibrary's `ClickNotification.kt` - a real-hardware decoder with its own
     * passing unit test, which reads `value == 0` as pressed for both fields. So each field is
     * `0` while held and `1` while released, not a plain boolean.
     */
    fun clickKeyPadStatus(upPressed: Boolean, downPressed: Boolean): ByteArray = packet(
        RideOnSpec.OPCODE_CLICK_BUTTON_STATUS,
        uint32Field(1, if (upPressed) 0L else 1L) + uint32Field(2, if (downPressed) 0L else 1L),
    )

    fun ridingData(
        powerWatts: Long,
        cadenceRpm: Long,
        speedX100: Long,
        heartRateBpm: Long,
        // See RideOnUuids.RIDING_DATA_FIELD5_SPEED_MULTIPLIER/FIELD6_OBSERVED_CONSTANT's KDoc:
        // reproduced from a real KICKR Core capture for wire fidelity, not confirmed as required.
        legacySpeedMetric: Long = (speedX100 * RideOnUuids.RIDING_DATA_FIELD5_SPEED_MULTIPLIER).roundToLong(),
        deviceParameter: Long = RideOnUuids.RIDING_DATA_FIELD6_OBSERVED_CONSTANT,
    ): ByteArray = packet(
        RideOnSpec.OPCODE_RIDING_DATA,
        uint32Field(1, powerWatts) +
            uint32Field(2, cadenceRpm) +
            uint32Field(3, speedX100) +
            uint32Field(4, heartRateBpm) +
            uint32Field(5, legacySpeedMetric) +
            uint32Field(6, deviceParameter),
    )

    fun zigZagEncode32(value: Int): Long = ProtobufVarint.zigZagEncode32(value)

    fun zigZagDecode32(value: Long): Int = ProtobufVarint.zigZagDecode32(value)

    private fun sint32Field(number: Int, value: Int): ByteArray =
        key(number, 0) + varint(zigZagEncode32(value))

    private fun uint32Field(number: Int, value: Long): ByteArray {
        requireUint32(value)
        return key(number, 0) + varint(value)
    }

    private fun bytesField(number: Int, value: ByteArray): ByteArray =
        key(number, 2) + varint(value.size.toLong()) + value

    private fun key(number: Int, wireType: Int): ByteArray {
        require(number > 0)
        return varint((number.toLong() shl 3) or wireType.toLong())
    }

    private fun packet(opcode: Int, body: ByteArray): ByteArray {
        require(opcode in 0..0xFF)
        require(body.size + 1 <= RideOnSpec.MAX_PACKET_BYTES)
        return byteArrayOf(opcode.toByte()) + body
    }

    private fun varint(value: Long): ByteArray = ProtobufVarint.encode(value)

    private fun requireUint32(value: Long) = require(value in 0..UINT32_MAX)

    /** RideOn's decode-error vocabulary over the shared [ByteCursor] (LIB-18f). */
    private class Reader(data: ByteArray, start: Int) {
        private val cursor = ByteCursor(data, start)
        var error: String = "truncated field"
            private set
        val remaining get() = cursor.remaining

        fun varint(): Long? = cursor.varint() ?: null.also {
            error = if (cursor.lastFailure == ByteCursorFailure.OVERLONG_VARINT) {
                "overlong varint"
            } else {
                "truncated varint"
            }
        }

        fun bytes(length: Int): ByteArray? = cursor.bytes(length) ?: null.also {
            error = "truncated fixed or byte field"
        }
    }

    private const val UINT32_MAX = 0xFFFF_FFFFL

    // Leaf field number for a gear-ratio HubRequest reply - see hubRequestResponse()'s KDoc.
    private const val GEAR_RATIO_LEAF_FIELD = 8
}
