package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

/**
 * Bounds-checked decoders for the Schwinn x70 family — 170, 270 and 570 (plan §7).
 *
 * ## This family is two consoles that share a namespace and nothing else
 *
 * Both physical captures agree. A **170** emits paired 20-byte and 14-byte notifications on two
 * separate characteristics — 698 of each in TMIW's trace, one-for-one — and **no 17-byte stream at
 * all**. A **270** and a submitter-labelled **570u** emit 17-byte notifications on the main
 * measurement characteristic. TMIW implements only the 17-byte path; QZ only the 170 pair. Neither
 * project is wrong and neither alone covers the family, which is why [Frame] is a sealed set rather
 * than one layout with optional fields.
 *
 * **Dispatch is by characteristic, never by length.** QZ dispatches on payload size alone
 * (`Q_UNUSED(characteristic)`), which means any 20-byte notification from any source would be read
 * as this family's supplemental frame. §7 says not to, and [parseSupplemental] and [parseSibling]
 * are separate entry points so a caller cannot accidentally do it.
 *
 * ## Byte 0 is the frame length, not a magic number (V17)
 *
 * TMIW treats `[17, 32, 0]` as a magic prefix. `17` is `0x11`, and the frame is 17 octets. Both
 * known initialization commands are length-prefixed identically — `05 03 D9 00 1F` is five octets,
 * `07 01 D3 00 1F 05 01` is seven — and both sum to exactly `0x100`, a checksum making the frame
 * zero mod 256. Two independently authored commands satisfying both rules is not coincidence.
 *
 * **Confirmed on the main characteristic and falsified elsewhere, which is the useful result.** The
 * 570u session's single 5-octet notification begins `0x05`, so two frame sizes on that
 * characteristic are both self-describing. But I predicted the 170's frames would begin `0x14` and
 * `0x0E` and they do not: byte 0 of both its streams is a wrapping sequence counter. The rule
 * belongs to the main characteristic, not to the family, and generalising it would have produced a
 * frame check that rejects every genuine 170 packet. The 17-byte check therefore still verifies the
 * observed prefix rather than computing it.
 *
 * ## What is confirmed, and what is not
 *
 * The 17-byte **stateless** layout is confirmed against all eight of TMIW's captured vectors:
 * time at 8–9 ÷1024 reproduces its asserted elapsed seconds and milliseconds, and calories at
 * 10–15 ÷2097152 reproduces its asserted calorie count, on every one.
 *
 * Its **stateful** derivations were not ported, and the user's captures explain why they could not
 * be. TMIW asserts cadence 0 in all eight of its vectors and power up to 4538 W at 102.6 km/h —
 * impossible on a spin bike. **Its vectors are sparse samples from long sessions** and the tick
 * counter wraps every 64 seconds, so no valid inter-frame delta exists: the cadence window is fed
 * nonsense and the power model faithfully converts it into a large number. TMIW's own data tests
 * are commented out (lines 42–266), which is consistent with the author having hit exactly this.
 *
 * With **consecutive** captured frames the same counter behaves exactly as a crank counter should —
 * about 42 rpm across the 270 session, 70–85 rpm across the 570u. The layout was never wrong; the
 * corpus was. Cadence here is still the raw counter, because turning it into rpm needs two frames
 * and the receive clock, and inventing a delta from one frame is the mistake being described.
 */
internal object SchwinnX70Measurement {

    /** 270 / 570 main stream. Exact length; a 16- or 18-octet frame is not one of these. */
    const val MAIN_FRAME_LENGTH = 17

    /** 170 supplemental stream: heart rate and resistance (V16). */
    const val SUPPLEMENTAL_FRAME_LENGTH = 20

    /** 170 sibling stream: speed and power (V15a, published under D72). */
    const val SIBLING_FRAME_LENGTH = 14

    /** The invariant first three octets of a main frame, `0x11 0x20 0x00`. */
    val MAIN_PREFIX = intArrayOf(17, 32, 0)

    /**
     * V17's prediction, held as a constant rather than as behaviour.
     *
     * If byte 0 is the frame length throughout the family, a supplemental frame begins `0x14` and a
     * sibling frame `0x0E`. Nothing here relies on it. A test asserts the *main* frame satisfies it,
     * which is the only one with a vector; the other two are recorded for the day one arrives.
     */
    const val FRAME_LENGTH_IS_FIRST_OCTET = true

    // ── 270 / 570 main frame ─────────────────────────────────────────────────────

    private const val MAIN_OFFSET_CADENCE_COUNTER = 4
    private const val MAIN_OFFSET_TIME = 8
    private const val MAIN_OFFSET_CALORIES = 10
    private const val MAIN_OFFSET_RESISTANCE = 16

    /** The console's tick rate. Time is `uint16` at 1/1024 s, so it wraps every 64 seconds. */
    const val TICKS_PER_SECOND = 1024.0

    /** Wrap modulus for [Main.timeTicks]. */
    const val TIME_TICK_MODULUS = 65536

    /**
     * TMIW's calorie divisor. **Published as of D73 (2026-08-14), on captured evidence.**
     *
     * §7 previously refused to publish x70 calories: `2^21` had no recorded provenance, no second
     * project corroborated it, and that it reproduced TMIW's own test expectations was circular,
     * since those expectations came from the same code. The user's captures replace that with a
     * real measurement in two independent sessions:
     *
     * | Console | Duration | Energy | Rate |
     * |---|---|---|---|
     * | 270 | 24.2 s | 1.75 kcal | 260 kcal/h |
     * | 570u | 1069.2 s | 137.25 kcal | 462 kcal/h |
     *
     * The 570u figure sits against a window cadence of 70–85 rpm and resistance climbing 5 → 11 —
     * a vigorous seventeen-minute ride, and 462 kcal/h is what that costs. The accumulator is
     * monotonic across all 10,281 frames and its top two octets are always zero.
     *
     * **What this establishes, precisely.** The divisor is right to within roughly a factor of two.
     * Halving it would give 924 kcal/h, which no rider sustains for seventeen minutes; doubling it
     * would give 231 kcal/h, which is too little for that cadence and resistance. That is a real
     * bound and it is the honest one — it is *not* verification against a console readout, and a
     * subtler error, say 10 %, would look exactly like this.
     *
     * **This is why [MAX_PLAUSIBLE_KCAL_PER_HOUR] exists.** `MetricKind.ENERGY` is deliberately
     * unbounded, so nothing downstream would catch a wrong energy value — the V8 work found exactly
     * that hazard. The guard has to live at the decoder or nowhere.
     */
    const val TMIW_CALORIE_DIVISOR = 2097152

    /**
     * Rate ceiling for a published energy total, in kcal/h.
     *
     * Not a physiological limit — an elite athlete can exceed it briefly. It is set well above both
     * captured sessions and below the rate a materially wrong divisor would produce, so it catches
     * a scale error rather than a hard effort. A total whose rate exceeds this is not published;
     * absent energy is visibly absent, and a wrong cumulative figure is believed.
     */
    const val MAX_PLAUSIBLE_KCAL_PER_HOUR = 1500.0

    /**
     * One decoded 270/570 frame.
     *
     * @param cadenceEventCounter bytes 4–5 verbatim. A revolution counter, not a rate — deriving
     * rpm needs two frames and the receive clock, and doing it here would invent a delta from one.
     * @param timeTicks the console's own clock at 1/1024 s, wrapping at [TIME_TICK_MODULUS].
     * Protocol time; §8.5 makes it non-authoritative for a FitBridge session.
     * @param caloriesRaw bytes 10–15 verbatim, undivided and unpublished.
     * @param resistance byte 16. The minimum legal value is 1.
     */
    data class Main(
        val cadenceEventCounter: Int,
        val timeTicks: Int,
        val caloriesRaw: Long,
        val resistance: Int,
    ) {
        /** Elapsed seconds on the console's clock, for diagnostics and corpus parity only. */
        val elapsedSeconds: Int get() = (timeTicks / TICKS_PER_SECOND).toInt()

        /** Elapsed milliseconds on the console's clock, likewise. */
        val elapsedMillis: Int get() = (timeTicks / TICKS_PER_SECOND * 1000).toInt()

        /** Cumulative energy in kcal (D73). Publishable only through [publishableKcal]. */
        val caloriesKcal: Double get() = caloriesRaw.toDouble() / TMIW_CALORIE_DIVISOR
    }

    /**
     * Energy accumulated between two readings, or null when the rate says the scale cannot be
     * trusted.
     *
     * **This takes a delta, and the first version of it did not — that was a defect.** Checking a
     * cumulative total against an observation window assumes FitBridge saw the whole ride. Connect
     * to a console seventy kcal into a session and the total-over-window rate is enormous through
     * no fault of the divisor, so the guard would suppress a correct value on every mid-session
     * join. A delta over the same window is immune to that and tests exactly the thing in doubt:
     * whether `2^21` converts this console's units into kcal at the right scale.
     *
     * Publishing the delta rather than the console's running total is also the right session
     * semantic. The machine's accumulator may predate the FitBridge session by an unknown amount,
     * and §8.5 already refuses to treat machine-reported elapsed time as authoritative for the same
     * reason.
     *
     * @param elapsedSeconds measured on FitBridge's clock, never the console's. A machine
     * misreporting its own duration would otherwise mask the error this exists to catch.
     */
    fun publishableKcal(first: Main, last: Main, elapsedSeconds: Double): Double? {
        val delta = last.caloriesKcal - first.caloriesKcal
        // A cumulative counter that went backwards is a wrapped or misread field, not a workout.
        if (delta < 0.0) return null
        if (elapsedSeconds < MIN_RATE_CHECK_SECONDS) return delta
        val ratePerHour = delta / elapsedSeconds * 3600.0
        return if (ratePerHour <= MAX_PLAUSIBLE_KCAL_PER_HOUR) delta else null
    }

    /** Below this, the average rate is too noisy to test a divisor against. */
    const val MIN_RATE_CHECK_SECONDS = 20.0

    fun isMainFrame(data: ByteArray): Boolean {
        if (data.size != MAIN_FRAME_LENGTH) return false
        for (index in MAIN_PREFIX.indices) {
            if (u8(data, index) != MAIN_PREFIX[index]) return false
        }
        return true
    }

    fun parseMain(data: ByteArray): Main? {
        if (!isMainFrame(data)) return null
        return Main(
            cadenceEventCounter = u16(data, MAIN_OFFSET_CADENCE_COUNTER),
            timeTicks = u16(data, MAIN_OFFSET_TIME),
            caloriesRaw = u48(data, MAIN_OFFSET_CALORIES),
            resistance = u8(data, MAIN_OFFSET_RESISTANCE),
        )
    }

    // ── 170 supplemental frame (V16) ─────────────────────────────────────────────

    private const val SUPPLEMENTAL_OFFSET_GUARD = 15
    private const val SUPPLEMENTAL_OFFSET_HEART_RATE = 16
    private const val SUPPLEMENTAL_OFFSET_RESISTANCE = 18

    /**
     * TMIW's guard at byte 15, invariant across all 698 captured frames.
     *
     * **It is a field tag, not a magic number**, and TMIW is checking it without knowing that. The
     * supplemental frame carries the same regular tag structure as the sibling frame:
     * [SUPPLEMENTAL_TAGS] lists every invariant octet, and heart rate and resistance are the values
     * belonging to tags `0x5A` and `0x5C`.
     */
    const val SUPPLEMENTAL_GUARD = 0x5A

    /**
     * Every invariant octet of a supplemental frame, at its position.
     *
     * Same reasoning as [SIBLING_TAGS]: verified, not walked. Checking all seven instead of TMIW's
     * one costs nothing and is the only structural evidence these frames offer.
     */
    val SUPPLEMENTAL_TAGS =
        mapOf(5 to 0x1F, 8 to 0x19, 10 to 0x39, 12 to 0x3C, 15 to 0x5A, 17 to 0x5C, 19 to 0x5F)

    /**
     * One decoded 170 supplemental frame.
     *
     * **V16 was registered as a conflict and is not one — the capture confirms both readings.**
     * TMIW reads heart rate at 16, QZ reads resistance at 18, the offsets do not overlap, and each
     * is the value of its own tag. Across 698 frames resistance took the values 2 and 4, which is
     * plausible, and heart rate stayed 0 throughout because no strap was paired. So TMIW's offset
     * is *consistent with* the capture rather than positively confirmed by it — a session with a
     * strap is still owed.
     */
    data class Supplemental(val heartRateBpm: Int, val resistance: Int)

    fun isSupplementalFrame(data: ByteArray): Boolean =
        data.size == SUPPLEMENTAL_FRAME_LENGTH &&
            SUPPLEMENTAL_TAGS.all { (offset, tag) -> u8(data, offset) == tag }

    fun parseSupplemental(data: ByteArray): Supplemental? {
        if (!isSupplementalFrame(data)) return null
        return Supplemental(
            heartRateBpm = u8(data, SUPPLEMENTAL_OFFSET_HEART_RATE),
            resistance = u8(data, SUPPLEMENTAL_OFFSET_RESISTANCE),
        )
    }

    // ── 170 sibling frame (V15a — published under D72, unconfirmed) ──────────────

    /**
     * **V15a resolved 2026-08-14 in QZ's favour, on 698 captured frames. QZ is right.**
     *
     * The user supplied the 170 HCI snoop, and it settles what no source project could. Four
     * independent checks agree:
     *
     * 1. **The two fields are zero together in exactly 100 frames and never one without the
     *    other** — not once in 698. They are the same physical measurement.
     * 2. **Their ranges are physical**: 0.84–15.30 km/h and 1.11–77.69 W across the session, at a
     *    resistance the paired stream reports as 2–4. The 655 W ceiling I flagged as the thing to
     *    watch is never approached, so the ÷100 scale is not in question after all.
     * 3. **`power ≈ speed^1.565`, standard deviation 0.042** over 598 moving samples. A flywheel
     *    trainer at fixed resistance sits between the rolling exponent 1 and the aerodynamic 3, and
     *    a fit that tight across a whole session is not something two arbitrary octet pairs
     *    produce.
     * 4. Both fall to zero when pedalling stops while the accumulator fields hold their values.
     *
     * The tightness of (3) also says the console derives power from speed rather than measuring it
     * — but that derivation is the machine's own and is what its display shows, which is exactly
     * the number to publish. It is a native reading, not a FitBridge estimate, so §7's `ESTIMATED`
     * provenance rule does not apply to it.
     */
    private const val SIBLING_OFFSET_SPEED = 3
    private const val SIBLING_OFFSET_POWER = 6
    private const val SIBLING_OFFSET_ACCUMULATOR_A = 9
    private const val SIBLING_OFFSET_ACCUMULATOR_B = 12
    const val SIBLING_SPEED_DIVISOR = 100.0
    const val SIBLING_POWER_DIVISOR = 100.0

    /**
     * The invariant octets of a sibling frame, at their positions.
     *
     * Every one of the 698 captured frames carries `0x61`, `0x60`, `0x3D`, `0x3E` at offsets 2, 5,
     * 8 and 11, each immediately before a little-endian `uint16`. **These are field tags, and that
     * matters more than it looks.** A regular tag-then-value structure means the fixed offsets both
     * projects use are a property of *this* firmware's tag set, not of the protocol: a console
     * emitting one extra field would shift everything after it and every offset would silently
     * read the wrong quantity.
     *
     * FitBridge still reads by offset — deriving a general tag parser from one firmware would be
     * inventing a specification — but it **verifies the tags first**, so a frame with a different
     * field set is rejected rather than misread. That is the whole difference between this and
     * QZ's length-only dispatch.
     */
    val SIBLING_TAGS = mapOf(2 to 0x61, 5 to 0x60, 8 to 0x3D, 11 to 0x3E)

    /**
     * One decoded 170 sibling frame.
     *
     * @param sequence byte 0, a wrapping frame counter. **Not a length** — V17's length-prefix rule
     * holds on the main characteristic and does not hold here, which is why it is checked per
     * stream rather than assumed family-wide.
     * @param accumulatorA tag `0x3D`, monotonic while pedalling.
     * @param accumulatorB tag `0x3E`, likewise, correlating with the paired frame's own accumulator
     * at r = 0.9995. Decoded and published as no metric: two co-varying counters with no ground
     * truth are not a distance and not a calorie count, and §7 already refuses to guess one.
     */
    data class Sibling(
        val sequence: Int,
        val speedKph: Double,
        val powerWatts: Double,
        val accumulatorA: Int,
        val accumulatorB: Int,
    )

    fun isSiblingFrame(data: ByteArray): Boolean =
        data.size == SIBLING_FRAME_LENGTH && SIBLING_TAGS.all { (offset, tag) -> u8(data, offset) == tag }

    fun parseSibling(data: ByteArray): Sibling? {
        if (!isSiblingFrame(data)) return null
        return Sibling(
            sequence = u8(data, 0),
            speedKph = u16(data, SIBLING_OFFSET_SPEED) / SIBLING_SPEED_DIVISOR,
            powerWatts = u16(data, SIBLING_OFFSET_POWER) / SIBLING_POWER_DIVISOR,
            accumulatorA = u16(data, SIBLING_OFFSET_ACCUMULATOR_A),
            accumulatorB = u16(data, SIBLING_OFFSET_ACCUMULATOR_B),
        )
    }

    // ── Framing (V17) ────────────────────────────────────────────────────────────

    /**
     * Whether [command] satisfies both structural rules of the initialization framing.
     *
     * Byte 0 equals the total length, and every octet sums to zero mod 256. Both known commands
     * pass. **This validates; it never generates.** A synthesised command would be a fourth value
     * for a field where FitBridge already has two and no way to choose, which is the mistake V13
     * records in the Life Fitness token.
     */
    fun isWellFormedCommand(command: ByteArray): Boolean {
        if (command.isEmpty()) return false
        if (u8(command, 0) != command.size) return false
        return command.sumOf { it.toInt() and 0xFF } % 256 == 0
    }

    private fun u8(data: ByteArray, offset: Int): Int = data[offset].toInt() and 0xFF

    private fun u16(data: ByteArray, offset: Int): Int =
        u8(data, offset) or (u8(data, offset + 1) shl 8)

    private fun u48(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 6) {
            value = value or (u8(data, offset + index).toLong() shl (8 * index))
        }
        return value
    }
}
