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
    /**
     * Protocol-independent control-kind words.
     *
     * The runtime labels below are "<protocol> <kind>" pairs, and the persona selector's
     * capability descriptors ([SimulatorPersonaDescriptor]) name the same kinds without a
     * protocol prefix, because the protocol already occupies its own slot there. Both read the
     * kind from here, so a persona described as accepting "Erg" and a status line reading
     * "FTMS - Erg" cannot drift apart.
     */
    object Kind {
        const val DIRECT = "Direct"
        const val ERG = "Erg"
        const val SIM = "Sim"

        /** What a persona that accepts no control writes reports. Lower-case: it is not a mode. */
        const val NONE = "none"
    }

    const val AUTOMATIC = "Automatic"

    const val FTMS_DIRECT = "FTMS - ${Kind.DIRECT}"
    const val FTMS_ERG = "FTMS - ${Kind.ERG}"
    const val FTMS_SIM = "FTMS - ${Kind.SIM}"

    const val ANT_DIRECT = "ANT+ ${Kind.DIRECT}"
    const val ANT_SIM = "ANT+ ${Kind.SIM}"

    const val KICKR_DIRECT = "KICKR-${Kind.DIRECT}"
    const val KICKR_SIM = "KICKR-${Kind.SIM}"

    const val RIDEON_DIRECT = "RideOn-${Kind.DIRECT}"
    const val RIDEON_SIM = "RideOn-${Kind.SIM}"
}
