package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtEngineState.FtmsCapabilities

/**
 * When the upstream capability snapshot is complete enough to publish as the downstream bridge
 * (LIB-9e).
 *
 * The downstream Target Setting Features word and Supported-* Range characteristics are built
 * once, from one snapshot (`TargetCapabilities.fromEngineCapabilities`), and a control whose
 * range is unknown is dropped. So the bridge must not start before every claimed range has been
 * read. Rebuilding the GATT server later would drop any downstream client already connected.
 *
 * The old gate waited only for the resistance range. A treadmill (speed, incline and power; no
 * resistance) passed the moment 0x2ACC was parsed, while its range reads were still queued, and
 * was bridged with no controls at all.
 *
 * Rules:
 *  - 0x2ACC not parsed yet: [Decision.WaitForFeatures] (no timeout, as before).
 *  - Every claimed range known: [Decision.Ready].
 *  - Some claimed range missing: [Decision.WaitForRanges] until [RANGE_WAIT_MS] has elapsed
 *    since the wait began, then [Decision.ReadyWithoutRanges], which advertises what is known.
 *    One characteristic that never answers must not block the bridge.
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

    /** Claimed controls whose Supported-* Range has not been read, in a fixed order. */
    fun missingRanges(caps: FtmsCapabilities): List<String> = buildList {
        if (caps.supportsSpeed && !caps.speedRangeKnown) add("speed")
        if (caps.supportsInclination && !caps.inclineRangeKnown) add("inclination")
        if (caps.supportsResistance && !caps.resistanceRangeKnown) add("resistance")
        if (caps.supportsPower && !caps.powerRangeKnown) add("power")
    }

    /**
     * @param rangeWaitMs how long the caller has already waited for ranges; 0 on the first check.
     */
    fun decide(caps: FtmsCapabilities, rangeWaitMs: Long): Decision {
        if (!caps.featuresRead) return Decision.WaitForFeatures
        val missing = missingRanges(caps)
        return when {
            missing.isEmpty() -> Decision.Ready
            rangeWaitMs >= RANGE_WAIT_MS -> Decision.ReadyWithoutRanges(missing)
            else -> Decision.WaitForRanges(missing)
        }
    }
}
