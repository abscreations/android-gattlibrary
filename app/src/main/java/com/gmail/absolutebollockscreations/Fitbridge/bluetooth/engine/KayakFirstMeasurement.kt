package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.core.FitBridgeBleInternalApi

/** Typed rejection for one complete Kayak First measurement line. */
internal enum class KayakFirstMeasurementRejection {
    EMPTY_LINE,
    WRONG_COMMAND,
    TOO_FEW_FIELDS,
    EMPTY_FIELD,
    SIGNED_VALUE,
    INVALID_NUMBER,
    NUMERIC_OVERFLOW,
    SPEED_OUT_OF_RANGE,
    CADENCE_OUT_OF_RANGE,
}

internal sealed interface KayakFirstMeasurementDecodeResult {
    data class Decoded(val reading: KayakFirstMeasurement.Reading) : KayakFirstMeasurementDecodeResult
    data class Rejected(
        val reason: KayakFirstMeasurementRejection,
        val fieldIndex: Int? = null,
    ) : KayakFirstMeasurementDecodeResult
}

/**
 * Pure decoder for the 24-field command-6 response defined by the Phase 8 wire specification.
 *
 * The field meanings follow the official E2 4.9.3 application and bundled firmware, converged with
 * the shipping KayakFit Bull client. Display selector IDs are a separate protocol namespace and
 * must never be used as command-6 indexes.
 *
 * Fields 20/21 are typed as newtons. The evidence is the KayakFit calibration, which fits
 * `force x pullLength x cadence / 60` against the console's own displayed power to within a few
 * watts. That fit confirms field 21 is linear in true pull force and dimensionally consistent with
 * cadence; the pull-length constant and any residual force scale remain degenerate in it, so the
 * newton typing is a converged working assumption, not a synchronized-console proof.
 *
 * Field 7 is the console's active-paddling flag: 1 while strokes are being detected, 0 when idle
 * and on the stale repeated frames a stopped console keeps returning. It is both the stroke-event
 * freshness gate and the session activity signal.
 *
 * Field 23 is the coverage window in seconds for this datapoint: 1 in steady polling, 2 when a poll
 * was missed, and larger on the first frame after a start. It is the time weight for every
 * integrated quantity. It is never allowed to reject or rewrite a metric.
 */
@FitBridgeBleInternalApi
object KayakFirstMeasurement {
    const val MINIMUM_FIELDS = 24
    const val EVENT_TIMESTAMP_INDEX = 1
    const val ACTIVE_PADDLING_INDEX = 7
    const val DISTANCE_INDEX = 9
    const val AVERAGE_SPEED_INDEX = 10
    const val SPEED_INDEX = 11
    const val AVERAGE_CADENCE_INDEX = 12
    const val CADENCE_INDEX = 13
    const val AVERAGE_200M_PACE_INDEX = 14
    const val INSTANTANEOUS_200M_PACE_INDEX = 15
    const val AVERAGE_500M_PACE_INDEX = 16
    const val INSTANTANEOUS_500M_PACE_INDEX = 17
    const val AVERAGE_1000M_PACE_INDEX = 18
    const val INSTANTANEOUS_1000M_PACE_INDEX = 19
    const val AVERAGE_FORCE_INDEX = 20
    const val FORCE_INDEX = 21
    const val ELAPSED_INDEX = 22
    const val WINDOW_INDEX = 23
    const val MAX_SPEED_KPH = 40.0
    const val MAX_CADENCE_SPM = 120

    /**
     * Upper clamp for the field-23 coverage window.
     *
     * A first frame after start legitimately covers everything since the workout began, so the
     * field is not bounded by the poll period. It is clamped only so a corrupt value cannot inject
     * an enormous integration weight, and it is intersected with the elapsed delta downstream.
     */
    const val MAX_WINDOW_SECONDS = 3_600L

    private val UNSIGNED_INTEGER = Regex("^[0-9]+$")
    private val UNSIGNED_DECIMAL = Regex("^[0-9]+(?:\\.[0-9]+)?$")

    data class Reading(
        val eventTimestampMs: Long = 0L,
        val activePaddling: Boolean = true,
        val distanceMetres: Double,
        val speedMetresPerSecond: Double,
        val cadenceSpm: Int,
        val forceNewtons: Double,
        val elapsedSeconds: Long,
        val averageSpeedMetresPerSecond: Double = 0.0,
        val averageCadenceSpm: Int = 0,
        val average200mPaceSeconds: Long = 0,
        val instantaneous200mPaceSeconds: Long = 0,
        val average500mPaceSeconds: Long = 0,
        val instantaneous500mPaceSeconds: Long = 0,
        val average1000mPaceSeconds: Long = 0,
        val instantaneous1000mPaceSeconds: Long = 0,
        val averageForceNewtons: Double = 0.0,
        val windowSeconds: Long? = null,
        val diagnosticStatusRaw: String,
        val fieldCount: Int,
    ) {
        val speedKph: Double get() = speedMetresPerSecond * 3.6
        val averageSpeedKph: Double get() = averageSpeedMetresPerSecond * 3.6

        /** Field 23 clamped for use as an integration weight; null when the console omitted it. */
        val coverageWindowSeconds: Long?
            get() = windowSeconds?.takeIf { it > 0L }?.coerceAtMost(MAX_WINDOW_SECONDS)
    }

    internal fun decode(line: String): KayakFirstMeasurementDecodeResult {
        if (line.isEmpty()) return rejected(KayakFirstMeasurementRejection.EMPTY_LINE)
        val fields = line.split(';')
        if (fields.first() != "6") return rejected(KayakFirstMeasurementRejection.WRONG_COMMAND, 0)
        if (fields.size < MINIMUM_FIELDS) {
            return rejected(KayakFirstMeasurementRejection.TOO_FEW_FIELDS)
        }

        lexicalRejection(fields[EVENT_TIMESTAMP_INDEX], EVENT_TIMESTAMP_INDEX, UNSIGNED_INTEGER)?.let { return it }
        lexicalRejection(fields[ACTIVE_PADDLING_INDEX], ACTIVE_PADDLING_INDEX, UNSIGNED_INTEGER)?.let { return it }

        lexicalRejection(fields[DISTANCE_INDEX], DISTANCE_INDEX, UNSIGNED_DECIMAL)?.let { return it }
        listOf(AVERAGE_SPEED_INDEX, SPEED_INDEX).forEach { index ->
            lexicalRejection(fields[index], index, UNSIGNED_DECIMAL)?.let { return it }
        }
        listOf(AVERAGE_CADENCE_INDEX, CADENCE_INDEX).forEach { index ->
            lexicalRejection(fields[index], index, UNSIGNED_INTEGER)?.let { return it }
        }
        listOf(AVERAGE_FORCE_INDEX, FORCE_INDEX).forEach { index ->
            lexicalRejection(fields[index], index, UNSIGNED_DECIMAL)?.let { return it }
        }
        listOf(
            AVERAGE_200M_PACE_INDEX,
            INSTANTANEOUS_200M_PACE_INDEX,
            AVERAGE_500M_PACE_INDEX,
            INSTANTANEOUS_500M_PACE_INDEX,
            AVERAGE_1000M_PACE_INDEX,
            INSTANTANEOUS_1000M_PACE_INDEX,
        ).forEach { index ->
            lexicalRejection(fields[index], index, UNSIGNED_INTEGER)?.let { return it }
        }
        lexicalRejection(fields[ELAPSED_INDEX], ELAPSED_INDEX, UNSIGNED_INTEGER)?.let { return it }

        val distance = fields[DISTANCE_INDEX].toDoubleOrNull()
            ?.takeIf { it.isFinite() }
            ?: return rejected(KayakFirstMeasurementRejection.NUMERIC_OVERFLOW, DISTANCE_INDEX)
        val eventTimestampMs = fields[EVENT_TIMESTAMP_INDEX].toLongOrNull()
            ?: return rejected(KayakFirstMeasurementRejection.NUMERIC_OVERFLOW, EVENT_TIMESTAMP_INDEX)
        val activeFlag = fields[ACTIVE_PADDLING_INDEX].toIntOrNull()
            ?: return rejected(KayakFirstMeasurementRejection.NUMERIC_OVERFLOW, ACTIVE_PADDLING_INDEX)
        if (activeFlag !in 0..1) return rejected(KayakFirstMeasurementRejection.INVALID_NUMBER, ACTIVE_PADDLING_INDEX)
        val speedMps = fields[SPEED_INDEX].toDoubleOrNull()
            ?.takeIf { it.isFinite() }
            ?: return rejected(KayakFirstMeasurementRejection.NUMERIC_OVERFLOW, SPEED_INDEX)
        val averageSpeedMps = fields[AVERAGE_SPEED_INDEX].toDoubleOrNull()
            ?.takeIf { it.isFinite() }
            ?: return rejected(KayakFirstMeasurementRejection.NUMERIC_OVERFLOW, AVERAGE_SPEED_INDEX)
        val averageCadence = fields[AVERAGE_CADENCE_INDEX].toIntOrNull()
            ?: return rejected(KayakFirstMeasurementRejection.NUMERIC_OVERFLOW, AVERAGE_CADENCE_INDEX)
        val cadence = fields[CADENCE_INDEX].toIntOrNull()
            ?: return rejected(KayakFirstMeasurementRejection.NUMERIC_OVERFLOW, CADENCE_INDEX)
        val averageForceNewtons = fields[AVERAGE_FORCE_INDEX].toDoubleOrNull()
            ?.takeIf { it.isFinite() }
            ?: return rejected(KayakFirstMeasurementRejection.NUMERIC_OVERFLOW, AVERAGE_FORCE_INDEX)
        val forceNewtons = fields[FORCE_INDEX].toDoubleOrNull()
            ?.takeIf { it.isFinite() }
            ?: return rejected(KayakFirstMeasurementRejection.NUMERIC_OVERFLOW, FORCE_INDEX)
        val elapsed = fields[ELAPSED_INDEX].toLongOrNull()
            ?: return rejected(KayakFirstMeasurementRejection.NUMERIC_OVERFLOW, ELAPSED_INDEX)
        // Field 23 is read leniently on purpose: it is a time weight, not a gate. A console that
        // omits it or sends something unparseable still yields a usable measurement, and the
        // downstream window tracker falls back to the elapsed delta.
        val windowSeconds = fields[WINDOW_INDEX]
            .takeIf { UNSIGNED_INTEGER.matches(it) }
            ?.toLongOrNull()

        val averageSpeedKph = averageSpeedMps * 3.6
        if (!averageSpeedKph.isFinite() || averageSpeedKph !in 0.0..MAX_SPEED_KPH) {
            return rejected(KayakFirstMeasurementRejection.SPEED_OUT_OF_RANGE, AVERAGE_SPEED_INDEX)
        }
        val speedKph = speedMps * 3.6
        if (!speedKph.isFinite() || speedKph !in 0.0..MAX_SPEED_KPH) {
            return rejected(KayakFirstMeasurementRejection.SPEED_OUT_OF_RANGE, SPEED_INDEX)
        }
        if (averageCadence !in 0..MAX_CADENCE_SPM) {
            return rejected(KayakFirstMeasurementRejection.CADENCE_OUT_OF_RANGE, AVERAGE_CADENCE_INDEX)
        }
        if (cadence !in 0..MAX_CADENCE_SPM) {
            return rejected(KayakFirstMeasurementRejection.CADENCE_OUT_OF_RANGE, CADENCE_INDEX)
        }
        val paceValues = mutableMapOf<Int, Long>()
        listOf(
            AVERAGE_200M_PACE_INDEX,
            INSTANTANEOUS_200M_PACE_INDEX,
            AVERAGE_500M_PACE_INDEX,
            INSTANTANEOUS_500M_PACE_INDEX,
            AVERAGE_1000M_PACE_INDEX,
            INSTANTANEOUS_1000M_PACE_INDEX,
        ).forEach { index ->
            paceValues[index] = fields[index].toLongOrNull()
                ?: return rejected(KayakFirstMeasurementRejection.NUMERIC_OVERFLOW, index)
        }

        return KayakFirstMeasurementDecodeResult.Decoded(
            Reading(
                eventTimestampMs = eventTimestampMs,
                activePaddling = activeFlag == 1,
                distanceMetres = distance,
                speedMetresPerSecond = speedMps,
                cadenceSpm = cadence,
                forceNewtons = forceNewtons,
                elapsedSeconds = elapsed,
                averageSpeedMetresPerSecond = averageSpeedMps,
                averageCadenceSpm = averageCadence,
                average200mPaceSeconds = paceValues.getValue(AVERAGE_200M_PACE_INDEX),
                instantaneous200mPaceSeconds = paceValues.getValue(INSTANTANEOUS_200M_PACE_INDEX),
                average500mPaceSeconds = paceValues.getValue(AVERAGE_500M_PACE_INDEX),
                instantaneous500mPaceSeconds = paceValues.getValue(INSTANTANEOUS_500M_PACE_INDEX),
                average1000mPaceSeconds = paceValues.getValue(AVERAGE_1000M_PACE_INDEX),
                instantaneous1000mPaceSeconds = paceValues.getValue(INSTANTANEOUS_1000M_PACE_INDEX),
                averageForceNewtons = averageForceNewtons,
                windowSeconds = windowSeconds,
                diagnosticStatusRaw = fields[WINDOW_INDEX],
                fieldCount = fields.size,
            ),
        )
    }

    private fun lexicalRejection(
        raw: String,
        index: Int,
        grammar: Regex,
    ): KayakFirstMeasurementDecodeResult.Rejected? = when {
        raw.isEmpty() -> rejected(KayakFirstMeasurementRejection.EMPTY_FIELD, index)
        raw.startsWith('+') || raw.startsWith('-') ->
            rejected(KayakFirstMeasurementRejection.SIGNED_VALUE, index)
        !grammar.matches(raw) -> rejected(KayakFirstMeasurementRejection.INVALID_NUMBER, index)
        else -> null
    }

    private fun rejected(
        reason: KayakFirstMeasurementRejection,
        fieldIndex: Int? = null,
    ) = KayakFirstMeasurementDecodeResult.Rejected(reason, fieldIndex)
}

internal enum class KayakFirstContinuityRejection {
    DISTANCE_DECREASED,
    ELAPSED_DECREASED,
}

internal sealed interface KayakFirstContinuityResult {
    data class Accepted(
        val reading: KayakFirstMeasurement.Reading,
        val establishedBaseline: Boolean,
    ) : KayakFirstContinuityResult

    data class Rejected(val reason: KayakFirstContinuityRejection) : KayakFirstContinuityResult
}

/** Pure per-session continuity guard. One instance belongs to one connection generation. */
internal class KayakFirstContinuityTracker {
    private var previousDistanceMetres: Double? = null
    private var previousElapsedSeconds: Long? = null

    fun accept(reading: KayakFirstMeasurement.Reading): KayakFirstContinuityResult {
        previousDistanceMetres?.let { previous ->
            if (reading.distanceMetres < previous) {
                return KayakFirstContinuityResult.Rejected(
                    KayakFirstContinuityRejection.DISTANCE_DECREASED,
                )
            }
        }
        previousElapsedSeconds?.let { previous ->
            if (reading.elapsedSeconds < previous) {
                return KayakFirstContinuityResult.Rejected(
                    KayakFirstContinuityRejection.ELAPSED_DECREASED,
                )
            }
        }

        val establishedBaseline = previousDistanceMetres == null
        previousDistanceMetres = reading.distanceMetres
        previousElapsedSeconds = reading.elapsedSeconds
        return KayakFirstContinuityResult.Accepted(reading, establishedBaseline)
    }

    /** Called for an accepted local start or a replacement connection generation. */
    fun reset() {
        previousDistanceMetres = null
        previousElapsedSeconds = null
    }
}
