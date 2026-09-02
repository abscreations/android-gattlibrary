package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.KeiserMSeriesMeasurement
import kotlin.math.roundToInt

/** Renders the dynamic part Android supplies to Keiser's manufacturer-data AD structure. */
internal object KeiserMSeriesSimulatorEncoder {
    const val FIRMWARE_MAJOR = 6
    const val FIRMWARE_MINOR = 0x30
    const val DATA_TYPE_REALTIME = 0x00
    const val SYNTHETIC_SYSTEM_ID = 1

    /** Keiser's captured fast interval (`0x0237 * 0.625 ms`), rounded for coroutine scheduling. */
    const val SAMPLE_PERIOD_MS = 354L

    /** Company ID is supplied separately to Android and is therefore absent from this value. */
    const val MANUFACTURER_PAYLOAD_LENGTH = KeiserMSeriesMeasurement.CURRENT_FIELD_LENGTH - 2

    fun encode(sample: FtmsMockSample): ByteArray = ByteArray(MANUFACTURER_PAYLOAD_LENGTH).also { data ->
        data[0] = FIRMWARE_MAJOR.toByte()
        data[1] = FIRMWARE_MINOR.toByte()
        data[2] = DATA_TYPE_REALTIME.toByte()
        data[3] = SYNTHETIC_SYSTEM_ID.toByte()
        putU16(data, 4, scaledTenths(sample.cadence))
        putU16(data, 6, sample.heartRate.coerceAtLeast(0) * 10L)
        putU16(data, 8, sample.powerWatts ?: 0)
        putU16(data, 10, sample.caloriesTotal)

        val elapsed = sample.elapsedSeconds.coerceIn(0, MAX_ELAPSED_SECONDS)
        data[12] = (elapsed / 60).toByte()
        data[13] = (elapsed % 60).toByte()

        // The simulator always presents metric distance. One wire unit is 0.1 km = 100 m.
        val distanceTenthsKm = ((sample.distanceMeters ?: 0).coerceAtLeast(0) / 100.0)
            .roundToInt()
            .coerceAtMost(DISTANCE_TENTHS_MASK)
        putU16(data, 14, distanceTenthsKm or DISTANCE_METRIC_BIT)
        data[16] = (sample.resistance ?: 0).coerceIn(0, U8_MAX).toByte()
    }

    /** Reattaches the little-endian company prefix used by the production decoder and fixtures. */
    fun canonicalField(manufacturerPayload: ByteArray): ByteArray =
        byteArrayOf(
            (KeiserMSeriesMeasurement.COMPANY_ID and 0xff).toByte(),
            (KeiserMSeriesMeasurement.COMPANY_ID ushr 8).toByte(),
        ) + manufacturerPayload

    private fun scaledTenths(value: Double?): Int =
        ((value ?: 0.0).coerceAtLeast(0.0) * 10.0).roundToInt()

    private fun putU16(target: ByteArray, offset: Int, value: Number) {
        val bounded = value.toLong().coerceIn(0L, U16_MAX.toLong()).toInt()
        target[offset] = bounded.toByte()
        target[offset + 1] = (bounded ushr 8).toByte()
    }

    private const val U8_MAX = 0xff
    private const val U16_MAX = 0xffff
    private const val DISTANCE_METRIC_BIT = 0x8000
    private const val DISTANCE_TENTHS_MASK = 0x7fff
    private const val MAX_ELAPSED_SECONDS = 255 * 60 + 59
}
