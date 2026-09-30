package com.gmail.absolutebollockscreations.gattlibrary.central

import kotlinx.coroutines.flow.StateFlow

/**
 * Debug-variant-only injection seam (L24, LIB-27): lets a synthetic-data generator drive the engine
 * state as though a machine were attached, without a BLE link.
 *
 * This lives in `src/debug` on purpose. It is not part of the release ABI, so `BtEngineState` and
 * its setters can become central-internal without a public write path surviving for simulation. The
 * simulator never sees this type; the debug app maps the simulator's `SimulatedUpstreamHost`
 * contract onto it (central does not depend on gatt-server).
 *
 * A simulator is a downstream peripheral plus a local metric producer, so it projects into workout
 * state without registering an upstream BLE link: the scanner's connection registry represents
 * physical central-role links, and registering a simulator there would make it look like a machine
 * FitBridge connected to and expose a disconnect action that only stops advertising.
 */
class SyntheticUpstream(
    private val engineState: BtEngineState,
) {
    /** True when a real machine link exists. */
    val machineLinkAttached: Boolean get() = engineState.gatt != null

    /** `BluetoothProfile.STATE_*` of the machine link. */
    val machineConnectionState: StateFlow<Int> get() = engineState.connectionState

    /** Latest external strap heart rate, or null. */
    val externalHeartRate: StateFlow<Int?> get() = engineState.externalHeartRate

    /** Wall-clock ms at which [externalHeartRate] was last updated; zero means never. */
    val lastExternalHeartRateAtMs: Long get() = engineState.lastExternalAt

    fun beginSession(haveControl: Boolean) {
        engineState.setActiveSession(true)
        engineState.setIsRunning(true)
        engineState.setHaveControl(haveControl)
    }

    /** Leave the state object as an idle machine would, not mid-workout. */
    fun endSession() {
        engineState.setHaveControl(false)
        engineState.setIsRunning(false)
        engineState.setActiveSession(false)
        engineState.setElapsedSeconds(null)
        engineState.setSpeedKph(null)
        engineState.setSpeedMph(null)
        engineState.setAvgSpeedKph(null)
        engineState.setTotalDistanceMeters(null)
        engineState.setInstantCadence(null)
        engineState.setAverageCadence(null)
        engineState.setResistance(null)
        engineState.setInclination(null)
        engineState.setInstantPower(null)
        engineState.setAveragePower(null)
        engineState.setForceOnBelt(null)
        engineState.setInstantPace(null)
        engineState.setAvgPace(null)
        engineState.setElevationGains(null, null)
        engineState.setTotalFloors(null)
        engineState.setTotalSteps(null)
        engineState.setTotalStrides(null)
        engineState.setStrokeCount(null)
        engineState.setStrokeRate(null)
        engineState.setExpEnergyTotal(null)
        engineState.setExpEnergyRateKcalPerHour(null)
        engineState.setHeartRate(null)
    }

    /**
     * Deliberately mirrors what the FTMS parsers do for a real machine, including leaving a metric
     * untouched when the persona does not produce it - a simulated rower must not publish an
     * inclination any more than a real one would.
     */
    fun publish(sample: SyntheticSample) {
        engineState.setElapsedSeconds(sample.elapsedSeconds)
        engineState.setHeartRate(sample.heartRate)
        engineState.setExpEnergyTotal(sample.caloriesTotal)
        engineState.setExpEnergyRateKcalPerHour(sample.caloriesPerHour)

        sample.speedKph?.let {
            engineState.setSpeedKph(it)
            engineState.setSpeedMph(it / MILES_TO_KM)
        }
        sample.averageSpeedKph?.let { engineState.setAvgSpeedKph(it) }
        sample.distanceMeters?.let { engineState.setTotalDistanceMeters(it) }
        sample.cadence?.let { engineState.setInstantCadence(it) }
        engineState.setAverageCadence(sample.averageCadence)
        sample.resistance?.let { engineState.setResistance(it.toDouble()) }
        sample.inclinationPercent?.let { engineState.setInclination(it) }
        sample.powerWatts?.let { engineState.setInstantPower(it) }
        engineState.setAveragePower(sample.averagePowerWatts)
        sample.forceOnBeltNewtons?.let { engineState.appendForceOnBeltSample(it) }
        sample.instantPaceMinPerKm?.let { engineState.setInstantPace(it) }
        sample.averagePaceMinPerKm?.let { engineState.setAvgPace(it) }
        sample.positiveElevationMeters?.let { engineState.setElevationGains(it, null) }
        sample.floors?.let { engineState.setTotalFloors(it) }

        // The count field means a different thing per persona, so it is routed rather than
        // written to all three.
        sample.movementCount?.let { count ->
            when (sample.movementCountKind) {
                null -> Unit
                SyntheticMovementKind.STROKES -> {
                    engineState.setStrokeCount(count)
                    sample.cadence?.let { engineState.setStrokeRate(it) }
                }
                SyntheticMovementKind.STEPS -> engineState.setTotalSteps(count)
                SyntheticMovementKind.STRIDES -> engineState.setTotalStrides(count.toDouble())
            }
        }
    }

    private companion object {
        const val MILES_TO_KM = 1.609344
    }
}

/** What [SyntheticSample.movementCount] means for the synthetic machine. */
enum class SyntheticMovementKind { STROKES, STEPS, STRIDES }

/** One coherent synthetic sample in engine units; null means "this machine has no such metric". */
data class SyntheticSample(
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
    /** Routes [movementCount]; null drops it. */
    val movementCountKind: SyntheticMovementKind? = null,
    val positiveElevationMeters: Double? = null,
    val floors: Int? = null,
)
