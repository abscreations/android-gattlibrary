package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

/**
 * One canonical metric derived from a Keiser advertisement, in FitBridge units.
 *
 * @param freshnessMs the firmware's statistics window, carried on every sample so fusion expires
 * this bike on its own broadcast interval rather than on a source-class default. See
 * [MetricSample.freshnessMs].
 */
internal data class KeiserSample(
    val metric: MetricKind,
    val value: Double,
    val nativeCumulative: Double? = null,
    val freshnessMs: Long,
)

/**
 * Map one filtered Keiser reading to canonical metrics (plan §6.3).
 *
 * ## Non-realtime produces nothing at all
 *
 * A review-mode frame is a replay of a ride that has already finished. Returning an empty list is
 * the whole rejection: there is no partial emission, no "stale" marking and no state to touch,
 * because a review frame must not refresh liveness either. That last part is the source's job in
 * phase 11.5, and this function returning nothing is what makes the two rules impossible to get
 * out of step — a caller cannot emit from a review frame even by mistake.
 *
 * ## Zero heart rate is absence; zero cadence and zero power are readings
 *
 * The corpus contains long metric-unit sessions with the heart-rate field flat at zero: no strap
 * paired. Zero is dropped here rather than left to [PlausibilityBounds], whose heart-rate floor of
 * 1.0 would also reject it but would arm `CaptureTrigger.PARSER_REJECTION` while doing so, filing
 * the most ordinary traffic a bike produces as a decoding anomaly. Precor draws the same line for
 * the same reason.
 *
 * Cadence and power are the opposite case and are emitted at zero. After [KeiserDropoutFilter] has
 * run, a zero in either field is a rider who has stopped, and suppressing it would leave the last
 * moving value on screen until it expired — showing motion that has ended.
 *
 * ## Distance and energy carry native cumulative counters
 *
 * Both are totals the bike accumulates itself, so they are published with `nativeCumulative` set
 * and fusion rebases them across source generations. That is what keeps a session's distance
 * continuous when a bike drops out for a minute and comes back with its own counter still
 * climbing — and, equally, when the counter resets because the rider started a new ride on the
 * console.
 *
 * ## Elapsed is emitted; the plan asks for it and fusion already ranks it
 *
 * Precor deliberately withholds machine elapsed time, but that is a per-family judgement rather
 * than a rule: FTMS and FE-C both publish it, fusion ranks `VENDOR_NATIVE` second for
 * [MetricKind.ELAPSED] behind FTMS and ahead of the session clock, and plan §3.3 lists it as a
 * Keiser output. So it is emitted, and it is emitted *only* as a metric — it is not also carried
 * as an event timestamp, because §3.5 forbids protocol time from touching freshness and giving it
 * a second channel is how it would eventually get there.
 *
 * ## What is deliberately not emitted
 *
 * **Speed.** The protocol has no speed field. Phase 11 does not derive one from distance, and a
 * downstream consumer that wants speed will find the field absent rather than estimated.
 *
 * **Resistance when the firmware does not carry it.** [KeiserMSeriesMeasurement.Reading.resistance]
 * is null on pre-`0x21` firmware, which means "the protocol said nothing", not "level zero".
 *
 * **Confidence.** Every sample here is `NATIVE` quality, but the numeric confidence belongs to the
 * accepted match rather than to the packet, and no matcher exists until phase 11.4. Rather than
 * invent a constant now, the source passes the matcher's confidence to `MetricEmitter.emit`, which
 * already takes one.
 *
 * **Anything writable.** No target, no capability, no control signal. The bike cannot receive.
 */
internal fun keiserSamples(reading: KeiserMSeriesMeasurement.Reading): List<KeiserSample> {
    if (!reading.isRealtime) return emptyList()

    val freshnessMs = reading.statsTimeoutMs
    val samples = mutableListOf<KeiserSample>()

    samples += KeiserSample(MetricKind.CADENCE, reading.cadenceRpm, freshnessMs = freshnessMs)
    samples += KeiserSample(MetricKind.POWER, reading.powerWatts.toDouble(), freshnessMs = freshnessMs)

    val energy = reading.energyKcal.toDouble()
    samples += KeiserSample(
        MetricKind.ENERGY,
        energy,
        nativeCumulative = energy,
        freshnessMs = freshnessMs,
    )

    samples += KeiserSample(
        MetricKind.DISTANCE,
        reading.distanceMetres,
        nativeCumulative = reading.distanceMetres,
        freshnessMs = freshnessMs,
    )

    samples += KeiserSample(
        MetricKind.ELAPSED,
        reading.elapsedSeconds.toDouble(),
        freshnessMs = freshnessMs,
    )

    reading.resistance?.let {
        samples += KeiserSample(MetricKind.RESISTANCE, it.toDouble(), freshnessMs = freshnessMs)
    }

    if (reading.heartRateBpm > 0.0) {
        samples += KeiserSample(MetricKind.HEART_RATE, reading.heartRateBpm, freshnessMs = freshnessMs)
    }

    return samples
}
