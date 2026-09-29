package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replays the redacted Zwift RunPod frames archived for Phase 1 session 8a96. */
class RscHardwareEvidenceTest {

    private fun load(): Map<String, Any?> {
        val stream = javaClass.classLoader
            ?.getResourceAsStream("fixtures/hardware/rsc_zwift_runpod_8a96.json")
        assertNotNull("hardware evidence fixture is missing", stream)
        return FixtureJson.obj(FixtureJson.parse(stream!!.bufferedReader().use { it.readText() }))
    }

    private fun hexBytes(hex: String): ByteArray {
        assertTrue("hex input must contain complete bytes", hex.length % 2 == 0)
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    @Test
    fun `redacted hardware notifications decode to the captured RunPod values`() {
        val fixture = load()
        var previousDistance = Double.NEGATIVE_INFINITY

        for (raw in FixtureJson.array(fixture["cases"])) {
            val case = FixtureJson.obj(raw)
            val expect = FixtureJson.obj(case["expect"])
            val reading = RscMeasurement.parse(hexBytes(FixtureJson.string(case["hex"])))
            assertNotNull("seq ${FixtureJson.int(case["seq"])} failed to parse", reading)
            reading!!

            assertEquals(FixtureJson.double(expect["speedKph"]), reading.speedKph, 1e-9)
            assertEquals(
                FixtureJson.int(expect["cadenceStepsPerMinute"]),
                reading.cadenceStepsPerMinute,
            )
            assertEquals(
                FixtureJson.double(expect["strideLengthMetres"]),
                reading.strideLengthMetres!!,
                1e-9,
            )
            val distance = reading.totalDistanceMetres!!
            assertEquals(FixtureJson.double(expect["totalDistanceMetres"]), distance, 1e-9)
            assertEquals(expect["running"] as Boolean, reading.running)
            assertTrue("native distance must be monotonic", distance > previousDistance)
            previousDistance = distance
        }
    }

    @Test
    fun `hardware fixture pins provenance and records the incomplete firmware evidence`() {
        val fixture = load()

        assertEquals(
            "bdfbfc5b079bdf850133c3b977be3750626f648ae880fa44dc4746b104cbcfe7",
            FixtureJson.string(fixture["source_sha256"]),
        )
        assertEquals("hardware-redacted-ndjson", FixtureJson.string(fixture["acquisition"]))
        assertEquals(null, fixture["firmware"])
    }
}
