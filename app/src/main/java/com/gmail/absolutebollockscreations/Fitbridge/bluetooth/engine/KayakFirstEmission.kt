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
    buildList {
        add(KayakFirstSample(
            metric = MetricKind.DISTANCE,
            value = reading.distanceMetres,
            nativeCumulative = reading.distanceMetres,
        ))
        if (reading.activePaddling) add(KayakFirstSample(MetricKind.SPEED, reading.speedKph))
        add(KayakFirstSample(
            metric = MetricKind.AVERAGE_SPEED,
            value = reading.averageSpeedKph,
        ))
        if (reading.activePaddling) {
            add(KayakFirstSample(MetricKind.CADENCE, reading.cadenceSpm.toDouble()))
        }
        add(KayakFirstSample(
            metric = MetricKind.AVERAGE_CADENCE,
            value = reading.averageCadenceSpm.toDouble(),
        ))
        add(KayakFirstSample(
            metric = MetricKind.ELAPSED,
            value = reading.elapsedSeconds.toDouble(),
        ))
    }

internal fun kayakFirstEstimatedPowerSamples(
    powerWatts: Double,
    averagePowerWatts: Double,
): List<KayakFirstSample> = listOf(
    KayakFirstSample(
        MetricKind.POWER,
        powerWatts,
        sourceClass = MetricSourceClass.DERIVED,
        quality = MetricQuality.ESTIMATED,
    ),
    KayakFirstSample(
        MetricKind.AVERAGE_POWER,
        averagePowerWatts,
        sourceClass = MetricSourceClass.DERIVED,
        quality = MetricQuality.ESTIMATED,
    ),
)

internal fun kayakFirstEstimatedEnergySamples(
    estimate: KayakFirstEnergyEstimate,
): List<KayakFirstSample> = listOf(
    KayakFirstSample(
        MetricKind.ENERGY,
        estimate.totalKilocalories,
        nativeCumulative = estimate.totalKilocalories,
        sourceClass = MetricSourceClass.DERIVED,
        quality = MetricQuality.ESTIMATED,
    ),
    KayakFirstSample(
        MetricKind.ENERGY_RATE,
        estimate.kilocaloriesPerHour,
        sourceClass = MetricSourceClass.DERIVED,
        quality = MetricQuality.ESTIMATED,
    ),
)

/**
 * Supplies FTMS Rower Data's mandatory Stroke Count without assigning meaning to an unknown
 * Kayak First wire field. The count is the trapezoidal integral of the evidenced stroke-rate
 * reading over the console's own represented window (field 23 intersected with the field 22
 * elapsed delta) and is therefore explicitly derived. Inactive frames contribute nothing.
 */
internal class KayakFirstDerivedStrokeCounter {
    private var previousCadenceSpm = 0.0
    private var strokes = 0.0

    fun sample(
        reading: KayakFirstMeasurement.Reading,
        representedSeconds: Long,
    ): KayakFirstSample {
        if (reading.activePaddling && representedSeconds > 0L) {
            strokes += (previousCadenceSpm + reading.cadenceSpm) * 0.5 * representedSeconds / 60.0
        }
        previousCadenceSpm = if (reading.activePaddling) reading.cadenceSpm.toDouble() else 0.0
        val count = strokes.toInt().coerceAtLeast(0)
        return KayakFirstSample(
            metric = MetricKind.STROKE_COUNT,
            value = count.toDouble(),
            nativeCumulative = count.toDouble(),
            sourceClass = MetricSourceClass.DERIVED,
            quality = MetricQuality.DERIVED,
        )
    }

    fun reset() {
        previousCadenceSpm = 0.0
        strokes = 0.0
    }
}
