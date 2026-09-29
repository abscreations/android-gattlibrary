package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.core.FitBridgeBleInternalApi

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.core.HrZoneMode
import java.util.UUID
import kotlin.math.roundToInt
import timber.log.Timber

/**
 * How long a heart-rate reading stays authoritative after it arrives.
 *
 * Top-level rather than inside [MetricFusion]'s private companion because a second consumer needs
 * it: the simulator's live-strap override decides whether an attached strap is still reporting, and
 * that window must be the *same* window fusion uses rather than a copy that can drift away from it.
 * The rest of the companion's tuning constants stay private — widening the whole object to share one
 * value would invite every file to reach for the others.
 *
 * A BLE strap notifies about once a second, so this tolerates three missed notifications.
 */
@FitBridgeBleInternalApi
const val HEART_RATE_FRESHNESS_MS = 4_000L

/** Protocol that produced a metric sample. */
@FitBridgeBleInternalApi
enum class MetricProtocol { FTMS, HRM, RSC, CSC, CPS, VENDOR, MOTION, GPS, SESSION }

/** Source role used by fusion priority independently of protocol framing. */
@FitBridgeBleInternalApi
enum class MetricSourceClass {
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

@FitBridgeBleInternalApi
enum class MetricQuality { NATIVE, DERIVED, ESTIMATED }

@FitBridgeBleInternalApi
enum class MetricUnit {
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
@FitBridgeBleInternalApi
enum class MetricKind(val unit: MetricUnit, val cumulative: Boolean = false) {
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
    TOTAL_STRIDES(MetricUnit.COUNT, cumulative = true),
    LAP_CADENCE_SAMPLE(MetricUnit.STEPS_PER_MINUTE),
}

/** Lifetime that owns metric state. It is never synthesized across transport kinds. */
@FitBridgeBleInternalApi
sealed interface MetricOriginId {
    data class Gatt(val connectionId: ConnectionId) : MetricOriginId

    data class Advertisement(
        val sourceKey: String,
        val generation: Long,
    ) : MetricOriginId {
        init {
            require(sourceKey.isNotBlank()) { "Advertisement source key must not be blank" }
            require(generation >= 0L) { "Advertisement generation must be non-negative" }
        }
    }
}

/** Wire endpoint that produced a metric within an origin lifetime. */
@FitBridgeBleInternalApi
sealed interface MetricEndpointId {
    data class GattCharacteristic(val uuid: UUID) : MetricEndpointId

    data class ManufacturerData(val companyId: Int) : MetricEndpointId {
        init {
            require(companyId in 0..0xffff) { "Manufacturer company ID must fit uint16" }
        }
    }
}

/** Stable identity of one metric-producing endpoint within one origin generation. */
@FitBridgeBleInternalApi
data class MetricSourceId(
    val originId: MetricOriginId,
    val protocol: MetricProtocol,
    val endpointId: MetricEndpointId,
) {
    /** Compatibility constructor for existing GATT handlers and characterization fixtures. */
    constructor(
        connectionId: ConnectionId,
        protocol: MetricProtocol,
        characteristicUuid: UUID,
    ) : this(
        originId = MetricOriginId.Gatt(connectionId),
        protocol = protocol,
        endpointId = MetricEndpointId.GattCharacteristic(characteristicUuid),
    )

    val gattConnectionId: ConnectionId?
        get() = (originId as? MetricOriginId.Gatt)?.connectionId

    val gattCharacteristicUuid: UUID?
        get() = (endpointId as? MetricEndpointId.GattCharacteristic)?.uuid

    /** GATT-only compatibility accessor. Advertisement code must use [originId]. */
    val connectionId: ConnectionId
        get() = requireNotNull(gattConnectionId) { "Metric source is not GATT-backed" }

    /** GATT-only compatibility accessor. Advertisement code must use [endpointId]. */
    val characteristicUuid: UUID
        get() = requireNotNull(gattCharacteristicUuid) { "Metric endpoint is not a characteristic" }
}

/**
 * Source-tagged handler output. [arrivalMs] is monotonic host receipt time and is the only
 * freshness clock. [eventMs] is protocol time and is retained only for protocol math.
 */
@FitBridgeBleInternalApi
data class MetricSample(
    val originId: MetricOriginId,
    val sourceId: MetricSourceId,
    val protocol: MetricProtocol,
    val sourceClass: MetricSourceClass,
    val metric: MetricKind,
    val arrivalMs: Long,
    val freshnessMs: Long? = null,
    val eventMs: Long? = null,
    val value: Double,
    val unit: MetricUnit = metric.unit,
    val nativeCumulative: Double? = null,
    val quality: MetricQuality = MetricQuality.NATIVE,
    val confidence: Double = 1.0,
) {
    /** Compatibility constructor for existing GATT parser tests and direct sample producers. */
    constructor(
        connectionId: ConnectionId,
        sourceId: MetricSourceId,
        protocol: MetricProtocol,
        sourceClass: MetricSourceClass,
        metric: MetricKind,
        arrivalMs: Long,
        eventMs: Long? = null,
        value: Double,
        unit: MetricUnit = metric.unit,
        nativeCumulative: Double? = null,
        quality: MetricQuality = MetricQuality.NATIVE,
        confidence: Double = 1.0,
        freshnessMs: Long? = null,
    ) : this(
        originId = MetricOriginId.Gatt(connectionId),
        sourceId = sourceId,
        protocol = protocol,
        sourceClass = sourceClass,
        metric = metric,
        arrivalMs = arrivalMs,
        freshnessMs = freshnessMs,
        eventMs = eventMs,
        value = value,
        unit = unit,
        nativeCumulative = nativeCumulative,
        quality = quality,
        confidence = confidence,
    )

    init {
        require(sourceId.originId == originId) { "Source and sample origins differ" }
        require(sourceId.protocol == protocol) { "Source and sample protocol differ" }
        require(arrivalMs >= 0L) { "Arrival time must be monotonic and non-negative" }
        require(freshnessMs == null || freshnessMs > 0L) { "Freshness override must be positive" }
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

    /** GATT-only compatibility accessor. Transport-neutral code must use [originId]. */
    val connectionId: ConnectionId
        get() = requireNotNull((originId as? MetricOriginId.Gatt)?.connectionId) {
            "Metric sample is not GATT-backed"
        }
}

@FitBridgeBleInternalApi
fun interface MonotonicMetricClock {
    fun nowMs(): Long

    companion object {
        val SYSTEM = MonotonicMetricClock { System.nanoTime() / 1_000_000L }
    }
}

/** Wall-clock time used only for UI series that are rendered against epoch time. */
@FitBridgeBleInternalApi
fun interface MetricSeriesClock {
    fun nowMs(): Long

    companion object {
        val SYSTEM = MetricSeriesClock(System::currentTimeMillis)
    }
}

@FitBridgeBleInternalApi
enum class FusionSubmission {
    ACCEPTED,
    REJECTED_STALE,

    /** Structurally impossible: a negative distance, a zero pace. */
    REJECTED_INVALID,

    /** Failed its §11b plausibility bound — see [PlausibilityBounds]. */
    REJECTED_IMPLAUSIBLE,
}

@FitBridgeBleInternalApi
enum class FusionPersona { RIDE, RUN, ROW_PADDLE }

private data class MetricCandidateKey(
    val sourceId: MetricSourceId,
    val sourceClass: MetricSourceClass,
    val quality: MetricQuality,
)

/** Packet-scoped source binding. Every field decoded from one packet shares [arrivalMs]. */
@FitBridgeBleInternalApi
class MetricEmitter(
    private val fusion: MetricFusion,
    val sourceId: MetricSourceId,
    val sourceClass: MetricSourceClass,
    val arrivalMs: Long,
    val freshnessMs: Long? = null,
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
            originId = sourceId.originId,
            sourceId = sourceId,
            protocol = sourceId.protocol,
            sourceClass = sourceClass,
            metric = metric,
            arrivalMs = arrivalMs,
            freshnessMs = freshnessMs,
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
@FitBridgeBleInternalApi
class MetricFusion(
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
    private val concept2DistanceOrigins = mutableMapOf<MetricOriginId, Concept2DistanceOrigin>()
    private val cumulativeDistanceRateAnchors = mutableMapOf<MetricKind, Pair<Long, Double>>()
    private val quarantinedCumulativeDistance = mutableSetOf<MetricKind>()
    private val cumulativeDistanceResetObserved = mutableSetOf<MetricKind>()
    private val heartRateFilters = mutableMapOf<MetricSourceId, HeartRateJumpEmaFilter>()
    private val rejectionWarningGate = RejectionWarningGate(REJECTION_WARNING_WINDOW_MS)
    private val distancePaceBuffer = ArrayDeque<Pair<Long, Double>>()
    private var persona: FusionPersona = FusionPersona.RIDE
    private var machineOriginId: MetricOriginId? = null

    /** Whether the current strap dropout has already been marked in the series. */
    private var heartRateStaleMarked: Boolean = false

    /**
     * Links that have produced a heart-rate reading from a strap, kept independently
     * of whether a candidate is currently held.
     *
     * ### Why candidate presence was the wrong test
     *
     * [heartRateCandidates] originally asked "does an HRM candidate exist?" and its
     * comment argued that `onDisconnected` purges a source's candidates, so the
     * question already meant "is a strap connected?".
     *
     * That was incomplete. `resetSessionState` also clears every candidate, and it
     * runs on both [startSession] and [endSession] - neither of which unclips the
     * strap from the user's chest. For the moment after each boundary no HRM
     * candidate existed, so the machine was admitted, and because a treadmill
     * notifies faster than a strap it won the first sample every time:
     *
     * ```
     * 16:17:17.095  appendHeartRateSample bpm=145 avg=145 seriesSize=1
     * 16:17:17.521  heartRateSource UNKNOWN → EXTERNAL_HRM
     * 16:17:17.521  appendHeartRateSample bpm=63  avg=104 seriesSize=2
     * ```
     *
     * Ten such samples in one session, each self-correcting within a second - but
     * each one starts the graph and the session average on the machine's number.
     *
     * Cleared only by [onDisconnected], which is the event that actually means the
     * strap has gone.
     */
    private val heartRateStrapLinks = mutableSetOf<ConnectionId>()

    /**
     * Where each RSC source is worn, from Sensor Location `0x2A5D`.
     *
     * `true` foot, `false` torso or hip, absent when the device did not say. See
     * [speedCandidates].
     */
    private val rscFootMounted = mutableMapOf<ConnectionId, Boolean>()

    /** Links that have produced an RSC reading, tracked like [heartRateStrapLinks] and for the same reason. */
    private val rscLinks = mutableSetOf<ConnectionId>()

    /**
     * Record what an RSC device reports for Sensor Location `0x2A5D`.
     *
     * Survives session boundaries for the same reason [heartRateStrapLinks] does:
     * a session ending does not move the sensor.
     */
    fun setRscMounting(connectionId: ConnectionId, footMounted: Boolean?) = synchronized(lock) {
        if (footMounted == null) rscFootMounted.remove(connectionId)
        else rscFootMounted[connectionId] = footMounted
    }

    fun submit(sample: MetricSample): FusionSubmission = synchronized(lock) {
        if (!isDomainValid(sample)) return@synchronized FusionSubmission.REJECTED_INVALID
        if (!isPlausible(sample)) {
            // §11b: dropped and captured, not published. Arming here rather than logging only
            // is the whole point - a bound firing means a field was decoded wrongly, and the
            // packets that caused it are still in the ring for another few seconds.
            if (rejectionWarningGate.shouldLog(sample)) {
                Timber.tag(BtEngineState.TAG).w(
                    "Implausible %s from %s: %.3f %s outside %s (persona=%s) - dropped; repeated warnings suppressed for %d ms",
                    sample.metric,
                    sample.sourceClass,
                    sample.value,
                    sample.unit,
                    PlausibilityBounds.describe(sample.metric, persona),
                    persona,
                    REJECTION_WARNING_WINDOW_MS,
                )
            }
            CaptureHooks.boundsRejection(sample, PlausibilityBounds.describe(sample.metric, persona))
            return@synchronized FusionSubmission.REJECTED_IMPLAUSIBLE
        }
        // §0.7a: every sample passes through here, so this is the one place that
        // sees a machine adapter proving it is alive. Deliberately before the
        // staleness check below - an out-of-order sample is still evidence the
        // device is talking, even if fusion has no use for its value.
        state.adapterLiveness.onSample(sample)
        // §0.9.4: recorded post-parse, so a capture pairs each decoded value with
        // the notify event whose bytes produced it. That pairing is what makes a
        // capture convertible into a §5 fixture rather than merely readable.
        CaptureHooks.sample(sample)
        val bySource = candidates.getOrPut(sample.metric) { mutableMapOf() }
        val candidateKey = sample.candidateKey()
        val previous = bySource[candidateKey]
        if (previous != null && sample.arrivalMs < previous.arrivalMs) {
            return@synchronized FusionSubmission.REJECTED_STALE
        }
        if (sample.metric == MetricKind.HEART_RATE &&
            sample.sourceClass == MetricSourceClass.HRM
        ) {
            sample.sourceId.gattConnectionId?.let(heartRateStrapLinks::add)
        }
        if (sample.sourceClass == MetricSourceClass.RSC) {
            sample.sourceId.gattConnectionId?.let(rscLinks::add)
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
        machineOriginId = connectionId?.let(MetricOriginId::Gatt)
    }

    fun selectMachineOrigin(originId: MetricOriginId?) = synchronized(lock) {
        machineOriginId = originId
    }

    fun selectedMachine(): ConnectionId? = synchronized(lock) {
        (machineOriginId as? MetricOriginId.Gatt)?.connectionId
    }

    fun selectedMachineOrigin(): MetricOriginId? = synchronized(lock) { machineOriginId }

    fun refresh(nowMs: Long = clock.nowMs()) = synchronized(lock) {
        candidates.keys.toList().forEach { reselect(it, nowMs) }
    }

    fun onDisconnected(connectionId: ConnectionId, nowMs: Long = clock.nowMs()) =
        onOriginEnded(MetricOriginId.Gatt(connectionId), nowMs)

    fun onOriginEnded(originId: MetricOriginId, nowMs: Long = clock.nowMs()) = synchronized(lock) {
        concept2DistanceOrigins.remove(originId)
        val affected = mutableSetOf<MetricKind>()
        candidates.forEach { (metric, bySource) ->
            if (bySource.keys.removeAll { it.sourceId.originId == originId }) affected += metric
        }
        manualAssignments.entries.removeAll { (metric, source) ->
            val clear = source.originId == originId
            if (clear) affected += metric
            clear
        }
        if (machineOriginId == originId) machineOriginId = null
        heartRateFilters.keys.removeAll { it.originId == originId }
        (originId as? MetricOriginId.Gatt)?.connectionId?.let { connectionId ->
            // The one event that actually means the strap and the pod have gone.
            heartRateStrapLinks -= connectionId
            rscFootMounted -= connectionId
            rscLinks -= connectionId
        }
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
            originId = source.originId,
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
        concept2DistanceOrigins.clear()
        cumulativeDistanceRateAnchors.clear()
        quarantinedCumulativeDistance.clear()
        cumulativeDistanceResetObserved.clear()
        heartRateFilters.clear()
        rejectionWarningGate.reset()
        distancePaceBuffer.clear()
        if (clearMachine) machineOriginId = null
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

    /**
     * Heart-rate candidates, restricted to the strap once one is present.
     *
     * ### The defect this fixes
     *
     * `priorityRank` already puts [MetricSourceClass.HRM] above
     * [MetricSourceClass.FTMS], so a fresh strap always outranked a machine.
     * But ranking only decides between candidates that survive the freshness
     * filter, and a strap that misses a notification or two drops out of it -
     * `HEART_RATE_FRESHNESS_MS` is 4 s and BLE straps notify about once a
     * second. The machine then became the best *remaining* candidate and the
     * metric silently changed hands until the next strap packet took it back.
     *
     * Observed 2026-08-06 with a strap reading 54-58 bpm against a TMIW tablet
     * reporting 120-140: the graph alternated between the two. Nothing was
     * ranked wrongly; the strap was simply absent from the contest at the moment
     * each machine packet arrived.
     *
     * ### The rule
     *
     * Once a connected strap has produced **one** reading, machine heart rate is
     * excluded from selection entirely. Not ranked lower - excluded, so a stale
     * strap cannot yield the metric. `onDisconnected` purges a source's
     * candidates, so "an HRM candidate exists" already means "an HRM is
     * connected"; no separate connection tracking is needed.
     *
     * Machine heart rate is still used up to the strap's first reading, so the
     * seconds between clipping a strap on and it finding a pulse do not read as
     * a fault.
     */
    private fun heartRateCandidates(
        all: Map<MetricCandidateKey, MetricSample>,
    ): Map<MetricCandidateKey, MetricSample> {
        val strapOnly = all.filterKeys { it.sourceClass == MetricSourceClass.HRM }
        if (strapOnly.isNotEmpty()) return strapOnly
        // No strap candidate right now. That is not the same as no strap: a session
        // boundary clears every candidate without unclipping anything, and the
        // machine used to win the first sample of each new session. Empty rather
        // than `all`, so heart rate simply waits for the strap's next packet -
        // which is also why no dropout is projected here, the caller requiring a
        // non-empty candidate set for that.
        return if (heartRateStrapLinks.isEmpty()) all else emptyMap()
    }

    /**
     * Speed candidates, with torso-mounted RSC set aside while a machine reports.
     *
     * ### Why this is not simply "the machine wins"
     *
     * Plan §4.1, accepted per D7, ranks **RSC above FTMS** for running speed, and
     * that is right when RSC means a foot pod: a Stryd or a Zwift RunPod measures
     * the runner, where a treadmill reports the speed it was told to run at. This
     * does not overturn that.
     *
     * What it excludes is a *chest strap* reporting the same characteristic while
     * inferring speed from torso accelerometry. Hardware session 948b of
     * 2026-08-07 recorded a Garmin HRM-Pro reporting 19.11 km/h at a cadence of 49
     * - 6.5 m per cadence unit, roughly triple a sprinter's stride - against a
     * treadmill steady at 3.00 km/h. Published speed alternated between the two.
     *
     * ### The test, and the evidence behind it
     *
     * Sensor Location `0x2A5D` is authoritative and both devices in question
     * answer it (2026-08-07, session `0b04`):
     *
     * ```
     * RSC sensor location on 3C:E4:B0:68:EB:A2: top of shoe (foot-mounted=true)
     * RSC sensor location on F4:55:89:A3:47:42: chest      (foot-mounted=false)
     * ```
     *
     * A device that declines the read falls back to a proxy: RSC and HRM on one
     * physical link means a torso device, because a foot pod does not measure
     * heart rate. That proxy shipped alone on 2026-08-07 and is now the fallback
     * rather than the rule - it misclassifies a foot pod that also exposes HRM,
     * and says nothing about a hip-mounted sensor, which `0x2A5D` reports exactly.
     *
     * Set aside, not down-ranked: the heart-rate lesson was that ranking alone
     * loses to a freshness gap, and the loser then takes the metric silently.
     */
    private fun speedCandidates(
        all: Map<MetricCandidateKey, MetricSample>,
    ): Map<MetricCandidateKey, MetricSample> {
        if (all.values.none { it.sourceClass == MetricSourceClass.FTMS }) return all

        // A connected foot pod owns speed outright, which is §4.1's "RSC > FTMS" for
        // running - as an exclusion rather than a ranking, for the reason the heart-rate
        // work established twice already: ranking loses to a freshness gap, and the loser
        // then takes the metric back silently.
        //
        // Session 8a96 of 2026-08-10 is why. A Zwift RunPod notifies on stride completion,
        // not on a timer, so at walking pace its gaps ran to 5.8 s against a 3 s window.
        // Seven of its eleven gaps exceeded it, and published speed alternated:
        //   10.48  4.13  4.50  10.50  4.13  10.52  …  4.18  4.18  10.55
        // Ranking was correct throughout; the pod was simply absent from the contest each
        // time a treadmill packet arrived. See also the longer RSC freshness window in
        // [freshnessMs] - exclusion alone would blank speed during those gaps instead.
        // RUN only. §4.1 ranks RSC above FTMS in the Run column and nowhere else: on a bike or
        // a rower the machine owns speed and a foot pod has no claim on it at all. Applying
        // this to every persona let a pod registered during a run displace an indoor rower's
        // own speed reading - caught by `persona priority selects ride run and row defaults`.
        if (persona == FusionPersona.RUN && rscLinks.any(::wornOnFoot)) {
            val podOwned = all.filterValues { it.sourceClass != MetricSourceClass.FTMS }
            if (podOwned.isNotEmpty()) return podOwned
        }

        val kept = all.filterValues { sample ->
            sample.sourceClass != MetricSourceClass.RSC ||
                sample.sourceId.gattConnectionId?.let(::wornOnFoot) == true
        }
        // Never strand the metric: if setting the strap aside would leave nothing,
        // its estimate is still better than no speed at all.
        return kept.ifEmpty { all }
    }

    /**
     * Whether an RSC link measures the foot, and so keeps its §4.1 priority.
     *
     * Sensor Location first; the shared-link proxy only when the device stayed
     * silent about where it is. Unknown resolves to "foot" so a sensor that
     * declines the read is not demoted for declining it.
     */
    private fun wornOnFoot(connectionId: ConnectionId): Boolean =
        rscFootMounted[connectionId] ?: (connectionId !in heartRateStrapLinks)

    /**
     * A connected strap has gone silent past its freshness window.
     *
     * The graph gets an explicit 0 so the gap is visible as a gap rather than as
     * a flat line held at the last reading, while the numeric readout blanks -
     * asserting "your heart rate is 0" would be a stronger claim than the data
     * supports. The average is deliberately left alone: a dropout is missing
     * data, not a zero-bpm interval, and folding it in would drag the session
     * average down for a strap problem.
     */
    private fun projectStaleHeartRate() {
        // Once per dropout, not once per machine packet. Machine data keeps
        // arriving at over 1 Hz while the strap is quiet, and every one of those
        // re-runs selection; without this the series would fill with zeros and
        // the "gap" would be indistinguishable from a long flat run at zero.
        if (heartRateStaleMarked) return
        heartRateStaleMarked = true

        state.setHeartRate(null)
        state.setExternalHeartRate(null)
        state.setHeartRateSource(BtEngineState.HrSource.UNKNOWN)
        state.appendHeartRateSeriesPoint(0.0, seriesClock.nowMs())
        publishHeartRateZones()
    }

    private fun reselect(metric: MetricKind, nowMs: Long) {
        val allCandidates = candidates[metric].orEmpty()
        val pinned = manualAssignments[metric]

        // Freshness is measured from the newest evidence in hand, not from the
        // packet that happened to trigger this pass.
        //
        // ### The defect this fixes (observed 2026-08-07, session 948b)
        //
        // `submit` calls `reselect(metric, sample.arrivalMs)`. An FTMS packet
        // carries fifteen fields under **one** arrival stamp taken when the packet
        // landed, and parsing it takes tens of milliseconds. A strap notification
        // arriving in that window is stamped later than the packet being parsed.
        // When the packet finally submits its heart-rate field, the strap's sample
        // is in the future relative to `nowMs`, `isFresh` computes a negative age,
        // and the `age >= 0` guard rejects a sample that is forty milliseconds old.
        //
        // Selection then found no fresh candidate at all and projected a dropout:
        // five spurious `appendHeartRateSeriesPoint(0.0)` calls in ninety seconds,
        // against a strap whose largest gap all session was 1.50 s. The 2026-08-06
        // HRM lockout stopped the machine's number being published but turned the
        // graph spikes into zeros rather than removing them.
        //
        // Taking the maximum is the whole fix: a sample cannot be stale relative to
        // an instant before it existed. The `age >= 0` guard is kept for genuinely
        // corrupt stamps, which this no longer misclassifies.
        val effectiveNow = maxOf(nowMs, allCandidates.values.maxOfOrNull { it.arrivalMs } ?: nowMs)

        // A manual pin overrides every automatic exclusion below: an explicit user
        // choice is not something to second-guess with a heuristic.
        val bySource = when {
            pinned != null -> allCandidates
            // A connected strap owns heart rate outright - see [heartRateCandidates].
            metric == MetricKind.HEART_RATE -> heartRateCandidates(allCandidates)
            // Torso-mounted RSC stands aside for a machine - see [speedCandidates].
            metric == MetricKind.SPEED || metric == MetricKind.AVERAGE_SPEED ->
                speedCandidates(allCandidates)
            else -> allCandidates
        }

        val winner = if (pinned != null) {
            bySource.values
                .asSequence()
                .filter { it.sourceId == pinned }
                .minWithOrNull(sampleComparator(metric))
        } else {
            bySource.values
                .asSequence()
                .filter { isFresh(it, effectiveNow) }
                .minWithOrNull(sampleComparator(metric))
        }
        val previous = selectedByMetric[metric]
        if (winner == null) {
            if (previous != null) {
                selectedByMetric.remove(metric)
                clearProjection(metric)
            }
            // A strap that has gone quiet must read as "no pulse", not as the
            // machine's number and not as the strap's last one held forever.
            if (metric == MetricKind.HEART_RATE && bySource.isNotEmpty()) {
                projectStaleHeartRate()
            }
            return
        }
        if (previous == winner) return
        selectedByMetric[metric] = winner
        if (metric.cumulative) projectCumulative(winner, previous?.sourceId) else projectPassThrough(winner)
    }

    private fun projectCumulative(sample: MetricSample, previousSource: MetricSourceId?) {
        val metric = sample.metric
        val rawNative = sample.nativeCumulative ?: return
        val native = if (metric == MetricKind.DISTANCE && sample.protocol == MetricProtocol.VENDOR &&
            sample.sourceClass == MetricSourceClass.VENDOR_NATIVE &&
            sample.sourceId.gattCharacteristicUuid in setOf(
                BluetoothIds.UUID_C2_GENERAL_STATUS, BluetoothIds.UUID_C2_MULTIPLEXED_INFORMATION,
            )
        ) {
            concept2DistanceOrigins.getOrPut(sample.originId) { Concept2DistanceOrigin() }.align(rawNative)
        } else rawNative
        val totalBefore = cumulativeTotals[metric]
        val activeSource = cumulativeActiveSource[metric]
        val sourceChanged = activeSource != sample.sourceId || previousSource != sample.sourceId
        val lastNative = cumulativeLastNative[metric]
        if (metric == MetricKind.DISTANCE) {
            when {
                totalBefore == null || sourceChanged || lastNative == null -> {
                    cumulativeDistanceRateAnchors[metric] = sample.arrivalMs to native
                    quarantinedCumulativeDistance.remove(metric)
                    cumulativeDistanceResetObserved.remove(metric)
                }
                metric in quarantinedCumulativeDistance -> {
                    if (native < lastNative) {
                        if (metric !in cumulativeDistanceResetObserved) {
                            // The boundary scenario emits zero before restoring the real counter.
                            // Remember that reset marker without replacing the last sane native
                            // value: restoration must add only growth since that sane value.
                            cumulativeDistanceResetObserved += metric
                        } else if (native > 0.0) {
                            // The counter stayed below the old value after its reset marker. This
                            // is a genuine reset rather than boundary restoration: establish the
                            // first positive value as the new baseline and preserve the total.
                            quarantinedCumulativeDistance.remove(metric)
                            cumulativeDistanceResetObserved.remove(metric)
                            cumulativeDistanceRateAnchors[metric] = sample.arrivalMs to native
                            cumulativeLastNative[metric] = native
                        }
                        projectPassThrough(sample.copy(value = totalBefore, nativeCumulative = native))
                        return
                    }
                    if (metric in cumulativeDistanceResetObserved &&
                        isPlausibleCumulativeDistanceProgress(metric, native, sample.arrivalMs)
                    ) {
                        // The normal counter returned after the reset marker. Leave lastNative at
                        // the last sane value so the canonical calculation below contributes only
                        // the legitimate distance accumulated through the boundary test.
                        quarantinedCumulativeDistance.remove(metric)
                        cumulativeDistanceResetObserved.remove(metric)
                    } else {
                        projectPassThrough(sample.copy(value = totalBefore, nativeCumulative = native))
                        return
                    }
                }
                native < lastNative -> {
                    cumulativeDistanceRateAnchors[metric] = sample.arrivalMs to native
                    cumulativeDistanceResetObserved.remove(metric)
                }
                !isPlausibleCumulativeDistanceProgress(metric, native, sample.arrivalMs) -> {
                    // Preserve the session total and, critically, the last sane native value.
                    // Boundary words remain quarantined until an observed reset is followed by
                    // either restoration of the sane counter or a new positive counter baseline.
                    quarantinedCumulativeDistance += metric
                    cumulativeDistanceResetObserved.remove(metric)
                    Timber.tag(BtEngineState.TAG).w(
                        "Implausible cumulative distance jump from %.1f m to %.1f m - held until counter reset",
                        lastNative,
                        native,
                    )
                    CaptureHooks.boundsRejection(
                        sample,
                        "cumulative growth envelope: ${PlausibilityBounds.rangeFor(MetricKind.SPEED, persona)?.max} km/h plus ${DISTANCE_COUNTER_BURST_ALLOWANCE_METERS.toInt()} m burst",
                    )
                    projectPassThrough(sample.copy(value = totalBefore, nativeCumulative = native))
                    return
                }
            }
        }
        val canonical = when {
            // An RSC sensor's total-distance counter may have started long before this
            // FitBridge session. Its first value establishes the origin; only later deltas
            // belong to the session (plan D28).
            totalBefore == null && metric == MetricKind.DISTANCE &&
                sample.sourceClass == MetricSourceClass.RSC -> 0.0
            totalBefore == null -> native
            sourceChanged -> totalBefore
            native < (lastNative ?: native) -> totalBefore
            else -> totalBefore + (native - (lastNative ?: native))
        }
        cumulativeTotals[metric] = canonical
        cumulativeActiveSource[metric] = sample.sourceId
        cumulativeLastNative[metric] = native
        projectPassThrough(sample.copy(value = canonical, nativeCumulative = native))
    }

    /**
     * Cumulative distance is coarse on some machines, so the envelope includes a one-off burst
     * allowance. Beyond that allowance, growth cannot outrun the persona's already-declared
     * maximum speed from the point where this native counter was established or reset.
     */
    private fun isPlausibleCumulativeDistanceProgress(
        metric: MetricKind,
        native: Double,
        arrivalMs: Long,
    ): Boolean {
        val (anchorAt, anchorNative) = cumulativeDistanceRateAnchors[metric] ?: return true
        val elapsedSeconds = (arrivalMs - anchorAt).coerceAtLeast(0L) / 1_000.0
        val maxKph = PlausibilityBounds.rangeFor(MetricKind.SPEED, persona)?.max ?: return true
        val allowedGrowth = DISTANCE_COUNTER_BURST_ALLOWANCE_METERS + maxKph / 3.6 * elapsedSeconds
        return native - anchorNative <= allowedGrowth
    }

    private fun isFresh(sample: MetricSample, nowMs: Long): Boolean {
        if (sample.sourceClass == MetricSourceClass.SESSION) return true
        val age = nowMs - sample.arrivalMs
        // A source may state its own window when the class default cannot describe it: an
        // advertisement-only machine's stale point is set by its broadcast period, not by a
        // notification cadence. Unset keeps every existing class default untouched.
        val window = sample.freshnessMs ?: freshnessMs(sample.metric, sample.sourceClass)
        return age >= 0L && age <= window
    }

    /**
     * How long a sample stays usable, by metric and by the kind of source that sent it.
     *
     * A foot pod notifies on **stride completion**, not on a timer, so its period is set by
     * the user's gait rather than by a connection interval. Session 8a96 recorded a Zwift
     * RunPod at walking pace with gaps up to 5.8 s — seven of eleven gaps exceeded the 3 s
     * default, and each one dropped it out of selection. That is not a stale sensor; it is a
     * sensor doing exactly what it is for.
     *
     * Eight seconds covers a slow walk without letting a genuinely dead pod hold speed
     * indefinitely. It is generous, and deliberately so: the plausibility bounds in
     * [PlausibilityBounds] now catch an absurd value, so a slightly stale but sane reading is
     * the lesser risk.
     */
    private fun freshnessMs(metric: MetricKind, sourceClass: MetricSourceClass): Long = when {
        metric == MetricKind.HEART_RATE -> HEART_RATE_FRESHNESS_MS
        sourceClass == MetricSourceClass.RSC -> RSC_FRESHNESS_MS
        else -> DEFAULT_FRESHNESS_MS
    }

    private fun sampleComparator(metric: MetricKind): Comparator<MetricSample> =
        compareBy<MetricSample> { qualityRank(it.quality) }
            .thenBy { priorityRank(metric, it.sourceClass) }
            .thenByDescending { it.confidence }
            .thenByDescending { it.arrivalMs }
            .thenBy { it.sourceId.originId.tieBreakKey() }
            .thenBy { it.sourceId.originId.generation() }
            .thenBy { it.sourceId.endpointId.tieBreakKey() }

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
            FusionPersona.RIDE -> rank(source, MetricSourceClass.CPS_CRANK, MetricSourceClass.CSC_CRANK, MetricSourceClass.FTMS, MetricSourceClass.MOTION, MetricSourceClass.VENDOR_NATIVE)
            FusionPersona.RUN -> rank(source, MetricSourceClass.RSC, MetricSourceClass.FTMS, MetricSourceClass.MOTION)
            FusionPersona.ROW_PADDLE -> rank(source, MetricSourceClass.FTMS, MetricSourceClass.VENDOR_NATIVE)
        }
        MetricKind.SPEED, MetricKind.AVERAGE_SPEED -> when (persona) {
            FusionPersona.RIDE -> rank(source, MetricSourceClass.FTMS, MetricSourceClass.CSC_WHEEL, MetricSourceClass.CPS_WHEEL, MetricSourceClass.DERIVED, MetricSourceClass.VENDOR_NATIVE)
            FusionPersona.RUN -> rank(source, MetricSourceClass.RSC, MetricSourceClass.FTMS, MetricSourceClass.GPS)
            FusionPersona.ROW_PADDLE -> rank(source, MetricSourceClass.FTMS, MetricSourceClass.VENDOR_NATIVE)
        }
        MetricKind.DISTANCE -> when (persona) {
            FusionPersona.RIDE -> rank(source, MetricSourceClass.FTMS, MetricSourceClass.VENDOR_NATIVE, MetricSourceClass.CSC_WHEEL, MetricSourceClass.CPS_WHEEL, MetricSourceClass.INTEGRATED)
            FusionPersona.RUN -> rank(source, MetricSourceClass.RSC, MetricSourceClass.FTMS, MetricSourceClass.INTEGRATED)
            FusionPersona.ROW_PADDLE -> rank(source, MetricSourceClass.FTMS, MetricSourceClass.INTEGRATED)
        }
        MetricKind.ENERGY -> rank(source, MetricSourceClass.HRM, MetricSourceClass.FTMS, MetricSourceClass.VENDOR_NATIVE, MetricSourceClass.CPS)
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

    /**
     * Structural validity: a value that cannot be a reading of this metric at all.
     *
     * Distinct from the §11b plausibility bound in [isPlausible]. This rejects nonsense — a
     * negative distance, a zero heart rate, or a zero pace that would divide badly downstream.
     * Finite positive values that exceed a physical ceiling belong to [isPlausible], where they
     * arm the §11b diagnostic capture rather than disappearing as ordinary malformed input.
     */
    private fun isDomainValid(sample: MetricSample): Boolean = when (sample.metric) {
        MetricKind.HEART_RATE -> sample.value > 0.0
        MetricKind.INSTANT_PACE, MetricKind.AVERAGE_PACE -> sample.value > 0.0
        MetricKind.INCLINATION, MetricKind.RAMP_ANGLE, MetricKind.RESISTANCE -> true
        else -> sample.value >= 0.0
    }

    /**
     * §11b: is this value physically possible for the activity in progress?
     *
     * Evaluated against the persona at the moment of arrival, so a bike's 60 km/h is admitted
     * and a treadmill's is not. Bounds and their reasoning live in [PlausibilityBounds].
     */
    private fun isPlausible(sample: MetricSample): Boolean =
        PlausibilityBounds.isPlausible(sample.metric, persona, sample.value)

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
                heartRateStaleMarked = false
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
                val watts = value.roundToInt()
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
                if (sample.sourceId.gattCharacteristicUuid == BluetoothIds.UUID_ROWING_MACHINE_DATA) {
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
                originId = base.originId,
                sourceId = base.sourceId,
                protocol = base.protocol,
                sourceClass = MetricSourceClass.DERIVED,
                metric = metric,
                arrivalMs = base.arrivalMs,
                freshnessMs = base.freshnessMs,
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
        originId = emitter.sourceId.originId,
        sourceId = emitter.sourceId,
        protocol = emitter.sourceId.protocol,
        sourceClass = emitter.sourceClass,
        metric = metric,
        arrivalMs = emitter.arrivalMs,
        freshnessMs = emitter.freshnessMs,
        value = value,
    )

    private companion object {
        const val DEFAULT_FRESHNESS_MS = 3_000L

        /** RSC notifies per stride, not on a timer — see [freshnessMs]. */
        const val RSC_FRESHNESS_MS = 8_000L
        const val DERIVED_PACE_CONFIDENCE = 0.75
        const val DISTANCE_PACE_MIN_MS = 5_000L
        const val DISTANCE_PACE_TARGET_MS = 10_000L
        const val DISTANCE_PACE_BUFFER_MS = 30_000L
        const val DISTANCE_COUNTER_BURST_ALLOWANCE_METERS = 500.0
        const val REJECTION_WARNING_WINDOW_MS = 10_000L
        val SESSION_SOURCE = MetricSourceId(
            connectionId = ConnectionId("FITBRIDGE_SESSION", 0L),
            protocol = MetricProtocol.SESSION,
            characteristicUuid = UUID(0L, 0L),
        )
    }
}

/** Logs the first bounds failure per metric/source, then one reminder per window. */
internal class RejectionWarningGate(private val windowMs: Long) {
    private data class Key(val metric: MetricKind, val sourceId: MetricSourceId)

    private val lastLoggedAt = mutableMapOf<Key, Long>()

    init {
        require(windowMs > 0L)
    }

    fun shouldLog(sample: MetricSample): Boolean {
        val key = Key(sample.metric, sample.sourceId)
        val previous = lastLoggedAt[key]
        if (previous != null && sample.arrivalMs - previous < windowMs) return false
        lastLoggedAt[key] = sample.arrivalMs
        return true
    }

    fun reset() = lastLoggedAt.clear()
}

private fun MetricOriginId.tieBreakKey(): String = when (this) {
    is MetricOriginId.Gatt -> "gatt:${connectionId.address}"
    is MetricOriginId.Advertisement -> "advertisement:$sourceKey"
}

private fun MetricOriginId.generation(): Long = when (this) {
    is MetricOriginId.Gatt -> connectionId.linkGeneration
    is MetricOriginId.Advertisement -> generation
}

private fun MetricEndpointId.tieBreakKey(): String = when (this) {
    is MetricEndpointId.GattCharacteristic -> "gatt:$uuid"
    is MetricEndpointId.ManufacturerData -> "manufacturer:${companyId.toString(16).padStart(4, '0')}"
}
