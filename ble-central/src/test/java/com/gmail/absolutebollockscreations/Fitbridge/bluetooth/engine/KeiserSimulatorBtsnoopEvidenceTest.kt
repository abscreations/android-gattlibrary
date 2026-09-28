package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeiserSimulatorBtsnoopEvidenceTest {
    private val resource = "fixtures/hardware/keiser_m_simulator_btsnoop_20260902.json"

    @Test
    fun `simulator btsnoop provenance and structural redaction stay pinned`() {
        val text = loadText()
        val fixture = FixtureJson.obj(FixtureJson.parse(text))
        val source = FixtureJson.obj(fixture["source"])

        assertEquals("keiser-m-series", FixtureJson.string(fixture["family"]))
        assertEquals(
            "2f146a7c4d59cbc511b004a983a7a43fd6bd7eaf6daa3207278b56de14e064c2",
            FixtureJson.string(source["source_sha256"]),
        )
        assertEquals(2_142, FixtureJson.int(source["retained_records"]))
        assertFalse(source["raw_file_committed"] as Boolean)
        assertFalse("fixture must not retain a Bluetooth address", addressRegex.containsMatchIn(text))
        assertFalse("fixture must not retain an absolute timestamp", isoTimestampRegex.containsMatchIn(text))
    }

    @Test
    fun `retained packets agree with the production decoder`() {
        val cases = FixtureJson.array(load()["cases"]).map(FixtureJson::obj)
        for (case in cases) {
            val caseId = FixtureJson.string(case["id"])
            val expected = FixtureJson.obj(case["expect"])
            val packet = FixtureJson.string(case["packet_hex"]).hexBytes()
            val decoded = KeiserMSeriesMeasurement.parse(packet)
            if (expected["accepted"] == false) {
                assertNull(caseId, decoded)
                continue
            }

            requireNotNull(decoded) { "production decoder rejected retained case $caseId" }
            expected["firmware"]?.let { assertEquals(FixtureJson.string(it), decoded.firmware) }
            expected["system_id"]?.let { assertEquals(FixtureJson.int(it), decoded.systemId) }
            expected["is_realtime"]?.let { assertEquals(it as Boolean, decoded.isRealtime) }
            expected["data_type"]?.let { assertEquals(FixtureJson.int(it), decoded.dataType) }
            expected["distance_raw_tenths"]?.let {
                assertEquals(FixtureJson.int(it), decoded.distanceRawTenths)
            }
            expected["distance_metres"]?.let {
                assertEquals(FixtureJson.double(it), decoded.distanceMetres, 1e-6)
            }
        }
    }

    @Test
    fun `capture retains both firmware cadence branches and authored malformed input`() {
        val identities = FixtureJson.array(load()["identities"]).map(FixtureJson::obj)
        val byPersona = identities.associateBy { FixtureJson.string(it["persona"]) }

        assertEquals(0.391399, FixtureJson.double(requireNotNull(byPersona["M3m"])["median_valid_gap_seconds"]), 1e-6)
        assertEquals(2.041541, FixtureJson.double(requireNotNull(byPersona["M3o"])["median_valid_gap_seconds"]), 1e-6)
        assertTrue(FixtureJson.int(requireNotNull(byPersona["M3m"])["malformed_records"]) > 0)
        assertTrue(FixtureJson.int(requireNotNull(byPersona["M3o"])["malformed_records"]) > 0)
    }

    private fun loadText(): String = javaClass.classLoader?.getResourceAsStream(resource)
        ?.bufferedReader()?.use { it.readText() }
        ?: error("missing $resource")

    private fun load(): Map<String, Any?> = FixtureJson.obj(FixtureJson.parse(loadText()))

    private fun String.hexBytes(): ByteArray {
        require(length % 2 == 0)
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private companion object {
        val addressRegex = Regex("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}")
        val isoTimestampRegex = Regex("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}")
    }
}
