package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.ControlRange
import com.gmail.absolutebollockscreations.gattlibrary.central.DeviceCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.server.ReportedTargetRanges
import com.gmail.absolutebollockscreations.gattlibrary.server.TargetCapabilities
import kotlin.math.roundToInt

/** FTMS wire units per canonical unit, as the Supported-* Range characteristics define them. */
private const val SPEED_RAW_PER_KPH = 100f
private const val INCLINE_RAW_PER_PERCENT = 10f
private const val RESISTANCE_RAW_PER_LEVEL = 10f
private const val POWER_RAW_PER_WATT = 1f

private fun ControlRange?.rawMin(rawPerUnit: Float): Float? = this?.let { (it.min * rawPerUnit).roundToInt().toFloat() }
private fun ControlRange?.rawMax(rawPerUnit: Float): Float? = this?.let { (it.max * rawPerUnit).roundToInt().toFloat() }
private fun ControlRange?.rawStep(rawPerUnit: Float): Float? = this?.let { (it.step * rawPerUnit).roundToInt().toFloat() }

/**
 * The downstream target claims and ranges for a machine's published [DeviceCapabilities].
 *
 * Canonical units are converted back to wire units by rounding to the nearest whole unit, which is
 * exact for every value a Supported-* Range characteristic can carry; the test proves that for
 * every 16-bit value rather than assuming it. This is the only path from central's capabilities
 * to the downstream claims; the raw-snapshot mapping it replaced was removed in LIB-70.
 */
public fun TargetCapabilities.Companion.fromDeviceCapabilities(caps: DeviceCapabilities): TargetCapabilities =
    fromReportedRanges(
        ReportedTargetRanges(
            supportsSpeed = caps.supportsSpeedTarget,
            supportsInclination = caps.supportsInclineTarget,
            supportsResistance = caps.supportsResistanceTarget,
            supportsPower = caps.supportsPowerTarget,
            supportsIndoorBikeSimulation = caps.supportsIndoorBikeSimulation,
            supportsWheelCircumference = caps.supportsWheelCircumference,
            speedMin = caps.speedRangeKph.rawMin(SPEED_RAW_PER_KPH),
            speedMax = caps.speedRangeKph.rawMax(SPEED_RAW_PER_KPH),
            speedIncr = caps.speedRangeKph.rawStep(SPEED_RAW_PER_KPH),
            speedRangeKnown = caps.speedRangeKph != null,
            inclineMin = caps.inclineRangePercent.rawMin(INCLINE_RAW_PER_PERCENT),
            inclineMax = caps.inclineRangePercent.rawMax(INCLINE_RAW_PER_PERCENT),
            inclineIncr = caps.inclineRangePercent.rawStep(INCLINE_RAW_PER_PERCENT),
            inclineRangeKnown = caps.inclineRangePercent != null,
            resistanceMin = caps.resistanceRange.rawMin(RESISTANCE_RAW_PER_LEVEL),
            resistanceMax = caps.resistanceRange.rawMax(RESISTANCE_RAW_PER_LEVEL),
            resistanceIncr = caps.resistanceRange.rawStep(RESISTANCE_RAW_PER_LEVEL),
            resistanceRangeKnown = caps.resistanceRange != null,
            powerMin = caps.powerRangeWatts.rawMin(POWER_RAW_PER_WATT),
            powerMax = caps.powerRangeWatts.rawMax(POWER_RAW_PER_WATT),
            powerIncr = caps.powerRangeWatts.rawStep(POWER_RAW_PER_WATT),
            powerRangeKnown = caps.powerRangeWatts != null,
        ),
    )
