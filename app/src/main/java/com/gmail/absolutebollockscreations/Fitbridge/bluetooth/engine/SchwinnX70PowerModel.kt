package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.sqrt

/**
 * Estimated power for the Schwinn x70's **17-byte main frame** (plan §7).
 *
 * ## This exists for one stream, and it runs alongside a native reading rather than instead of one
 *
 * **Corrected 2026-08-18.** This file said it existed "for one console and must never reach the
 * other", on the reading that a 170 reports power natively while a 270/570 "emits only the 17-byte
 * main frame … and no power at all". Both halves are false. Captured QZ logs show every console
 * emitting all three streams, so a 170 produces main frames and **this model has been running on
 * 170s since the handler shipped** — while the same console reports power natively on its 14-byte
 * sibling frame, resolved as V15a and re-verified on 1,040 further frames across two machines.
 *
 * So the boundary is not between machines. It is between **streams**, and the two are live at once:
 * the sibling frame carries a measured power, the main frame carries a crank counter and a
 * resistance level from which one is inferred, and both arrive within about 100 ms of each other in
 * every session that has a main stream.
 *
 * **That makes `MetricQuality.ESTIMATED` load-bearing rather than precautionary.** Publishing a
 * modelled number where a native one exists is what the tag was introduced to prevent, and until
 * this correction the adversary was believed to be hypothetical — a 170 somewhere versus a 270
 * somewhere. It is neither hypothetical nor cross-device: it is the same device, twice a second.
 * Quality is the **first** sort key in the fusion comparator, ahead of source class, so the
 * estimate loses unconditionally; `SchwinnX70FusionAndBoundsTest` drives that through the real
 * comparator rather than asserting it about an enum's declaration order.
 *
 * ## The coefficients are third-party and their provenance is thin
 *
 * Twenty-five quadratic rows indexed by resistance, evaluated as `a·cadence² + b·cadence + c`.
 * TMIW adapted them from a third-party ANT library — **not from Schwinn, and not from a capture** —
 * and no second project corroborates them. They are transcribed here unchanged, as data rather than
 * as code, so that they can be replaced wholesale if a better source appears and so that no reader
 * mistakes them for a derived or fitted result.
 *
 * **What the captures can and cannot say about them, restated after the correction.** The sibling
 * stream gives real `(cadence, resistance, power)` triples on the *same console* that also emits the
 * main frame, which removes the "wrong machine" objection this paragraph used to rest on. It does
 * not make the check meaningful, and the reason is now sharper rather than weaker: across the two
 * QZ sessions resistance took the values 1–22 on one console and 4–6 on the other, but cadence and
 * resistance never varied independently enough to separate a row of the table from its neighbours,
 * and the console's own power is itself derived from speed (a log-log slope near 2.2), so checking a
 * model against it compares two derivations rather than a model against a measurement.
 *
 * A real validation needs a console readout or a calibrated meter. Neither exists here.
 *
 * ## The damping heuristics are TMIW's, ported as named parameters
 *
 * TMIW damps jumps and resets baselines with inline literals. §7 asks for them as documented,
 * testable parameters instead, which is what [DampedPower] provides. They are heuristics about a
 * *display*, not about the machine: their purpose is to stop a resistance change from producing a
 * visible step, and nothing about the protocol requires them.
 */
internal object SchwinnX70PowerModel {

    /**
     * Quadratic coefficients per resistance level: `a`, `b`, `c` for `a·rpm² + b·rpm + c`.
     *
     * Source: TMIW `schwinn_x70.dart`, adapted from
     * `ursoft/ANT_Libraries` `WROOM_esp32.ino`. Third-party, unverified, and the weakest evidence in
     * Phase 7 — recorded as such in §7 rather than smoothed over.
     */
    val RESISTANCE_POWER_COEFFICIENTS: List<Triple<Double, Double, Double>> = listOf(
        Triple(-0.0014, 0.7920, -13.985),
        Triple(-0.0004, 0.9991, -19.051),
        Triple(0.0015, 0.9179, -13.745),
        Triple(0.0040, 0.9857, -13.095),
        Triple(0.0027, 1.3958, -22.741),
        Triple(0.0057, 1.1586, -15.126),
        Triple(-0.0013, 2.4666, -49.052),
        Triple(0.0002, 2.6349, -52.390),
        Triple(0.0034, 2.6240, -48.072),
        Triple(0.0147, 1.6372, -19.653),
        Triple(0.0062, 2.5851, -43.254),
        Triple(0.0064, 3.2864, -59.336),
        Triple(0.0048, 3.6734, -69.245),
        Triple(0.0184, 2.1842, -28.936),
        Triple(0.0052, 4.3939, -78.603),
        Triple(0.0094, 3.8871, -65.982),
        Triple(0.0165, 3.3074, -49.906),
        Triple(0.0251, 3.2956, -44.436),
        Triple(0.0281, 2.9107, -38.767),
        Triple(0.0311, 2.9435, -35.851),
        Triple(0.0141, 5.5646, -88.686),
        Triple(0.0517, 1.8361, -13.777),
        Triple(0.0467, 2.9273, -35.908),
        Triple(0.0429, 4.1821, -50.141),
        Triple(0.0652, 3.6670, -46.863),
    )

    /** The minimum legal resistance the console reports. Level 1 selects the first row. */
    const val MINIMUM_RESISTANCE = 1

    /**
     * Estimate power in watts from cadence and resistance.
     *
     * Every row's constant term is negative, so at low cadence the polynomial goes below zero. The
     * floor at 0 is therefore load-bearing rather than defensive: at 20 rpm and resistance 7 the
     * raw value is about −0.1 W, and a negative power reaching a session would be worse than an
     * absent one.
     *
     * Resistance wraps with `% 25` because that is what TMIW does and a console reporting level 26
     * has never been observed. Wrapping is a guess either way; wrapping at least keeps a
     * hypothetical high level inside the table rather than throwing.
     */
    fun powerWatts(cadenceRpm: Int, resistance: Int): Double {
        val level = maxOf(resistance, MINIMUM_RESISTANCE)
        val (a, b, c) = RESISTANCE_POWER_COEFFICIENTS[(level - 1) % RESISTANCE_POWER_COEFFICIENTS.size]
        val raw = a * cadenceRpm * cadenceRpm + b * cadenceRpm + c
        return maxOf(raw, 0.0)
    }

    /**
     * TMIW's jump damping, above which a change is halved rather than applied.
     *
     * A resistance change moves the estimate to a different quadratic in one step, and 400 W is the
     * threshold TMIW chose for calling that a discontinuity. It has no protocol meaning.
     */
    const val JUMP_DAMPING_THRESHOLD_WATTS = 400.0

    /** Below this, a damped estimate is held at 1 W rather than reported as nothing. */
    const val DAMPED_FLOOR_WATTS = 1.0

    /**
     * Gap after which the estimate restarts rather than damping across the interruption.
     *
     * TMIW's 63 s, kept because the alternative is worse in a specific way: damping across a
     * two-minute pause would blend the end of one effort into the start of the next and produce a
     * ramp the rider never rode.
     */
    const val BASELINE_RESET_SECONDS = 63.0

    /**
     * Damping state for one link.
     *
     * Deliberately a value type with an explicit previous reading rather than a mutable field on a
     * handler: the estimate must not survive a link generation, and making the state something the
     * caller has to carry forward makes forgetting to reset it a visible omission.
     */
    data class DampedPower(val watts: Double) {
        companion object {
            /** The first estimate of a link, undamped — there is nothing yet to damp against. */
            fun first(estimate: Double) = DampedPower(estimate)
        }
    }

    /**
     * Apply the damping rule to a new estimate.
     *
     * @param previous the last damped value on this link, or null to start fresh.
     * @param secondsSincePrevious elapsed on FitBridge's clock. §8.5 — the console's own tick
     * counter wraps every 64 seconds, which is *inside* [BASELINE_RESET_SECONDS], so using it here
     * would make a long pause indistinguishable from a short one.
     */
    fun damp(previous: DampedPower?, estimate: Double, secondsSincePrevious: Double): DampedPower {
        if (previous == null || secondsSincePrevious > BASELINE_RESET_SECONDS) {
            return DampedPower.first(estimate)
        }
        val damped = if (abs(previous.watts - estimate) < JUMP_DAMPING_THRESHOLD_WATTS) {
            estimate
        } else {
            previous.watts + (estimate - previous.watts) / 2.0
        }
        return DampedPower(maxOf(damped, DAMPED_FLOOR_WATTS))
    }

    // ── Speed from power (270 / 570 only) ────────────────────────────────────────

    /**
     * Road-cycling constants behind [speedKphFromPower], ported from TMIW's `PowerSpeedMixin`,
     * which cites `gribble.org/cycling/power_v_speed.html`.
     *
     * **Read what these are before trusting the output.** They describe a rider on a bicycle moving
     * through air: frontal area, drag coefficient, air density, rolling resistance, drivetrain
     * loss. A Schwinn 270 does not move and has no air resistance. The number this produces is
     * therefore not the console's speed and not any speed the rider travelled — it is *the speed at
     * which a road cyclist would have to ride to produce the same wattage*, which is a real and
     * well-defined quantity, and is what indoor training apps conventionally display.
     *
     * That distinction is why the value ships as `MetricQuality.ESTIMATED` and why it is stacked on
     * top of an already-estimated power. **Pending validation against the official Explore the World
     * application once the x70 simulator persona exists** — the same loop that resolved four Life
     * Fitness personas. If that app disagrees, this is the first thing to change.
     *
     * The athlete's mass is taken from settings where available rather than assumed, since
     * FitBridge already collects it; everything else uses TMIW's defaults so that a disagreement
     * with Explore the World is attributable to the model rather than to the constants.
     */
    const val GRAVITY = 9.8067
    const val DEFAULT_ATHLETE_MASS_KG = 80.0
    const val BIKE_MASS_KG = 9.0
    const val ROLLING_RESISTANCE_COEFFICIENT = 0.005
    const val DRAG_COEFFICIENT = 0.63
    const val FRONTAL_AREA_M2 = 0.509
    const val AIR_DENSITY_KG_PER_M3 = 1.226
    const val DRIVETRAIN_FRACTION = 0.98
    private const val MS_TO_KPH = 3.6

    /**
     * Solve `power = (c + a·v²)·v / driveTrainFraction` for `v`, then convert to km/h.
     *
     * The depressed cubic `v³ + (c/a)·v = d/a` has one real root, obtained by Cardano's formula.
     * `kotlin.math.cbrt` handles negative arguments correctly, which is worth noting because TMIW
     * carries an explicit sign workaround here — Dart's `pow` rejects negative bases — and porting
     * that workaround would have been copying a language limitation rather than a protocol fact.
     *
     * Zero power gives zero speed exactly, with no division involved.
     */
    fun speedKphFromPower(powerWatts: Double, athleteMassKg: Double = DEFAULT_ATHLETE_MASS_KG): Double {
        if (powerWatts <= 0.0) return 0.0

        val a = 0.5 * FRONTAL_AREA_M2 * DRAG_COEFFICIENT * AIR_DENSITY_KG_PER_M3
        val c = GRAVITY * (athleteMassKg + BIKE_MASS_KG) * ROLLING_RESISTANCE_COEFFICIENT
        val q = c / (3.0 * a)
        val r = DRIVETRAIN_FRACTION * powerWatts / (2.0 * a)
        val e = sqrt(q * q * q + r * r)

        return (cbrt(r + e) + cbrt(r - e)) * MS_TO_KPH
    }
}
