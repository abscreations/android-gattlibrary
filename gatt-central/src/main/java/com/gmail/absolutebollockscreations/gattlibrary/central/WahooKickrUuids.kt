package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.WahooUuids

/**
 * Phase-10 legacy KICKR wire vocabulary.
 *
 * UUIDs are aliases of the shared registry so scan, discovery, and routing cannot drift. Opcode,
 * range, and timeout constants live here beside the later pure codec/session implementation.
 * Merely recording RideOn UUIDs does not activate that separate protocol.
 */
@GattLibraryInternalApi
object WahooKickrUuids {
    val CYCLING_POWER_SERVICE = SigUuids.UUID_CYCLING_POWER_SERVICE
    val MARKER_EE01 = WahooUuids.UUID_WAHOO_KICKR_MARKER_EE01
    val MARKER_EE0B = WahooUuids.UUID_WAHOO_KICKR_MARKER_EE0B
    val CONTROL = WahooUuids.UUID_WAHOO_KICKR_CONTROL

    val RIDEON_SERVICE = WahooUuids.UUID_WAHOO_RIDEON_SERVICE
    val RIDEON_CORE2_SERVICE = WahooUuids.UUID_WAHOO_RIDEON_CORE2_SERVICE
    val RIDEON_ASYNC_TX = WahooUuids.UUID_WAHOO_RIDEON_ASYNC_TX
    val RIDEON_SYNC_RX = WahooUuids.UUID_WAHOO_RIDEON_SYNC_RX
    val RIDEON_SYNC_TX = WahooUuids.UUID_WAHOO_RIDEON_SYNC_TX

    const val UNLOCK_OVERALL_TIMEOUT_MS = 10_000L
    const val UNLOCK_MAX_ATTEMPTS = 3
    const val UNLOCK_MIN_ATTEMPT_INTERVAL_MS = 1_000L
    const val ATT_WRITE_TIMEOUT_MS = 3_000L
    const val INDICATION_TIMEOUT_MS = 800L
    const val ERG_MIN_INTERVAL_MS = 2_000L

    val MARKER_SERVICES = setOf(MARKER_EE01, MARKER_EE0B)
    val RIDEON_SERVICES = setOf(RIDEON_SERVICE, RIDEON_CORE2_SERVICE)
}
