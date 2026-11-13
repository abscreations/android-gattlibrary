package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Phase 2 parity with the two CSC-related TMIW tests pinned by the executable plan. Moved from app tests
 * in LIB-76; the FitBridge native-cycling default case is the app's NativeCyclingPreferenceTest.
 */
class CscFixtureParityTest {
    private fun load(): Map<String, Any?> {
        val stream = javaClass.classLoader?.getResourceAsStream("fixtures/tmiw/csc_phase2.json")
        assertNotNull("CSC parity fixture is missing", stream)
        return FixtureJson.obj(FixtureJson.parse(stream!!.bufferedReader().use { it.readText() }))
    }

    @Test
    fun `TMIW combined packet accepts trailing bytes and decodes both event fields`() {
        val case = FixtureJson.obj(FixtureJson.array(load()["cases"]).single())
        val packet = FixtureJson.array(case["packet"])
            .map { FixtureJson.int(it).toByte() }
            .toByteArray()
        val expected = FixtureJson.obj(case["expect"])

        val reading = CscMeasurement.parse(packet)
        assertNotNull(reading)
        val wheel = reading!!.wheel!!
        val crank = reading.crank!!
        assertEquals(
            FixtureJson.int(expected["cumulativeWheelRevolutions"]).toLong(),
            wheel.cumulativeRevolutions,
        )
        assertEquals(
            FixtureJson.int(expected["lastWheelEventTimeTicks"]),
            wheel.lastEventTimeTicks,
        )
        assertEquals(
            FixtureJson.int(expected["cumulativeCrankRevolutions"]),
            crank.cumulativeRevolutions,
        )
        assertEquals(
            FixtureJson.int(expected["lastCrankEventTimeTicks"]),
            crank.lastEventTimeTicks,
        )
    }

    @Test
    fun `fixture pins both plan-named TMIW files and source commit`() {
        val fixture = load()
        assertEquals(
            setOf(
                "test/cycling_speed_and_cadence_sensor_test.dart",
                "test/native_biking_sensors_test.dart",
            ),
            FixtureJson.array(fixture["sources"]).map(FixtureJson::string).toSet(),
        )
        assertEquals(
            "85f2ee7520705c99ce81db7355df1be7f4b72ecf",
            FixtureJson.string(fixture["source_commit"]),
        )
        assertEquals("transcribed", FixtureJson.string(fixture["acquisition"]))
        assertEquals(
            "00002a5b-0000-1000-8000-00805f9b34fb",
            FixtureJson.string(fixture["characteristic"]),
        )
    }
}
