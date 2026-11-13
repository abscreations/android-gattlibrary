package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi

/**
 * Physically sensible ranges per metric, per activity persona — plan §1.5 item 5, §11b.
 *
 * ### Why this exists
 *
 * D25 ships roughly a dozen machine families that nobody involved has ever connected to, on
 * cross-project corroboration rather than hardware. §1.5 is blunt about what protects the user
 * in that arrangement: *"bounds checking is what actually protects a user from a mis-scaled
 * field in a family nobody could test."* Everything else in the evidence ladder is bookkeeping
 * around this file.
 *
 * It is also the resolution of divergence **V4**. `RscMeasurement` decodes `0xFFFF` speed
 * literally, because `0x2A53` defines no not-available sentinel and inventing one to match
 * TMIW would be the wrong kind of corroboration. That decision is only defensible if something
 * rejects the resulting 921.5859375 km/h, and this is that something — deliberately here rather
 * than as a magic constant in the decoder, so the rule is stated once and applies to every
 * family rather than to the one that prompted it.
 *
 * ### How the numbers were chosen
 *
 * **Every ceiling is a record, not a plausible session.** The purpose is to catch a mis-scaled
 * field — a factor of ten, a byte-order error, an unmasked sentinel — not to police athletic
 * performance. A bound that a real user can reach is a bug, because a dropped sample is harder
 * to notice than a wrong one: a wrong number is visible and arguable, a missing one just is not
 * there. Where a limit was uncertain the looser value was taken.
 *
 * Concretely, `SPEED` on `RUN` allows 45 km/h. No human sustains that — the 100 m world record
 * averages about 37 km/h — but a treadmill's declared belt speed, a foot pod's estimate during
 * a stumble, and a sprint interval all live below 45, while a `0xFFFF` sentinel (921 km/h), a
 * ten-times scaling error on a 10 km/h run (100 km/h) and a byte swap all live above it.
 *
 * ### What is deliberately unbounded
 *
 * Cumulative metrics — `DISTANCE`, `ENERGY`, `ELAPSED`, the counters — have no upper bound
 * here. Their plausible ceiling depends on session length rather than physiology, and dropping
 * one sample from a cumulative series does more damage than letting it through: the accumulator
 * works on deltas, so a gap is not neutral. They keep the existing non-negative check. Distance
 * has a separate rate-of-change/reset gate in `MetricFusion`, where continuity state is available;
 * that mechanism intentionally does not turn a session-dependent cumulative value into a fixed
 * ceiling here.
 *
 * `INCLINATION` and `RAMP_ANGLE` are signed and bounded both ways. `RESISTANCE` is a vendor
 * level with no shared meaning across machines and stays unbounded.
 */
@GattLibraryInternalApi
internal object PlausibilityBounds {

    /** An inclusive range, or null where a metric is deliberately not bounded above. */
    data class Range(val min: Double, val max: Double) {
        operator fun contains(value: Double): Boolean = value in min..max
    }

    /**
     * @return the bound for [metric] under [persona], or null when the metric is unbounded and
     * only the existing domain check applies.
     */
    fun rangeFor(metric: MetricKind, persona: FusionPersona): Range? = when (metric) {

        // ── heart rate ────────────────────────────────────────────────────────────────────
        // Persona-independent. The documented human maximum is around 300 bpm; 254 is already
        // the wire ceiling for a uint8 with 255 reserved. The floor stays at 1: zero is what a
        // strap reports when it has lost contact, and `projectStaleHeartRate` handles that as a
        // dropout rather than as a reading.
        MetricKind.HEART_RATE -> Range(1.0, 254.0)

        // ── speed ─────────────────────────────────────────────────────────────────────────
        // RUN: 45 km/h against a 37 km/h sprint record. RIDE: 130 km/h — descending records sit
        // near 100 and a trainer flywheel can overrun briefly. ROW/PADDLE: 40 km/h, generous
        // against a racing eight's 22.
        MetricKind.SPEED, MetricKind.AVERAGE_SPEED -> when (persona) {
            FusionPersona.RUN -> Range(0.0, 45.0)
            FusionPersona.RIDE -> Range(0.0, 130.0)
            FusionPersona.ROW_PADDLE -> Range(0.0, 40.0)
        }

        // ── cadence ───────────────────────────────────────────────────────────────────────
        // RUN: 300 /min covers step-reporting devices (~200 at a sprint) and stride-reporting
        // ones alike, which matters while Q21 is open - FitBridge does not yet know which unit
        // a given device means. RIDE: 250 rpm against a track sprinter's ~140.
        MetricKind.CADENCE, MetricKind.AVERAGE_CADENCE, MetricKind.LAP_CADENCE_SAMPLE ->
            when (persona) {
                FusionPersona.RUN -> Range(0.0, 300.0)
                FusionPersona.RIDE -> Range(0.0, 250.0)
                FusionPersona.ROW_PADDLE -> Range(0.0, 120.0)
            }

        // Rowing stroke rate is its own metric; 60 /min is roughly double a racing rate.
        MetricKind.STROKE_RATE -> Range(0.0, 60.0)

        // ── power ─────────────────────────────────────────────────────────────────────────
        // §1.5 names this case directly: "a rower does not sustain 3000 W". Track sprinters
        // peak near 2500 W for a few seconds, so 3000 is above anything real and well below a
        // uint16 sentinel at 65535 or a ten-times scaling error.
        MetricKind.POWER, MetricKind.AVERAGE_POWER -> Range(0.0, 3000.0)

        // ── pace ──────────────────────────────────────────────────────────────────────────
        // The other half of the speed check, and the one that has already fired on real
        // hardware: 0.52 km/h during a walk-start transient became 115.3 min/km in session
        // 8af3. 60 min/km is a 1 km/h shuffle - slower than that is a stopped user, for whom
        // pace is undefined rather than enormous. The floor of 1.0 min/km corresponds to
        // 60 km/h and catches the same scaling errors from the other direction.
        MetricKind.INSTANT_PACE, MetricKind.AVERAGE_PACE -> Range(1.0, 60.0)

        // ── machine geometry ──────────────────────────────────────────────────────────────
        // Signed. Treadmill inclination spans roughly -10 % to +30 %; ±50 % is loose enough for
        // any machine and tight enough to catch a sign or scale error. Ramp angle in degrees.
        MetricKind.INCLINATION -> Range(-50.0, 50.0)
        MetricKind.RAMP_ANGLE -> Range(-45.0, 45.0)

        // ── energy rate and effort ────────────────────────────────────────────────────────
        // 5000 kcal/h is several times a world-class effort. METs cap at 30 against ~23 for
        // elite running.
        MetricKind.ENERGY_RATE -> Range(0.0, 5000.0)
        MetricKind.METABOLIC_EQUIVALENT -> Range(0.0, 30.0)

        // Treadmill belt force. 5000 N is far above a runner's ground reaction force.
        MetricKind.FORCE_ON_BELT -> Range(0.0, 5000.0)

        // ── deliberately unbounded ────────────────────────────────────────────────────────
        // Cumulative: ceiling depends on session length, and dropping one sample corrupts a
        // delta chain. Resistance: a vendor level with no shared meaning. Elevation gain:
        // cumulative in the same way.
        MetricKind.DISTANCE, MetricKind.ENERGY, MetricKind.ELAPSED,
        MetricKind.STROKE_COUNT, MetricKind.TOTAL_FLOORS, MetricKind.TOTAL_STEPS,
        MetricKind.TOTAL_STRIDES, MetricKind.RESISTANCE,
        MetricKind.ELEVATION_GAIN_POSITIVE, MetricKind.ELEVATION_GAIN_NEGATIVE,
        -> null
    }

    /** Whether [value] is plausible for [metric] under [persona]. Unbounded metrics always pass. */
    fun isPlausible(metric: MetricKind, persona: FusionPersona, value: Double): Boolean =
        rangeFor(metric, persona)?.contains(value) ?: true

    /** Human-readable bound for the rejection log, so a report says what was expected. */
    fun describe(metric: MetricKind, persona: FusionPersona): String =
        rangeFor(metric, persona)?.let { "${it.min}..${it.max} ${metric.unit}" } ?: "unbounded"
}
