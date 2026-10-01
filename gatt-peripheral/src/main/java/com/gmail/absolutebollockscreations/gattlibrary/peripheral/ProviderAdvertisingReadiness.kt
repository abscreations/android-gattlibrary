package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.DeviceCapabilities

/**
 * When the machine's published capabilities are complete enough to publish as the downstream bridge
 * (LIB-9e), over the public [DeviceCapabilities]. The rules are unchanged from the engine's version:
 *
 *  - Feature word not read yet: [Decision.WaitForFeatures] (no timeout).
 *  - Every claimed range known: [Decision.Ready].
 *  - Some claimed range missing: [Decision.WaitForRanges] until [RANGE_WAIT_MS] has elapsed since
 *    the wait began, then [Decision.ReadyWithoutRanges], which advertises what is known.
 *
 * The downstream Target Setting Features word and Supported-* Range characteristics are built once
 * from one snapshot and a control whose range is unknown is dropped, so the bridge must not start
 * before every claimed range has been read, but one characteristic that never answers must not block
 * it forever.
 */
internal object ProviderAdvertisingReadiness {

    /** How long to wait for claimed ranges before bridging without them. */
    const val RANGE_WAIT_MS = 5_000L

    /** Poll period while waiting for ranges; they normally arrive within a few hundred ms. */
    const val RANGE_POLL_MS = 250L

    sealed interface Decision {
        data object WaitForFeatures : Decision
        data class WaitForRanges(val missing: List<String>) : Decision
        data object Ready : Decision
        data class ReadyWithoutRanges(val missing: List<String>) : Decision
    }

    /** Claimed controls whose range has not been reported, in a fixed order. */
    fun missingRanges(caps: DeviceCapabilities): List<String> = buildList {
        if (caps.supportsSpeedTarget && caps.speedRangeKph == null) add("speed")
        if (caps.supportsInclineTarget && caps.inclineRangePercent == null) add("inclination")
        if (caps.supportsResistanceTarget && caps.resistanceRange == null) add("resistance")
        if (caps.supportsPowerTarget && caps.powerRangeWatts == null) add("power")
    }

    /** @param rangeWaitMs how long the caller has already waited for ranges; 0 on the first check. */
    fun decide(caps: DeviceCapabilities, rangeWaitMs: Long): Decision {
        if (!caps.featuresRead) return Decision.WaitForFeatures
        val missing = missingRanges(caps)
        return when {
            missing.isEmpty() -> Decision.Ready
            rangeWaitMs >= RANGE_WAIT_MS -> Decision.ReadyWithoutRanges(missing)
            else -> Decision.WaitForRanges(missing)
        }
    }
}
