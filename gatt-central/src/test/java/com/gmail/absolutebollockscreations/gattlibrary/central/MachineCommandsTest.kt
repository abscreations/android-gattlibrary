package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MachineCommandsTest {
    private val sessionTransitions = listOf(MachineCommand.StartOrResume, MachineCommand.StopOrPause)
    private val targets = listOf(
        MachineCommand.TargetSpeedKph(5f),
        MachineCommand.TargetInclinePercent(1f),
        MachineCommand.TargetResistanceLevel(2f),
        MachineCommand.TargetPowerWatts(100),
        MachineCommand.WheelCircumferenceMm(2100f),
        MachineCommand.IndoorBikeSimulation(0f, 1f, 0.004f, 0.5f),
    )

    @Test
    fun `a machine that runs its own session terminates start and stop locally`() {
        assertTrue(sessionTransitions.all { it.terminatesLocallyOn(machineRunsOwnSession = true) })
    }

    @Test
    fun `targets are never terminated locally`() {
        assertTrue(targets.none { it.terminatesLocallyOn(machineRunsOwnSession = true) })
    }

    @Test
    fun `an ordinary machine receives every command`() {
        assertFalse((sessionTransitions + targets).any { it.terminatesLocallyOn(machineRunsOwnSession = false) })
    }
}
