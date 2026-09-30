package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids

/**
 * The FTMS offset-desync defect and its D38 fix, pinned together.
 *
 * ## The defect this replaces
 *
 * Every optional-field reader in [BtEngineFtmsParsers] guards with `has(b, off, n)`. Before
 * D38 a failed guard returned `off` **unchanged** and the walk continued to the next flagged
 * field. A field smaller than the one that failed therefore fit, and read at an offset
 * belonging to a different field entirely.
 *
 * This was not theoretical. Both vectors below are pinned TMIW fixtures from real Matrix
 * hardware (TMIW@85f2ee75 `test/matrix_treadmill_test.dart` and `test/matrix_bike_test.dart`),
 * and both are machines whose flags promise more fields than their 20-byte packet carries. The
 * treadmill vector published its total-energy byte as a heart rate.
 *
 * ## What these tests hold
 *
 * Each test asserts three things at once: the fields ahead of the failure still decode, the
 * fields after it are absent rather than wrong, and — for the treadmill — the truncated energy
 * block still yields its total. The commented "was" values are what the previous walk produced
 * and are kept deliberately: they are the regression this file exists to prevent, and a future
 * reader deciding the abort is over-strict needs to see what continuing actually cost.
 *
 * Plan §12.1 V7 records the accompanying finding — that neither Matrix layout needs a quirk
 * registry entry, because a correctly aborting spec-order walk reproduces TMIW's decoded set.
 *
 * @see BtEngineFtmsParsers
 */
class FtmsOffsetDesyncCharacterizationTest {

    private val connection = ConnectionId("AA:BB:CC:DD:EE:40", 40L)

    private data class Harness(val fusion: MetricFusion, val emitter: MetricEmitter)

    private fun harness(characteristic: UUID): Harness {
        val fusion = MetricFusion(BtEngineState())
        return Harness(
            fusion,
            MetricEmitter(
                fusion = fusion,
                sourceId = MetricSourceId(connection, MetricProtocol.FTMS, characteristic),
                sourceClass = MetricSourceClass.FTMS,
                arrivalMs = 40_000L,
            ),
        )
    }

    private fun MetricFusion.valueOf(metric: MetricKind): Double? = latestSample(metric)?.value

    private fun bytes(vararg v: Int): ByteArray =
        ByteArray(v.size) { (v[it] and 0xFF).toByte() }

    /**
     * Matrix Performance treadmill, flags `0x1F9E`, length 20.
     *
     * Spec-order walk of this packet:
     * ```
     *   off 0  flags            0x1F9E  (speed present; avg speed, distance, incline+ramp,
     *                                    elevation, energy, HR, MET, elapsed, remaining,
     *                                    belt force all flagged; both pace bits clear)
     *   off 2  speed            81      -> 0.81 km/h
     *   off 4  avg speed        178     -> 1.78 km/h
     *   off 6  total distance   88      -> 88 m
     *   off 9  incline + ramp   4 bytes
     *   off 13 elevation pair   4 bytes
     *   off 17 expended energy  needs 5, only 3 remain -> guard fails, off stays 17
     *   off 17 heart rate       needs 1, fits -> reads byte 17
     * ```
     * Byte 17 is the total-energy LSB, value 12. TMIW decodes bytes 17..18 as 12 kcal. The old
     * walk emitted byte 17 as a heart rate of 12 bpm, which sits inside [PlausibilityBounds]'
     * 1..254 window and was therefore accepted, projected, and believed.
     *
     * The 12 kcal here makes the wrongness visible. The same packet later in a real session
     * carries 169 kcal, and 169 bpm is an ordinary running heart rate — that is the actual
     * hazard, because nothing downstream could distinguish it from a real reading.
     */
    @Test
    fun `matrix treadmill 20-byte packet yields energy and no phantom heart rate`() {
        val h = harness(FtmsUuids.UUID_TREADMILL_DATA)

        BtEngineFtmsParsers.doTreadmill(
            h.emitter,
            bytes(158, 31, 81, 0, 178, 0, 88, 0, 0, 0, 0, 255, 127, 0, 0, 255, 255, 12, 0, 151),
        )

        // Ahead of the truncation, and unchanged by D38.
        assertEquals(0.81, h.fusion.valueOf(MetricKind.SPEED)!!, 1e-9)
        assertEquals(88.0, h.fusion.valueOf(MetricKind.DISTANCE)!!, 1e-9)

        // The partial energy block recovers the total from the two octets that are present.
        // This agrees with TMIW's `processExpandedEnergyFlag(partial: true)`. Was: null.
        assertEquals(
            "total energy leads the block and is present, even though the block is short",
            12.0,
            h.fusion.valueOf(MetricKind.ENERGY)!!,
            1e-9,
        )

        // Energy rate needs the per-hour and per-minute octets, which are not in the packet.
        assertNull(h.fusion.valueOf(MetricKind.ENERGY_RATE))

        // The walk stops at the short block, so nothing after it is read. Was: 12.0 bpm, taken
        // from the total-energy LSB. This assertion is the point of the whole change.
        assertNull(
            "byte 17 is the total-energy LSB and must never surface as a heart rate",
            h.fusion.valueOf(MetricKind.HEART_RATE),
        )

        // Was: 0.0, read from byte 18 - the total-energy MSB.
        assertNull(h.fusion.valueOf(MetricKind.METABOLIC_EQUIVALENT))
    }

    /**
     * Matrix bike, flags `0x1DFE`, length 20. Milder instance of the same defect.
     *
     * ```
     *   off 2  speed        3203 -> 32.03 km/h
     *   off 4  avg speed    3123
     *   off 6  cadence      134  -> 67 rpm
     *   off 8  avg cadence  128
     *   off 10 distance     2497 m
     *   off 13 resistance   13
     *   off 15 power        162 W
     *   off 17 avg power    157 W
     *   off 19 energy       needs 5, only 1 remains -> guard fails, off stays 19
     *   off 19 MET          needs 1, fits -> reads byte 19
     * ```
     * Only one octet remains when the energy block starts, so not even the partial read applies
     * and the walk simply stops. The heart-rate bit is clear in `0x1DFE`, so this packet never
     * produced a phantom HR; it is pinned because it proves the desync is a property of the
     * walk rather than of one characteristic.
     */
    @Test
    fun `matrix bike 20-byte packet stops at the energy block with no bogus MET`() {
        val h = harness(FtmsUuids.UUID_BIKING_DATA)

        BtEngineFtmsParsers.doBike(
            h.emitter,
            bytes(254, 29, 131, 12, 51, 12, 134, 0, 128, 0, 193, 9, 0, 13, 0, 162, 0, 157, 0, 54),
        )

        // Everything TMIW's bespoke Matrix override decodes, from the standard walk alone.
        assertEquals(32.03, h.fusion.valueOf(MetricKind.SPEED)!!, 1e-9)
        assertEquals(67.0, h.fusion.valueOf(MetricKind.CADENCE)!!, 1e-9)
        assertEquals(2497.0, h.fusion.valueOf(MetricKind.DISTANCE)!!, 1e-9)
        assertEquals(162.0, h.fusion.valueOf(MetricKind.POWER)!!, 1e-9)
        assertEquals(157.0, h.fusion.valueOf(MetricKind.AVERAGE_POWER)!!, 1e-9)

        // One octet left where five are needed, so no energy at all - correctly, since a single
        // octet cannot be a uint16 total. TMIW decodes no energy from this packet either.
        assertNull(h.fusion.valueOf(MetricKind.ENERGY))

        // Was: MET 5.4, read from byte 19 after the energy guard failed. TMIW steps over it.
        assertNull(
            "byte 19 must not be read as MET once the walk has aborted",
            h.fusion.valueOf(MetricKind.METABOLIC_EQUIVALENT),
        )

        assertNull(h.fusion.valueOf(MetricKind.HEART_RATE))
    }

    /**
     * FTMS "Data Not Available" for Expended Energy (plan §12.1 V8, resolved 2026-08-10).
     *
     * The three energy sub-fields share one flag bit, so a server that cannot compute a value
     * has no flag to clear and sends `0xFFFF` instead. The specification states that this means
     * Data Not Available for Total Energy and Energy Per Hour.
     *
     * This matters more than it looks. [MetricKind.ENERGY] is **deliberately unbounded** in
     * [PlausibilityBounds] — cumulative metrics have no sensible ceiling and dropping a sample
     * corrupts a delta chain — so nothing downstream would have caught 65535 kcal. It would
     * have been published as a real cumulative reading and fed the session's energy continuity.
     *
     * The rate fields were already handled by `ftmsEnergyRateKcalPerHour`; the total was not.
     */
    @Test
    fun `an unavailable total energy sentinel is dropped, not published as 65535 kcal`() {
        val h = harness(FtmsUuids.UUID_BIKING_DATA)

        // Flags 0x0101: bit 0 set so speed is absent, bit 8 for expended energy. Total 0xFFFF,
        // per-hour 0xFFFF, per-minute 0xFF — a machine declaring it computes none of the three.
        //
        // Bit 0 matters: it is More Data, and its *clear* state is what makes speed present.
        // With it clear the energy block starts two octets later and this test would pass by
        // taking the truncated-block path rather than the sentinel path.
        BtEngineFtmsParsers.doBike(
            h.emitter,
            bytes(0x01, 0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF),
        )

        assertNull(
            "0xFFFF means Data Not Available; ENERGY is unbounded so nothing else would catch it",
            h.fusion.valueOf(MetricKind.ENERGY),
        )
        assertNull(h.fusion.valueOf(MetricKind.ENERGY_RATE))
    }

    /** A real total beside unavailable rates still publishes the total. */
    @Test
    fun `a known total energy with unavailable rates still publishes the total`() {
        val h = harness(FtmsUuids.UUID_BIKING_DATA)

        // Flags 0x0101 again: speed absent, expended energy present. Total 300 kcal, both
        // rates unavailable.
        BtEngineFtmsParsers.doBike(
            h.emitter,
            bytes(0x01, 0x01, 0x2C, 0x01, 0xFF, 0xFF, 0xFF),
        )

        assertEquals(300.0, h.fusion.valueOf(MetricKind.ENERGY)!!, 1e-9)
        assertNull(h.fusion.valueOf(MetricKind.ENERGY_RATE))
    }

    /**
     * The control case. A conforming 14-byte Matrix treadmill packet (`0x1F00`, pinned in the
     * same TMIW file) has no failed guard anywhere in its walk, so every field lands on its
     * correct offset and FitBridge already agrees with TMIW exactly.
     *
     * This must be unaffected by D38. A fix that changes this packet's output has broken
     * conforming devices to repair a non-conforming one.
     */
    @Test
    fun `conforming treadmill packet is already correct and must survive the fix`() {
        val h = harness(FtmsUuids.UUID_TREADMILL_DATA)

        BtEngineFtmsParsers.doTreadmill(
            h.emitter,
            bytes(0, 31, 113, 0, 0, 2, 204, 0, 60, 6, 255, 127, 26, 0),
        )

        assertEquals(1.13, h.fusion.valueOf(MetricKind.SPEED)!!, 1e-9)
        assertEquals(204.0, h.fusion.valueOf(MetricKind.ELAPSED)!!, 1e-9)
        assertEquals(26.0, h.fusion.valueOf(MetricKind.POWER)!!, 1e-9)
        // TMIW reports heartRate 0 for this vector; 0 is outside the 1..254 plausibility
        // window, so FitBridge decodes it and fusion rejects it. That divergence is intended.
        assertNull(h.fusion.valueOf(MetricKind.HEART_RATE))
    }
}
