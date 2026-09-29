package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtEngineState

/**
 * Projects a local generator into workout state without inventing an upstream BLE link.
 *
 * The scanner's connection registry represents physical central-role links. A simulator is a
 * downstream peripheral plus a local metric producer, so registering it there makes it look like
 * a machine FitBridge connected to and exposes a disconnect action that actually stops advertising.
 */
internal class SimulatorSessionProjection(
    private val engineState: BtEngineState,
) {
    fun begin(haveControl: Boolean) {
        engineState.setActiveSession(true)
        engineState.setIsRunning(true)
        engineState.setHaveControl(haveControl)
    }

    /** Leave the state object as an idle machine would, not mid-workout. */
    fun end() {
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
}
