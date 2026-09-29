package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import android.bluetooth.BluetoothProfile

/**
 * Bridge mode and simulator mode are mutually exclusive (decision L17, LIB-9c).
 *
 * A bridge starts when the engine opens an upstream machine link. The simulator writes into the
 * same `BtEngineState` a real machine does, so it must be fully stopped the moment that link
 * starts connecting, and must refuse to start while one exists.
 *
 * Only the machine link counts. Heart-rate and other sensor links do not: the simulator uses a
 * live heart-rate strap on purpose.
 */
internal object SimulatorBridgeGuard {

    /** Shown when an upstream machine link stopped a running simulator. */
    const val STOPPED_FOR_BRIDGE =
        "Simulator stopped: a machine connected for bridging (bridge and simulator never run together)"

    /** Shown when a simulator start is refused because a machine link is active. */
    const val START_REFUSED = "Disconnect the physical machine before simulating data"

    /** True when the engine's machine link is connecting or connected. */
    fun upstreamLinkActive(machineConnectionState: Int): Boolean =
        machineConnectionState == BluetoothProfile.STATE_CONNECTING ||
            machineConnectionState == BluetoothProfile.STATE_CONNECTED
}
