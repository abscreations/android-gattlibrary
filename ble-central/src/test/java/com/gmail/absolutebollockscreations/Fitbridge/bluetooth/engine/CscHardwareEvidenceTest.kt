package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replays the redacted physical CSC sensor frames archived on 2026-08-10. */
class CscHardwareEvidenceTest {

    private fun load(): Map<String, Any?> {
        val stream = javaClass.classLoader
            ?.getResourceAsStream("fixtures/hardware/csc_physical_sensors_20260810.json")
        assertNotNull("hardware CSC evidence fixture is missing", stream)
        return FixtureJson.obj(FixtureJson.parse(stream!!.bufferedReader().use { it.readText() }))
    }

    private fun hexBytes(hex: String): ByteArray {
        assertTrue("hex input must contain complete bytes", hex.length % 2 == 0)
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    @Test
    fun `physical frames reproduce captured bike and elliptical metrics`() {
        val fixture = load()
        val calibration = CscCalibration(
            wheelCircumferenceMm = FixtureJson.int(fixture["wheel_circumference_mm"]),
            ellipticalStrideLengthMm = FixtureJson.int(fixture["elliptical_stride_length_mm"]),
        )

        for (rawSession in FixtureJson.array(fixture["sessions"])) {
            val session = FixtureJson.obj(rawSession)
            val sessionId = FixtureJson.string(session["id"])
            val persona = CscWorkoutPersona.valueOf(FixtureJson.string(session["persona"]))
            val supportedRoles = roles(session["supported_roles"])
            val assignedRoles = roles(session["assigned_roles"])
            val tracker = CscMeasurementTracker()
            val derivation = CscDerivationState()

            for (rawFrame in FixtureJson.array(session["frames"])) {
                val frame = FixtureJson.obj(rawFrame)
                val expected = FixtureJson.obj(frame["expect"])
                val seq = FixtureJson.int(frame["seq"])
                val update = tracker.accept(hexBytes(FixtureJson.string(frame["hex"])))
                assertNotNull("$sessionId seq $seq failed to parse", update)
                update!!

                val wheel = update.reading.wheel
                val crank = update.reading.crank
                assertTrue("$sessionId seq $seq must carry exactly one native event", (wheel == null) xor (crank == null))
                if (wheel != null) {
                    assertEquals(FixtureJson.int(expected["counter"]).toLong(), wheel.cumulativeRevolutions)
                    assertEquals(FixtureJson.int(expected["event_ticks"]), wheel.lastEventTimeTicks)
                } else {
                    requireNotNull(crank)
                    assertEquals(FixtureJson.int(expected["counter"]), crank.cumulativeRevolutions)
                    assertEquals(FixtureJson.int(expected["event_ticks"]), crank.lastEventTimeTicks)
                }

                val derived = derivation.derive(
                    update = update,
                    persona = persona,
                    supportedRoles = supportedRoles,
                    assignedRoles = assignedRoles,
                    calibration = calibration,
                )
                if (frame["baseline"] == true) {
                    assertTrue("$sessionId baseline must emit nothing", derived.isEmpty())
                    continue
                }

                val deltaRevolutions = FixtureJson.int(expected["delta_revolutions"])
                val deltaTicks = FixtureJson.int(expected["delta_ticks"])
                update.wheelDelta?.let {
                    assertEquals(deltaRevolutions.toLong(), it.revolutions)
                    assertEquals(deltaTicks, it.eventTimeTicks)
                }
                update.crankDelta?.let {
                    assertEquals(deltaRevolutions, it.revolutions)
                    assertEquals(deltaTicks, it.eventTimeTicks)
                }
                assertMetricIfPresent(derived, expected, "speed_kph", MetricKind.SPEED)
                assertMetricIfPresent(derived, expected, "cadence_per_minute", MetricKind.CADENCE)
                assertMetricIfPresent(derived, expected, "total_strides", MetricKind.TOTAL_STRIDES)
                assertMetricIfPresent(derived, expected, "distance_metres", MetricKind.DISTANCE)
            }
        }
    }

    @Test
    fun `fixture pins physical provenance and incomplete identity evidence`() {
        val fixture = load()
        val sources = FixtureJson.array(fixture["sources"]).map(FixtureJson::obj)
        assertEquals(
            setOf(
                "53afe432760ead5c5a5d2c4b617844547e34175e9b3bd5ad018cae6a207bfe38",
                "ff153c43a25fbd9551c808563b2cb02221b6a53390337eb0e068ecdfe5c63cc9",
                "ffd2810349c946f9ed4152ceaa2c726aa908321adf99520b467728c98e0e5aee",
            ),
            sources.mapTo(linkedSetOf()) { FixtureJson.string(it["source_sha256"]) },
        )
        assertTrue(sources.all { it["firmware"] == null })
        assertEquals("hardware-redacted-ndjson", FixtureJson.string(fixture["acquisition"]))

        val sessions = FixtureJson.array(fixture["sessions"]).map(FixtureJson::obj)
        assertEquals(5, sessions.size)
        assertEquals(2, sessions.count { FixtureJson.string(it["persona"]) == "ELLIPTICAL" })
        assertTrue(sessions.any { FixtureJson.string(it["feature_hex"]) == "0200" })
        assertTrue(sessions.none { FixtureJson.string(it["feature_hex"]) == "0300" })
    }

    private fun roles(value: Any?): Set<BtEngineState.CscRole> =
        FixtureJson.array(value).mapTo(linkedSetOf()) {
            BtEngineState.CscRole.valueOf(FixtureJson.string(it))
        }

    private fun assertMetricIfPresent(
        derived: List<CscDerivedMetric>,
        expected: Map<String, Any?>,
        key: String,
        metric: MetricKind,
    ) {
        if (key !in expected) return
        assertEquals(
            FixtureJson.double(expected[key]),
            derived.single { it.metric == metric }.value,
            1e-9,
        )
    }
}
