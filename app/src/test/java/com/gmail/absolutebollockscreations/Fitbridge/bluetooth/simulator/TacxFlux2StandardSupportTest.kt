package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.CyclingRevolutionSnapshot
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.FtmsControlPointHandler
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.FtmsProcedureSession

class TacxFlux2StandardSupportTest {
    @Test fun `indoor bike data reproduces captured field shape`() {
        val state = FeCBleMachineState(FeCEquipmentType.TRAINER).apply {
            speedMps = 10.0
            cadenceRpm = 82
            instantaneousPowerWatts = 245
        }
        assertEquals(
            "44 00 10 0E A4 00 F5 00",
            hex(TacxFlux2StandardEncoders.indoorBikeData(state)),
        )
    }

    @Test fun `cycling power packet includes physical Flux wheel and crank shape`() {
        val state = FeCBleMachineState(FeCEquipmentType.TRAINER).apply {
            instantaneousPowerWatts = 245
        }
        val frame = TacxFlux2StandardEncoders.cyclingPowerMeasurement(
            state,
            CyclingRevolutionSnapshot(
                cumulativeWheelRevolutions = 12,
                lastWheelEventTime = 0x1234,
                cumulativeCrankRevolutions = 7,
                lastCrankEventTime = 0x5678,
            ),
        )
        assertEquals("30 00 F5 00 0C 00 00 00 34 12 07 00 78 56", hex(frame))
    }

    @Test fun `standard FTMS simulation controls the shared FE-C model`() {
        val state = FeCBleMachineState(FeCEquipmentType.TRAINER)
        val session = FtmsProcedureSession()
        val handler = FtmsControlPointHandler(TacxFlux2FtmsControlSink(state))

        assertEquals("80 00 01", hex(handler.handle(byteArrayOf(0x00), session).response))
        val outcome = handler.handle(
            byteArrayOf(
                0x11,
                0x00, 0x00,       // wind: 0 m/s
                0x38, 0xFF.toByte(), // grade: -2.00%
                0x28,             // Crr: 0.0040
                0x33,             // Cw: 0.51 kg/m
            ),
            session,
        )

        assertEquals("80 11 01", hex(outcome.response))
        assertEquals(FeCBleControlMode.SIMULATION, state.controlMode)
        assertEquals(-2.0, state.track!!.gradePercent, 0.0001)
        assertEquals(0.004, state.track!!.crr, 0.000001)
        assertEquals(0.51, state.wind!!.windCoefficient, 0.0001)
        assertTrue(outcome.status!!.contentEquals(byteArrayOf(0x12, 0x00, 0x00, 0x38, 0xFF.toByte(), 0x28, 0x33)))
    }

    private fun hex(value: ByteArray): String = value.joinToString(" ") { "%02X".format(it) }
}
