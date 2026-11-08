package com.gmail.absolutebollockscreations.gattlibrary.central

/** Nominal legacy force sampled once per observed completed pull; never a measured load cell value. */
public data class MaxProForceSummary(
    val lastPounds: Double? = null,
    val averagePounds: Double? = null,
    val maximumPounds: Double? = null,
    val observedPulls: Long = 0,
)

internal class MaxProForceTracker {
    private var previousCount: Int? = null
    var summary = MaxProForceSummary()
        private set

    fun reset(baseline: Int? = null) {
        previousCount = baseline
        summary = MaxProForceSummary()
    }

    fun observe(side: MaxProSide): MaxProForceSummary {
        val previous = previousCount
        previousCount = side.deviceRepCount
        if (previous == null || side.deviceRepCount == previous) return summary
        if (side.deviceRepCount < previous) {
            summary = MaxProForceSummary()
            return summary
        }
        val pounds = side.nominalForceNewtons?.div(4.4482216152605) ?: return summary
        // A counter gap contributes only the latest observed pull, never invented missing pulls.
        val count = summary.observedPulls + 1
        val average = summary.averagePounds?.let { it + (pounds - it) / count } ?: pounds
        summary = MaxProForceSummary(pounds, average,
            maxOf(summary.maximumPounds ?: pounds, pounds), count)
        return summary
    }
}
