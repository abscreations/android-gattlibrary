package com.gmail.absolutebollockscreations.gattlibrary.simulator

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SoleCaptureTest {
    @Test
    fun `simulated discovery retains the captured FTMS and proprietary surfaces`() {
        for (profile in SoleSimulatorProfile.entries) {
            val fixture = soleFixture(profile)
            val surface = fixture.forSimulation(profile)
            assertEquals(profile.name, fixture.advertisedServices, surface.advertisedServices)
            assertArrayEquals(profile.name, fixture.ftmsServiceData, surface.ftmsServiceData)
            assertTrue(soleUuid("1826") in surface.advertisedServices)
            assertTrue(soleUuid("1826") in surface.services)
            assertTrue(surface.services.getValue(soleUuid("1826"))
                .any { it.key == profile.ftmsWorkoutKey && it.hasCccd })
            val ftms = surface.services.getValue(soleUuid("1826")).associateBy { it.key.characteristic }
            val feature = checkNotNull(ftms[soleUuid("2acc")]?.readValue)
            if (profile == SoleSimulatorProfile.F65) {
                assertArrayEquals(ByteArray(4), feature)
            } else {
                assertEquals(8, feature.size)
                assertArrayEquals(ByteArray(4), feature.copyOfRange(4, 8))
            }
            assertFalse(soleUuid("2ad9") in ftms)
            assertTrue(ftms.keys.intersect(SoleCapture.targetRangeUuids).isEmpty())
            if (profile.usesProprietaryWorkout) {
                assertTrue(surface.services.getValue(SoleCapture.vendorNotify.service)
                    .any { it.key == SoleCapture.vendorNotify && it.hasCccd })
                assertFalse(SoleReplayLane.CONTROL in surface.lanes)
                val vendorReplies = surface.lanes.getValue(SoleReplayLane.VENDOR)
                    .filter { it.operation == SoleReplayOperation.NOTIFY }
                    .map { it.value }
                assertTrue(vendorReplies.any { it.size >= 4 && it[2] == 0xf0.toByte() })
                assertFalse(vendorReplies.any {
                    it.size in listOf(18, 20, 22) && it[0] == 0x5b.toByte() && it[2] == 0x06.toByte()
                })
                if (profile == SoleSimulatorProfile.R92) {
                    // The capture has one standalone empty frame. Other two-byte
                    // notifications ending in 5D are tails of split workout frames.
                    assertEquals(
                        listOf("5b5d"),
                        vendorReplies.filter { it.size == 2 && it.last() == 0x5d.toByte() }
                            .map { it.toHexString() },
                    )
                }
            }
            assertFalse(SoleReplayLane.TREADMILL in surface.lanes)
            assertFalse(SoleReplayLane.CONTROL in surface.lanes)
        }
    }

    @Test
    fun `only selected Sole personas expose synthetic FTMS controls and ranges`() {
        val controlled = setOf(SoleSimulatorProfile.ST90, SoleSimulatorProfile.F80_MODERN, SoleSimulatorProfile.F85_59)
        for (profile in SoleSimulatorProfile.entries) {
            val targets = profile.testControlTargets
            assertEquals(profile.name, profile in controlled, targets.any)
            val fixture = soleFixture(profile)
            val ftms = fixture.forSimulation(profile, targets)
                .services.getValue(soleUuid("1826")).associateBy { it.key.characteristic }
            assertEquals(profile in controlled, soleUuid("2ad9") in ftms)
            if (profile !in controlled) continue
            assertArrayEquals("03000000".hexToByteArray(), checkNotNull(ftms[soleUuid("2acc")]?.readValue).copyOfRange(4, 8))
            assertArrayEquals("0000b80b0a00".hexToByteArray(), ftms[soleUuid("2ad4")]?.readValue)
            assertArrayEquals("9cff2c010500".hexToByteArray(), ftms[soleUuid("2ad5")]?.readValue)
            val inclineOnly = fixture.forSimulation(profile, targets.copy(speed = false, speedRange = null))
                .services.getValue(soleUuid("1826")).associateBy { it.key.characteristic }
            assertFalse(soleUuid("2ad4") in inclineOnly)
            assertArrayEquals("02000000".hexToByteArray(),
                checkNotNull(inclineOnly[soleUuid("2acc")]?.readValue).copyOfRange(4, 8))
        }
    }

    @Test
    fun `generated Sole packets carry live metrics and retain device framing`() {
        val sample = FtmsMockSample(
            elapsedSeconds = 9, heartRate = 141, caloriesTotal = 3,
            caloriesPerHour = 180, caloriesPerMinute = 3, speedKph = 16.09344,
            cadence = 83.0, resistance = 7, inclinationPercent = 4.0, powerWatts = 200,
        )
        val treadmill = soleVendorWorkoutFrames(SoleSimulatorProfile.F63, sample, 20).single()
        assertEquals(18, treadmill.size)
        assertArrayEquals("5b0f06".hexToByteArray(), treadmill.copyOfRange(0, 3))
        assertArrayEquals(byteArrayOf(141.toByte(), 100, 4), treadmill.copyOfRange(9, 12))
        assertEquals(0x5d.toByte(), treadmill.last())
        val lcb = soleVendorWorkoutFrames(SoleSimulatorProfile.LCB, sample, 22)
        val r92 = soleVendorWorkoutFrames(SoleSimulatorProfile.R92, sample, 20)
        assertArrayEquals("5b0213075d".hexToByteArray(), lcb.first())
        assertEquals(listOf(5, 20, 2), r92.map { it.size })
        assertArrayEquals(lcb[1], r92[1] + r92[2])
        assertArrayEquals("5303e800c8".hexToByteArray(), lcb[1].copyOfRange(10, 15))
        assertArrayEquals("00014906".hexToByteArray(), soleFtmsWorkoutFrames(SoleSimulatorProfile.F65, sample, 20)[0])
        for (profile in SoleSimulatorProfile.entries) {
            val ftms = soleFtmsWorkoutFrames(profile, sample, 20)
            assertTrue(profile.name, ftms.isNotEmpty() && ftms.all { it.size <= 20 })
            assertArrayEquals(profile.name, "4906".hexToByteArray(), ftms.first().copyOfRange(2, 4))
        }
        val bike = soleFtmsWorkoutFrames(SoleSimulatorProfile.R92, sample, 64).single()
        assertArrayEquals("640b4906a6000700c800".hexToByteArray(), bike.copyOfRange(0, 10))
    }

    @Test
    fun `FTMS and legacy UART workout subscriptions are independent`() {
        val uart = SoleCapture.vendorNotify
        for (profile in SoleSimulatorProfile.entries) {
            val ftms = profile.ftmsWorkoutKey
            val expectedUuid = if (profile in listOf(SoleSimulatorProfile.LCB, SoleSimulatorProfile.R92)) "2ad2" else "2acd"
            assertEquals(soleUuid(expectedUuid), ftms.characteristic)
            val both = mapOf(ftms to 1, uart to 1)
            assertEquals(emptyList<SoleAttribute>(), profile.workoutNotificationKeys(emptyMap(), true))
            assertEquals(listOf(ftms), profile.workoutNotificationKeys(both, false))
            if (profile.sendsVendorWorkout) {
                assertEquals(listOf(ftms, uart), profile.workoutNotificationKeys(both, true))
                assertEquals(listOf(uart), profile.workoutNotificationKeys(mapOf(uart to 1), true))
            } else {
                assertEquals(listOf(ftms), profile.workoutNotificationKeys(both, true))
            }
        }
    }

    @Test
    fun `F65 UART responder follows the observed handshake and opens the stream at the final acknowledgement`() {
        val responder = SoleF65VendorResponder()
        assertEquals(
            listOf("5b08f0910000dc0a0f125d", "5b0203015d"),
            responder.accept("5b01f05d".hexToByteArray()).map { it.toHexString() },
        )
        // Client acknowledgements get no reply; state and 02 frames echo; other commands are acknowledged.
        assertTrue(responder.accept("5b0400104f4b5d".hexToByteArray()).isEmpty())
        assertEquals(listOf("5b0203015d"), responder.accept("5b0203015d".hexToByteArray()).map { it.toHexString() })
        assertEquals(listOf("5b0202025d"), responder.accept("5b0202025d".hexToByteArray()).map { it.toHexString() })
        for ((write, ack) in listOf(
            "5b06070123009baa5d" to "5b0400074f4b5d",
            "5b030810015d" to "5b0400084f4b5d",
            "5b0504000000005d" to "5b0400044f4b5d",
            "5b0222095d" to "5b0400224f4b5d",
        )) {
            assertEquals(listOf(ack), responder.accept(write.hexToByteArray()).map { it.toHexString() })
        }
        assertFalse(responder.started)
        assertTrue(responder.accept("5b0400404f4b5d".hexToByteArray()).isEmpty())
        assertTrue(responder.started)
        // Malformed frames are ignored.
        assertTrue(responder.accept("5b02f05d".hexToByteArray()).isEmpty())
        assertTrue(responder.accept("5b01f0".hexToByteArray()).isEmpty())
        responder.reset()
        assertFalse(responder.started)
    }

    @Test
    fun `F65 sends generated incline over UART while FTMS keeps its short speed packets`() {
        val profile = SoleSimulatorProfile.F65
        assertFalse(profile.usesProprietaryWorkout)
        assertTrue(profile.sendsVendorWorkout)
        // No replay lane: the F65 vendor session is synthetic.
        assertTrue(soleFixture(profile).forSimulation(profile).lanes.isEmpty())
        val sample = FtmsMockSample(
            elapsedSeconds = 9, heartRate = 141, caloriesTotal = 3,
            caloriesPerHour = 180, caloriesPerMinute = 3, speedKph = 16.09344,
            inclinationPercent = 6.4,
        )
        val frame = soleVendorWorkoutFrames(profile, sample, 20).single()
        assertEquals(18, frame.size)
        assertArrayEquals("5b0f06".hexToByteArray(), frame.copyOfRange(0, 3))
        // HR 141, 10.0 mph, 6% incline, then the captured F65 byte-12 marker.
        assertArrayEquals(byteArrayOf(141.toByte(), 100, 6, 1), frame.copyOfRange(9, 13))
        assertEquals(1.toByte(), frame[16])
        assertEquals(0x5d.toByte(), frame.last())
        assertEquals(0.toByte(), soleVendorWorkoutFrames(profile, sample.copy(inclinationPercent = -2.0), 20).single()[11])
        assertEquals(2, soleFtmsWorkoutFrames(profile, sample, 20).size)
        val both = mapOf(profile.ftmsWorkoutKey to 1, SoleCapture.vendorNotify to 1)
        assertEquals(listOf(profile.ftmsWorkoutKey), profile.workoutNotificationKeys(both, vendorStarted = false))
        assertEquals(listOf(profile.ftmsWorkoutKey, SoleCapture.vendorNotify), profile.workoutNotificationKeys(both, true))
    }

    @Test
    fun `all nine personas preserve the captured inventories and notification counts`() {
        val expected = mapOf(
            SoleSimulatorProfile.ST90 to listOf(12, 66, 19, 2607),
            SoleSimulatorProfile.F80_MODERN to listOf(12, 66, 19, 2471),
            SoleSimulatorProfile.F65 to listOf(10, 58, 17, 270),
            SoleSimulatorProfile.F63 to listOf(10, 59, 17, 701),
            SoleSimulatorProfile.F80_LEGACY to listOf(10, 58, 17, 538),
            SoleSimulatorProfile.F85_58 to listOf(10, 58, 17, 759),
            SoleSimulatorProfile.F85_59 to listOf(10, 59, 17, 1773),
            SoleSimulatorProfile.LCB to listOf(7, 54, 15, 169),
            SoleSimulatorProfile.R92 to listOf(5, 20, 7, 530),
        )
        assertEquals(expected.keys.toList(),
            simulatorPersonaOptions(true).filterIsInstance<SimulatorPersona.Sole>().map { it.profile })
        expected.forEach { (profile, counts) ->
            val fixture = soleFixture(profile)
            val characteristics = fixture.services.values.flatten()
            val outgoing = fixture.lanes.values.flatten().count {
                it.operation == SoleReplayOperation.NOTIFY || it.operation == SoleReplayOperation.INDICATE
            }
            assertEquals(profile.name, counts,
                listOf(fixture.services.size, characteristics.size, characteristics.count { it.hasCccd }, outgoing))
        }
    }

    @Test
    fun `F65 retains the short malformed feature and all alternating speed and heart-rate packets`() {
        val fixture = soleFixture(SoleSimulatorProfile.F65)
        val feature = fixture.services.getValue(soleUuid("1826")).single { it.key.characteristic == soleUuid("2acc") }
        assertArrayEquals(ByteArray(4), feature.readValue)
        val frames = fixture.lanes.getValue(SoleReplayLane.TREADMILL)
        assertTrue(frames.all { it.value.size == 4 })
        assertEquals(mapOf("01010000" to 135, "00016400" to 18, "00012c01" to 73,
            "00015802" to 24, "00018403" to 20), frames.groupingBy { it.value.toHexString() }.eachCount())
        assertTrue(frames.zipWithNext().all { (a, b) -> a.value[0] != b.value[0] })
    }

    @Test
    fun `legacy replay requires the captured write mode and restarts with a new client cursor`() {
        val events = soleFixture(SoleSimulatorProfile.F63).lanes.getValue(SoleReplayLane.VENDOR)
        val first = events.first()
        val cursor = SoleReplayCursor(events)
        assertNull(cursor.takeOutput())
        assertFalse(cursor.accept(first.key, responseNeeded = true, first.value))
        assertFalse(cursor.accept(first.key, responseNeeded = false, ByteArray(0)))
        assertTrue(cursor.accept(first.key, responseNeeded = false, first.value))
        val reply = requireNotNull(cursor.takeOutput())
        assertArrayEquals("5b08f090000178050f125d".hexToByteArray(), reply.value)
        assertNull(SoleReplayCursor(events).takeOutput())
    }

    @Test
    fun `ST90 and modern F80 replay different start responses`() {
        fun responses(profile: SoleSimulatorProfile): List<String> {
            val events = soleFixture(profile).lanes.getValue(SoleReplayLane.CONTROL)
            return events.map { it.value.toHexString() }.filter { it.startsWith("8007") }
        }
        assertEquals(listOf("800705", "800705", "800701"), responses(SoleSimulatorProfile.ST90))
        assertEquals(listOf("800701"), responses(SoleSimulatorProfile.F80_MODERN))
    }

    @Test
    fun `an early FTMS request preserves the preceding captured outputs`() {
        val cursor = SoleReplayCursor(soleFixture(SoleSimulatorProfile.ST90).lanes.getValue(SoleReplayLane.CONTROL))
        assertTrue(cursor.accept(SoleCapture.controlPoint, responseNeeded = true, byteArrayOf(0)))
        assertFalse(cursor.accept(SoleCapture.controlPoint, responseNeeded = true, byteArrayOf(7)))
        assertEquals("0001", cursor.takeOutput()?.value?.toHexString())
        assertEquals("01", cursor.takeOutput()?.value?.toHexString())
        assertEquals("0001", cursor.takeOutput()?.value?.toHexString())
        assertEquals("800001", cursor.takeOutput()?.value?.toHexString())
    }

    @Test
    fun `R92 advertises DIS and FTMS and sends a vendor frame before the first write`() {
        val fixture = soleFixture(SoleSimulatorProfile.R92)
        assertEquals(setOf(soleUuid("180a"), soleUuid("1826")), fixture.advertisedServices.toSet())
        assertTrue(fixture.services.getValue(soleUuid("1826")).none { it.key.characteristic == soleUuid("2acc") })
        val cursor = SoleReplayCursor(fixture.lanes.getValue(SoleReplayLane.VENDOR))
        assertArrayEquals("5b0213005d".hexToByteArray(), requireNotNull(cursor.takeOutput()).value)
        assertNull(cursor.takeOutput())
    }
}

internal fun soleFixture(profile: SoleSimulatorProfile): SoleCapture =
    sequenceOf(File("gatt-simulator/src/main/assets/sole"), File("src/main/assets/sole"))
        .first(File::isDirectory).resolve("${profile.name}.txt").reader().use { SoleCapture.load(it) }
