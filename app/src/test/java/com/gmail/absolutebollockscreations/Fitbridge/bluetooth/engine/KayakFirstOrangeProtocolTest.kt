package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.bluetooth.BluetoothGattCharacteristic
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow

class KayakFirstOrangeProtocolTest {
    @Test
    fun `language neutral adversarial corpus replays through parser`() {
        val text = requireNotNull(javaClass.classLoader?.getResourceAsStream(
            "fixtures/simulated/kayak_first_orange_adversarial_v1.json",
        )).bufferedReader().use { it.readText() }
        val fixture = FixtureJson.obj(FixtureJson.parse(text))
        assertEquals(1, FixtureJson.int(fixture["schema_version"]))
        assertEquals(
            listOf(65_008, 335_046, 70_009, 340_048),
            FixtureJson.array(fixture["steady_tuple_us"]).map(FixtureJson::int),
        )
        FixtureJson.array(fixture["cases"]).map(FixtureJson::obj).forEach { case ->
            val parser = KayakFirstOrangeResponseParser()
            val result = FixtureJson.array(case["lines"])
                .map(FixtureJson::string)
                .mapNotNull(parser::accept)
                .single()
            val actual = when (result) {
                KayakFirstOrangeResponse.ResetOk -> "reset"
                KayakFirstOrangeResponse.Rejected -> "reject"
                is KayakFirstOrangeResponse.Timing -> if (
                    result.value.command == KayakFirstOrangeCommand.AUXILIARY
                ) "auxiliary" else "timing"
            }
            assertEquals(FixtureJson.string(case["id"]), FixtureJson.string(case["expect"]), actual)
        }
    }

    @Test
    fun `legacy Orange discovery tolerates absent CCCD without weakening Blue`() {
        val inventory = KayakFirstGattInventory(
            mapOf(
                BluetoothIds.UUID_KAYAK_FIRST_SERVICE to mapOf(
                    BluetoothIds.UUID_KAYAK_FIRST_IO to KayakFirstCharacteristicInventory(
                        properties = BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                        hasCccd = false,
                    ),
                ),
            ),
        )
        assertEquals(
            KayakFirstDiscoveryFailure.IO_MISSING_CCCD,
            planKayakFirstDiscovery(KAYAK_FIRST_CANDIDATE_ID, inventory).failure,
        )
        val orange = planKayakFirstDiscovery(
            KAYAK_FIRST_CANDIDATE_ID,
            inventory,
            allowMissingCccd = true,
        )
        assertTrue(orange.activate)
        assertNull(orange.notificationTarget)
    }

    @Test
    fun `numeric prefix selects official generation boundary`() {
        assertEquals(KayakFirstGeneration.ORANGE, kayakFirstGeneration("17100001"))
        assertEquals(KayakFirstGeneration.BLUE, kayakFirstGeneration("18100001"))
        assertEquals(KayakFirstGeneration.BLUE, kayakFirstGeneration("25100001"))
        assertNull(kayakFirstGeneration("17BLUE01"))
        assertNull(kayakFirstGeneration("1710000"))
    }

    @Test
    fun `commands are exactly one ascii digit without terminator`() {
        assertArrayEquals(byteArrayOf('1'.code.toByte()), KayakFirstOrangeCommand.T_MIN.bytes())
        assertArrayEquals(byteArrayOf('2'.code.toByte()), KayakFirstOrangeCommand.T_H.bytes())
        assertArrayEquals(byteArrayOf('3'.code.toByte()), KayakFirstOrangeCommand.T_MAX.bytes())
        assertArrayEquals(byteArrayOf('4'.code.toByte()), KayakFirstOrangeCommand.T_V.bytes())
        assertArrayEquals(byteArrayOf('5'.code.toByte()), KayakFirstOrangeCommand.AUXILIARY.bytes())
        assertArrayEquals(byteArrayOf('9'.code.toByte()), KayakFirstOrangeCommand.RESET.bytes())
    }

    @Test
    fun `LF assembler accepts fragmented coalesced and legacy CRLF lines`() {
        val assembler = KayakFirstOrangeLineAssembler()
        assertTrue(assembler.accept("1\n7".toByteArray()).single() is KayakFirstOrangeFrameEvent.Line)
        val completed = assembler.accept("\n0000065008\n2\r\n".toByteArray())
            .filterIsInstance<KayakFirstOrangeFrameEvent.Line>()
            .map { it.value }
        assertEquals(listOf("7", "0000065008", "2"), completed)
    }

    @Test
    fun `LF assembler discards an overlong or non ascii line and recovers at boundary`() {
        val bounded = KayakFirstOrangeLineAssembler(maximumLineBytes = 3)
        val overflow = bounded.accept("1234suffix\nOK\n".toByteArray())
        assertTrue(overflow.first() is KayakFirstOrangeFrameEvent.TooLong)
        assertEquals(
            "OK",
            (overflow.last() as KayakFirstOrangeFrameEvent.Line).value,
        )
        val invalid = KayakFirstOrangeLineAssembler().accept(byteArrayOf(0xff.toByte(), '\n'.code.toByte()))
        assertTrue(invalid.single() is KayakFirstOrangeFrameEvent.InvalidAscii)
    }

    @Test
    fun `response parser decodes microseconds and rejects grammar overflow and bounds`() {
        fun parse(lines: List<String>): KayakFirstOrangeResponse =
            KayakFirstOrangeResponseParser().let { parser ->
                lines.firstNotNullOfOrNull(parser::accept)
                    ?: error("No terminal response for $lines")
            }

        val decoded = parse(listOf("1", "42", "0000065008"))
            as KayakFirstOrangeResponse.Timing
        assertEquals(KayakFirstOrangeCommand.T_MIN, decoded.value.command)
        assertEquals(42L, decoded.value.sequence)
        assertEquals(0.065008, decoded.value.timingSeconds, 0.0)
        assertEquals(KayakFirstOrangeResponse.ResetOk, parse(listOf("OK")))
        assertEquals(KayakFirstOrangeResponse.Rejected, parse(listOf("1", "-1", "0000065008")))
        assertEquals(KayakFirstOrangeResponse.Rejected, parse(listOf("1", "1", "10000000000")))
        assertEquals(KayakFirstOrangeResponse.Rejected, parse(listOf("1", "1", "0000000000")))

        val recovering = KayakFirstOrangeResponseParser()
        assertEquals(KayakFirstOrangeResponse.Rejected, recovering.accept("bad"))
        assertNull(recovering.accept("2"))
        assertNull(recovering.accept("8"))
        assertTrue(recovering.accept("0000335046") is KayakFirstOrangeResponse.Timing)
    }

    @Test
    fun `E2 physics uses canonical constants and equations`() {
        val timing = KayakFirstOrangeTimingTuple(0.065008, 0.335046, 0.070009, 0.340048)
        val result = requireNotNull(KayakFirstOrangePhysics.calculate(timing, 80.0))
        val omegaMax = 2.0 * PI / timing.tMinSeconds
        val omegaMin = 2.0 * PI / timing.tMaxSeconds
        val expectedPower = (0.5 * 0.02527962 * omegaMax.pow(2.0) -
            0.5 * 0.02527962 * omegaMin.pow(2.0)) / timing.tHSeconds
        val expectedForce = 0.02527962 * (omegaMax - omegaMin) / (0.027 * timing.tHSeconds)
        val expectedSpeed = (expectedPower / (0.1419262 * 92.0.pow(2.0 / 3.0))).pow(1.0 / 3.0)
        assertEquals(expectedPower, result.powerWatts, 1e-9)
        assertEquals(expectedForce, result.forceNewtons, 1e-9)
        assertEquals(expectedSpeed, result.speedMetresPerSecond, 1e-9)
        assertEquals(60.0 / (0.335046 + 0.340048), result.strokeRateSpm, 1e-9)
        assertNull(KayakFirstOrangePhysics.calculate(timing, null))
        assertNull(KayakFirstOrangePhysics.calculate(timing.copy(tMaxSeconds = 0.05), 80.0))
    }

    @Test
    fun `session performs reset then 1234 cadence and ignores duplicate tuple for integration`() {
        val session = KayakFirstOrangeSession { 80.0 }
        assertEquals(KayakFirstOrangeCommand.RESET, session.start())
        assertEquals(
            KayakFirstOrangeCommand.T_MIN,
            session.onResponse(KayakFirstOrangeResponse.ResetOk, 0L).first,
        )
        fun response(command: KayakFirstOrangeCommand, sequence: Long, timing: Long) =
            KayakFirstOrangeResponse.Timing(
                KayakFirstOrangeTimingResponse(command, sequence, timing),
            )
        fun tuple(base: Long, at: Long): KayakFirstOrangeReading? {
            session.onResponse(response(KayakFirstOrangeCommand.T_MIN, base, 65_008), at)
            session.onResponse(response(KayakFirstOrangeCommand.T_H, base + 1, 335_046), at)
            session.onResponse(response(KayakFirstOrangeCommand.T_MAX, base + 2, 70_009), at)
            return session.onResponse(response(KayakFirstOrangeCommand.T_V, base + 3, 340_048), at).second
        }
        val first = requireNotNull(tuple(1, 1_000))
        assertEquals(0.0, first.distanceMetres, 0.0)
        val second = requireNotNull(tuple(5, 2_000))
        assertEquals(second.physics.speedMetresPerSecond, second.distanceMetres, 1e-9)
        // Replaying the same four sequence values produces no reading and therefore no integration.
        assertNull(tuple(5, 3_000))
        val third = requireNotNull(tuple(9, 4_000))
        assertEquals(3.0, third.elapsedSeconds, 0.0)
        assertEquals(third.physics.speedMetresPerSecond * 3.0, third.distanceMetres, 1e-9)
        assertTrue(third.strokeCount > 0.0)
    }

    @Test
    fun `orange tuples represent the interval since the previous tuple not the session clock`() {
        val session = KayakFirstOrangeSession { 80.0 }
        session.onResponse(KayakFirstOrangeResponse.ResetOk, 0L)
        fun response(command: KayakFirstOrangeCommand, sequence: Long, timing: Long) =
            KayakFirstOrangeResponse.Timing(
                KayakFirstOrangeTimingResponse(command, sequence, timing),
            )
        fun tuple(base: Long, at: Long): KayakFirstOrangeReading? {
            session.onResponse(response(KayakFirstOrangeCommand.T_MIN, base, 65_008), at)
            session.onResponse(response(KayakFirstOrangeCommand.T_H, base + 1, 335_046), at)
            session.onResponse(response(KayakFirstOrangeCommand.T_MAX, base + 2, 70_009), at)
            return session.onResponse(response(KayakFirstOrangeCommand.T_V, base + 3, 340_048), at).second
        }
        // The first tuple has no previous instant to measure from, so it represents nothing.
        assertEquals(0.0, requireNotNull(tuple(1, 1_000)).representedSeconds, 0.0)
        assertEquals(1.0, requireNotNull(tuple(5, 2_000)).representedSeconds, 1e-9)
        // A missed poll represents the real gap, not one nominal period.
        val third = requireNotNull(tuple(9, 4_500))
        assertEquals(2.5, third.representedSeconds, 1e-9)
        // ...and never the cumulative clock, which is what energy was previously weighted by.
        assertEquals(3.5, third.elapsedSeconds, 1e-9)

        // Energy must integrate the represented interval. Weighting by the cumulative clock, as
        // the handler previously did, integrates power against the whole session on every frame
        // and grows the total without bound. Minutes rather than seconds here so the comparison
        // survives the estimator's kcal rounding.
        val power = third.physics.powerWatts
        val frames = listOf(0.0 to 0.0, 600.0 to 600.0, 900.0 to 1_500.0)
        val correct = KayakFirstEnergyEstimator()
        val defective = KayakFirstEnergyEstimator()
        var byRepresented = 0.0
        var byElapsed = 0.0
        frames.forEach { (represented, elapsed) ->
            byRepresented = correct.sample(represented, power).totalKilocalories
            byElapsed = defective.sample(elapsed, power).totalKilocalories
        }
        assertEquals(
            kotlin.math.round(
                power * 1_500.0 / (KayakFirstEnergyEstimator.JOULES_PER_KILOCALORIE *
                    KayakFirstEnergyEstimator.METABOLIC_EFFICIENCY),
            ),
            byRepresented,
            0.0,
        )
        assertTrue(byElapsed > byRepresented)
    }

    @Test
    fun `silent reset can advance once without fabricating an acknowledgement`() {
        val session = KayakFirstOrangeSession { 80.0 }
        assertEquals(KayakFirstOrangeCommand.T_MIN, session.assumeResetComplete())
        assertNull(session.assumeResetComplete())
    }

    @Test
    fun `mismatched command cannot advance polling state`() {
        val session = KayakFirstOrangeSession { 80.0 }
        session.onResponse(KayakFirstOrangeResponse.ResetOk, 0L)
        val wrong = KayakFirstOrangeResponse.Timing(
            KayakFirstOrangeTimingResponse(KayakFirstOrangeCommand.T_H, 1, 335_046),
        )
        assertEquals(null to null, session.onResponse(wrong, 1L))
    }
}
