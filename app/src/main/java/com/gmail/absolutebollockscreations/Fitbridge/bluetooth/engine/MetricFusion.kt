package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import java.util.UUID
import kotlin.math.roundToInt

/** Protocol that produced a metric sample. */
internal enum class MetricProtocol { FTMS, HRM, RSC, CSC, CPS, VENDOR, MOTION, GPS, SESSION }

/** Source role used by fusion priority independently of protocol framing. */
internal enum class MetricSourceClass {
    HRM,
    FTMS,
    RSC,
    CSC_CRANK,
    CSC_WHEEL,
    CPS,
    CPS_CRANK,
    CPS_WHEEL,
    VENDOR_NATIVE,
    MOTION,
    GPS,
    DERIVED,
    INTEGRATED,
    SESSION,
}

internal enum class MetricQuality { NATIVE, DERIVED, ESTIMATED }

internal enum class MetricUnit {
    BPM,
    KILOMETERS_PER_HOUR,
    STEPS_PER_MINUTE,
    WATTS,
    METERS,
    KILOCALORIES,
    KILOCALORIES_PER_HOUR,
    MINUTES_PER_KILOMETER,
    LEVEL,
    SECONDS,
}

/** Canonical workout fields that can participate in source fusion. */
internal enum class MetricKind(val unit: MetricUnit, val cumulative: Boolean = false) {
    HEART_RATE(MetricUnit.BPM),
    SPEED(MetricUnit.KILOMETERS_PER_HOUR),
    AVERAGE_SPEED(MetricUnit.KILOMETERS_PER_HOUR),
    CADENCE(MetricUnit.STEPS_PER_MINUTE),
    AVERAGE_CADENCE(MetricUnit.STEPS_PER_MINUTE),
    POWER(MetricUnit.WATTS),
    AVERAGE_POWER(MetricUnit.WATTS),
    DISTANCE(MetricUnit.METERS, cumulative = true),
    ENERGY(MetricUnit.KILOCALORIES, cumulative = true),
    ENERGY_RATE(MetricUnit.KILOCALORIES_PER_HOUR),
    INSTANT_PACE(MetricUnit.MINUTES_PER_KILOMETER),
    AVERAGE_PACE(MetricUnit.MINUTES_PER_KILOMETER),
    RESISTANCE(MetricUnit.LEVEL),
    ELAPSED(MetricUnit.SECONDS),
}

/** Stable identity of one metric-producing characteristic for one physical link generation. */
internal data class MetricSourceId(
    val connectionId: ConnectionId,
    val protocol: MetricProtocol,
    val characteristicUuid: UUID,
)

/**
 * Source-tagged handler output. [arrivalMs] is monotonic host receipt time and is the only
 * freshness clock. [eventMs] is protocol time and is retained only for protocol math.
 */
internal data class MetricSample(
    val connectionId: ConnectionId,
    val sourceId: MetricSourceId,
    val protocol: MetricProtocol,
    val sourceClass: MetricSourceClass,
    val metric: MetricKind,
    val arrivalMs: Long,
    val eventMs: Long? = null,
    val value: Double,
    val unit: MetricUnit = metric.unit,
    val nativeCumulative: Double? = null,
    val quality: MetricQuality = MetricQuality.NATIVE,
    val confidence: Double = 1.0,
) {
    init {
        require(sourceId.connectionId == connectionId) { "Source and sample connection differ" }
        require(sourceId.protocol == protocol) { "Source and sample protocol differ" }
        require(arrivalMs >= 0L) { "Arrival time must be monotonic and non-negative" }
        require(value.isFinite()) { "Metric value must be finite" }
        require(unit == metric.unit) { "${metric.name} requires ${metric.unit}" }
        require(confidence.isFinite() && confidence in 0.0..1.0) {
            "Metric confidence must be within 0.0..1.0"
        }
        require(nativeCumulative == null || (nativeCumulative.isFinite() && nativeCumulative >= 0.0)) {
            "Native cumulative value must be finite and non-negative"
        }
        require(!metric.cumulative || nativeCumulative != null) {
            "${metric.name} requires its native cumulative counter"
        }
    }
}

internal fun interface MonotonicMetricClock {
    fun nowMs(): Long

    companion object {
        val SYSTEM = MonotonicMetricClock { System.nanoTime() / 1_000_000L }
    }
}

internal enum class FusionSubmission { ACCEPTED, REJECTED_STALE }

/** Initial Phase 0.6a policy: newest arrival for each metric projects directly to engine state. */
internal class MetricFusion(
    private val state: BtEngineState,
) {
    private val lock = Any()
    private val latestByMetric = mutableMapOf<MetricKind, MetricSample>()

    fun submit(sample: MetricSample): FusionSubmission = synchronized(lock) {
        val previous = latestByMetric[sample.metric]
        if (previous != null && sample.arrivalMs < previous.arrivalMs) {
            return@synchronized FusionSubmission.REJECTED_STALE
        }
        latestByMetric[sample.metric] = sample
        projectPassThrough(sample)
        FusionSubmission.ACCEPTED
    }

    fun latestSample(metric: MetricKind): MetricSample? = synchronized(lock) {
        latestByMetric[metric]
    }

    private fun projectPassThrough(sample: MetricSample) {
        val at = sample.arrivalMs
        val value = sample.value
        when (sample.metric) {
            MetricKind.HEART_RATE -> {
                val bpm = value.roundToInt()
                if (sample.sourceClass == MetricSourceClass.HRM) {
                    state.setExternalHeartRate(bpm)
                    state.updateLastExternalAt(at)
                    state.setHeartRateSource(BtEngineState.HrSource.EXTERNAL_HRM)
                } else {
                    state.setHeartRateSource(BtEngineState.HrSource.UNKNOWN)
                }
                state.appendHeartRateSample(bpm, at)
            }

            MetricKind.SPEED -> {
                state.appendSpeedKphSample(value, at)
                state.setSpeedMph(value / 1.609344)
                state.setSpeedDisplay(if (state.units.value == 1) value / 1.609344 else value)
                val peak = maxOf(state.peakSpeedKph.value ?: value, value)
                state.setPeakSpeedKph(peak)
                state.setPeakSpeedMph(peak / 1.609344)
                state.setPeakSpeedDisplay(if (state.units.value == 1) peak / 1.609344 else peak)
            }

            MetricKind.AVERAGE_SPEED -> {
                state.setAvgSpeedKph(value)
                state.setAvgSpeedDisplay(if (state.units.value == 1) value / 1.609344 else value)
            }

            MetricKind.CADENCE -> state.appendCadenceSpmSample(value, at)
            MetricKind.AVERAGE_CADENCE -> state.setAverageCadence(value)
            MetricKind.POWER -> {
                val watts = value.roundToInt()
                state.appendPowerSample(watts, at)
                state.setPeakPower(maxOf(state.peakPower.value ?: watts, watts))
            }
            MetricKind.AVERAGE_POWER -> state.setAveragePower(value.roundToInt())
            MetricKind.DISTANCE -> {
                val meters = value.roundToInt()
                state.setTotalDistanceMeters(meters)
                state.setTotalDistanceDisplay(
                    if (state.units.value == 1) meters * 0.000621371 else meters / 1000.0,
                )
                state.appendDistanceMetersSample(meters, at)
            }
            MetricKind.ENERGY -> state.setExpEnergyTotal(value.roundToInt())
            MetricKind.ENERGY_RATE -> state.setExpEnergyRateKcalPerHour(value.roundToInt())
            MetricKind.INSTANT_PACE -> state.setInstantPace(value)
            MetricKind.AVERAGE_PACE -> state.setAvgPace(value)
            MetricKind.RESISTANCE -> state.setResistance(value)
            MetricKind.ELAPSED -> state.setElapsedSeconds(value.roundToInt())
        }
    }
}
