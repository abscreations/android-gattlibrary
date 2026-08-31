package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

/**
 * Canonical "Control mode" display strings for the simulator debug UI.
 *
 * One shared vocabulary across every simulator protocol (FTMS, FE-C-over-BLE, legacy Wahoo
 * KICKR, Zwift RideOn) so the Generated Metrics panel reads consistently regardless of which
 * persona is running, and so each protocol layer's mode-computation logic and its unit tests
 * reference the same literal strings instead of re-deriving them independently.
 *
 * "Automatic" always means no control write has been received yet for the active session -
 * the machine is running its own autonomous synthetic workout. Every other label names the
 * protocol that last wrote a target, followed by which kind of target it was: a direct
 * speed/resistance/incline/gear value, an ERG power target, or a full simulation
 * (wind/grade/rolling-resistance/wind-resistance) parameter set.
 */
object SimulatorControlModeLabels {
    const val AUTOMATIC = "Automatic"

    const val FTMS_DIRECT = "FTMS - Direct"

    /**
     * Set Target Resistance Level (0x04) specifically, split out of [FTMS_DIRECT] so the panel
     * names the procedure the client actually ran. [FTMS_DIRECT] now covers the remaining direct
     * targets - speed, inclination, and a bare gear shift on the protocols that carry one.
     */
    const val FTMS_RESISTANCE = "FTMS - Resistance"
    const val FTMS_ERG = "FTMS - Erg"
    const val FTMS_SIM = "FTMS - Sim"

    const val ANT_DIRECT = "ANT+ Direct"
    const val ANT_SIM = "ANT+ Sim"

    const val KICKR_DIRECT = "KICKR-Direct"
    const val KICKR_SIM = "KICKR-Sim"

    const val RIDEON_DIRECT = "RideOn-Direct"
    const val RIDEON_SIM = "RideOn-Sim"
}
