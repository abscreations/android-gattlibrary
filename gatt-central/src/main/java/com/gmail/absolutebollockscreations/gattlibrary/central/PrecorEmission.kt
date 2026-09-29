package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi

/**
 * One canonical metric derived from a Precor Chrono frame, in FitBridge units.
 *
 * @param eventMs the machine's own elapsed time in milliseconds. Protocol time, used for
 * protocol math only; fusion's freshness clock stays on arrival time.
 */
@GattLibraryInternalApi
data class PrecorSample(
    val metric: MetricKind,
    val value: Double,
    val nativeCumulative: Double? = null,
    val eventMs: Long,
)

/**
 * Map a decoded Chrono frame to canonical metrics (plan §5b).
 *
 * ## Unit conversions are almost absent here, and that is the interesting part
 *
 * Speed arrives in 0.01 km/h and [MetricKind.SPEED] is km/h; cadence arrives in 0.1 rpm and
 * [MetricKind.CADENCE] is per-minute; distance is already metres and energy already kcal. The
 * scaling therefore lives entirely in [PrecorMeasurement], and nothing in this function can
 * introduce the factor-of-sixty class of error that `concept2Samples` has to be careful about.
 * Where Concept2 needed a comment per field explaining a conversion, this file needs one
 * explaining why there are none.
 *
 * ## Heart rate zero is absence, not a reading
 *
 * The Chrono reports `0` when no strap is paired — seven of TMIW's ten captured vectors do
 * exactly this, including two mid-session where the strap dropped and came back. Zero is
 * filtered here rather than left to [PlausibilityBounds], whose heart-rate floor of 1.0 would
 * also reject it: rejection there arms `CaptureTrigger.PARSER_REJECTION` and reads as a
 * decoding anomaly, when a machine saying "no strap" is the most ordinary traffic it produces.
 * That is the same distinction V9 drew for the FTMS `0x7FFF` sentinels, and the same reason
 * it is drawn at the emission boundary rather than in the bounds table.
 *
 * ## What is deliberately not emitted
 *
 * **Elapsed time.** §8.5 — a machine-reported elapsed time is not authoritative for a
 * FitBridge session, which may have started before this connection and may outlive it. It is
 * carried as `eventMs` so fusion can relate readings to one another, and no further.
 *
 * **Bytes 15–16.** [PrecorMeasurement] explains why the cumulative-work-in-kJ reading is
 * persuasive and still not shipped: one source, no ground truth, and no consumer.
 *
 * **Energy rate, average power, average speed, resistance, stroke count.** The frame carries
 * none of them. TMIW's descriptor sets `caloriesPerHourMetric`, `caloriesPerMinuteMetric` and
 * `strokeCountMetric` to null for this device, and its own tests assert that.
 */
@GattLibraryInternalApi
fun precorSamples(reading: PrecorMeasurement.Reading): List<PrecorSample> {
    val eventMs = reading.elapsedSeconds * 1_000L
    val samples = mutableListOf<PrecorSample>()

    samples += PrecorSample(MetricKind.SPEED, reading.speedKph, eventMs = eventMs)
    samples += PrecorSample(MetricKind.CADENCE, reading.cadenceRpm, eventMs = eventMs)
    samples += PrecorSample(MetricKind.POWER, reading.powerWatts.toDouble(), eventMs = eventMs)

    val distance = reading.distanceMetres.toDouble()
    samples += PrecorSample(
        MetricKind.DISTANCE,
        distance,
        nativeCumulative = distance,
        eventMs = eventMs,
    )

    val energy = reading.calories.toDouble()
    samples += PrecorSample(
        MetricKind.ENERGY,
        energy,
        nativeCumulative = energy,
        eventMs = eventMs,
    )

    if (reading.heartRateBpm > 0) {
        samples += PrecorSample(
            MetricKind.HEART_RATE,
            reading.heartRateBpm.toDouble(),
            eventMs = eventMs,
        )
    }

    return samples
}
