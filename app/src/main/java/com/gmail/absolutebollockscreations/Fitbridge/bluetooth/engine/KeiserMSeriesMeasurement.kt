package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

/**
 * Bounds-checked decoder for the Keiser M-series advertisement payload (plan §11, phase 11.3).
 *
 * **Sources.** Unusually for this repository, there is a vendor document:
 * `https://dev.keiser.com/mseries/direct/` publishes the field table and one worked parse example.
 * gymnasticon v1.4.1 (`e142f539`, MIT) corroborates the magic prefix, the cadence and power
 * offsets, the realtime predicate and the firmware timeout rule from an independent
 * implementation. The three sanitized QZ debug logs behind
 * `fixtures/hardware/keiser_m_qz_debug_advertisements.json` supply 5,785 real advertisements
 * across firmware 6.38, 6.46 and 6.54. Vendor plus permissive source plus capture is the
 * strongest evidence position any family in this project has had; what follows is therefore
 * unusually free of guesses, and the few remaining ones are labelled.
 *
 * ## There is no connection here, and that shapes the whole file
 *
 * A Keiser M3 is a beacon. It never accepts a connection, exposes no service, and answers no
 * write. Every value below arrives in a repeated manufacturer-data advertisement, which means
 * the same payload may be delivered many times, out of order, or not at all, and that nothing
 * downstream may present this machine as connected. This decoder is pure: it holds no clock, no
 * selection, no scan and no dropout state, so none of that ambiguity can be resolved here. It is
 * resolved in the advertisement source (phase 11.5) with a monotonic arrival clock.
 *
 * ## Canonical field — the company ID is part of the buffer
 *
 * Android's `ScanRecord` splits manufacturer data into a company ID and a value, so its callback
 * hands FitBridge 17 bytes beginning with the firmware major. The vendor document, gymnasticon
 * and the capture corpus all describe a buffer that *begins* `02 01`. Rather than carry two
 * shapes through the code, the Android adapter (phase 11.4) prepends the little-endian company
 * ID once and everything below — decoder, fixtures and simulator alike — speaks the 19-byte
 * canonical field. This function is the single definition of that field.
 *
 * ```
 *   0..1   Company / magic     02 01               identity, not data
 *   2      Firmware major      uint8
 *   3      Firmware minor      uint8, read as hex  0x30 is "30", not decimal 48
 *   4      Data type           uint8               realtime iff 0x00 or 0x81..0xFE
 *   5      System ID           uint8               the number shown on the bike
 *   6..7   Cadence             uint16 LE, ÷10 rpm
 *   8..9   Heart rate          uint16 LE, ÷10 bpm
 *  10..11  Power               uint16 LE, W
 *  12..13  Energy              uint16 LE, kcal     cumulative
 *  14      Elapsed minutes     uint8
 *  15      Elapsed seconds     uint8
 *  16..17  Distance            uint16 LE           bit 15 selects unit; bits 0..14 are tenths
 *  18      Resistance          uint8               present only from firmware minor 0x21
 * ```
 *
 * ## Firmware bytes are hexadecimal, and reading them as decimal breaks both thresholds
 *
 * gymnasticon writes the version as `major.toString(16) + "." + minor.toString(16)` and compares
 * against `parseInt("30", 16)`. Firmware "6.30" is therefore the byte pair `06 30`, and its minor
 * byte is decimal 48. Both thresholds in this file — resistance presence at minor `0x21` and the
 * fast broadcast interval at minor `0x30` — are comparisons of the raw byte, never of a rendered
 * string and never of a float. Rendering "6.30" and then comparing it as the number 6.3 would put
 * firmware 6.40 (`0x40`, decimal 64) *below* 6.30, which is the specific error the plan calls out.
 *
 * [firmware] pads the minor to two hex digits, so byte pair `06 02` renders "6.02". gymnasticon
 * would render it "6.2". No observed firmware has a minor below `0x12`, so the two renderings have
 * never disagreed on real traffic; the padded form is used because it is the one that cannot be
 * misread as a decimal fraction.
 *
 * ## Two thresholds, two different rules, and the asymmetry is the sources' not ours
 *
 * Resistance presence is gated on the **minor byte alone** (`minor >= 0x21`). The statistics
 * timeout is gated on **major 6 *and* minor >= 0x30**, so a hypothetical firmware 7.00 falls back
 * to the seven-second window. Both come straight from their sources — the vendor layout for the
 * first, gymnasticon's `bikeVersion` for the second — and neither source explains the difference.
 * It is preserved rather than harmonised: guessing that Keiser meant the same rule twice would be
 * inventing protocol, and the fallback in each case is the conservative one.
 *
 * ## Distance bit 15 is a unit selector, not a sign
 *
 * The word at 16..17 is not a signed distance. Bit 15 set means kilometres, clear means miles, and
 * the low fifteen bits are tenths of that unit. Reading it as `int16` would turn every metric-unit
 * bike into a negative distance, which is why [DistanceUnit] exists as a decoded field rather than
 * as an inferred sign. Imperial tenths convert at 160.934 m — a tenth of the 1.60934 km mile the
 * plan pins — and metric tenths at exactly 100 m.
 *
 * ## Cadence and heart rate are tenths, and the earlier plan said they were not
 *
 * A QZ-derived draft of this phase listed cadence and heart rate as unscaled. Vendor documentation,
 * gymnasticon's cadence decoding and the capture corpus all say ÷10, and the corpus settles it:
 * unscaled, its heart-rate fields would read 1,597 and 2,395 bpm. The corpus is also why the
 * division is *not* rounded here. gymnasticon rounds cadence to a whole rpm; the wire carries
 * tenths and the captures contain fractional values such as 82.4 and 78.0 rpm, so rounding would
 * discard evidenced precision for nothing. That divergence is recorded as V24 in the fixture.
 *
 * ## Review packets are decoded and marked, not rejected here
 *
 * Data type `0x00` or `0x81..0xFE` is live telemetry; `0x01`, `0x80` and `0xFF` are review-mode
 * replay of a finished ride. The captures contain 137 review packets across 5,785, including 70
 * consecutive `0xFF` frames — a bike sitting on its summary screen, replaying a ride that has
 * ended. Publishing those as live telemetry would show a stationary bike producing power.
 *
 * They are still decoded. A caller that wants to log or capture what a review frame contained can,
 * and [Reading.isRealtime] is the single predicate everything downstream filters on. Refusing to
 * decode them would make a diagnostic capture of a review packet unreadable, which is the opposite
 * of what the capture channel is for.
 *
 * ## What is deliberately absent
 *
 * **Speed.** The protocol does not carry it. Phase 11 does not derive or estimate it, and there is
 * no field here that a later reader could mistake for one.
 *
 * **Anything writable.** There is no control point, no target, no resistance command. Resistance
 * at byte 18 is a *reading* of where the rider set the lever.
 */
internal object KeiserMSeriesMeasurement {

    /** Bluetooth SIG company identifier assigned to Keiser, little-endian on the wire as `02 01`. */
    const val COMPANY_ID = 0x0102

    /** The invariant first two octets of the canonical field. */
    val MAGIC_PREFIX = intArrayOf(0x02, 0x01)

    /** Canonical length once byte 18 is present. */
    const val CURRENT_FIELD_LENGTH = 19

    /** Canonical length for firmware that predates the resistance byte. */
    const val LEGACY_FIELD_LENGTH = 18

    /** Smallest firmware minor byte that carries byte 18. Compared as a byte, never as text. */
    const val RESISTANCE_FROM_MINOR = 0x21

    /** The fast-broadcast firmware: major exactly this, and minor at least [FAST_INTERVAL_MINOR]. */
    const val FAST_INTERVAL_MAJOR = 6

    /** gymnasticon's `KEISER_STATS_NEWVER_MINOR`, read as hexadecimal. */
    const val FAST_INTERVAL_MINOR = 0x30

    /** Android-tolerant statistics window for firmware advertising at roughly 300–355 ms. */
    const val FAST_STATS_TIMEOUT_MS = 2_000L

    /** Statistics window for older or unrecognised firmware advertising at roughly 2 s. */
    const val SLOW_STATS_TIMEOUT_MS = 7_000L

    /** Absence after which the bike is gone rather than merely stale. Owned by phase 11.5. */
    const val BIKE_GONE_TIMEOUT_MS = 60_000L

    /** One tenth of the 1.60934 km statute mile the plan pins, in metres. */
    const val METRES_PER_TENTH_MILE = 160.934

    /** One tenth of a kilometre, in metres. Exact by definition. */
    const val METRES_PER_TENTH_KILOMETRE = 100.0

    /** Cadence and heart rate are both transmitted in tenths of their display unit. */
    const val TENTHS_SCALE = 10.0

    private const val OFFSET_FIRMWARE_MAJOR = 2
    private const val OFFSET_FIRMWARE_MINOR = 3
    private const val OFFSET_DATA_TYPE = 4
    private const val OFFSET_SYSTEM_ID = 5
    private const val OFFSET_CADENCE = 6
    private const val OFFSET_HEART_RATE = 8
    private const val OFFSET_POWER = 10
    private const val OFFSET_ENERGY = 12
    private const val OFFSET_ELAPSED_MINUTES = 14
    private const val OFFSET_ELAPSED_SECONDS = 15
    private const val OFFSET_DISTANCE = 16
    private const val OFFSET_RESISTANCE = 18

    /** Fewest octets needed to know the firmware, and so to know the expected total length. */
    private const val MINIMUM_HEADER_LENGTH = 4

    private const val DISTANCE_UNIT_BIT = 0x8000
    private const val DISTANCE_TENTHS_MASK = 0x7fff

    /**
     * The firmware bytes, read from the header alone.
     *
     * Deliberately separate from [Reading]: the version is knowable from four octets, while a full
     * decode needs the record to be exactly the length that same version implies. gymnasticon draws
     * the identical line — `bikeVersion` checks only the magic prefix and reads bytes 2 and 3,
     * while `parse` is a different function — and phase 11 needs both halves. A bike whose record
     * length FitBridge rejects can still say which firmware it is running, which is the difference
     * between a diagnostic that names the problem and one that says "unknown device".
     *
     * The vendor/gymnasticon version fixtures exist precisely at this boundary: they vary only
     * bytes 2 and 3 of one 19-octet buffer and are labelled `version-only; not packet-shape
     * evidence`, so firmware 5.12 appears there in a record whose length its own resistance rule
     * forbids. Asking [parse] about those vectors is a category error.
     */
    data class FirmwareVersion(val major: Int, val minor: Int) {

        /** Hexadecimal, for display and diagnostics only. Never parsed back into a number. */
        val display: String get() = KeiserMSeriesMeasurement.firmwareText(major, minor)

        /** How long this firmware's values stay valid without a new advertisement. */
        val statsTimeoutMs: Long get() = KeiserMSeriesMeasurement.statsTimeoutMs(major, minor)

        /** Whether a record from this firmware carries byte 18. */
        val carriesResistance: Boolean get() = KeiserMSeriesMeasurement.carriesResistance(minor)
    }

    /**
     * Read the firmware from [data]'s header, or null when [data] is not a Keiser field at all.
     *
     * Checks the length needed to reach the version bytes and the magic prefix, and nothing else.
     * A record that this accepts may still fail [parse].
     */
    fun firmwareVersion(data: ByteArray): FirmwareVersion? {
        if (data.size < MINIMUM_HEADER_LENGTH) return null
        for (index in MAGIC_PREFIX.indices) {
            if (u8(data, index) != MAGIC_PREFIX[index]) return null
        }
        return FirmwareVersion(u8(data, OFFSET_FIRMWARE_MAJOR), u8(data, OFFSET_FIRMWARE_MINOR))
    }

    /** Hexadecimal firmware rendering, shared by [Reading] and [FirmwareVersion]. */
    fun firmwareText(firmwareMajor: Int, firmwareMinor: Int): String =
        "${firmwareMajor.toString(16)}.${firmwareMinor.toString(16).padStart(2, '0')}"

    /** Which unit the bike's console is displaying. Bit 15 of the distance word selects it. */
    enum class DistanceUnit(val wireName: String, val metresPerTenth: Double) {
        MILES("miles", METRES_PER_TENTH_MILE),
        KILOMETRES("kilometres", METRES_PER_TENTH_KILOMETRE),
    }

    /**
     * One decoded advertisement.
     *
     * @param firmwareMajor byte 2 verbatim.
     * @param firmwareMinor byte 3 verbatim, as a byte. Compare against this, never against
     * [firmware].
     * @param statsTimeoutMs how long this firmware's values stay valid without a new
     * advertisement. Carried on the reading because it is a property of the *packet*, not of the
     * source, and phase 11.5 must not have to re-derive it.
     * @param dataType byte 4 verbatim, kept so a capture of a rejected frame says which mode it
     * claimed.
     * @param isRealtime the only predicate downstream may filter on.
     * @param systemId the number the rider sees on the bike, and the in-session selector when two
     * bikes are in range.
     * @param elapsedSeconds the machine's own ride timer. Protocol time; §8.5 makes it
     * non-authoritative for a FitBridge session, and §3.5 forbids it from touching freshness.
     * @param resistance byte 18, or null when this firmware does not carry it. Null is "the
     * protocol has nothing to say", not "level zero".
     */
    data class Reading(
        val firmwareMajor: Int,
        val firmwareMinor: Int,
        val statsTimeoutMs: Long,
        val dataType: Int,
        val isRealtime: Boolean,
        val systemId: Int,
        val cadenceRpm: Double,
        val heartRateBpm: Double,
        val powerWatts: Int,
        val energyKcal: Int,
        val elapsedSeconds: Int,
        val distanceUnit: DistanceUnit,
        val distanceRawTenths: Int,
        val distanceMetres: Double,
        val resistance: Int?,
    ) {
        /** Hexadecimal firmware for display and diagnostics only. Never parsed back into a number. */
        val firmware: String get() =
            KeiserMSeriesMeasurement.firmwareText(firmwareMajor, firmwareMinor)
    }

    /**
     * Whether [data] could be a Keiser canonical field at all.
     *
     * Length before content, then magic, then the firmware-derived exact length — in that order,
     * because each read is only safe once the previous check has passed. Public so the phase 11.4
     * matcher uses this predicate rather than a second copy that can drift away from it.
     *
     * The final length check is exact, not a minimum. A 20-octet buffer is rejected even though
     * its first 19 octets would decode: the fixture's `trailing_byte_policy` is "reject until
     * vendor or capture evidence establishes an extension", and 5,785 captured advertisements
     * contain no wrong-length packet at all. Admitting a trailing byte now would mean guessing
     * that a future firmware extends the record forwards rather than, say, redefining byte 18.
     */
    fun isKeiserField(data: ByteArray): Boolean {
        val version = firmwareVersion(data) ?: return false
        return data.size == expectedLength(version.minor)
    }

    /**
     * Decode one canonical field, or null when [data] is not one.
     *
     * Null is the only failure mode and it is total. There is no partial reading: a field
     * truncated anywhere has unknowable boundaries beyond the cut, and the fixture's truncation
     * matrix asserts that every one of the nineteen prefixes of the official vector is rejected.
     * A partial decode would be worse than no decode, because a plausible-looking cadence
     * recovered from a mangled packet is exactly the value nothing downstream would question.
     */
    fun parse(data: ByteArray): Reading? {
        if (!isKeiserField(data)) return null
        val major = u8(data, OFFSET_FIRMWARE_MAJOR)
        val minor = u8(data, OFFSET_FIRMWARE_MINOR)
        val dataType = u8(data, OFFSET_DATA_TYPE)
        val distanceWord = u16(data, OFFSET_DISTANCE)
        val unit =
            if (distanceWord and DISTANCE_UNIT_BIT != 0) DistanceUnit.KILOMETRES else DistanceUnit.MILES
        val tenths = distanceWord and DISTANCE_TENTHS_MASK
        return Reading(
            firmwareMajor = major,
            firmwareMinor = minor,
            statsTimeoutMs = statsTimeoutMs(major, minor),
            dataType = dataType,
            isRealtime = isRealtime(dataType),
            systemId = u8(data, OFFSET_SYSTEM_ID),
            cadenceRpm = u16(data, OFFSET_CADENCE) / TENTHS_SCALE,
            heartRateBpm = u16(data, OFFSET_HEART_RATE) / TENTHS_SCALE,
            powerWatts = u16(data, OFFSET_POWER),
            energyKcal = u16(data, OFFSET_ENERGY),
            elapsedSeconds = u8(data, OFFSET_ELAPSED_MINUTES) * 60 + u8(data, OFFSET_ELAPSED_SECONDS),
            distanceUnit = unit,
            distanceRawTenths = tenths,
            distanceMetres = tenths * unit.metresPerTenth,
            resistance = if (carriesResistance(minor)) u8(data, OFFSET_RESISTANCE) else null,
        )
    }

    /**
     * Live telemetry, or review-mode replay of a finished ride.
     *
     * `0x00` and `0x81..0xFE` are live. gymnasticon writes the same predicate as
     * `realtime === 0 || (realtime > 128 && realtime < 255)`, and the vendor document's
     * data-type section is where both come from. An earlier QZ-derived draft of this phase
     * accepted every `uint8`, which is not a predicate at all — it accepts `0xFF`, the value the
     * captures show a bike sending 70 times in a row while displaying a ride summary.
     */
    fun isRealtime(dataType: Int): Boolean = dataType == 0x00 || dataType in 0x81..0xfe

    /** Whether this firmware minor byte carries byte 18. See the asymmetry note in the file header. */
    fun carriesResistance(firmwareMinor: Int): Boolean = firmwareMinor >= RESISTANCE_FROM_MINOR

    /**
     * How long this firmware's values remain valid without a new advertisement.
     *
     * Old bikes broadcast about every two seconds and new ones every 300–355 ms, so one window
     * cannot serve both: seven seconds on a fast bike would hold a dead reading for twenty
     * missed advertisements, and one second on a slow bike would clear a perfectly live one.
     * Unrecognised firmware gets the seven-second window, which fails towards keeping a real
     * reading rather than towards blanking it.
     */
    fun statsTimeoutMs(firmwareMajor: Int, firmwareMinor: Int): Long =
        if (firmwareMajor == FAST_INTERVAL_MAJOR && firmwareMinor >= FAST_INTERVAL_MINOR) {
            FAST_STATS_TIMEOUT_MS
        } else {
            SLOW_STATS_TIMEOUT_MS
        }

    private fun expectedLength(firmwareMinor: Int): Int =
        if (carriesResistance(firmwareMinor)) CURRENT_FIELD_LENGTH else LEGACY_FIELD_LENGTH

    private fun u8(data: ByteArray, offset: Int): Int = data[offset].toInt() and 0xFF

    private fun u16(data: ByteArray, offset: Int): Int =
        u8(data, offset) or (u8(data, offset + 1) shl 8)
}

/**
 * Repairs the M-series' single-frame cadence and power dropouts (plan §3.4).
 *
 * ## The defect is real and it is asymmetric
 *
 * A Keiser occasionally advertises zero power while still reporting cadence, or zero cadence while
 * still reporting power. gymnasticon carries a filter for exactly this
 * (`src/util/dropout-filter.js`, shared with the Flywheel bike), and the QZ capture corpus contains
 * both shapes: a frame reading 21.6 rpm and 0 W between frames reading 26 W and 22 W, and a frame
 * reading 0 rpm and 27 W between frames reading 27.3 rpm and 19.4 rpm. Neither is a physical
 * transition — power does not vanish and return within 300 ms at constant cadence.
 *
 * The rule is asymmetric because the *evidence* is: a zero in one field while the other still shows
 * motion is a glitch, and zeros in both fields are a rider who has stopped pedalling. The corpus
 * contains a genuine coast — cadence and power both zero while heart rate reads 187.8 bpm and the
 * ride timer keeps running — and holding the previous values through it would show a stationary
 * rider producing 60 W.
 *
 * ## Substitution lasts exactly one frame, and that is a deliberate bound
 *
 * The previous values recorded here are the ones **as decoded**, not the ones this filter
 * published. So a second consecutive zero-power frame fails the `previous power > 0` test and
 * passes through as zero. gymnasticon does the same (`prev = curr`, before the fix is applied),
 * and the difference matters: seeding from the published value instead would let one glitch hold a
 * stale wattage indefinitely for as long as the rider keeps spinning, and a fabricated power
 * number that never expires is a worse failure than a one-frame flicker to zero.
 *
 * The captures only ever show single-frame dropouts, so a longer run of zeros is not a dropout
 * this project has evidence for. Treating it as real is the honest default.
 *
 * ## What must not reach this filter
 *
 * Malformed, truncated, wrong-system-ID and review-mode packets must never be offered here: they
 * would seed `previous` with values from another bike or from a replayed ride. The advertisement
 * source (phase 11.5) owns that gate, and [reset] exists so a new source generation — a
 * reselected bike, or one that went away and came back — starts with no memory of the last.
 */
internal class KeiserDropoutFilter {

    /**
     * The last reading's values **as decoded**. Both start at zero, which is why no separate
     * "have I seen a frame yet" flag is needed: every substitution rule already requires the
     * corresponding previous value to be strictly positive, so an unseeded filter can never
     * substitute.
     */
    private var previousCadenceRpm: Double = 0.0
    private var previousPowerWatts: Int = 0

    /** Whether the last call replaced a spurious zero. Diagnostic only; the reading is authoritative. */
    var lastSubstitutedCadence: Boolean = false
        private set

    /** Whether the last call replaced a spurious zero. Diagnostic only; the reading is authoritative. */
    var lastSubstitutedPower: Boolean = false
        private set

    /**
     * Apply the asymmetric rule to one accepted realtime reading.
     *
     * Callers pass only accepted realtime readings; see the class comment for why.
     */
    fun filter(reading: KeiserMSeriesMeasurement.Reading): KeiserMSeriesMeasurement.Reading {
        val priorCadenceRpm = previousCadenceRpm
        val priorPowerWatts = previousPowerWatts

        // Recorded before substitution on purpose: see "Substitution lasts exactly one frame".
        previousCadenceRpm = reading.cadenceRpm
        previousPowerWatts = reading.powerWatts

        val holdPower = reading.powerWatts == 0 && reading.cadenceRpm > 0.0 && priorPowerWatts > 0
        val holdCadence = reading.cadenceRpm == 0.0 && reading.powerWatts > 0 && priorCadenceRpm > 0.0
        lastSubstitutedPower = holdPower
        lastSubstitutedCadence = holdCadence

        if (!holdPower && !holdCadence) return reading
        return reading.copy(
            cadenceRpm = if (holdCadence) priorCadenceRpm else reading.cadenceRpm,
            powerWatts = if (holdPower) priorPowerWatts else reading.powerWatts,
        )
    }

    /** Forget everything. Called when a source generation ends or a different bike is selected. */
    fun reset() {
        previousCadenceRpm = 0.0
        previousPowerWatts = 0
        lastSubstitutedCadence = false
        lastSubstitutedPower = false
    }
}
