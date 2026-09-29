package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeiserCaptureFixtureIntegrityTest {
    private val resource = "fixtures/hardware/keiser_m_qz_debug_advertisements.json"

    @Test
    fun `capture corpus provenance and privacy boundary stay pinned`() {
        val text = loadText()
        val fixture = FixtureJson.obj(FixtureJson.parse(text))
        val sources = FixtureJson.array(fixture["sources"]).map(FixtureJson::obj)
        val summary = FixtureJson.obj(fixture["summary"])

        assertEquals("keiser-m-series", FixtureJson.string(fixture["family"]))
        assertEquals(258, FixtureJson.int(FixtureJson.obj(fixture["protocol_authority"])["company_id"]))
        assertEquals(
            setOf(
                "7975333de013e264ff1f6c4619475ded24398b33df67ca4e6ea3292ba8b6a427",
                "daf4e1d89ab597592b1a644a11c78a573aa0eb0bb0b5403cde99c9be9f231708",
                "2bfe90452b5300aadc5355fd48a8c416a99223b58833518448ffbec31446bfce",
            ),
            sources.mapTo(linkedSetOf()) { FixtureJson.string(it["source_sha256"]) },
        )
        assertEquals(5785, sources.sumOf { FixtureJson.int(it["normalized_packets"]) })
        assertEquals(4799, sources.sumOf { FixtureJson.int(it["unique_payloads"]) })
        assertEquals(5648, sources.sumOf { FixtureJson.int(it["live_packets"]) })
        assertEquals(137, sources.sumOf { FixtureJson.int(it["review_packets"]) })
        assertEquals(5785, FixtureJson.int(summary["normalized_packets"]))
        assertEquals(0, FixtureJson.int(summary["wrong_length_packets"]))
        assertTrue(FixtureJson.string(fixture["capture_kind"]).contains("not raw Bluetooth HCI"))
        assertTrue(FixtureJson.string(fixture["evidence_limit"]).contains("do not constitute physical-hardware verification"))
        assertFalse("fixture must not retain a Bluetooth address", Regex("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}").containsMatchIn(text))
    }

    @Test
    fun `all captured cases agree with the documented Keiser wire layout`() {
        val fixture = load()
        for (rawCase in FixtureJson.array(fixture["cases"])) {
            val case = FixtureJson.obj(rawCase)
            val decoded = decode(packet(case))
            assertExpected(FixtureJson.string(case["id"]), FixtureJson.obj(case["expect"]), decoded)
        }
    }

    @Test
    fun `review frames remain explicit rejection fixtures`() {
        val cases = FixtureJson.array(load()["cases"]).map(FixtureJson::obj)
        val reviewCases = cases.filter { !FixtureJson.obj(it["expect"]).boolean("is_realtime") }

        assertEquals(setOf(5, 70), reviewCases.mapTo(linkedSetOf()) { FixtureJson.int(it["observed_repeats"]) })
        assertTrue(reviewCases.all { decode(packet(it)).dataType == 0xff })
    }

    @Test
    fun `captured dropout sequences pin asymmetric substitution and recovery`() {
        for (rawSequence in FixtureJson.array(load()["dropout_sequences"])) {
            val sequence = FixtureJson.obj(rawSequence)
            val filter = KeiserDropoutFilter()
            for (rawFrame in FixtureJson.array(sequence["frames"])) {
                val frame = FixtureJson.obj(rawFrame)
                val decoded = decode(packet(frame))
                val filtered = filter.filter(decoded)
                val expected = FixtureJson.obj(frame["expect"])

                assertEquals(FixtureJson.double(expected["cadence_rpm"]), decoded.cadenceRpm, 1e-9)
                assertEquals(FixtureJson.int(expected["power_watts"]), decoded.powerWatts)
                assertEquals(FixtureJson.double(expected["filtered_cadence_rpm"]), filtered.cadenceRpm, 1e-9)
                assertEquals(FixtureJson.int(expected["filtered_power_watts"]), filtered.powerWatts)
            }
        }
    }

    @Test
    fun `captured coast passes both zero values instead of retaining motion`() {
        val cases = FixtureJson.array(load()["cases"]).map(FixtureJson::obj)
        val coast = cases.single { FixtureJson.string(it["id"]) == "coast-both-cadence-and-power-zero" }
        val seed = FixtureJson.obj(coast["filter_seed"])
        val expected = FixtureJson.obj(coast["expect"])

        // The corpus records the coast's preceding state as 78.0 rpm and 60 W, which is exactly
        // the live 6.54 case above. Seeding the filter by running that packet through it keeps
        // the previous state on the wire rather than hand-set beside it.
        val riding = cases.single {
            FixtureJson.string(it["id"]) == "imperial-live-fw-6.54-android-normalized"
        }
        val filter = KeiserDropoutFilter()
        val seeded = filter.filter(decode(packet(riding)))
        assertEquals(FixtureJson.double(seed["cadence_rpm"]), seeded.cadenceRpm, 1e-9)
        assertEquals(FixtureJson.int(seed["power_watts"]), seeded.powerWatts)

        val filtered = filter.filter(decode(packet(coast)))
        assertEquals(FixtureJson.double(expected["filtered_cadence_rpm"]), filtered.cadenceRpm, 1e-9)
        assertEquals(FixtureJson.int(expected["filtered_power_watts"]), filtered.powerWatts)
    }

    private fun assertExpected(
        label: String,
        expected: Map<String, Any?>,
        actual: KeiserMSeriesMeasurement.Reading,
    ) {
        assertEquals("$label firmware", FixtureJson.string(expected["firmware"]), actual.firmware)
        assertEquals("$label data type", FixtureJson.int(expected["data_type"]), actual.dataType)
        assertEquals("$label realtime", expected.boolean("is_realtime"), actual.isRealtime)
        assertEquals("$label system ID", FixtureJson.int(expected["system_id"]), actual.systemId)
        assertEquals("$label cadence", FixtureJson.double(expected["cadence_rpm"]), actual.cadenceRpm, 1e-9)
        assertEquals("$label heart rate", FixtureJson.double(expected["heart_rate_bpm"]), actual.heartRateBpm, 1e-9)
        assertEquals("$label power", FixtureJson.int(expected["power_watts"]), actual.powerWatts)
        assertEquals("$label energy", FixtureJson.int(expected["energy_kcal"]), actual.energyKcal)
        assertEquals("$label elapsed", FixtureJson.int(expected["elapsed_seconds"]), actual.elapsedSeconds)
        assertEquals("$label distance unit", FixtureJson.string(expected["distance_unit"]), actual.distanceUnit.wireName)
        assertEquals("$label raw distance", FixtureJson.int(expected["distance_raw_tenths"]), actual.distanceRawTenths)
        assertEquals("$label distance", FixtureJson.double(expected["distance_metres"]), actual.distanceMetres, 1e-9)
        assertEquals("$label resistance", FixtureJson.int(expected["resistance"]), requireNotNull(actual.resistance))
    }

    /**
     * Phase 11.3: this fixture consumer now exercises the production decoder and the production
     * dropout filter. Every captured expectation is unchanged; the local reference implementations
     * that used to compute them are gone, so the corpus can no longer agree with a decoder
     * FitBridge does not ship.
     */
    private fun decode(packet: ByteArray): KeiserMSeriesMeasurement.Reading =
        requireNotNull(KeiserMSeriesMeasurement.parse(packet)) {
            "production decoder rejected a captured packet"
        }

    private fun packet(case: Map<String, Any?>): ByteArray = FixtureJson.array(case["packet"])
        .map { FixtureJson.int(it).toByte() }
        .toByteArray()

    private fun loadText(): String = javaClass.classLoader?.getResourceAsStream(resource)
        ?.bufferedReader()?.use { it.readText() }
        ?: error("missing $resource")

    private fun load(): Map<String, Any?> = FixtureJson.obj(FixtureJson.parse(loadText()))

    private fun Map<String, Any?>.boolean(key: String): Boolean = this[key] as? Boolean
        ?: error("expected boolean $key")
}
