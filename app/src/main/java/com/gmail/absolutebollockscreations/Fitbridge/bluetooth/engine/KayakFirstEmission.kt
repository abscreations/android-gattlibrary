package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

/** One canonical, source-independent metric mapped from an accepted Kayak First reading. */
internal data class KayakFirstSample(
    val metric: MetricKind,
    val value: Double,
    val nativeCumulative: Double? = null,
    val sourceClass: MetricSourceClass = MetricSourceClass.VENDOR_NATIVE,
    val quality: MetricQuality = MetricQuality.NATIVE,
)

/**
 * Pure reading-to-sample mapping. A later connection handler supplies origin, endpoint, arrival
 * time, and confidence before submitting these values to fusion.
 *
 * Cadence intentionally remains [MetricKind.CADENCE] here: the captured console reaches 99 spm,
 * while the generic `STROKE_RATE` bound is 60. The downstream rower projection converts the
 * accepted row/paddle cadence to its stroke-rate field after the 120 spm source bound has run.
 */
internal fun kayakFirstSamples(reading: KayakFirstMeasurement.Reading): List<KayakFirstSample> =
    listOf(
        KayakFirstSample(
            metric = MetricKind.DISTANCE,
            value = reading.distanceMetres,
            nativeCumulative = reading.distanceMetres,
        ),
        KayakFirstSample(
            metric = MetricKind.SPEED,
            value = reading.speedKph,
        ),
        KayakFirstSample(
            metric = MetricKind.CADENCE,
            value = reading.cadenceSpm.toDouble(),
        ),
        KayakFirstSample(
            metric = MetricKind.POWER,
            value = reading.powerWatts.toDouble(),
        ),
        KayakFirstSample(
            metric = MetricKind.ELAPSED,
            value = reading.elapsedSeconds.toDouble(),
        ),
    )
