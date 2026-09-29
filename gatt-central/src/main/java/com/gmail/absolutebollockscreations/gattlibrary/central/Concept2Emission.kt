package com.gmail.absolutebollockscreations.gattlibrary.central

/**
 * One canonical metric derived from a Concept2 frame, in FitBridge units.
 *
 * @param eventMs the frame's own elapsed time in milliseconds. This is protocol time, not
 * arrival time — fusion's freshness clock stays on arrival — and it exists because every
 * Concept2 characteristic carries the same elapsed-time prefix. It is what lets a power reading
 * from `0x0036` and a distance reading from `0x0031` be known to describe the same instant
 * rather than merely to have arrived together.
 */
internal data class Concept2Sample(
    val metric: MetricKind,
    val value: Double,
    val nativeCumulative: Double? = null,
    val eventMs: Long,
)

/**
 * Map a decoded frame to canonical metrics (plan §5a).
 *
 * The unit conversions are the whole substance of this function, and each is the kind of thing
 * that fails silently:
 *
 *  - **Speed** is metres per second on the wire and kilometres per hour in [MetricKind.SPEED].
 *  - **Pace** is seconds per 500 m on the wire and minutes per kilometre in
 *    [MetricKind.INSTANT_PACE] — a factor of two *and* a factor of sixty, in opposite
 *    directions. Getting one and not the other yields a plausible-looking number.
 *  - **Distance** and **energy** are cumulative, so they carry `nativeCumulative` for fusion's
 *    continuity logic rather than being treated as instantaneous.
 *
 * Heart rate is already filtered at decode: the specification names `255` invalid, so
 * [Concept2Measurement] drops it and it never reaches here.
 */
internal fun concept2Samples(reading: Concept2Measurement.Reading): List<Concept2Sample> {
    val eventMs = reading.elapsedCentiseconds * 10L
    val samples = mutableListOf<Concept2Sample>()

    reading.distanceMetres?.let {
        samples += Concept2Sample(MetricKind.DISTANCE, it, nativeCumulative = it, eventMs = eventMs)
    }
    reading.speedMetresPerSecond?.let {
        samples += Concept2Sample(MetricKind.SPEED, it * MPS_TO_KPH, eventMs = eventMs)
    }
    reading.strokeRatePerMinute?.let {
        samples += Concept2Sample(MetricKind.STROKE_RATE, it.toDouble(), eventMs = eventMs)
    }
    reading.heartRateBpm?.let {
        samples += Concept2Sample(MetricKind.HEART_RATE, it.toDouble(), eventMs = eventMs)
    }
    reading.paceSecondsPer500m?.let {
        // Zero pace means stopped, and there is no finite minutes-per-kilometre for it. Emitting
        // nothing keeps an absent pace visibly absent instead of publishing an infinity.
        if (it > 0.0) {
            samples += Concept2Sample(
                MetricKind.INSTANT_PACE,
                rowerSecondsPer500mToSecondsPerKm(it) / 60.0,
                eventMs = eventMs,
            )
        }
    }
    reading.totalCalories?.let {
        samples += Concept2Sample(
            MetricKind.ENERGY,
            it.toDouble(),
            nativeCumulative = it.toDouble(),
            eventMs = eventMs,
        )
    }
    reading.averagePowerWatts?.let {
        samples += Concept2Sample(MetricKind.AVERAGE_POWER, it.toDouble(), eventMs = eventMs)
    }
    reading.strokePowerWatts?.let {
        samples += Concept2Sample(MetricKind.POWER, it.toDouble(), eventMs = eventMs)
    }

    return samples
}

private const val MPS_TO_KPH = 3.6
