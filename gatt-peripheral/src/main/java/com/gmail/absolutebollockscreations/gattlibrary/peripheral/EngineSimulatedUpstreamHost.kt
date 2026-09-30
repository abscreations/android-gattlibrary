package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.BtEngineState
import com.gmail.absolutebollockscreations.gattlibrary.server.MovementCountKind
import com.gmail.absolutebollockscreations.gattlibrary.server.SimulatedUpstreamHost
import com.gmail.absolutebollockscreations.gattlibrary.server.SimulatedUpstreamSample
import kotlinx.coroutines.flow.StateFlow

/**
 * Bridge: the simulator's [SimulatedUpstreamHost] over the real [BtEngineState] (LIB-19k).
 *
 * A simulator is a downstream peripheral plus a local metric producer, so it projects into workout
 * state without registering an upstream BLE link: the scanner's connection registry represents
 * physical central-role links, and registering a simulator there would make it look like a machine
 * FitBridge connected to and expose a disconnect action that only stops advertising.
 */
class EngineSimulatedUpstreamHost(
    private val engineState: BtEngineState,
) : SimulatedUpstreamHost {
    override val machineLinkAttached: Boolean get() = engineState.gatt != null
    override val machineConnectionState: StateFlow<Int> get() = engineState.connectionState
    override val externalHeartRate: StateFlow<Int?> get() = engineState.externalHeartRate
    override val lastExternalHeartRateAtMs: Long get() = engineState.lastExternalAt

    override fun beginSession(haveControl: Boolean) {
        engineState.setActiveSession(true)
        engineState.setIsRunning(true)
        engineState.setHaveControl(haveControl)
    }

    /** Leave the state object as an idle machine would, not mid-workout. */
    override fun endSession() {
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
    override fun publish(sample: SimulatedUpstreamSample) {
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
                MovementCountKind.STROKES -> {
                    engineState.setStrokeCount(count)
                    sample.cadence?.let { engineState.setStrokeRate(it) }
                }
                MovementCountKind.STEPS -> engineState.setTotalSteps(count)
                MovementCountKind.STRIDES -> engineState.setTotalStrides(count.toDouble())
            }
        }
    }

    private companion object {
        const val MILES_TO_KM = 1.609344
    }
}
