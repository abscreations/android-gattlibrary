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

    /** Clamp to the machine limits, then snap to the nearest advertised step. */
    fun clampAndSnap(value: Int): Int {
        require(isValid)
        val clamped = value.coerceIn(minimum, maximum)
        val steps = ((clamped - minimum).toDouble() / increment).roundToInt()
        return (minimum + steps * increment).coerceIn(minimum, maximum)
    }

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
    val wheelCircumference: Boolean = false,
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
        if (wheelCircumference) bits = bits or (1 shl t.WHEEL_CIRCUMFERENCE)
        return bits
    }

    val any: Boolean
        get() = supportsSpeed || supportsInclination || supportsResistance ||
            supportsPower || supportsHeartRate || indoorBikeSimulation || wheelCircumference

    companion object {
        val NONE = TargetCapabilities()

        /**
         * Generous sanity cap, in the characteristic's own 0.01 km/h units:
         * 60.00 km/h. Expressed raw because that is what the source values are;
         * comparing a raw 3000 against a 60 "km/h" limit is what silently
         * rewrote a 30 km/h machine as a 60 km/h one.
         */
        private const val MAX_SPEED_RAW = 6000

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
            // [BtEngineState.FtmsCapabilities] holds every range in RAW SPEC
            // UNITS, exactly as read off the upstream machine - 0.01 km/h,
            // 0.1 %, 0.1 level, whole watts. The Supported-* Range
            // characteristics use those same units, so this is a pass-through
            // and must not rescale.
            //
            // Inclination and speed used to rescale here as though the source
            // were in public units, while resistance and power did not. The
            // asymmetry is why resistance control worked and inclination did
            // not: a machine reporting -10.0..30.0 % step 0.5 was republished as
            // -100.0..300.0 % step 5.0, and a watch obeying that advertisement
            // snapped 1.0 % to 0.0, 3.0 % to 5.0, and let the user reach 110.0 %
            // because the maximum claimed 300 %. Confirmed on the wire
            // 2026-08-06: the watch wrote 030000 / 033200 / 034c04.
            val speed = range(
                enabled = caps.supportsSpeed && caps.speedRangeKnown,
                min = caps.speedMin?.let { clampSpeedRaw(it) },
                max = caps.speedMax?.let { clampSpeedRaw(it) },
                increment = caps.speedIncr?.roundToInt(),
            )?.takeIf { it.isValidU16 }

            val inclination = range(
                enabled = caps.supportsInclination && caps.inclineRangeKnown,
                min = caps.inclineMin?.roundToInt(),
                max = caps.inclineMax?.roundToInt(),
                increment = caps.inclineIncr?.roundToInt(),
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
                // §9.5.6: claim simulation only when the upstream machine declared Target
                // Setting Features bit 13. Unlike the targets above there is no range
                // characteristic to validate against — the honesty gate is the feature bit
                // alone, which is why the upstream write is gated on the same bit.
                indoorBikeSimulation = caps.supportsIndoorBikeSimulation,
                wheelCircumference = caps.supportsWheelCircumference,
                speedRange = speed,
                inclinationRange = inclination,
                resistanceRange = resistance,
                powerRange = power,
                heartRateRange = null,
            )
        }

        private fun clampSpeedRaw(raw: Float): Int =
            raw.roundToInt().coerceIn(0, MAX_SPEED_RAW)

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
