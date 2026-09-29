package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replays combined CSC frames archived from simulated TMIW-Bike session be87. */
class CscSimulatedEvidenceTest {

    private fun load(): Map<String, Any?> {
        val stream = javaClass.classLoader
            ?.getResourceAsStream("fixtures/simulated/csc_tmiw_bike_be87.json")
        assertNotNull("simulated CSC evidence fixture is missing", stream)
        return FixtureJson.obj(FixtureJson.parse(stream!!.bufferedReader().use { it.readText() }))
    }

    private fun hexBytes(hex: String): ByteArray {
        assertTrue("hex input must contain complete bytes", hex.length % 2 == 0)
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    @Test
    fun `combined simulator frames reproduce captured bike metrics`() {
        val fixture = load()
        val tracker = CscMeasurementTracker()
        val derivation = CscDerivationState()
        val roles = BtEngineState.CscRole.entries.toSet()
        val calibration = CscCalibration(
            wheelCircumferenceMm = FixtureJson.int(fixture["wheel_circumference_mm"]),
        )

        for (raw in FixtureJson.array(fixture["cases"])) {
            val case = FixtureJson.obj(raw)
            val expected = FixtureJson.obj(case["expect"])
            val update = tracker.accept(hexBytes(FixtureJson.string(case["hex"])))
            assertNotNull("seq ${FixtureJson.int(case["seq"])} failed to parse", update)
            update!!
            val wheel = requireNotNull(update.reading.wheel)
            val crank = requireNotNull(update.reading.crank)

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

            val derived = derivation.derive(
                update = update,
                persona = CscWorkoutPersona.BIKE,
                supportedRoles = roles,
                assignedRoles = roles,
                calibration = calibration,
            )
            if (case["baseline"] == true) {
                assertNull(update.wheelDelta)
                assertNull(update.crankDelta)
                assertTrue(derived.isEmpty())
            } else {
                val wheelDelta = requireNotNull(update.wheelDelta)
                val crankDelta = requireNotNull(update.crankDelta)
                assertEquals(
                    FixtureJson.int(expected["wheelDeltaRevolutions"]).toLong(),
                    wheelDelta.revolutions,
                )
                assertEquals(
                    FixtureJson.int(expected["wheelDeltaTicks"]),
                    wheelDelta.eventTimeTicks,
                )
                assertEquals(
                    FixtureJson.int(expected["crankDeltaRevolutions"]),
                    crankDelta.revolutions,
                )
                assertEquals(
                    FixtureJson.int(expected["crankDeltaTicks"]),
                    crankDelta.eventTimeTicks,
                )
                assertEquals(
                    FixtureJson.double(expected["speedKph"]),
                    derived.metric(MetricKind.SPEED).value,
                    1e-9,
                )
                assertEquals(
                    FixtureJson.double(expected["cadenceRpm"]),
                    derived.metric(MetricKind.CADENCE).value,
                    1e-9,
                )
                assertEquals(
                    FixtureJson.double(expected["distanceMetres"]),
                    derived.metric(MetricKind.DISTANCE).value,
                    1e-9,
                )
            }
        }
    }

    @Test
    fun `fixture pins simulated provenance and combined feature evidence`() {
        val fixture = load()

        assertEquals(
            "0ca02e1d7a18e87190cfd5c4276237d11dc817296f583a68e07b7b13ada17477",
            FixtureJson.string(fixture["source_sha256"]),
        )
        assertEquals("simulator-redacted-ndjson", FixtureJson.string(fixture["acquisition"]))
        assertEquals("0300", FixtureJson.string(fixture["feature_hex"]))
        assertEquals(63, FixtureJson.int(fixture["observed_notification_count"]))
        assertEquals(null, fixture["simulator_revision"])
    }

    private fun List<CscDerivedMetric>.metric(kind: MetricKind): CscDerivedMetric =
        single { it.metric == kind }
}
