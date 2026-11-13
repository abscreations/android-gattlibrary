package com.gmail.absolutebollockscreations.gattlibrary.server

/**
 * Host-app settings the generic FTMS peripheral consumes (LIB-6/S6).
 *
 * Replaces the controller's direct read of the app's preference file, which a library cannot
 * know about.
 *
 * @property treadmillSpeedControlsEnabled the D26 treadmill speed-control opt-in. When it returns
 * `false`, the treadmill persona publishes no target-speed feature bit or Supported Speed Range,
 * and target-speed writes are refused. It is evaluated at every capability decision, not captured
 * once, so turning the setting off takes effect immediately (the controller then rebuilds the
 * GATT surface via `refreshTreadmillSpeedControls`). **The default is off:** an app that supplies
 * nothing never exposes speed control on a machine a person stands on.
 *
 * The opt-in guards **bridged** sessions only: speed commands passed through to a real treadmill.
 * A local control override (the data simulator, which moves nothing) is exempt, so a simulated
 * treadmill with "Accept target commands" on always advertises and accepts target speed
 * (LIB-9d).
 */
public class PeripheralConfig(
    public val treadmillSpeedControlsEnabled: () -> Boolean = { false },
)
