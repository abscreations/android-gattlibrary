package com.gmail.absolutebollockscreations.gattlibrary.simulator

import kotlin.math.roundToInt
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.CyclingRevolutionSnapshot
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.CyclingSensorEncoders
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.FtmsControlSink
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.FtmsTargetRange
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.TargetCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.core.bytes.LittleEndian.u16Wrapping
import com.gmail.absolutebollockscreations.gattlibrary.core.bytes.LittleEndian.s16Wrapping
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.CpsNativeEventInput

/** Standard packet behavior directly exercised by Zwift in the physical Flux-2 capture. */
internal object TacxFlux2StandardEncoders {
    const val INDOOR_BIKE_DATA_FLAGS = 0x0044

    fun indoorBikeData(state: FeCBleMachineState): ByteArray =
        u16Wrapping(INDOOR_BIKE_DATA_FLAGS) +
            u16Wrapping((state.speedMps * 360.0).roundToInt().coerceIn(0, 0xFFFF)) +
            u16Wrapping(((state.cadenceRpm ?: 0) * 2).coerceIn(0, 0xFFFF)) +
            s16Wrapping((state.instantaneousPowerWatts ?: 0).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))

    /** The capture's CPS packets carry both wheel and crank event fields (flags `0x0030`). */
    fun cyclingPowerMeasurement(
        state: FeCBleMachineState,
        revolutions: CyclingRevolutionSnapshot,
    ): ByteArray = CyclingSensorEncoders.cyclingPowerMeasurement(
        instantaneousPowerWatts = state.instantaneousPowerWatts ?: 0,
        revolutions = revolutions,
        native = CpsNativeEventInput(
            cumulativeWheelRevolutions = revolutions.cumulativeWheelRevolutions,
            lastWheelEventTimeTicks = revolutions.lastWheelEventTime,
            cumulativeCrankRevolutions = revolutions.cumulativeCrankRevolutions,
            lastCrankEventTimeTicks = revolutions.lastCrankEventTime,
        ),
    )
}

/**
 * Maps standard FTMS trainer procedures onto the same state used by FE-C and CPS.
 *
 * The feature bits are a physical read. The numeric range triples are deliberately broad
 * simulator limits because this capture did not read the physical range characteristics.
 */
internal class TacxFlux2FtmsControlSink(
    private val state: FeCBleMachineState,
) : FtmsControlSink {
    override val canControl: Boolean = true
    override val targetCapabilities: TargetCapabilities = TargetCapabilities(
        resistance = true,
        power = true,
        indoorBikeSimulation = true,
        resistanceRange = FtmsTargetRange(0, 1_000, 1),
        powerRange = FtmsTargetRange(0, 2_000, 1),
    )
    override val clampsTargetsToAdvertisedRange: Boolean = false

    override fun requestControl(): Boolean = true

    override fun reset(): Boolean {
        synchronized(state) { state.reset() }
        return true
    }

    override fun startOrResume(): Boolean {
        synchronized(state) {
            if (state.feState != FeCEquipmentState.IN_USE) state.enterInUseWithDefaults()
        }
        return true
    }

    override fun stopOrPause(stop: Boolean): Boolean {
        synchronized(state) { state.feState = FeCEquipmentState.FINISHED }
        return true
    }

    override fun setTargetSpeedKph(kph: Double): Boolean = false
    override fun setTargetInclinationPercent(percent: Double): Boolean = false

    override fun setTargetResistance(level: Double): Boolean {
        synchronized(state) {
            FeCBleSyntheticModel.applyControlCommand(
                state,
                FeCBleControlCommand.BasicResistance(level),
                clientKey = null,
            )
        }
        return true
    }

    override fun setTargetPowerWatts(watts: Int): Boolean {
        synchronized(state) {
            FeCBleSyntheticModel.applyControlCommand(
                state,
                FeCBleControlCommand.TargetPower(watts.toDouble()),
                clientKey = null,
            )
        }
        return true
    }

    override fun setTargetHeartRate(bpm: Int): Boolean = false
    override fun setWheelCircumferenceMm(millimetres: Double): Boolean = false

    override fun setIndoorBikeSimulation(
        windMps: Double,
        gradePercent: Double,
        crr: Double,
        cw: Double,
    ): Boolean {
        synchronized(state) {
            state.controlMode = FeCBleControlMode.SIMULATION
            state.track = FeCBleTrackConfig(gradePercent, crr)
            state.wind = FeCBleWindConfig(
                windCoefficient = cw,
                windSpeedEncodedUnit = (windMps * 3.6).roundToInt(),
                draftingFactor = 1.0,
            )
        }
        return true
    }
}
