package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

/**
 * Decoder for RSC Measurement, `0x2A53`.
 *
 * Pure and Android-free so the wire format is unit-testable, in the same shape
 * as the FTMS encoders. Every field is bounds-checked: a short packet yields
 * whatever prefix is complete rather than throwing, because a truncated
 * notification is a device problem and must not take the connection down.
 *
 * ### Wire format
 *
 * | Offset | Field | Type | Scale | Presence |
 * |---|---|---|---|---|
 * | 0 | Flags | uint8 | — | always |
 * | 1-2 | Instantaneous Speed | uint16 | 1/256 m/s | always |
 * | 3 | Instantaneous Cadence | uint8 | steps/min | always |
 * | 4-5 | Instantaneous Stride Length | uint16 | 1/100 m | flag bit 0 |
 * | next 4 | Total Distance | uint32 | 1/10 m | flag bit 1 |
 *
 * Flag bit 2 is walking (1) / running (0) and carries no field of its own.
 *
 * ### Two things worth knowing
 *
 * **Only bits 0 and 1 affect the layout.** The walking/running bit changes with
 * gait and must be masked out before deriving offsets, or a runner slowing to a
 * walk mid-session would appear to change the packet shape. TMIW masks with
 * `& 3` for exactly this reason and says so.
 *
 * **Stride length is parsed here, where TMIW skips it.** TMIW advances past the
 * field without decoding it. It costs two bytes of arithmetic, and stride length
 * times cadence is an independent cross-check on distance - useful precisely
 * when a footpod's own distance is suspect.
 *
 * ### Divergence from TMIW: "not available" sentinels (plan §1.5.1)
 *
 * TMIW returns null for a speed of `0xFFFF` and a distance of `0xFFFFFFFF`,
 * because `ShortMetricDescriptor` and `LongMetricDescriptor` are shared with its
 * FTMS decoders and carry the FTMS convention with them.
 *
 * **`0x2A53` defines no such sentinels.** This decoder therefore does not
 * implement them, by explicit decision on 2026-08-06: the reference behaviour is
 * an artefact of code reuse rather than a reading of the RSC specification, and
 * inventing a spec rule to match it would be the wrong kind of corroboration.
 *
 * The consequence is real and deliberate: a device sending `0xFFFF` produces
 * 921.5859375 km/h rather than "no reading". The defence against that belongs in
 * the §11b runtime plausibility bounds, which are still owed for this phase -
 * not in a magic constant here. Recorded in the divergence register.
 */
internal object RscMeasurement {

    /** Metres per second per wire unit: the speed field is in 1/256 m/s. */
    private const val SPEED_UNITS_PER_MPS = 256.0

    /** Metres per wire unit: stride length is in 1/100 m. */
    private const val STRIDE_UNITS_PER_METRE = 100.0

    /** Metres per wire unit: total distance is in 1/10 m. */
    private const val DISTANCE_UNITS_PER_METRE = 10.0

    private const val FLAG_STRIDE_LENGTH = 0x01
    private const val FLAG_TOTAL_DISTANCE = 0x02
    private const val FLAG_WALKING = 0x04

    /** Smallest packet that carries the mandatory fields. */
    const val MINIMUM_LENGTH = 4

    /**
     * One decoded measurement.
     *
     * A null field was absent from the packet or was truncated away - callers
     * must not substitute zero, which is a legitimate reading for every one of
     * these.
     */
    data class Reading(
        val speedKph: Double,
        val cadenceStepsPerMinute: Int,
        val strideLengthMetres: Double? = null,
        val totalDistanceMetres: Double? = null,
        val walking: Boolean = false,
    )

    /**
     * @return the decoded reading, or null when the packet is too short to carry
     * even the mandatory speed and cadence.
     */
    fun parse(data: ByteArray): Reading? {
        if (data.size < MINIMUM_LENGTH) return null

        val flags = data[0].toInt() and 0xFF
        val speedRaw = u16(data, 1)
        val cadence = data[3].toInt() and 0xFF

        // Layout depends only on bits 0 and 1; bit 2 is gait state.
        var offset = 4
        var strideLengthMetres: Double? = null
        if (flags and FLAG_STRIDE_LENGTH != 0) {
            if (data.size >= offset + 2) {
                strideLengthMetres = u16(data, offset) / STRIDE_UNITS_PER_METRE
            }
            offset += 2
        }

        var totalDistanceMetres: Double? = null
        if (flags and FLAG_TOTAL_DISTANCE != 0 && data.size >= offset + 4) {
            totalDistanceMetres = u32(data, offset) / DISTANCE_UNITS_PER_METRE
        }

        return Reading(
            speedKph = speedRaw / SPEED_UNITS_PER_MPS * MPS_TO_KPH,
            cadenceStepsPerMinute = cadence,
            strideLengthMetres = strideLengthMetres,
            totalDistanceMetres = totalDistanceMetres,
            walking = flags and FLAG_WALKING != 0,
        )
    }

    private const val MPS_TO_KPH = 3.6

    /**
     * Human-readable RSC Feature bits (`0x2A54`), for the connection log.
     *
     * Advisory only. The per-packet flags are authoritative for layout, so a
     * device whose feature bits disagree with what it actually sends still
     * decodes correctly - this exists to make that disagreement visible during
     * triage rather than to drive any decision.
     */
    fun describeFeatures(features: Int): String {
        val named = buildList {
            if (features and 0x0001 != 0) add("stride-length")
            if (features and 0x0002 != 0) add("total-distance")
            if (features and 0x0004 != 0) add("walking-or-running")
            if (features and 0x0008 != 0) add("calibration")
            if (features and 0x0010 != 0) add("multiple-sensor-locations")
        }
        return if (named.isEmpty()) "none advertised" else named.joinToString(", ")
    }

    private fun u16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or
            ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or
            ((b[at + 3].toLong() and 0xFF) shl 24)
}
