package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.core.KeepAwake
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsControlSink
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsControlPointHandler
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsProcedureSession
import com.gmail.absolutebollockscreations.gattlibrary.server.InMemoryUpstream
import com.gmail.absolutebollockscreations.gattlibrary.server.TargetCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatorWorkoutControlTest {
    @Test
    fun `Sole FTMS procedures drive the synthetic workout`() {
        var installedSink: FtmsControlSink? = null
        val host = object : FtmsSimulator.SimulatorPeripheralHost {
            override val isAdvertising = true
            override fun startAdvertising(persona: SimulatorPersona, targets: TargetCapabilities) = true
            override fun stopAdvertising() = Unit
            override fun setControlOverride(sink: FtmsControlSink?) {
                installedSink = sink
            }
        }
        val simulator = FtmsSimulator(InMemoryUpstream(), host, object : KeepAwake {
            override fun acquireCpu(owner: Any) = Unit
            override fun releaseCpu(owner: Any) = Unit
        })
        try {
            simulator.start(SimulatorPersona.Sole(SoleSimulatorProfile.F80_MODERN), enableControls = true)
            val delegate = requireNotNull(installedSink)
            val handler = FtmsControlPointHandler(delegate)
            val session = FtmsProcedureSession()
            assertEquals(1, handler.handle(byteArrayOf(0), session).response[2].toInt())
            assertEquals(1, handler.handle(byteArrayOf(7), session).response[2].toInt())
            assertTrue(simulator.workoutRunning.value)
            assertEquals(1, handler.handle(byteArrayOf(3, 40, 0), session).response[2].toInt())
            assertEquals(4.0, simulator.state.value.acceptedTargets.getValue(FtmsMockTarget.INCLINATION), 0.001)
            assertEquals(1, handler.handle(byteArrayOf(8, 2), session).response[2].toInt())
            assertFalse(simulator.workoutRunning.value)
        } finally {
            simulator.dispose()
        }
    }

    @Test
    fun `workout controls leave the BLE session running until simulator stop`() {
        val transitions = mutableListOf<Boolean>()
        val host = object : FtmsSimulator.SimulatorPeripheralHost {
            override var isAdvertising = false

            override fun startAdvertising(persona: SimulatorPersona, targets: TargetCapabilities): Boolean {
                isAdvertising = true
                return true
            }

            override fun stopAdvertising() {
                isAdvertising = false
            }

            override fun setControlOverride(sink: FtmsControlSink?) = Unit

            override fun setWorkoutRunning(running: Boolean) {
                transitions += running
            }
        }
        val simulator = FtmsSimulator(InMemoryUpstream(), host, object : KeepAwake {
            override fun acquireCpu(owner: Any) = Unit
            override fun releaseCpu(owner: Any) = Unit
        })

        try {
            simulator.startWorkout()
            simulator.start(SimulatorPersona.Sole(SoleSimulatorProfile.ST90))
            assertTrue(host.isAdvertising)
            assertFalse(simulator.workoutRunning.value)
            assertEquals(0L, simulator.state.value.samplesGenerated)
            assertEquals(emptyList<Boolean>(), transitions)

            simulator.startWorkout()
            simulator.startWorkout()
            assertTrue(simulator.workoutRunning.value)
            simulator.stopWorkout()
            assertFalse(simulator.workoutRunning.value)
            assertTrue(host.isAdvertising)
            assertTrue(simulator.state.value.running)

            simulator.startWorkout()
            simulator.stop()
            assertFalse(host.isAdvertising)
            assertFalse(simulator.workoutRunning.value)
            assertEquals(listOf(true, false, true, false), transitions)
        } finally {
            simulator.dispose()
        }
    }

    @Test
    fun `stopping KICKR measurements drops queued workout data and preserves control replies`() {
        val session = KickrPeripheralSession(1L, KickrSyntheticModel())
        val uuid = java.util.UUID(0L, 1L)
        session.enqueueWorkoutFrame(KickrTxFrame(uuid, byteArrayOf(1), indicate = false))
        session.enqueue(KickrTxFrame(uuid, byteArrayOf(2), indicate = true))

        session.clearWorkoutMeasurements()

        assertEquals(1, session.pendingCount)
        assertTrue(requireNotNull(session.beginNext()).indicate)
    }
}
