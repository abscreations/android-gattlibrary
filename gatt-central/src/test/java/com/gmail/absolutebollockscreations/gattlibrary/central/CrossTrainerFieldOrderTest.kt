package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsDataFlags
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids

/**
 * Cross Trainer Data (`0x2ACE`) field order, from a physical machine.
 *
 * ### The defect this pins
 *
 * [BtEngineFtmsParsers.doElliptical] read Resistance Level (bit 7) *before*
 * Inclination and Ramp Angle (bit 6). FTMS Table 4.6 puts inclination first, and
 * so do FitBridge's own flag constants - [FtmsDataFlags.ELL_INCLINATION] is `0x40` and
 * [FtmsDataFlags.ELL_RESISTANCE] is `0x80`. Only the parser disagreed.
 *
 * Whenever a machine set both bits, the two fields were swapped:
 *
 * | Machine field | Was reported as |
 * |---|---|
 * | Inclination | RESISTANCE |
 * | Ramp angle | INCLINATION |
 * | Resistance level | RAMP_ANGLE |
 *
 * ### Why it survived
 *
 * The widths cancel. Resistance (2 octets) plus the inclination pair (4) is the
 * same six octets as the inclination pair (4) plus resistance (2), so the offset
 * realigns immediately afterwards and **every field from Instantaneous Power
 * onward stayed correct**. There was no truncation, no bounds failure and no
 * implausible value - just three metrics quietly holding each other's numbers.
 *
 * The `div10 = true` that used to sit on this call was part of the same defect.
 * Dividing the inclination field (55, i.e. 5.5 %) by ten yields 5.5, which reads
 * exactly like a resistance level on a machine whose levels run 1-20. The
 * mis-scaling made the mis-read look plausible, which is why it took a sniffer
 * capture rather than a bug report to find.
 *
 * ### The evidence
 *
 * [CAPTURED_FRAME] is an unmodified notification from a physical elliptical,
 * 2026-08-06. Across all 179 frames of that session the reported RESISTANCE
 * series was bit-for-bit the machine's inclination field divided by ten.
 */
class CrossTrainerFieldOrderTest {

    /**
     * One real Cross Trainer Data notification, 38 octets.
     *
     * Flags `0x002FFE` declare average speed, distance, step count, stride count,
     * elevation gain, inclination, resistance, instantaneous and average power,
     * energy, heart rate and elapsed time. Bit 0 is clear, so instantaneous speed
     * is present.
     *
     * Field layout, by octet offset:
     *
     * | Offset | Field | Raw | Means |
     * |---|---|---|---|
     * | 20 | Inclination | 55 | 5.5 % |
     * | 22 | Ramp angle | 32 | 3.2 deg |
     * | 24 | Resistance level | 7 | level 7 |
     * | 26 | Instantaneous power | 157 | 157 W |
     */
    private val capturedFrame = byteArrayOf(
        0xFE.toByte(), 0x2F, 0x00, // flags
        0x6C, 0x03, //               instantaneous speed  876 -> 8.76 km/h
        0x84.toByte(), 0x03, //      average speed        900 -> 9.00 km/h
        0x82.toByte(), 0x01, 0x00, //total distance       386 m
        0x7F, 0x00, //               step rate            127 /min
        0x82.toByte(), 0x00, //      average step rate    130 /min
        0x86.toByte(), 0x06, //      stride count        1670
        0x17, 0x00, //               elevation gain +      23 m
        0x00, 0x00, //               elevation gain -       0 m
        0x37, 0x00, //               inclination           55 -> 5.5 %
        0x20, 0x00, //               ramp angle            32 -> 3.2 deg
        0x07, 0x00, //               resistance level       7
        0x9D.toByte(), 0x00, //      instantaneous power  157 W
        0xA5.toByte(), 0x00, //      average power        165 W
        0x19, 0x00, //               total energy          25 kcal
        0x31, 0x02, //               energy per hour      561
        0x09, //                     energy per minute      9
        0x84.toByte(), //            heart rate           132 bpm
        0x9B.toByte(), 0x00, //      elapsed time         155 s
    )

    private fun parse(): MetricFusion {
        val state = BtEngineState()
        val fusion = MetricFusion(state)
        BtEngineFtmsParsers.doElliptical(
            MetricEmitter(
                fusion = fusion,
                sourceId = MetricSourceId(
                    ConnectionId("AA:BB:CC:DD:EE:10", 10L),
                    MetricProtocol.FTMS,
                    FtmsUuids.UUID_ELLIPTICAL_DATA,
                ),
                sourceClass = MetricSourceClass.FTMS,
                arrivalMs = 12_345L,
            ),
            capturedFrame,
        )
        return fusion
    }

    private fun MetricFusion.value(kind: MetricKind): Double =
        latestSample(kind)!!.value

    @Test
    fun `resistance is the machine's resistance field, not its inclination`() {
        // Before the fix this read 5.5 - the inclination field divided by ten.
        // The machine's own resistance field holds a raw 7, which the 2ACE
        // DecimalExponent of -1 makes 0.7.
        assertEquals(0.7, parse().value(MetricKind.RESISTANCE), 0.0001)
    }

    @Test
    fun `inclination is the machine's inclination field, not its ramp angle`() {
        // Before the fix this read 3.2 - the ramp angle.
        assertEquals(5.5, parse().value(MetricKind.INCLINATION), 0.0001)
    }

    @Test
    fun `ramp angle is the machine's ramp angle, not its resistance level`() {
        // Before the fix this read 0.7 - the resistance level divided by ten.
        assertEquals(3.2, parse().value(MetricKind.RAMP_ANGLE), 0.0001)
    }

    @Test
    fun `resistance level is decoded in tenths`() {
        // The divide was briefly removed on the reasoning that it existed only to
        // make the swapped inclination field look like a plausible level. It did
        // do that, but it is also simply correct: the 2ACE XML gives Resistance
        // Level `DecimalExponent -1`. Two defects shared one line and only one of
        // them was real.
        //
        // NOTE: this elliptical's console displays no resistance value, so there
        // is no ground truth for whether its raw 7 means 0.7, or whether the
        // machine is non-compliant and means 7. FitBridge passes the spec reading
        // through unchanged; the device is recorded unverified for this field
        // rather than a scaling quirk being invented to make the number look
        // nicer.
        assertEquals("2ACE resistance is tenths", 0.7, parse().value(MetricKind.RESISTANCE), 0.0001)
    }

    @Test
    fun `fields after the swapped pair were never affected`() {
        // This is why the defect went unnoticed: the two mis-reads consume the
        // same total width, so alignment is restored before power is read. These
        // assertions passed both before and after the fix, and are here to prove
        // the fix did not shift anything it should not have.
        val fusion = parse()
        assertEquals(8.76, fusion.value(MetricKind.SPEED), 0.0001)
        assertEquals(157.0, fusion.value(MetricKind.POWER), 0.0001)
        assertEquals(165.0, fusion.value(MetricKind.AVERAGE_POWER), 0.0001)
        assertEquals(132.0, fusion.value(MetricKind.HEART_RATE), 0.0001)
        assertEquals(155.0, fusion.value(MetricKind.ELAPSED), 0.0001)
    }
}
