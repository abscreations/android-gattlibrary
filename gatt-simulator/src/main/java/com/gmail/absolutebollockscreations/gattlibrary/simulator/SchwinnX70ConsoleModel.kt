package com.gmail.absolutebollockscreations.gattlibrary.simulator

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.SchwinnX70Spec

/**
 * One coherent console state, from which all three streams are rendered.
 *
 * Every field a frame publishes lives here exactly once, which is what makes cross-stream agreement
 * true by construction rather than by two encoders happening to agree. The captured consoles carry
 * resistance on both the main frame's byte 16 and the supplemental frame's byte 18 and never
 * disagree beyond an adjacent value mid-change; a simulator emitting them from two sources would
 * have to work to reproduce that, and one emitting them from a single field cannot fail to.
 *
 * @param sequence the counter both paired frames carry, wrapping at 256. Shared, not two counters
 * that happen to match — that was verified on 4,409 captured frames of a second console.
 * @param speedMph the sibling frame's speed field, in miles per hour. See
 * `SchwinnX70Measurement.KM_PER_MILE` (central) for the evidence and for what would falsify it.
 * @param crankCounter revolutions, `uint16`, wrapping. **Not a rate.** The main frame carries this
 * and FitBridge differentiates it between changes, so it must advance at the real pedalling rate or
 * the central computes a cadence the ride never had.
 * @param clockTicks the console's own clock at 1/1024 s, wrapping every 64 seconds. The wrap is
 * reproduced rather than avoided: it is the edge that broke TMIW's test corpus.
 * @param energyRaw the `uint48` accumulator, pre-divisor.
 * @param siblingAccumulatorA cumulative energy in kilocalories (tag `0x3D`) — **the field Explore
 * the World displays as the ride's calorie total**, confirmed on a ten-minute capture.
 * @param siblingAccumulatorB cumulative distance at 100 units per unit of [speedMph]-hour, i.e. per
 * mile (tag `0x3E`).
 * @param supplementalAccumulator elapsed seconds of motion, not of connection (tag `0x3C`).
 */
@SimulatorProbeApi
data class SchwinnX70ConsoleState(
    val sequence: Int,
    val speedMph: Double,
    val powerWatts: Double,
    val cadenceRpm: Double,
    val crankCounter: Int,
    val clockTicks: Int,
    val energyRaw: Long,
    val resistance: Int,
    val heartRateBpm: Int,
    val siblingAccumulatorA: Int,
    val siblingAccumulatorB: Int,
    val supplementalAccumulator: Int,
)

/**
 * Renders one tick of the shared ride engine into the fields an x70 frame publishes.
 *
 * ## This used to generate its own ride, and no longer does
 *
 * Until 2026-08-19 each persona carried a captured peak speed and power, derived power from speed
 * through the corpus's `speed^1.565` fit, ramped resistance across that console's captured span and
 * accumulated its own energy. It was faithful to the captures and it was the wrong thing to build:
 * the engine's speed was rescaled per console, so one ride reached a client as three different
 * numbers and none of them was the number the simulator's own UI displayed. Every other persona
 * publishes what the engine generates. This one now does too.
 *
 * **The corpus still governs the octets.** §8.2's rule is unchanged — copy a captured frame,
 * overwrite only the fields whose meaning is established, leave the rest exactly as the console
 * sent it. What was dropped is capture-derived *values*, not capture-derived *structure*.
 *
 * ## Speed is published in miles per hour
 *
 * Explore the World reads this field and prints it unconverted as mph, whatever the phone's locale
 * or unit settings say. The simulator converts before encoding and
 * `SchwinnX70Measurement.parseSibling` (central) converts back on the way in, so FitBridge, TMIW and ETW all
 * describe the same ride — and the same conversion applies to a physical bike, which is the point.
 * That constant carries the evidence and the open question; this class just applies it.
 */
@SimulatorProbeApi
class SchwinnX70ConsoleModel {

    private var sequence = 0
    private var crankRevolutions = 0.0
    private var clockSeconds = 0.0
    private var movingSeconds = 0.0
    private var resistanceLevel = SchwinnX70Ride.RESISTANCE_FLOOR
    private var resistanceSeeded = false
    private var secondsSinceResistanceStep = 0.0

    /**
     * Advance by [seconds] and render the state a frame would carry.
     *
     * Everything here arrives from [FtmsMockSample]; nothing is modelled. The engine already keeps
     * power, cadence and heart rate coherent with speed, which is the property the old per-console
     * physics existed to provide and provided worse.
     *
     * @param speedKph the engine's speed, converted to mph on the way to the wire.
     * @param heartRateBpm zero when no strap is attached — absence, not a reading of zero.
     * @param energyKcal the engine's cumulative calorie total, published verbatim.
     * @param distanceMeters the engine's cumulative distance.
     */
    fun advance(
        seconds: Double,
        speedKph: Double,
        powerWatts: Double,
        cadenceRpm: Double,
        heartRateBpm: Int,
        energyKcal: Double,
        distanceMeters: Double,
    ): SchwinnX70ConsoleState {
        require(seconds >= 0.0)
        clockSeconds += seconds

        val speed = speedKph.coerceAtLeast(0.0)
        val moving = speed > 0.0
        // Zero together or neither: 2,996 captured frames, no exceptions. Emitting a power at zero
        // speed would break the one invariant this stream is most confidently known to hold, so the
        // engine's power is suppressed rather than published when the ride is stopped.
        val power = if (moving) powerWatts.coerceAtLeast(0.0) else 0.0
        val cadence = if (moving) cadenceRpm.coerceAtLeast(0.0) else 0.0

        crankRevolutions += cadence * seconds / 60.0
        advanceResistance(seconds, power, cadence)
        if (moving) movingSeconds += seconds

        val state = SchwinnX70ConsoleState(
            sequence = sequence,
            speedMph = speed / KM_PER_MILE,
            powerWatts = power,
            cadenceRpm = cadence,
            crankCounter = crankRevolutions.toInt() and 0xFFFF,
            clockTicks = (clockSeconds * TICKS_PER_SECOND).toLong().toInt() and 0xFFFF,
            energyRaw = (energyKcal.coerceAtLeast(0.0) * ENERGY_DIVISOR).roundToLong(),
            resistance = resistanceLevel,
            heartRateBpm = heartRateBpm.coerceIn(0, 0xFF),
            siblingAccumulatorA = energyKcal.toInt().coerceAtLeast(0) and 0xFFFF,
            siblingAccumulatorB =
                (distanceMeters.coerceAtLeast(0.0) / METRES_PER_MILE * SIBLING_DISTANCE_UNITS_PER_MILE)
                    .toInt() and 0xFFFF,
            supplementalAccumulator = movingSeconds.toInt() and 0xFFFF,
        )
        sequence = (sequence + 1) and 0xFF
        return state
    }

    /**
     * Choose the resistance level whose modelled power matches the ride the engine is generating.
     *
     * ## Why this is an inversion rather than a scale
     *
     * The main frame carries a resistance level, not a power. Every consumer that reads it —
     * FitBridge's own 270 path, TMIW, QZ — recovers power from `(cadence, resistance)` through
     * [SchwinnX70Spec.RESISTANCE_POWER_COEFFICIENTS]. So the level is not decoration: it *is*
     * the power, encoded. Publishing a level derived from anything else makes the main stream
     * contradict the sibling stream's measured power on the same tick.
     *
     * That is exactly what mapping the engine's own 3-12 band onto the family's 1-22 did, and it
     * was measured: at 88 rpm and 223 W the engine's level 9-11 mapped to 15-20, which the table
     * reads back as 348-464 W. TMIW showed 330-480 W, a speed of 43 km/h against 34, and a distance
     * 17% long — three symptoms, one cause, all of them downstream of the level.
     *
     * Inverting the table instead makes the two streams agree by construction, which is the same
     * argument that already makes the crank counter follow the sibling frame's speed.
     *
     * ## Why it moves one step at a time
     *
     * Resistance is a knob, not a measurement. The captured 270 held level 6 across 42 seconds and
     * the 170 walked 17 to 22 during a deliberate sweep; neither jitters tick to tick, and the
     * engine's power and cadence both carry noise that would make an unsmoothed inversion do so.
     * One step per second tracks a ramp without inventing a rider who cannot leave the knob alone.
     * The opening level is seeded directly so the first frames of a link are not 20 seconds of
     * wrong power.
     */
    private fun advanceResistance(seconds: Double, powerWatts: Double, cadenceRpm: Double) {
        secondsSinceResistanceStep += seconds
        if (cadenceRpm < MIN_CADENCE_FOR_RESISTANCE || powerWatts <= 0.0) return

        val rpm = cadenceRpm.roundToInt()
        val target = (SchwinnX70Ride.RESISTANCE_FLOOR..SchwinnX70Ride.RESISTANCE_CEILING)
            .minBy { level -> abs(powerWattsFor(rpm, level) - powerWatts) }

        if (!resistanceSeeded) {
            resistanceLevel = target
            resistanceSeeded = true
            secondsSinceResistanceStep = 0.0
            return
        }
        if (target == resistanceLevel || secondsSinceResistanceStep < RESISTANCE_STEP_SECONDS) return
        resistanceLevel += if (target > resistanceLevel) 1 else -1
        secondsSinceResistanceStep = 0.0
    }

    companion object {
        /** Below this the table's quadratics are not usable and the level is left where it is. */
        const val MIN_CADENCE_FOR_RESISTANCE = 20.0

        /** How often the simulated rider is willing to move the knob. */
        const val RESISTANCE_STEP_SECONDS = 1.0

        /** `SchwinnX70Measurement.TMIW_CALORIE_DIVISOR` (central), restated so the encoder needs no decoder. */
        const val ENERGY_DIVISOR = 2_097_152.0

        /** Kilometres per statute mile; the sibling frame carries mph (restated, not imported: L22). */
        const val KM_PER_MILE = 1.609344

        /**
         * Resistance level and cadence to watts, from the console's published quadratic table
         * (`core.vendor.SchwinnX70Spec.RESISTANCE_POWER_COEFFICIENTS`). An independent restatement of
         * central's model so the simulator needs no decoder-side type; floored at zero and wrapping
         * the level like the original.
         */
        private fun powerWattsFor(cadenceRpm: Int, resistance: Int): Double {
            val table = SchwinnX70Spec.RESISTANCE_POWER_COEFFICIENTS
            val (a, b, c) = table[(maxOf(resistance, 1) - 1) % table.size]
            return maxOf(a * cadenceRpm * cadenceRpm + b * cadenceRpm + c, 0.0)
        }

        /** The console's clock rate, 1/1024 s. */
        const val TICKS_PER_SECOND = 1024.0

        const val METRES_PER_MILE = 1609.344

        /**
         * Sibling accumulator B advances 100 units per unit of distance covered at the speed the
         * same frame reports.
         *
         * Measured, not assumed: integrating the sibling frame's own speed field across four
         * captured sessions on two consoles gives 100.5, 105.1, 101.0 and 100.0 — a 2.4% spread.
         * That derivation is unit-agnostic, so it fixes B *relative to the speed field* without
         * saying which unit either is in. Since speed is published in mph, B is published per mile,
         * and the two stay consistent on the wire whichever way Q12 resolves.
         */
        const val SIBLING_DISTANCE_UNITS_PER_MILE = 100.0
    }
}
