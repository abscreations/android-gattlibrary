package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.FitBridgeBleInternalApi

import kotlin.math.pow

/** Manufacturer-model estimates used only when KayakFirst does not transmit a native metric. */
@FitBridgeBleInternalApi
object KayakFirstDerivedMetrics {
    const val MODEL_COEFFICIENT = 0.1419262
    const val KAYAK_MASS_KG = 12.0

    /**
     * Effective handle pull length used by the force/cadence power model.
     *
     * `0.600 m` is the KayakFit shipped calibration, fitted so that
     * `force x length x cadence / 60` reproduces the console's own displayed power. It is a
     * FitBridge/KayakFit model parameter, not a KayakFirst-published constant.
     */
    const val DEFAULT_PULL_LENGTH_M = 0.600
    val PULL_LENGTH_RANGE_M = 0.3..1.2

    fun normalizePullLength(metres: Double?): Double =
        metres?.takeIf { it.isFinite() && it in PULL_LENGTH_RANGE_M } ?: DEFAULT_PULL_LENGTH_M

    /**
     * Preferred Blue/Bull estimator: mechanical work per stroke over stroke period.
     *
     * Both inputs are instantaneous console fields from the same frame, which is what makes this
     * checkable against the console display. Returns null when either input is unusable so the
     * caller can fall back to the speed model rather than publishing a fabricated zero.
     */
    fun estimatePowerFromForceAndCadence(
        forceNewtons: Double,
        cadenceSpm: Int,
        pullLengthM: Double = DEFAULT_PULL_LENGTH_M,
    ): Double? {
        if (!forceNewtons.isFinite() || forceNewtons < 0.0) return null
        if (cadenceSpm < 0) return null
        // No cadence means no strokes, which is a real zero.
        if (cadenceSpm == 0) return 0.0
        // Cadence without force is not a physical state; it means this console or firmware is not
        // populating the force fields. Fall back rather than publishing a false zero.
        if (forceNewtons == 0.0) return null
        return forceNewtons * normalizePullLength(pullLengthM) * cadenceSpm / 60.0
    }

    fun estimatePowerFromSpeed(speedMps: Double, athleteWeightKg: Double?): Double? {
        if (!speedMps.isFinite() || speedMps < 0.0) return null
        if (speedMps == 0.0) return 0.0
        val athleteMass = athleteWeightKg?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val effectiveMassKg = athleteMass + KAYAK_MASS_KG
        return MODEL_COEFFICIENT * effectiveMassKg.pow(2.0 / 3.0) * speedMps.pow(3.0)
    }

    /**
     * Blue/Bull instantaneous power precedence.
     *
     * 1. force x pull length x cadence, from the instantaneous console fields;
     * 2. the inverted E2 modeled-speed relation, when force/cadence are unusable.
     *
     * Both remain estimates. A native power field, if one is ever proven, outranks both.
     */
    fun estimatePower(
        reading: KayakFirstMeasurement.Reading,
        athleteWeightKg: Double?,
        pullLengthM: Double = DEFAULT_PULL_LENGTH_M,
    ): Double? = estimatePowerFromForceAndCadence(
        reading.forceNewtons,
        reading.cadenceSpm,
        pullLengthM,
    ) ?: estimatePowerFromSpeed(reading.speedMetresPerSecond, athleteWeightKg)
}

/**
 * Resolves the non-overlapping seconds one accepted frame represents.
 *
 * The console states its own coverage in field 23, and the elapsed counter in field 22 states how
 * far the session clock actually moved. The represented duration is the smaller of the two: field
 * 23 alone would double-count when the console repeats a window, and the elapsed delta alone loses
 * the first post-start frame, which legitimately covers everything since the workout began.
 *
 * A stalled or reset elapsed counter yields zero represented seconds rather than a negative or
 * fabricated one; the caller keeps that time as pause.
 */
internal class KayakFirstSampleWindowTracker {
    private var previousElapsedSeconds: Long? = null

    fun accept(reading: KayakFirstMeasurement.Reading): Long {
        val previous = previousElapsedSeconds
        previousElapsedSeconds = reading.elapsedSeconds
        val declared = reading.coverageWindowSeconds
        if (previous == null) {
            // First frame of the generation: trust the console's declared coverage, or nothing.
            return declared ?: 0L
        }
        val elapsedDelta = reading.elapsedSeconds - previous
        if (elapsedDelta <= 0L) return 0L
        return declared?.coerceAtMost(elapsedDelta) ?: elapsedDelta
    }

    fun reset() {
        previousElapsedSeconds = null
    }
}

/** What the field-7 activity flag did across two accepted frames. */
internal enum class KayakFirstActivityTransition { STARTED, RESUMED, PAUSED, UNCHANGED }

/**
 * Converts the field-7 stream into session start/pause/resume transitions.
 *
 * A stopped console keeps answering polls with held frames whose field 7 is 0, so this flag — not
 * frozen speed or a stalled distance — is the authoritative activity signal. A pause is only
 * declared once the console has been inactive for [pauseDelaySeconds]: at low cadence field 7 can
 * clear briefly between strokes, and reacting to that would fragment a continuous effort.
 */
internal class KayakFirstActivityGate(
    private val pauseDelaySeconds: Long = MEANINGFUL_PAUSE_SECONDS,
) {
    private var started = false
    private var active = false
    private var inactiveSeconds = 0L

    /** True while the session is considered under way; integration is gated on this. */
    val isActive: Boolean get() = active

    fun accept(
        reading: KayakFirstMeasurement.Reading,
        representedSeconds: Long,
    ): KayakFirstActivityTransition {
        if (reading.activePaddling) {
            inactiveSeconds = 0L
            if (!active) {
                active = true
                val transition = if (started) {
                    KayakFirstActivityTransition.RESUMED
                } else {
                    KayakFirstActivityTransition.STARTED
                }
                started = true
                return transition
            }
            return KayakFirstActivityTransition.UNCHANGED
        }

        if (!active) return KayakFirstActivityTransition.UNCHANGED
        inactiveSeconds += representedSeconds.coerceAtLeast(0L)
        if (inactiveSeconds < pauseDelaySeconds) return KayakFirstActivityTransition.UNCHANGED
        active = false
        return KayakFirstActivityTransition.PAUSED
    }

    fun reset() {
        started = false
        active = false
        inactiveSeconds = 0L
    }

    companion object {
        const val MEANINGFUL_PAUSE_SECONDS = 5L
    }
}

/**
 * Field-23 time-weighted average power.
 *
 * The weight is the represented duration resolved by [KayakFirstSampleWindowTracker], not the
 * frame count: a held frame that covers two seconds must weigh twice a one-second frame, and a
 * duplicate covering zero seconds must weigh nothing. Inactive time is excluded entirely, so a
 * pause cannot drag the average down.
 */
internal class KayakFirstAveragePowerAccumulator {
    private var previousPowerWatts = 0.0
    private var workWattSeconds = 0.0
    private var activeSeconds = 0.0

    /**
     * Seconds are a [Double] because not every KayakFirst persona has an integer window: Blue
     * resolves whole seconds from fields 22/23, while Orange has no window field at all and its
     * central integrates over the real interval between accepted tuples.
     */
    fun sample(representedSeconds: Double, powerWatts: Double, active: Boolean = true): Double {
        require(representedSeconds.isFinite() && representedSeconds >= 0.0)
        require(powerWatts.isFinite() && powerWatts >= 0.0)
        if (active && representedSeconds > 0.0) {
            workWattSeconds += (previousPowerWatts + powerWatts) * 0.5 * representedSeconds
            activeSeconds += representedSeconds
        }
        previousPowerWatts = if (active) powerWatts else 0.0
        return if (activeSeconds <= 0.0) powerWatts else workWattSeconds / activeSeconds
    }

    fun sample(representedSeconds: Long, powerWatts: Double, active: Boolean = true): Double =
        sample(representedSeconds.toDouble(), powerWatts, active)

    fun reset() {
        previousPowerWatts = 0.0
        workWattSeconds = 0.0
        activeSeconds = 0.0
    }
}

/**
 * Shared mechanical-work energy estimator for KayakFirst personas.
 *
 * FitBridge reports metabolic kcal using the existing rower-model policy of 23% efficiency.
 * Integration is weighted by the console's own represented window (field 23 intersected with the
 * field 22 elapsed delta) and gated on field-7 activity; arrival time is deliberately irrelevant.
 */
internal class KayakFirstEnergyEstimator {
    private var previousPowerWatts = 0.0
    private var energyKilocalories = 0.0

    fun sample(
        representedSeconds: Long,
        powerWatts: Double,
        active: Boolean = true,
    ): KayakFirstEnergyEstimate = sample(representedSeconds.toDouble(), powerWatts, active)

    /**
     * The weight is the duration this frame represents, never the cumulative session clock.
     * Passing elapsed time here integrates power against the whole session on every frame and
     * grows energy quadratically.
     */
    fun sample(
        representedSeconds: Double,
        powerWatts: Double,
        active: Boolean = true,
    ): KayakFirstEnergyEstimate {
        require(representedSeconds.isFinite() && representedSeconds >= 0.0)
        require(powerWatts.isFinite() && powerWatts >= 0.0)
        if (active && representedSeconds > 0.0) {
            val averagePowerWatts = (previousPowerWatts + powerWatts) * 0.5
            energyKilocalories += averagePowerWatts * representedSeconds /
                (JOULES_PER_KILOCALORIE * METABOLIC_EFFICIENCY)
        }
        previousPowerWatts = if (active) powerWatts else 0.0
        return KayakFirstEnergyEstimate(
            totalKilocalories = kotlin.math.round(energyKilocalories).coerceAtLeast(0.0),
            kilocaloriesPerHour = powerWatts * 3_600.0 /
                (JOULES_PER_KILOCALORIE * METABOLIC_EFFICIENCY),
        )
    }

    fun reset() {
        previousPowerWatts = 0.0
        energyKilocalories = 0.0
    }

    companion object {
        const val JOULES_PER_KILOCALORIE = 4_184.0
        const val METABOLIC_EFFICIENCY = 0.23
    }
}

@FitBridgeBleInternalApi
data class KayakFirstEnergyEstimate(
    val totalKilocalories: Double,
    val kilocaloriesPerHour: Double,
)

internal enum class KayakFirstPaceAnomaly { AVERAGE_500, INSTANT_500, AVERAGE_1000, INSTANT_1000 }

internal fun KayakFirstMeasurement.Reading.paceAnomalies(
    toleranceSeconds: Long = 2L,
): Set<KayakFirstPaceAnomaly> {
    fun differs(actual: Long, expected: Double) = kotlin.math.abs(actual - expected) > toleranceSeconds
    return buildSet {
        if (differs(average500mPaceSeconds, average200mPaceSeconds * 2.5)) {
            add(KayakFirstPaceAnomaly.AVERAGE_500)
        }
        if (differs(instantaneous500mPaceSeconds, instantaneous200mPaceSeconds * 2.5)) {
            add(KayakFirstPaceAnomaly.INSTANT_500)
        }
        if (differs(average1000mPaceSeconds, average200mPaceSeconds * 5.0)) {
            add(KayakFirstPaceAnomaly.AVERAGE_1000)
        }
        if (differs(instantaneous1000mPaceSeconds, instantaneous200mPaceSeconds * 5.0)) {
            add(KayakFirstPaceAnomaly.INSTANT_1000)
        }
    }
}
