package com.gmail.absolutebollockscreations.gattlibrary.server

import kotlinx.coroutines.flow.StateFlow

/**
 * What a synthetic-data generator (the FTMS simulator) needs from its host, and where it puts its
 * samples. Defined here so the simulator depends on this contract and never on central (L22).
 *
 * Read side: whether a real machine is attached or connecting (the simulator must not fight it), and
 * the live external heart-rate strap, which overrides the generated value. Write side: begin and end
 * a simulated session and publish one coherent sample - a metric a persona does not produce stays
 * untouched. [InMemoryUpstream] implements the write side over process memory with no central
 * behind it; the FitBridge debug app pairs it with the real machine and strap state read from the
 * public client (`ClientSimulatedUpstreamHost`, LIB-68). Simulated samples never enter central.
 */
public interface SimulatedUpstreamHost {
    /** True when a real machine link exists; the simulator refuses to start beside it. */
    public val machineLinkAttached: Boolean

    /** `BluetoothProfile.STATE_*` of the machine link, so the simulator can stop when a bridge starts. */
    public val machineConnectionState: StateFlow<Int>

    /** Latest external strap heart rate, or null. Never the value the simulator itself publishes. */
    public val externalHeartRate: StateFlow<Int?>

    /** Wall-clock ms at which [externalHeartRate] was last updated; zero means never. */
    public val lastExternalHeartRateAtMs: Long

    public fun beginSession(haveControl: Boolean)

    /** Leave the host state as an idle machine would, not mid-workout. */
    public fun endSession()

    public fun publish(sample: SimulatedUpstreamSample)
}

/** What the [SimulatedUpstreamSample.movementCount] field means for the simulated persona. */
public enum class MovementCountKind { STROKES, STEPS, STRIDES }

/** One coherent simulated sample in FitBridge engine units; null means "this persona has no such metric". */
public data class SimulatedUpstreamSample(
    val elapsedSeconds: Int,
    val heartRate: Int,
    val caloriesTotal: Int,
    val caloriesPerHour: Int,
    val speedKph: Double? = null,
    val averageSpeedKph: Double? = null,
    val distanceMeters: Int? = null,
    val cadence: Double? = null,
    val averageCadence: Double? = null,
    val resistance: Int? = null,
    val inclinationPercent: Double? = null,
    val powerWatts: Int? = null,
    val averagePowerWatts: Int? = null,
    val forceOnBeltNewtons: Int? = null,
    /** Minutes per km. */
    val instantPaceMinPerKm: Double? = null,
    val averagePaceMinPerKm: Double? = null,
    val movementCount: Int? = null,
    /** Routes [movementCount]; null (no engine) drops it. */
    val movementCountKind: MovementCountKind? = null,
    val positiveElevationMeters: Double? = null,
    val floors: Int? = null,
)
