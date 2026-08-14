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
 * ## Two consoles produce the same metrics by opposite routes
 *
 * A **170** measures speed and power and reports them on its 14-byte stream; cadence and heart rate
 * arrive on the paired 20-byte stream. A **270 / 570** measures a crank counter and a resistance
 * level and reports neither speed nor power, so both are modelled. The metric set a rider sees is
 * therefore similar and its provenance is not, which is the entire reason [SchwinnX70Sample] carries
 * a quality and `precorSamples` does not need to.
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
     * Metrics from a 170's paired frames.
     *
     * Speed and power are `NATIVE`: V15a resolved them against 698 captured frames, where they are
     * zero together in exactly 100 and never one without the other, and `power ≈ speed^1.565` with
     * a standard deviation of 0.042. The console derives power from speed internally, but that is
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
     * Metrics from a 270 / 570 main frame.
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
     * Cadence in rpm from two crank-counter readings.
     *
     * Returns null rather than zero when there is nothing to compute: a single frame, a
     * non-advancing clock, or a counter that went backwards past a plausible wrap. **Zero would be a
     * claim that the rider stopped**, and this function cannot tell that from having no information.
     *
     * The counter and the elapsed seconds are both the caller's, and the seconds must come from
     * FitBridge's clock — the 270's own tick field wraps every 64 seconds and cannot distinguish a
     * long pause from a short one.
     */
    fun cadenceRpm(previousCounter: Int, currentCounter: Int, elapsedSeconds: Double): Double? {
        if (elapsedSeconds <= 0.0) return null
        val revolutions = (currentCounter - previousCounter + 65536) % 65536
        // A counter that appears to have advanced most of its range in one interval is a wrap in
        // the wrong direction or a dropped frame, not a sprint.
        if (revolutions > 32768) return null
        return revolutions / elapsedSeconds * 60.0
    }
}
