package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

/**
 * One canonical metric derived from a Schwinn x70 frame, in FitBridge units.
 *
 * @param quality the provenance that keeps a 270's modelled power from outranking a 170's native
 * reading. Fusion sorts on this **before** source class, so it is not advisory.
 * @param eventMs the machine's own elapsed time in milliseconds, where the frame carries one.
 * Protocol time only; fusion's freshness clock stays on arrival time.
 */
internal data class SchwinnX70Sample(
    val metric: MetricKind,
    val value: Double,
    val quality: MetricQuality,
    val nativeCumulative: Double? = null,
    val eventMs: Long? = null,
)

/**
 * Map decoded x70 frames to canonical metrics (plan §7).
 *
 * ## Two routes to the same metrics — per stream, not per console
 *
 * **Corrected 2026-08-18.** This said a *170* measures speed and power natively while a *270/570*
 * models both from a crank counter, as though the split were between machines. It is not: captured
 * QZ logs show every console emitting all three streams, so one machine can report speed natively
 * on its 14-byte frame *and* have speed modelled from its 17-byte frame in the same session.
 *
 * The split is between **streams**. The 14-byte sibling frame carries measured speed and power; the
 * 17-byte main frame carries a crank counter and a resistance level from which both are inferred.
 * Which of those a rider ends up seeing depends on which streams are live, and the main stream is
 * intermittent.
 *
 * That makes [SchwinnX70Sample]'s quality field more load-bearing than when it was written, not
 * less: `NATIVE` and `ESTIMATED` values for the same metric can now arrive from one device within
 * one second of each other, and fusion's quality ordering is the only thing keeping the model from
 * outranking the measurement.
 *
 * ## Cadence is the caller's, not this file's
 *
 * The 270's frame carries a **revolution counter**, not a rate. Turning it into rpm needs two frames
 * and the receive clock, so it is computed by the handler that holds link state and passed in here.
 * That split is deliberate: the one thing TMIW's disabled test corpus proves is that computing
 * cadence from a single frame produces nonsense, and a function that takes one frame has no honest
 * way to refuse.
 *
 * ## What is deliberately not emitted
 *
 * **Elapsed time.** §8.5 — carried as `eventMs` so fusion can relate readings, and no further.
 *
 * **The 170's two accumulators** (tags `0x3D`, `0x3E`) and **the 20-byte frame's own accumulator**
 * (tag `0x3C`). They co-vary at r = 0.9995 across 698 captured frames, so they are certainly
 * measuring the same thing, and nothing establishes what. Two correlated counters with no ground
 * truth are not a distance and not an energy total — the same reason Precor's bytes 15–16 stay
 * unpublished despite a persuasive kJ interpretation.
 *
 * **Resistance as a metric.** It feeds the 270's power model and is otherwise a control setting, not
 * a measurement of the rider.
 */
internal object SchwinnX70Emission {

    /**
     * Metrics from the paired 20/14-byte frames.
     *
     * Named for the 170 because that is the console the layout was resolved on. A 270 emits these
     * frames too (2026-08-18), so the name is history rather than a restriction.
     *
     * Speed and power are `NATIVE`: V15a resolved them against 698 captured frames, where they are
     * zero together in exactly 100 and never one without the other, and power tracks speed as a
     * tight power law with a log-log slope near 2.2 (unit-invariant, so the mph correction did not
     * disturb it). The console derives power from speed internally, but that is
     * the machine's own arithmetic and what its display shows, so it is a reading and not a
     * FitBridge estimate.
     *
     * @param cadenceRpm computed by the caller from the supplemental stream, or null before two
     * frames exist.
     */
    fun samplesFor170(
        sibling: SchwinnX70Measurement.Sibling,
        supplemental: SchwinnX70Measurement.Supplemental?,
        cadenceRpm: Double?,
    ): List<SchwinnX70Sample> = buildList {
        add(SchwinnX70Sample(MetricKind.SPEED, sibling.speedKph, MetricQuality.NATIVE))
        add(SchwinnX70Sample(MetricKind.POWER, sibling.powerWatts, MetricQuality.NATIVE))

        cadenceRpm?.let { add(SchwinnX70Sample(MetricKind.CADENCE, it, MetricQuality.DERIVED)) }

        // Zero is "no strap", not a reading of zero — the same distinction Precor draws, and for
        // the same reason: PlausibilityBounds would reject it as a decoding anomaly and arm a
        // diagnostic capture, when it is the most ordinary traffic the machine produces. All 698
        // captured frames say zero here, because no strap was paired.
        supplemental?.takeIf { it.heartRateBpm > 0 }?.let {
            add(SchwinnX70Sample(MetricKind.HEART_RATE, it.heartRateBpm.toDouble(), MetricQuality.NATIVE))
        }
    }

    /**
     * Metrics from the 17-byte main frame.
     *
     * Named for the 270/570 for the same historical reason as [samplesFor170]; a 170 emits this
     * frame as well.
     *
     * Power is `ESTIMATED` — a third-party coefficient table, no vendor source, no capture that
     * could check it. Speed is `ESTIMATED` too and sits on top of that estimate: D74's road-cycling
     * inversion, pending validation against Explore the World.
     *
     * @param cadenceRpm computed by the caller across frames; null suppresses power and speed
     * entirely rather than modelling them from an assumed cadence.
     * @param sessionEnergyKcal the guarded value from [SchwinnX70Measurement.publishableKcal], or
     * null when its rate said the scale could not be trusted.
     * @param athleteMassKg from settings where known.
     */
    fun samplesFor270(
        main: SchwinnX70Measurement.Main,
        cadenceRpm: Double?,
        dampedPower: SchwinnX70PowerModel.DampedPower?,
        sessionEnergyKcal: Double?,
        athleteMassKg: Double = SchwinnX70PowerModel.DEFAULT_ATHLETE_MASS_KG,
    ): List<SchwinnX70Sample> = buildList {
        val eventMs = (main.timeTicks / SchwinnX70Measurement.TICKS_PER_SECOND * 1000).toLong()

        cadenceRpm?.let {
            add(SchwinnX70Sample(MetricKind.CADENCE, it, MetricQuality.DERIVED, eventMs = eventMs))
        }

        // No cadence means no power: the model's only input is cadence and resistance, and
        // substituting a guess for the missing half would produce a number with the same shape as
        // a measurement. Before the second frame arrives a 270 reports nothing, which is correct.
        dampedPower?.let { power ->
            add(SchwinnX70Sample(MetricKind.POWER, power.watts, MetricQuality.ESTIMATED, eventMs = eventMs))
            add(
                SchwinnX70Sample(
                    MetricKind.SPEED,
                    SchwinnX70PowerModel.speedKphFromPower(power.watts, athleteMassKg),
                    MetricQuality.ESTIMATED,
                    eventMs = eventMs,
                ),
            )
        }

        sessionEnergyKcal?.let {
            add(
                SchwinnX70Sample(
                    MetricKind.ENERGY,
                    it,
                    MetricQuality.NATIVE,
                    nativeCumulative = it,
                    eventMs = eventMs,
                ),
            )
        }
    }

    /**
     * Below this, a counter that has not moved means "no reading yet", not "the rider stopped".
     *
     * One revolution at 20 rpm takes three seconds, and 20 rpm is about as slow as anyone turns a
     * crank under load. Four seconds is past that, so a counter still unchanged after it has
     * genuinely stopped rather than merely not ticked.
     */
    const val STOPPED_AFTER_SECONDS = 4.0

    /**
     * Cadence in rpm from two crank-counter readings.
     *
     * ## `elapsedSeconds` is time since the counter last **changed**, not since the last frame
     *
     * **This is the whole correction of 2026-08-18 and it lives in the caller, so it is stated
     * here where it can be seen.** The counter counts revolutions — about 1.3 a second at 80 rpm —
     * while the main stream notifies about ten times a second. Differentiating it frame to frame
     * therefore sees a delta of 0 or 1 across a ~100 ms window, and one revolution in 0.1 s is
     * 600 rpm.
     *
     * Measured across three captured slices and two physical consoles, that produced **88 % exactly
     * zero, about 12 % above 600 rpm, and one usable value in 744** — a rider watching cadence sit
     * at zero while the plausibility guard silently dropped the spikes. Power and speed inherit it,
     * because [samplesFor270] models both from this number.
     *
     * Measured between changes, the same captures give 74–115 rpm with a median of 81.4, matching
     * their 80.3 rpm session average.
     *
     * **Nothing caught it because every cadence test measured the session average** — first counter
     * to last counter across a whole run — which averages the defect away and says nothing about the
     * value a user sees.
     *
     * ## What it returns, and why absence is not zero
     *
     * Null when there is nothing to say: a non-advancing clock, a counter that went backwards past a
     * plausible wrap, or a counter that has not moved *yet*. Zero only once the counter has been
     * still for [STOPPED_AFTER_SECONDS], which is a genuine stop rather than the ordinary gap
     * between two revolutions.
     *
     * The seconds must come from FitBridge's clock — the console's own tick field wraps every 64
     * seconds and cannot distinguish a long pause from a short one.
     */
    fun cadenceRpm(previousCounter: Int, currentCounter: Int, elapsedSeconds: Double): Double? {
        if (elapsedSeconds <= 0.0) return null
        val revolutions = (currentCounter - previousCounter + 65536) % 65536
        // A counter that appears to have advanced most of its range in one interval is a wrap in
        // the wrong direction or a dropped frame, not a sprint.
        if (revolutions > 32768) return null
        if (revolutions == 0) {
            return if (elapsedSeconds >= STOPPED_AFTER_SECONDS) 0.0 else null
        }
        return revolutions / elapsedSeconds * 60.0
    }
}
