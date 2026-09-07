package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the sanitized, user-submitted physical Kayak First application-log corpus. */
class KayakFirstHardwareCorpusTest {
    private val resource = "fixtures/hardware/kayak_first_qz_202604.json"

    @Test
    fun `provenance and privacy boundary stay pinned`() {
        val text = loadText()
        val fixture = FixtureJson.obj(FixtureJson.parse(text))
        val sources = FixtureJson.array(fixture["sources"]).map(FixtureJson::obj)

        assertEquals("kayak-first-paddle", FixtureJson.string(fixture["family"]))
        assertEquals(7, sources.size)
        assertEquals(
            setOf(
                "b3bb372d4defef0505149f3e031b45c62f91ed6445ce49e741a84be04c094f6d",
                "6bd2f8574b456c61ef9a5925cd09c1e4cd85c6ebc2b2829f097d743006570ce2",
                "470c57af4c7ea299c9c1a78da86196b0da5e62e4e131611be19ea99e3eef49a5",
                "9e6b89da8d9367e63b1d95cd15044af585962fab8d9b037d2f7abb18e5f762ec",
                "25493b13166300f15248549b64a2173a569672933fcf2d64e09252caa3b6dbc9",
                "a092e0e89f3ce12e7133117199c3a6d17b7872bac1d1bee660da727b7743bf90",
                "0a482fe2b29de87e923a409b4cc4da00839f11ee90e9e0c43e7825bdd2fa12fe",
            ),
            sources.mapTo(linkedSetOf()) { FixtureJson.string(it["source_sha256"]) },
        )
        assertEquals(15, FixtureJson.int(FixtureJson.obj(fixture["advertisement"])["observations"]))
        assertTrue(FixtureJson.string(fixture["capture_kind"]).contains("not raw Bluetooth HCI"))
        assertTrue(FixtureJson.string(fixture["evidence_limit"]).contains("does not prove a byte-exact advertisement"))
        assertFalse("fixture must not retain a Bluetooth address", MAC_ADDRESS.containsMatchIn(text))
        assertFalse("fixture must not retain known profile or store-token keys", PRIVATE_KEY.containsMatchIn(text))
        assertEquals(
            "<console-id>",
            FixtureJson.string(FixtureJson.obj(fixture["advertisement"])["local_name_sanitized"]),
        )
    }

    @Test
    fun `semantic advertisement preserves the address-bearing manufacturer relationship`() {
        val advertisement = FixtureJson.obj(load()["advertisement"])
        val payload = ints(advertisement["manufacturer_payload_sanitized"])
        val address = ints(advertisement["pseudonymous_address_bytes"])

        assertEquals(42841, FixtureJson.int(advertisement["manufacturer_data_id"]))
        assertEquals(listOf(136, 160), payload.take(2))
        assertEquals(address.reversed(), payload.drop(2))
        assertEquals(
            listOf("0000ffe0-0000-1000-8000-00805f9b34fb"),
            strings(advertisement["advertised_services"]),
        )
    }

    @Test
    fun `service and exercised FFE1 surface stay exact without claiming a full GATT dump`() {
        val gatt = FixtureJson.obj(load()["gatt"])
        assertEquals(
            listOf(
                "00001800-0000-1000-8000-00805f9b34fb",
                "00001801-0000-1000-8000-00805f9b34fb",
                "0000180a-0000-1000-8000-00805f9b34fb",
                "0000ffe0-0000-1000-8000-00805f9b34fb",
            ),
            strings(gatt["discovered_services"]),
        )
        val service = FixtureJson.obj(gatt["protocol_service"])
        val characteristic = FixtureJson.obj(service["characteristic"])
        val cccd = FixtureJson.obj(service["cccd"])
        assertEquals(listOf(35, 39), ints(service["observed_handle_range"]))
        assertEquals("0000ffe1-0000-1000-8000-00805f9b34fb", FixtureJson.string(characteristic["uuid"]))
        assertEquals(36, FixtureJson.int(characteristic["value_handle"]))
        assertEquals(listOf("write-without-response", "notify"), strings(characteristic["exercised_operations"]))
        assertEquals(38, FixtureJson.int(cccd["handle"]))
        assertEquals(listOf(1, 0), ints(cccd["written_value"]))
        assertTrue(strings(gatt["not_observed"]).contains("declared FFE1 property bitmask"))
    }

    @Test
    fun `captured notification boundaries reassemble to the recorded frames`() {
        for (rawCase in FixtureJson.array(load()["fragmentation_cases"])) {
            val case = FixtureJson.obj(rawCase)
            val joined = strings(case["chunks_ascii"]).joinToString(separator = "").removeSuffix("\r\n")
            assertEquals(FixtureJson.string(case["id"]), FixtureJson.string(case["reassembled_ascii"]), joined)
            assertMeasurement(case["reassembled_ascii"], FixtureJson.obj(case["expect"]))
        }
    }

    @Test
    fun `successful setup transcript pins silence as silence and display acknowledgement as success`() {
        val transcripts = FixtureJson.array(load()["setup_transcripts"]).map(FixtureJson::obj)
        val transcript = transcripts.single {
            FixtureJson.string(it["id"]) == "idle-long-successful-setup"
        }
        val events = FixtureJson.array(transcript["events"]).map(FixtureJson::obj)

        assertEquals(11, events.size)
        assertEquals(
            listOf(0, 114, 6172, 17308, 17395, 27441, 38529, 49656, 64797, 64857, 74909),
            events.map { FixtureJson.int(it["t_ms"]) },
        )
        assertEquals(
            3,
            events.count {
                FixtureJson.string(it["classification"]).startsWith("legacy-handshake-attempt")
            },
        )
        assertTrue(
            events.filter {
                FixtureJson.string(it["classification"]).startsWith("legacy-handshake-attempt")
            }.all { FixtureJson.string(it["classification"]).contains("no-response") },
        )
        val displayResponse = events.single {
            FixtureJson.string(it["classification"]) == "display-configuration-response"
        }
        assertEquals("5;1\r\n", FixtureJson.string(displayResponse["ascii"]))
        assertEquals(60, FixtureJson.int(displayResponse["t_ms"]) - 64797)
        assertTrue(
            FixtureJson.string(transcript["classification"])
                .contains("handshake remained unacknowledged"),
        )
    }

    @Test
    fun `selected physical workout vectors retain the Phase 8 field map`() {
        val fixture = load()
        val cases = FixtureJson.array(fixture["measurement_cases"]).map(FixtureJson::obj)
        assertEquals(21, cases.size)
        assertEquals(cases.size, cases.map { FixtureJson.string(it["id"]) }.toSet().size)

        for (case in cases) {
            assertTrue(FixtureJson.int(case["t_ms"]) >= 0)
            assertMeasurement(case["frame_ascii"], FixtureJson.obj(case["expect"]))
        }

        val expected = cases.map { FixtureJson.obj(it["expect"]) }
        assertEquals(152, expected.maxOf { FixtureJson.int(it["distance_m"]) })
        assertEquals(3.36, expected.maxOf { FixtureJson.double(it["speed_mps"]) }, 1e-9)
        assertEquals(99, expected.maxOf { FixtureJson.int(it["cadence_spm"]) })
        assertEquals(103, expected.maxOf { FixtureJson.int(it["force_newtons"]) })
        assertEquals(69, expected.maxOf { FixtureJson.int(it["elapsed_seconds"]) })
        assertEquals(setOf(1, 2, 3), cases.mapTo(linkedSetOf()) { FixtureJson.int(it["session"]) })
    }

    @Test
    fun `capture totals and short response rejection seeds stay explicit`() {
        val fixture = load()
        val summary = FixtureJson.obj(fixture["measurement_summary"])
        assertEquals(478, FixtureJson.int(summary["valid_24_field_frames"]))
        assertEquals(281, FixtureJson.int(summary["idle_frames"]))
        assertEquals(197, FixtureJson.int(summary["workout_frames"]))

        val shortCases = FixtureJson.array(fixture["short_measurement_cases"]).map(FixtureJson::obj)
        assertEquals(11, shortCases.sumOf { FixtureJson.int(it["observed_count"]) })
        assertTrue(shortCases.all { FixtureJson.string(it["frame_ascii"]).split(';').size < 24 })
        assertTrue(shortCases.all { FixtureJson.string(it["expect"]) == "reject-too-few-fields" })

        val transactions = FixtureJson.array(fixture["transaction_cases"]).map(FixtureJson::obj)
        val poll = transactions.single { FixtureJson.string(it["id"]) == "poll-write" }
        assertEquals(494, FixtureJson.int(poll["observed_count"]))
        val handshake = transactions.single { FixtureJson.string(it["id"]) == "legacy-handshake-write-normalized" }
        assertEquals("2;1700000000;120;70;", FixtureJson.string(handshake["ascii"]))
        assertEquals(0, FixtureJson.int(handshake["observed_responses"]))
    }

    private fun assertMeasurement(frameValue: Any?, expected: Map<String, Any?>) {
        val fields = FixtureJson.string(frameValue).split(';')
        assertEquals(FixtureJson.int(expected["field_count"] ?: 24), fields.size)
        assertEquals(FixtureJson.int(expected["distance_m"]), fields[9].toInt())
        assertEquals(FixtureJson.double(expected["speed_mps"]), fields[11].toDouble(), 1e-9)
        assertEquals(FixtureJson.double(expected["speed_kmh"]), fields[11].toDouble() * 3.6, 1e-9)
        assertEquals(FixtureJson.int(expected["cadence_spm"]), fields[13].toInt())
        assertEquals(FixtureJson.int(expected["force_newtons"]), fields[21].toInt())
        assertEquals(FixtureJson.int(expected["elapsed_seconds"]), fields[22].toInt())
    }

    private fun loadText(): String = javaClass.classLoader?.getResourceAsStream(resource)
        ?.bufferedReader()
        ?.use { it.readText() }
        ?: error("missing $resource")

    private fun load(): Map<String, Any?> = FixtureJson.obj(FixtureJson.parse(loadText()))

    private fun strings(value: Any?): List<String> = FixtureJson.array(value).map(FixtureJson::string)

    private fun ints(value: Any?): List<Int> = FixtureJson.array(value).map(FixtureJson::int)

    private companion object {
        val MAC_ADDRESS = Regex("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}")
        val PRIVATE_KEY = Regex("(?i)(fitshow_user_id|skuDetailsToken|offerIdToken|productDetailsToken)")
    }
}
