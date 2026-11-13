package com.gmail.absolutebollockscreations.gattlibrary.server

import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi

/**
 * Pure connection-count gate shared by connectable simulator peripherals.
 *
 * Android may re-enable a legacy advertising set as a connection forms. The owning peripheral
 * must explicitly pause its advertiser for the lifetime of any inbound link, then resume only
 * after the final link leaves. Duplicate callbacks are deliberately idempotent.
 */
@GattLibraryInternalApi
public class ConnectableAdvertisingGate {
    public enum class Action { NONE, PAUSE, RESUME }

    private val connected = mutableSetOf<String>()

    @Synchronized
    public fun onConnected(connectionKey: String): Action {
        if (!connected.add(connectionKey)) return Action.NONE
        return if (connected.size == 1) Action.PAUSE else Action.NONE
    }

    @Synchronized
    public fun onDisconnected(connectionKey: String): Action {
        if (!connected.remove(connectionKey)) return Action.NONE
        return if (connected.isEmpty()) Action.RESUME else Action.NONE
    }

    @Synchronized
    public fun reset(): Unit = connected.clear()
}
