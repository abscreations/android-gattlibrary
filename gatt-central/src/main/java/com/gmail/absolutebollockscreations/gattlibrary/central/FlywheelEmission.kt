package com.gmail.absolutebollockscreations.gattlibrary.central

/** One native or explicitly synthetic metric produced from a valid Flywheel notification. */
internal data class FlywheelSample(
    val metric: MetricKind,
    val value: Double,
    val sourceClass: MetricSourceClass,
    val quality: MetricQuality,
    val cumulative: Double? = null,
)

/**
 * Flywheel exposes native power and cadence only. The additional motion fields are explicitly
 * synthetic under D97 and use no unknown bytes from the proprietary frame.
 */
internal class FlywheelSyntheticMetrics {
    private var previousArrivalMs: Long? = null
    private var distanceMetres = 0.0
    private var integratedMs = 0L

    fun samples(
        reading: FlywheelMeasurement.Reading,
        arrivalMs: Long,
        athleteMassKg: Double,
    ): List<FlywheelSample> {
        val speedKph = SchwinnX70PowerModel.speedKphFromPower(
            reading.powerWatts.toDouble(),
            athleteMassKg,
        )
        previousArrivalMs?.let { previous ->
            val deltaMs = arrivalMs - previous
            if (deltaMs in 1..MAX_INTEGRATION_GAP_MS) {
                distanceMetres += speedKph / 3.6 * deltaMs / 1_000.0
                integratedMs += deltaMs
            }
        }
        previousArrivalMs = arrivalMs
        val averageSpeedKph = if (integratedMs > 0L) {
            distanceMetres * 3_600.0 / integratedMs
        } else {
            speedKph
        }

        return listOf(
            FlywheelSample(
                MetricKind.CADENCE,
                reading.cadenceRpm.toDouble(),
                MetricSourceClass.VENDOR_NATIVE,
                MetricQuality.NATIVE,
            ),
            FlywheelSample(
                MetricKind.POWER,
                reading.powerWatts.toDouble(),
                MetricSourceClass.VENDOR_NATIVE,
                MetricQuality.NATIVE,
            ),
            FlywheelSample(
                MetricKind.SPEED,
                speedKph,
                MetricSourceClass.DERIVED,
                MetricQuality.ESTIMATED,
            ),
            FlywheelSample(
                MetricKind.AVERAGE_SPEED,
                averageSpeedKph,
                MetricSourceClass.DERIVED,
                MetricQuality.ESTIMATED,
            ),
            FlywheelSample(
                MetricKind.DISTANCE,
                distanceMetres,
                MetricSourceClass.INTEGRATED,
                MetricQuality.ESTIMATED,
                cumulative = distanceMetres,
            ),
        )
    }

    /** Run Mode owns workout totals even when the physical Flywheel link stays connected. */
    fun resetSession() {
        previousArrivalMs = null
        distanceMetres = 0.0
        integratedMs = 0L
    }

    private companion object {
        /** Matches fusion's generic machine freshness window; a stale gap never becomes travel. */
        const val MAX_INTEGRATION_GAP_MS = 3_000L
    }
}
