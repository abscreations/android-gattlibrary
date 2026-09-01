package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

/**
 * Who currently needs the platform BLE scan running (plan §5.3).
 *
 * Until phase 11 the scan had exactly one owner — the device picker — and "the picker is closed"
 * and "nobody needs the radio" were the same statement. An advertisement-only machine breaks that
 * equivalence: its telemetry *is* the scan, so a Keiser stays live for the whole session while the
 * picker opens and closes and its five-minute timeout comes and goes.
 */
internal enum class ScanLeaseOwner {
    /** The device picker. Bounded by the existing picker/scanner timeout. */
    DISCOVERY_UI,

    /** Held for as long as an advertisement-only machine is selected. */
    ADVERTISEMENT_MACHINE,
}

/**
 * Reference counting for the platform scan, by named owner rather than by count.
 *
 * ## Named owners, not a counter
 *
 * A bare integer would work until something released a lease it never took, or took one twice and
 * released it once — and the symptom of either is a radio left scanning for the rest of the
 * session, which costs the user battery and shows up nowhere. Owners are an enum and acquisition is
 * idempotent per owner, so both mistakes are unrepresentable.
 *
 * ## The transition, not the state, is the return value
 *
 * [acquire] answers "must the platform scan be started now?" and [release] answers "may it be
 * stopped now?". A caller that asks and then acts cannot leave the radio out of step with the
 * lease set, and a caller that ignores the answer cannot silently stop a scan another owner still
 * needs — which is the failure this class exists to prevent, since it is exactly what closing the
 * picker would otherwise do to a live Keiser.
 *
 * Pure and synchronous: it makes no platform call, so a test drives the whole ownership model
 * without a radio, and the one place that talks to Android stays the scan layer.
 */
internal class ScanLeaseRegistry {

    private val lock = Any()
    private val owners = linkedSetOf<ScanLeaseOwner>()

    /**
     * Take [owner]'s lease.
     *
     * @return true when this call took the scan from unheld to held, and the platform scan must
     * therefore be started. False when the scan is already running for someone — including for
     * this same owner, since acquiring twice is a no-op rather than an error.
     */
    fun acquire(owner: ScanLeaseOwner): Boolean = synchronized(lock) {
        val wasHeld = owners.isNotEmpty()
        owners += owner
        !wasHeld
    }

    /**
     * Give up [owner]'s lease.
     *
     * @return true when no owner remains and the platform scan may be stopped. False when another
     * owner still needs it, or when [owner] held no lease to release.
     */
    fun release(owner: ScanLeaseOwner): Boolean = synchronized(lock) {
        if (!owners.remove(owner)) return@synchronized false
        owners.isEmpty()
    }

    /** Drop every lease. Returns true when that stopped a running scan. */
    fun releaseAll(): Boolean = synchronized(lock) {
        val wasHeld = owners.isNotEmpty()
        owners.clear()
        wasHeld
    }

    fun isHeld(): Boolean = synchronized(lock) { owners.isNotEmpty() }

    fun holds(owner: ScanLeaseOwner): Boolean = synchronized(lock) { owner in owners }

    /** Current owners, for diagnostics. Ordered by acquisition. */
    fun owners(): Set<ScanLeaseOwner> = synchronized(lock) { owners.toSet() }
}
