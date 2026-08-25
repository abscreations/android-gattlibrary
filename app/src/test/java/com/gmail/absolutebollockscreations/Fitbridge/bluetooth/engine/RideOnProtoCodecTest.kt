package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

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
