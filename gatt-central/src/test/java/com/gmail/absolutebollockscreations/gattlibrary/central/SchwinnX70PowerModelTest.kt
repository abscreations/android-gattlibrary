package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.SchwinnX70Spec

/**
 * The 270/570 power estimate — plan §7, §11b, and the D10 provenance rule.
 *
 * **What these tests are worth.** The coefficient table is third-party, unvalidated, and cannot be
 * checked against any capture in this repository: the 170 is the only console that reports power,
 * it is a different machine, and its resistance ranged over exactly two levels. So nothing here
 * asserts that the model is *right*. What it asserts is that the model is bounded, monotonic where
 * physics requires, never negative, and — the part that actually protects a user — that its output
 * loses to a native reading every time.
 */
class SchwinnX70PowerModelTest {

    @Test
    fun `the coefficient table is twenty-five rows and is not silently extensible`() {
        assertEquals(25, SchwinnX70Spec.RESISTANCE_POWER_COEFFICIENTS.size)
    }

    @Test
    fun `power never goes negative, which the raw polynomial does`() {
        // Every row's constant term is negative, so the floor is load-bearing rather than
        // defensive. Without it a slow pedal at high resistance publishes a negative wattage.
        for (resistance in 1..25) {
            for (cadence in 0..200) {
                val watts = SchwinnX70PowerModel.powerWatts(cadence, resistance)
                assertTrue("resistance $resistance at $cadence rpm gave $watts W", watts >= 0.0)
            }
        }

        // And the floor is genuinely reached rather than being unreachable belt-and-braces.
        assertEquals(0.0, SchwinnX70PowerModel.powerWatts(cadenceRpm = 0, resistance = 7), 1e-9)
    }

    @Test
    fun `power rises with cadence and with resistance across the usable range`() {
        // The two monotonicities a rider would notice immediately if they were inverted — which is
        // the realistic failure mode for a transcribed table, since a row pasted out of order
        // breaks exactly this and nothing else.
        for (resistance in 1..25) {
            var previous = -1.0
            for (cadence in 40..120 step 10) {
                val watts = SchwinnX70PowerModel.powerWatts(cadence, resistance)
                assertTrue("resistance $resistance is not monotonic in cadence", watts >= previous)
                previous = watts
            }
        }

        val atFixedCadence = (1..25).map { SchwinnX70PowerModel.powerWatts(90, it) }
        assertTrue(
            "harder resistance must not produce less power at the same cadence",
            atFixedCadence.first() < atFixedCadence.last(),
        )
    }

    @Test
    fun `every table row produces a plausible wattage at a realistic cadence`() {
        // §11b applied to a model rather than a parser. A transposed digit in the table would most
        // likely show up here as a value outside the bound, which is the only automatic check
        // available given there is nothing to validate the coefficients against.
        val bound = PlausibilityBounds.rangeFor(MetricKind.POWER, FusionPersona.RIDE)!!
        for (resistance in 1..25) {
            val watts = SchwinnX70PowerModel.powerWatts(cadenceRpm = 90, resistance = resistance)
            assertTrue("resistance $resistance at 90 rpm gave $watts W", watts in bound)
        }
    }

    @Test
    fun `resistance below the minimum is clamped rather than indexing backwards`() {
        // The console's minimum legal level is 1, but the wire can report 0 — the idle frame in the
        // captured corpus does exactly that. `(0 - 1) % 25` is -1 in Kotlin, which would throw.
        assertEquals(
            SchwinnX70PowerModel.powerWatts(90, 1),
            SchwinnX70PowerModel.powerWatts(90, 0),
            1e-9,
        )
        assertEquals(
            SchwinnX70PowerModel.powerWatts(90, 1),
            SchwinnX70PowerModel.powerWatts(90, -5),
            1e-9,
        )
    }

    // ── Damping (§7: named parameters, not inline literals) ──────────────────────

    @Test
    fun `an ordinary change passes through undamped`() {
        val previous = SchwinnX70PowerModel.DampedPower(120.0)
        assertEquals(150.0, SchwinnX70PowerModel.damp(previous, 150.0, 1.0).watts, 1e-9)
    }

    @Test
    fun `a jump beyond the threshold moves halfway`() {
        val previous = SchwinnX70PowerModel.DampedPower(100.0)
        val damped = SchwinnX70PowerModel.damp(previous, 600.0, 1.0)
        assertEquals(350.0, damped.watts, 1e-9)
        assertNotEquals("a damped value is not the raw estimate", 600.0, damped.watts, 1e-9)
    }

    @Test
    fun `a long gap restarts the estimate instead of ramping across it`() {
        // Damping across a two-minute pause would blend the end of one effort into the start of the
        // next and produce a ramp the rider never rode.
        val previous = SchwinnX70PowerModel.DampedPower(300.0)
        val afterGap = SchwinnX70PowerModel.damp(
            previous,
            estimate = 40.0,
            secondsSincePrevious = SchwinnX70PowerModel.BASELINE_RESET_SECONDS + 0.1,
        )
        assertEquals("the estimate restarts, undamped", 40.0, afterGap.watts, 1e-9)
    }

    @Test
    fun `the console tick counter could not have driven the gap check`() {
        // §8.5, with teeth. The main frame's own clock is a uint16 at 1/1024 s, so it wraps every
        // 64.0 seconds — which is *inside* the 63 s reset window. A pause of 65 s and a pause of
        // 1 s are indistinguishable on that clock, so the reset must use FitBridge's clock.
        val wrapSeconds = SchwinnX70Measurement.TIME_TICK_MODULUS / SchwinnX70Measurement.TICKS_PER_SECOND
        assertTrue(
            "the reset window ($wrapSeconds s wrap) must sit inside one wrap for this to matter",
            SchwinnX70PowerModel.BASELINE_RESET_SECONDS < wrapSeconds,
        )
    }

    // ── Speed from power (270 / 570 only) ────────────────────────────────────────

    @Test
    fun `the Cardano solution inverts the forward power model`() {
        // The only check available that does not depend on believing the constants: whatever speed
        // comes out must, put back through the forward equation, reproduce the wattage that went
        // in. That catches an arithmetic slip in the cubic without asserting the physics is right
        // for a stationary bike, which it is not and does not claim to be.
        val a = 0.5 * SchwinnX70PowerModel.FRONTAL_AREA_M2 *
            SchwinnX70PowerModel.DRAG_COEFFICIENT * SchwinnX70PowerModel.AIR_DENSITY_KG_PER_M3
        val c = SchwinnX70PowerModel.GRAVITY *
            (SchwinnX70PowerModel.DEFAULT_ATHLETE_MASS_KG + SchwinnX70PowerModel.BIKE_MASS_KG) *
            SchwinnX70PowerModel.ROLLING_RESISTANCE_COEFFICIENT

        for (watts in listOf(10.0, 50.0, 100.0, 200.0, 400.0, 800.0)) {
            val ms = SchwinnX70PowerModel.speedKphFromPower(watts) / 3.6
            val roundTrip = (c + a * ms * ms) * ms / SchwinnX70PowerModel.DRIVETRAIN_FRACTION
            assertEquals("$watts W must round-trip through the cubic", watts, roundTrip, 1e-6)
        }
    }

    @Test
    fun `zero and negative power give exactly zero speed`() {
        assertEquals(0.0, SchwinnX70PowerModel.speedKphFromPower(0.0), 0.0)
        assertEquals(0.0, SchwinnX70PowerModel.speedKphFromPower(-50.0), 0.0)
    }

    @Test
    fun `speed rises with power and stays inside its plausibility bound`() {
        val bound = PlausibilityBounds.rangeFor(MetricKind.SPEED, FusionPersona.RIDE)!!
        var previous = 0.0
        for (watts in 10..600 step 10) {
            val kph = SchwinnX70PowerModel.speedKphFromPower(watts.toDouble())
            assertTrue("$watts W gave $kph km/h, below the previous step", kph > previous)
            assertTrue("$watts W gave $kph km/h, outside the RIDE bound", kph in bound)
            previous = kph
        }
    }

    @Test
    fun `a heavier athlete needs more power for the same speed`() {
        // The one place the athlete's mass enters, and the direction is worth pinning because it is
        // easy to invert: mass raises rolling resistance, so at fixed power a heavier rider goes
        // slower. FitBridge already collects weight for the Life Fitness handshake, so this uses a
        // real value rather than the 80 kg default where one exists.
        val light = SchwinnX70PowerModel.speedKphFromPower(150.0, athleteMassKg = 60.0)
        val heavy = SchwinnX70PowerModel.speedKphFromPower(150.0, athleteMassKg = 110.0)
        assertTrue("a heavier rider is slower at the same wattage", heavy < light)
    }

    @Test
    fun `the whole 270 usable power range produces sane speeds`() {
        // End to end across the model: every resistance level at a realistic cadence, through the
        // coefficient table and then the cubic. This is the path a 270 rider's displayed speed
        // actually takes, and it is what the Explore the World session will be checked against.
        val bound = PlausibilityBounds.rangeFor(MetricKind.SPEED, FusionPersona.RIDE)!!
        for (resistance in 1..25) {
            val watts = SchwinnX70PowerModel.powerWatts(cadenceRpm = 90, resistance = resistance)
            val kph = SchwinnX70PowerModel.speedKphFromPower(watts)
            assertTrue("resistance $resistance gave $watts W -> $kph km/h", kph in bound)
        }
    }

    // ── D10 / §7 provenance: an estimate must never outrank a native reading ─────

    @Test
    fun `the quality enum is declared in the order the fusion comparator relies on`() {
        // **Renamed 2026-08-18, because the old name promised something this test does not do.**
        // It was called "a native power reading beats a modelled one regardless of source class",
        // which is §7's provenance obligation — and it asserts an enum's declaration order using a
        // helper defined in this file. It never touches MetricFusion. Reorder the real comparator
        // to put source class first and this test still passes.
        //
        // It is worth keeping for what it actually checks: MetricFusion.qualityRank maps these
        // constants by ordinal, so reordering the enum would silently invert the priority. That is
        // a real hazard and this is a cheap guard against it.
        //
        // The obligation itself is discharged by SchwinnX70FusionAndBoundsTest, which submits real
        // x70 samples to a real MetricFusion.
        val ranks = MetricQuality.entries.associateWith { quality ->
            MetricQuality.entries.count { other -> qualityOutranks(other, quality) }
        }
        assertTrue(
            "NATIVE must outrank both other qualities",
            ranks[MetricQuality.NATIVE] == 0,
        )
        assertTrue(
            "ESTIMATED must be outranked by both others",
            ranks[MetricQuality.ESTIMATED] == 2,
        )
        assertTrue(
            "DERIVED sits between them",
            ranks[MetricQuality.DERIVED] == 1,
        )
    }

    private fun qualityOutranks(candidate: MetricQuality, over: MetricQuality): Boolean =
        candidate.ordinal < over.ordinal

    @Test
    fun `the model is documented as estimated rather than merely believed to be`() {
        // A source-text contract, and a cheap one. The provenance claim that keeps this model from
        // outranking a 170's native reading lives in whatever the caller passes as `quality`, and
        // nothing in this file can enforce that. What it can do is refuse to let the coefficients'
        // origin become invisible.
        val source = java.io.File(
            "gatt-central/src/main/java/com/gmail/absolutebollockscreations/gattlibrary/central/" +
                "SchwinnX70PowerModel.kt",
        ).takeIf { it.isFile } ?: java.io.File(
            "src/main/java/com/gmail/absolutebollockscreations/gattlibrary/central/" +
                "SchwinnX70PowerModel.kt",
        )
        assertTrue("the model source is findable", source.isFile)
        // The coefficient table and its provenance moved to core (LIB-19c); the caveats stay here.
        val specSource = java.io.File(
            "gatt-core/src/main/java/com/gmail/absolutebollockscreations/gattlibrary/core/vendor/" +
                "SchwinnX70Spec.kt",
        ).takeIf { it.isFile } ?: java.io.File(
            "../gatt-core/src/main/java/com/gmail/absolutebollockscreations/gattlibrary/core/vendor/" +
                "SchwinnX70Spec.kt",
        )
        assertTrue("the spec source is findable", specSource.isFile)
        val text = source.readText() + specSource.readText()

        assertTrue("the third-party origin is stated", text.contains("ANT_Libraries"))
        assertTrue("the ESTIMATED consequence is stated", text.contains("MetricQuality.ESTIMATED"))
        assertTrue(
            "the native-reading adversary is stated",
            text.contains("sibling frame carries a measured power"),
        )
        assertTrue(
            "and the correction is not quietly dropped",
            text.contains("has been running on") && text.contains("170s since the handler shipped"),
        )
    }
}
