package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.FitBridgeBleInternalApi

/**
 * Encodes FE-C over BLE control pages (48/49/50/51) for FEC3, per plan §2.5/§10/§16.6.
 *
 * Every function here is pure - it builds an ANT serial frame via [FeCFrameCodec.encode] and
 * returns bytes; it does not touch a `BluetoothGatt`, does not know about write-without-response,
 * and does not decide *when* to send anything. Dispatch is `FeCControlScheduler`'s job (§9.9);
 * this file's only obligation is that the bytes it produces are correct.
 *
 * Every control write uses [BluetoothIds.FEC_ANT_MSG_ACKNOWLEDGED_DATA] (`0x4F`), matching the
 * Tacx PDF's worked example and every FE-C control page this plan documents - broadcast data
 * (`0x4E`) is for the machine's outbound telemetry, not commands sent to it.
 *
 * Page 55 (User Configuration) is deliberately **not implemented here**. Its packed bit layout
 * is not pinned by any vector this project has evidence for (plan §21 does not resolve it, and
 * inventing one would mean guessing which bits mean what on a page nothing here has tested
 * against a real trainer). Pages 48/49/50/51 are implemented because each has either a pinned
 * byte-for-byte vector (51, from the Tacx PDF) or an unambiguous, narrowly-scoped ANT+ FE-C
 * field definition (48/49/50) with an explicit "unknown" sentinel this code always has a safe
 * value for.
 */
@FitBridgeBleInternalApi
object FeCControlEncoder {

    private const val CHANNEL = BluetoothIds.FEC_DEFAULT_CHANNEL
    private const val RESERVED: Byte = 0xFF.toByte()
    private const val FIELD_UNAVAILABLE = 0xFF

    /** Page 70 - request a specific data page from the fitness equipment. */
    fun encodeRequestDataPage(
        requestedPage: Int,
        transmissionCount: Int = 2,
        acknowledgedResponse: Boolean = false,
    ): ByteArray {
        require(requestedPage in 0..0xFF) { "requestedPage out of range: $requestedPage" }
        require(transmissionCount in 1..0x7F) { "transmissionCount out of range: $transmissionCount" }
        val commandType = if (acknowledgedResponse) 0x81 else 0x01
        val payload = byteArrayOf(
            BluetoothIds.FEC_PAGE_REQUEST_DATA_PAGE.toByte(),
            RESERVED, RESERVED, RESERVED, RESERVED,
            transmissionCount.toByte(),
            requestedPage.toByte(),
            commandType.toByte(),
        )
        return FeCFrameCodec.encode(BluetoothIds.FEC_ANT_MSG_ACKNOWLEDGED_DATA, CHANNEL, payload)
    }

    /**
     * Page 48 - Basic Resistance. [percent] is total resistance in `0.0..100.0`, encoded at
     * 0.5% resolution (raw range 0..200). The legal range itself is the ANT+ FE-C spec's own
     * bound, not evidence-backed against a specific trainer's accepted range (plan §21 FEC-Q1) -
     * callers above this layer should not assume every device honors the full range.
     */
    fun encodeBasicResistance(percent: Double): ByteArray {
        require(percent.isFinite()) { "percent must be finite: $percent" }
        require(percent in 0.0..100.0) { "percent out of legal range 0.0..100.0: $percent" }
        val raw = Math.round(percent / 0.5).coerceIn(0L, 200L)
        val payload = byteArrayOf(
            BluetoothIds.FEC_PAGE_BASIC_RESISTANCE.toByte(),
            RESERVED, RESERVED, RESERVED, RESERVED, RESERVED, RESERVED,
            raw.toByte(),
        )
        return FeCFrameCodec.encode(BluetoothIds.FEC_ANT_MSG_ACKNOWLEDGED_DATA, CHANNEL, payload)
    }

    /**
     * Page 49 - Target Power. [watts] must be finite and non-negative, encoded at 0.25 W
     * resolution (uint16LE). `0xFFFF` is the ANT+ "invalid" sentinel, so the encodable raw
     * range stops one short of it rather than wrapping - plan §10/§21 FEC-Q1 leave the true
     * legal maximum for a real trainer unresolved, so this only refuses what the wire format
     * itself cannot represent, not what a physical device might reject.
     */
    fun encodeTargetPower(watts: Double): ByteArray {
        require(watts.isFinite()) { "watts must be finite: $watts" }
        require(watts >= 0.0) { "watts must not be negative: $watts" }
        val raw = Math.round(watts * 4.0)
        require(raw in 0..0xFFFE) { "target power out of encodable range: $watts W" }
        val payload = byteArrayOf(
            BluetoothIds.FEC_PAGE_TARGET_POWER.toByte(),
            RESERVED, RESERVED, RESERVED, RESERVED, RESERVED,
            (raw and 0xFF).toByte(),
            ((raw shr 8) and 0xFF).toByte(),
        )
        return FeCFrameCodec.encode(BluetoothIds.FEC_ANT_MSG_ACKNOWLEDGED_DATA, CHANNEL, payload)
    }

    /**
     * Page 50 - Wind Resistance. Every field is independently optional per the ANT+ spec's own
     * `0xFF` "use the trainer's default" sentinel - passing `null` encodes that sentinel rather
     * than this code inventing a coefficient, wind speed unit, or drafting factor it has no
     * evidence for (plan §21 FEC-Q3/Q4 are open; `null` is always the safe choice).
     *
     * @param windResistanceCoefficient kg/m, 0.01 resolution, non-negative.
     * @param windSpeedKmh km/h, 1.0 resolution, offset-encoded (`raw = speed + 127`), so the
     *   representable range is `-127..127` km/h.
     * @param draftingFactor `0.0..1.0`, encoded as a 0.01 raw fraction (i.e. `0..100`).
     */
    fun encodeWindResistance(
        windResistanceCoefficient: Double? = null,
        windSpeedKmh: Double? = null,
        draftingFactor: Double? = null,
    ): ByteArray {
        val coefficientByte = windResistanceCoefficient?.let {
            require(it.isFinite() && it >= 0.0) { "windResistanceCoefficient must be finite and non-negative: $it" }
            Math.round(it / 0.01).coerceIn(0L, 254L).toInt()
        } ?: FIELD_UNAVAILABLE

        val windSpeedByte = windSpeedKmh?.let {
            require(it.isFinite()) { "windSpeedKmh must be finite: $it" }
            require(it in -127.0..127.0) { "windSpeedKmh out of encodable range -127..127: $it" }
            Math.round(it) + 127L
        } ?: FIELD_UNAVAILABLE.toLong()

        val draftingByte = draftingFactor?.let {
            require(it.isFinite() && it in 0.0..1.0) { "draftingFactor must be within 0.0..1.0: $it" }
            Math.round(it / 0.01).coerceIn(0L, 254L)
        } ?: FIELD_UNAVAILABLE.toLong()

        val payload = byteArrayOf(
            BluetoothIds.FEC_PAGE_WIND_RESISTANCE.toByte(),
            RESERVED, RESERVED, RESERVED, RESERVED,
            coefficientByte.toByte(),
            windSpeedByte.toByte(),
            draftingByte.toByte(),
        )
        return FeCFrameCodec.encode(BluetoothIds.FEC_ANT_MSG_ACKNOWLEDGED_DATA, CHANNEL, payload)
    }

    /**
     * Page 51 - Track Resistance. Byte layout verified against the Tacx PDF's `setSlope(0)`
     * worked example (plan §16.6's pinned vector `A4 09 4F 05 33 FF FF FF FF 20 4E 42 F8`),
     * not assumed from prose alone: reserved bytes 1-4, grade as a uint16LE at bytes 5-6,
     * rolling resistance coefficient as a single byte at 7. Byte layout and default-value
     * semantics both additionally confirmed against the official ANT+ FE-C spec (D000001231
     * Rev 5.0, Table 8-42/§8.8.4).
     *
     * @param gradePercent `-200.0..200.0`, 0.01% resolution, offset-encoded (`raw = (grade +
     *   200) / 0.01`). Always sent as a concrete value - grade is the whole point of every call
     *   to this function, so there is no "omit it" case the way there is for [rollingResistanceCoefficient].
     * @param rollingResistanceCoefficient dimensionless Crr, non-negative, 0.00005 resolution, or
     *   `null` to encode the spec's own `0xFF` "use the device's default" sentinel (§8.8.4.2's
     *   0.004 asphalt-road default) rather than inventing a value. Per §8.8.4.2, this field
     *   "is applicable to controllable trainers only, and shall be ignored by other types of
     *   fitness equipment" - so `null` is not just the safe choice, it is the spec-correct one
     *   for a caller (e.g. an inclination-only write to a treadmill) that has no rolling
     *   resistance figure to offer and does not need one.
     */
    fun encodeTrackResistance(gradePercent: Double, rollingResistanceCoefficient: Double? = null): ByteArray {
        require(gradePercent.isFinite()) { "gradePercent must be finite: $gradePercent" }
        require(gradePercent in -200.0..200.0) { "gradePercent out of legal range -200..200: $gradePercent" }
        val crrRaw = rollingResistanceCoefficient?.let {
            require(it.isFinite() && it >= 0.0) {
                "rollingResistanceCoefficient must be finite and non-negative: $it"
            }
            Math.round(it / 0.00005).coerceIn(0L, 0xFEL)
        } ?: FIELD_UNAVAILABLE.toLong()
        val gradeRaw = Math.round((gradePercent + 200.0) / 0.01).coerceIn(0L, 0xFFFEL)
        val payload = byteArrayOf(
            BluetoothIds.FEC_PAGE_TRACK_RESISTANCE.toByte(),
            RESERVED, RESERVED, RESERVED, RESERVED,
            (gradeRaw and 0xFF).toByte(),
            ((gradeRaw shr 8) and 0xFF).toByte(),
            crrRaw.toByte(),
        )
        return FeCFrameCodec.encode(BluetoothIds.FEC_ANT_MSG_ACKNOWLEDGED_DATA, CHANNEL, payload)
    }
}
