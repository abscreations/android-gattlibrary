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
    COUNT,
    DEGREES,
    METABOLIC_EQUIVALENT,
    NEWTONS,
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
    STROKE_RATE(MetricUnit.STEPS_PER_MINUTE),
    STROKE_COUNT(MetricUnit.COUNT),
    METABOLIC_EQUIVALENT(MetricUnit.METABOLIC_EQUIVALENT),
    INCLINATION(MetricUnit.DEGREES),
    RAMP_ANGLE(MetricUnit.DEGREES),
    ELEVATION_GAIN_POSITIVE(MetricUnit.METERS),
    ELEVATION_GAIN_NEGATIVE(MetricUnit.METERS),
    FORCE_ON_BELT(MetricUnit.NEWTONS),
    TOTAL_FLOORS(MetricUnit.COUNT),
    TOTAL_STEPS(MetricUnit.COUNT),
    TOTAL_STRIDES(MetricUnit.COUNT),
    LAP_CADENCE_SAMPLE(MetricUnit.STEPS_PER_MINUTE),
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

/** Packet-scoped source binding. Every field decoded from one packet shares [arrivalMs]. */
internal class MetricEmitter(
    private val fusion: MetricFusion,
    val sourceId: MetricSourceId,
    val sourceClass: MetricSourceClass,
    val arrivalMs: Long,
) {
    fun emit(
        metric: MetricKind,
        value: Number,
        eventMs: Long? = null,
        nativeCumulative: Double? = if (metric.cumulative) value.toDouble() else null,
        quality: MetricQuality = MetricQuality.NATIVE,
        confidence: Double = 1.0,
    ): FusionSubmission = fusion.submit(
        MetricSample(
            connectionId = sourceId.connectionId,
            sourceId = sourceId,
            protocol = sourceId.protocol,
            sourceClass = sourceClass,
            metric = metric,
            arrivalMs = arrivalMs,
            eventMs = eventMs,
            value = value.toDouble(),
            nativeCumulative = nativeCumulative,
            quality = quality,
            confidence = confidence,
        ),
    )

    fun emitElevationGains(positiveMeters: Double?, negativeMeters: Double?) {
        fusion.submitElevationGains(this, positiveMeters, negativeMeters)
    }
}

/** Initial Phase 0.6a policy: newest arrival for each metric projects directly to engine state. */
internal class MetricFusion(
    private val state: BtEngineState,
    private val legacyInterceptor: ((MetricSample) -> Boolean)? = null,
    private val legacyObserver: ((MetricSample) -> Unit)? = null,
) {
    private val lock = Any()
    private val latestByMetric = mutableMapOf<MetricKind, MetricSample>()

    fun submit(sample: MetricSample): FusionSubmission = synchronized(lock) {
        val previous = latestByMetric[sample.metric]
        if (previous != null && sample.arrivalMs < previous.arrivalMs) {
            return@synchronized FusionSubmission.REJECTED_STALE
        }
        latestByMetric[sample.metric] = sample
        if (legacyInterceptor?.invoke(sample) != true) {
            projectPassThrough(sample)
            legacyObserver?.invoke(sample)
        }
        FusionSubmission.ACCEPTED
    }

    fun submitElevationGains(
        emitter: MetricEmitter,
        positiveMeters: Double?,
        negativeMeters: Double?,
    ) = synchronized(lock) {
        val positiveSample = positiveMeters?.let {
            emitterSample(emitter, MetricKind.ELEVATION_GAIN_POSITIVE, it)
        }
        val negativeSample = negativeMeters?.let {
            emitterSample(emitter, MetricKind.ELEVATION_GAIN_NEGATIVE, it)
        }
        positiveSample?.let { latestByMetric[it.metric] = it }
        negativeSample?.let { latestByMetric[it.metric] = it }
        state.setElevationGains(
            positiveSample?.value,
            negativeSample?.value,
            emitter.arrivalMs,
        )
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
                val watts = adjustedPower(sample)
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
                updateLapDistance(meters)
                if (sample.sourceId.characteristicUuid == BluetoothIds.UUID_ROWING_MACHINE_DATA) {
                    state.updateLapRowingSpeed(at)
                    state.updateRowingSpeed(at)
                }
            }
            MetricKind.ENERGY -> state.setExpEnergyTotal(value.roundToInt())
            MetricKind.ENERGY_RATE -> state.setExpEnergyRateKcalPerHour(value.roundToInt())
            MetricKind.INSTANT_PACE -> state.setInstantPace(value)
            MetricKind.AVERAGE_PACE -> state.setAvgPace(value)
            MetricKind.RESISTANCE -> state.appendResistanceSample(value, at)
            MetricKind.ELAPSED -> {
                val seconds = value.roundToInt()
                state.setElapsedSeconds(seconds)
                updateLapElapsed(seconds)
                state.strokeCount.value?.let(::updateLapStrokes)
            }
            MetricKind.STROKE_RATE -> state.appendStrokeRateSample(value, at)
            MetricKind.STROKE_COUNT -> {
                val strokes = value.roundToInt()
                state.setStrokeCount(strokes)
                updateLapStrokes(strokes)
            }
            MetricKind.METABOLIC_EQUIVALENT -> state.setMetabolicEquivalent(value)
            MetricKind.INCLINATION -> state.appendInclineDegSample(value, at)
            MetricKind.RAMP_ANGLE -> state.setRampAngle(value)
            MetricKind.ELEVATION_GAIN_POSITIVE -> state.setElevationGainPos(value)
            MetricKind.ELEVATION_GAIN_NEGATIVE -> state.setElevationGainNeg(value)
            MetricKind.FORCE_ON_BELT -> state.appendForceOnBeltSample(value.roundToInt(), at)
            MetricKind.TOTAL_FLOORS -> state.setTotalFloors(value.roundToInt())
            MetricKind.TOTAL_STEPS -> {
                val steps = value.roundToInt()
                state.setTotalSteps(steps)
                state.setLapSteps((steps - state.lapStartSteps).coerceAtLeast(0))
            }
            MetricKind.TOTAL_STRIDES -> state.setTotalStrides(value)
            MetricKind.LAP_CADENCE_SAMPLE -> updateLapCadence(value)
        }
    }

    private fun adjustedPower(sample: MetricSample): Int {
        val raw = sample.value.roundToInt()
        if (sample.protocol != MetricProtocol.FTMS || sample.quality != MetricQuality.NATIVE) {
            return raw
        }
        state.internalPowerSum += raw.toDouble()
        state.internalPowerCount += 1
        val reportedAverage = state.averagePower.value
        if (state.internalPowerCount <= 10 || reportedAverage == null) return raw
        val observedAverage = state.internalPowerSum / state.internalPowerCount.toDouble()
        if (observedAverage <= 0.0 || observedAverage + 2.0 >= reportedAverage.toDouble()) return raw
        return (raw * (reportedAverage.toDouble() / observedAverage)).toInt()
    }

    private fun updateLapDistance(totalMeters: Int) {
        val lapMeters = (totalMeters - state.lapStartDistanceMeters).coerceAtLeast(0)
        state.setLapDistanceMeters(lapMeters)
        state.setLapDistanceDisplay(
            if (state.units.value == 1) lapMeters * 0.000621371 else lapMeters / 1000.0,
        )
        recomputeLapDerived()
    }

    private fun updateLapElapsed(totalSeconds: Int) {
        state.setLapElapsedTimeSec((totalSeconds - state.lapStartElapsedSec).coerceAtLeast(0))
        recomputeLapDerived()
    }

    private fun updateLapStrokes(totalStrokes: Int) {
        val lapStrokes = (totalStrokes - state.lapStartStrokeCount).coerceAtLeast(0)
        state.setLapStrokeCount(lapStrokes)
        val lapSeconds = state.lapElapsedTimeSec.value
        if (lapSeconds != null && lapSeconds > 0) {
            state.setLapCadenceSpm(lapStrokes.toDouble() / (lapSeconds / 60.0))
        }
    }

    private fun updateLapCadence(value: Double) {
        state.lapAvgCadenceTotal += value
        state.lapAvgCadenceCount += 1
        state.setLapCadenceSpm(state.lapAvgCadenceTotal / state.lapAvgCadenceCount.toDouble())
    }

    private fun recomputeLapDerived() {
        val distance = state.lapDistanceMeters.value ?: return
        val seconds = state.lapElapsedTimeSec.value ?: return
        if (seconds <= 0) {
            state.setLapAvgSpeedMps(null)
            state.setLapAvgSpeedDisplay(null)
            state.setLapRunPaceMinPerUnit(null)
            state.setLapRowPaceMinPer500m(null)
            return
        }
        val metersPerSecond = distance.toDouble() / seconds.toDouble()
        state.setLapAvgSpeedMps(metersPerSecond)
        state.setLapAvgSpeedDisplay(
            if (state.units.value == 1) metersPerSecond * 2.23694 else metersPerSecond * 3.6,
        )
        val displayDistanceMeters = if (state.units.value == 1) 1_609.344 else 1_000.0
        state.setLapRunPaceMinPerUnit(
            if (metersPerSecond > 0.0) displayDistanceMeters / metersPerSecond / 60.0 else null,
        )
        state.setLapRowPaceMinPer500m(
            if (metersPerSecond > 0.0) 500.0 / metersPerSecond / 60.0 else null,
        )
    }

    private fun emitterSample(
        emitter: MetricEmitter,
        metric: MetricKind,
        value: Double,
    ): MetricSample = MetricSample(
        connectionId = emitter.sourceId.connectionId,
        sourceId = emitter.sourceId,
        protocol = emitter.sourceId.protocol,
        sourceClass = emitter.sourceClass,
        metric = metric,
        arrivalMs = emitter.arrivalMs,
        value = value,
    )
}
