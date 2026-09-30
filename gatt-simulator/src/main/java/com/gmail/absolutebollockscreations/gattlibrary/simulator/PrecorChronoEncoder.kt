package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.central.PrecorMeasurement
import kotlin.math.roundToInt
import com.gmail.absolutebollockscreations.gattlibrary.core.bytes.LittleEndian

/** Encoder for the fixed 19-byte Precor Spinner Chrono Power measurement fixture. */
internal object PrecorChronoEncoder {
    /** One physical Chrono measurement/auxiliary pair is observed approximately every second. */
    const val PAIR_PERIOD_MS = 1_000L

    fun encode(sample: FtmsMockSample): ByteArray {
        val frame = ByteArray(PrecorMeasurement.FRAME_LENGTH)
        PrecorMeasurement.MAGIC_PREFIX.forEachIndexed { index, value -> frame[index] = value.toByte() }

        putU16(frame, 3, sample.elapsedSeconds)
        frame[5] = sample.heartRate.coerceIn(0, U8_MAX).toByte()
        putU16(frame, 6, scaled(sample.speedKph, 100.0))
        putU16(frame, 8, scaled(sample.cadence, 10.0))
        putU24(frame, 10, sample.distanceMeters ?: 0)
        putU16(frame, 13, sample.caloriesTotal)

        // Captures make cumulative mechanical work the best-supported interpretation of this
        // unidentified field. It remains diagnostic-only when the central adapter decodes it.
        val workKilojoules = ((sample.averagePowerWatts ?: sample.powerWatts ?: 0).toLong() *
            sample.elapsedSeconds.coerceAtLeast(0).toLong() / 1_000L)
        putU16(frame, 15, workKilojoules)
        putU16(frame, 17, sample.powerWatts ?: 0)
        return frame
    }

    /** Ordered notifications observed on `a026e01d-…` in QZ issue #261's physical capture. */
    fun encodeNotifications(sample: FtmsMockSample): List<ByteArray> =
        listOf(encode(sample), PrecorMeasurement.auxiliaryFrame())

    private fun scaled(value: Double?, scale: Double): Int =
        ((value ?: 0.0).coerceAtLeast(0.0) * scale).roundToInt()

    /** Saturating (LIB-18b W2); [value] may be any number and is truncated toward zero first. */
    private fun putU16(target: ByteArray, offset: Int, value: Number) =
        LittleEndian.putU16Saturating(target, offset, value.toLong())

    private fun putU24(target: ByteArray, offset: Int, value: Int) =
        LittleEndian.putU24Saturating(target, offset, value.toLong())

    private const val U8_MAX = 0xFF
}
