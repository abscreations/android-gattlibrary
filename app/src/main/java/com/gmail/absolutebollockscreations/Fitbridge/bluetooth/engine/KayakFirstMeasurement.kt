package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

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
    POWER_OUT_OF_RANGE,
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
 * Only five evidenced fields are named. The device timestamp and status remain uninterpreted; the
 * latter is retained verbatim solely so an armed diagnostic path can report future evidence.
 */
internal object KayakFirstMeasurement {
    const val MINIMUM_FIELDS = 24
    const val DISTANCE_INDEX = 9
    const val SPEED_INDEX = 11
    const val CADENCE_INDEX = 13
    const val POWER_INDEX = 21
    const val ELAPSED_INDEX = 22
    const val STATUS_INDEX = 23
    const val MAX_SPEED_KPH = 40.0
    const val MAX_CADENCE_SPM = 120
    const val MAX_POWER_WATTS = 3_000

    private val UNSIGNED_INTEGER = Regex("^[0-9]+$")
    private val UNSIGNED_DECIMAL = Regex("^[0-9]+(?:\\.[0-9]+)?$")

    data class Reading(
        val distanceMetres: Double,
        val speedMetresPerSecond: Double,
        val cadenceSpm: Int,
        val powerWatts: Int,
        val elapsedSeconds: Long,
        val diagnosticStatusRaw: String,
        val fieldCount: Int,
    ) {
        val speedKph: Double get() = speedMetresPerSecond * 3.6
    }

    fun decode(line: String): KayakFirstMeasurementDecodeResult {
        if (line.isEmpty()) return rejected(KayakFirstMeasurementRejection.EMPTY_LINE)
        val fields = line.split(';')
        if (fields.first() != "6") return rejected(KayakFirstMeasurementRejection.WRONG_COMMAND, 0)
        if (fields.size < MINIMUM_FIELDS) {
            return rejected(KayakFirstMeasurementRejection.TOO_FEW_FIELDS)
        }

        lexicalRejection(fields[DISTANCE_INDEX], DISTANCE_INDEX, UNSIGNED_DECIMAL)?.let { return it }
        lexicalRejection(fields[SPEED_INDEX], SPEED_INDEX, UNSIGNED_DECIMAL)?.let { return it }
        lexicalRejection(fields[CADENCE_INDEX], CADENCE_INDEX, UNSIGNED_INTEGER)?.let { return it }
        lexicalRejection(fields[POWER_INDEX], POWER_INDEX, UNSIGNED_INTEGER)?.let { return it }
        lexicalRejection(fields[ELAPSED_INDEX], ELAPSED_INDEX, UNSIGNED_INTEGER)?.let { return it }

        val distance = fields[DISTANCE_INDEX].toDoubleOrNull()
            ?.takeIf { it.isFinite() }
            ?: return rejected(KayakFirstMeasurementRejection.NUMERIC_OVERFLOW, DISTANCE_INDEX)
        val speedMps = fields[SPEED_INDEX].toDoubleOrNull()
            ?.takeIf { it.isFinite() }
            ?: return rejected(KayakFirstMeasurementRejection.NUMERIC_OVERFLOW, SPEED_INDEX)
        val cadence = fields[CADENCE_INDEX].toIntOrNull()
            ?: return rejected(KayakFirstMeasurementRejection.NUMERIC_OVERFLOW, CADENCE_INDEX)
        val power = fields[POWER_INDEX].toIntOrNull()
            ?: return rejected(KayakFirstMeasurementRejection.NUMERIC_OVERFLOW, POWER_INDEX)
        val elapsed = fields[ELAPSED_INDEX].toLongOrNull()
            ?: return rejected(KayakFirstMeasurementRejection.NUMERIC_OVERFLOW, ELAPSED_INDEX)

        val speedKph = speedMps * 3.6
        if (!speedKph.isFinite() || speedKph !in 0.0..MAX_SPEED_KPH) {
            return rejected(KayakFirstMeasurementRejection.SPEED_OUT_OF_RANGE, SPEED_INDEX)
        }
        if (cadence !in 0..MAX_CADENCE_SPM) {
            return rejected(KayakFirstMeasurementRejection.CADENCE_OUT_OF_RANGE, CADENCE_INDEX)
        }
        if (power !in 0..MAX_POWER_WATTS) {
            return rejected(KayakFirstMeasurementRejection.POWER_OUT_OF_RANGE, POWER_INDEX)
        }

        return KayakFirstMeasurementDecodeResult.Decoded(
            Reading(
                distanceMetres = distance,
                speedMetresPerSecond = speedMps,
                cadenceSpm = cadence,
                powerWatts = power,
                elapsedSeconds = elapsed,
                diagnosticStatusRaw = fields[STATUS_INDEX],
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
