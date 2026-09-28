package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.core.FitBridgeBleInternalApi

/**
 * Bounds-checked decoders for the Schwinn x70 family — 170, 270 and 570 (plan §7).
 *
 * ## One protocol, three streams, and every console emits all of them
 *
 * **Corrected 2026-08-18.** This file previously recorded, as the phase's central fact, that a 170
 * emits the 20/14-byte pair and *no 17-byte stream at all* while a 270/570 emits only the 17-byte
 * stream. Both halves are false. QZ debug logs — which, unlike an nRF Connect log or an HCI snoop
 * without a handle map, record each notification against its characteristic — show a 170 emitting
 * **1,938 seventeen-byte frames**, all passing [isMainFrame], interleaved with the pair for four
 * minutes on a link that connected once. The same logs show a 270 emitting the pair.
 *
 * The original conclusion was a **collection artefact read as a device fact**: the 170 HCI trace did
 * not carry `5c7d82a0` traffic, and absence in one capture became absence in the device.
 *
 * What is true is that the main stream is **intermittent**. Across five captured sessions it
 * appears 0, 215, 1,244, 1,938 and 2,553 times, and when present it ticks at the same ~90–110 ms
 * cadence as the pair. What starts and stops it is not established, and console model predicts
 * neither its presence nor its rate.
 *
 * The decoders below needed no change for any of this — only the model around them did. The three
 * layouts stay separate because they *are* separate layouts, not because they belong to separate
 * machines.
 *
 * See `docs/evidence/phase-7/2026-08-18-schwinn-x70-qz-debug-log-corpus.md`.
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
@FitBridgeBleInternalApi
object SchwinnX70Measurement {

    /** 270 / 570 main stream. Exact length; a 16- or 18-octet frame is not one of these. */
    const val MAIN_FRAME_LENGTH = 17

    /** 170 supplemental stream: heart rate and resistance (V16). */
    const val SUPPLEMENTAL_FRAME_LENGTH = 20

    /** 170 sibling stream: speed and power (V15a, published under D72). */
    const val SIBLING_FRAME_LENGTH = 14

    /** The invariant first three octets of a main frame, `0x11 0x20 0x00`. */
    val MAIN_PREFIX = intArrayOf(17, 32, 0)

    /**
     * The console's stream marker: a 5-octet frame on the measurement characteristic.
     *
     * **Registered 2026-08-18 to stop counting a legitimate frame as corruption.** `05 09 01 01 00`
     * and `05 09 01 03 00` arrive on [BluetoothIds.UUID_X70_MEASUREMENT], self-describe their
     * length in byte 0, and appear in four of five captured sessions — as the **first**
     * main-characteristic frame of the link in both single-connection ones. [parseMain] requires
     * exactly 17 octets, so before this existed every one of them incremented the handler's
     * rejected-frame counter. That counter feeds the §11 evidence record and diagnostic capture,
     * so it was wrong on every x70 session that produced a main stream.
     *
     * **This recognises the frame; it does not decode it.**
     *
     * **Widened 2026-08-19, because the first version repeated the defect it was written to fix.**
     * It required the first three octets to be `05 09 01`, generalised from two samples. A 570u
     * corpus then produced `05 09 02 01 00` — byte 2 is `0x02` — which the check rejected and the
     * handler counted as corruption, which is precisely what `isStreamMarker` exists to stop.
     *
     * Five variants are now observed across three consoles: `05 09 01 00 00`, `05 09 01 01 00`,
     * `05 09 01 03 00` and `05 09 02 01 00`. Bytes 2 and 3 both vary and neither is understood.
     * What holds everywhere is the length, the `05 09` opening, and a zero last octet, so that is
     * what is checked — enough to reject a foreign 5-octet frame, and no more than the evidence
     * supports.
     */
    const val STREAM_MARKER_LENGTH = 5

    /** The two leading octets that hold across every observed variant. */
    val STREAM_MARKER_PREFIX = intArrayOf(0x05, 0x09)

    /** Byte 4 is zero in every observed marker; bytes 2 and 3 vary and mean nothing known. */
    const val STREAM_MARKER_TRAILER = 0x00

    /**
     * Whether [data] is the measurement characteristic's stream marker rather than a measurement.
     *
     * Callers must only ask this of a frame that arrived on [BluetoothIds.UUID_X70_MEASUREMENT].
     * Length alone is not identity anywhere in this family, and QZ's length-only dispatch is the
     * defect §7 exists to avoid repeating.
     */
    fun isStreamMarker(data: ByteArray): Boolean {
        if (data.size != STREAM_MARKER_LENGTH) return false
        for (index in STREAM_MARKER_PREFIX.indices) {
            if (u8(data, index) != STREAM_MARKER_PREFIX[index]) return false
        }
        return u8(data, STREAM_MARKER_LENGTH - 1) == STREAM_MARKER_TRAILER
    }

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
        // A counter that went backwards is not a workout. **This is observed behaviour, not a
        // hypothetical**: in the captured 170 session the first main frame of the link carries a
        // total higher than the second, so the console reports a stale value or resets as the
        // workout begins. Returning null here is correct but not sufficient on its own — a caller
        // that keeps the stale frame as its baseline under-reports for the rest of the link, which
        // is why SchwinnX70ProtocolHandler re-baselines instead of only suppressing.
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
     *
     * **These tags do not all precede a `uint16`, unlike the sibling frame's.** Derived from the
     * tag offsets themselves and corrected 2026-08-18: `0x1F` at 5 owns a u16 at 6–7; `0x19` at 8
     * and `0x39` at 10 own single octets at 9 and 11; `0x3C` at 12 owns a u16 at 13–14; `0x5A` at
     * 15 and `0x5C` at 17 own single octets at 16 and 18 — heart rate and resistance; and `0x5F` at
     * 19 is the frame's last octet and **owns no value at all**, most economically read as a
     * terminator. The decoder reads fixed offsets and is right about all of them; this matters to
     * anything that generates a frame rather than parsing one.
     */
    val SUPPLEMENTAL_TAGS =
        mapOf(5 to 0x1F, 8 to 0x19, 10 to 0x39, 12 to 0x3C, 15 to 0x5A, 17 to 0x5C, 19 to 0x5F)

    /**
     * One decoded 170 supplemental frame.
     *
     * **V16 was registered as a conflict and is not one — the capture confirms both readings.**
     * TMIW reads heart rate at 16, QZ reads resistance at 18, the offsets do not overlap, and each
     * is the value of its own tag. Across 698 frames resistance took the values 2 and 4, which is
     * plausible, and heart rate stayed 0 throughout because no strap was paired.
     *
     * **The strap session arrived, and byte 16 is confirmed (2026-08-18).** A QZ debug log from a
     * physical 270 carries 402 consecutive supplemental frames reading 120–123 bpm, with zero on
     * both sides of the block — a strap connecting, holding a steady effort, and dropping. No
     * accumulator, counter or resistance level behaves like that. The offset is established by
     * capture rather than merely consistent with one, and this file no longer owes that session.
     *
     * Resistance at 18 is confirmed independently and more strongly: it equals main-frame byte 16
     * on the same console, exactly on 2,543 matched frames of the 270 and within ±1 on the 170,
     * every exception an adjacent value caught while the setting was changing. Two characteristics
     * carrying one field is also the evidence that the streams come from one machine.
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
     * 3. **`power ≈ speed^2.2` — the log-log regression slope, which is **unit-invariant** and so survives the 2026-08-19 mph correction. 2.215 pooled over 911 moving samples on two consoles, 2.581 with a residual sd of 0.0004 on the 270's 423. The figure previously quoted here, `speed^1.565` with sd 0.042, was the mean of `ln(power)/ln(speed)` over a narrow speed band — a ratio, not an exponent, and it moves with the unit (it reads ~1.35 once the field is decoded as mph)**. A flywheel
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

    /**
     * The sibling frame's speed field is **miles per hour**, and FitBridge converts on decode.
     *
     * Explore the World — Nautilus's own app — reads this field and displays it unconverted with an
     * mph label, regardless of the phone's locale or unit settings. Confirmed three ways against
     * the simulator: with one engine speed the three former personas emitted 13.43, 16.28 and
     * 20.38 and ETW showed 13.4, 16.4 and 20.3. A fourth, weaker line agrees — ETW derives cadence
     * from this field at a development of 5.51 m per crank revolution if it is mph, against 3.42
     * if km/h, and road-typical is 5 to 8.
     *
     * **This is a decision on a closed-source app's behaviour, not a proof, and Q12 stays open.**
     * The corpus cannot arbitrate: accumulator `0x3E` was calibrated by integrating this same
     * field, so it is 100 units per km or per mile symmetrically. A third reading would be worse
     * than either — Schwinn consoles have a units toggle and the field may simply follow it, in
     * which case no client can know the unit from the protocol and ETW is right to pass it through.
     * What would settle it: one hardware ride with a known console unit setting, ideally a pair
     * with the toggle flipped between them.
     *
     * Reverting is one edit here. Everything downstream reads [Sibling.speedKph].
     */
    const val KM_PER_MILE = 1.609344
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
        val speedMph: Double,
        val powerWatts: Double,
        val accumulatorA: Int,
        val accumulatorB: Int,
    ) {
        /**
         * The wire field converted to FitBridge's internal km/h convention.
         *
         * Every consumer reads this rather than [speedMph]; see [KM_PER_MILE] for the evidence and
         * for what would falsify it.
         */
        val speedKph: Double get() = speedMph * KM_PER_MILE
    }

    fun isSiblingFrame(data: ByteArray): Boolean =
        data.size == SIBLING_FRAME_LENGTH && SIBLING_TAGS.all { (offset, tag) -> u8(data, offset) == tag }

    fun parseSibling(data: ByteArray): Sibling? {
        if (!isSiblingFrame(data)) return null
        return Sibling(
            sequence = u8(data, 0),
            speedMph = u16(data, SIBLING_OFFSET_SPEED) / SIBLING_SPEED_DIVISOR,
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
