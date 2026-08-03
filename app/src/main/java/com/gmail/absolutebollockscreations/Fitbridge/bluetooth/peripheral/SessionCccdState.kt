package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import java.util.UUID

/**
 * Exact CCCD values for one GATT connection.
 *
 * Bond state is deliberately absent: the peripheral accepts bonded peers but
 * requires every peer to configure subscriptions again after reconnecting.
 * Copy-on-write publication also keeps notifier reads safe while callbacks
 * update the map.
 */
internal class SessionCccdState {
    @Volatile
    private var values: Map<UUID, PeripheralConnection.CccdMode> = emptyMap()

    fun write(characteristic: UUID, mode: PeripheralConnection.CccdMode) {
        values = if (mode == PeripheralConnection.CccdMode.DISABLED) {
            values - characteristic
        } else {
            values + (characteristic to mode)
        }
    }

    fun mode(characteristic: UUID): PeripheralConnection.CccdMode =
        values[characteristic] ?: PeripheralConnection.CccdMode.DISABLED

    fun clear() {
        values = emptyMap()
    }
}
