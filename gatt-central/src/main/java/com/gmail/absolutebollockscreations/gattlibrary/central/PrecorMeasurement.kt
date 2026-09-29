package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.FitBridgeBleInternalApi

/**
 * Bounds-checked decoder for the Precor Spinner Chrono Power measurement frame (plan §5b).
 *
 * **Sources.** Two reverse-engineering efforts, no vendor document. TMIW's
 * `PrecorSpinnerChronoPower` descriptor supplies the field table and ten captured vectors;
 * QZ's `chronobike::characteristicChanged` corroborates the length, the speed field and the
 * power field exactly. There is no published Precor specification behind any of it, which is
 * the reason the §11b plausibility bounds matter more here than they do for a SIG profile.
 *
 * ## Layout — exactly 19 octets, prefixed `53 59 16`
 *
 * ```
 *   0..2   Magic prefix        83, 89, 22          identity, not data
 *   3..4   Elapsed time        uint16 LE, s        protocol time only (§8.5)
 *   5      Heart rate          uint8, bpm
 *   6..7   Speed               uint16 LE, ÷100 km/h
 *   8..9   Cadence             uint16 LE, ÷10 rpm  (V10 — see below)
 *   10..12 Distance            uint24 LE, m        cumulative
 *   13..14 Calories            uint16 LE, kcal     cumulative
 *   15..16 Unidentified        uint16 LE           decoded, never published
 *   17..18 Power               uint16 LE, W
 * ```
 *
 * ## The prefix is identity, and it is checked before anything else
 *
 * TMIW treats `[83, 89, 22]` as both a data-processability gate and a "flag word", and the
 * doubled role is why it checks the same three octets twice. FitBridge keeps only the first
 * meaning: these octets never vary, so they carry no per-frame information and there is
 * nothing to decode. A frame failing the check is rejected whole.
 *
 * §5b asks that a rejected frame "must not consume the deduplication window". FitBridge has no
 * deduplication or throttling layer at all — fusion arbitrates by source and freshness instead
 * of by rate — so the requirement is satisfied structurally rather than by code. That is worth
 * stating once: the honest way to meet a criterion inherited from another project's
 * architecture is to show the hazard cannot arise, not to build the hazard and then guard it.
 *
 * ## Cadence: divergence V10, resolved on evidence
 *
 * TMIW reads cadence as `uint16` LE at 8–9 ÷10. QZ reads `uint8` at byte 8 ÷2 and discards
 * byte 9 entirely. The two disagree for every cadence at or above 25.6 rpm, so this is not a
 * rounding difference — at TMIW's vector `…, 252, 3, …` they yield 102 rpm and 126 rpm.
 *
 * **Byte 9 settles it.** Across TMIW's ten captured vectors, byte 9 takes the values
 * 1, 1, 0, 0, 2, 2, 1, 1, 3, 3 — and in every case it is exactly the high octet of
 * `cadence × 10` under TMIW's reading. It is not constant, not zero, and it tracks the cadence
 * the machine was reporting. A byte that co-varies with the field beside it is part of that
 * field. QZ is reading the low octet of a uint16 and truncating every cadence above 25.5 rpm;
 * that its users have not obviously noticed is unsurprising, since the truncated value is
 * itself a plausible-looking cadence.
 *
 * So this ships as a resolved divergence rather than an adopted guess, and
 * [PRECOR_CADENCE_HIGH_OCTET_OBSERVED] exists so the runtime keeps checking the claim: see
 * [Reading.cadenceHighOctet].
 *
 * ## Bytes 15–16 are decoded and deliberately not published
 *
 * The plan records them as unaccounted and asks that they be logged rather than assumed to be
 * padding. They are not padding. Across all ten vectors `calories / value` is 1.19–1.21 — five
 * significant agreements on a ratio no two independent quantities would hold — and
 * `value × 1000 / elapsedSeconds` yields 76–121 W against instantaneous powers of 90–195 W.
 * The shape of a cumulative work total in kJ, from which the displayed calorie figure is
 * computed at a fixed factor.
 *
 * That is a hypothesis with arithmetic behind it, not a fact, and there is no second source
 * and no ground truth. So it is decoded into [Reading.unidentified15to16] where a diagnostic
 * capture can see it, and emitted as no metric at all. A field understood this well is exactly
 * the one it is tempting to ship.
 */
@FitBridgeBleInternalApi
object PrecorMeasurement {

    /** Exact frame length. Not a minimum: a 20-octet frame is not a Chrono frame. */
    const val FRAME_LENGTH = 19

    /** The invariant first three octets, `0x53 0x59 0x16`. */
    val MAGIC_PREFIX = intArrayOf(83, 89, 22)

    /** Known non-metric companion notification observed after each physical Chrono frame. */
    private val AUXILIARY_FRAME = byteArrayOf(0x24, 0x08, 0x00)

    fun auxiliaryFrame(): ByteArray = AUXILIARY_FRAME.copyOf()

    fun isAuxiliaryFrame(data: ByteArray): Boolean = data.contentEquals(AUXILIARY_FRAME)

    /** Offsets, named so a reader can check them against the table above without counting. */
    private const val OFFSET_ELAPSED = 3
    private const val OFFSET_HEART_RATE = 5
    private const val OFFSET_SPEED = 6
    private const val OFFSET_CADENCE = 8
    private const val OFFSET_DISTANCE = 10
    private const val OFFSET_CALORIES = 13
    private const val OFFSET_UNIDENTIFIED = 15
    private const val OFFSET_POWER = 17

    private const val SPEED_SCALE = 100.0
    private const val CADENCE_SCALE = 10.0

    /**
     * One decoded frame.
     *
     * Every field is non-null. This is a fixed-layout proprietary frame with no flag word: if
     * the length and prefix hold, every field is present at a known offset, and if they do not,
     * there is no frame. The optionality that [Concept2Measurement.Reading] carries exists
     * because Concept2 frames can be truncated mid-field; a 19-octet gate leaves no such case.
     *
     * @param elapsedSeconds the machine's own clock. Protocol time, carried into
     * `MetricSample.eventMs` and never published as a metric — §8.5 is explicit that a
     * machine-reported elapsed time is not authoritative for a FitBridge session.
     * @param cadenceHighOctet byte 9 verbatim, retained for the V10 falsification probe.
     * @param unidentified15to16 bytes 15–16, decoded so they can be observed and never emitted.
     */
    data class Reading(
        val elapsedSeconds: Int,
        val heartRateBpm: Int,
        val speedKph: Double,
        val cadenceRpm: Double,
        val distanceMetres: Int,
        val calories: Int,
        val powerWatts: Int,
        val cadenceHighOctet: Int,
        val unidentified15to16: Int,
    )

    /**
     * Whether [data] is a Chrono measurement frame at all.
     *
     * Length first, then prefix — in that order, because the prefix read is only safe once the
     * length is known. Kept public so the matcher can use the identical predicate rather than
     * a second, drifting copy of it.
     */
    fun isChronoFrame(data: ByteArray): Boolean {
        if (data.size != FRAME_LENGTH) return false
        for (index in MAGIC_PREFIX.indices) {
            if ((data[index].toInt() and 0xFF) != MAGIC_PREFIX[index]) return false
        }
        return true
    }

    /**
     * Decode one frame, or null when [data] is not one.
     *
     * Null is the only failure mode and it is total. There is no partial decode here and no
     * salvage of a frame that is nearly right: a 19-octet packet with a wrong prefix is another
     * device's traffic on a characteristic FitBridge should not have been reading, and a
     * 18-octet packet is a truncation whose field boundaries are unknowable.
     */
    fun parse(data: ByteArray): Reading? {
        if (!isChronoFrame(data)) return null
        return Reading(
            elapsedSeconds = u16(data, OFFSET_ELAPSED),
            heartRateBpm = u8(data, OFFSET_HEART_RATE),
            speedKph = u16(data, OFFSET_SPEED) / SPEED_SCALE,
            cadenceRpm = u16(data, OFFSET_CADENCE) / CADENCE_SCALE,
            distanceMetres = u24(data, OFFSET_DISTANCE),
            calories = u16(data, OFFSET_CALORIES),
            powerWatts = u16(data, OFFSET_POWER),
            cadenceHighOctet = u8(data, OFFSET_CADENCE + 1),
            unidentified15to16 = u16(data, OFFSET_UNIDENTIFIED),
        )
    }

    private fun u8(data: ByteArray, offset: Int): Int = data[offset].toInt() and 0xFF

    private fun u16(data: ByteArray, offset: Int): Int =
        u8(data, offset) or (u8(data, offset + 1) shl 8)

    private fun u24(data: ByteArray, offset: Int): Int =
        u8(data, offset) or (u8(data, offset + 1) shl 8) or (u8(data, offset + 2) shl 16)
}

/**
 * Whether this frame's byte 9 is evidence that cadence really is a `uint16` (divergence V10).
 *
 * A non-zero high octet is only producible by TMIW's reading; under QZ's reading byte 9 is an
 * unused octet that would have no reason to hold 3 while the machine reports 102 rpm. Every
 * such frame is another confirmation, and their complete absence across a long session at
 * working cadence would be the first sign the resolution was wrong — because under TMIW's
 * reading a byte 9 permanently at zero caps cadence at 25.5 rpm, which nobody rides.
 *
 * Cheap enough to evaluate per frame; the handler logs the transition once per link rather
 * than the value, so this costs nothing on the notification path.
 */
@FitBridgeBleInternalApi
fun PrecorMeasurement.Reading.confirmsCadenceIsUint16(): Boolean = cadenceHighOctet != 0

/** Name used in the one-shot log line, so the string is not built inline in the handler. */
internal const val PRECOR_CADENCE_HIGH_OCTET_OBSERVED =
    "Precor cadence high octet non-zero; divergence V10 confirmed on this link"
