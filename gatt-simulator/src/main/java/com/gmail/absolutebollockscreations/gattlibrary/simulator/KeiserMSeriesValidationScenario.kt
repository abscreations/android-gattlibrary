package com.gmail.absolutebollockscreations.gattlibrary.simulator

import kotlin.math.roundToInt

/** Deterministic five-minute adversarial ride shared by the Android M3i, M3m and M3o personas. */
internal class KeiserMSeriesValidationScenario(
    private val profile: KeiserMSeriesSimulatorProfile,
) {
    enum class Event {
        RIDE,
        POWER_DROPOUT,
        CADENCE_DROPOUT,
        MALFORMED,
        REALTIME_BOUNDARY,
        DISTANCE_BOUNDARY,
        COAST,
        REPEATED_UNCHANGED,
        STALE_SILENCE,
        RECOVERY,
    }

    data class Output(
        val sample: FtmsMockSample,
        /** Null means stop transmitting until a later output supplies a new value. */
        val manufacturerPayload: ByteArray?,
        val event: Event,
        val loopElapsedMs: Long,
        val loopIndex: Long,
    )

    private var elapsedMs = 0L
    private var previousLoopElapsedMs = -1L
    private var coastDistanceMeters: Int? = null
    private var distanceOffsetMeters = 0
    private var repeatedSample: FtmsMockSample? = null
    private var repeatedPayload: ByteArray? = null
    private var recoveringFromSilence = false

    fun next(generated: FtmsMockSample): Output {
        val loopIndex = elapsedMs / LOOP_DURATION_MS
        val at = elapsedMs % LOOP_DURATION_MS
        if (at < previousLoopElapsedMs) resetLoopState()

        var sample = generated.copy(
            distanceMeters = generated.distanceMeters?.let { (it - distanceOffsetMeters).coerceAtLeast(0) },
        )
        var event = Event.RIDE
        var dataType = KeiserMSeriesSimulatorEncoder.DATA_TYPE_REALTIME
        var distanceBoundary: Int? = null

        if (at in COAST_START_MS until COAST_END_MS) {
            val heldDistance = coastDistanceMeters ?: sample.distanceMeters ?: 0
            coastDistanceMeters = heldDistance
            generated.distanceMeters?.let { distanceOffsetMeters = (it - heldDistance).coerceAtLeast(0) }
            sample = generated.copy(cadence = 0.0, powerWatts = 0, distanceMeters = heldDistance)
            event = Event.COAST
        } else {
            coastDistanceMeters = null
        }

        if (crossed(at, POWER_DROPOUT_AT_MS)) {
            sample = sample.copy(powerWatts = 0)
            event = Event.POWER_DROPOUT
        } else if (crossed(at, CADENCE_DROPOUT_AT_MS)) {
            sample = sample.copy(cadence = 0.0)
            event = Event.CADENCE_DROPOUT
        }

        realtimeBoundaryIndex(at)?.let { index ->
            dataType = REALTIME_BOUNDARY_TYPES[index]
            event = Event.REALTIME_BOUNDARY
        }
        distanceBoundaryIndex(at)?.let { index ->
            distanceBoundary = DISTANCE_BOUNDARY_TENTHS[index]
            sample = sample.copy(
                distanceMeters = (distanceBoundary * profile.distanceUnit.metresPerTenth).roundToInt(),
            )
            event = Event.DISTANCE_BOUNDARY
        }

        val inStaleSilence = at in STALE_START_MS until
            (STALE_START_MS + profile.statsTimeoutMs + profile.samplePeriodMs)
        if (inStaleSilence) {
            recoveringFromSilence = true
            previousLoopElapsedMs = at
            elapsedMs += profile.samplePeriodMs
            return Output(
                sample = sample,
                manufacturerPayload = null,
                event = Event.STALE_SILENCE,
                loopElapsedMs = at,
                loopIndex = loopIndex,
            )
        }

        val repeat = at in REPEAT_START_MS until REPEAT_END_MS
        if (repeat) {
            if (repeatedPayload == null) {
                repeatedSample = sample
                repeatedPayload = KeiserMSeriesSimulatorEncoder.encode(
                    sample = sample,
                    profile = profile,
                    dataType = dataType,
                    distanceRawTenths = distanceBoundary,
                )
            }
            sample = requireNotNull(repeatedSample)
            event = Event.REPEATED_UNCHANGED
        } else {
            repeatedSample = null
            repeatedPayload = null
        }

        var payload = repeatedPayload ?: KeiserMSeriesSimulatorEncoder.encode(
            sample = sample,
            profile = profile,
            dataType = dataType,
            distanceRawTenths = distanceBoundary,
        )

        if (!repeat && crossedMalformedBoundary(at)) {
            // Current firmware requires 17 value bytes. Removing resistance makes this packet
            // structurally invalid while retaining the right company ID and discovery name.
            payload = payload.copyOf(payload.size - 1)
            event = Event.MALFORMED
        } else if (recoveringFromSilence) {
            recoveringFromSilence = false
            event = Event.RECOVERY
        }

        previousLoopElapsedMs = at
        elapsedMs += profile.samplePeriodMs
        return Output(sample, payload.copyOf(), event, at, loopIndex)
    }

    private fun crossed(now: Long, boundary: Long): Boolean =
        now >= boundary && previousLoopElapsedMs < boundary

    private fun crossedMalformedBoundary(now: Long): Boolean {
        if (previousLoopElapsedMs < 0) return false
        return now / MALFORMED_PERIOD_MS > previousLoopElapsedMs / MALFORMED_PERIOD_MS
    }

    private fun realtimeBoundaryIndex(now: Long): Int? {
        if (now < REALTIME_BOUNDARY_START_MS) return null
        val index = ((now - REALTIME_BOUNDARY_START_MS) / profile.samplePeriodMs).toInt()
        return index.takeIf { it in REALTIME_BOUNDARY_TYPES.indices }
    }

    private fun distanceBoundaryIndex(now: Long): Int? {
        if (now < DISTANCE_BOUNDARY_START_MS) return null
        val index = ((now - DISTANCE_BOUNDARY_START_MS) / profile.samplePeriodMs).toInt()
        return index.takeIf { it in DISTANCE_BOUNDARY_TENTHS.indices }
    }

    private fun resetLoopState() {
        previousLoopElapsedMs = -1L
        coastDistanceMeters = null
        repeatedSample = null
        repeatedPayload = null
        recoveringFromSilence = false
    }

    companion object {
        const val LOOP_DURATION_MS = 300_000L
        const val MALFORMED_PERIOD_MS = 15_000L
        const val COAST_START_MS = 90_000L
        const val COAST_END_MS = 120_000L
        const val REPEAT_START_MS = 122_000L
        const val REPEAT_END_MS = 127_000L
        const val STALE_START_MS = 137_000L
        private const val POWER_DROPOUT_AT_MS = 32_000L
        private const val CADENCE_DROPOUT_AT_MS = 37_000L
        private const val REALTIME_BOUNDARY_START_MS = 62_000L
        private const val DISTANCE_BOUNDARY_START_MS = 77_000L
        private val REALTIME_BOUNDARY_TYPES = intArrayOf(0x00, 0x01, 0x80, 0x81, 0xfe, 0xff)
        private val DISTANCE_BOUNDARY_TENTHS = intArrayOf(0x7ffe, 0x7fff, 0x0000)
    }
}
