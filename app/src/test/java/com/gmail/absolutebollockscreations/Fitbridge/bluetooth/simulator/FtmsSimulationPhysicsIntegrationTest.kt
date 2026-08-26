package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtServiceProfiles.FtmsMachineProfile
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.FtmsControlPointHandler
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.FtmsControlSink
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.FtmsProcedureSession
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.FtmsSpec
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.TargetCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt
import kotlin.random.Random

class FtmsSimulationPhysicsIntegrationTest {
    @Test
    fun `FTMS signed descent lowers power at the same published speed`() {
        val engine = FtmsMockEngine(
            FtmsMachineProfile.INDOOR_BIKE,
            controlsEnabled = true,
            random = Random(11),
        )
        val handler = FtmsControlPointHandler(EngineSink(engine))
        val session = FtmsProcedureSession()

        assertResult(FtmsSpec.Result.SUCCESS, handler.handle(bytes(0x00), session))
        assertResult(FtmsSpec.Result.SUCCESS, handler.handle(bytes(0x02, 0xF0, 0x0A), session))
        assertResult(
            FtmsSpec.Result.SUCCESS,
            handler.handle(bytes(0x11, 0, 0, 0, 0, 40, 51), session),
        )
        val flat = engine.tick(0.5)

        assertResult(
            FtmsSpec.Result.SUCCESS,
            // -5.00% = signed little-endian -500 = 0xFE0C.
            handler.handle(bytes(0x11, 0, 0, 0x0C, 0xFE, 40, 51), session),
        )
        val descent = engine.tick(0.5)

        val flatSpeed = requireNotNull(flat.speedKph)
        val descentSpeed = requireNotNull(descent.speedKph)
        assertEquals(28.0, flatSpeed, 0.0)
        assertEquals(flatSpeed, descentSpeed, 0.0)
        assertEquals(-5.0, engine.simulationParams()!!.gradePercent, 0.0)
        assertTrue(descent.powerWatts!! < flat.powerWatts!!)
        assertEquals(0, descent.powerWatts)
    }

    @Test
    fun `FTMS 0x11 reaches road physics and later ERG takes ownership`() {
        val engine = FtmsMockEngine(
            FtmsMachineProfile.INDOOR_BIKE,
            controlsEnabled = true,
            random = Random(7),
        )
        val handler = FtmsControlPointHandler(EngineSink(engine))
        val session = FtmsProcedureSession()

        assertResult(FtmsSpec.Result.SUCCESS, handler.handle(bytes(0x00), session))
        assertResult(
            FtmsSpec.Result.SUCCESS,
            handler.handle(bytes(0x11, 0xD0, 0x07, 0x90, 0x01, 45, 51), session),
        )
        val simulation = engine.tick(0.5)
        val expected = CyclingSimulationPhysics.requiredRiderPowerWatts(
            speedKph = simulation.speedKph!!,
            gradePercent = 4.0,
            crr = 0.0045,
            cwKgPerM = 0.51,
            windMps = 2.0,
        ).roundToInt()
        assertEquals(expected, simulation.powerWatts)
        assertEquals(SimulatorControlModeLabels.FTMS_SIM, engine.controlModeLabel())

        assertResult(FtmsSpec.Result.SUCCESS, handler.handle(bytes(0x05, 0xFA, 0x00), session))
        repeat(10) { engine.tick(0.5) }
        assertEquals(250, engine.snapshot().powerWatts)
        assertEquals(SimulatorControlModeLabels.FTMS_ERG, engine.controlModeLabel())
        assertTrue(FtmsMockTarget.POWER in engine.controlTargets())
    }

    private class EngineSink(private val engine: FtmsMockEngine) : FtmsControlSink {
        override val canControl: Boolean = true
        override val targetCapabilities: TargetCapabilities get() = engine.controlCapabilities
        override val clampsTargetsToAdvertisedRange: Boolean = true
        override fun requestControl() = true
        override fun reset(): Boolean = engine.run { reset(); true }
        override fun startOrResume() = true
        override fun stopOrPause(stop: Boolean) = true
        override fun setTargetSpeedKph(kph: Double) = engine.setTarget(FtmsMockTarget.SPEED, kph)
        override fun setTargetInclinationPercent(percent: Double) =
            engine.setTarget(FtmsMockTarget.INCLINATION, percent)
        override fun setTargetResistance(level: Double) = engine.setTarget(FtmsMockTarget.RESISTANCE, level)
        override fun setTargetPowerWatts(watts: Int) = engine.setTarget(FtmsMockTarget.POWER, watts.toDouble())
        override fun setTargetHeartRate(bpm: Int) = false
        override fun setWheelCircumferenceMm(millimetres: Double) = true
        override fun setIndoorBikeSimulation(
            windMps: Double,
            gradePercent: Double,
            crr: Double,
            cw: Double,
        ) = engine.setSimulationParams(FtmsMockSimulationParams(windMps, gradePercent, crr, cw))
    }

    private fun assertResult(expected: Byte, outcome: FtmsControlPointHandler.Outcome) {
        assertEquals(expected, outcome.response[2])
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
