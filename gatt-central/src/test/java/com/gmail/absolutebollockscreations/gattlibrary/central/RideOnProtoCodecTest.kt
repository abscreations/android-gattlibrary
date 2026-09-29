package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RideOnProtoCodecTest {
    @Test
    fun `UUID contract and service-specific handshakes are exact`() {
        assertEquals(BluetoothIds.UUID_WAHOO_RIDEON_SERVICE, RideOnUuids.ORIGINAL_SERVICE)
        assertEquals(BluetoothIds.UUID_WAHOO_RIDEON_CORE2_SERVICE, RideOnUuids.CORE2_SERVICE)
        assertArrayEquals(hex("52 69 64 65 4F 6E 02 02"), RideOnUuids.HANDSHAKE_ORIGINAL)
        assertArrayEquals(hex("52 69 64 65 4F 6E 02 03"), RideOnUuids.HANDSHAKE_CORE2)
    }

    @Test
    fun `HubRequest zero and ERG 250 match pinned vectors`() {
        assertArrayEquals(hex("00 08 00"), RideOnProtoCodec.hubRequest(0))
        assertArrayEquals(hex("04 18 FA 01"), RideOnProtoCodec.hubCommand(targetPowerWatts = 250))
    }

    @Test
    fun `HubRequest gear-ratio reply matches a real KICKR Core capture exactly`() {
        // zwiftplay/zaplibrary's zwift-play-kickr-core.csv HCI-snoop export, packet 5838: a real
        // Wahoo KICKR Core answering Zwift's dataId-520 query for gear index 12 (ratio 24000).
        assertArrayEquals(
            hex("3C 08 88 04 12 06 0A 04 40 C0 BB 01"),
            RideOnProtoCodec.hubRequestGearRatio(24_000),
        )
        // Same capture, packet 8366: gear index 5 (ratio 12300) - one byte shorter because its
        // varint only needs two bytes, confirming the reply's length tracks the varint, not a
        // fixed frame size.
        assertArrayEquals(
            hex("3C 08 88 04 12 05 0A 03 40 8C 60"),
            RideOnProtoCodec.hubRequestGearRatio(12_300),
        )
    }

    @Test
    fun `RidingData at rest matches a real KICKR Core capture exactly, including fields 5 and 6`() {
        // zwiftplay/zaplibrary's zwift-play-kickr-core.csv HCI-snoop export, packet 4630: the
        // very first RidingData notification from a real Wahoo KICKR Core, all zero. Field 5
        // (a derived speed metric - see RideOnUuids's KDoc) is trivially exact at zero speed;
        // field 6 (a constant, unconfirmed device parameter in that capture) matches exactly at
        // its default.
        assertArrayEquals(
            hex("03 08 00 10 00 18 00 20 00 28 00 30 CC 81 02"),
            RideOnProtoCodec.ridingData(powerWatts = 0, cadenceRpm = 0, speedX100 = 0, heartRateBpm = 0),
        )
    }

    @Test
    fun `status message matches qdomyos-zwift's handshake diagnostic frames exactly`() {
        // qdomyos-zwift (QZ) - a mature open-source RideOn peripheral real Zwift accepts - sends
        // these two literal frames, over notify, immediately after accepting the RideOn
        // handshake. See RideOnProtoCodec.statusMessage()'s KDoc.
        assertArrayEquals(
            hex("2A 08 03 12 11 22 0F 41 54 58 20 30 34 2C 20 53 54 58 20 30 34 00"),
            RideOnProtoCodec.statusMessage(3, "ATX 04, STX 04"),
        )
        assertArrayEquals(
            hex("2A 08 03 12 0D 22 0B 52 49 44 45 5F 4F 4E 28 32 29 00"),
            RideOnProtoCodec.statusMessage(3, "RIDE_ON(2)"),
        )
    }

    @Test
    fun `general-info reply matches qdomyos-zwift's dataId-0 reply exactly with QZ's own identity`() {
        // qdomyos-zwift's `general_info_reply` hex constant, decoded field-for-field: opcode
        // 0x3C, {1: 0, 2: {1:512, 2:<4 bytes>, 3:"", 6:"BA-E4372D927BDE", 7:"", 8:{1:1, 2:83}}}.
        // Reproduced here with QZ's own identity string to confirm the encoder is byte-exact,
        // independent of the FitBridge-specific serial ZwiftTrainerPeripheralServer actually
        // sends (see hubGeneralInfoReply()'s KDoc).
        assertArrayEquals(
            hex(
                "3C 08 00 12 24 08 80 04 12 04 00 04 00 0C 1A 00 32 0F 42 41 2D 45 34 33 37 32 44 " +
                    "39 32 37 42 44 45 3A 00 42 04 08 01 10 53",
            ),
            RideOnProtoCodec.hubGeneralInfoReply(serial = "BA-E4372D927BDE"),
        )
    }

    @Test
    fun `device-info reply matches qdomyos-zwift's opcode-0x41 reply structurally`() {
        // qdomyos-zwift's `device_info_reply` hex constant decodes to opcode 0x3C,
        // {1: 0, 2: {1: {1:512, 2:<4 bytes>, 3:"KICKR CORE\u0000", 6:<serial>, 7:"1",
        // 8:{1:1, 2:20}}}} - one extra level of nesting under field2 versus the general-info
        // reply above. Reproduced with QZ's own device-name and version-pair values to confirm
        // the encoder's structure is byte-exact; the serial differs from QZ's real-hardware
        // value on purpose (see hubDeviceInfoReply()'s KDoc).
        val reply = RideOnProtoCodec.hubDeviceInfoReply(
            deviceName = "KICKR CORE",
            serial = "FITBRIDGE-SIM01",
            shortField = "1",
        )
        val outer = decoded(reply)
        assertEquals(RideOnUuids.OPCODE_HUB_REQUEST_RESPONSE, outer.opcode)
        assertEquals(0L, varint(outer.fields, 1))
        val level2 = (outer.fields.single { it.number == 2 } as RideOnProtoField.Bytes).value
        val level2Fields = decoded(byteArrayOf(0) + level2).fields
        val inner = (level2Fields.single { it.number == 1 } as RideOnProtoField.Bytes).value
        val innerFields = decoded(byteArrayOf(0) + inner).fields
        assertEquals(RideOnUuids.HUB_INFO_MARKER, varint(innerFields, 1))
        assertArrayEquals(
            "KICKR CORE\u0000".toByteArray(Charsets.US_ASCII),
            (innerFields.single { it.number == 3 } as RideOnProtoField.Bytes).value,
        )
        assertArrayEquals(
            "FITBRIDGE-SIM01".toByteArray(Charsets.US_ASCII),
            (innerFields.single { it.number == 6 } as RideOnProtoField.Bytes).value,
        )
        assertArrayEquals(
            "1".toByteArray(Charsets.US_ASCII),
            (innerFields.single { it.number == 7 } as RideOnProtoField.Bytes).value,
        )
        val versionPair = (innerFields.single { it.number == 8 } as RideOnProtoField.Bytes).value
        val versionFields = decoded(byteArrayOf(0) + versionPair).fields
        assertEquals(1L, varint(versionFields, 1))
        assertEquals(20L, varint(versionFields, 2))
    }

    @Test
    fun `click button status matches the pressed-is-zero convention exactly`() {
        // Field value 0 = held, 1 = released - see clickKeyPadStatus()'s KDoc. Independently
        // verified in Python against qdomyos-zwift's zwift_hub.proto layout before being pinned
        // here.
        assertArrayEquals(hex("37 08 00 10 01"), RideOnProtoCodec.clickKeyPadStatus(upPressed = true, downPressed = false))
        assertArrayEquals(hex("37 08 01 10 00"), RideOnProtoCodec.clickKeyPadStatus(upPressed = false, downPressed = true))
        assertArrayEquals(hex("37 08 01 10 01"), RideOnProtoCodec.clickKeyPadStatus(upPressed = false, downPressed = false))
    }

    @Test
    fun `virtual shift sequence zero is encoded explicitly`() {
        assertArrayEquals(
            hex("3F 08 01 10 00 22 04 38 C0 BB 01"),
            RideOnProtoCodec.virtualShift(commandType = 1, sequence = 0, gearRatioX10000 = 24_000),
        )
    }

    @Test
    fun `nested simulation and physical messages retain independent RideOn scales`() {
        val packet = RideOnProtoCodec.hubCommand(
            simulation = RideOnSimulationParam(
                windMpsX100 = -125,
                gradePercentX100 = 400,
                aerodynamicCoefficientX10000 = 5_100,
                rollingResistanceX100000 = 400,
            ),
            physical = RideOnPhysicalParam(
                gearRatioX10000 = 24_000,
                bicycleMassKgX100 = 1_000,
                riderMassKgX100 = 7_500,
            ),
        )
        val decoded = decoded(packet)
        assertEquals(RideOnUuids.OPCODE_HUB_COMMAND, decoded.opcode)
        val simulation = (decoded.fields.single { it.number == 4 } as RideOnProtoField.Bytes).value
        val physical = (decoded.fields.single { it.number == 5 } as RideOnProtoField.Bytes).value

        val simFields = decoded(byteArrayOf(0) + simulation).fields
        assertEquals(RideOnProtoCodec.zigZagEncode32(-125), varint(simFields, 1))
        assertEquals(RideOnProtoCodec.zigZagEncode32(400), varint(simFields, 2))
        assertEquals(5_100, varint(simFields, 3))
        assertEquals(400, varint(simFields, 4))
        val physicalFields = decoded(byteArrayOf(0) + physical).fields
        assertEquals(24_000, varint(physicalFields, 2))
        assertEquals(1_000, varint(physicalFields, 4))
        assertEquals(7_500, varint(physicalFields, 5))
    }

    @Test
    fun `signed ZigZag boundaries round trip`() {
        listOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE).forEach { value ->
            assertEquals(value, RideOnProtoCodec.zigZagDecode32(RideOnProtoCodec.zigZagEncode32(value)))
        }
    }

    @Test
    fun `unknown supported wire types are retained without disrupting later fields`() {
        val packet = byteArrayOf(0x03, 0x09) + ByteArray(8) { it.toByte() } +
            byteArrayOf(0x15, 1, 2, 3, 4, 0x18, 0x2A)
        val decoded = decoded(packet)
        assertTrue(decoded.fields[0] is RideOnProtoField.Fixed64)
        assertTrue(decoded.fields[1] is RideOnProtoField.Fixed32)
        assertEquals(42, varint(decoded.fields, 3))
    }

    @Test
    fun `malformed packets fail closed`() {
        assertInvalid(byteArrayOf())
        assertInvalid(byteArrayOf(0x03, 0x08, 0x80.toByte()))
        assertInvalid(byteArrayOf(0x03, 0x0A, 0x05, 1, 2))
        assertInvalid(byteArrayOf(0x03, 0x0B))
        assertInvalid(byteArrayOf(0x03, 0x08) + ByteArray(11) { 0x80.toByte() })
        assertInvalid(ByteArray(RideOnUuids.MAX_PACKET_BYTES + 1))
    }

    @Test
    fun `uint32 overflow and packet overflow are rejected by encoders`() {
        assertThrows { RideOnProtoCodec.hubRequest(-1) }
        assertThrows { RideOnProtoCodec.hubRequest(0x1_0000_0000L) }
        assertThrows {
            RideOnProtoCodec.hubCommand(
                physical = RideOnPhysicalParam(
                    bicycleMassKgX100 = Long.MAX_VALUE,
                    riderMassKgX100 = 1,
                ),
            )
        }
    }

    private fun decoded(packet: ByteArray): RideOnDecodedPacket =
        when (val result = RideOnProtoCodec.decode(packet)) {
            is RideOnDecodeResult.Decoded -> result.packet
            is RideOnDecodeResult.Invalid -> throw AssertionError(result.reason)
        }

    private fun assertInvalid(packet: ByteArray) {
        assertTrue(RideOnProtoCodec.decode(packet) is RideOnDecodeResult.Invalid)
    }

    private fun varint(fields: List<RideOnProtoField>, number: Int): Long =
        (fields.single { it.number == number } as RideOnProtoField.Varint).value

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected validation failure.
        }
    }

    private fun hex(value: String): ByteArray = value.split(' ')
        .filter { it.isNotBlank() }
        .map { it.toInt(16).toByte() }
        .toByteArray()
}
