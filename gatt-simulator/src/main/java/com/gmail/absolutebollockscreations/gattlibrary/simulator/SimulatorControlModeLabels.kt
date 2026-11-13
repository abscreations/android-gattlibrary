package com.gmail.absolutebollockscreations.gattlibrary.simulator

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
public object SimulatorControlModeLabels {
    /**
     * Protocol-independent control-kind words.
     *
     * The runtime labels below are "<protocol> <kind>" pairs, and the persona selector's
     * capability descriptors ([SimulatorPersonaDescriptor]) name the same kinds without a
     * protocol prefix, because the protocol already occupies its own slot there. Both read the
     * kind from here, so a persona described as accepting "Erg" and a status line reading
     * "FTMS - Erg" cannot drift apart.
     */
    internal object Kind {
        const val DIRECT = "Direct"
        const val ERG = "Erg"
        const val SIM = "Sim"

        /** What a persona that accepts no control writes reports. Lower-case: it is not a mode. */
        const val NONE = "none"
    }

    public const val AUTOMATIC: String = "Automatic"

    @SimulatorProbeApi
    public const val FTMS_DIRECT: String = "FTMS - ${Kind.DIRECT}"
    @SimulatorProbeApi
    public const val FTMS_ERG: String = "FTMS - ${Kind.ERG}"
    public const val FTMS_SIM: String = "FTMS - ${Kind.SIM}"

    public const val ANT_DIRECT: String = "ANT+ ${Kind.DIRECT}"
    public const val ANT_SIM: String = "ANT+ ${Kind.SIM}"

    internal const val KICKR_DIRECT = "KICKR-${Kind.DIRECT}"
    internal const val KICKR_SIM = "KICKR-${Kind.SIM}"

    internal const val RIDEON_DIRECT = "RideOn-${Kind.DIRECT}"
    internal const val RIDEON_SIM = "RideOn-${Kind.SIM}"
}
