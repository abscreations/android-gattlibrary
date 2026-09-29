package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Central-side consumer for QZ issue #261's sanitized physical Chrono session. */
class PrecorHardwareCorpusTest {

    private data class Corpus(
        val raw: String,
        val root: Map<String, Any?>,
        val measurements: List<Map<String, Any?>>,
    )

    private fun load(): Corpus {
        val stream = javaClass.classLoader
            ?.getResourceAsStream("fixtures/hardware/precor_chrono_qz_issue_261.json")
        assertNotNull("physical Chrono fixture is missing", stream)
        val raw = stream!!.bufferedReader().use { it.readText() }
        val root = FixtureJson.obj(FixtureJson.parse(raw))
        val measurements = FixtureJson.array(root["measurements"]).map(FixtureJson::obj)
        return Corpus(raw, root, measurements)
    }

    private fun packetOf(case: Map<String, Any?>): ByteArray =
        FixtureJson.array(case["packet"])
            .map { FixtureJson.int(it).toByte() }
            .toByteArray()

    @Test
    fun `corpus pins the physical source counts and privacy boundary`() {
        val corpus = load()
        val root = corpus.root

        assertEquals("vendor.precor.chrono", FixtureJson.string(root["family"]))
        assertEquals("CHRONO 0", FixtureJson.string(root["deviceLabel"]))
        assertEquals("sanitized-hci-extraction", FixtureJson.string(root["acquisition"]))
        assertEquals(
            "https://github.com/cagnulein/qdomyos-zwift/issues/261",
            FixtureJson.string(root["sourceUrl"]),
        )
        assertEquals(
            "3aa1a9ca6397ced0abc906e5be55d373896d3ef927df00751469983a15d82809",
            FixtureJson.string(root["attachmentSha256"]),
        )
        assertEquals(
            "a026ee07-0a7d-4ab3-97fa-f1500f9feb8b",
            FixtureJson.string(root["service"]),
        )
        assertEquals(
            "a026e01d-0a7d-4ab3-97fa-f1500f9feb8b",
            FixtureJson.string(root["characteristic"]),
        )

        assertEquals(167, FixtureJson.int(root["measurementCount"]))
        assertEquals(167, corpus.measurements.size)
        val auxiliaryCount = corpus.measurements.count { it["auxiliaryDelayMs"] != null }
        assertEquals(166, FixtureJson.int(root["auxiliaryCount"]))
        assertEquals(166, auxiliaryCount)
        assertEquals(
            333,
            FixtureJson.int(root["measurementCount"]) + FixtureJson.int(root["auxiliaryCount"]),
        )
        assertEquals(333, FixtureJson.int(root["notificationCount"]))
        assertEquals(
            listOf(36, 8, 0),
            FixtureJson.array(root["auxiliaryPacket"]).map(FixtureJson::int),
        )

        val macAddress = Regex("(?i)(?:[0-9a-f]{2}:){5}[0-9a-f]{2}")
        assertFalse("the sanitized fixture must not contain a Bluetooth address", macAddress.containsMatchIn(corpus.raw))
        val allowedCaseKeys = setOf("name", "relativeMs", "packet", "auxiliaryDelayMs", "expect")
        assertTrue(corpus.measurements.all { it.keys == allowedCaseKeys })
    }

    @Test
    fun `every captured notification traverses the production central decoder without rejection`() {
        val corpus = load()
        val handler = PrecorProtocolHandler()
        val auxiliary = FixtureJson.array(corpus.root["auxiliaryPacket"])
            .map { FixtureJson.int(it).toByte() }
            .toByteArray()
        var measurements = 0
        var auxiliaries = 0

        for (case in corpus.measurements) {
            val label = FixtureJson.string(case["name"])
            val expect = FixtureJson.obj(case["expect"])
            val reading = handler.decodeNotification(packetOf(case))
            assertNotNull("$label failed the central Chrono gate", reading)
            reading!!

            assertEquals("$label elapsed", FixtureJson.int(expect["elapsedSeconds"]), reading.elapsedSeconds)
            assertEquals("$label heart rate", FixtureJson.int(expect["heartRateBpm"]), reading.heartRateBpm)
            assertEquals("$label speed", FixtureJson.double(expect["speedKph"]), reading.speedKph, 1e-9)
            assertEquals("$label cadence", FixtureJson.double(expect["cadenceRpm"]), reading.cadenceRpm, 1e-9)
            assertEquals("$label distance", FixtureJson.int(expect["distanceMetres"]), reading.distanceMetres)
            assertEquals("$label calories", FixtureJson.int(expect["calories"]), reading.calories)
            assertEquals(
                "$label unidentified field",
                FixtureJson.int(expect["unidentified15to16"]),
                reading.unidentified15to16,
            )
            assertEquals("$label power", FixtureJson.int(expect["powerWatts"]), reading.powerWatts)
            measurements++

            if (case["auxiliaryDelayMs"] != null) {
                assertNull("$label companion must carry no metrics", handler.decodeNotification(auxiliary))
                auxiliaries++
            }
        }

        assertEquals(167, measurements)
        assertEquals(166, auxiliaries)
        assertEquals("captured traffic is all recognized", 0, handler.rejectedFrameCount())
    }

    @Test
    fun `every physical reading maps to the honest central metric set`() {
        for (case in load().measurements) {
            val label = FixtureJson.string(case["name"])
            val reading = requireNotNull(PrecorMeasurement.parse(packetOf(case)))
            val samples = precorSamples(reading).associateBy { it.metric }

            assertEquals(
                "$label metric set",
                setOf(
                    MetricKind.SPEED,
                    MetricKind.CADENCE,
                    MetricKind.POWER,
                    MetricKind.DISTANCE,
                    MetricKind.ENERGY,
                ),
                samples.keys,
            )
            assertEquals(reading.speedKph, samples.getValue(MetricKind.SPEED).value, 1e-9)
            assertEquals(reading.cadenceRpm, samples.getValue(MetricKind.CADENCE).value, 1e-9)
            assertEquals(reading.powerWatts.toDouble(), samples.getValue(MetricKind.POWER).value, 1e-9)
            assertEquals(reading.distanceMetres.toDouble(), samples.getValue(MetricKind.DISTANCE).value, 1e-9)
            assertEquals(reading.calories.toDouble(), samples.getValue(MetricKind.ENERGY).value, 1e-9)
            assertEquals(
                reading.distanceMetres.toDouble(),
                samples.getValue(MetricKind.DISTANCE).nativeCumulative,
            )
            assertEquals(
                reading.calories.toDouble(),
                samples.getValue(MetricKind.ENERGY).nativeCumulative,
            )
            assertTrue(samples.values.all { it.eventMs == reading.elapsedSeconds * 1_000L })
        }
    }

    @Test
    fun `session counters are monotonic and delivery is approximately one hertz`() {
        val corpus = load()
        val times = corpus.measurements.map { FixtureJson.int(it["relativeMs"]) }
        val readings = corpus.measurements.map { requireNotNull(PrecorMeasurement.parse(packetOf(it))) }
        val intervals = times.zipWithNext { before, after -> after - before }
        val sorted = intervals.sorted()
        val median = (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        val declared = FixtureJson.obj(corpus.root["measurementIntervalMs"])

        assertTrue("relative times must advance", intervals.all { it > 0 })
        assertEquals(FixtureJson.int(declared["minimum"]), intervals.minOrNull())
        assertEquals(FixtureJson.int(declared["maximum"]), intervals.maxOrNull())
        assertEquals(FixtureJson.double(declared["median"]), median, 1.0)
        assertTrue("most captured deliveries should sit near 1 Hz", intervals.count { it in 900..1_100 } >= 145)

        assertTrue(readings.zipWithNext().all { (a, b) -> b.elapsedSeconds >= a.elapsedSeconds })
        assertTrue(readings.zipWithNext().all { (a, b) -> b.distanceMetres >= a.distanceMetres })
        assertTrue(readings.zipWithNext().all { (a, b) -> b.calories >= a.calories })
        assertTrue(readings.zipWithNext().all { (a, b) -> b.unidentified15to16 >= a.unidentified15to16 })
        assertTrue("working cadence must exercise the high octet", readings.all { it.confirmsCadenceIsUint16() })
    }
}
