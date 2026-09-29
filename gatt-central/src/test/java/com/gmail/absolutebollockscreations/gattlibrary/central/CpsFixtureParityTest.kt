package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class CpsFixtureParityTest {
    private fun load(): Map<String, Any?> {
        val stream = javaClass.classLoader?.getResourceAsStream("fixtures/tmiw/cps_phase3.json")
        assertNotNull("CPS parity fixture is missing", stream)
        return FixtureJson.obj(FixtureJson.parse(stream!!.bufferedReader().use { it.readText() }))
    }

    @Test
    fun `TMIW sample and Stages SC3 cadence vectors retain parity`() {
        val cases = FixtureJson.array(load()["cases"]).map(FixtureJson::obj)
        val readings = cases.associate { case ->
            FixtureJson.string(case["id"]) to CpsMeasurement.parse(packet(case))!!
        }
        assertEquals(103, readings.getValue("tmiw-sample").instantaneousPowerWatts)
        assertEquals(228, readings.getValue("tmiw-sample").crank?.cumulativeRevolutions)

        val tracker = CpsMeasurementTracker()
        tracker.accept(packet(cases.single { it["id"] == "stages-sc3-baseline" }))
        val delta = tracker.accept(packet(cases.single { it["id"] == "stages-sc3-next" }))!!
            .crankDelta!!
        assertEquals(58, CpsDerivationState.crankCadenceRpm(delta)?.roundToInt())
    }

    @Test
    fun `negative power is an intentional signedness correction`() {
        val case = FixtureJson.array(load()["cases"]).map(FixtureJson::obj)
            .single { it["id"] == "negative-power-divergence" }
        assertEquals(-3, CpsMeasurement.parse(packet(case))?.instantaneousPowerWatts)
        assertEquals(65533, FixtureJson.int(case["tmiwValue"]))
        assertTrue(FixtureJson.string(case["divergenceReason"]).contains("signed"))
    }

    @Test
    fun `fixture pins source and license provenance`() {
        val fixture = load()
        assertEquals("85f2ee7520705c99ce81db7355df1be7f4b72ecf", fixture["source_commit"])
        assertEquals("Apache-2.0", fixture["license"])
        assertEquals("transcribed", fixture["acquisition"])
        assertEquals(
            setOf("test/power_meter_sensor_test.dart", "lib/devices/gadgets/cycling_power_meter_sensor.dart"),
            FixtureJson.array(fixture["sources"]).map(FixtureJson::string).toSet(),
        )
    }

    private fun packet(case: Map<String, Any?>): ByteArray = FixtureJson.array(case["packet"])
        .map { FixtureJson.int(it).toByte() }.toByteArray()
}
