package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeiserProtocolFixtureIntegrityTest {
    private val resource = "fixtures/gymnasticon/keiser_m.json"

    @Test
    fun `vendor and permissive-source provenance are pinned`() {
        val fixture = load()
        val sources = FixtureJson.array(fixture["sources"]).map(FixtureJson::obj)
        val gymnasticon = sources.single { FixtureJson.string(it["id"]) == "gymnasticon-v1.4.1" }
        val paths = FixtureJson.array(gymnasticon["paths"]).map(FixtureJson::string).toSet()

        assertEquals("keiser-m-series", FixtureJson.string(fixture["family"]))
        assertEquals("e142f539c518d6d4ced3abdd587a825b918a9367", FixtureJson.string(gymnasticon["commit"]))
        assertEquals("MIT", FixtureJson.string(gymnasticon["license"]))
        assertEquals(
            setOf(
                "src/bikes/keiser.js",
                "src/test/bikes/keiser.js",
                "src/util/dropout-filter.js",
                "LICENSE.txt",
                "package.json",
            ),
            paths,
        )
        assertTrue(sources.any { FixtureJson.string(it["kind"]) == "vendor-documentation" })
        assertTrue(sources.any { FixtureJson.string(it["kind"]) == "authored-adversarial" })
    }

    @Test
    fun `official vector preserves every documented field and fractional cadence`() {
        val case = FixtureJson.obj(FixtureJson.array(load()["official_cases"]).single())
        val expected = FixtureJson.obj(case["expect"])
        val decoded = decode(packet(case))

        assertEquals("6.30", decoded.firmware)
        assertEquals(1_000, decoded.statsTimeoutMs)
        assertEquals(0, decoded.dataType)
        assertTrue(decoded.isRealtime)
        assertEquals(56, decoded.systemId)
        assertEquals(82.4, decoded.cadenceRpm, 1e-9)
        assertEquals(135.0, decoded.heartRateBpm, 1e-9)
        assertEquals(115, decoded.powerWatts)
        assertEquals(13, decoded.energyKcal)
        assertEquals(279, decoded.elapsedSeconds)
        assertEquals("miles", decoded.distanceUnit)
        assertEquals(1, decoded.distanceRawTenths)
        assertEquals(160.934, decoded.distanceMetres, 1e-9)
        assertEquals(10, decoded.resistance)
        assertEquals(FixtureJson.double(expected["cadence_rpm"]), decoded.cadenceRpm, 1e-9)
        assertEquals(
            82.0,
            FixtureJson.double(FixtureJson.obj(case["reference_actual"])["gymnasticon_cadence_rpm"]),
            1e-9,
        )
        assertTrue(FixtureJson.string(case["divergence_reason"]).startsWith("V24:"))
    }

    @Test
    fun `version fixtures pin encoded hexadecimal threshold and timeout`() {
        val cases = FixtureJson.array(load()["version_cases"]).map(FixtureJson::obj)
        assertEquals(setOf("5.12", "6.22", "6.30", "6.40"), cases.mapTo(linkedSetOf()) {
            FixtureJson.string(FixtureJson.obj(it["expect"])["firmware"])
        })
        for (case in cases) {
            val bytes = packet(case)
            val expected = FixtureJson.obj(case["expect"])
            assertEquals(FixtureJson.string(expected["firmware"]), firmware(bytes))
            assertEquals(FixtureJson.int(expected["stats_timeout_ms"]), statsTimeoutMs(bytes))
            assertTrue(FixtureJson.string(case["scope"]).contains("version-only"))
        }
    }

    @Test
    fun `realtime boundaries distinguish live data from review replay`() {
        val cases = FixtureJson.array(load()["realtime_cases"]).map(FixtureJson::obj)
        val accepted = linkedSetOf<Int>()
        val rejected = linkedSetOf<Int>()
        for (case in cases) {
            val expected = FixtureJson.obj(case["expect"])
            val dataType = packet(case)[4].u8()
            assertEquals(FixtureJson.int(expected["data_type"]), dataType)
            val realtime = isRealtime(dataType)
            assertEquals(expected.boolean("is_realtime"), realtime)
            (if (realtime) accepted else rejected).add(dataType)
        }
        assertEquals(setOf(0x00, 0x81, 0xfe), accepted)
        assertEquals(setOf(0x01, 0x80, 0xff), rejected)
    }

    @Test
    fun `distance boundary fixtures pin bit fifteen as unit not sign`() {
        for (case in FixtureJson.array(load()["distance_cases"]).map(FixtureJson::obj)) {
            val expected = FixtureJson.obj(case["expect"])
            val decoded = decode(packet(case))
            assertEquals(FixtureJson.int(expected["encoded_word"]), packet(case).u16(16))
            assertEquals(FixtureJson.string(expected["distance_unit"]), decoded.distanceUnit)
            assertEquals(FixtureJson.int(expected["distance_raw_tenths"]), decoded.distanceRawTenths)
            assertEquals(FixtureJson.double(expected["distance_metres"]), decoded.distanceMetres, 1e-9)
        }
    }

    @Test
    fun `packet shapes make resistance and strict trailing policy explicit`() {
        val cases = FixtureJson.array(load()["packet_shape_cases"]).map(FixtureJson::obj)
        for (case in cases) {
            val expected = FixtureJson.obj(case["expect"])
            assertEquals(FixtureJson.string(case["id"]), expected.boolean("structurally_valid"), validShape(packet(case)))
        }
        assertEquals(18, packet(cases.single { FixtureJson.string(it["id"]) == "old-5.12-minimal-without-resistance" }).size)
        assertEquals(19, packet(cases.single { FixtureJson.string(it["id"]) == "current-6.30-exact-length" }).size)
        assertTrue(
            FixtureJson.string(FixtureJson.obj(load()["wire_contract"])["trailing_byte_policy"])
                .startsWith("reject"),
        )
    }

    @Test
    fun `bad magic truncation matrix and high-bit fields are adversarial not observations`() {
        val fixture = load()
        val official = packet(FixtureJson.obj(FixtureJson.array(fixture["official_cases"]).single()))
        val matrix = FixtureJson.obj(fixture["truncation_matrix"])
        val lengths = FixtureJson.array(matrix["truncate_to_lengths"]).map(FixtureJson::int)
        assertEquals((0..18).toList(), lengths)
        assertTrue(lengths.all { !validShape(official.copyOf(it)) })
        assertFalse(matrix.boolean("expect_structurally_valid"))

        val adversarial = FixtureJson.array(fixture["adversarial_cases"]).map(FixtureJson::obj)
        val bad = adversarial.filter { FixtureJson.string(it["id"]).startsWith("bad-") || FixtureJson.string(it["id"]).startsWith("wrong-") }
        assertTrue(bad.all { !validShape(packet(it)) && FixtureJson.string(it["origin"]) == "fitbridge-authored" })

        val high = adversarial.single { FixtureJson.string(it["id"]) == "high-bit-every-uint16-field" }
        val decoded = decode(packet(high))
        val expected = FixtureJson.obj(high["expect"])
        assertEquals(3276.9, decoded.cadenceRpm, 1e-9)
        assertEquals(6524.4, decoded.heartRateBpm, 1e-9)
        assertEquals(37428, decoded.powerWatts)
        assertEquals(43981, decoded.energyKcal)
        assertEquals(100.0, decoded.distanceMetres, 1e-9)
        assertEquals(254, decoded.resistance)
        assertTrue(FixtureJson.string(expected["runtime_plausibility"]).contains("rejected"))
    }

    @Test
    fun `all documented divergences retain expectation reference behavior and reason`() {
        val divergences = FixtureJson.array(load()["documented_divergences"]).map(FixtureJson::obj)
        assertEquals(setOf("V24", "keiser-scale-correction", "keiser-realtime-correction"),
            divergences.mapTo(linkedSetOf()) { FixtureJson.string(it["id"]) })
        for (divergence in divergences) {
            assertTrue(divergence.containsKey("expect"))
            assertTrue(divergence.containsKey("reference_actual"))
            assertTrue(FixtureJson.string(divergence["divergence_reason"]).isNotBlank())
        }
    }

    private fun decode(packet: ByteArray): Decoded {
        require(validShape(packet))
        val distanceWord = packet.u16(16)
        val metric = distanceWord and 0x8000 != 0
        val distanceRaw = distanceWord and 0x7fff
        return Decoded(
            firmware = firmware(packet),
            statsTimeoutMs = statsTimeoutMs(packet),
            dataType = packet[4].u8(),
            isRealtime = isRealtime(packet[4].u8()),
            systemId = packet[5].u8(),
            cadenceRpm = packet.u16(6) / 10.0,
            heartRateBpm = packet.u16(8) / 10.0,
            powerWatts = packet.u16(10),
            energyKcal = packet.u16(12),
            elapsedSeconds = packet[14].u8() * 60 + packet[15].u8(),
            distanceUnit = if (metric) "kilometres" else "miles",
            distanceRawTenths = distanceRaw,
            distanceMetres = distanceRaw * if (metric) 100.0 else 160.934,
            resistance = packet.getOrNull(18)?.u8(),
        )
    }

    private fun validShape(packet: ByteArray): Boolean {
        if (packet.size < 4 || packet[0].u8() != 0x02 || packet[1].u8() != 0x01) return false
        val expectedLength = if (packet[3].u8() >= 0x21) 19 else 18
        return packet.size == expectedLength
    }

    private fun firmware(packet: ByteArray): String =
        "${packet[2].u8().toString(16)}.${packet[3].u8().toString(16).padStart(2, '0')}"

    private fun statsTimeoutMs(packet: ByteArray): Int =
        if (packet[2].u8() == 6 && packet[3].u8() >= 0x30) 1_000 else 7_000

    private fun isRealtime(dataType: Int): Boolean = dataType == 0 || dataType in 0x81..0xfe

    private fun packet(case: Map<String, Any?>): ByteArray = FixtureJson.array(case["packet"])
        .map { FixtureJson.int(it).toByte() }
        .toByteArray()

    private fun load(): Map<String, Any?> {
        val stream = javaClass.classLoader?.getResourceAsStream(resource) ?: error("missing $resource")
        return FixtureJson.obj(FixtureJson.parse(stream.bufferedReader().use { it.readText() }))
    }

    private fun Map<String, Any?>.boolean(key: String): Boolean = this[key] as? Boolean
        ?: error("expected boolean $key")

    private fun Byte.u8(): Int = toInt() and 0xff

    private fun ByteArray.u16(offset: Int): Int = this[offset].u8() or (this[offset + 1].u8() shl 8)

    private data class Decoded(
        val firmware: String,
        val statsTimeoutMs: Int,
        val dataType: Int,
        val isRealtime: Boolean,
        val systemId: Int,
        val cadenceRpm: Double,
        val heartRateBpm: Double,
        val powerWatts: Int,
        val energyKcal: Int,
        val elapsedSeconds: Int,
        val distanceUnit: String,
        val distanceRawTenths: Int,
        val distanceMetres: Double,
        val resistance: Int?,
    )
}
