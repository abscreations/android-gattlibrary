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
    val quality: MetricQuality = MetricQuality.NATIVE,
)

/** Total-distance average, using protocol elapsed only for protocol math (plan §3.5). */
internal fun keiserAverageSpeedKph(reading: KeiserMSeriesMeasurement.Reading): Double? {
    if (!reading.isRealtime || reading.elapsedSeconds <= 0) return null
    val speed = reading.distanceMetres * 3.6 / reading.elapsedSeconds.toDouble()
    return speed.takeIf { value ->
        value.isFinite() && value in requireNotNull(
            PlausibilityBounds.rangeFor(MetricKind.AVERAGE_SPEED, FusionPersona.RIDE),
        )
    }
}

/**
 * Stateful speed estimate for the Keiser's coarse cumulative distance counter.
 *
 * The wire advances in tenths of a kilometre or mile, so a short fixed-time window produces zeros
 * followed by spikes. This estimator instead spans up to three distinct distance increments. The
 * protocol's total-distance average is the startup fallback; a confirmed cadence-and-power stop is
 * zero immediately. Counter/time rollback and unit changes rebase the estimator.
 */
internal class KeiserSpeedEstimator {
    private data class Observation(val elapsedSeconds: Int, val distanceMetres: Double)

    private val distanceChanges = ArrayDeque<Observation>()
    private var previousElapsedSeconds: Int? = null
    private var previousDistanceMetres: Double? = null
    private var previousUnit: KeiserMSeriesMeasurement.DistanceUnit? = null
    private var lastInstantaneousKph: Double? = null
    private var wasStopped = false

    fun estimateKph(reading: KeiserMSeriesMeasurement.Reading): Double? {
        if (!reading.isRealtime) return null

        val elapsedRollback = previousElapsedSeconds?.let { reading.elapsedSeconds < it } == true
        val distanceRollback = previousDistanceMetres?.let { reading.distanceMetres < it } == true
        val unitChanged = previousUnit?.let { reading.distanceUnit != it } == true
        if (elapsedRollback || distanceRollback || unitChanged) reset()

        val priorDistance = previousDistanceMetres
        val distanceChanged = priorDistance == null || reading.distanceMetres > priorDistance
        val stoppedNow = reading.cadenceRpm == 0.0 && reading.powerWatts == 0
        if (!stoppedNow && wasStopped) {
            lastInstantaneousKph = null
            wasStopped = false
        }
        if (distanceChanged && reading.distanceMetres > 0.0) {
            val observation = Observation(reading.elapsedSeconds, reading.distanceMetres)
            if (distanceChanges.lastOrNull()?.elapsedSeconds == observation.elapsedSeconds) {
                distanceChanges.removeLast()
            }
            distanceChanges.addLast(observation)
            while (distanceChanges.size > MAX_DISTANCE_OBSERVATIONS) distanceChanges.removeFirst()

            val first = distanceChanges.first()
            val last = distanceChanges.last()
            val elapsedDelta = last.elapsedSeconds - first.elapsedSeconds
            val distanceDelta = last.distanceMetres - first.distanceMetres
            if (elapsedDelta > 0 && distanceDelta > 0.0) {
                val candidate = distanceDelta * 3.6 / elapsedDelta.toDouble()
                val bounds = requireNotNull(
                    PlausibilityBounds.rangeFor(MetricKind.SPEED, FusionPersona.RIDE),
                )
                if (candidate.isFinite() && candidate in bounds) {
                    lastInstantaneousKph = candidate
                }
            }
        }

        previousElapsedSeconds = reading.elapsedSeconds
        previousDistanceMetres = reading.distanceMetres
        previousUnit = reading.distanceUnit

        if (stoppedNow) {
            distanceChanges.clear()
            if (reading.distanceMetres > 0.0) {
                distanceChanges.addLast(Observation(reading.elapsedSeconds, reading.distanceMetres))
            }
            lastInstantaneousKph = 0.0
            wasStopped = true
            return 0.0
        }
        if (lastInstantaneousKph == null && reading.distanceMetres > 0.0) {
            lastInstantaneousKph = keiserAverageSpeedKph(reading)
        }
        return lastInstantaneousKph
    }

    fun reset() {
        distanceChanges.clear()
        previousElapsedSeconds = null
        previousDistanceMetres = null
        previousUnit = null
        lastInstantaneousKph = null
        wasStopped = false
    }

    private companion object {
        const val MAX_DISTANCE_OBSERVATIONS = 4
    }
}

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
 * ## Derived speed provenance
 *
 * The protocol has no speed field. [MetricKind.AVERAGE_SPEED] is derived directly from cumulative
 * distance and elapsed time. [MetricKind.SPEED] is supplied only when the caller provides the
 * stateful [estimatedSpeedKph] from [KeiserSpeedEstimator]. Both are explicitly `ESTIMATED`.
 *
 * **Resistance when the firmware does not carry it.** [KeiserMSeriesMeasurement.Reading.resistance]
 * is null on pre-`0x21` firmware, which means "the protocol said nothing", not "level zero".
 *
 * **Confidence.** Native and estimated samples share the accepted match's numeric confidence; it
 * belongs to the source identity rather than to the packet. The source passes it to
 * `MetricEmitter.emit` alongside each sample's explicit quality.
 *
 * **Anything writable.** No target, no capability, no control signal. The bike cannot receive.
 */
internal fun keiserSamples(
    reading: KeiserMSeriesMeasurement.Reading,
    estimatedSpeedKph: Double? = null,
): List<KeiserSample> {
    if (!reading.isRealtime) return emptyList()

    val freshnessMs = reading.statsTimeoutMs
    val samples = mutableListOf<KeiserSample>()

    estimatedSpeedKph?.let {
        samples += KeiserSample(
            MetricKind.SPEED,
            it,
            freshnessMs = freshnessMs,
            quality = MetricQuality.ESTIMATED,
        )
    }
    keiserAverageSpeedKph(reading)?.let {
        samples += KeiserSample(
            MetricKind.AVERAGE_SPEED,
            it,
            freshnessMs = freshnessMs,
            quality = MetricQuality.ESTIMATED,
        )
    }

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
