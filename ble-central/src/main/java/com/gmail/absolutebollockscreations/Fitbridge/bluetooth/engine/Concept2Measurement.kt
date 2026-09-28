package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.core.FitBridgeBleInternalApi

/**
 * Bounds-checked decoders for the Concept2 PM Bluetooth Smart rowing characteristics.
 *
 * **Source of truth:** *Concept2 PM Bluetooth Smart Communication Interface Definition*,
 * revision 1.30 (3/2/2022). Base UUID `CE06xxxx-43E5-11E4-916C-0800200C9A66`, discharged
 * against the vendor document rather than inferred from a four-character alias (plan §10, §5a).
 * Field offsets, widths, scales and lengths below are all from that document's field tables.
 *
 * ## The elapsed-time prefix is the point
 *
 * Every characteristic here begins with the same three octets: **Elapsed Time, uint24 LE, in
 * 0.01 s**. TMIW discards it, so its four Concept2 streams have no common time base and can
 * only be related by arrival order. FitBridge decodes it into [Reading.elapsedCentiseconds] and
 * the handler carries it into `MetricSample.eventMs`, which gives fusion genuine
 * cross-characteristic alignment — a power reading and a distance reading can be known to
 * describe the same instant rather than merely to have arrived close together.
 *
 * ## Two delivery paths, and they are NOT the same payload
 *
 * The specification tells Android applications to subscribe to the multiplexed characteristic
 * `0x0080` "in lieu of" the individual ones (D41). It is tempting to assume a multiplexed frame
 * is simply an identification byte followed by the payload the individual characteristic would
 * have carried. **That is false**, and the specification carries a separate "C2 Multiplexed
 * Information: Data Definitions" table because of it. QZ's `concept2skierg.cpp` states the same
 * thing in a one-line warning: *"data packets are not exactly the same as separate
 * characteristics"*.
 *
 * For the four characteristics FitBridge consumes:
 *
 * | ID | Individual | Multiplexed | Consumed fields |
 * |---|---|---|---|
 * | `0x31` | 19 B | 19 B | distance at 3–5 in both — same |
 * | `0x32` | 17 B | 19 B | speed/rate/HR/pace at 3–8 in both — same; the extra octets are appended |
 * | `0x33` | 20 B | 18 B | **different**: see below |
 * | `0x36` | 15 B | 17 B | stroke power at 3–4 in both — same; the extra octets are appended |
 *
 * `0x33` is the one that actually diverges, and it diverges in the middle rather than at the
 * tail:
 *
 * ```
 *   individual : elapsed(0-2) intervalCount(3) avgPower(4-5)      totalCalories(6-7)
 *   multiplexed: elapsed(0-2) intervalCount(3) totalCalories(4-5) splitAvgPace(6-7)
 * ```
 *
 * Decoding a multiplexed `0x33` with the individual layout reads Split/Interval Average Pace as
 * total calories and total calories as average power. Both land in plausible ranges, so nothing
 * downstream would catch it — and since D41 makes the multiplexed path the *preferred* one, that
 * would have been the default behaviour rather than an edge case.
 *
 * The two paths cannot double-deliver: the specification states that an identifier is
 * multiplexed **only** while the individual characteristic's notification is not enabled, so
 * arming an individual CCCD withdraws that ID from `0x0080` automatically.
 *
 * ## Control
 *
 * None in the central-role adapter, per amended D11. The downstream peripheral simulator owns
 * its separate closed-loop CSAFE surface; these decoders retain no control seam and must not
 * forward a simulator request to a physical Concept2 link.
 */
@FitBridgeBleInternalApi
object Concept2Measurement {

    /** Low byte of the characteristic UUID, which is also the multiplexed identification byte. */
    object Id {
        const val GENERAL_STATUS = 0x31
        const val ADDITIONAL_STATUS_1 = 0x32
        const val ADDITIONAL_STATUS_2 = 0x33
        const val ADDITIONAL_STROKE_DATA = 0x36
    }

    /** Documented payload lengths, used to reject a frame that cannot be what it claims. */
    object Length {
        const val GENERAL_STATUS = 19
        const val ADDITIONAL_STATUS_1 = 17
        const val ADDITIONAL_STATUS_2 = 20
        const val ADDITIONAL_STROKE_DATA = 15
    }

    /**
     * One decoded frame. Every field is optional except the elapsed-time prefix, because a
     * frame that is truncated mid-field yields the values ahead of the cut and nothing after —
     * the same discipline D38 established for FTMS.
     */
    data class Reading(
        val id: Int,
        /** Shared time base, uint24 in 0.01 s. */
        val elapsedCentiseconds: Int,
        val distanceMetres: Double? = null,
        val speedMetresPerSecond: Double? = null,
        val strokeRatePerMinute: Int? = null,
        val heartRateBpm: Int? = null,
        val paceSecondsPer500m: Double? = null,
        val totalCalories: Int? = null,
        val averagePowerWatts: Int? = null,
        val strokePowerWatts: Int? = null,
    ) {
        val elapsedSeconds: Double get() = elapsedCentiseconds / 100.0
    }

    /**
     * Decode a frame received on an **individual** characteristic.
     *
     * @param id the characteristic's low byte, from [Id].
     */
    fun parse(id: Int, data: ByteArray): Reading? = when (id) {
        Id.GENERAL_STATUS -> generalStatus(data)
        Id.ADDITIONAL_STATUS_1 -> additionalStatus1(data)
        Id.ADDITIONAL_STATUS_2 -> additionalStatus2(data)
        Id.ADDITIONAL_STROKE_DATA -> additionalStrokeData(data)
        else -> null
    }

    /**
     * Decode a frame from the multiplexed characteristic `0x0080`.
     *
     * Dispatches on the identification byte into the **multiplexed** layouts, which are not the
     * individual ones — see this object's documentation for the `0x33` divergence.
     *
     * An unrecognised identifier returns null rather than being guessed at. The PM multiplexes
     * eleven identifiers and FitBridge consumes four; the others are real frames carrying other
     * data, not corruption, and must be skipped silently rather than misparsed.
     */
    fun multiplexed(data: ByteArray): Reading? {
        if (data.isEmpty()) return null
        val payload = data.copyOfRange(1, data.size)
        return when (data[0].toInt() and 0xFF) {
            // Identical layouts for every field FitBridge consumes; the multiplexed forms of
            // 0x32 and 0x36 only append octets past the fields read here.
            Id.GENERAL_STATUS -> generalStatus(payload)
            Id.ADDITIONAL_STATUS_1 -> additionalStatus1(payload)
            Id.ADDITIONAL_STROKE_DATA -> additionalStrokeData(payload)
            // Diverges mid-frame. Must not reuse the individual layout.
            Id.ADDITIONAL_STATUS_2 -> multiplexedAdditionalStatus2(payload)
            else -> null
        }
    }

    /**
     * `0x0033` as carried on `0x0080`: 18 octets, and **average power is not present**.
     *
     * ```
     *   0..2  Elapsed Time            uint24, 0.01 s
     *   3     Interval Count          uint8                  (not consumed)
     *   4..5  Total Calories          uint16, kcal
     *   6..7  Split/Int Average Pace  uint16                 (not consumed)
     *   8..   Split averages, last split time and distance   (not consumed)
     * ```
     *
     * The individual form places average power at 4–5 and total calories at 6–7. Average power
     * still reaches FitBridge on this path — the multiplexed `0x32` carries it at octets 16–17 —
     * but it is not decoded here, because reading it from a frame that does not contain it is
     * how this defect started.
     */
    fun multiplexedAdditionalStatus2(data: ByteArray): Reading? {
        val cursor = Cursor(data)
        val elapsed = cursor.u24() ?: return null
        cursor.u8() ?: return Reading(Id.ADDITIONAL_STATUS_2, elapsed)
        val calories = cursor.u16()
        return Reading(
            id = Id.ADDITIONAL_STATUS_2,
            elapsedCentiseconds = elapsed,
            totalCalories = calories,
        )
    }

    // ── Individual characteristic decoders ───────────────────────────────────────

    /**
     * `0x0031` General Status, 19 octets.
     *
     * ```
     *   0..2  Elapsed Time   uint24, 0.01 s
     *   3..5  Distance       uint24, 0.1 m
     *   6     Workout Type   enum      (not consumed)
     *   7     Interval Type  enum      (not consumed)
     *   8     Workout State  enum      (not consumed)
     *   9     Rowing State   enum      (not consumed)
     *   10    Stroke State   enum      (not consumed)
     *   11..  Total Work Distance, drag factor          (not consumed)
     * ```
     */
    fun generalStatus(data: ByteArray): Reading? {
        val cursor = Cursor(data)
        val elapsed = cursor.u24() ?: return null
        val distance = cursor.u24()?.let { it / 10.0 }
        return Reading(
            id = Id.GENERAL_STATUS,
            elapsedCentiseconds = elapsed,
            distanceMetres = distance,
        )
    }

    /**
     * `0x0032` Additional Status 1, 17 octets.
     *
     * ```
     *   0..2  Elapsed Time   uint24, 0.01 s
     *   3..4  Speed          uint16, 0.001 m/s
     *   5     Stroke Rate    uint8,  strokes/min
     *   6     Heart Rate     uint8,  bpm — 255 is documented as invalid
     *   7..8  Current Pace   uint16, 0.01 s per 500 m
     *   9..   Average Pace, rest distance, machine type            (not consumed)
     * ```
     */
    fun additionalStatus1(data: ByteArray): Reading? {
        val cursor = Cursor(data)
        val elapsed = cursor.u24() ?: return null
        val speed = cursor.u16()?.let { it / 1000.0 }
        val strokeRate = cursor.u8()
        // The specification names 255 explicitly as invalid rather than leaving it to a
        // plausibility bound, so it is dropped at decode: an absent heart rate is visibly
        // absent, and 255 bpm passed downstream would be a reading nobody sent.
        val heartRate = cursor.u8()?.takeIf { it != HEART_RATE_INVALID }
        val pace = cursor.u16()?.let { it / 100.0 }
        return Reading(
            id = Id.ADDITIONAL_STATUS_1,
            elapsedCentiseconds = elapsed,
            speedMetresPerSecond = speed,
            strokeRatePerMinute = strokeRate,
            heartRateBpm = heartRate,
            paceSecondsPer500m = pace,
        )
    }

    /**
     * `0x0033` Additional Status 2, 20 octets.
     *
     * ```
     *   0..2  Elapsed Time    uint24, 0.01 s
     *   3     Interval Count  uint8                 (not consumed)
     *   4..5  Average Power   uint16, W
     *   6..7  Total Calories  uint16, kcal
     *   8..   Split averages, speed, drag factor    (not consumed)
     * ```
     *
     * Average power at 4..5 is not listed in plan §5a's table but is in the vendor field table,
     * and is decoded here because it costs nothing and the frame must be walked past it anyway.
     */
    fun additionalStatus2(data: ByteArray): Reading? {
        val cursor = Cursor(data)
        val elapsed = cursor.u24() ?: return null
        cursor.u8() ?: return Reading(Id.ADDITIONAL_STATUS_2, elapsed)
        val averagePower = cursor.u16()
        val calories = cursor.u16()
        return Reading(
            id = Id.ADDITIONAL_STATUS_2,
            elapsedCentiseconds = elapsed,
            totalCalories = calories,
            averagePowerWatts = averagePower,
        )
    }

    /**
     * `0x0036` Additional Stroke Data, 15 octets.
     *
     * ```
     *   0..2  Elapsed Time     uint24, 0.01 s
     *   3..4  Stroke Power     uint16, W
     *   5..6  Stroke Calories  uint16, cal/hr        (not consumed)
     *   7..8  Stroke Count     uint16                (not consumed)
     *   9..   Projected work time and distance       (not consumed)
     * ```
     */
    fun additionalStrokeData(data: ByteArray): Reading? {
        val cursor = Cursor(data)
        val elapsed = cursor.u24() ?: return null
        val power = cursor.u16()
        return Reading(
            id = Id.ADDITIONAL_STROKE_DATA,
            elapsedCentiseconds = elapsed,
            strokePowerWatts = power,
        )
    }

    /** Documented invalid marker for the heart-rate octet. */
    const val HEART_RATE_INVALID = 255

    private class Cursor(private val data: ByteArray) {
        private var offset = 0
        fun u8(): Int? = if (offset < data.size) data[offset++].toInt() and 0xFF else null
        fun u16(): Int? {
            if (data.size - offset < 2) return null
            return u8()!! or (u8()!! shl 8)
        }
        fun u24(): Int? {
            if (data.size - offset < 3) return null
            return u8()!! or (u8()!! shl 8) or (u8()!! shl 16)
        }
    }
}
