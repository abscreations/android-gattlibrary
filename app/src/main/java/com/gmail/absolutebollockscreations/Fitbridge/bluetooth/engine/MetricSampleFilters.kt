package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import kotlin.math.round

/** Stateful transformation applied to one source before a valid sample enters selection. */
internal interface MetricSampleFilter {
    fun filter(sample: MetricSample): MetricSample
    fun reset()
}

/**
 * Preserves the legacy external-HRM spike protection without owning source selection or state.
 * One instance is used per [MetricSourceId], so independent straps never influence each other.
 */
internal class HeartRateJumpEmaFilter : MetricSampleFilter {
    private var smoothed: Double? = null
    private var smoothedAtMs: Long = 0L

    override fun filter(sample: MetricSample): MetricSample {
        require(sample.metric == MetricKind.HEART_RATE)
        val previous = smoothed
        if (previous == null) {
            smoothed = sample.value
            smoothedAtMs = sample.arrivalMs
            return sample
        }
        require(sample.arrivalMs >= smoothedAtMs) { "HR filter arrival time moved backwards" }
        val deltaSeconds = (sample.arrivalMs - smoothedAtMs).coerceAtLeast(1L) / 1_000.0
        val maximumJump = MAX_JUMP_PER_SECOND * deltaSeconds
        val clamped = sample.value.coerceIn(previous - maximumJump, previous + maximumJump)
        val alpha = (EMA_ALPHA * deltaSeconds).coerceIn(MIN_ALPHA, MAX_ALPHA)
        val next = previous * (1.0 - alpha) + clamped * alpha
        smoothed = next
        smoothedAtMs = sample.arrivalMs
        return sample.copy(value = round(next))
    }

    override fun reset() {
        smoothed = null
        smoothedAtMs = 0L
    }

    private companion object {
        const val EMA_ALPHA = 0.35
        const val MIN_ALPHA = 0.20
        const val MAX_ALPHA = 0.85
        const val MAX_JUMP_PER_SECOND = 18.0
    }
}
