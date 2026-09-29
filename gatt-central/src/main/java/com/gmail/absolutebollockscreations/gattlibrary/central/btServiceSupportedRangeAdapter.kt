/**
 * =================================================================================================
 * Adapter for FTMS “Supported * Range” characteristics (engine → provider).
 *
 * =================================================================================================
 * PURPOSE
 *  - Convert internal FTMS capability ranges from [BtEngineState] into the exact
 *    binary payloads required by FTMS Supported * Range characteristics on the
 *    provider-side GATT server (btService).
 *  - Centralize all scaling, clamping, and little-endian packing so btService
 *    can expose spec-compliant range characteristics without duplicating logic.
 *
 * RESPONSIBILITIES
 *  - Build payloads for the following FTMS characteristics (3× values each:
 *    minimum, maximum, increment):
 *      • Supported Speed Range        (0x2AD4) – uint16, resolution 0.01 km/h
 *      • Supported Inclination Range  (0x2AD5) – sint16, resolution 0.1 % grade
 *      • Supported Resistance Range   (0x2AD6) – sint16, unitless (level)
 *      • Supported Heart Rate Range   (0x2AD7) – uint8, bpm
 *      • Supported Power Range        (0x2AD8) – sint16, watts
 *  - Read capability flags and min/max/increment fields from [BtEngineState],
 *    then:
 *      • Apply sensible defaults when ranges are unknown or unsupported.
 *      • Clamp values into legal numeric domains for each GATT type.
 *      • Pack (min, max, increment) into 3×(u16/s16/u8) little-endian fields.
 *
 * DESIGN
 *  - Stateless singleton `object`; safe to call from any thread and at any time.
 *  - Intended to be consumed indirectly via [BtServiceEngineFacade] from the
 *    btService module when handling Supported-* Range READs.
 *  - All BLE-specific concerns (services, characteristics, READ callbacks) live
 *    in btService; this adapter only understands engine state and FTMS encoding.
 */


package com.gmail.absolutebollockscreations.gattlibrary.central

import kotlin.math.roundToInt

internal object BtServiceSupportedRangesAdapter {

    // Supported Speed Range (0x2AD4) – 3× uint16, 0.01 km/h
    fun buildSpeedRange(state: BtEngineState): ByteArray {
        val known = state.speedRangeKnown && state.supportsSpeed

        val minKph = if (known) state.speedMin else 0f
        val maxKph = if (known) state.speedMax else 0f
        val incrKph = if (known && state.speedIncr > 0f) state.speedIncr else 0.1f // fallback incr

        val min = clampU16(speedTo01Kph(minKph))
        val max = clampU16(speedTo01Kph(maxKph))
        val inc = clampU16(speedTo01Kph(incrKph))

        return u16x3(min, max, inc)
    }

    // Supported Inclination Range (0x2AD5) – 3× sint16, 0.1 % grade
    fun buildInclineRange(state: BtEngineState): ByteArray {
        val known = state.inclineRangeKnown && state.supportsInclination

        val minPct = if (known) state.inclineMin else 0f
        val maxPct = if (known) state.inclineMax else 0f
        val incrPct = if (known && state.inclineIncr != 0f) state.inclineIncr else 0.1f

        val min = clampS16((minPct * 10f).roundToInt())
        val max = clampS16((maxPct * 10f).roundToInt())
        val inc = clampS16((incrPct * 10f).roundToInt())

        return s16x3(min, max, inc)
    }

    // Supported Resistance Level Range (0x2AD6) – 3× sint16, unitless, resolution 1
    fun buildResistanceRange(state: BtEngineState): ByteArray {
        val known = state.resistanceRangeKnown && state.supportsResistance

        val minRaw = if (known) state.resistanceMin else 0f
        val maxRaw = if (known) state.resistanceMax else 0f
        val incrRaw = if (known && state.resistanceIncr != 0f) state.resistanceIncr else 1f

        val min = clampS16(minRaw.roundToInt())
        val max = clampS16(maxRaw.roundToInt())
        val inc = clampS16(incrRaw.roundToInt())

        return s16x3(min, max, inc)
    }

    // Supported Heart Rate Range (0x2AD7) – 3× uint8, bpm
    //
    // BtEngineState does not track a machine-level HR range. We expose a very generic range
    // that virtually all devices can handle: 0..220 bpm, increment step 1 bpm.
    fun buildHeartRateRange(@Suppress("UNUSED_PARAMETER") state: BtEngineState): ByteArray {
        val min = clampU8(0)
        val max = clampU8(220)
        val inc = clampU8(1)
        return byteArrayOf(min.toByte(), max.toByte(), inc.toByte())
    }

    // Supported Power Range (0x2AD8) – 3× sint16, watts
    fun buildPowerRange(state: BtEngineState): ByteArray {
        val known = state.powerRangeKnown && state.supportsPower

        val minRaw = if (known) state.powerMin else 0f
        val maxRaw = if (known) state.powerMax else 0f
        val incrRaw = if (known && state.powerIncr != 0f) state.powerIncr else 1f

        val min = clampS16(minRaw.roundToInt())
        val max = clampS16(maxRaw.roundToInt())
        val inc = clampS16(incrRaw.roundToInt())

        return s16x3(min, max, inc)
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private fun speedTo01Kph(kph: Float): Int {
        val clamped = kph.coerceIn(0f, 60f)      // generous cap
        return (clamped * 100f).roundToInt()
    }

    private fun clampU16(v: Int): Int = v.coerceIn(0, 0xFFFF)
    private fun clampU8(v: Int): Int = v.coerceIn(0, 0xFF)
    private fun clampS16(v: Int): Int = v.coerceIn(-0x8000, 0x7FFF)

    private fun u16x3(a: Int, b: Int, c: Int): ByteArray {
        val out = ByteArray(6)
        out[0] = (a and 0xFF).toByte()
        out[1] = ((a ushr 8) and 0xFF).toByte()
        out[2] = (b and 0xFF).toByte()
        out[3] = ((b ushr 8) and 0xFF).toByte()
        out[4] = (c and 0xFF).toByte()
        out[5] = ((c ushr 8) and 0xFF).toByte()
        return out
    }

    private fun s16x3(a: Int, b: Int, c: Int): ByteArray {
        fun toLeBytes(s: Int): Pair<Byte, Byte> {
            val v = clampS16(s)
            return ((v and 0xFF).toByte()) to (((v ushr 8) and 0xFF).toByte())
        }

        val (a0, a1) = toLeBytes(a)
        val (b0, b1) = toLeBytes(b)
        val (c0, c1) = toLeBytes(c)

        return byteArrayOf(a0, a1, b0, b1, c0, c1)
    }
}