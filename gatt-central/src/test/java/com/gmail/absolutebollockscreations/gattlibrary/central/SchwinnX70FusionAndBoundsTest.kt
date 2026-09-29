package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Schwinn x70 fusion provenance (§11 obligation 6) and bounds rejection (§11 obligation 11b).
 *
 * ## Why this file exists rather than the tests that were supposed to cover it
 *
 * §7's exit criteria ask for "a provenance test proving estimated power never wins fusion against a
 * native source". The test that carried that name asserted the declaration order of the
 * [MetricQuality] enum, using a helper defined in its own file, and never touched [MetricFusion].
 * Reordering the real comparator would not have failed it. The 2026-08-18 exit audit found that,
 * and this file is the answer.
 *
 * ## The adversary stopped being hypothetical
 *
 * Until 2026-08-18 the contest this obligation guards was believed to be between machines — a 170
 * reporting power natively somewhere, a 270 reporting a modelled estimate somewhere else. Captured
 * QZ logs show every x70 console emitting all three streams, so **one console reports a measured
 * power on its 14-byte sibling frame and supplies the inputs for a modelled one on its 17-byte main
 * frame, about ten times a second, in every session that has a main stream.**
 *
 * The two arrive on different characteristics, so the engine tags them as different sources on one
 * connection, and fusion has a real contest to resolve roughly twice per second for the whole ride.
 * If quality did not outrank source class and recency, a rider would watch a measured wattage be
 * overwritten by a table lookup, continuously, and nothing downstream would flag it.
 *
 * ## Obligation 11b
 *
 * §11b asks that a sample outside its declared range is dropped and captured rather than published,
 * one test per metric per untestable family. The x70's existing bounds tests all assert that
 * captured values fall *inside* their ranges, which is the opposite direction and cannot fail if the
 * rejection path breaks.
 */
class SchwinnX70FusionAndBoundsTest {

    private val link = ConnectionId("AA:00:00:00:00:70", 1)

    /** The engine tags a sample with the characteristic it arrived on; so does this. */
    private fun sourceFor(characteristic: UUID) =
        MetricSourceId(link, MetricProtocol.VENDOR, characteristic)

    private fun sample(
        metric: MetricKind,
        value: Double,
        quality: MetricQuality,
        characteristic: UUID,
        arrivalMs: Long,
    ) = MetricSample(
        connectionId = link,
        sourceId = sourceFor(characteristic),
        protocol = MetricProtocol.VENDOR,
        sourceClass = MetricSourceClass.VENDOR_NATIVE,
        metric = metric,
        arrivalMs = arrivalMs,
        eventMs = null,
        value = value,
        nativeCumulative = if (metric.cumulative) value else null,
        quality = quality,
        confidence = 1.0,
    )

    // ── A real interleaved session, decoded by the production decoders ───────────

    private class Record(val tMs: Int, val channel: String, val packet: ByteArray)

    private fun interleaved270(): List<Record> {
        val stream = javaClass.classLoader
            ?.getResourceAsStream("fixtures/hardware/schwinn_x70_270_qz_interleaved.json")
            ?: error("corpus missing")
        val fixture = FixtureJson.obj(FixtureJson.parse(stream.bufferedReader().use { it.readText() }))
        return FixtureJson.array(fixture["cases"]).map { FixtureJson.obj(it) }.map { case ->
            Record(
                tMs = FixtureJson.int(case["tMs"]),
                channel = FixtureJson.string(case["ch"]),
                packet = FixtureJson.array(case["packet"])
                    .map { FixtureJson.int(it).toByte() }.toByteArray(),
            )
        }
    }

    private class Emission(val arrivalMs: Long, val characteristic: UUID, val produced: SchwinnX70Sample)

    /**
     * The metrics the handler would emit, in arrival order, tagged with the characteristic that
     * produced them.
     *
     * **The characteristic is carried rather than inferred from quality.** A main frame emits
     * `ENERGY` as `NATIVE` — the console really does count calories — so deriving the source from
     * the quality tag would file energy under the sibling stream and quietly test the wrong thing.
     */
    private fun emittedFromCapture(): List<Emission> {
        val handler = SchwinnX70ProtocolHandler()
        val uuids = mapOf(
            "main" to BluetoothIds.UUID_X70_MEASUREMENT,
            "supplemental" to BluetoothIds.UUID_X70_SUPPLEMENTAL,
            "sibling" to BluetoothIds.UUID_X70_SIBLING,
        )
        return interleaved270().flatMap { record ->
            val uuid = uuids[record.channel] ?: return@flatMap emptyList()
            handler.samplesForNotification(uuid, record.packet, arrivalMs = record.tMs.toLong())
                .map { Emission(record.tMs.toLong(), uuid, it) }
        }
    }

    // ── Obligation 6: the provenance rule, through the real comparator ───────────

    @Test
    fun `the two streams really are separate fusion sources on one connection`() {
        // If they were one source there would be no contest and no obligation. The engine keys a
        // source on the characteristic, so the measured reading and the modelled one compete.
        assertNotEquals(
            "the sibling and main streams must not collapse into one source",
            sourceFor(BluetoothIds.UUID_X70_SIBLING),
            sourceFor(BluetoothIds.UUID_X70_MEASUREMENT),
        )
    }

    @Test
    fun `a measured sibling power beats a modelled main-frame power`() {
        val fusion = MetricFusion(BtEngineState())
        fusion.setPersona(FusionPersona.RIDE, 0)

        fusion.submit(
            sample(MetricKind.POWER, 210.0, MetricQuality.ESTIMATED, BluetoothIds.UUID_X70_MEASUREMENT, 1_000),
        )
        assertEquals(
            "with nothing better available the estimate is used",
            sourceFor(BluetoothIds.UUID_X70_MEASUREMENT),
            fusion.selectedSource(MetricKind.POWER),
        )

        fusion.submit(
            sample(MetricKind.POWER, 104.0, MetricQuality.NATIVE, BluetoothIds.UUID_X70_SIBLING, 1_050),
        )
        assertEquals(
            "the measured reading takes over",
            sourceFor(BluetoothIds.UUID_X70_SIBLING),
            fusion.selectedSource(MetricKind.POWER),
        )
    }

    @Test
    fun `a later estimate does not displace an earlier measurement`() {
        // The failure mode that matters. The two streams interleave, so the estimate is *newer*
        // than the measurement roughly half the time. Recency must not beat quality, or the
        // selection would oscillate every tick for the whole ride.
        val fusion = MetricFusion(BtEngineState())
        fusion.setPersona(FusionPersona.RIDE, 0)

        fusion.submit(
            sample(MetricKind.POWER, 104.0, MetricQuality.NATIVE, BluetoothIds.UUID_X70_SIBLING, 2_000),
        )
        for (tick in 1..10) {
            fusion.submit(
                sample(
                    MetricKind.POWER,
                    210.0,
                    MetricQuality.ESTIMATED,
                    BluetoothIds.UUID_X70_MEASUREMENT,
                    2_000 + tick * 100L,
                ),
            )
            fusion.submit(
                sample(
                    MetricKind.POWER,
                    104.0,
                    MetricQuality.NATIVE,
                    BluetoothIds.UUID_X70_SIBLING,
                    2_050 + tick * 100L,
                ),
            )
            assertEquals(
                "tick $tick: the measurement must hold the metric",
                sourceFor(BluetoothIds.UUID_X70_SIBLING),
                fusion.selectedSource(MetricKind.POWER),
            )
        }
    }

    @Test
    fun `speed is protected by the same rule as power`() {
        // D74's speed model sits on top of the power model, so it is an estimate derived from an
        // estimate. It has the same native adversary and needs the same protection.
        val fusion = MetricFusion(BtEngineState())
        fusion.setPersona(FusionPersona.RIDE, 0)

        fusion.submit(
            sample(MetricKind.SPEED, 31.0, MetricQuality.ESTIMATED, BluetoothIds.UUID_X70_MEASUREMENT, 1_000),
        )
        fusion.submit(
            sample(MetricKind.SPEED, 17.2, MetricQuality.NATIVE, BluetoothIds.UUID_X70_SIBLING, 1_050),
        )
        assertEquals(
            sourceFor(BluetoothIds.UUID_X70_SIBLING),
            fusion.selectedSource(MetricKind.SPEED),
        )
    }

    @Test
    fun `the rule holds on a captured session rather than on constructed samples`() {
        // The obligation, discharged against real traffic: replay a captured interleaved session
        // through the production handler, submit whatever it emits to a real MetricFusion in
        // arrival order, and require the sibling stream to own speed and power at the end.
        val emitted = emittedFromCapture()
        assertTrue("the capture must produce metrics", emitted.size > 500)

        val qualities = emitted.filter { it.produced.metric == MetricKind.POWER }
            .map { it.produced.quality }.toSet()
        assertEquals(
            "and it must contain both sides of the contest, or the test proves nothing",
            setOf(MetricQuality.NATIVE, MetricQuality.ESTIMATED),
            qualities,
        )

        val fusion = MetricFusion(BtEngineState())
        fusion.setPersona(FusionPersona.RIDE, 0)
        var rejected = 0
        for (emission in emitted) {
            val outcome = fusion.submit(
                sample(
                    emission.produced.metric,
                    emission.produced.value,
                    emission.produced.quality,
                    emission.characteristic,
                    emission.arrivalMs,
                ),
            )
            if (outcome == FusionSubmission.REJECTED_IMPLAUSIBLE) rejected++
        }

        // Zero again since the cadence fix. Before it, 21 of these were cadence values above
        // 600 rpm that the plausibility guard had to drop.
        assertEquals("no captured value is implausible", 0, rejected)
        assertEquals(
            "the measured stream owns power at the end of a real session",
            sourceFor(BluetoothIds.UUID_X70_SIBLING),
            fusion.selectedSource(MetricKind.POWER),
        )
        assertEquals(
            "and speed",
            sourceFor(BluetoothIds.UUID_X70_SIBLING),
            fusion.selectedSource(MetricKind.SPEED),
        )
    }


    // ── The defect the captured session exposed ─────────────────────────────────

    @Test
    fun `cadence is derived between counter changes, not between frames`() {
        // **The regression for a defect this corpus exposed on 2026-08-18, now fixed.**
        //
        // The main frame carries a crank *revolution* counter. At 80 rpm it advances about 1.3
        // times a second, while the stream notifies about ten times a second. `cadenceRpm`
        // differentiates it across consecutive frames, so a ~100 ms window sees a delta of either
        // 0 or 1 — and 1 revolution in 0.1 s is 600 rpm.
        //
        // Across three captured slices and two physical consoles: **88 % of derived cadence values
        // are exactly 0 rpm, about 12 % exceed 600 rpm, and one sample in 744 lands in a plausible
        // range.** The bounds guard drops the high ones. It cannot drop the zeros, because zero is
        // in range — so a rider sees cadence pinned at zero, which is precisely the "claim that the
        // rider stopped" that `cadenceRpm`'s own KDoc forbids.
        //
        // It reaches further than cadence. `mainSamples` feeds this number to
        // `SchwinnX70PowerModel.powerWatts`, and speed is derived from that power, so a console
        // whose only stream is the main one publishes a modelled power and speed built on a cadence
        // that is wrong nearly every frame.
        //
        // **Why nothing caught it.** Every existing cadence test measures the session *average* —
        // first counter to last counter over the whole run — which comes out at a healthy 64–80 rpm
        // and says nothing about what the handler emits frame by frame.
        //
        // **The fix**: measure between counter *changes* rather than between frames, emit nothing
        // while the counter has not moved, and call it a real zero only after
        // `STOPPED_AFTER_SECONDS`. The same capture then gives 74–115 rpm, median 81.4.
        val samples = emittedFromCapture()
            .filter { it.produced.metric == MetricKind.CADENCE }
            .map { it.produced.value }
        assertTrue("the capture must produce cadence", samples.size > 10)

        val bound = requireNotNull(PlausibilityBounds.rangeFor(MetricKind.CADENCE, FusionPersona.RIDE))
        assertEquals(
            "every derived value must be plausible — before the fix, none of them was",
            emptyList<Double>(),
            samples.filter { it < bound.min || it > bound.max },
        )
        assertEquals(
            "and none may be a false zero: the crank never stopped in this capture",
            emptyList<Double>(),
            samples.filter { it == 0.0 },
        )

        // The values must also agree with what the session as a whole did — about 64 rpm here —
        // rather than merely landing inside a wide bound.
        val mean = samples.average()
        assertTrue("mean derived cadence $mean must match the session's own pace", mean in 40.0..120.0)

        // Far fewer samples than main frames, and that is the point: cadence is only knowable when
        // the crank turns, which is roughly once a second, not ten times.
        val mainFrames = interleaved270().count { it.channel == "main" && it.packet.size == 17 }
        assertTrue(
            "cadence must be emitted per revolution, not per frame: ${samples.size} of $mainFrames",
            samples.size < mainFrames / 2,
        )
    }

    // ── Obligation 11b: out of range is dropped, not published ──────────────────

    private fun assertRejectedOutOfBounds(metric: MetricKind) {
        val bound = PlausibilityBounds.rangeFor(metric, FusionPersona.RIDE)
        assertNotNull("$metric must declare a bound for this obligation to mean anything", bound)

        val fusion = MetricFusion(BtEngineState())
        fusion.setPersona(FusionPersona.RIDE, 0)

        val absurd = bound!!.max * 10.0 + 1.0
        val outcome = fusion.submit(
            sample(metric, absurd, MetricQuality.NATIVE, BluetoothIds.UUID_X70_SIBLING, 1_000),
        )

        assertEquals("$metric at $absurd must be rejected", FusionSubmission.REJECTED_IMPLAUSIBLE, outcome)
        assertNull(
            "$metric: a rejected sample may not select a source",
            fusion.selectedSource(metric),
        )

        // The rejection is about the value, not the source: the same stream keeps working.
        val sane = (bound.min + bound.max) / 2.0
        assertEquals(
            "$metric: a sane follow-up from the same stream is accepted",
            FusionSubmission.ACCEPTED,
            fusion.submit(sample(metric, sane, MetricQuality.NATIVE, BluetoothIds.UUID_X70_SIBLING, 2_000)),
        )
        assertEquals(sourceFor(BluetoothIds.UUID_X70_SIBLING), fusion.selectedSource(metric))
    }

    @Test
    fun `an implausible speed from an x70 is dropped rather than published`() =
        assertRejectedOutOfBounds(MetricKind.SPEED)

    @Test
    fun `an implausible power from an x70 is dropped rather than published`() =
        assertRejectedOutOfBounds(MetricKind.POWER)

    @Test
    fun `an implausible cadence from an x70 is dropped rather than published`() =
        assertRejectedOutOfBounds(MetricKind.CADENCE)

    @Test
    fun `energy and resistance are unbounded, so the decoder is the only thing guarding them`() {
        // §7's exit list asks for plausibility bounds on "cadence, resistance, and derived power".
        // Two of those exist. Resistance does not and deliberately never will — it is a vendor
        // level with no shared meaning, so a bound would be a number invented for a scale nobody
        // publishes. Energy is unbounded for a different reason: a cumulative total legitimately
        // grows without limit.
        //
        // That leaves the x70's energy metric with **no downstream guard at all**, which is exactly
        // what the §12.1 V8 work found in the FTMS path, and it is why the rate check inside
        // SchwinnX70Measurement is load-bearing rather than decorative. This test states the gap
        // and then shows what closes it.
        assertNull(
            "energy is unbounded by design",
            PlausibilityBounds.rangeFor(MetricKind.ENERGY, FusionPersona.RIDE),
        )
        assertEquals("and says so", "unbounded", PlausibilityBounds.describe(MetricKind.ENERGY, FusionPersona.RIDE))

        val fusion = MetricFusion(BtEngineState())
        fusion.setPersona(FusionPersona.RIDE, 0)
        assertEquals(
            "fusion would publish an absurd energy total without complaint",
            FusionSubmission.ACCEPTED,
            fusion.submit(
                sample(MetricKind.ENERGY, 500_000.0, MetricQuality.NATIVE, BluetoothIds.UUID_X70_MEASUREMENT, 1_000),
            ),
        )

        // The decoder is what refuses. A reading taken one octet off inflates the accumulator by a
        // factor of 256, which the rate guard catches and fusion never would.
        val first = requireNotNull(
            SchwinnX70Measurement.parseMain(
                byteArrayOf(17, 32, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 4),
            ),
        )
        val misread = requireNotNull(
            SchwinnX70Measurement.parseMain(
                byteArrayOf(17, 32, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 4),
            ),
        )
        assertNull(
            "an implausible energy rate is withheld at the decoder",
            SchwinnX70Measurement.publishableKcal(first, misread, elapsedSeconds = 60.0),
        )
    }
}
