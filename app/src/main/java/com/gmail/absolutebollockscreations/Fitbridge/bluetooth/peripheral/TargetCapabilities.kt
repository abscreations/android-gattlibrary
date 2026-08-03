package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtEngineState
import kotlin.math.roundToInt

/** Integer wire-unit range shared by feature publication and CP validation. */
data class FtmsTargetRange(
    val minimum: Int,
    val maximum: Int,
    val increment: Int,
) {
    val isValid: Boolean
        get() = increment > 0 && minimum <= maximum

    val isValidU16: Boolean
        get() = isValid && minimum >= 0 && maximum <= 0xFFFF && increment <= 0xFFFF

    val isValidS16: Boolean
        get() = isValid && minimum >= Short.MIN_VALUE && maximum <= Short.MAX_VALUE &&
            increment <= 0xFFFF

    val isValidU8: Boolean
        get() = isValid && minimum >= 0 && maximum <= 0xFF && increment <= 0xFF

    fun contains(value: Int): Boolean =
        isValid && value in minimum..maximum && (value - minimum) % increment == 0

    fun encodeU16(): ByteArray {
        require(isValidU16)
        return u16le(minimum) + u16le(maximum) + u16le(increment)
    }

    fun encodeS16(): ByteArray {
        require(isValidS16)
        return s16le(minimum) + s16le(maximum) + u16le(increment)
    }

    fun encodeU8(): ByteArray {
        require(isValidU8)
        return u8(minimum) + u8(maximum) + u8(increment)
    }
}

/**
 * What the downstream machine can be asked to change.
 *
 * Drives two things that must stay consistent with each other:
 *
 *  1. The Target Setting Features word of 0x2ACC.
 *  2. Which Supported-* Range characteristics are registered at all. FTMS
 *     4.11-4.15 each say the range characteristic "shall be exposed by the
 *     Server if the [corresponding] Target Setting feature is supported", so
 *     with [NONE] the five range characteristics are not registered.
 *
 * The previous FitBridge registry registered all five range characteristics
 * unconditionally and answered them with zero-filled triples when the upstream
 * capability was unknown, which advertises a control surface that does not
 * exist.
 *
 * [fromEngineCapabilities] preserves FitBridge's genuine advantage over the
 * TrackMyIndoorWorkout original: these ranges are read from the *real* upstream
 * machine rather than hardcoded.
 */
data class TargetCapabilities(
    val speed: Boolean = false,
    val inclination: Boolean = false,
    val resistance: Boolean = false,
    val power: Boolean = false,
    val heartRate: Boolean = false,
    val indoorBikeSimulation: Boolean = false,
    val speedRange: FtmsTargetRange? = null,
    val inclinationRange: FtmsTargetRange? = null,
    val resistanceRange: FtmsTargetRange? = null,
    val powerRange: FtmsTargetRange? = null,
    val heartRateRange: FtmsTargetRange? = null,
) {
    val supportsSpeed get() = speed && speedRange?.isValidU16 == true
    val supportsInclination get() = inclination && inclinationRange?.isValidS16 == true
    val supportsResistance get() = resistance && resistanceRange?.isValidS16 == true
    val supportsPower get() = power && powerRange?.isValidS16 == true
    val supportsHeartRate get() = heartRate && heartRateRange?.isValidU8 == true

    /** Target Setting Features bitfield for the upper word of 0x2ACC. */
    fun toTargetFeatureBits(): Int {
        val t = FtmsSpec.TargetFeature
        var bits = 0
        if (supportsSpeed) bits = bits or (1 shl t.SPEED)
        if (supportsInclination) bits = bits or (1 shl t.INCLINATION)
        if (supportsResistance) bits = bits or (1 shl t.RESISTANCE)
        if (supportsPower) bits = bits or (1 shl t.POWER)
        if (supportsHeartRate) bits = bits or (1 shl t.HEART_RATE)
        if (indoorBikeSimulation) bits = bits or (1 shl t.INDOOR_BIKE_SIMULATION)
        return bits
    }

    val any: Boolean
        get() = supportsSpeed || supportsInclination || supportsResistance ||
            supportsPower || supportsHeartRate || indoorBikeSimulation

    companion object {
        val NONE = TargetCapabilities()

        /** Generous cap matching the previous BtServiceSupportedRangesAdapter. */
        private const val MAX_SPEED_KPH = 60f

        /**
         * Derive the peripheral's advertised target capability from what the
         * upstream machine actually reported.
         *
         * A capability is claimed only when the engine both says it is supported
         * AND has read a usable range for it. An unknown or degenerate range now
         * suppresses the whole feature instead of being published as
         * `min=0 max=0 step=0.1`, which is what the old
         * `BtServiceSupportedRangesAdapter` fallbacks produced.
         */
        fun fromEngineCapabilities(caps: BtEngineState.FtmsCapabilities): TargetCapabilities {
            val speed = range(
                enabled = caps.supportsSpeed && caps.speedRangeKnown,
                min = caps.speedMin?.let { scaleSpeed(it) },
                max = caps.speedMax?.let { scaleSpeed(it) },
                increment = caps.speedIncr?.let { scaleSpeed(it) },
            )?.takeIf { it.isValidU16 }

            val inclination = range(
                enabled = caps.supportsInclination && caps.inclineRangeKnown,
                min = caps.inclineMin?.let { (it * 10f).roundToInt() },
                max = caps.inclineMax?.let { (it * 10f).roundToInt() },
                increment = caps.inclineIncr?.let { (it * 10f).roundToInt() },
            )?.takeIf { it.isValidS16 }

            val resistance = range(
                enabled = caps.supportsResistance && caps.resistanceRangeKnown,
                min = caps.resistanceMin?.roundToInt(),
                max = caps.resistanceMax?.roundToInt(),
                increment = caps.resistanceIncr?.roundToInt(),
            )?.takeIf { it.isValidS16 }

            val power = range(
                enabled = caps.supportsPower && caps.powerRangeKnown,
                min = caps.powerMin?.roundToInt(),
                max = caps.powerMax?.roundToInt(),
                increment = caps.powerIncr?.roundToInt(),
            )?.takeIf { it.isValidS16 }

            return TargetCapabilities(
                speed = speed != null,
                inclination = inclination != null,
                resistance = resistance != null,
                power = power != null,
                // No upstream heart-rate target path exists, so the previous
                // hardcoded 0..220 range is no longer advertised.
                heartRate = false,
                speedRange = speed,
                inclinationRange = inclination,
                resistanceRange = resistance,
                powerRange = power,
                heartRateRange = null,
            )
        }

        private fun scaleSpeed(kph: Float): Int =
            (kph.coerceIn(0f, MAX_SPEED_KPH) * 100f).roundToInt()

        private fun range(
            enabled: Boolean,
            min: Int?,
            max: Int?,
            increment: Int?,
        ): FtmsTargetRange? {
            if (!enabled || min == null || max == null) return null
            // A zero or absent increment is not a usable step; the spec requires
            // a positive increment for a range to mean anything.
            val step = increment?.takeIf { it > 0 } ?: return null
            return FtmsTargetRange(min, max, step).takeIf { it.isValid }
        }
    }
}
