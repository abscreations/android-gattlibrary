package com.gmail.absolutebollockscreations.gattlibrary.server

import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Latest metrics from the upstream machine, in FTMS-native units.
 *
 * This decouples the notifier from `BtEngineState`. The engine pump writes a new
 * snapshot whenever upstream notifications arrive; the notifier reads the latest
 * one on its own timer. That decoupling is deliberate - it gives rate
 * independence and backpressure for free, and means a burst of upstream packets
 * never turns into a burst of downstream notifications.
 *
 * Every field is nullable. A null field clears its flag bit and is omitted from
 * the packet entirely; it is never sent as zero. The previous per-sport encoders
 * read `StateFlow.value` directly and substituted 0 for every absent metric,
 * which told the client the machine was reporting a real zero.
 */
data class FtmsMetricSnapshot(
    /** km/h. Mandatory for speed-based personas. */
    val speedKph: Double? = null,
    /** Session-average speed in km/h. */
    val averageSpeedKph: Double? = null,
    /** FTMS Rower instantaneous pace in seconds per 500 metres. */
    val paceSecondsPer500m: Double? = null,
    /** FTMS Rower cumulative session-average pace in seconds per 500 metres. */
    val averagePaceSecondsPer500m: Double? = null,
    /** rpm for bike, strokes/min for rower, steps/min for climbers. */
    val cadence: Double? = null,
    /** Genuine time-weighted session average, never copied from the instantaneous field. */
    val averageCadence: Double? = null,
    /** Watts. May be negative in principle; encoded as sint16. */
    val powerWatts: Int? = null,
    /** Genuine time-weighted session average power. */
    val averagePowerWatts: Int? = null,
    /** Force on the treadmill belt in newtons. Paired with power on the wire. */
    val forceOnBeltNewtons: Int? = null,
    val heartRate: Int? = null,
    /** Metres. */
    val distanceMeters: Int? = null,
    val elapsedSeconds: Int? = null,
    val caloriesTotal: Int? = null,
    val caloriesPerHour: Int? = null,
    val caloriesPerMinute: Int? = null,
    /**
     * Metabolic Equivalent in METs (FTMS uint8, 0.1 resolution). Instantaneous: zeroed when the
     * source goes stale. Nothing in the bridge populates it yet; only a source that supplies it
     * (and a capability that claims Feature bit 11) should set it.
     */
    val metabolicEquivalent: Double? = null,
    /**
     * Seconds remaining in a programmed workout (FTMS uint16). A countdown, so held rather than
     * zeroed when stale. As with [metabolicEquivalent], nothing populates it yet.
     */
    val remainingTimeSeconds: Int? = null,
    /**
     * Machine resistance level, in public units - the number a console would
     * show, not the wire encoding.
     *
     * Fractional, because Cross Trainer Data (`0x2ACE`) declares Resistance Level
     * with `DecimalExponent -1`. This was an `Int` until 2026-08-06, which
     * silently truncated every compliant machine's tenths on the way through the
     * bridge: a level of 0.7 arrived as 1. Each encoder applies its own
     * characteristic's scaling.
     */
    val resistance: Float? = null,
    /** Percent grade with 1 decimal place, e.g. -2.5 means -2.5%. */
    val inclinationPercent: Double? = null,
    /** Cumulative ascent/descent in metres. FTMS encodes each as uint16 metres. */
    val positiveElevationGainMeters: Double? = null,
    val negativeElevationGainMeters: Double? = null,
    /** Cumulative floors. One synthetic floor is 3 metres. */
    val floors: Int? = null,
    /** Strokes, strides or steps depending on persona. */
    val strokeCount: Int? = null,
    /** Exact selected upstream CSC counters/event times, if this is a CSC bike session. */
    val cscNativeEvents: CscNativeEventInput? = null,
    /** Exact upstream CPS wheel/crank event fields for native CPS forwarding. */
    val cpsNativeEvents: CpsNativeEventInput? = null,
    /**
     * Raw treadmill pace octets from the upstream machine, re-emitted verbatim. Never
     * synthesised: the wire field resolves to 6 km/h per step, so a value derived from fused
     * speed would contradict the Instantaneous Speed field in the same packet.
     */
    val treadmillNativePace: TreadmillNativePaceInput? = null,
    /**
     * `SystemClock.elapsedRealtime()` when [EngineMetricPump] **sampled** this
     * snapshot — not when the upstream machine sent the data.
     *
     * The distinction matters because this timestamp is what the notifier's
     * staleness metric measures against. That metric therefore covers
     * sample → downstream acknowledgement, and is blind to how long a value sat
     * in [BtEngineState] before the pump read it. `BtEngineState` carries no
     * arrival timestamps today, so the upstream leg cannot be measured here at
     * all; a real end-to-end figure needs the arrival-time-tagged `MetricSample`
     * that plan §3.1 introduces.
     *
     * Read the staleness metric as "peripheral-side lag", and remember that the
     * unmeasured upstream leg is bounded by the machine's own notification rate,
     * which is typically the larger of the two and is not something FitBridge can
     * reduce.
     */
    val updatedAtMs: Long = 0L,
) {
    companion object {
        val EMPTY = FtmsMetricSnapshot()

        /**
         * How long a non-null speed is reused after the source stops supplying
         * one. Every speed-based FTMS data characteristic makes Instantaneous
         * Speed mandatory, so emitting nothing makes the client look frozen;
         * holding the last value briefly is the lesser evil. After this window
         * we fall back to zero rather than silence.
         */
        const val METRIC_FRESHNESS_MS = 5_000L
        const val SPEED_HOLD_MS = METRIC_FRESHNESS_MS
    }
}

/**
 * Thread-safe holder. The engine pump writes on the upstream GATT callback
 * thread; the notifier reads from a coroutine. A volatile reference to an
 * immutable value is sufficient - no lock, and readers never see a half-written
 * snapshot.
 */
class FtmsMetricStore {
    @Volatile
    var snapshot: FtmsMetricSnapshot = FtmsMetricSnapshot.EMPTY
        private set

    /**
     * Number of metric updates received from the engine. A diagnostic signal: if
     * this is zero while a client is subscribed, the problem is upstream of the
     * peripheral, not in the encoder or notifier.
     */
    @Volatile
    var updateCount: Long = 0L
        private set

    @Volatile
    private var lastKnownSpeedKph: Double? = null

    @Volatile
    private var lastKnownSpeedAtMs: Long = 0L

    private val cadenceAverage = TimeWeightedAverage()
    private val powerAverage = TimeWeightedAverage()
    private val elevationAccumulator = ElevationAccumulator()

    @Synchronized
    fun update(next: FtmsMetricSnapshot, profile: FtmsMachineProfile? = null) {
        if (next.speedKph != null) {
            lastKnownSpeedKph = next.speedKph
            lastKnownSpeedAtMs = next.updatedAtMs
        }
        cadenceAverage.observe(next.cadence, next.updatedAtMs)
        powerAverage.observe(next.powerWatts?.toDouble(), next.updatedAtMs)

        var forceOnBelt = next.forceOnBeltNewtons
        if (profile == FtmsMachineProfile.TREADMILL && forceOnBelt == null) {
            forceOnBelt = syntheticForceOnBelt(next.speedKph, next.powerWatts)
        }

        var positiveElevation = next.positiveElevationGainMeters
        var negativeElevation = next.negativeElevationGainMeters
        if (profile == FtmsMachineProfile.TREADMILL || profile == FtmsMachineProfile.ELLIPTICAL) {
            val canSynthesize = next.speedKph?.isFinite() == true &&
                next.inclinationPercent?.isFinite() == true
            if (canSynthesize) {
                elevationAccumulator.seedIfEmpty(
                    positiveElevation ?: snapshot.positiveElevationGainMeters,
                    negativeElevation ?: snapshot.negativeElevationGainMeters,
                )
                elevationAccumulator.observe(
                    next.speedKph,
                    next.inclinationPercent,
                    next.updatedAtMs,
                )
                positiveElevation = elevationAccumulator.positiveMeters
                negativeElevation = elevationAccumulator.negativeMeters
            } else {
                elevationAccumulator.breakContinuity()
            }
        } else {
            elevationAccumulator.breakContinuity()
        }

        var floors = next.floors
        // Preserve machine-reported values. Derive only the missing counterpart
        // using the convention of one floor per 3 metres.
        if (positiveElevation == null && floors != null) {
            positiveElevation = floors.coerceAtLeast(0) * METRES_PER_FLOOR
        }
        if (floors == null && positiveElevation != null && positiveElevation.isFinite()) {
            floors = floor(positiveElevation.coerceAtLeast(0.0) / METRES_PER_FLOOR).toInt()
        }

        snapshot = next.copy(
            averageCadence = next.averageCadence ?: cadenceAverage.value,
            averagePowerWatts = next.averagePowerWatts ?: powerAverage.value?.toInt(),
            forceOnBeltNewtons = forceOnBelt,
            positiveElevationGainMeters = positiveElevation,
            negativeElevationGainMeters = negativeElevation,
            floors = floors,
        )
        updateCount++
    }

    @Synchronized
    fun clear() {
        snapshot = FtmsMetricSnapshot.EMPTY
        updateCount = 0L
        lastKnownSpeedKph = null
        lastKnownSpeedAtMs = 0L
        cadenceAverage.clear()
        powerAverage.clear()
        elevationAccumulator.clear()
    }

    /**
     * Speed to encode.
     *
     * Within the hold window the last real speed is reused; past it we fall back
     * to 0 km/h (the machine is idle) rather than going silent, so a speed-based
     * persona keeps emitting a well-formed data packet. Returns null only when we
     * have never seen a speed at all - before the first sample there is nothing
     * to send.
     */
    fun effectiveSpeedKph(nowMs: Long): Double? {
        val current = snapshot
        val s = current.speedKph
        if (s != null && nowMs - current.updatedAtMs in 0..FtmsMetricSnapshot.SPEED_HOLD_MS) return s
        val held = lastKnownSpeedKph ?: return null
        return if (nowMs - lastKnownSpeedAtMs in 0..FtmsMetricSnapshot.SPEED_HOLD_MS) held else 0.0
    }

    /**
     * The snapshot to notify with, designed to *never go silent* once a workout
     * has produced at least one sample.
     *
     * While the latest push is fresh, it is sent verbatim. Once it ages past
     * [FtmsMetricSnapshot.METRIC_FRESHNESS_MS] the stream keeps flowing but the
     * values are split by kind:
     *
     *  - **Instantaneous** readings (speed, cadence, power, force on belt, heart
     *    rate, resistance, inclination, per-hour/per-minute energy rates) are
     *    zeroed: the correct thing to report when the machine has gone idle.
     *  - **Cumulative** counters (distance, elapsed time, total energy, elevation
     *    gains, floors, stroke/step count) and session **averages** are held at
     *    their last value, never zeroed - rewinding a client's totals to 0 and
     *    back is a display glitch and can corrupt the recorded activity.
     *
     * A field that was never populated stays null (absent from the packet); we
     * never fabricate a metric the source has not supplied.
     *
     * Returns null only before the very first sample.
     */
    fun snapshotForNotification(nowMs: Long): FtmsMetricSnapshot? {
        val current = snapshot
        if (current.updatedAtMs <= 0L) return null
        val age = nowMs - current.updatedAtMs
        if (age in 0..FtmsMetricSnapshot.METRIC_FRESHNESS_MS) return current

        // Stale: keep the stream alive. Zero instantaneous readings, hold totals.
        return current.copy(
            speedKph = current.speedKph?.let { 0.0 },
            paceSecondsPer500m = current.paceSecondsPer500m?.let { 0.0 },
            cadence = current.cadence?.let { 0.0 },
            powerWatts = current.powerWatts?.let { 0 },
            forceOnBeltNewtons = current.forceOnBeltNewtons?.let { 0 },
            heartRate = current.heartRate?.let { 0 },
            resistance = current.resistance?.let { 0f },
            inclinationPercent = current.inclinationPercent?.let { 0.0 },
            caloriesPerHour = current.caloriesPerHour?.let { 0 },
            caloriesPerMinute = current.caloriesPerMinute?.let { 0 },
            metabolicEquivalent = current.metabolicEquivalent?.let { 0.0 },
            // Held (cumulative / session-average): distanceMeters, elapsedSeconds,
            // caloriesTotal, remainingTimeSeconds, strokeCount, averageSpeedKph,
            // averagePaceSecondsPer500m, averageCadence, averagePowerWatts.
        )
    }

    private fun syntheticForceOnBelt(speedKph: Double?, powerWatts: Int?): Int? {
        if (speedKph == null || !speedKph.isFinite() || powerWatts == null) return null
        val speedMetresPerSecond = speedKph / 3.6
        if (speedMetresPerSecond > MIN_FORCE_SPEED_METRES_PER_SECOND) {
            return (powerWatts / speedMetresPerSecond).roundToInt()
        }
        return 0.takeIf { powerWatts == 0 }
    }

    private class TimeWeightedAverage {
        private var previous: Double? = null
        private var previousAtMs: Long = 0L
        private var weightedTotal = 0.0
        private var durationMs = 0L

        val value: Double?
            get() = when {
                durationMs > 0L -> weightedTotal / durationMs
                else -> previous
            }

        fun observe(next: Double?, atMs: Long) {
            val prior = previous
            val dt = atMs - previousAtMs
            if (next != null && prior != null && dt in 1..FtmsMetricSnapshot.METRIC_FRESHNESS_MS) {
                // Trapezoidal integration handles changing samples without
                // pretending the newest instantaneous sample is the average.
                weightedTotal += ((prior + next) / 2.0) * dt
                durationMs += dt
            }
            previous = next
            previousAtMs = atMs
        }

        fun clear() {
            previous = null
            previousAtMs = 0L
            weightedTotal = 0.0
            durationMs = 0L
        }
    }

    /**
     * Trapezoidal integration of vertical velocity.
     *
     * FTMS inclination is grade percent (rise/run), while speed is distance along
     * the belt/path. sin(atan(grade / 100)) converts path distance to its
     * vertical component. Integrating the prior and current vertical rates makes
     * changing speed and inclination genuinely time-weighted.
     */
    private class ElevationAccumulator {
        var positiveMeters: Double = 0.0
            private set
        var negativeMeters: Double = 0.0
            private set

        private var previousVerticalMetresPerSecond: Double? = null
        private var previousAtMs: Long = 0L
        private var seeded = false

        fun seedIfEmpty(positive: Double?, negative: Double?) {
            if (seeded) return
            positiveMeters = positive?.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
            negativeMeters = negative?.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
            seeded = true
        }

        fun observe(speedKph: Double?, inclinationPercent: Double?, atMs: Long) {
            val speed = speedKph?.takeIf { it.isFinite() } ?: run {
                breakContinuity()
                return
            }
            val grade = inclinationPercent?.takeIf { it.isFinite() } ?: run {
                breakContinuity()
                return
            }
            if (!seeded) seedIfEmpty(null, null)

            val verticalRate = (speed.coerceAtLeast(0.0) / 3.6) * sin(atan(grade / 100.0))
            val prior = previousVerticalMetresPerSecond
            val dt = atMs - previousAtMs
            if (prior != null && dt in 1..FtmsMetricSnapshot.METRIC_FRESHNESS_MS) {
                val deltaMeters = ((prior + verticalRate) / 2.0) * (dt / 1000.0)
                if (deltaMeters >= 0.0) {
                    positiveMeters += deltaMeters
                } else {
                    negativeMeters += -deltaMeters
                }
            }
            previousVerticalMetresPerSecond = verticalRate
            previousAtMs = atMs
        }

        fun breakContinuity() {
            previousVerticalMetresPerSecond = null
            previousAtMs = 0L
        }

        fun clear() {
            positiveMeters = 0.0
            negativeMeters = 0.0
            seeded = false
            breakContinuity()
        }
    }

    companion object {
        const val METRES_PER_FLOOR = 3.0
        private const val MIN_FORCE_SPEED_METRES_PER_SECOND = 0.01
    }
}
