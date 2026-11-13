package com.gmail.absolutebollockscreations.gattlibrary.conformance

import com.gmail.absolutebollockscreations.gattlibrary.central.RideOnDecodeResult
import com.gmail.absolutebollockscreations.gattlibrary.central.RideOnProtoCodec
import com.gmail.absolutebollockscreations.gattlibrary.central.RideOnProtoField
import com.gmail.absolutebollockscreations.gattlibrary.central.RideOnUuids
import com.gmail.absolutebollockscreations.gattlibrary.central.decodeRideOnTelemetry
import com.gmail.absolutebollockscreations.gattlibrary.simulator.RideOnWire
import com.gmail.absolutebollockscreations.gattlibrary.simulator.RideOnWireDecodeResult
import com.gmail.absolutebollockscreations.gattlibrary.simulator.RideOnWireField
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L25 parity: the simulator's RideOn builders and decoder are an independent copy (LIB-19j, L23
 * double entry) of central's `RideOnProtoCodec`. Every builder must emit the same bytes, both
 * decoders must read every frame the same way, and central's telemetry decoder must accept what
 * the simulator emits. A shared bug in both copies is not caught here; the golden captures are.
 */
class RideOnWireParityTest {

    private val serial = "40241800984"
    private val builtFrames: Map<String, Pair<ByteArray, ByteArray>> = mapOf(
        "virtualShiftResponse" to (RideOnWire.virtualShiftResponse(7) to RideOnProtoCodec.virtualShiftResponse(7)),
        "hubRequestResponse" to (RideOnWire.hubRequestResponse(0x10, 3, 99) to RideOnProtoCodec.hubRequestResponse(0x10, 3, 99)),
        "hubRequestGearRatio" to (RideOnWire.hubRequestGearRatio(34_500) to RideOnProtoCodec.hubRequestGearRatio(34_500)),
        "statusMessage" to (RideOnWire.statusMessage(2, "OK") to RideOnProtoCodec.statusMessage(2, "OK")),
        "hubGeneralInfoReply" to (RideOnWire.hubGeneralInfoReply(serial) to RideOnProtoCodec.hubGeneralInfoReply(serial)),
        "hubGeneralInfoReplyCustom" to (
            RideOnWire.hubGeneralInfoReply(serial, byteArrayOf(1, 2, 3, 4), 2, 9, 5) to
                RideOnProtoCodec.hubGeneralInfoReply(serial, byteArrayOf(1, 2, 3, 4), 2, 9, 5)
            ),
        "hubDeviceInfoReply" to (
            RideOnWire.hubDeviceInfoReply("KICKR CORE", serial, "1") to
                RideOnProtoCodec.hubDeviceInfoReply("KICKR CORE", serial, "1")
            ),
        "clickPressedUp" to (RideOnWire.clickKeyPadStatus(true, false) to RideOnProtoCodec.clickKeyPadStatus(true, false)),
        "clickReleased" to (RideOnWire.clickKeyPadStatus(false, false) to RideOnProtoCodec.clickKeyPadStatus(false, false)),
        "ridingData" to (RideOnWire.ridingData(215, 88, 3_250, 131) to RideOnProtoCodec.ridingData(215, 88, 3_250, 131)),
        "ridingDataOverrides" to (
            RideOnWire.ridingData(0, 0, 0, 0, legacySpeedMetric = 5, deviceParameter = 39_044) to
                RideOnProtoCodec.ridingData(0, 0, 0, 0, legacySpeedMetric = 5, deviceParameter = 39_044)
            ),
    )

    @Test
    fun builtFramesAreByteIdentical() {
        builtFrames.forEach { (name, pair) -> assertArrayEquals("frame $name", pair.second, pair.first) }
    }

    @Test
    fun zigZagMatches() {
        listOf(0, 1, -1, 2, -2, 12_345, -12_345, Int.MAX_VALUE, Int.MIN_VALUE).forEach {
            assertEquals(RideOnProtoCodec.zigZagEncode32(it), RideOnWire.zigZagEncode32(it))
            assertEquals(RideOnProtoCodec.zigZagDecode32(RideOnProtoCodec.zigZagEncode32(it)), RideOnWire.zigZagDecode32(RideOnWire.zigZagEncode32(it)))
        }
    }

    @Test
    fun observedConstantsMatchCentral() {
        assertEquals(RideOnUuids.RIDING_DATA_FIELD5_SPEED_MULTIPLIER, RideOnWire.RIDING_DATA_FIELD5_SPEED_MULTIPLIER, 0.0)
        assertEquals(RideOnUuids.RIDING_DATA_FIELD6_OBSERVED_CONSTANT, RideOnWire.RIDING_DATA_FIELD6_OBSERVED_CONSTANT)
    }

    @Test
    fun bothDecodersReadEveryFrameTheSameWay() {
        val corpus = builtFrames.values.flatMap { listOf(it.first, it.second) } + malformedFrames()
        corpus.forEach { bytes -> assertEquals(centralShape(bytes), simulatorShape(bytes)) }
    }

    @Test
    fun centralDecodesEverySimulatorFrame() {
        builtFrames.forEach { (name, pair) ->
            assertTrue("central rejected $name", RideOnProtoCodec.decode(pair.first) is RideOnDecodeResult.Decoded)
        }
    }

    @Test
    fun centralTelemetryReadsSimulatorRidingData() {
        val telemetry = decodeRideOnTelemetry(RideOnWire.ridingData(215, 88, 3_250, 131))
        assertNotNull(telemetry)
        assertEquals(215, telemetry!!.powerWatts)
        assertEquals(88, telemetry.cadenceRpm)
        assertEquals(32.5, telemetry.speedKph!!, 1e-9)
        assertEquals(131, telemetry.heartRateBpm)
        assertEquals(
            mapOf(5 to (3_250 * RideOnUuids.RIDING_DATA_FIELD5_SPEED_MULTIPLIER).toLong(), 6 to RideOnUuids.RIDING_DATA_FIELD6_OBSERVED_CONSTANT),
            telemetry.diagnosticFields,
        )
    }

    private fun malformedFrames(): List<ByteArray> {
        val good = RideOnProtoCodec.ridingData(215, 88, 3_250, 131)
        return listOf(
            byteArrayOf(),
            byteArrayOf(0x03),
            good.copyOf(good.size - 1),
            good.copyOf(3),
            byteArrayOf(0x03, 0x08),
            byteArrayOf(0x03, 0x0A, 0x7F),
            ByteArray(600) { 0x08 },
            byteArrayOf(0x03, 0x0B, 0x00),
        )
    }

    private fun centralShape(bytes: ByteArray): String = when (val r = RideOnProtoCodec.decode(bytes)) {
        is RideOnDecodeResult.Invalid -> "invalid"
        is RideOnDecodeResult.Decoded ->
            "${r.packet.opcode}:" + r.packet.fields.joinToString(",") {
                when (it) {
                    is RideOnProtoField.Varint -> "${it.number}v${it.value}"
                    is RideOnProtoField.Fixed64 -> "${it.number}f64${it.bytes.toList()}"
                    is RideOnProtoField.Bytes -> "${it.number}b${it.value.toList()}"
                    is RideOnProtoField.Fixed32 -> "${it.number}f32${it.bytes.toList()}"
                }
            }
    }

    private fun simulatorShape(bytes: ByteArray): String = when (val r = RideOnWire.decode(bytes)) {
        is RideOnWireDecodeResult.Invalid -> "invalid"
        is RideOnWireDecodeResult.Decoded ->
            "${r.packet.opcode}:" + r.packet.fields.joinToString(",") {
                when (it) {
                    is RideOnWireField.Varint -> "${it.number}v${it.value}"
                    is RideOnWireField.Fixed64 -> "${it.number}f64${it.bytes.toList()}"
                    is RideOnWireField.Bytes -> "${it.number}b${it.value.toList()}"
                    is RideOnWireField.Fixed32 -> "${it.number}f32${it.bytes.toList()}"
                }
            }
    }
}
