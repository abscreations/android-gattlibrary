package com.gmail.absolutebollockscreations.gattlibrary.server

import kotlinx.coroutines.flow.StateFlow

/**
 * What a synthetic-data generator (the FTMS simulator) needs from the host's machine engine, and
 * where it puts its samples. Defined here so the simulator depends on this contract rather than on
 * central's `BtEngineState` (L22, LIB-19k); the host bridge (`EngineSimulatedUpstreamHost` in
 * the debug app, over central's debug-only `SyntheticUpstream`, LIB-27) implements it over the real engine state.
 *
 * Read side: whether a real machine is attached or connecting (the simulator must not fight it), and
 * the live external heart-rate strap, which overrides the generated value. Write side: begin and end
 * a simulated session and publish one coherent sample, mirroring what the FTMS parsers do for a real
 * machine - a metric a persona does not produce stays untouched.
 */
interface SimulatedUpstreamHost {
    /** True when a real machine link exists; the simulator refuses to start beside it. */
    val machineLinkAttached: Boolean

    /** `BluetoothProfile.STATE_*` of the machine link, so the simulator can stop when a bridge starts. */
    val machineConnectionState: StateFlow<Int>

    /** Latest external strap heart rate, or null. Never the value the simulator itself publishes. */
    val externalHeartRate: StateFlow<Int?>

    /** Wall-clock ms at which [externalHeartRate] was last updated; zero means never. */
    val lastExternalHeartRateAtMs: Long

    fun beginSession(haveControl: Boolean)

    /** Leave the host state as an idle machine would, not mid-workout. */
    fun endSession()

    fun publish(sample: SimulatedUpstreamSample)
}

/** What the [SimulatedUpstreamSample.movementCount] field means for the simulated persona. */
enum class MovementCountKind { STROKES, STEPS, STRIDES }

/** One coherent simulated sample in FitBridge engine units; null means "this persona has no such metric". */
data class SimulatedUpstreamSample(
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
