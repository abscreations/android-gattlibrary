package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import kotlin.math.roundToInt

/** Encodes the captured 34-octet Flywheel notification shape used by the Phase 12 decoder. */
internal object FlywheelSportsEncoder {
    const val PERIOD_MS = 1_000L
    const val FRAME_SIZE = 34

    private val template = byteArrayOf(
        0xff.toByte(), 0x1f, 0x0c, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x0a, 0x00, 0x00, 0x00, 0x01,
        0x61, 0x55,
    )

    fun encode(sample: FtmsMockSample): ByteArray {
        val power = (sample.powerWatts ?: 0).coerceIn(0, 0xffff)
        val cadence = (sample.cadence ?: 0.0).roundToInt().coerceIn(0, 0xff)
        return template.copyOf().also { frame ->
            frame[3] = ((power ushr 8) and 0xff).toByte()
            frame[4] = (power and 0xff).toByte()
            frame[12] = cadence.toByte()
        }
    }
}
