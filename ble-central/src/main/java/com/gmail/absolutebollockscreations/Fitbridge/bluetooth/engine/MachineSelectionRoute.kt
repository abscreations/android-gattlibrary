package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

/**
 * Which selection path a machine row takes (plan §6.6, phase 11.5b).
 *
 * Until phase 11 there was one path, and `connectMachine` could simply be `connectFtms`. An
 * advertisement-only machine cannot take it: there is nothing to connect to, so calling
 * `connectFtms` on a Keiser would open a `ConnectionContext` for a device that will never answer,
 * stop the scan its telemetry depends on, and then sit in a connecting state until it timed out.
 *
 * The decision is a pure function of the catalog row so it can be asserted without a radio, and so
 * the branch lives in one place rather than being re-derived by each caller.
 */
internal enum class MachineSelectionRoute {
    /** Open a GATT link, as every machine family before phase 11 does. */
    GATT_CONNECT,

    /** Select an advertisement source. No link, no control, no disconnect affordance. */
    ADVERTISEMENT_SELECT,
}

/**
 * Route a machine selection by what the discovery catalog knows about the device.
 *
 * An address the catalog has never seen routes to [MachineSelectionRoute.GATT_CONNECT], which is
 * exactly what happened before phase 11 — `connectMachine` on an unknown address has always simply
 * tried to connect. Preserving that keeps this change to the advertisement case: an unknown device
 * is not evidence of an advertisement-only one, and guessing otherwise would silently break the
 * existing "connect to an address I already know" paths.
 */
internal fun machineSelectionRoute(
    device: BtEngineState.DiscoveredDevice?,
): MachineSelectionRoute = when (device?.transport) {
    BtEngineState.DeviceTransport.ADVERTISEMENT_ONLY -> MachineSelectionRoute.ADVERTISEMENT_SELECT
    else -> MachineSelectionRoute.GATT_CONNECT
}

/** Outcome of a machine selection, so a caller knows which affordances now apply. */
enum class MachineSelectionOutcome {
    /** A GATT connection was started. The CCCD/control watcher applies. */
    GATT_CONNECTING,

    /** An advertisement source was selected. There is no link and no control point. */
    ADVERTISEMENT_SELECTED,

    /** Nothing was selected. */
    FAILED,
}
