package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Plan §5.4 adversarial corpus for the FTMS parsers, and the only coverage the stepper and
 * stair climber personas have.
 *
 * ## Why these are authored rather than generated
 *
 * The §5.5 parity corpus proves FitBridge agrees with TrackMyIndoorWorkout on real traffic.
 * It cannot prove anything about traffic neither project has seen, and TMIW's corpus contains
 * **no byte vectors at all** for the step climber persona. §11a is explicit that for a family
 * FitBridge cannot test on hardware, adversarial fixtures are the core of the phase rather than
 * trailing coverage — they are the only place a decoding error can surface without the machine.
 *
 * Every expectation here is derived from the Bluetooth SIG characteristic definitions for
 * `0x2AD2`, `0x2ACD`, `0x2ACF` and `0x2AD0`, checked field by field against the parser. Two
 * results of that check are worth recording:
 *
 *  - Stepper (`0x2ACF`) and climber (`0x2AD0`) flag bits and field widths are correct, including
 *    the mandatory block difference — the stepper carries Floors **and** Step Count, the climber
 *    Floors alone.
 *  - Elevation gain resolution differs by persona and FitBridge already distinguishes it:
 *    metres exactly for stepper and climber, tenths of a metre for the treadmill. Reading one
 *    with the other's scale is a silent ten-times error, so it is pinned below.
 *
 * ## Bounds are part of the expected behaviour
 *
 * Samples are read through [MetricFusion], so [PlausibilityBounds] applies. Where a case expects
 * absence, the comment says whether the parser declined to decode or fusion rejected the value —
 * they are different failures and a test that conflates them would pass for the wrong reason.
 */
class FtmsAdversarialParserTest {

    private data class Harness(val fusion: MetricFusion, val emitter: MetricEmitter)

    private fun harness(characteristic: UUID, persona: FusionPersona): Harness {
        val fusion = MetricFusion(BtEngineState()).also { it.setPersona(persona) }
        return Harness(
            fusion,
            MetricEmitter(
                fusion = fusion,
                sourceId = MetricSourceId(
                    ConnectionId("adversarial", 1L),
                    MetricProtocol.FTMS,
                    characteristic,
                ),
                sourceClass = MetricSourceClass.FTMS,
                arrivalMs = 9_000L,
            ),
        )
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { (v[it] and 0xFF).toByte() }
    private fun MetricFusion.valueOf(m: MetricKind): Double? = latestSample(m)?.value

    private fun bike(persona: FusionPersona = FusionPersona.RIDE) =
        harness(BluetoothIds.UUID_BIKING_DATA, persona)

    /**
     * Indoor Bike, every optional field present. Flags `0x1FFE`: bit 0 clear (speed present) and
     * bits 1..12 set. Thirty octets, which is the characteristic's maximum extent.
     */
    private val allFieldsBike = bytes(
        0xFE, 0x1F,             // flags
        0xD2, 0x04,             // speed          1234 -> 12.34 km/h
        0xB0, 0x04,             // average speed  1200 -> 12.00 km/h
        0xA0, 0x00,             // cadence         160 -> 80 rpm (0.5 resolution)
        0x96, 0x00,             // average cadence 150 -> 75 rpm
        0xA0, 0x86, 0x01,       // distance     100000 m (uint24)
        0x0C, 0x00,             // resistance       12
        0xFA, 0x00,             // power           250 W
        0xF0, 0x00,             // average power   240 W
        0xF4, 0x01,             // total energy    500 kcal
        0x58, 0x02,             // energy/hour     600 kcal
        0x0A,                   // energy/minute    10 kcal
        0x91,                   // heart rate      145 bpm
        0x69,                   // MET             105 -> 10.5
        0x10, 0x0E,             // elapsed        3600 s
        0x58, 0x02,             // remaining       600 s
    )

    // ── All-optional-fields packet ───────────────────────────────────────────────

    @Test
    fun `a maximal indoor bike packet decodes every field at its specified scale`() {
        val h = bike()
        BtEngineFtmsParsers.doBike(h.emitter, allFieldsBike)

        assertEquals(30, allFieldsBike.size)
        assertEquals(12.34, h.fusion.valueOf(MetricKind.SPEED)!!, 1e-9)
        assertEquals(12.00, h.fusion.valueOf(MetricKind.AVERAGE_SPEED)!!, 1e-9)
        assertEquals(80.0, h.fusion.valueOf(MetricKind.CADENCE)!!, 1e-9)
        assertEquals(75.0, h.fusion.valueOf(MetricKind.AVERAGE_CADENCE)!!, 1e-9)
        assertEquals(100_000.0, h.fusion.valueOf(MetricKind.DISTANCE)!!, 1e-9)
        assertEquals(12.0, h.fusion.valueOf(MetricKind.RESISTANCE)!!, 1e-9)
        assertEquals(250.0, h.fusion.valueOf(MetricKind.POWER)!!, 1e-9)
        assertEquals(240.0, h.fusion.valueOf(MetricKind.AVERAGE_POWER)!!, 1e-9)
        assertEquals(500.0, h.fusion.valueOf(MetricKind.ENERGY)!!, 1e-9)
        assertEquals(600.0, h.fusion.valueOf(MetricKind.ENERGY_RATE)!!, 1e-9)
        assertEquals(145.0, h.fusion.valueOf(MetricKind.HEART_RATE)!!, 1e-9)
        assertEquals(10.5, h.fusion.valueOf(MetricKind.METABOLIC_EQUIVALENT)!!, 1e-9)
        assertEquals(3600.0, h.fusion.valueOf(MetricKind.ELAPSED)!!, 1e-9)
    }

    // ── Trailing vendor octets ───────────────────────────────────────────────────

    @Test
    fun `trailing vendor octets after a complete frame are ignored`() {
        val h = bike()
        BtEngineFtmsParsers.doBike(h.emitter, allFieldsBike + bytes(0xAA, 0xBB, 0xCC))

        // Identical to the un-padded frame: the walk is driven by flags, not by buffer length.
        assertEquals(12.34, h.fusion.valueOf(MetricKind.SPEED)!!, 1e-9)
        assertEquals(3600.0, h.fusion.valueOf(MetricKind.ELAPSED)!!, 1e-9)
        assertEquals(500.0, h.fusion.valueOf(MetricKind.ENERGY)!!, 1e-9)
    }

    // ── Truncation at every optional boundary ────────────────────────────────────

    /**
     * Truncate the maximal frame to every length from 0 to 29 and parse each one.
     *
     * Two properties, both of which D38 is what makes true: nothing throws, and no field is
     * decoded from octets the packet does not contain. Speed is the probe — it occupies octets
     * 2..3, so it must appear at length 4 and never below it.
     */
    @Test
    fun `every truncation of a maximal frame is bounded and decodes no invented field`() {
        for (length in 0 until allFieldsBike.size) {
            val h = bike()
            val truncated = allFieldsBike.copyOf(length)

            BtEngineFtmsParsers.doBike(h.emitter, truncated)

            val speed = h.fusion.valueOf(MetricKind.SPEED)
            if (length >= 4) {
                assertEquals("length $length should decode speed", 12.34, speed!!, 1e-9)
            } else {
                assertNull("length $length cannot contain a complete speed field", speed)
            }
            // Elapsed sits at octets 26..27; nothing shorter may produce it.
            if (length < 28) {
                assertNull(
                    "length $length cannot contain elapsed time",
                    h.fusion.valueOf(MetricKind.ELAPSED),
                )
            }
        }
    }

    // ── Reserved flag bits ───────────────────────────────────────────────────────

    @Test
    fun `reserved flag bits do not shift the layout`() {
        val h = bike()
        // Bits 13..15 are Reserved for Future Use in 0x2AD2. Setting them must change nothing:
        // they carry no field, so a parser that treated unknown bits as data would desync.
        val reserved = allFieldsBike.copyOf()
        reserved[1] = (reserved[1].toInt() or 0xE0).toByte()

        BtEngineFtmsParsers.doBike(h.emitter, reserved)

        assertEquals(12.34, h.fusion.valueOf(MetricKind.SPEED)!!, 1e-9)
        assertEquals(3600.0, h.fusion.valueOf(MetricKind.ELAPSED)!!, 1e-9)
    }

    // ── Signed fields with the high bit set ──────────────────────────────────────

    @Test
    fun `sint16 resistance decodes negative and power is bounded rather than misread`() {
        val h = bike()
        // Flags 0x0061: bit 0 set (no speed), resistance (bit 5) and power (bit 6).
        // Resistance -10 = 0xFFF6, power -50 = 0xFFCE.
        BtEngineFtmsParsers.doBike(h.emitter, bytes(0x61, 0x00, 0xF6, 0xFF, 0xCE, 0xFF))

        // Resistance is a vendor level with no shared meaning and is deliberately unbounded,
        // so a negative level survives as decoded.
        assertEquals(-10.0, h.fusion.valueOf(MetricKind.RESISTANCE)!!, 1e-9)

        // Power decodes as -50 and is then rejected by the plausibility bound, not by the
        // parser. Decoding stays truthful; publication does not. Same resolution as CPS V6.
        assertNull(h.fusion.valueOf(MetricKind.POWER))
        assertTrue(
            "the bound, not the parser, is what excludes negative power",
            !PlausibilityBounds.isPlausible(MetricKind.POWER, FusionPersona.RIDE, -50.0),
        )
    }

    @Test
    fun `treadmill inclination decodes a negative grade`() {
        val h = harness(BluetoothIds.UUID_TREADMILL_DATA, FusionPersona.RUN)
        // Flags 0x0009: bit 0 set (no speed), inclination + ramp (bit 3).
        // Inclination -5.0 % = -50 = 0xFFCE, ramp -2.5 deg = -25 = 0xFFE7.
        BtEngineFtmsParsers.doTreadmill(h.emitter, bytes(0x09, 0x00, 0xCE, 0xFF, 0xE7, 0xFF))

        assertEquals(-5.0, h.fusion.valueOf(MetricKind.INCLINATION)!!, 1e-9)
        assertEquals(-2.5, h.fusion.valueOf(MetricKind.RAMP_ANGLE)!!, 1e-9)
    }

    // ── Sentinels (plan §12.1 V8) ────────────────────────────────────────────────

    @Test
    fun `the unavailable-energy sentinel is honoured on every persona that carries it`() {
        // Bike, flags 0x0101 = expended energy, speed suppressed. Bit 0 must be set: it is
        // More Data, and leaving it clear makes speed present, which shifts the energy block
        // and would let this pass down the truncated-block path instead of the sentinel path.
        val b = bike()
        BtEngineFtmsParsers.doBike(b.emitter, bytes(0x01, 0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF))
        assertNull(b.fusion.valueOf(MetricKind.ENERGY))

        // Treadmill, flags 0x0081 = expended energy only, speed suppressed.
        val t = harness(BluetoothIds.UUID_TREADMILL_DATA, FusionPersona.RUN)
        BtEngineFtmsParsers.doTreadmill(t.emitter, bytes(0x81, 0x00, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF))
        assertNull(t.fusion.valueOf(MetricKind.ENERGY))

        // Climber, flags 0x0021 = expended energy only, floors suppressed.
        val c = harness(BluetoothIds.UUID_STAIR_CLIMBER_DATA, FusionPersona.RUN)
        BtEngineFtmsParsers.doClimber(c.emitter, bytes(0x21, 0x00, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF))
        assertNull(c.fusion.valueOf(MetricKind.ENERGY))
    }

    /**
     * `0x7FFF` is "Data Not Available" for the sint16 fields: Inclination, Ramp Angle Setting,
     * Force on Belt and Power Output (plan §12.1 V9).
     *
     * Each shares a flag bit with a partner — inclination with ramp angle, force with power —
     * so a machine that measures one and not the other cannot signal absence by clearing a
     * flag. The pinned True treadmill vector sends `0x7FFF` for both ramp angle and force on
     * belt, so this is ordinary traffic rather than an edge case.
     *
     * Bounds would reject 3276.7 % and 32767 N anyway, but rejection is the wrong mechanism:
     * it arms a capture trigger and reads as a decoding anomaly, where a declared absence is
     * normal. The partner field must still decode.
     */
    @Test
    fun `the sint16 unavailable sentinel is honoured per field, not per flag`() {
        // Flags 0x0009: bit 0 set (no speed), inclination + ramp angle (bit 3).
        // Inclination 7.5 % = 75; ramp angle unavailable.
        val a = harness(BluetoothIds.UUID_TREADMILL_DATA, FusionPersona.RUN)
        BtEngineFtmsParsers.doTreadmill(a.emitter, bytes(0x09, 0x00, 0x4B, 0x00, 0xFF, 0x7F))
        assertEquals(7.5, a.fusion.valueOf(MetricKind.INCLINATION)!!, 1e-9)
        assertNull("ramp angle is 0x7FFF", a.fusion.valueOf(MetricKind.RAMP_ANGLE))

        // The other way round: inclination unavailable, ramp angle 3.0 degrees = 30.
        val b = harness(BluetoothIds.UUID_TREADMILL_DATA, FusionPersona.RUN)
        BtEngineFtmsParsers.doTreadmill(b.emitter, bytes(0x09, 0x00, 0xFF, 0x7F, 0x1E, 0x00))
        assertNull("inclination is 0x7FFF", b.fusion.valueOf(MetricKind.INCLINATION))
        assertEquals(3.0, b.fusion.valueOf(MetricKind.RAMP_ANGLE)!!, 1e-9)

        // Force on belt unavailable, power output 180 W. Flags 0x1001: no speed, bit 12.
        val c = harness(BluetoothIds.UUID_TREADMILL_DATA, FusionPersona.RUN)
        BtEngineFtmsParsers.doTreadmill(c.emitter, bytes(0x01, 0x10, 0xFF, 0x7F, 0xB4, 0x00))
        assertNull("force on belt is 0x7FFF", c.fusion.valueOf(MetricKind.FORCE_ON_BELT))
        assertEquals(180.0, c.fusion.valueOf(MetricKind.POWER)!!, 1e-9)

        // 32766 is a real reading, one below the sentinel, and must survive decoding. The
        // force bound then excludes it from publication — decoding and publication are
        // different questions and only the first is being tested here.
        val d = harness(BluetoothIds.UUID_TREADMILL_DATA, FusionPersona.RUN)
        BtEngineFtmsParsers.doTreadmill(d.emitter, bytes(0x01, 0x10, 0xFE, 0x7F, 0x64, 0x00))
        assertEquals(100.0, d.fusion.valueOf(MetricKind.POWER)!!, 1e-9)
        assertTrue(
            "32766 N is out of bounds but is not the sentinel",
            !PlausibilityBounds.isPlausible(MetricKind.FORCE_ON_BELT, FusionPersona.RUN, 32766.0),
        )
    }

    /** The cross trainer carries the same inclination pair on `0x2ACE`. */
    @Test
    fun `the elliptical honours the sint16 sentinel on its inclination pair`() {
        val h = harness(BluetoothIds.UUID_ELLIPTICAL_DATA, FusionPersona.RUN)
        // Three-octet flags 0x000041: bit 0 set (no speed), inclination pair (bit 6).
        BtEngineFtmsParsers.doElliptical(h.emitter, bytes(0x41, 0x00, 0x00, 0xFF, 0x7F, 0x1E, 0x00))

        assertNull(h.fusion.valueOf(MetricKind.INCLINATION))
        assertEquals(3.0, h.fusion.valueOf(MetricKind.RAMP_ANGLE)!!, 1e-9)
    }

    @Test
    fun `elevation unavailable markers are dropped per component without losing valid gain`() {
        // Treadmill flags 0x0011: speed absent, elevation pair present. Elevation uses 0.1 m.
        for (unavailable in listOf(0x7FFF, 0xFFFF)) {
            val h = harness(BluetoothIds.UUID_TREADMILL_DATA, FusionPersona.RUN)
            BtEngineFtmsParsers.doTreadmill(
                h.emitter,
                bytes(
                    0x11, 0x00,
                    0x46, 0x00, // positive 70 -> 7.0 m
                    unavailable and 0xFF, unavailable ushr 8,
                ),
            )
            assertEquals(7.0, h.fusion.valueOf(MetricKind.ELEVATION_GAIN_POSITIVE)!!, 1e-9)
            assertNull("0x${unavailable.toString(16)} is unavailable", h.fusion.valueOf(MetricKind.ELEVATION_GAIN_NEGATIVE))
        }

        // Cross Trainer flags 0x000021: speed absent, elevation pair present. Units are 1 m.
        for (unavailable in listOf(0xFFFF, 0x7FFF)) {
            val h = harness(BluetoothIds.UUID_ELLIPTICAL_DATA, FusionPersona.RUN)
            BtEngineFtmsParsers.doElliptical(
                h.emitter,
                bytes(
                    0x21, 0x00, 0x00,
                    0x07, 0x00, // positive 7 m
                    unavailable and 0xFF, unavailable ushr 8,
                ),
            )
            assertEquals(7.0, h.fusion.valueOf(MetricKind.ELEVATION_GAIN_POSITIVE)!!, 1e-9)
            assertNull("0x${unavailable.toString(16)} is unavailable", h.fusion.valueOf(MetricKind.ELEVATION_GAIN_NEGATIVE))
        }
    }

    // ── Counter extremes ─────────────────────────────────────────────────────────

    @Test
    fun `cumulative counters decode at their maximum representable value`() {
        val h = bike()
        // Flags 0x0111: no speed, distance (bit 4), energy (bit 8).
        // Distance 0xFFFFFF is the uint24 ceiling; energy 65534 is the largest value that is
        // not the Data Not Available sentinel.
        BtEngineFtmsParsers.doBike(
            h.emitter,
            bytes(0x11, 0x01, 0xFF, 0xFF, 0xFF, 0xFE, 0xFF, 0x00, 0x00, 0x00),
        )

        assertEquals(16_777_215.0, h.fusion.valueOf(MetricKind.DISTANCE)!!, 1e-9)
        assertEquals(
            "65534 is a real reading; only 65535 means unavailable",
            65_534.0,
            h.fusion.valueOf(MetricKind.ENERGY)!!,
            1e-9,
        )
    }

    /**
     * Counter rollover (plan §11.3).
     *
     * FTMS cumulative counters are fixed-width and the specification defines no wrap behaviour:
     * Total Distance is `uint24` and wraps at 16,777,216 m, Total Energy `uint16` at 65,535 —
     * except 65,535 is the Data Not Available sentinel, so energy effectively wraps to a value
     * that cannot be distinguished from "unknown".
     *
     * The parser's obligation stops at decoding each frame truthfully: a wrapped counter is a
     * small number, and it must be reported as the small number the machine actually sent. What
     * a *decrease* means for a session total is a cumulative-continuity question owned by
     * fusion, exactly as CSC's V5/D30 settled for wheel revolutions — the parser must not
     * silently add 16,777,216 to make the series monotonic, because that would invent distance
     * on any machine that simply reset.
     */
    @Test
    fun `a wrapped distance counter is decoded as the value on the wire`() {
        // Flags 0x0011: no speed, total distance.
        val ceiling = bike()
        BtEngineFtmsParsers.doBike(ceiling.emitter, bytes(0x11, 0x00, 0xFF, 0xFF, 0xFF))
        assertEquals(16_777_215.0, ceiling.fusion.valueOf(MetricKind.DISTANCE)!!, 1e-9)

        // One metre later the counter is 0. The parser reports 0; it does not reconstruct
        // 16,777,216, and it does not drop the frame.
        val wrapped = bike()
        BtEngineFtmsParsers.doBike(wrapped.emitter, bytes(0x11, 0x00, 0x00, 0x00, 0x00))
        assertEquals(0.0, wrapped.fusion.valueOf(MetricKind.DISTANCE)!!, 1e-9)

        // And the sample still carries its native cumulative counter, which is what fusion
        // needs to decide whether this is a wrap, a reset, or a new machine.
        val sample = wrapped.fusion.latestSample(MetricKind.DISTANCE)!!
        assertEquals(0.0, sample.nativeCumulative!!, 1e-9)
    }

    @Test
    fun `energy at the wrap boundary is a sentinel, one below it is a reading`() {
        // 65535 is Data Not Available, so a uint16 energy counter cannot express a wrap to a
        // full value — it lands on "unknown" instead. Pinned so the asymmetry is visible.
        val na = bike()
        BtEngineFtmsParsers.doBike(na.emitter, bytes(0x01, 0x01, 0xFF, 0xFF, 0x00, 0x00, 0x00))
        assertNull(na.fusion.valueOf(MetricKind.ENERGY))

        val wrapped = bike()
        BtEngineFtmsParsers.doBike(wrapped.emitter, bytes(0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00))
        assertEquals(0.0, wrapped.fusion.valueOf(MetricKind.ENERGY)!!, 1e-9)
    }

    // ── Stepper 0x2ACF — the persona TMIW supplies no vectors for ────────────────

    @Test
    fun `step climber decodes its two-field mandatory block and optional fields`() {
        val h = harness(BluetoothIds.UUID_STAIR_STEPPER_DATA, FusionPersona.RUN)
        // Flags 0x000E: bit 0 clear (floors + step count present), step rate (1),
        // average step rate (2), positive elevation gain (3).
        BtEngineFtmsParsers.doStepper(
            h.emitter,
            bytes(
                0x0E, 0x00,
                0x1E, 0x00,   // floors            30
                0xC8, 0x00,   // step count       200
                0x50, 0x00,   // step rate         80 /min
                0x4B, 0x00,   // average step rate 75 /min
                0x19, 0x00,   // elevation gain    25 m, resolution 1
            ),
        )

        assertEquals(30.0, h.fusion.valueOf(MetricKind.TOTAL_FLOORS)!!, 1e-9)
        assertEquals(200.0, h.fusion.valueOf(MetricKind.TOTAL_STEPS)!!, 1e-9)
        assertEquals(80.0, h.fusion.valueOf(MetricKind.CADENCE)!!, 1e-9)
        assertEquals(75.0, h.fusion.valueOf(MetricKind.AVERAGE_CADENCE)!!, 1e-9)
        // Metres exactly. The treadmill's elevation field is tenths; reading one with the
        // other's scale is a silent ten-times error, so the personas are pinned separately.
        assertEquals(25.0, h.fusion.valueOf(MetricKind.ELEVATION_GAIN_POSITIVE)!!, 1e-9)
    }

    @Test
    fun `stair climber carries floors alone in its mandatory block, not floors and steps`() {
        val h = harness(BluetoothIds.UUID_STAIR_CLIMBER_DATA, FusionPersona.RUN)
        // Flags 0x0010: bit 0 clear (floors present), stride count (bit 4), nothing between.
        // If the mandatory block were read as four octets like the stepper's, stride count
        // would decode from the wrong offset.
        BtEngineFtmsParsers.doClimber(
            h.emitter,
            bytes(0x10, 0x00, 0x2C, 0x01, 0xF4, 0x01),
        )

        assertEquals(300.0, h.fusion.valueOf(MetricKind.TOTAL_FLOORS)!!, 1e-9)
        assertEquals(500.0, h.fusion.valueOf(MetricKind.TOTAL_STRIDES)!!, 1e-9)
    }

    @Test
    fun `stepper and climber truncations stay bounded`() {
        val stepper = bytes(0x0E, 0x00, 0x1E, 0x00, 0xC8, 0x00, 0x50, 0x00, 0x4B, 0x00, 0x19, 0x00)
        val climber = bytes(0x10, 0x00, 0x2C, 0x01, 0xF4, 0x01)

        for (length in 0..stepper.size) {
            val h = harness(BluetoothIds.UUID_STAIR_STEPPER_DATA, FusionPersona.RUN)
            BtEngineFtmsParsers.doStepper(h.emitter, stepper.copyOf(length))
            if (length < 6) {
                assertNull(
                    "length $length cannot contain the floors-and-steps block",
                    h.fusion.valueOf(MetricKind.TOTAL_STEPS),
                )
            }
        }
        for (length in 0..climber.size) {
            val h = harness(BluetoothIds.UUID_STAIR_CLIMBER_DATA, FusionPersona.RUN)
            BtEngineFtmsParsers.doClimber(h.emitter, climber.copyOf(length))
            if (length < 4) {
                assertNull(
                    "length $length cannot contain floors",
                    h.fusion.valueOf(MetricKind.TOTAL_FLOORS),
                )
            }
        }
    }

    // ── Minimal packets ──────────────────────────────────────────────────────────

    @Test
    fun `flag-only and sub-flag packets decode nothing and do not throw`() {
        for (persona in listOf(FusionPersona.RIDE, FusionPersona.RUN, FusionPersona.ROW_PADDLE)) {
            for (packet in listOf(bytes(), bytes(0x01), bytes(0x01, 0x00))) {
                val b = harness(BluetoothIds.UUID_BIKING_DATA, persona)
                BtEngineFtmsParsers.doBike(b.emitter, packet)
                assertNull(b.fusion.valueOf(MetricKind.SPEED))

                val t = harness(BluetoothIds.UUID_TREADMILL_DATA, persona)
                BtEngineFtmsParsers.doTreadmill(t.emitter, packet)
                assertNull(t.fusion.valueOf(MetricKind.SPEED))

                val r = harness(BluetoothIds.UUID_ROWING_MACHINE_DATA, persona)
                BtEngineFtmsParsers.doRower(r.emitter, packet)
                assertNull(r.fusion.valueOf(MetricKind.STROKE_RATE))
            }
        }
    }

    @Test
    fun `an elliptical packet shorter than its three-octet flag word decodes nothing`() {
        // 0x2ACE is the one FTMS data characteristic with a 24-bit flag field.
        for (packet in listOf(bytes(), bytes(0x00), bytes(0x00, 0x00))) {
            val h = harness(BluetoothIds.UUID_ELLIPTICAL_DATA, FusionPersona.RUN)
            BtEngineFtmsParsers.doElliptical(h.emitter, packet)
            assertNull(h.fusion.valueOf(MetricKind.SPEED))
        }

        // Three octets of flags and nothing else is legal, and decodes nothing.
        val h = harness(BluetoothIds.UUID_ELLIPTICAL_DATA, FusionPersona.RUN)
        BtEngineFtmsParsers.doElliptical(h.emitter, bytes(0x01, 0x00, 0x00))
        assertNull(h.fusion.valueOf(MetricKind.SPEED))
        assertNull(h.fusion.valueOf(MetricKind.DISTANCE))
    }
}
