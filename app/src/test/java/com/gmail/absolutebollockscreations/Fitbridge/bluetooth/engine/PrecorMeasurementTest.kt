package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Precor Spinner Chrono Power parser, plan §5b and §11.2.
 *
 * ## Fixture provenance
 *
 * The ten vectors below are transcribed verbatim from
 * `test/precor_spinner_chrono_power_test.dart` in TMIW at the pinned commit
 * `85f2ee7520705c99ce81db7355df1be7f4b72ecf` (Apache-2.0), together with the values TMIW
 * asserts for each. They are real captured frames from a Precor Spinner Chrono Power, not
 * constructed examples, which is what makes the byte-9 argument in [PrecorMeasurement] an
 * observation rather than a thought experiment.
 *
 * The expected values are TMIW's own, unedited. Where FitBridge deliberately differs — heart
 * rate zero suppressed at emission, elapsed time not published, bytes 15–16 not published —
 * the difference is asserted in [PrecorEmissionTest] rather than by quietly changing a number
 * here. AGENTS.md: do not edit expected values to make Kotlin tests pass.
 */
class PrecorMeasurementTest {

    private fun frame(vararg v: Int) = ByteArray(v.size) { (v[it] and 0xFF).toByte() }

    /** One TMIW vector and the values TMIW asserts for it. */
    private data class Vector(
        val data: ByteArray,
        val elapsed: Int,
        val heartRate: Int,
        val speed: Double,
        val cadence: Double,
        val distance: Int,
        val calories: Int,
        val power: Int,
    )

    private val corpus = listOf(
        Vector(
            frame(83, 89, 22, 110, 2, 0, 200, 8, 84, 1, 80, 14, 0, 57, 0, 47, 0, 90, 0),
            elapsed = 622, heartRate = 0, speed = 22.48, cadence = 34.0,
            distance = 3664, calories = 57, power = 90,
        ),
        Vector(
            frame(83, 89, 22, 248, 4, 77, 235, 8, 94, 1, 220, 29, 0, 124, 0, 103, 0, 94, 0),
            elapsed = 1272, heartRate = 77, speed = 22.83, cadence = 35.0,
            distance = 7644, calories = 124, power = 94,
        ),
        Vector(
            frame(83, 89, 22, 4, 5, 67, 0, 0, 0, 0, 24, 30, 0, 125, 0, 104, 0, 0, 0),
            elapsed = 1284, heartRate = 67, speed = 0.0, cadence = 0.0,
            distance = 7704, calories = 125, power = 0,
        ),
        Vector(
            frame(83, 89, 22, 57, 5, 0, 0, 0, 0, 0, 59, 31, 0, 129, 0, 108, 0, 0, 0),
            elapsed = 1337, heartRate = 0, speed = 0.0, cadence = 0.0,
            distance = 7995, calories = 129, power = 0,
        ),
        Vector(
            frame(83, 89, 22, 247, 0, 79, 0, 14, 128, 2, 71, 6, 0, 31, 0, 26, 0, 24, 1),
            elapsed = 247, heartRate = 79, speed = 35.84, cadence = 64.0,
            distance = 1607, calories = 31, power = 280,
        ),
        Vector(
            frame(83, 89, 22, 8, 1, 103, 222, 13, 118, 2, 235, 6, 0, 36, 0, 30, 0, 18, 1),
            elapsed = 264, heartRate = 103, speed = 35.5, cadence = 63.0,
            distance = 1771, calories = 36, power = 274,
        ),
        Vector(
            frame(83, 89, 22, 225, 6, 88, 156, 9, 164, 1, 207, 47, 0, 1, 1, 214, 0, 115, 0),
            elapsed = 1761, heartRate = 88, speed = 24.6, cadence = 42.0,
            distance = 12239, calories = 257, power = 115,
        ),
        Vector(
            frame(83, 89, 22, 15, 7, 81, 98, 9, 144, 1, 0, 49, 0, 6, 1, 219, 0, 108, 0),
            elapsed = 1807, heartRate = 81, speed = 24.02, cadence = 40.0,
            distance = 12544, calories = 262, power = 108,
        ),
        Vector(
            frame(83, 89, 22, 132, 8, 68, 134, 11, 152, 3, 93, 58, 0, 51, 1, 0, 1, 179, 0),
            elapsed = 2180, heartRate = 68, speed = 29.5, cadence = 92.0,
            distance = 14941, calories = 307, power = 179,
        ),
        Vector(
            frame(83, 89, 22, 193, 8, 83, 245, 11, 252, 3, 35, 60, 0, 60, 1, 7, 1, 195, 0),
            elapsed = 2241, heartRate = 83, speed = 30.61, cadence = 102.0,
            distance = 15395, calories = 316, power = 195,
        ),
    )

    // ── §11.2 fixture parity ─────────────────────────────────────────────────────

    @Test
    fun `every TMIW vector decodes to TMIW's values`() {
        for ((index, vector) in corpus.withIndex()) {
            val reading = PrecorMeasurement.parse(vector.data)
            assertNotNull("vector $index must be processable", reading)
            requireNotNull(reading)
            assertEquals("vector $index elapsed", vector.elapsed, reading.elapsedSeconds)
            assertEquals("vector $index heart rate", vector.heartRate, reading.heartRateBpm)
            assertEquals("vector $index speed", vector.speed, reading.speedKph, 1e-9)
            assertEquals("vector $index cadence", vector.cadence, reading.cadenceRpm, 1e-9)
            assertEquals("vector $index distance", vector.distance, reading.distanceMetres)
            assertEquals("vector $index calories", vector.calories, reading.calories)
            assertEquals("vector $index power", vector.power, reading.powerWatts)
        }
    }

    // ── Divergence V10: the evidence, asserted rather than described ─────────────

    @Test
    fun `byte 9 is the high octet of cadence in every captured vector`() {
        // This is the whole V10 argument in executable form. QZ reads cadence as byte 8 alone,
        // which requires byte 9 to be something else. In every real frame it is exactly
        // (cadence * 10) >> 8 — it cannot be an unrelated field and hold that relationship ten
        // times, including three distinct non-zero values.
        for ((index, vector) in corpus.withIndex()) {
            val reading = requireNotNull(PrecorMeasurement.parse(vector.data))
            val expectedHighOctet = ((vector.cadence * 10).toInt() shr 8) and 0xFF
            assertEquals(
                "vector $index byte 9 must be the cadence high octet",
                expectedHighOctet,
                reading.cadenceHighOctet,
            )
        }
    }

    @Test
    fun `the corpus contains non-zero high octets, so QZ's uint8 reading is falsified`() {
        val nonZero = corpus.count {
            requireNotNull(PrecorMeasurement.parse(it.data)).confirmsCadenceIsUint16()
        }
        // Eight of ten. The two that do not are the two stationary frames, where cadence is
        // genuinely zero and a zero high octet is the correct value rather than a missing one.
        assertEquals("eight of the ten captured frames carry a non-zero byte 9", 8, nonZero)
    }

    @Test
    fun `the two readings disagree materially above 25 point 5 rpm`() {
        // Not a parser assertion — a statement of what was at stake, kept executable so that
        // anyone revisiting V10 sees the size of the error rather than re-deriving it.
        val vector = corpus.last() // cadence 102 rpm
        val reading = requireNotNull(PrecorMeasurement.parse(vector.data))
        val qzReading = (vector.data[8].toInt() and 0xFF) / 2.0

        assertEquals(102.0, reading.cadenceRpm, 1e-9)
        assertEquals(126.0, qzReading, 1e-9)
        assertTrue("the disagreement is 24 rpm, not a rounding difference", qzReading > 120.0)
    }

    @Test
    fun `a zero high octet is not treated as confirmation`() {
        val stationary = requireNotNull(PrecorMeasurement.parse(corpus[2].data))
        assertEquals(0.0, stationary.cadenceRpm, 1e-9)
        assertFalse(stationary.confirmsCadenceIsUint16())
    }

    // ── §11.3 / §11a adversarial input ───────────────────────────────────────────

    @Test
    fun `a frame of any length other than 19 is rejected`() {
        val valid = corpus.first().data
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
            val corrupted = corpus.first().data.copyOf()
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
        assertTrue(
            "655 km/h must be outside the RIDE speed bound",
            PlausibilityBounds.rangeFor(MetricKind.SPEED, FusionPersona.RIDE)!!
                .contains(reading.speedKph).not(),
        )
        assertTrue(
            "6553 rpm must be outside the RIDE cadence bound",
            PlausibilityBounds.rangeFor(MetricKind.CADENCE, FusionPersona.RIDE)!!
                .contains(reading.cadenceRpm).not(),
        )
        assertTrue(
            "65535 W must be outside the power bound",
            PlausibilityBounds.rangeFor(MetricKind.POWER, FusionPersona.RIDE)!!
                .contains(reading.powerWatts.toDouble()).not(),
        )
    }

    @Test
    fun `every captured vector lies inside its plausibility bounds`() {
        // The complement of the test above, and the more important direction: bounds that
        // reject real captured data would silently delete a working machine's session.
        for ((index, vector) in corpus.withIndex()) {
            val reading = requireNotNull(PrecorMeasurement.parse(vector.data))
            assertTrue(
                "vector $index speed ${reading.speedKph} must be plausible",
                reading.speedKph in PlausibilityBounds.rangeFor(MetricKind.SPEED, FusionPersona.RIDE)!!,
            )
            assertTrue(
                "vector $index cadence ${reading.cadenceRpm} must be plausible",
                reading.cadenceRpm in PlausibilityBounds.rangeFor(MetricKind.CADENCE, FusionPersona.RIDE)!!,
            )
            assertTrue(
                "vector $index power ${reading.powerWatts} must be plausible",
                reading.powerWatts.toDouble() in
                    PlausibilityBounds.rangeFor(MetricKind.POWER, FusionPersona.RIDE)!!,
            )
        }
    }

    // ── Bytes 15–16: observed, never shipped ─────────────────────────────────────

    @Test
    fun `bytes 15 and 16 hold a quantity that tracks calories at a fixed ratio`() {
        // Pinning the observation that made this field interesting, so that a future capture
        // contradicting it is a visible test failure rather than a forgotten paragraph. This
        // asserts the evidence for the cumulative-work hypothesis; it does not assert the
        // hypothesis, and nothing publishes the field.
        for ((index, vector) in corpus.withIndex()) {
            val reading = requireNotNull(PrecorMeasurement.parse(vector.data))
            if (reading.unidentified15to16 == 0) continue
            val ratio = reading.calories.toDouble() / reading.unidentified15to16
            assertTrue(
                "vector $index ratio $ratio must sit near 1.2",
                ratio in 1.15..1.25,
            )
        }
    }
}
