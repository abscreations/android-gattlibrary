package com.gmail.absolutebollockscreations.gattlibrary.central

/**
 * Raw FTMS Treadmill Data pace octets, preserved for lossless downstream re-emission.
 *
 * ## Why raw octets rather than the decoded value
 *
 * Treadmill Instantaneous and Average Pace (`0x2ACD`, flag bits 5 and 6) are `uint8` in
 * kilometres per **minute** at 0.1 resolution. One raw step is therefore **6 km/h**: raw 1 is
 * 6 km/h, raw 2 is 12 km/h, and nothing between them is representable.
 *
 * That resolution makes the field useless to synthesise. Converting FitBridge's fused speed
 * into it would publish a value disagreeing with the Instantaneous Speed field in the same
 * packet — which carries 0.01 km/h resolution — by up to 25 % at ordinary running speeds. The
 * downstream honesty invariant rules that out: an absent metric is visibly absent, a wrong one
 * is quietly believed.
 *
 * Passing the upstream machine's own octet straight through is exact, because it is the same
 * quantisation going out as came in. So this store keeps the raw octet, never a derived one,
 * and the encoder emits the pace bits only when a real treadmill supplied them.
 *
 * ## Known limitation
 *
 * `snapshot` returns the newest link generation without checking that the same connection also
 * supplied the fused speed. With a foot pod owning speed beside a treadmill supplying pace, the
 * two downstream fields could come from different devices. This mirrors [CscNativeEventStore]
 * and [CpsNativeEventStore], which have the same shape; gating on the selected source needs a
 * fusion reference the metric pump does not currently hold. Recorded as Q27.
 */
data class TreadmillNativePaceSnapshot(
    /** Raw `uint8`, 0.1 km/min. Re-emitted verbatim. */
    val instantaneousRaw: Int? = null,
    val averageRaw: Int? = null,
)

internal class TreadmillNativePaceStore {
    private val entries = linkedMapOf<ConnectionId, TreadmillNativePaceSnapshot>()

    @Synchronized
    fun publish(connectionId: ConnectionId, instantaneousRaw: Int?, averageRaw: Int?) {
        if (instantaneousRaw == null && averageRaw == null) return
        val previous = entries[connectionId]
        entries[connectionId] = TreadmillNativePaceSnapshot(
            instantaneousRaw = instantaneousRaw ?: previous?.instantaneousRaw,
            averageRaw = averageRaw ?: previous?.averageRaw,
        )
    }

    @Synchronized
    fun remove(connectionId: ConnectionId) {
        entries.remove(connectionId)
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    @Synchronized
    fun snapshot(): TreadmillNativePaceSnapshot? =
        entries.entries.maxByOrNull { it.key.linkGeneration }?.value
}
