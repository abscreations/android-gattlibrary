package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan Phase 1 exit criterion 1: fixture parity on all four TMIW RSC corpora.
 *
 * The corpora are read from `gatt-fixtures/src/main/resources/fixtures/tmiw/`, not
 * restated here. A fixture nobody loads is a decorative file, and the point of
 * D4 is that expectations come from the reference implementation rather than
 * from whoever wrote the Kotlin.
 *
 * ### Provenance
 *
 * Transcribed from TMIW at `85f2ee75`. Plan §5.1 classifies TMIW as
 * generate-only, on the grounds that its tests build packets with `Random()` and
 * assert against descriptor internals. **That is not true of these four**: all
 * are literal `TestPair` lists with no `Random()` call between them, so the
 * expectations transcribe directly. §5.1 has been corrected to say the strategy
 * is per-corpus rather than per-project.
 *
 * Speed, cadence and distance expectations reproduce TMIW's own expressions,
 * which were checked against its `maxUint8 = 256` and `ms2kmh = 3.6` constants
 * rather than assumed. Stride length is FitBridge-authored: TMIW advances past
 * the field without decoding it.
 */
class RscFixtureParityTest {

    private val corpora = listOf(
        "rsc_stryd",
        "rsc_npe_runn",
        "rsc_technogym_run",
        "rsc_technogym_myrun",
    )

    private fun load(name: String): Map<String, Any?> {
        val stream = javaClass.classLoader?.getResourceAsStream("fixtures/tmiw/$name.json")
        assertNotNull("fixture $name.json is missing from test resources", stream)
        val text = stream!!.bufferedReader().use { it.readText() }
        return FixtureJson.obj(FixtureJson.parse(text))
    }

    private fun packetOf(case: Map<String, Any?>): ByteArray =
        FixtureJson.array(case["packet"]).map { FixtureJson.int(it).toByte() }.toByteArray()

    @Test
    fun `every corpus decodes exactly as TrackMyIndoorWorkout does`() {
        var casesChecked = 0

        for (name in corpora) {
            val fixture = load(name)
            val family = FixtureJson.string(fixture["family"])

            for (raw in FixtureJson.array(fixture["cases"])) {
                val case = FixtureJson.obj(raw)
                val label = "$family/${FixtureJson.string(case["name"])}"
                val expect = FixtureJson.obj(case["expect"])

                val reading = RscMeasurement.parse(packetOf(case))
                assertNotNull("$label failed to parse", reading)
                reading!!

                assertEquals(
                    "$label speed",
                    FixtureJson.double(expect["speedKph"]),
                    reading.speedKph,
                    1e-9,
                )
                assertEquals(
                    "$label cadence",
                    FixtureJson.int(expect["cadenceStepsPerMinute"]),
                    reading.cadenceStepsPerMinute,
                )
                assertEquals(
                    "$label walking flag",
                    expect["walking"] as Boolean,
                    reading.walking,
                )

                val stride = expect["strideLengthMetres"]
                if (stride == null) {
                    assertNull("$label must report no stride length", reading.strideLengthMetres)
                } else {
                    assertEquals(
                        "$label stride length",
                        FixtureJson.double(stride),
                        reading.strideLengthMetres!!,
                        1e-9,
                    )
                }

                val distance = expect["totalDistanceMetres"]
                if (distance == null) {
                    assertNull("$label must report no distance", reading.totalDistanceMetres)
                } else {
                    assertEquals(
                        "$label total distance",
                        FixtureJson.double(distance),
                        reading.totalDistanceMetres!!,
                        1e-9,
                    )
                }
                casesChecked++
            }
        }

        assertEquals("every transcribed case must run", 5, casesChecked)
    }

    @Test
    fun `each corpus declares where it came from`() {
        // Provenance is a plan requirement, not decoration: a fixture whose
        // origin is unrecorded cannot be re-derived when a reference changes.
        for (name in corpora) {
            val fixture = load(name)
            assertTrue(
                "$name must name its TMIW source file",
                FixtureJson.string(fixture["source"]).contains(".dart"),
            )
            assertEquals(
                "$name must pin the reference commit",
                "85f2ee7520705c99ce81db7355df1be7f4b72ecf",
                FixtureJson.string(fixture["source_commit"]),
            )
            assertEquals(
                "$name must record how it was acquired",
                "transcribed",
                FixtureJson.string(fixture["acquisition"]),
            )
            assertEquals(
                "$name must name the characteristic it decodes",
                "00002a53-0000-1000-8000-00805f9b34fb",
                FixtureJson.string(fixture["characteristic"]),
            )
        }
    }

    @Test
    fun `the corpora between them cover every optional-field combination`() {
        // Parity is only worth as much as the corpus's coverage. Flags 0x00,
        // 0x02 and 0x03 are present; 0x01 - stride without distance - is not
        // produced by any of the four devices and is covered by the
        // FitBridge-authored adversarial vectors instead.
        val flagsSeen = corpora
            .flatMap { FixtureJson.array(load(it)["cases"]) }
            .map { packetOf(FixtureJson.obj(it))[0].toInt() and 0x03 }
            .toSet()

        assertEquals(setOf(0x00, 0x02, 0x03), flagsSeen)
    }
}
