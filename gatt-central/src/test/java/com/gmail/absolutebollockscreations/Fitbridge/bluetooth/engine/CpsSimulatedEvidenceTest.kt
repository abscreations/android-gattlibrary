package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replays CPS frames archived from simulated combined CSC+CPS session 8f8c. */
class CpsSimulatedEvidenceTest {

    private fun load(): Map<String, Any?> {
        val stream = javaClass.classLoader
            ?.getResourceAsStream("fixtures/simulated/cps_tmiw_bike_8f8c.json")
        assertNotNull("simulated CPS evidence fixture is missing", stream)
        return FixtureJson.obj(FixtureJson.parse(stream!!.bufferedReader().use { it.readText() }))
    }

    @Test
    fun `captured frames reproduce power cadence and event-time rollover`() {
        for (rawScenario in FixtureJson.array(load()["scenarios"])) {
            val scenario = FixtureJson.obj(rawScenario)
            val tracker = CpsMeasurementTracker()
            FixtureJson.array(scenario["frames"]).forEachIndexed { index, rawFrame ->
                val frame = FixtureJson.obj(rawFrame)
                val expected = FixtureJson.obj(frame["expect"])
                val update = tracker.accept(hexBytes(FixtureJson.string(frame["hex"])))
                assertNotNull("seq ${FixtureJson.int(frame["seq"])} failed to parse", update)
                update!!
                val crank = requireNotNull(update.reading.crank)
                assertEquals(FixtureJson.int(expected["powerWatts"]), update.reading.instantaneousPowerWatts)
                assertEquals(FixtureJson.int(expected["crankRevolutions"]), crank.cumulativeRevolutions)
                assertEquals(FixtureJson.int(expected["crankEventTimeTicks"]), crank.lastEventTimeTicks)

                if (index == 0) {
                    assertNull(update.crankDelta)
                } else {
                    val delta = requireNotNull(update.crankDelta)
                    assertEquals(FixtureJson.int(expected["deltaRevolutions"]), delta.revolutions)
                    assertEquals(FixtureJson.int(expected["deltaTicks"]), delta.eventTimeTicks)
                    assertEquals(
                        FixtureJson.double(expected["cadenceRpm"]),
                        requireNotNull(CpsDerivationState.crankCadenceRpm(delta)),
                        1e-9,
                    )
                }
            }
        }

        val lastFrame = FixtureJson.obj(load()["last_frame"])
        val expected = FixtureJson.obj(lastFrame["expect"])
        val reading = requireNotNull(CpsMeasurement.parse(hexBytes(FixtureJson.string(lastFrame["hex"]))))
        assertEquals(FixtureJson.int(expected["powerWatts"]), reading.instantaneousPowerWatts)
        assertEquals(FixtureJson.int(expected["crankRevolutions"]), reading.crank?.cumulativeRevolutions)
        assertEquals(FixtureJson.int(expected["crankEventTimeTicks"]), reading.crank?.lastEventTimeTicks)
    }

    @Test
    fun `fixture pins simulator provenance setup and observed bounds`() {
        val fixture = load()

        assertEquals("S", FixtureJson.string(fixture["tier"]))
        assertEquals("simulator-redacted-ndjson", FixtureJson.string(fixture["acquisition"]))
        assertEquals(
            "5926f598b80c09af46f722b8a3dd3f65ee8da4bb90bcf1515db469d9471d105d",
            FixtureJson.string(fixture["archive_sha256"]),
        )
        assertEquals(
            "7cb680b042c803296785b2106f5cd44d4eee3f38ac7a41d46e84d1af742189c3",
            FixtureJson.string(fixture["source_sha256"]),
        )
        assertEquals(setOf("1826", "1816", "1818"), FixtureJson.array(fixture["advertised_services"])
            .map(FixtureJson::string).toSet())
        assertEquals("08000000", FixtureJson.string(fixture["feature_hex"]))
        assertEquals(13, FixtureJson.int(fixture["sensor_location"]))
        assertEquals("SUCCESS", FixtureJson.string(fixture["cccd_result"]))
        assertEquals(88, FixtureJson.int(fixture["observed_notification_count"]))
        assertEquals(163, FixtureJson.int(fixture["observed_power_min_watts"]))
        assertEquals(183, FixtureJson.int(fixture["observed_power_max_watts"]))
        assertEquals(null, fixture["simulator_revision"])
        assertTrue(FixtureJson.string(fixture["note"]).contains("not physical"))
    }

    private fun hexBytes(hex: String): ByteArray {
        assertTrue("hex input must contain complete bytes", hex.length % 2 == 0)
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
