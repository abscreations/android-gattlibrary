package com.gmail.absolutebollockscreations.gattlibrary.simulator

/**
 * The legacy KICKR control commands the simulator's decoder produces. Simulator-owned (LIB-19j):
 * the decoder is an independent reverse of the wire format, so its result type must not be central's
 * `WahooKickrCommand`. Tests map between the two to prove they agree.
 */
internal sealed interface KickrLegacyCommand {
    data object Unlock : KickrLegacyCommand
    data class Resistance(val fraction: Double) : KickrLegacyCommand
    data class StandardLevel(val level: Int) : KickrLegacyCommand
    data class Erg(val watts: Int) : KickrLegacyCommand
    data class Simulation(
        val totalMassKg: Double,
        val crr: Double,
        val cw: Double,
    ) : KickrLegacyCommand
    data class RollingResistance(val crr: Double) : KickrLegacyCommand
    data class WindResistance(val cw: Double) : KickrLegacyCommand
    data class Grade(val percent: Double) : KickrLegacyCommand
    data class WindSpeed(val metresPerSecond: Double) : KickrLegacyCommand
    data class WheelCircumference(val millimetres: Double) : KickrLegacyCommand
}
