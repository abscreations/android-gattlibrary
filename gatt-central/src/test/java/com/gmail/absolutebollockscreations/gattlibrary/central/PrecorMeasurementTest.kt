package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FitBridge-authored adversarial coverage for the Precor Chrono parser — plan §5.4, §11a, §11b.
 *
 * ## The division of labour with [PrecorFixtureParityTest]
 *
 * Parity against TMIW's captured vectors lives there, driven by
 * `fixtures/tmiw/precor_spinner_chrono.json` with its provenance header. **This file holds only
 * what no reference project could supply**: truncation, corrupted prefixes, saturated `uint16`
 * values and the plausibility bounds that catch them.
 *
 * §5.1 is explicit that these are different kinds of evidence and must not be mixed. A
 * reference project tests the packets its hardware sent; it never sends a 4-octet frame or
 * `0xFFFF` in every field. Putting FitBridge-authored cases in a corpus whose header reads
 * `"acquisition": "transcribed"` would make that header untrue, so they stay here.
 *
 * Under D25 this is not trailing coverage. Nobody involved has a Chrono, so these cases and the
 * §11b bounds are the only place a decoding error can surface at all.
 *
 * **The ten captured vectors were removed from this file when the corpus was created.** They had
 * been typed into Kotlin and were then duplicated by the corpus, which is the drift hazard §5
 * exists to prevent: two copies of the same numbers, one provenanced and one not, with nothing
 * keeping them equal. The valid frame used as a base for corruption below is loaded from the
 * corpus for the same reason.
 */
class PrecorMeasurementTest {

    private fun frame(vararg v: Int) = ByteArray(v.size) { (v[it] and 0xFF).toByte() }

    /** A real captured frame, taken from the corpus rather than restated here. */
    private fun validFrame(): ByteArray {
        val stream = javaClass.classLoader
            ?.getResourceAsStream("fixtures/tmiw/precor_spinner_chrono.json")
            ?: error("corpus missing")
        val fixture = FixtureJson.obj(FixtureJson.parse(stream.bufferedReader().use { it.readText() }))
        val first = FixtureJson.obj(FixtureJson.array(fixture["cases"]).first())
        return FixtureJson.array(first["packet"])
            .map { FixtureJson.int(it).toByte() }
            .toByteArray()
    }

    // ── §11.3 / §11a adversarial input ───────────────────────────────────────────

    @Test
    fun `captured auxiliary notification has an exact defensive predicate`() {
        val auxiliary = PrecorMeasurement.auxiliaryFrame()
        assertTrue(PrecorMeasurement.isAuxiliaryFrame(auxiliary))
        assertNull(PrecorMeasurement.parse(auxiliary))

        auxiliary[2] = 0x01
        assertFalse(PrecorMeasurement.isAuxiliaryFrame(auxiliary))
        assertFalse(PrecorMeasurement.isAuxiliaryFrame(byteArrayOf(0x24, 0x08)))
        assertArrayEquals(byteArrayOf(0x24, 0x08, 0x00), PrecorMeasurement.auxiliaryFrame())
    }

    @Test
    fun `a frame of any length other than 19 is rejected`() {
        val valid = validFrame()
        for (length in 0..25) {
            if (length == PrecorMeasurement.FRAME_LENGTH) continue
            val truncated = ByteArray(length) { index ->
                if (index < valid.size) valid[index] else 0
            }
            assertNull(
                "a $length-octet frame is not a Chrono frame",
                PrecorMeasurement.parse(truncated),
            )
        }
    }

    @Test
    fun `a 19-octet frame with a wrong prefix is rejected`() {
        // The negative test §5b's exit criteria name explicitly. Each prefix octet is corrupted
        // independently: a check that only ever tests the first byte passes a broken parser.
        for (position in 0..2) {
            val corrupted = validFrame()
            corrupted[position] = (corrupted[position].toInt() + 1).toByte()
            assertNull(
                "corrupting prefix octet $position must reject the frame",
                PrecorMeasurement.parse(corrupted),
            )
            assertFalse(PrecorMeasurement.isChronoFrame(corrupted))
        }
    }

    @Test
    fun `an all-zero 19-octet frame is rejected and an all-zero payload is not`() {
        assertNull(PrecorMeasurement.parse(ByteArray(19)))

        // TMIW's own flag test: prefix present, every other octet zero. Processable, and every
        // field legitimately zero — a machine sitting idle before anyone mounts it.
        val idle = frame(83, 89, 22, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        val reading = requireNotNull(PrecorMeasurement.parse(idle))
        assertEquals(0, reading.elapsedSeconds)
        assertEquals(0.0, reading.speedKph, 1e-9)
        assertEquals(0.0, reading.cadenceRpm, 1e-9)
        assertEquals(0, reading.distanceMetres)
        assertEquals(0, reading.powerWatts)
        assertFalse("a stationary machine's byte 9 is legitimately zero", reading.confirmsCadenceIsUint16())
    }

    @Test
    fun `every field reads its maximum unsigned value without sign extension`() {
        // The high-bit case AGENTS.md requires. A Kotlin Byte is signed, so 0xFF at any offset
        // is the shape that produces -1 where 255 was meant, and 0xFFFF the shape that produces
        // -1 where 65535 was meant.
        val saturated = frame(
            83, 89, 22,
            0xFF, 0xFF,        // elapsed
            0xFF,              // heart rate
            0xFF, 0xFF,        // speed
            0xFF, 0xFF,        // cadence
            0xFF, 0xFF, 0xFF,  // distance
            0xFF, 0xFF,        // calories
            0xFF, 0xFF,        // unidentified
            0xFF, 0xFF,        // power
        )
        val reading = requireNotNull(PrecorMeasurement.parse(saturated))

        assertEquals(65535, reading.elapsedSeconds)
        assertEquals(255, reading.heartRateBpm)
        assertEquals(655.35, reading.speedKph, 1e-9)
        assertEquals(6553.5, reading.cadenceRpm, 1e-9)
        assertEquals(16777215, reading.distanceMetres)
        assertEquals(65535, reading.calories)
        assertEquals(65535, reading.unidentified15to16)
        assertEquals(65535, reading.powerWatts)

        // Decoded literally, exactly as V4 established for RSC: the wire says what it says, and
        // §11b bounds are what stop it reaching a session. There is no vendor document defining
        // a not-available sentinel for this family, so inventing one would be the V4 mistake.
        assertFalse(
            "655 km/h must be outside the RIDE speed bound",
            reading.speedKph in PlausibilityBounds.rangeFor(MetricKind.SPEED, FusionPersona.RIDE)!!,
        )
        assertFalse(
            "6553 rpm must be outside the RIDE cadence bound",
            reading.cadenceRpm in PlausibilityBounds.rangeFor(MetricKind.CADENCE, FusionPersona.RIDE)!!,
        )
        assertFalse(
            "65535 W must be outside the power bound",
            reading.powerWatts.toDouble() in
                PlausibilityBounds.rangeFor(MetricKind.POWER, FusionPersona.RIDE)!!,
        )
    }

    @Test
    fun `the two V10 readings disagree materially above 25 point 5 rpm`() {
        // Not a parser assertion — a statement of what was at stake in V10, kept executable so
        // that anyone revisiting it sees the size of the error rather than re-deriving it.
        // Byte 8 = 252 with byte 9 = 3 is the highest-cadence captured frame.
        val highCadence = frame(83, 89, 22, 193, 8, 83, 245, 11, 252, 3, 35, 60, 0, 60, 1, 7, 1, 195, 0)
        val reading = requireNotNull(PrecorMeasurement.parse(highCadence))
        val qzReading = (highCadence[8].toInt() and 0xFF) / 2.0

        assertEquals("FitBridge and TMIW", 102.0, reading.cadenceRpm, 1e-9)
        assertEquals("QZ's chronobike", 126.0, qzReading, 1e-9)
        assertTrue("a 24 rpm disagreement is not a rounding difference", qzReading - reading.cadenceRpm > 20.0)
    }

    // ── §11b: bounds must not reject real data ───────────────────────────────────

    @Test
    fun `every captured corpus frame lies inside its plausibility bounds`() {
        // The complement of the saturated case, and the more important direction: bounds that
        // reject real captured data would silently delete a working machine's session. Driven
        // from the corpus so it covers whatever the corpus contains.
        val stream = javaClass.classLoader
            ?.getResourceAsStream("fixtures/tmiw/precor_spinner_chrono.json")
            ?: error("corpus missing")
        val fixture = FixtureJson.obj(FixtureJson.parse(stream.bufferedReader().use { it.readText() }))

        for (raw in FixtureJson.array(fixture["cases"])) {
            val case = FixtureJson.obj(raw)
            val label = FixtureJson.string(case["name"])
            val packet = FixtureJson.array(case["packet"])
                .map { FixtureJson.int(it).toByte() }
                .toByteArray()
            val reading = requireNotNull(PrecorMeasurement.parse(packet))

            assertTrue(
                "$label speed ${reading.speedKph} must be plausible",
                reading.speedKph in PlausibilityBounds.rangeFor(MetricKind.SPEED, FusionPersona.RIDE)!!,
            )
            assertTrue(
                "$label cadence ${reading.cadenceRpm} must be plausible",
                reading.cadenceRpm in PlausibilityBounds.rangeFor(MetricKind.CADENCE, FusionPersona.RIDE)!!,
            )
            assertTrue(
                "$label power ${reading.powerWatts} must be plausible",
                reading.powerWatts.toDouble() in
                    PlausibilityBounds.rangeFor(MetricKind.POWER, FusionPersona.RIDE)!!,
            )
        }
    }
}
