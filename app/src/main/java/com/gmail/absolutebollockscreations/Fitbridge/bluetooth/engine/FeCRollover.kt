package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

/**
 * Turns a small unsigned counter that wraps at [modulus] into a monotonically increasing
 * total, by tracking the delta between consecutive raw readings rather than the raw value
 * itself.
 *
 * FE-C over BLE reuses several ANT+ FE-C byte- and uint16-width counters (page 16 elapsed
 * time and distance, page 25 event count and accumulated power) that roll over far sooner
 * than a real session runs. Per `docs/fe-c-ble_central_implementation.md` §2.4/§8, the
 * required moduli are: elapsed time = 256 (quarter-second byte, ~64s period), distance =
 * 256 (meter byte), event count = 256 (byte), accumulated power = 65536 (uint16 watts).
 *
 * One instance is owned per counter per connection generation - `FeCProtocolSession` creates
 * fresh accumulators on construction, so a reconnect never inherits a stale baseline from a
 * previous link (plan §8: "new connection generation starts fresh").
 */
internal class UnsignedModuloAccumulator(private val modulus: Long) {
    init {
        require(modulus > 0) { "modulus must be positive, was $modulus" }
    }

    private var lastRaw: Long? = null
    private var accumulatedTotal: Long = 0L

    /** Running total accumulated so far, in raw counter units (not yet unit-scaled). */
    val total: Long get() = accumulatedTotal

    /** True once at least one raw reading has been accumulated. */
    val hasSample: Boolean get() = lastRaw != null

    /**
     * Feeds the next raw reading (expected in `0 until modulus`) and returns the updated
     * running total. The first call seeds the baseline and returns [raw] itself, since there
     * is no prior reading to compute a delta against. A repeated identical [raw] (e.g. a
     * duplicate notification carrying unchanged FE-C data) contributes a delta of zero, so
     * calling this more than once per physical counter tick does not double-count.
     */
    fun accumulate(raw: Long): Long {
        require(raw in 0 until modulus) { "raw=$raw out of range for modulus=$modulus" }
        val previous = lastRaw
        lastRaw = raw
        if (previous == null) {
            accumulatedTotal = raw
            return accumulatedTotal
        }
        val delta = if (raw >= previous) raw - previous else (modulus - previous) + raw
        accumulatedTotal += delta
        return accumulatedTotal
    }

    /** Discards accumulated state. Used only when a session must explicitly restart counting. */
    fun reset() {
        lastRaw = null
        accumulatedTotal = 0L
    }
}
