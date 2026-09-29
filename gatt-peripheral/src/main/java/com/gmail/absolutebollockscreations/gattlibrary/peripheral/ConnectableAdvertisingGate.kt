package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.core.FitBridgeBleInternalApi

/**
 * Pure connection-count gate shared by connectable simulator peripherals.
 *
 * Android may re-enable a legacy advertising set as a connection forms. The owning peripheral
 * must explicitly pause its advertiser for the lifetime of any inbound link, then resume only
 * after the final link leaves. Duplicate callbacks are deliberately idempotent.
 */
@FitBridgeBleInternalApi
class ConnectableAdvertisingGate {
    enum class Action { NONE, PAUSE, RESUME }

    private val connected = mutableSetOf<String>()

    @Synchronized
    fun onConnected(connectionKey: String): Action {
        if (!connected.add(connectionKey)) return Action.NONE
        return if (connected.size == 1) Action.PAUSE else Action.NONE
    }

    @Synchronized
    fun onDisconnected(connectionKey: String): Action {
        if (!connected.remove(connectionKey)) return Action.NONE
        return if (connected.isEmpty()) Action.RESUME else Action.NONE
    }

    @Synchronized
    fun reset() = connected.clear()
}
