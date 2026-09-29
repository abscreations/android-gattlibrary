package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FitBridge-authored adversarial vectors for `0x2A53` (plan §11a).
 *
 * The four TMIW corpora cover what four real devices actually sent. They do not
 * cover truncation, the unused flag combination, gait changes, or boundary
 * values, because that hardware never produced them - which is exactly why §11a
 * exists. For an untestable family these are the only place a decoding error can
 * surface before a user meets it.
 */
class RscMeasurementTest {

    private fun bytes(vararg v: Int): ByteArray = v.map { it.toByte() }.toByteArray()

    // ── layout ───────────────────────────────────────────────────────────────

    @Test
    fun `stride length without total distance is decoded`() {
        // Flags 0x01. No device in the corpus emits this, and it is the one
        // combination where a wrong offset would not be caught by the others:
        // stride is present but distance is not, so an implementation that
        // conflated the two flags would read distance out of nothing.
        val reading = RscMeasurement.parse(bytes(0x01, 0x00, 0x01, 90, 0x84, 0x03))!!

        assertEquals(3.6, reading.speedKph, 1e-9)      // 256/256 m/s
        assertEquals(90, reading.cadenceStepsPerMinute)
        assertEquals(9.0, reading.strideLengthMetres!!, 1e-9)  // 900/100
        assertNull("distance was not advertised", reading.totalDistanceMetres)
    }

    @Test
    fun `distance sits four octets earlier when stride length is absent`() {
        // The bug this pins: treating the distance offset as fixed. With stride
        // present distance starts at 6; without it, at 4.
        val withStride = RscMeasurement.parse(bytes(0x03, 0, 1, 80, 100, 0, 0xE8, 0x03, 0, 0))!!
        val withoutStride = RscMeasurement.parse(bytes(0x02, 0, 1, 80, 0xE8, 0x03, 0, 0))!!

        assertEquals(100.0, withStride.totalDistanceMetres!!, 1e-9)
        assertEquals(100.0, withoutStride.totalDistanceMetres!!, 1e-9)
    }

    // ── gait ─────────────────────────────────────────────────────────────────

    @Test
    fun `the walking bit is reported but does not move any field`() {
        // Masking with 0x03 before deriving offsets is the whole point: a runner
        // slowing to a walk must not appear to change the packet shape.
        val running = RscMeasurement.parse(bytes(0x03, 0, 1, 80, 100, 0, 0xE8, 0x03, 0, 0))!!
        val walking = RscMeasurement.parse(bytes(0x07, 0, 1, 80, 100, 0, 0xE8, 0x03, 0, 0))!!

        assertFalse(running.walking)
        assertTrue(walking.walking)
        assertEquals(running.speedKph, walking.speedKph, 1e-9)
        assertEquals(running.strideLengthMetres!!, walking.strideLengthMetres!!, 1e-9)
        assertEquals(running.totalDistanceMetres!!, walking.totalDistanceMetres!!, 1e-9)
    }

    @Test
    fun `reserved flag bits do not disturb the layout`() {
        // Stryd sets bit 7. Anything above bit 2 is reserved and must be ignored
        // rather than folded into the offset calculation.
        val plain = RscMeasurement.parse(bytes(0x00, 0x91, 0x01, 187))!!
        val reserved = RscMeasurement.parse(bytes(0xF8, 0x91, 0x01, 187))!!

        assertEquals(plain.speedKph, reserved.speedKph, 1e-9)
        assertEquals(plain.cadenceStepsPerMinute, reserved.cadenceStepsPerMinute)
    }

    // ── truncation ───────────────────────────────────────────────────────────

    @Test
    fun `a packet too short for the mandatory fields yields nothing`() {
        for (length in 0..3) {
            assertNull(
                "a $length-octet packet cannot carry speed and cadence",
                RscMeasurement.parse(ByteArray(length)),
            )
        }
        assertEquals(4, RscMeasurement.MINIMUM_LENGTH)
    }

    @Test
    fun `an advertised field that was truncated away reads as absent, not as zero`() {
        // A device that sets a flag and then sends a short packet is broken, but
        // the difference between "no stride reading" and "a stride of zero
        // metres" is the difference between a gap and a lie.
        val strideCut = RscMeasurement.parse(bytes(0x01, 0, 1, 80, 100))!!
        assertNull(strideCut.strideLengthMetres)

        val distanceCut = RscMeasurement.parse(bytes(0x02, 0, 1, 80, 0xE8, 0x03))!!
        assertNull(distanceCut.totalDistanceMetres)

        // The mandatory fields still survive a truncated tail.
        assertEquals(80, strideCut.cadenceStepsPerMinute)
        assertEquals(80, distanceCut.cadenceStepsPerMinute)
    }

    @Test
    fun `trailing bytes beyond the advertised fields are ignored`() {
        // The Stryd corpus packet is 13 octets carrying 4 octets of meaning.
        val padded = RscMeasurement.parse(
            bytes(0x00, 0x07, 0, 0, 0, 0, 0xFF, 0xFF, 0xFF, 0, 0, 0, 0),
        )!!
        assertEquals(0.0984375, padded.speedKph, 1e-9)
        assertEquals(0, padded.cadenceStepsPerMinute)
    }

    // ── boundaries ───────────────────────────────────────────────────────────

    @Test
    fun `the widest legal values do not overflow`() {
        // Distance is uint32 in tenths: 0xFFFFFFFF is 429,496,729.5 m. Reading it
        // into an Int would wrap negative.
        val reading = RscMeasurement.parse(
            bytes(0x03, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF),
        )!!

        assertEquals(921.5859375, reading.speedKph, 1e-9)   // 65535/256*3.6
        assertEquals(255, reading.cadenceStepsPerMinute)
        assertEquals(655.35, reading.strideLengthMetres!!, 1e-9)
        assertEquals(429_496_729.5, reading.totalDistanceMetres!!, 1e-6)
        assertTrue("distance must not wrap negative", reading.totalDistanceMetres > 0)
    }

    @Test
    fun `an all-zero packet is a legitimate standing-still reading`() {
        val reading = RscMeasurement.parse(bytes(0x00, 0, 0, 0))!!
        assertEquals(0.0, reading.speedKph, 1e-9)
        assertEquals(0, reading.cadenceStepsPerMinute)
    }

    // ── divergence from TMIW, recorded deliberately ──────────────────────────

    @Test
    fun `no not-available sentinel is applied, unlike TMIW`() {
        // TMIW returns null for 0xFFFF speed and 0xFFFFFFFF distance because it
        // shares descriptor classes with its FTMS decoders. 0x2A53 defines no
        // such sentinels, so FitBridge decodes them literally - see the
        // divergence register. This test exists so the choice is visible and
        // cannot be reversed by accident.
        val reading = RscMeasurement.parse(
            bytes(0x02, 0xFF, 0xFF, 100, 0xFF, 0xFF, 0xFF, 0xFF),
        )!!

        assertEquals("0xFFFF is decoded, not treated as absent", 921.5859375, reading.speedKph, 1e-9)
        assertEquals(429_496_729.5, reading.totalDistanceMetres!!, 1e-6)
    }
}
