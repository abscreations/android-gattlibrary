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

    const val OPCODE_UNLOCK = 0x20
    const val OPCODE_RESISTANCE = 0x40
    const val OPCODE_STANDARD_LEVEL = 0x41
    const val OPCODE_ERG = 0x42
    const val OPCODE_SIMULATION = 0x43
    const val OPCODE_ROLLING_RESISTANCE = 0x44
    const val OPCODE_WIND_RESISTANCE = 0x45
    const val OPCODE_GRADE = 0x46
    const val OPCODE_WIND_SPEED = 0x47
    const val OPCODE_WHEEL_CIRCUMFERENCE = 0x48

    const val UNLOCK_OVERALL_TIMEOUT_MS = 10_000L
    const val UNLOCK_MAX_ATTEMPTS = 3
    const val UNLOCK_MIN_ATTEMPT_INTERVAL_MS = 1_000L
    const val ATT_WRITE_TIMEOUT_MS = 3_000L
    const val INDICATION_TIMEOUT_MS = 800L
    const val ERG_MIN_INTERVAL_MS = 2_000L

    const val MIN_STANDARD_LEVEL = 1
    const val MAX_STANDARD_LEVEL = 9
    const val MIN_ERG_WATTS = 0
    const val MAX_ERG_WATTS = 2_000
    const val MAX_TOTAL_MASS_KG = 327.67
    const val MAX_CRR = 0.0255
    const val MAX_CW = 2.55
    const val MAX_WHEEL_CIRCUMFERENCE_MM = 6_553.5

    val MARKER_SERVICES = setOf(MARKER_EE01, MARKER_EE0B)
    val RIDEON_SERVICES = setOf(RIDEON_SERVICE, RIDEON_CORE2_SERVICE)
}
