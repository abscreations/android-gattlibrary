package com.gmail.absolutebollockscreations.gattlibrary.server

import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the once-per-physical-connection guard for an inbound GATT-server link hold.
 *
 * Android can otherwise arm its short link-idle timeout when an unrelated local GATT client
 * closes. Only the server connection lifecycle releases this guard; local central-side client
 * activity must not affect it.
 */
@GattLibraryInternalApi
public class PeripheralConnectionRetentionGuard {
    private val retainedConnections = ConcurrentHashMap.newKeySet<String>()

    /** Runs [retain] exactly once for a key until [release] or [clear] is called. */
    public fun retainOnce(key: String, retain: () -> Unit): Boolean {
        if (!retainedConnections.add(key)) return false
        retain()
        return true
    }

    public fun release(key: String) {
        retainedConnections.remove(key)
    }

    public fun clear() {
        retainedConnections.clear()
    }

    internal fun isClaimed(key: String): Boolean = key in retainedConnections
}
