package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan §5.5 consumer for the Precor Chrono corpus; Phase 5b §11.3 and §11.11.
 *
 * ## Why this exists when `PrecorMeasurementTest` already holds the same vectors
 *
 * It does not hold the same vectors — it holds *copies* of them, typed into Kotlin. That is the
 * distinction §5 is built around. A corpus file carries a provenance header naming the source
 * file and pinned commit, so a fixture is attributable to `TMIW@85f2ee75` on evidence rather
 * than on the say-so of whoever wrote the test. Vectors embedded in test source carry no such
 * claim and cannot be re-derived when the reference moves.
 *
 * Phase 5b shipped with its vectors in Kotlin only, so the family had parity coverage but no
 * corpus entry — invisible to anything that reasons about the corpus as a whole, and outside
 * the §11.11 evidence record. This file and `precor_spinner_chrono.json` close that.
 *
 * `PrecorMeasurementTest` keeps the adversarial cases, which is where §5.1 puts them: truncation,
 * corrupted prefix and saturated `uint16` values are FitBridge-authored, not observed, and
 * mixing them into a corpus whose header says `"acquisition": "transcribed"` would make the
 * provenance header a lie.
 *
 * ## The divergence this corpus is evidence for
 *
 * V10. QZ decodes Chrono cadence as `uint8` at byte 8 ÷ 2 and discards byte 9; TMIW reads
 * `uint16` LE at 8–9 ÷ 10. The corpus settles it, and [byte 9 is the cadence high octet in every
 * case] is that argument in executable form — which is exactly the kind of claim that belongs
 * against a provenanced corpus rather than against numbers someone retyped.
 */
class PrecorFixtureParityTest {

    private val corpus = "precor_spinner_chrono"

    private fun load(name: String): Map<String, Any?> {
        val stream = javaClass.classLoader?.getResourceAsStream("fixtures/tmiw/$name.json")
        assertNotNull("fixture $name.json is missing from test resources", stream)
        val text = stream!!.bufferedReader().use { it.readText() }
        return FixtureJson.obj(FixtureJson.parse(text))
    }

    private fun packetOf(case: Map<String, Any?>): ByteArray =
        FixtureJson.array(case["packet"]).map { FixtureJson.int(it).toByte() }.toByteArray()

    private fun cases(): List<Map<String, Any?>> =
        FixtureJson.array(load(corpus)["cases"]).map(FixtureJson::obj)

    @Test
    fun `every corpus case decodes exactly as TrackMyIndoorWorkout does`() {
        val fixture = load(corpus)
        val family = FixtureJson.string(fixture["family"])
        var casesChecked = 0

        for (case in FixtureJson.array(fixture["cases"]).map(FixtureJson::obj)) {
            val label = "$family/${FixtureJson.string(case["name"])}"
            val expect = FixtureJson.obj(case["expect"])

            val reading = PrecorMeasurement.parse(packetOf(case))
            assertNotNull("$label failed to parse", reading)
            reading!!

            assertEquals(
                "$label elapsed",
                FixtureJson.int(expect["elapsedSeconds"]),
                reading.elapsedSeconds,
            )
            assertEquals(
                "$label heart rate",
                FixtureJson.int(expect["heartRateBpm"]),
                reading.heartRateBpm,
            )
            assertEquals(
                "$label speed",
                FixtureJson.double(expect["speedKph"]),
                reading.speedKph,
                1e-9,
            )
            assertEquals(
                "$label cadence",
                FixtureJson.double(expect["cadenceRpm"]),
                reading.cadenceRpm,
                1e-9,
            )
            assertEquals(
                "$label distance",
                FixtureJson.int(expect["distanceMetres"]),
                reading.distanceMetres,
            )
            assertEquals(
                "$label calories",
                FixtureJson.int(expect["calories"]),
                reading.calories,
            )
            assertEquals(
                "$label power",
                FixtureJson.int(expect["powerWatts"]),
                reading.powerWatts,
            )
            casesChecked++
        }

        assertEquals("every transcribed case must run", 10, casesChecked)
    }

    @Test
    fun `the corpus declares where it came from`() {
        // Provenance is a plan requirement, not decoration: a fixture whose origin is
        // unrecorded cannot be re-derived when a reference changes, and under D25 it is also
        // the licensing record for a family FitBridge cannot test.
        val fixture = load(corpus)

        assertTrue(
            "must name its TMIW source file",
            FixtureJson.string(fixture["source"]).contains(".dart"),
        )
        assertEquals(
            "must pin the reference commit",
            "85f2ee7520705c99ce81db7355df1be7f4b72ecf",
            FixtureJson.string(fixture["source_commit"]),
        )
        assertEquals(
            "must record how it was acquired",
            "transcribed",
            FixtureJson.string(fixture["acquisition"]),
        )
        assertEquals(
            "must name the characteristic it decodes",
            "a026e01d-0a7d-4ab3-97fa-f1500f9feb8b",
            FixtureJson.string(fixture["characteristic"]),
        )
        assertEquals(
            "must name the service, which §10 resolved from QZ",
            "a026ee07-0a7d-4ab3-97fa-f1500f9feb8b",
            FixtureJson.string(fixture["service"]),
        )
    }

    @Test
    fun `every case is a well-formed Chrono frame`() {
        // The corpus is only worth what its packets are. A case that failed the prefix or length
        // gate would make the parity assertions above vacuous by never reaching a comparison.
        val fixture = load(corpus)
        val expectedLength = FixtureJson.int(fixture["frameLength"])
        val prefix = FixtureJson.array(fixture["magicPrefix"]).map(FixtureJson::int)

        for (case in cases()) {
            val packet = packetOf(case)
            assertEquals("frame length", expectedLength, packet.size)
            assertEquals(
                "declared prefix must match the packet",
                prefix,
                packet.take(3).map { it.toInt() and 0xFF },
            )
            assertTrue(PrecorMeasurement.isChronoFrame(packet))
        }
    }

    @Test
    fun `byte 9 is the cadence high octet in every case, which is what resolved V10`() {
        // The V10 argument, made against the provenanced corpus rather than against retyped
        // numbers. QZ's uint8 reading requires byte 9 to be some other field; in all ten
        // captured frames it is exactly (cadence x 10) >> 8, and in eight of them it is
        // non-zero, which no unrelated field would manage.
        var nonZero = 0

        for (case in cases()) {
            val label = FixtureJson.string(case["name"])
            val reading = requireNotNull(PrecorMeasurement.parse(packetOf(case)))
            val expectedHighOctet =
                ((FixtureJson.double(FixtureJson.obj(case["expect"])["cadenceRpm"]) * 10)
                    .toInt() shr 8) and 0xFF

            assertEquals("$label byte 9", expectedHighOctet, reading.cadenceHighOctet)
            if (reading.confirmsCadenceIsUint16()) nonZero++
        }

        assertEquals("eight of ten captured frames carry a non-zero byte 9", 8, nonZero)
    }

    @Test
    fun `the unpublished field tracks calories at a fixed ratio across the corpus`() {
        // Pins the observation behind the cumulative-work-in-kJ reading of bytes 15-16 so a
        // future capture contradicting it fails visibly. Asserts the evidence, not the
        // hypothesis; nothing publishes this field.
        for (case in cases()) {
            val label = FixtureJson.string(case["name"])
            val reading = requireNotNull(PrecorMeasurement.parse(packetOf(case)))
            if (reading.unidentified15to16 == 0) continue

            val ratio = reading.calories.toDouble() / reading.unidentified15to16
            assertTrue("$label ratio $ratio must sit near 1.2", ratio in 1.15..1.25)
        }
    }
}
