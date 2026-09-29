package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlywheelMeasurementTest {
    private val resource = "fixtures/gymnasticon/flywheel.json"

    @Test
    fun `production decoder matches normative and boundary fixtures`() {
        val fixture = load()
        val cases = FixtureJson.array(fixture["normative_cases"]).map(FixtureJson::obj) +
            FixtureJson.array(fixture["boundary_cases"]).map(FixtureJson::obj)

        for (case in cases) {
            val expected = FixtureJson.obj(case["expect"])
            val result = FlywheelMeasurement.decode(packet(case))
            assertTrue(case.string("id"), result is FlywheelDecodeResult.Decoded)
            val reading = (result as FlywheelDecodeResult.Decoded).reading
            assertEquals(FixtureJson.int(expected["power_watts"]), reading.powerWatts)
            assertEquals(FixtureJson.int(expected["cadence_rpm"]), reading.cadenceRpm)
        }

        val highByte = cases.single { it.string("id") == "high-byte-set-power" }
        val decoded = (FlywheelMeasurement.decode(packet(highByte)) as FlywheelDecodeResult.Decoded).reading
        assertEquals(0x8001, decoded.powerWatts)
        assertNotEquals("little-endian regression would produce 0x0180", 0x0180, decoded.powerWatts)
    }

    @Test
    fun `every frame shorter than cadence is rejected without field access`() {
        val fixture = load()
        val normative = FixtureJson.obj(FixtureJson.array(fixture["normative_cases"]).single())
        val source = packet(normative)
        val matrix = FixtureJson.obj(fixture["truncation_matrix"])
        val lengths = FixtureJson.array(matrix["truncate_to_lengths"]).map(FixtureJson::int)

        assertEquals((0 until FlywheelMeasurement.MINIMUM_FRAME_BYTES).toList(), lengths)
        for (length in lengths) {
            assertEquals(
                "length=$length",
                FlywheelDecodeResult.Rejected(FlywheelRejection.TOO_SHORT),
                FlywheelMeasurement.decode(source.copyOf(length)),
            )
        }
    }

    @Test
    fun `each incorrect magic byte has a typed rejection and no reading`() {
        val cases = FixtureJson.array(load()["wrong_magic_cases"]).map(FixtureJson::obj)
        for (case in cases) {
            val result = FlywheelMeasurement.decode(packet(case))
            assertEquals(
                case.string("id"),
                FlywheelDecodeResult.Rejected(FlywheelRejection.BAD_MAGIC),
                result,
            )
            assertFalse(result is FlywheelDecodeResult.Decoded)
        }
    }

    @Test
    fun `unknown prefix and trailing bytes never change decoded fields`() {
        val base = byteArrayOf(
            0xff.toByte(), 0x1f, 0x0c, 0x01, 0x22,
            0, 0, 0, 0, 0, 0, 0,
            0x5a,
        )
        val changedUnknowns = base.copyOf().also {
            for (index in 5..11) it[index] = (0x80 + index).toByte()
        }
        val withTail = changedUnknowns + byteArrayOf(0x00, 0x7f, 0x80.toByte(), 0xff.toByte())
        val expected = FlywheelMeasurement.Reading(powerWatts = 290, cadenceRpm = 90)

        assertEquals(expected, decoded(base))
        assertEquals(expected, decoded(changedUnknowns))
        assertEquals(expected, decoded(withTail))
    }

    @Test
    fun `D96 fixture sequence repairs each field independently`() {
        val policy = FixtureJson.obj(load()["dropout_policy"])
        val filter = FlywheelDropoutFilter()

        for ((index, rawStep) in FixtureJson.array(policy["sequence"]).withIndex()) {
            val step = FixtureJson.obj(rawStep)
            val input = reading(step["input"])
            val expected = reading(step["expect"])
            assertEquals("step=$index input=$input", expected, filter.filter(input))
        }
    }

    @Test
    fun `substitution flags distinguish first and consecutive zeros`() {
        val filter = FlywheelDropoutFilter()
        filter.filter(FlywheelMeasurement.Reading(200, 95))

        assertEquals(FlywheelMeasurement.Reading(200, 95), filter.filter(FlywheelMeasurement.Reading(0, 0)))
        assertTrue(filter.lastSubstitutedPower)
        assertTrue(filter.lastSubstitutedCadence)

        assertEquals(FlywheelMeasurement.Reading(0, 0), filter.filter(FlywheelMeasurement.Reading(0, 0)))
        assertFalse(filter.lastSubstitutedPower)
        assertFalse(filter.lastSubstitutedCadence)
    }

    @Test
    fun `generation reset clears remembered values and dropped state`() {
        val policy = FixtureJson.obj(load()["dropout_policy"])
        val afterReset = FixtureJson.obj(policy["after_generation_reset"])
        val filter = FlywheelDropoutFilter()

        filter.filter(FlywheelMeasurement.Reading(250, 100))
        filter.filter(FlywheelMeasurement.Reading(0, 0))
        filter.reset()

        assertFalse(filter.lastSubstitutedPower)
        assertFalse(filter.lastSubstitutedCadence)
        assertEquals(reading(afterReset["expect"]), filter.filter(reading(afterReset["input"])))
    }

    private fun decoded(bytes: ByteArray): FlywheelMeasurement.Reading =
        (FlywheelMeasurement.decode(bytes) as FlywheelDecodeResult.Decoded).reading

    private fun packet(case: Map<String, Any?>): ByteArray {
        val hex = case["packet_hex"] as? String
        if (hex != null) {
            require(hex.length % 2 == 0)
            return ByteArray(hex.length / 2) { index ->
                hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }
        return FixtureJson.array(case["packet"]).map { FixtureJson.int(it).toByte() }.toByteArray()
    }

    private fun reading(value: Any?): FlywheelMeasurement.Reading {
        val fields = FixtureJson.array(value).map(FixtureJson::int)
        return FlywheelMeasurement.Reading(powerWatts = fields[0], cadenceRpm = fields[1])
    }

    private fun load(): Map<String, Any?> {
        val stream = javaClass.classLoader?.getResourceAsStream(resource) ?: error("missing $resource")
        return FixtureJson.obj(FixtureJson.parse(stream.bufferedReader().use { it.readText() }))
    }

    private fun Map<String, Any?>.string(key: String): String = FixtureJson.string(this[key])
}
