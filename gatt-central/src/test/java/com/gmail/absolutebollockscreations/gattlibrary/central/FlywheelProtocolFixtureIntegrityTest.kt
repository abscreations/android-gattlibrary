package com.gmail.absolutebollockscreations.gattlibrary.central

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 12.1 fixture checks deliberately have no dependency on a production Flywheel decoder. */
class FlywheelProtocolFixtureIntegrityTest {
    private val resource = "fixtures/gymnasticon/flywheel.json"

    @Test
    fun `normative and supplemental MIT provenance are pinned`() {
        val fixture = load()
        val sources = FixtureJson.array(fixture["sources"]).map(FixtureJson::obj)
        val original = sources.single { it.string("id") == "gymnasticon-v1.4.1" }
        val newerFork = sources.single { it.string("id") == "gymnasticon-v2" }

        assertEquals("flywheel-sports", fixture.string("family"))
        assertTrue(fixture.string("evidence_tier").startsWith("tier 1 "))
        assertSource(
            original,
            "e142f539c518d6d4ced3abdd587a825b918a9367",
            setOf("src/bikes/flywheel.js", "src/test/bikes/flywheel.js", "src/util/dropout-filter.js", "LICENSE.txt", "package.json"),
        )
        assertSource(
            newerFork,
            "643f1c3376876dabf793f42804505a7ee1c1481a",
            setOf("src/bikes/flywheel.js", "src/test/bikes/flywheel.js", "src/util/dropout-filter.js", "LICENSE", "package.json"),
        )
        assertTrue(newerFork.string("note").contains("not independent"))
        assertTrue(sources.any { it.string("kind") == "authored-adversarial" })
    }

    @Test
    fun `GATT and wire contract pin the narrow measurement-only protocol`() {
        val fixture = load()
        val gatt = FixtureJson.obj(fixture["gatt_contract"])
        val wire = FixtureJson.obj(fixture["wire_contract"])

        assertEquals("Flywheel 1", gatt.string("local_name"))
        assertEquals("6e400001-b5a3-f393-e0a9-e50e24dcca9e", gatt.string("service_uuid"))
        assertEquals("6e400002-b5a3-f393-e0a9-e50e24dcca9e", gatt.string("rx_write_uuid"))
        assertEquals("6e400003-b5a3-f393-e0a9-e50e24dcca9e", gatt.string("tx_notify_uuid"))
        assertTrue(gatt.string("control_policy").contains("unsupported"))
        assertEquals(13, FixtureJson.int(wire["minimum_packet_bytes"]))
        assertEquals("ff1f0c", wire.string("magic_hex"))
        assertEquals("uint16-big-endian", wire.string("power_encoding"))
        assertEquals(3, FixtureJson.int(wire["power_offset"]))
        assertEquals(12, FixtureJson.int(wire["cadence_offset"]))
        assertEquals(listOf("5..11", "13..end"), FixtureJson.array(wire["unknown_ranges"]).map(FixtureJson::string))
        assertTrue(wire.string("trailing_byte_policy").startsWith("accept"))
    }

    @Test
    fun `canonical source vector and corpus counts are exact`() {
        val fixture = load()
        val counts = FixtureJson.obj(fixture["corpus_counts"])
        val normative = FixtureJson.array(fixture["normative_cases"]).map(FixtureJson::obj)
        val boundaries = FixtureJson.array(fixture["boundary_cases"]).map(FixtureJson::obj)
        val wrongMagic = FixtureJson.array(fixture["wrong_magic_cases"]).map(FixtureJson::obj)
        val truncation = FixtureJson.obj(fixture["truncation_matrix"])
        val dropout = FixtureJson.obj(fixture["dropout_policy"])
        val sourceCase = normative.single()
        val packet = hexBytes(sourceCase.string("packet_hex"))

        assertEquals(FixtureJson.int(counts["normative_cases"]), normative.size)
        assertEquals(FixtureJson.int(counts["boundary_cases"]), boundaries.size)
        assertEquals(FixtureJson.int(counts["wrong_magic_cases"]), wrongMagic.size)
        assertEquals(FixtureJson.int(counts["truncation_lengths"]), FixtureJson.array(truncation["truncate_to_lengths"]).size)
        assertEquals(FixtureJson.int(counts["dropout_steps"]), FixtureJson.array(dropout["sequence"]).size)
        assertEquals(34, FixtureJson.int(sourceCase["packet_bytes"]))
        assertEquals(34, packet.size)
        assertEquals(sourceCase.string("packet_binary_sha256"), packet.sha256())
        assertReading(sourceCase, requireNotNull(referenceDecode(packet)))
    }

    @Test
    fun `unsigned big-endian boundaries zero and trailing bytes are pinned`() {
        val cases = FixtureJson.array(load()["boundary_cases"]).map(FixtureJson::obj)
        assertEquals(setOf("high-byte-set-power", "maximum-unsigned-fields", "zero-fields", "accepted-trailing-bytes"), cases.mapTo(linkedSetOf()) { it.string("id") })

        for (case in cases) {
            val packet = packet(case)
            assertTrue(case.string("id"), referenceValid(packet))
            assertReading(case, requireNotNull(referenceDecode(packet)))
            assertEquals("fitbridge-authored", case.string("origin"))
        }
        assertEquals(32769, requireNotNull(referenceDecode(packet(cases[0]))).powerWatts)
        assertEquals(65535, requireNotNull(referenceDecode(packet(cases[1]))).powerWatts)
        assertEquals(15, packet(cases.single { it.string("id") == "accepted-trailing-bytes" }).size)
    }

    @Test
    fun `every short prefix and every magic-byte mutation is rejected`() {
        val fixture = load()
        val source = hexBytes(FixtureJson.obj(FixtureJson.array(fixture["normative_cases"]).single()).string("packet_hex"))
        val matrix = FixtureJson.obj(fixture["truncation_matrix"])
        val lengths = FixtureJson.array(matrix["truncate_to_lengths"]).map(FixtureJson::int)
        assertEquals((0..12).toList(), lengths)
        assertFalse(matrix.boolean("expect_structurally_valid"))
        assertTrue(lengths.all { !referenceValid(source.copyOf(it)) })

        val wrongMagic = FixtureJson.array(fixture["wrong_magic_cases"]).map(FixtureJson::obj)
        assertEquals(3, wrongMagic.size)
        wrongMagic.forEachIndexed { index, case ->
            val packet = packet(case)
            assertEquals("wrong-magic-byte-$index", case.string("id"))
            assertFalse(referenceValid(packet))
            assertEquals("fitbridge-authored", case.string("origin"))
        }
    }

    @Test
    fun `D96 sequence repairs power and cadence independently and resets by generation`() {
        val policy = FixtureJson.obj(load()["dropout_policy"])
        assertEquals("D96", policy.string("decision"))
        assertEquals("V26", policy.string("divergence"))
        assertEquals(setOf("power_watts", "cadence_rpm"), FixtureJson.array(policy["fields"]).mapTo(linkedSetOf(), FixtureJson::string))
        assertTrue(policy.string("evidence_note").contains("power dropouts only"))

        val filter = ReferenceDropoutFilter()
        for (step in FixtureJson.array(policy["sequence"]).map(FixtureJson::obj)) {
            val input = pair(step["input"])
            assertEquals(pair(step["expect"]), filter.apply(input))
        }
        filter.reset()
        val afterReset = FixtureJson.obj(policy["after_generation_reset"])
        assertEquals(pair(afterReset["expect"]), filter.apply(pair(afterReset["input"])))
    }

    private fun assertSource(source: Map<String, Any?>, commit: String, expectedPaths: Set<String>) {
        assertEquals(commit, source.string("commit"))
        assertEquals("MIT", source.string("license"))
        assertEquals(expectedPaths, FixtureJson.array(source["paths"]).mapTo(linkedSetOf(), FixtureJson::string))
    }

    private fun assertReading(case: Map<String, Any?>, actual: Reading) {
        val expected = FixtureJson.obj(case["expect"])
        assertTrue(expected.boolean("structurally_valid"))
        assertEquals(FixtureJson.int(expected["power_watts"]), actual.powerWatts)
        assertEquals(FixtureJson.int(expected["cadence_rpm"]), actual.cadenceRpm)
    }

    private fun referenceValid(packet: ByteArray): Boolean =
        packet.size >= 13 && packet[0].u8() == 0xff && packet[1].u8() == 0x1f && packet[2].u8() == 0x0c

    private fun referenceDecode(packet: ByteArray): Reading? {
        if (!referenceValid(packet)) return null
        return Reading(powerWatts = (packet[3].u8() shl 8) or packet[4].u8(), cadenceRpm = packet[12].u8())
    }

    private fun packet(case: Map<String, Any?>): ByteArray = FixtureJson.array(case["packet"])
        .map { FixtureJson.int(it).toByte() }
        .toByteArray()

    private fun pair(value: Any?): Reading {
        val values = FixtureJson.array(value).map(FixtureJson::int)
        return Reading(values[0], values[1])
    }

    private fun hexBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it.u8()) }

    private fun load(): Map<String, Any?> {
        val stream = javaClass.classLoader?.getResourceAsStream(resource) ?: error("missing $resource")
        return FixtureJson.obj(FixtureJson.parse(stream.bufferedReader().use { it.readText() }))
    }

    private fun Map<String, Any?>.string(key: String): String = FixtureJson.string(this[key])
    private fun Map<String, Any?>.boolean(key: String): Boolean = this[key] as? Boolean ?: error("expected boolean $key")
    private fun Byte.u8(): Int = toInt() and 0xff

    private data class Reading(val powerWatts: Int, val cadenceRpm: Int)

    private class ReferenceDropoutFilter {
        private var last = Reading(0, 0)
        private var powerDropped = false
        private var cadenceDropped = false

        fun apply(input: Reading): Reading {
            val power = if (input.powerWatts == 0) {
                if (powerDropped) 0 else last.powerWatts.also { powerDropped = true }
            } else {
                input.powerWatts.also { last = last.copy(powerWatts = it); powerDropped = false }
            }
            val cadence = if (input.cadenceRpm == 0) {
                if (cadenceDropped) 0 else last.cadenceRpm.also { cadenceDropped = true }
            } else {
                input.cadenceRpm.also { last = last.copy(cadenceRpm = it); cadenceDropped = false }
            }
            return Reading(power, cadence)
        }

        fun reset() {
            last = Reading(0, 0)
            powerDropped = false
            cadenceDropped = false
        }
    }
}
