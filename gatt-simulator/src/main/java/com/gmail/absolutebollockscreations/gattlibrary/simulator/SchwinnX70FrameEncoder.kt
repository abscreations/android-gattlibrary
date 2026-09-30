package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.central.SchwinnX70Measurement
import kotlin.math.roundToInt
import com.gmail.absolutebollockscreations.gattlibrary.core.bytes.LittleEndian

/**
 * Corpus-anchored frame encoder (plan §8.2).
 *
 * ## The rule this file exists to enforce
 *
 * Copy a captured frame; overwrite **only** the fields whose meaning is established; leave every
 * other octet exactly as the console sent it, or replay it from the captured sequence where the
 * corpus shows it moving. Roughly a third of these octets are unexplained, so a generator that
 * filled them with zeroes would emit frames FitBridge decodes perfectly and no native consumer has
 * ever seen.
 *
 * The cost is worth stating plainly: a persona cannot express a console state that the frozen octets
 * contradict. Since nobody knows what those octets mean, that is not a loss anyone can measure.
 *
 * ## What is not replay
 *
 * One captured frame per stream seeds a structure. The metrics are generated, the session is
 * synthetic, and no captured session is played back — which is the §8.1 rule, and the reason a
 * capture taken against this simulator is distinguishable from a physical one.
 */
internal class SchwinnX70FrameEncoder {

    private val template = SchwinnX70Template.DEFAULT
    private var frameIndex = 0

    /**
     * The paired 20- and 14-byte frames for one tick, in the order a console sends them.
     *
     * Supplemental first, then sibling: that is the arrival order in every captured session, and the
     * two carry the same sequence counter because they are one emission rather than two.
     */
    fun encodePair(state: SchwinnX70ConsoleState): List<ByteArray> =
        listOf(encodeSupplemental(state), encodeSibling(state))

    /** The 20-byte supplemental frame: heart rate, resistance, an accumulator. */
    fun encodeSupplemental(state: SchwinnX70ConsoleState): ByteArray {
        val frame = template.supplemental.copyOf()
        frame[0] = state.sequence.toByte()
        // Byte 2 is a second counter on the 170 and near-static on the 270. Advancing it with the
        // sequence matches the former and stays within the latter's observed behaviour.
        frame[2] = state.sequence.toByte()
        // Byte 3 is unexplained and structured (Q1). Replayed in captured arrival order so its runs
        // and sawtooth survive; a random draw over the same values would not reproduce either.
        frame[3] = cycled(template.supplementalByte3).toByte()
        putU16(frame, SUPPLEMENTAL_ACCUMULATOR, state.supplementalAccumulator)
        // Zero is "no strap", not a reading of zero. The decoder suppresses a zero for exactly this
        // reason, so the encoder must be able to emit one.
        frame[SUPPLEMENTAL_HEART_RATE] = state.heartRateBpm.toByte()
        frame[SUPPLEMENTAL_RESISTANCE] = state.resistance.toByte()
        return frame
    }

    /** The 14-byte sibling frame: native speed and power, two accumulators. */
    fun encodeSibling(state: SchwinnX70ConsoleState): ByteArray {
        val frame = template.sibling.copyOf()
        frame[0] = state.sequence.toByte()
        frame[1] = cycled(template.siblingByte1).toByte()
        putU16(frame, SIBLING_SPEED, scaled(state.speedMph))
        putU16(frame, SIBLING_POWER, scaled(state.powerWatts))
        putU16(frame, SIBLING_ACCUMULATOR_A, state.siblingAccumulatorA)
        putU16(frame, SIBLING_ACCUMULATOR_B, state.siblingAccumulatorB)
        return frame
    }

    /** The 17-byte main frame: crank counter, console clock, cumulative energy, resistance. */
    fun encodeMain(state: SchwinnX70ConsoleState): ByteArray {
        val frame = template.main.copyOf()
        frame[3] = cycled(template.mainByte3).toByte()
        putU16(frame, MAIN_CRANK_COUNTER, state.crankCounter)
        putU16(frame, MAIN_CLOCK, state.clockTicks)
        putU48(frame, MAIN_ENERGY, state.energyRaw)
        frame[MAIN_RESISTANCE] = state.resistance.toByte()
        return frame
    }

    /**
     * The stream marker, and the first frame on the measurement characteristic.
     *
     * Emitted verbatim, both variants represented across a session. FitBridge recognises it via
     * `isStreamMarker` and decodes nothing from it; a client that treats its first main-frame
     * notification as a measurement is wrong on hardware, and this is what proves it.
     */
    fun streamMarker(index: Int): ByteArray =
        OBSERVED_MARKERS[index.mod(OBSERVED_MARKERS.size)].copyOf()

    /** Advance the replay cursor without emitting, so a paused stream does not freeze its octets. */
    fun skipTick() {
        frameIndex++
    }

    private fun cycled(values: IntArray): Int = values[frameIndex % values.size]

    /** Called once per tick, after both paired frames are built from one index. */
    fun completeTick() {
        frameIndex++
    }

    private fun scaled(value: Double): Int =
        (value.coerceAtLeast(0.0) * SchwinnX70Measurement.SIBLING_SPEED_DIVISOR)
            .roundToInt().coerceIn(0, 0xFFFF)

    /** Saturating (LIB-18b W2). */
    private fun putU16(target: ByteArray, offset: Int, value: Int) =
        LittleEndian.putU16Saturating(target, offset, value.toLong())

    private fun putU48(target: ByteArray, offset: Int, value: Long) {
        val bounded = value.coerceIn(0L, MAX_U48)
        for (index in 0 until 6) {
            target[offset + index] = (bounded ushr (8 * index)).toByte()
        }
    }

    companion object {
        // Offsets restated from SchwinnX70Measurement, which keeps them private. A mismatch is
        // caught immediately: every generated frame is required to round-trip through that decoder.
        private const val SIBLING_SPEED = 3
        private const val SIBLING_POWER = 6
        private const val SIBLING_ACCUMULATOR_A = 9
        private const val SIBLING_ACCUMULATOR_B = 12
        private const val SUPPLEMENTAL_ACCUMULATOR = 13
        private const val SUPPLEMENTAL_HEART_RATE = 16
        private const val SUPPLEMENTAL_RESISTANCE = 18
        private const val MAIN_CRANK_COUNTER = 4
        private const val MAIN_CLOCK = 8
        private const val MAIN_ENERGY = 10
        private const val MAIN_RESISTANCE = 16

        private const val MAX_U48 = 0xFFFFFFFFFFFFL

        /**
         * Every 5-octet marker observed across three consoles.
         *
         * Bytes 2 and 3 both vary and neither is understood. The encoder cycles the captured set
         * rather than emitting one value, so a client that keys on any of them meets all of them —
         * and so the central's own `isStreamMarker` is exercised against the full family rather
         * than the two variants an earlier revision generalised from.
         */
        val OBSERVED_MARKERS: List<ByteArray> = listOf(
            byteArrayOf(0x05, 0x09, 0x01, 0x00, 0x00),
            byteArrayOf(0x05, 0x09, 0x01, 0x01, 0x00),
            byteArrayOf(0x05, 0x09, 0x01, 0x03, 0x00),
            byteArrayOf(0x05, 0x09, 0x02, 0x01, 0x00),
        )
    }
}
