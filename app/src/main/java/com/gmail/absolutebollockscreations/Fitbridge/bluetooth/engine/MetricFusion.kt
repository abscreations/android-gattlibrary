package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import com.gmail.absolutebollockscreations.Fitbridge.HrZoneMode
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

    /** Grade, as used by FTMS Inclination. Not an angle. */
    PERCENT,
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
    INCLINATION(MetricUnit.PERCENT),
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

/** Wall-clock time used only for UI series that are rendered against epoch time. */
internal fun interface MetricSeriesClock {
    fun nowMs(): Long

    companion object {
        val SYSTEM = MetricSeriesClock(System::currentTimeMillis)
    }
}

internal enum class FusionSubmission { ACCEPTED, REJECTED_STALE, REJECTED_INVALID }

internal enum class FusionPersona { RIDE, RUN, ROW_PADDLE }

private data class MetricCandidateKey(
    val sourceId: MetricSourceId,
    val sourceClass: MetricSourceClass,
    val quality: MetricQuality,
)

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

/**
 * Session-scoped, per-metric source selection. Protocol time is never consulted here: source
 * freshness and all tie-breaking recency use monotonic host [MetricSample.arrivalMs] only.
 */
internal class MetricFusion(
    private val state: BtEngineState,
    private val clock: MonotonicMetricClock = MonotonicMetricClock.SYSTEM,
    private val seriesClock: MetricSeriesClock = MetricSeriesClock.SYSTEM,
) {
    private val lock = Any()
    private val candidates = mutableMapOf<MetricKind, MutableMap<MetricCandidateKey, MetricSample>>()
    private val selectedByMetric = mutableMapOf<MetricKind, MetricSample>()
    private val manualAssignments = mutableMapOf<MetricKind, MetricSourceId>()
    private val cumulativeTotals = mutableMapOf<MetricKind, Double>()
    private val cumulativeActiveSource = mutableMapOf<MetricKind, MetricSourceId>()
    private val cumulativeLastNative = mutableMapOf<MetricKind, Double>()
    private val heartRateFilters = mutableMapOf<MetricSourceId, HeartRateJumpEmaFilter>()
    private val distancePaceBuffer = ArrayDeque<Pair<Long, Double>>()
    private var persona: FusionPersona = FusionPersona.RIDE
    private var machineConnectionId: ConnectionId? = null

    fun submit(sample: MetricSample): FusionSubmission = synchronized(lock) {
        if (!isDomainValid(sample)) return@synchronized FusionSubmission.REJECTED_INVALID
        // §0.7a: every sample passes through here, so this is the one place that
        // sees a machine adapter proving it is alive. Deliberately before the
        // staleness check below - an out-of-order sample is still evidence the
        // device is talking, even if fusion has no use for its value.
        state.adapterLiveness.onSample(sample)
        val bySource = candidates.getOrPut(sample.metric) { mutableMapOf() }
        val candidateKey = sample.candidateKey()
        val previous = bySource[candidateKey]
        if (previous != null && sample.arrivalMs < previous.arrivalMs) {
            return@synchronized FusionSubmission.REJECTED_STALE
        }
        val filtered = filter(sample)
        bySource[candidateKey] = filtered
        reselect(filtered.metric, filtered.arrivalMs)
        FusionSubmission.ACCEPTED
    }

    fun submitElevationGains(
        emitter: MetricEmitter,
        positiveMeters: Double?,
        negativeMeters: Double?,
    ) {
        positiveMeters?.let { submit(emitterSample(emitter, MetricKind.ELEVATION_GAIN_POSITIVE, it)) }
        negativeMeters?.let { submit(emitterSample(emitter, MetricKind.ELEVATION_GAIN_NEGATIVE, it)) }
    }

    fun latestSample(metric: MetricKind): MetricSample? = synchronized(lock) {
        selectedByMetric[metric]
    }

    fun selectedSource(metric: MetricKind): MetricSourceId? = synchronized(lock) {
        selectedByMetric[metric]?.sourceId
    }

    fun manualAssignment(metric: MetricKind): MetricSourceId? = synchronized(lock) {
        manualAssignments[metric]
    }

    fun assign(metric: MetricKind, sourceId: MetricSourceId, nowMs: Long = clock.nowMs()) =
        synchronized(lock) {
            manualAssignments[metric] = sourceId
            reselect(metric, nowMs)
        }

    fun clearAssignment(metric: MetricKind, nowMs: Long = clock.nowMs()) = synchronized(lock) {
        manualAssignments.remove(metric)
        reselect(metric, nowMs)
    }

    fun setPersona(value: FusionPersona, nowMs: Long = clock.nowMs()) = synchronized(lock) {
        persona = value
        candidates.keys.toList().forEach { reselect(it, nowMs) }
    }

    fun currentPersona(): FusionPersona = synchronized(lock) { persona }

    fun selectMachine(connectionId: ConnectionId?) = synchronized(lock) {
        machineConnectionId = connectionId
    }

    fun selectedMachine(): ConnectionId? = synchronized(lock) { machineConnectionId }

    fun refresh(nowMs: Long = clock.nowMs()) = synchronized(lock) {
        candidates.keys.toList().forEach { reselect(it, nowMs) }
    }

    fun onDisconnected(connectionId: ConnectionId, nowMs: Long = clock.nowMs()) = synchronized(lock) {
        val affected = mutableSetOf<MetricKind>()
        candidates.forEach { (metric, bySource) ->
            if (bySource.keys.removeAll { it.sourceId.connectionId == connectionId }) affected += metric
        }
        manualAssignments.entries.removeAll { (metric, source) ->
            val clear = source.connectionId == connectionId
            if (clear) affected += metric
            clear
        }
        if (machineConnectionId == connectionId) machineConnectionId = null
        heartRateFilters.keys.removeAll { it.connectionId == connectionId }
        affected.forEach { reselect(it, nowMs) }
    }

    fun startSession(value: FusionPersona, nowMs: Long = clock.nowMs()) = synchronized(lock) {
        resetSessionState(clearMachine = false)
        persona = value
        state.setTotalDistanceMeters(0)
        state.setTotalDistanceDisplay(0.0)
        state.setExpEnergyTotal(0)
        updateSessionElapsedLocked(0, nowMs)
    }

    fun endSession() = synchronized(lock) {
        resetSessionState(clearMachine = true)
    }

    fun updateSessionElapsed(totalSeconds: Int, arrivalMs: Long = clock.nowMs()): FusionSubmission =
        synchronized(lock) {
            if (totalSeconds < 0 || arrivalMs < 0L) return@synchronized FusionSubmission.REJECTED_INVALID
            updateSessionElapsedLocked(totalSeconds, arrivalMs)
            FusionSubmission.ACCEPTED
        }

    private fun updateSessionElapsedLocked(totalSeconds: Int, arrivalMs: Long) {
        val source = SESSION_SOURCE
        val sample = MetricSample(
            connectionId = source.connectionId,
            sourceId = source,
            protocol = MetricProtocol.SESSION,
            sourceClass = MetricSourceClass.SESSION,
            metric = MetricKind.ELAPSED,
            arrivalMs = arrivalMs,
            value = totalSeconds.toDouble(),
        )
        candidates.getOrPut(MetricKind.ELAPSED) { mutableMapOf() }[sample.candidateKey()] = sample
        // The session clock is one candidate among the machine's own, not a forced projection.
        reselect(MetricKind.ELAPSED, arrivalMs)
    }

    private fun resetSessionState(clearMachine: Boolean) {
        selectedByMetric.keys.toList().forEach(::clearProjection)
        candidates.clear()
        selectedByMetric.clear()
        manualAssignments.clear()
        cumulativeTotals.clear()
        cumulativeActiveSource.clear()
        cumulativeLastNative.clear()
        heartRateFilters.clear()
        distancePaceBuffer.clear()
        if (clearMachine) machineConnectionId = null
    }

    fun recalculateHeartRateZones() = synchronized(lock) {
        publishHeartRateZones()
    }

    private fun filter(sample: MetricSample): MetricSample {
        if (sample.metric != MetricKind.HEART_RATE || sample.sourceClass != MetricSourceClass.HRM) {
            return sample
        }
        return heartRateFilters.getOrPut(sample.sourceId) { HeartRateJumpEmaFilter() }.filter(sample)
    }

    private fun MetricSample.candidateKey() = MetricCandidateKey(sourceId, sourceClass, quality)

    private fun reselect(metric: MetricKind, nowMs: Long) {
        val bySource = candidates[metric].orEmpty()
        val pinned = manualAssignments[metric]
        val winner = if (pinned != null) {
            bySource.values
                .asSequence()
                .filter { it.sourceId == pinned }
                .minWithOrNull(sampleComparator(metric))
        } else {
            bySource.values
                .asSequence()
                .filter { isFresh(it, nowMs) }
                .minWithOrNull(sampleComparator(metric))
        }
        val previous = selectedByMetric[metric]
        if (winner == null) {
            if (previous != null) {
                selectedByMetric.remove(metric)
                clearProjection(metric)
            }
            return
        }
        if (previous == winner) return
        selectedByMetric[metric] = winner
        if (metric.cumulative) projectCumulative(winner, previous?.sourceId) else projectPassThrough(winner)
    }

    private fun projectCumulative(sample: MetricSample, previousSource: MetricSourceId?) {
        val metric = sample.metric
        val native = sample.nativeCumulative ?: return
        val totalBefore = cumulativeTotals[metric]
        val activeSource = cumulativeActiveSource[metric]
        val canonical = when {
            totalBefore == null -> native
            activeSource != sample.sourceId || previousSource != sample.sourceId -> totalBefore
            native < (cumulativeLastNative[metric] ?: native) -> totalBefore
            else -> totalBefore + (native - (cumulativeLastNative[metric] ?: native))
        }
        cumulativeTotals[metric] = canonical
        cumulativeActiveSource[metric] = sample.sourceId
        cumulativeLastNative[metric] = native
        projectPassThrough(sample.copy(value = canonical, nativeCumulative = native))
    }

    private fun isFresh(sample: MetricSample, nowMs: Long): Boolean {
        if (sample.sourceClass == MetricSourceClass.SESSION) return true
        val age = nowMs - sample.arrivalMs
        return age >= 0L && age <= freshnessMs(sample.metric)
    }

    private fun freshnessMs(metric: MetricKind): Long =
        if (metric == MetricKind.HEART_RATE) HEART_RATE_FRESHNESS_MS else DEFAULT_FRESHNESS_MS

    private fun sampleComparator(metric: MetricKind): Comparator<MetricSample> =
        compareBy<MetricSample> { qualityRank(it.quality) }
            .thenBy { priorityRank(metric, it.sourceClass) }
            .thenByDescending { it.confidence }
            .thenByDescending { it.arrivalMs }
            .thenBy { it.sourceId.connectionId.address }
            .thenBy { it.sourceId.connectionId.linkGeneration }

    private fun qualityRank(quality: MetricQuality): Int = when (quality) {
        MetricQuality.NATIVE -> 0
        MetricQuality.DERIVED -> 1
        MetricQuality.ESTIMATED -> 2
    }

    private fun priorityRank(metric: MetricKind, source: MetricSourceClass): Int = when (metric) {
        MetricKind.HEART_RATE -> rank(source, MetricSourceClass.HRM, MetricSourceClass.FTMS, MetricSourceClass.VENDOR_NATIVE)
        MetricKind.POWER, MetricKind.AVERAGE_POWER -> when (persona) {
            FusionPersona.RIDE -> rank(source, MetricSourceClass.CPS, MetricSourceClass.FTMS, MetricSourceClass.VENDOR_NATIVE, MetricSourceClass.DERIVED)
            FusionPersona.RUN -> rank(source, MetricSourceClass.CPS, MetricSourceClass.FTMS)
            FusionPersona.ROW_PADDLE -> rank(source, MetricSourceClass.FTMS, MetricSourceClass.VENDOR_NATIVE)
        }
        MetricKind.CADENCE, MetricKind.AVERAGE_CADENCE -> when (persona) {
            FusionPersona.RIDE -> rank(source, MetricSourceClass.CPS_CRANK, MetricSourceClass.CSC_CRANK, MetricSourceClass.FTMS, MetricSourceClass.MOTION)
            FusionPersona.RUN -> rank(source, MetricSourceClass.RSC, MetricSourceClass.FTMS, MetricSourceClass.MOTION)
            FusionPersona.ROW_PADDLE -> rank(source, MetricSourceClass.FTMS, MetricSourceClass.VENDOR_NATIVE)
        }
        MetricKind.SPEED, MetricKind.AVERAGE_SPEED -> when (persona) {
            FusionPersona.RIDE -> rank(source, MetricSourceClass.FTMS, MetricSourceClass.CSC_WHEEL, MetricSourceClass.CPS_WHEEL, MetricSourceClass.DERIVED)
            FusionPersona.RUN -> rank(source, MetricSourceClass.RSC, MetricSourceClass.FTMS, MetricSourceClass.GPS)
            FusionPersona.ROW_PADDLE -> rank(source, MetricSourceClass.FTMS, MetricSourceClass.VENDOR_NATIVE)
        }
        MetricKind.DISTANCE -> when (persona) {
            FusionPersona.RIDE -> rank(source, MetricSourceClass.FTMS, MetricSourceClass.CSC_WHEEL, MetricSourceClass.CPS_WHEEL, MetricSourceClass.INTEGRATED)
            FusionPersona.RUN -> rank(source, MetricSourceClass.RSC, MetricSourceClass.FTMS, MetricSourceClass.INTEGRATED)
            FusionPersona.ROW_PADDLE -> rank(source, MetricSourceClass.FTMS, MetricSourceClass.INTEGRATED)
        }
        MetricKind.ENERGY -> rank(source, MetricSourceClass.HRM, MetricSourceClass.FTMS, MetricSourceClass.CPS)
        MetricKind.INSTANT_PACE, MetricKind.AVERAGE_PACE -> when (persona) {
            FusionPersona.RUN -> rank(source, MetricSourceClass.RSC, MetricSourceClass.FTMS, MetricSourceClass.GPS, MetricSourceClass.DERIVED)
            FusionPersona.ROW_PADDLE -> rank(source, MetricSourceClass.FTMS, MetricSourceClass.VENDOR_NATIVE, MetricSourceClass.DERIVED)
            FusionPersona.RIDE -> rank(source, MetricSourceClass.FTMS, MetricSourceClass.DERIVED)
        }
        // A machine's own elapsed time wins while it is fresh, so elapsed pauses when the
        // machine pauses. The session clock is the fallback and never goes stale, so a machine
        // that never reports elapsed still produces a running session time.
        MetricKind.ELAPSED -> rank(
            source,
            MetricSourceClass.FTMS,
            MetricSourceClass.VENDOR_NATIVE,
            MetricSourceClass.INTEGRATED,
            MetricSourceClass.SESSION,
        )
        else -> rank(source, MetricSourceClass.FTMS, MetricSourceClass.VENDOR_NATIVE, MetricSourceClass.MOTION, MetricSourceClass.DERIVED)
    }

    private fun rank(source: MetricSourceClass, vararg order: MetricSourceClass): Int {
        val index = order.indexOf(source)
        return if (index >= 0) index else 1_000 + source.ordinal
    }

    private fun isDomainValid(sample: MetricSample): Boolean = when (sample.metric) {
        MetricKind.HEART_RATE -> sample.value in 1.0..254.0
        MetricKind.INSTANT_PACE, MetricKind.AVERAGE_PACE -> sample.value > 0.0
        MetricKind.INCLINATION, MetricKind.RAMP_ANGLE, MetricKind.RESISTANCE -> true
        else -> sample.value >= 0.0
    }

    private fun clearProjection(metric: MetricKind) {
        when (metric) {
            MetricKind.HEART_RATE -> {
                state.setHeartRate(null)
                state.setExternalHeartRate(null)
                state.setHeartRateSource(BtEngineState.HrSource.UNKNOWN)
                publishHeartRateZones()
            }
            MetricKind.SPEED -> {
                state.setSpeedKph(null)
                state.setSpeedMph(null)
                state.setSpeedDisplay(null)
            }
            MetricKind.AVERAGE_SPEED -> {
                state.setAvgSpeedKph(null)
                state.setAvgSpeedDisplay(null)
            }
            MetricKind.CADENCE -> state.setInstantCadence(null)
            MetricKind.AVERAGE_CADENCE -> state.setAverageCadence(null)
            MetricKind.POWER -> state.setInstantPower(null)
            MetricKind.AVERAGE_POWER -> state.setAveragePower(null)
            MetricKind.ENERGY_RATE -> state.setExpEnergyRateKcalPerHour(null)
            MetricKind.INSTANT_PACE -> state.setInstantPace(null)
            MetricKind.AVERAGE_PACE -> state.setAvgPace(null)
            MetricKind.RESISTANCE -> state.setResistance(null)
            MetricKind.STROKE_RATE -> state.setStrokeRate(null)
            MetricKind.METABOLIC_EQUIVALENT -> state.setMetabolicEquivalent(null)
            MetricKind.RAMP_ANGLE -> state.setRampAngle(null)
            MetricKind.TOTAL_STRIDES -> state.setTotalStrides(null)
            MetricKind.DISTANCE, MetricKind.ENERGY, MetricKind.ELAPSED,
            MetricKind.STROKE_COUNT, MetricKind.INCLINATION,
            MetricKind.ELEVATION_GAIN_POSITIVE, MetricKind.ELEVATION_GAIN_NEGATIVE,
            MetricKind.FORCE_ON_BELT, MetricKind.TOTAL_FLOORS, MetricKind.TOTAL_STEPS,
            MetricKind.LAP_CADENCE_SAMPLE -> Unit
        }
    }

    private fun projectPassThrough(sample: MetricSample) {
        val arrivalAt = sample.arrivalMs
        val seriesAt = seriesClock.nowMs()
        val value = sample.value
        when (sample.metric) {
            MetricKind.HEART_RATE -> {
                val bpm = value.roundToInt()
                if (sample.sourceClass == MetricSourceClass.HRM) {
                    state.setExternalHeartRate(bpm)
                    state.updateLastExternalAt(arrivalAt)
                    state.setHeartRateSource(BtEngineState.HrSource.EXTERNAL_HRM)
                } else {
                    state.setHeartRateSource(BtEngineState.HrSource.UNKNOWN)
                }
                state.appendHeartRateSample(bpm, seriesAt)
                publishHeartRateZones()
            }

            MetricKind.SPEED -> {
                state.appendSpeedKphSample(value, seriesAt)
                state.setSpeedMph(value / 1.609344)
                state.setSpeedDisplay(if (state.units.value == 1) value / 1.609344 else value)
                val peak = maxOf(state.peakSpeedKph.value ?: value, value)
                state.setPeakSpeedKph(peak)
                state.setPeakSpeedMph(peak / 1.609344)
                state.setPeakSpeedDisplay(if (state.units.value == 1) peak / 1.609344 else peak)
                if (value > 0.0) emitDerivedPace(MetricKind.INSTANT_PACE, 60.0 / value, sample)
            }

            MetricKind.AVERAGE_SPEED -> {
                state.setAvgSpeedKph(value)
                state.setAvgSpeedDisplay(if (state.units.value == 1) value / 1.609344 else value)
                if (value > 0.0) emitDerivedPace(MetricKind.AVERAGE_PACE, 60.0 / value, sample)
            }

            MetricKind.CADENCE -> state.appendCadenceSpmSample(value, seriesAt)
            MetricKind.AVERAGE_CADENCE -> state.setAverageCadence(value)
            MetricKind.POWER -> {
                val watts = adjustedPower(sample)
                state.appendPowerSample(watts, seriesAt)
                state.setPeakPower(maxOf(state.peakPower.value ?: watts, watts))
            }
            MetricKind.AVERAGE_POWER -> state.setAveragePower(value.roundToInt())
            MetricKind.DISTANCE -> {
                val meters = value.roundToInt()
                state.setTotalDistanceMeters(meters)
                state.setTotalDistanceDisplay(
                    if (state.units.value == 1) meters * 0.000621371 else meters / 1000.0,
                )
                state.appendDistanceMetersSample(meters, seriesAt)
                updateLapDistance(meters)
                if (sample.sourceId.characteristicUuid == BluetoothIds.UUID_ROWING_MACHINE_DATA) {
                    state.updateLapRowingSpeed(seriesAt)
                    state.updateRowingSpeed(seriesAt)
                }
                updateDistanceDerivedPace(sample)
            }
            MetricKind.ENERGY -> state.setExpEnergyTotal(value.roundToInt())
            MetricKind.ENERGY_RATE -> state.setExpEnergyRateKcalPerHour(value.roundToInt())
            MetricKind.INSTANT_PACE -> state.setInstantPace(value)
            MetricKind.AVERAGE_PACE -> state.setAvgPace(value)
            MetricKind.RESISTANCE -> state.appendResistanceSample(value, seriesAt)
            MetricKind.ELAPSED -> {
                val seconds = value.roundToInt()
                state.setElapsedSeconds(seconds)
                updateLapElapsed(seconds)
                state.strokeCount.value?.let(::updateLapStrokes)
                updateAveragePaceFromSessionDistance(sample)
            }
            MetricKind.STROKE_RATE -> state.appendStrokeRateSample(value, seriesAt)
            MetricKind.STROKE_COUNT -> {
                val strokes = value.roundToInt()
                state.setStrokeCount(strokes)
                updateLapStrokes(strokes)
            }
            MetricKind.METABOLIC_EQUIVALENT -> state.setMetabolicEquivalent(value)
            MetricKind.INCLINATION -> state.appendInclinePercentSample(value, seriesAt)
            MetricKind.RAMP_ANGLE -> state.setRampAngle(value)
            MetricKind.ELEVATION_GAIN_POSITIVE -> state.setElevationGainPos(value)
            MetricKind.ELEVATION_GAIN_NEGATIVE -> state.setElevationGainNeg(value)
            MetricKind.FORCE_ON_BELT -> state.appendForceOnBeltSample(value.roundToInt(), seriesAt)
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

    private fun emitDerivedPace(metric: MetricKind, minutesPerKilometer: Double, base: MetricSample) {
        if (minutesPerKilometer <= 0.0 || !minutesPerKilometer.isFinite()) return
        submit(
            MetricSample(
                connectionId = base.connectionId,
                sourceId = base.sourceId,
                protocol = base.protocol,
                sourceClass = MetricSourceClass.DERIVED,
                metric = metric,
                arrivalMs = base.arrivalMs,
                eventMs = base.eventMs,
                value = minutesPerKilometer,
                quality = MetricQuality.DERIVED,
                confidence = DERIVED_PACE_CONFIDENCE,
            ),
        )
    }

    private fun updateDistanceDerivedPace(sample: MetricSample) {
        val at = sample.arrivalMs
        distancePaceBuffer.addLast(at to sample.value)
        val cutoff = at - DISTANCE_PACE_BUFFER_MS
        while (distancePaceBuffer.isNotEmpty() && distancePaceBuffer.first().first < cutoff) {
            distancePaceBuffer.removeFirst()
        }
        val target = at - DISTANCE_PACE_TARGET_MS
        val oldest = distancePaceBuffer.lastOrNull { it.first <= target }
            ?: distancePaceBuffer.firstOrNull()
        if (oldest != null) {
            val elapsedMs = at - oldest.first
            val deltaMeters = sample.value - oldest.second
            if (elapsedMs >= DISTANCE_PACE_MIN_MS && deltaMeters > 0.0) {
                val minutesPerKm = elapsedMs.toDouble() / 60_000.0 * 1_000.0 / deltaMeters
                emitDerivedPace(MetricKind.INSTANT_PACE, minutesPerKm, sample)
            }
        }
        updateAveragePaceFromSessionDistance(sample)
    }

    private fun updateAveragePaceFromSessionDistance(base: MetricSample) {
        val distanceMeters = state.totalDistanceMeters.value ?: return
        val elapsedSeconds = state.elapsedTimeSec.value ?: return
        if (distanceMeters <= 0 || elapsedSeconds <= 0) return
        val minutesPerKm = elapsedSeconds.toDouble() / 60.0 * 1_000.0 / distanceMeters.toDouble()
        emitDerivedPace(MetricKind.AVERAGE_PACE, minutesPerKm, base)
    }

    private fun publishHeartRateZones() {
        val mode = state.hrZoneModeFromApp?.value
        val max = state.hrMaxFromApp?.value
        val rest = state.restingHrFromApp?.value
        state.setHeartRateZone(state.heartRate.value?.let { computeHeartRateZone(it, mode, max, rest) })
        state.setAverageHeartRateZone(
            state.averageHeartRate.value?.let { computeHeartRateZone(it, mode, max, rest) },
        )
    }

    private fun computeHeartRateZone(
        bpm: Int,
        mode: HrZoneMode?,
        maxHeartRate: Int?,
        restingHeartRate: Int?,
    ): Int? {
        val ratio = when (mode) {
            null, HrZoneMode.Off -> return null
            HrZoneMode.MaxPercent -> {
                if (maxHeartRate == null || maxHeartRate <= 0) return null
                bpm.toDouble() / maxHeartRate.toDouble()
            }
            HrZoneMode.HeartRateReserve -> {
                if (maxHeartRate == null || restingHeartRate == null || maxHeartRate <= restingHeartRate) {
                    return null
                }
                (bpm - restingHeartRate).toDouble() / (maxHeartRate - restingHeartRate).toDouble()
            }
        }.coerceIn(0.0, 1.0)
        return when {
            ratio < 0.50 -> 0
            ratio < 0.60 -> 1
            ratio < 0.70 -> 2
            ratio < 0.80 -> 3
            ratio < 0.90 -> 4
            else -> 5
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

    private companion object {
        const val HEART_RATE_FRESHNESS_MS = 4_000L
        const val DEFAULT_FRESHNESS_MS = 3_000L
        const val DERIVED_PACE_CONFIDENCE = 0.75
        const val DISTANCE_PACE_MIN_MS = 5_000L
        const val DISTANCE_PACE_TARGET_MS = 10_000L
        const val DISTANCE_PACE_BUFFER_MS = 30_000L
        val SESSION_SOURCE = MetricSourceId(
            connectionId = ConnectionId("FITBRIDGE_SESSION", 0L),
            protocol = MetricProtocol.SESSION,
            characteristicUuid = UUID(0L, 0L),
        )
    }
}
