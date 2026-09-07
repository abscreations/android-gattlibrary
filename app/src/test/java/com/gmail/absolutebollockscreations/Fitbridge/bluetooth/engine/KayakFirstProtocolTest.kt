package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KayakFirstProtocolTest {
    private val resource = "fixtures/simulated/kayak_first_adversarial_v1.json"

    @Test
    fun `display vocabulary and defaults exactly match pinned TMIW`() {
        assertEquals(
            listOf(0, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 17, 18),
            KayakFirstDisplayMetric.entries.map(KayakFirstDisplayMetric::wireCode),
        )
        assertEquals(
            listOf(
                "Elapsed Time",
                "Total Distance",
                "Average Speed",
                "Instantaneous Speed",
                "Average Stroke Rate",
                "Inst. Stroke Rate",
                "Average 200m Pace (sec)",
                "Inst. 200m Pace (sec)",
                "Average 500m Pace (sec)",
                "Inst. 500m Pace (sec)",
                "Average 1000m Pace (sec)",
                "Inst. 1000m Pace (sec)",
                "Average Force",
                "Instantaneous Force",
                "Total Expended Energy",
                "Instantaneous Power",
                "Average Power",
            ),
            KayakFirstDisplayMetric.entries.map(KayakFirstDisplayMetric::displayLabel),
        )
        assertEquals(listOf(0, 2, 5, 11, 15), KayakFirstDisplayConfiguration.DEFAULT.codes())
        assertNull(KayakFirstDisplayMetric.fromWireCode(1))
        assertNull(KayakFirstDisplayMetric.fromWireCode(16))
        assertNull(KayakFirstDisplayConfiguration.fromWireCodes(listOf(0, 2, 5, 11)))
        assertNull(KayakFirstDisplayConfiguration.fromWireCodes(listOf(0, 2, 5, 11, 16)))
        assertEquals(
            listOf(0, 0, 0, 0, 0),
            requireNotNull(KayakFirstDisplayConfiguration.fromWireCodes(listOf(0, 0, 0, 0, 0))).codes(),
        )
    }

    @Test
    fun `normative commands reproduce the Phase 8 oracle byte for byte`() {
        val cases = FixtureJson.array(load()["outbound_command_cases"])
            .map(FixtureJson::obj)
            .filter { FixtureJson.string(it["authority"]) == "central-normative" }

        cases.forEach { case ->
            val command = commandFor(FixtureJson.string(case["id"]))
            val expected = FixtureJson.string(case["command_ascii"])
            assertEquals(expected, KayakFirstCommandCodec.encode(command).toString(Charsets.UTF_8))
            assertEquals(
                strings(case["expected_chunks_ascii"]),
                KayakFirstCommandCodec.chunks(command).map { it.toString(Charsets.UTF_8) },
            )
            assertTrue(KayakFirstCommandCodec.chunks(command).all { it.size <= 20 })
        }

        assertEquals(80, KayakFirstCommandCodec.NEUTRAL_WEIGHT_KG)
        assertEquals(1, KayakFirstSportMode.CANOEING.wireCode)
        assertEquals(2, KayakFirstSportMode.KAYAKING.wireCode)
    }

    @Test
    fun `sport selection is encoded in the complete legacy handshake`() {
        val canoe = KayakFirstCommand.LegacyHandshake(
            1_788_737_313L,
            -240,
            KayakFirstSportMode.CANOEING,
        )
        val kayak = canoe.copy(sport = KayakFirstSportMode.KAYAKING)

        assertEquals("2;1788737313;-240;80;1\r\n", KayakFirstCommandCodec.encode(canoe).toString(Charsets.UTF_8))
        assertEquals("2;1788737313;-240;80;2\r\n", KayakFirstCommandCodec.encode(kayak).toString(Charsets.UTF_8))
    }

    @Test
    fun `bull handshake encodes command 21 with athlete and boat weight`() {
        val bull = KayakFirstCommand.BullHandshake(
            unixEpochSeconds = 1_788_737_313L,
            timezoneOffsetMinutes = -240,
            athleteWeightKg = 93,
            boatWeightKg = 12,
        )
        assertEquals(
            "21;1788737313;-240;93;12\r\n",
            KayakFirstCommandCodec.encode(bull).toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `configured athlete weight is transmitted and an absent one falls back to neutral`() {
        assertEquals(93, KayakFirstCommandCodec.athleteWeightKgOrNeutral(93))
        assertEquals(
            KayakFirstCommandCodec.NEUTRAL_WEIGHT_KG,
            KayakFirstCommandCodec.athleteWeightKgOrNeutral(null),
        )
        // An out-of-range configuration is neutralized rather than transmitted.
        assertEquals(
            KayakFirstCommandCodec.NEUTRAL_WEIGHT_KG,
            KayakFirstCommandCodec.athleteWeightKgOrNeutral(2),
        )
        val legacy = KayakFirstCommand.LegacyHandshake(
            unixEpochSeconds = 1_788_737_313L,
            timezoneOffsetMinutes = -240,
            sport = KayakFirstSportMode.KAYAKING,
            athleteWeightKg = 93,
        )
        assertEquals(
            "2;1788737313;-240;93;2\r\n",
            KayakFirstCommandCodec.encode(legacy).toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `both handshake tokens classify as handshake responses`() {
        listOf("2;1", "21;1").forEach { line ->
            val decoded = KayakFirstResponseDecoder.decode(line)
            assertTrue(decoded is KayakFirstLineDecodeResult.Decoded)
            assertEquals(
                KayakFirstResponseKind.HANDSHAKE,
                (decoded as KayakFirstLineDecodeResult.Decoded).response.kind,
            )
        }
    }

    @Test
    fun `payload capacity changes chunking but never command bytes`() {
        val command = KayakFirstCommand.LegacyHandshake(1_700_000_000L, 120)
        val encoded = KayakFirstCommandCodec.encode(command)

        for (capacity in listOf(1, 2, 3, 7, 20, 64)) {
            val chunks = KayakFirstCommandCodec.chunks(command, capacity)
            assertTrue(chunks.all { it.size in 1..capacity })
            assertTrue(chunks.fold(byteArrayOf()) { accumulated, chunk -> accumulated + chunk }
                .contentEquals(encoded))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero payload capacity is rejected`() {
        KayakFirstCommandCodec.chunks(KayakFirstCommand.Poll, 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative handshake epoch is rejected`() {
        KayakFirstCommand.LegacyHandshake(-1L, 0)
    }

    @Test
    fun `response variants classify without granting invented success`() {
        val cases = FixtureJson.array(load()["response_cases"]).map(FixtureJson::obj)
        cases.forEach { case ->
            val line = FixtureJson.string(case["response_ascii"]).removeSuffix("\r\n")
            val expected = FixtureJson.string(case["expect"])
            when (val result = KayakFirstResponseDecoder.decode(line)) {
                is KayakFirstLineDecodeResult.Rejected -> {
                    assertEquals("reject-unknown-command", expected)
                    assertEquals(KayakFirstLineRejection.UNKNOWN_COMMAND, result.reason)
                }
                is KayakFirstLineDecodeResult.Decoded -> {
                    val response = result.response
                    when (expected) {
                        "reset-success" -> assertEquals(KayakFirstResponseKind.RESET, response.kind)
                        "handshake-success" -> assertEquals(KayakFirstResponseKind.HANDSHAKE, response.kind)
                        "display-success" -> {
                            assertEquals(KayakFirstResponseKind.DISPLAY_CONFIGURATION, response.kind)
                            assertTrue(response.isDisplaySuccess)
                        }
                        "display-failure" -> {
                            assertEquals(KayakFirstResponseKind.DISPLAY_CONFIGURATION, response.kind)
                            assertFalse(response.isDisplaySuccess)
                        }
                        "poll-response-not-measurement" -> {
                            assertEquals(KayakFirstResponseKind.POLL, response.kind)
                            assertTrue(response.fields.isEmpty())
                        }
                        "session-start-response", "session-stop-response",
                        "session-stop-response-observed-dialect",
                        -> assertEquals(KayakFirstResponseKind.SESSION, response.kind)
                        else -> error("Unhandled fixture result $expected")
                    }
                }
            }
        }
        assertEquals(
            KayakFirstLineRejection.EMPTY_LINE,
            (KayakFirstResponseDecoder.decode("") as KayakFirstLineDecodeResult.Rejected).reason,
        )
        // Command 21 entered the profile with the Bull convergence; 3 and 8 remain outside it.
        for (line in listOf("3;1", "8;1")) {
            val response = (KayakFirstResponseDecoder.decode(line) as KayakFirstLineDecodeResult.Decoded)
                .response
            assertEquals(KayakFirstResponseKind.KNOWN_OUT_OF_PROFILE, response.kind)
        }
        assertEquals(
            KayakFirstResponseKind.HANDSHAKE,
            (KayakFirstResponseDecoder.decode("21;1") as KayakFirstLineDecodeResult.Decoded)
                .response.kind,
        )
    }

    private fun commandFor(id: String): KayakFirstCommand = when (id) {
        "tmiw-complete-legacy-handshake" -> KayakFirstCommand.LegacyHandshake(1_700_000_000L, 120)
        "tmiw-reset" -> KayakFirstCommand.Reset
        "tmiw-display-default" ->
            KayakFirstCommand.DisplayConfiguration(KayakFirstDisplayConfiguration.DEFAULT)
        "tmiw-poll" -> KayakFirstCommand.Poll
        "tmiw-start-short" -> KayakFirstCommand.Start
        "tmiw-stop-short" -> KayakFirstCommand.Stop
        else -> error("No production command for $id")
    }

    private fun KayakFirstDisplayConfiguration.codes(): List<Int> =
        slots.map(KayakFirstDisplayMetric::wireCode)

    private fun load(): Map<String, Any?> = javaClass.classLoader
        ?.getResourceAsStream(resource)
        ?.bufferedReader()
        ?.use { FixtureJson.obj(FixtureJson.parse(it.readText())) }
        ?: error("missing $resource")

    private fun strings(value: Any?): List<String> = FixtureJson.array(value).map(FixtureJson::string)
}
