package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.Concept2WorkoutGoalKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Concept2CsafeCodecTest {
    @Test
    fun `captured ErgData envelope and compound single profiles are accepted atomically`() {
        val distance = Concept2CsafeLink()
        assertAccepted(
            hex("01 F1 01 76 05 01 03 05 14 13 72 F2"),
            distance.accept(
                hex("01 F1 76 18 01 01 03 03 05 80 00 00 03 E8 05 05 80 00 00 00 C8 14 01 01 13 02 01 01 4D F2"),
                100,
            ),
        )
        assertEquals(Concept2WorkoutGoalKind.DISTANCE, distance.handshakeState.workoutProgram?.goalKind)
        assertEquals(1_000, distance.handshakeState.workoutProgram?.goalValue)
        assertEquals(Concept2WorkoutGoalKind.DISTANCE, distance.handshakeState.workoutProgram?.splitGoalKind)
        assertEquals(200, distance.handshakeState.workoutProgram?.splitGoalValue)

        val time = Concept2CsafeLink()
        assertTrue(time.accept(
            hex("01 F1 76 18 01 01 05 03 05 00 00 00 17 70 05 05 00 00 00 17 70 14 01 01 13 02 01 01 68 F2"),
            100,
        ) is Concept2CsafeLink.RequestResult.Accepted)
        assertEquals(Concept2WorkoutGoalKind.TIME, time.handshakeState.workoutProgram?.goalKind)
        assertEquals(6_000, time.handshakeState.workoutProgram?.goalValue)

        val calories = Concept2CsafeLink()
        assertTrue(calories.accept(
            hex("F1 76 18 01 01 0A 03 05 40 00 00 00 64 05 05 40 00 00 00 14 14 01 01 13 02 01 01 17 F2"),
            100,
        ) is Concept2CsafeLink.RequestResult.Accepted)
        assertEquals(Concept2WorkoutGoalKind.CALORIES, calories.handshakeState.workoutProgram?.goalKind)
        assertEquals(100, calories.handshakeState.workoutProgram?.goalValue)
        assertEquals(Concept2WorkoutGoalKind.CALORIES, calories.handshakeState.workoutProgram?.splitGoalKind)
        assertEquals(20, calories.handshakeState.workoutProgram?.splitGoalValue)
    }

    @Test
    fun `single profile rejects a split duration type that disagrees with its workout duration`() {
        val link = Concept2CsafeLink()

        assertRejected(
            Concept2CsafeLink.RejectionReason.UNSUPPORTED,
            link.accept(configuration(
                0x01, 1, 3,
                0x03, 5, 0x80, 0, 0, 3, 0xE8,
                0x05, 5, 0x40, 0, 0, 0, 50,
                0x14, 1, 1,
                0x13, 2, 1, 1,
            ), 100),
        )
    }

    @Test
    fun `fixed interval profile retains work goal and rest duration`() {
        val link = Concept2CsafeLink()
        assertTrue(link.accept(
            hex("F1 76 15 01 01 06 03 05 00 00 00 2E E0 04 02 00 1E 14 01 01 13 02 01 01 B0 F2"),
            100,
        ) is Concept2CsafeLink.RequestResult.Accepted)

        val program = link.handshakeState.workoutProgram!!
        assertTrue(program.isInterval)
        assertEquals(Concept2WorkoutGoalKind.TIME, program.goalKind)
        assertEquals(12_000, program.goalValue)
        assertEquals(30, program.restSeconds)
    }

    @Test
    fun `target heart rate and PM game target stroke rate are retained by a profile`() {
        val link = Concept2CsafeLink()
        val heartRate = Concept2CsafeCodec.encodeStandard(hex("30 01 96"))!!
        assertTrue(link.accept(heartRate, 100) is Concept2CsafeLink.RequestResult.Accepted)

        val gameParams = ByteArray(22).also { it[21] = 30 }
        val targetStroke = Concept2CsafeCodec.encodeStandard(
            byteArrayOf(0x77, 24, 0x38, 22) + gameParams,
        )!!
        assertTrue(link.accept(targetStroke, 150) is Concept2CsafeLink.RequestResult.Accepted)

        assertTrue(link.accept(
            hex("F1 76 18 01 01 05 03 05 00 00 00 17 70 05 05 00 00 00 17 70 14 01 01 13 02 01 01 68 F2"),
            200,
        ) is Concept2CsafeLink.RequestResult.Accepted)

        assertEquals(30, link.handshakeState.workoutProgram?.targetStrokeRate)
        assertEquals(150, link.handshakeState.workoutProgram?.targetHeartRateBpm)
    }

    @Test
    fun `variable intervals retain ordered mixed goals and entry-local targets`() {
        val link = Concept2CsafeLink()
        assertTrue(link.accept(configuration(
            0x18, 1, 0,
            0x01, 1, 8,
            0x17, 1, 1,
            0x03, 5, 0x80, 0, 0, 1, 0xF4,
            0x04, 2, 0, 10,
            0x06, 4, 0, 0, 0x27, 0x10,
            0x15, 2, 0, 250,
            0x14, 1, 1,
        ), 100) is Concept2CsafeLink.RequestResult.Accepted)
        assertNull(link.handshakeState.workoutProgram)

        assertTrue(link.accept(configuration(
            0x18, 1, 1,
            0x17, 1, 0,
            0x03, 5, 0, 0, 0, 0, 200,
            0x04, 2, 0, 0,
            0x16, 2, 1, 0xF4,
            0x14, 1, 1,
            0x13, 2, 1, 1,
        ), 150) is Concept2CsafeLink.RequestResult.Accepted)

        val program = link.handshakeState.workoutProgram!!
        assertTrue(program.isVariableInterval)
        assertEquals(2, program.intervals.size)
        assertEquals(Concept2WorkoutGoalKind.DISTANCE, program.intervals[0].goalKind)
        assertEquals(500, program.intervals[0].goalValue)
        assertEquals(10_000, program.intervals[0].targetPaceCentiseconds)
        assertEquals(250, program.intervals[0].targetPowerWatts)
        assertEquals(Concept2WorkoutGoalKind.TIME, program.intervals[1].goalKind)
        assertEquals(200, program.intervals[1].goalValue)
        assertEquals(500, program.intervals[1].targetCaloriesPerHour)
        assertNull(program.intervals[1].targetPaceCentiseconds)
        assertNull(program.intervals[1].targetPowerWatts)
    }

    @Test
    fun `official variable undefined-rest profile sentinel is retained and recognized`() {
        val link = Concept2CsafeLink()
        assertTrue(link.accept(configuration(
            0x18, 1, 0,
            0x01, 1, 8,
            0x17, 1, 4,
            0x03, 5, 0x80, 0, 0, 0, 100,
            0x04, 2, 0, 0,
            0x14, 1, 1,
        ), 100) is Concept2CsafeLink.RequestResult.Accepted)

        assertTrue(link.accept(configuration(
            0x18, 1, 1,
            0x17, 1, 3,
            0x03, 5, 0, 0, 0, 0x2E, 0xE0,
            0x04, 2, 0, 0,
            0x14, 1, 1,
            0x01, 1, 9,
            0x05, 5, 0x80, 0, 0, 0, 0,
            0x13, 2, 1, 1,
        ), 150) is Concept2CsafeLink.RequestResult.Accepted)

        val program = link.handshakeState.workoutProgram!!
        assertEquals(9, program.workoutType)
        assertEquals(2, program.intervals.size)
        assertTrue(program.intervals.all { it.restSeconds == null })
        assertEquals(0, link.handshakeState.splitDuration)
    }

    @Test
    fun `variable interval list accepts fifty contiguous entries and rejects index fifty`() {
        val link = Concept2CsafeLink()
        repeat(50) { index ->
            val commands = mutableListOf(
                0x18, 1, index,
                0x17, 1, 0,
                0x03, 5, 0, 0, 0, 0, 100,
                0x04, 2, 0, 0,
                0x14, 1, 1,
            )
            if (index == 0) commands.addAll(3, listOf(0x01, 1, 8))
            if (index == 49) commands.addAll(listOf(0x13, 2, 1, 1))
            assertTrue(
                link.accept(configuration(*commands.toIntArray()), 100L + index * 50L)
                    is Concept2CsafeLink.RequestResult.Accepted,
            )
        }
        assertEquals(50, link.handshakeState.workoutProgram?.intervals?.size)

        assertRejected(
            Concept2CsafeLink.RejectionReason.UNSUPPORTED,
            link.accept(configuration(0x18, 1, 50), 2_600),
        )
        assertEquals(50, link.handshakeState.workoutProgram?.intervals?.size)
    }

    @Test
    fun `gapped variable interval configuration stays incomplete`() {
        val link = Concept2CsafeLink()
        assertTrue(link.accept(configuration(
            0x18, 1, 1,
            0x01, 1, 8,
            0x17, 1, 0,
            0x03, 5, 0, 0, 0, 0, 100,
            0x04, 2, 0, 0,
            0x14, 1, 1,
            0x13, 2, 1, 1,
        ), 100) is Concept2CsafeLink.RequestResult.Accepted)

        assertNull(link.handshakeState.workoutProgram)
        assertEquals(setOf(1), link.handshakeState.configuredIntervals.keys)
    }

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

    @Test
    fun `accepted response is notified only for that subscribed client and reset clears readiness`() {
        val subscribed = Concept2Pm5Session()
        val other = Concept2Pm5Session()
        val terminate = hex("F1 76 04 13 02 01 02 60 F2")
        subscribed.setSubscribed(BluetoothIds.UUID_C2_CSAFE_RESPONSE, true)

        val result = subscribed.acceptCsafeRequest(terminate, 100)

        assertArrayEquals(
            hex("F1 01 76 01 13 65 F2"),
            subscribed.notificationForAcceptedCsafe(result),
        )
        assertNull(other.notificationForAcceptedCsafe(result))
        subscribed.reset()
        assertFalse(subscribed.isSubscribed(BluetoothIds.UUID_C2_CSAFE_RESPONSE))
        assertNull(subscribed.csafeResponse)
    }

    @Test
    fun `workout reset clears CSAFE configuration but retains response subscription`() {
        val session = Concept2Pm5Session()
        val terminate = hex("F1 76 04 13 02 01 02 60 F2")
        session.setSubscribed(BluetoothIds.UUID_C2_CSAFE_RESPONSE, true)
        assertTrue(session.acceptCsafeRequest(terminate, 100) is Concept2CsafeLink.RequestResult.Accepted)

        session.resetCsafeWorkoutState()

        assertTrue(session.isSubscribed(BluetoothIds.UUID_C2_CSAFE_RESPONSE))
        assertNull(session.csafeResponse)
        assertNull(session.csafeHandshakeState.workoutType)
        assertNull(session.csafeHandshakeState.screenType)
        assertNull(session.csafeHandshakeState.screenValue)
        assertAccepted(
            hex("F1 01 76 01 13 65 F2"),
            session.acceptCsafeRequest(terminate, 100),
        )
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

    private fun configuration(vararg commands: Int): ByteArray {
        val nested = commands.map { it.toByte() }.toByteArray()
        return Concept2CsafeCodec.encodeStandard(byteArrayOf(0x76, nested.size.toByte()) + nested)!!
    }
}
