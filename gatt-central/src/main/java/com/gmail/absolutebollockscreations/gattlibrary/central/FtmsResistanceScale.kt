package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Decides whether a machine's Cross Trainer Resistance Level field is in the
 * spec's tenths or in raw whole levels, using the machine's own declared range.
 *
 * ### Why a decision is needed at all
 *
 * `org.bluetooth.characteristic.cross_trainer_data` (`0x2ACE`) gives Resistance
 * Level `DecimalExponent -1`, so a level of 8 should go out as 80. Real machines
 * do not all agree. The physical elliptical captured on 2026-08-06:
 *
 * | Characteristic | Value | As tenths | As raw levels |
 * |---|---|---|---|
 * | Supported Resistance Level Range `0x2AD6` | min 10, max 300, step 10 | **1.0 - 30.0, step 1.0** | 10 - 300, step 10 |
 * | Cross Trainer Data resistance field | 7 - 8 | 0.7 - 0.8 | **7 - 8** |
 *
 * Read as tenths the live value is *below the machine's own declared minimum*
 * and lands on no step boundary. Read as raw levels it sits inside the range on
 * exact steps. The machine is therefore non-compliant on the data field while
 * being compliant on the range - and it says so itself, in a characteristic
 * FitBridge already reads.
 *
 * ### The rule
 *
 * The range is the arbiter, because a machine that contradicts itself is telling
 * us which of the two readings it meant:
 *
 *  - exactly one interpretation inside `[min, max]` -> that one;
 *  - both inside, but only one on a step boundary -> the one on the boundary;
 *  - both equally plausible, or the range unknown -> the spec, tenths.
 *
 * The default is deliberately the spec. A guess is only preferred over the
 * standard where the device has published evidence contradicting it.
 *
 * ### Stickiness
 *
 * [decide] is pure and per-sample; callers latch the first non-default answer
 * for the life of a connection. A reading that flips interpretation mid-session
 * would produce a 10x step in the recorded activity, which is worse than being
 * consistently wrong.
 */
@GattLibraryInternalApi
object FtmsResistanceScale {

    /** How a machine encodes Resistance Level on its data characteristic. */
    enum class Encoding {
        /** Spec: `DecimalExponent -1`, so the wire value is levels x 10. */
        TENTHS,

        /** Non-compliant: the wire value is the level itself. */
        RAW_LEVELS,
        ;

        /** Wire value -> public level. */
        fun toLevel(raw: Int): Float = if (this == TENTHS) raw / 10f else raw.toFloat()
    }

    /**
     * @param raw   the sint16 straight off the wire
     * @param min   declared minimum, in levels (already divided by ten)
     * @param max   declared maximum, in levels
     * @param incr  declared increment, in levels; <= 0 means unknown
     * @param known false when `0x2AD6` has not been read, which forces the spec
     */
    fun decide(
        raw: Int,
        min: Float,
        max: Float,
        incr: Float,
        known: Boolean,
    ): Encoding {
        if (!known || max <= min) return Encoding.TENTHS

        val asTenths = raw / 10f
        val asRaw = raw.toFloat()
        val tenthsInRange = asTenths in min..max
        val rawInRange = asRaw in min..max

        if (tenthsInRange && !rawInRange) return Encoding.TENTHS
        if (rawInRange && !tenthsInRange) return Encoding.RAW_LEVELS

        if (tenthsInRange && rawInRange && incr > 0f) {
            val tenthsOnStep = onStep(asTenths, min, incr)
            val rawOnStep = onStep(asRaw, min, incr)
            if (tenthsOnStep && !rawOnStep) return Encoding.TENTHS
            if (rawOnStep && !tenthsOnStep) return Encoding.RAW_LEVELS
        }

        // Ambiguous, or neither reading fits. A machine whose live value is
        // outside its own declared range tells us nothing, so defer to the spec
        // rather than inventing a preference.
        return Encoding.TENTHS
    }

    /** Whether [value] sits on a `min + n * incr` boundary, within rounding. */
    private fun onStep(value: Float, min: Float, incr: Float): Boolean {
        if (incr <= 0f) return false
        val steps = (value - min) / incr
        return abs(steps - steps.roundToInt()) < STEP_TOLERANCE
    }

    /**
     * Generous enough to absorb float error on values that are exact in decimal
     * but not in binary - 0.1 increments in particular - and tight enough that a
     * value half a step out is still rejected.
     */
    private const val STEP_TOLERANCE = 0.01f
}
