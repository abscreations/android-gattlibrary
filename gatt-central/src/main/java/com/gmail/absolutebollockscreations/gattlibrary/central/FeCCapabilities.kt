package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi

/**
 * Projects FE-C page-54 mode bits into the FTMS-shaped capability fields consumed by the local
 * provider. FEC3 presence means only that writes are possible; it does not establish support for
 * Basic Resistance, Target Power, or Simulation. When FEC3 exists and no page is supplied,
 * [featuresRead][BtEngineState.featuresRead] remains false and every control is withheld. A valid
 * page (or the handler's conservative timeout page with all bits clear) completes discovery.
 *
 * Page 54's maximum resistance is in newtons, not an FTMS resistance level or a power/incline
 * range, so it is retained in [FeCMeasurementSnapshot] and never used here. For each advertised
 * mode, the range below is the FE-C page's documented encodable domain expressed in downstream
 * FTMS raw units; it is not a claim about a particular machine's physical limit.
 */
@GattLibraryInternalApi
internal object FeCCapabilities {

    /** Page 48 (Basic Resistance): 0-100%, 0.5% resolution -> FTMS raw 0.1-level units. */
    private const val RESISTANCE_MIN_RAW = 0f
    private const val RESISTANCE_MAX_RAW = 1000f
    private const val RESISTANCE_INCR_RAW = 5f

    /** Page 49 (Target Power): 0-4000 W. FTMS's own Target Power characteristic is 1 W/unit -
     * finer than that does not exist on the downstream wire regardless of FE-C's own 0.25 W step. */
    private const val POWER_MIN_RAW = 0f
    private const val POWER_MAX_RAW = 4000f
    private const val POWER_INCR_RAW = 1f

    /** Page 51 Grade: -200.00%..+200.00% -> FTMS raw 0.1%-per-unit units. */
    private const val INCLINE_MIN_RAW = -2000f
    private const val INCLINE_MAX_RAW = 2000f
    private const val INCLINE_INCR_RAW = 1f

    fun publish(
        state: BtEngineState,
        controlAvailable: Boolean,
        page: FeCPage.FeCapabilities? = null,
    ) {
        val resolved = !controlAvailable || page != null
        val supportsResistance = controlAvailable && page?.supportsBasicResistance == true
        val supportsPower = controlAvailable && page?.supportsTargetPower == true
        val supportsSimulation = controlAvailable && page?.supportsSimulation == true

        state.supportsResistance = supportsResistance
        state.supportsPower = supportsPower
        state.supportsIndoorBikeSimulation = supportsSimulation
        state.supportsSpeed = false
        state.supportsInclination = supportsSimulation
        state.featuresRead = resolved

        state.resistanceRangeKnown = supportsResistance
        state.resistanceMin = if (supportsResistance) RESISTANCE_MIN_RAW else 0f
        state.resistanceMax = if (supportsResistance) RESISTANCE_MAX_RAW else 0f
        state.resistanceIncr = if (supportsResistance) RESISTANCE_INCR_RAW else 0f

        state.powerRangeKnown = supportsPower
        state.powerMin = if (supportsPower) POWER_MIN_RAW else 0f
        state.powerMax = if (supportsPower) POWER_MAX_RAW else 0f
        state.powerIncr = if (supportsPower) POWER_INCR_RAW else 0f

        state.inclineRangeKnown = supportsSimulation
        state.inclineMin = if (supportsSimulation) INCLINE_MIN_RAW else 0f
        state.inclineMax = if (supportsSimulation) INCLINE_MAX_RAW else 0f
        state.inclineIncr = if (supportsSimulation) INCLINE_INCR_RAW else 0f

        state.mergeFtmsCapabilities(
            BtEngineState.FtmsCapabilities(
                featuresRead = resolved,
                supportsResistance = supportsResistance,
                supportsPower = supportsPower,
                supportsIndoorBikeSimulation = supportsSimulation,
                supportsSpeed = false,
                supportsInclination = supportsSimulation,

                resistanceMin = state.resistanceMin,
                resistanceMax = state.resistanceMax,
                resistanceIncr = state.resistanceIncr,
                resistanceRangeKnown = supportsResistance,

                powerMin = state.powerMin,
                powerMax = state.powerMax,
                powerIncr = state.powerIncr,
                powerRangeKnown = supportsPower,

                inclineMin = state.inclineMin,
                inclineMax = state.inclineMax,
                inclineIncr = state.inclineIncr,
                inclineRangeKnown = supportsSimulation,
            )
        )
    }
}
