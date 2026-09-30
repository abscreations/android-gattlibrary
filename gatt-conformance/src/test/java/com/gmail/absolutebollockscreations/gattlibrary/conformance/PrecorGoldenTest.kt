package com.gmail.absolutebollockscreations.gattlibrary.conformance

import com.gmail.absolutebollockscreations.gattlibrary.central.FixtureJson
import com.gmail.absolutebollockscreations.gattlibrary.simulator.FtmsMockSample
import com.gmail.absolutebollockscreations.gattlibrary.simulator.PrecorChronoEncoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L25 golden check for Precor Chrono against `hardware/precor_chrono_qz_issue_261.json`, a
 * physical capture (QZ issue #261, HCI extraction). Every captured 19-octet measurement is rebuilt
 * from the fixture's independently decoded fields and must match the captured octets exactly,
 * and the auxiliary notification must match the captured `24 08 00`.
 *
 * Bytes 15-16 are the unidentified field the encoder derives as work = mean power x time; the
 * mean power fed in is the smallest that reproduces the captured value, so that field is held to
 * the capture too, without claiming it is understood.
 *
 * This also ties the literal in PrecorChronoEncoderTest ("captured notification sequence", elapsed
 * 446 s, 17.69 km/h, 62 rpm, 1 918 m, 9 kcal, 38 W): that sample is physical-000 here, so the
 * literal, the encoder and the fixture can only stay equal together.
 */
class PrecorGoldenTest {
    private val fixture = Fixtures.json("hardware/precor_chrono_qz_issue_261.json")
    private val measurements = Fixtures.objects(fixture["measurements"])

    private fun sampleFor(expect: Map<String, Any?>): FtmsMockSample {
        val elapsed = FixtureJson.int(expect["elapsedSeconds"])
        val kilojoules = FixtureJson.int(expect["unidentified15to16"])
        val meanPower = if (elapsed > 0) (kilojoules * 1_000 + elapsed - 1) / elapsed else 0
        return FtmsMockSample(
            elapsedSeconds = elapsed,
            heartRate = FixtureJson.int(expect["heartRateBpm"]),
            caloriesTotal = FixtureJson.int(expect["calories"]),
            caloriesPerHour = 0,
            caloriesPerMinute = 0,
            speedKph = FixtureJson.double(expect["speedKph"]),
            distanceMeters = FixtureJson.int(expect["distanceMetres"]),
            cadence = FixtureJson.double(expect["cadenceRpm"]),
            powerWatts = FixtureJson.int(expect["powerWatts"]),
            averagePowerWatts = meanPower,
        )
    }

    @Test
    fun everyCapturedMeasurementIsReproducedOctetForOctet() {
        assertEquals(FixtureJson.int(fixture["measurementCount"]), measurements.size)
        assertTrue("capture is non-trivial", measurements.size > 100)
        measurements.forEach { m ->
            val name = FixtureJson.string(m["name"])
            val golden = Fixtures.bytes(m["packet"])
            val encoded = PrecorChronoEncoder.encode(sampleFor(FixtureJson.obj(m["expect"])))
            assertArrayEquals("measurement $name", golden, encoded)
        }
    }

    @Test
    fun auxiliaryNotificationFollowsEveryMeasurementAndMatchesTheCapture() {
        val golden = Fixtures.bytes(fixture["auxiliaryPacket"])
        measurements.forEach { m ->
            val notifications = PrecorChronoEncoder.encodeNotifications(sampleFor(FixtureJson.obj(m["expect"])))
            assertEquals(2, notifications.size)
            assertArrayEquals(golden, notifications[1])
        }
    }

    @Test
    fun theLiteralSampleInTheEncoderTestIsTheFirstCapturedMeasurement() {
        val first = FixtureJson.obj(measurements.first()["expect"])
        assertEquals(446, FixtureJson.int(first["elapsedSeconds"]))
        assertEquals(17.69, FixtureJson.double(first["speedKph"]), 0.0)
        assertEquals(62, FixtureJson.int(first["cadenceRpm"]))
        assertEquals(1_918, FixtureJson.int(first["distanceMetres"]))
        assertEquals(9, FixtureJson.int(first["calories"]))
        assertEquals(38, FixtureJson.int(first["powerWatts"]))
    }
}
