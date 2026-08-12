package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Concept2CsafeCodecTest {
    @Test
    fun `captured terminate and Just Row frames produce documented alternating responses`() {
        val link = Concept2CsafeLink()

        assertAccepted(
            hex("F1 01 76 01 13 65 F2"),
            link.accept(hex("F1 76 04 13 02 01 02 60 F2"), 1_000),
        )
        assertEquals(1, link.handshakeState.screenType)
        assertEquals(2, link.handshakeState.screenValue)

        assertAccepted(
            hex("F1 81 76 02 01 13 E7 F2"),
            link.accept(hex("F1 76 07 01 01 01 13 02 01 01 61 F2"), 1_050),
        )
        assertEquals(1, link.handshakeState.workoutType)
        assertEquals(1, link.handshakeState.screenType)
        assertEquals(1, link.handshakeState.screenValue)
        assertArrayEquals(hex("F1 81 76 02 01 13 E7 F2"), link.response)
    }

    @Test
    fun `vendor terminate typo 62 fails while captured XOR checksum 60 passes`() {
        assertFailure(
            Concept2CsafeCodec.FailureReason.BAD_CHECKSUM,
            Concept2CsafeCodec.decodeStandard(hex("F1 76 04 13 02 01 02 62 F2")),
        )
        assertTrue(
            Concept2CsafeCodec.decodeStandard(hex("F1 76 04 13 02 01 02 60 F2"))
                is Concept2CsafeCodec.DecodeResult.Success,
        )
    }

    @Test
    fun `codec round trips all stuffed flag bytes`() {
        val contents = hex("20 04 F0 F1 F2 F3")
        val wire = Concept2CsafeCodec.encodeStandard(contents)!!
        assertArrayEquals(hex("F1 20 04 F3 00 F3 01 F3 02 F3 03 24 F2"), wire)
        val decoded = Concept2CsafeCodec.decodeStandard(wire) as Concept2CsafeCodec.DecodeResult.Success
        assertEquals(1, decoded.commands.size)
        assertEquals(0x20, decoded.commands.single().identifier)
        assertArrayEquals(hex("F0 F1 F2 F3"), decoded.commands.single().data)
    }

    @Test
    fun `generic command parser accepts complete short long and multiple commands`() {
        val parsed = Concept2CsafeCodec.parseCommandBytes(hex("80 20 02 AA BB 81"))
            as Concept2CsafeCodec.DecodeResult.Success
        assertEquals(listOf(0x80, 0x20, 0x81), parsed.commands.map { it.identifier })
        assertArrayEquals(byteArrayOf(), parsed.commands[0].data)
        assertArrayEquals(hex("AA BB"), parsed.commands[1].data)
    }

    @Test
    fun `malformed frames are rejected before command execution`() {
        assertFailure(
            Concept2CsafeCodec.FailureReason.INVALID_DELIMITERS,
            Concept2CsafeCodec.decodeStandard(hex("F1 76 00 76")),
        )
        assertFailure(
            Concept2CsafeCodec.FailureReason.INVALID_BYTE_STUFFING,
            Concept2CsafeCodec.decodeStandard(hex("F1 F3 04 F2")),
        )
        assertFailure(
            Concept2CsafeCodec.FailureReason.TRUNCATED_COMMAND,
            Concept2CsafeCodec.decodeStandard(hex("F1 76 04 13 02 63 F2")),
        )
        assertFailure(
            Concept2CsafeCodec.FailureReason.EXTENDED_FRAME_UNSUPPORTED,
            Concept2CsafeCodec.decodeStandard(hex("F0 00 FD 00 F2")),
        )
        assertFailure(
            Concept2CsafeCodec.FailureReason.FRAME_TOO_LARGE,
            Concept2CsafeCodec.decodeStandard(ByteArray(121) { 0 }),
        )
    }

    @Test
    fun `oversized encoded frame is not emitted`() {
        assertNull(Concept2CsafeCodec.encodeStandard(ByteArray(118) { 1 }))
    }

    @Test
    fun `too-fast or unsupported request preserves response toggle and captured state`() {
        val link = Concept2CsafeLink()
        val terminate = hex("F1 76 04 13 02 01 02 60 F2")
        val prepare = hex("F1 76 07 01 01 01 13 02 01 01 61 F2")
        val first = link.accept(terminate, 100)
        assertTrue(first is Concept2CsafeLink.RequestResult.Accepted)
        val saved = link.response

        assertRejected(Concept2CsafeLink.RejectionReason.TOO_SOON, link.accept(prepare, 149))
        assertArrayEquals(saved, link.response)
        assertEquals(2, link.handshakeState.screenValue)

        val unsupportedContents = hex("76 03 13 01 09")
        val unsupported = Concept2CsafeCodec.encodeStandard(unsupportedContents)!!
        assertRejected(Concept2CsafeLink.RejectionReason.UNSUPPORTED, link.accept(unsupported, 150))
        assertArrayEquals(saved, link.response)

        assertAccepted(hex("F1 81 76 02 01 13 E7 F2"), link.accept(prepare, 151))
    }

    @Test
    fun `link state response and timing are isolated and reset together`() {
        val first = Concept2Pm5Session()
        val second = Concept2Pm5Session()
        val terminate = hex("F1 76 04 13 02 01 02 60 F2")

        assertTrue(first.acceptCsafeRequest(terminate, 100) is Concept2CsafeLink.RequestResult.Accepted)
        assertNull(second.csafeResponse)
        assertEquals(2, first.csafeHandshakeState.screenValue)
        assertNull(second.csafeHandshakeState.screenValue)

        first.reset()
        assertNull(first.csafeResponse)
        assertNull(first.csafeHandshakeState.screenValue)
        assertAccepted(hex("F1 01 76 01 13 65 F2"), first.acceptCsafeRequest(terminate, 101))
    }

    private fun assertAccepted(expected: ByteArray, result: Concept2CsafeLink.RequestResult) {
        assertTrue(result is Concept2CsafeLink.RequestResult.Accepted)
        assertArrayEquals(expected, (result as Concept2CsafeLink.RequestResult.Accepted).response)
    }

    private fun assertRejected(
        expected: Concept2CsafeLink.RejectionReason,
        result: Concept2CsafeLink.RequestResult,
    ) {
        assertTrue(result is Concept2CsafeLink.RequestResult.Rejected)
        assertEquals(expected, (result as Concept2CsafeLink.RequestResult.Rejected).reason)
    }

    private fun assertFailure(
        expected: Concept2CsafeCodec.FailureReason,
        result: Concept2CsafeCodec.DecodeResult,
    ) {
        assertTrue(result is Concept2CsafeCodec.DecodeResult.Failure)
        assertEquals(expected, (result as Concept2CsafeCodec.DecodeResult.Failure).reason)
    }

    private fun hex(value: String): ByteArray = value.trim().split(Regex("\\s+")).map {
        it.toInt(16).toByte()
    }.toByteArray()
}
