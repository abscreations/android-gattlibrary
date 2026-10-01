package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.central.BtEngineState.FtmsCapabilities

/** An inclusive range a machine accepts for one target, with the smallest step it honours. */
public data class ControlRange(val min: Float, val max: Float, val step: Float)

/**
 * What the connected machine can be asked to do, in canonical units.
 *
 * Empty (`featuresRead = false`, nothing supported) while no machine is connected or its feature
 * word has not been read. Adapter-aware: a Wahoo KICKR or RideOn bike that has not granted control
 * reports nothing, and once it has, reports the controls that adapter actually offers, which is
 * the same answer the downstream bridge advertises. Spin-down and the raw-levels resistance flag
 * are deliberately not part of this first cut.
 *
 * Ranges are null until the machine has reported them. Units are canonical: speed in km/h,
 * incline in percent, resistance in the machine's level units, power in watts.
 */
public data class DeviceCapabilities(
    /** The answer is settled; false means the machine's feature word has not been read yet. */
    val featuresRead: Boolean = false,
    val supportsSpeedTarget: Boolean = false,
    val supportsInclineTarget: Boolean = false,
    val supportsResistanceTarget: Boolean = false,
    val supportsPowerTarget: Boolean = false,
    val supportsIndoorBikeSimulation: Boolean = false,
    val supportsWheelCircumference: Boolean = false,
    /** The machine reports pace itself, so it can be relayed rather than derived. */
    val supportsPaceMeasurement: Boolean = false,
    val speedRangeKph: ControlRange? = null,
    val inclineRangePercent: ControlRange? = null,
    val resistanceRange: ControlRange? = null,
    val powerRangeWatts: ControlRange? = null,
) {
    public companion object {
        public val NONE: DeviceCapabilities = DeviceCapabilities()
    }
}

/** Raw wire units per canonical unit, as the FTMS Supported-* Range characteristics define them. */
internal object CapabilityUnits {
    const val SPEED_RAW_PER_KPH = 100f
    const val INCLINE_RAW_PER_PERCENT = 10f
    const val RESISTANCE_RAW_PER_LEVEL = 10f
    const val POWER_RAW_PER_WATT = 1f
}

private fun range(known: Boolean, min: Float?, max: Float?, step: Float?, rawPerUnit: Float): ControlRange? {
    if (!known || min == null || max == null) return null
    return ControlRange(min / rawPerUnit, max / rawPerUnit, (step ?: 0f) / rawPerUnit)
}

/** Canonical view of a raw-unit snapshot. [paceMeasurement] is carried by engine state, not the snapshot. */
internal fun FtmsCapabilities.toDeviceCapabilities(paceMeasurement: Boolean): DeviceCapabilities =
    DeviceCapabilities(
        featuresRead = featuresRead,
        supportsSpeedTarget = supportsSpeed,
        supportsInclineTarget = supportsInclination,
        supportsResistanceTarget = supportsResistance,
        supportsPowerTarget = supportsPower,
        supportsIndoorBikeSimulation = supportsIndoorBikeSimulation,
        supportsWheelCircumference = supportsWheelCircumference,
        supportsPaceMeasurement = paceMeasurement,
        speedRangeKph = range(speedRangeKnown, speedMin, speedMax, speedIncr, CapabilityUnits.SPEED_RAW_PER_KPH),
        inclineRangePercent = range(
            inclineRangeKnown, inclineMin, inclineMax, inclineIncr, CapabilityUnits.INCLINE_RAW_PER_PERCENT,
        ),
        resistanceRange = range(
            resistanceRangeKnown, resistanceMin, resistanceMax, resistanceIncr,
            CapabilityUnits.RESISTANCE_RAW_PER_LEVEL,
        ),
        powerRangeWatts = range(powerRangeKnown, powerMin, powerMax, powerIncr, CapabilityUnits.POWER_RAW_PER_WATT),
    )
