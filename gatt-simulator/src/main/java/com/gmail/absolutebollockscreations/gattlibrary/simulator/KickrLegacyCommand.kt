package com.gmail.absolutebollockscreations.gattlibrary.simulator

/**
 * The legacy KICKR control commands the simulator's decoder produces. Simulator-owned (LIB-19j):
 * the decoder is an independent reverse of the wire format, so its result type must not be central's
 * `WahooKickrCommand`. Tests map between the two to prove they agree.
 */
@SimulatorProbeApi
public sealed interface KickrLegacyCommand {
    public data object Unlock : KickrLegacyCommand
    public data class Resistance(val fraction: Double) : KickrLegacyCommand
    public data class StandardLevel(val level: Int) : KickrLegacyCommand
    public data class Erg(val watts: Int) : KickrLegacyCommand
    public data class Simulation(
        val totalMassKg: Double,
        val crr: Double,
        val cw: Double,
    ) : KickrLegacyCommand
    public data class RollingResistance(val crr: Double) : KickrLegacyCommand
    public data class WindResistance(val cw: Double) : KickrLegacyCommand
    public data class Grade(val percent: Double) : KickrLegacyCommand
    public data class WindSpeed(val metresPerSecond: Double) : KickrLegacyCommand
    public data class WheelCircumference(val millimetres: Double) : KickrLegacyCommand
}
