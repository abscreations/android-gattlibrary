package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.CommandResult
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsTargetRange
import com.gmail.absolutebollockscreations.gattlibrary.server.TargetCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ClientFtmsControlSink] keeps what the engine-facade sink did (accept session procedures locally
 * with no machine, never optimistic about targets, report the machine's refusal, turn a throw into a
 * refusal) and replaces dispatch-time lookup with a machine token captured at [beginSession].
 *
 * Kayak First local termination of Start/Stop now happens inside the central client; see
 * `MachineCommandsTest` there and [FtmsPassThroughCharacterizationTest] for the sink-side contract.
 */
class ClientFtmsControlSinkTest {

    /** A sink whose session has begun, so it is bound to the link's current machine. */
    private fun bound(link: FakeUpstreamMachineLink) = ClientFtmsControlSink(link).also {
        it.beginSession(TargetCapabilities.NONE)
    }

    private fun ClientFtmsControlSink.allTargets(): List<Boolean> = listOf(
        setTargetSpeedKph(10.0),
        setTargetInclinationPercent(1.0),
        setTargetResistance(2.0),
        setTargetPowerWatts(100),
        setTargetHeartRate(120),
        setWheelCircumferenceMm(2100.0),
        setIndoorBikeSimulation(0.0, 1.0, 0.004, 0.5),
    )

    private fun ClientFtmsControlSink.sessionCalls(): List<Boolean> =
        listOf(requestControl(), reset(), startOrResume(), stopOrPause(stop = true), stopOrPause(stop = false))

    @Test
    fun `no machine attached accepts session procedures and refuses every target`() {
        val sink = ClientFtmsControlSink(NoUpstreamMachine).also { it.beginSession(TargetCapabilities.NONE) }
        assertFalse(sink.canControl)
        assertTrue(sink.sessionCalls().all { it })
        assertTrue(sink.allTargets().none { it })
    }

    @Test
    fun `a machine that is not connected behaves like none and is never called`() {
        val link = FakeUpstreamMachineLink().apply { machineConnected = false }
        val sink = bound(link)
        assertFalse(sink.canControl)
        assertTrue(sink.sessionCalls().all { it })
        assertTrue(sink.allTargets().none { it })
        assertEquals(emptyList<String>(), link.calls)
    }

    @Test
    fun `a session that began with no machine has none even if one appears later`() {
        val link = FakeUpstreamMachineLink().apply { machineToken = null }
        val sink = bound(link)
        link.machineToken = link.tokenFor("late")
        link.currentKey = "late"
        assertFalse(sink.canControl)
        assertTrue(sink.startOrResume())
        assertFalse(sink.setTargetPowerWatts(100))
        assertEquals(emptyList<String>(), link.calls)
    }

    @Test
    fun `canControl follows the live connection state`() {
        val link = FakeUpstreamMachineLink()
        val sink = bound(link)
        assertTrue(sink.canControl)
        link.machineConnected = false
        assertFalse(sink.canControl)
    }

    @Test
    fun `canControl is false before a session begins and after it ends`() {
        val link = FakeUpstreamMachineLink()
        val sink = ClientFtmsControlSink(link)
        assertFalse(sink.canControl)
        sink.beginSession(TargetCapabilities.NONE)
        assertTrue(sink.canControl)
        sink.endSession()
        assertFalse(sink.canControl)
    }

    @Test
    fun `request control and reset are accepted locally and never reach the machine`() {
        val link = FakeUpstreamMachineLink().apply { refuse() }
        val sink = bound(link)
        assertTrue(sink.requestControl())
        assertTrue(sink.reset())
        assertEquals(emptyList<String>(), link.calls)
    }

    @Test
    fun `stop and pause both collapse to the single stopOrPause`() {
        val link = FakeUpstreamMachineLink()
        val sink = bound(link)
        sink.stopOrPause(stop = true)
        sink.stopOrPause(stop = false)
        assertEquals(listOf("stop", "stop"), link.calls)
    }

    @Test
    fun `a connected machine's refusal is reported for sessions and targets`() {
        val link = FakeUpstreamMachineLink().apply { refuse() }
        val sink = bound(link)
        assertFalse(sink.startOrResume())
        assertFalse(sink.stopOrPause(stop = true))
        assertTrue(sink.allTargets().none { it })
    }

    @Test
    fun `targets are forwarded with float conversion and argument order kept`() {
        val link = FakeUpstreamMachineLink()
        val sink = bound(link)
        assertTrue(sink.setTargetSpeedKph(12.34))
        assertTrue(sink.setTargetInclinationPercent(-2.5))
        assertTrue(sink.setTargetResistance(7.5))
        assertTrue(sink.setTargetPowerWatts(200))
        assertTrue(sink.setWheelCircumferenceMm(2096.0))
        assertTrue(sink.setIndoorBikeSimulation(windMps = 1.0, gradePercent = 2.5, crr = 0.004, cw = 0.51))
        assertEquals(
            listOf(
                "speed=12.34", "incline=-2.5", "resistance=7.5", "power=200", "wheel=2096.0",
                "sim=1.0,2.5,0.004,0.51",
            ),
            link.calls,
        )
    }

    @Test
    fun `heart rate target has no upstream path and is refused without calling the machine`() {
        val link = FakeUpstreamMachineLink()
        assertFalse(bound(link).setTargetHeartRate(150))
        assertEquals(emptyList<String>(), link.calls)
    }

    @Test
    fun `an upstream exception becomes a refusal rather than propagating`() {
        val link = FakeUpstreamMachineLink().apply { throwOnCall = true }
        val sink = bound(link)
        assertFalse(sink.startOrResume())
        assertFalse(sink.stopOrPause(stop = false))
        assertTrue(sink.allTargets().none { it })
        assertTrue(sink.requestControl())
    }

    @Test
    fun `every command of a session carries the machine the session began with`() {
        val link = FakeUpstreamMachineLink()
        val sink = bound(link)
        val session = link.machineToken
        sink.setTargetPowerWatts(150)
        sink.startOrResume()
        assertEquals(listOf(session, session), link.tokens)
    }

    @Test
    fun `a replacement machine is never sent a command meant for the session's machine`() {
        // Replaces the engine sink's dispatch-time lookup, which redirected to whichever machine was current.
        val link = FakeUpstreamMachineLink()
        val sink = bound(link)
        val original = link.machineToken

        // The machine is swapped mid-session; the link now names a different one.
        link.currentKey = "machine-2"
        link.machineToken = link.tokenFor("machine-2")

        assertFalse("commands for the old machine are refused", sink.setTargetPowerWatts(160))
        assertFalse(sink.startOrResume())
        assertEquals(listOf(original, original), link.tokens)

        // A new session binds to the new machine.
        sink.endSession()
        sink.beginSession(TargetCapabilities.NONE)
        assertTrue(sink.setTargetPowerWatts(170))
        assertEquals(link.tokenFor("machine-2"), link.tokens.last())
    }

    @Test
    fun `a machine change reported by the link is a refusal for sessions and targets`() {
        val link = FakeUpstreamMachineLink().apply { result = CommandResult.MachineChanged }
        val sink = bound(link)
        assertFalse(sink.startOrResume())
        assertFalse(sink.setTargetSpeedKph(5.0))
    }

    @Test
    fun `advertised target capability is empty until a session begins and cleared when it ends`() {
        val sink = ClientFtmsControlSink(FakeUpstreamMachineLink())
        assertEquals(TargetCapabilities.NONE, sink.targetCapabilities)
        val caps = TargetCapabilities(speed = true, speedRange = FtmsTargetRange(0, 5_000, 1))
        sink.beginSession(caps)
        assertEquals(caps, sink.targetCapabilities)
        sink.endSession()
        assertEquals(TargetCapabilities.NONE, sink.targetCapabilities)
    }

    @Test
    fun `target changes are reported asynchronously through the upstream status characteristic`() {
        assertTrue(ClientFtmsControlSink(NoUpstreamMachine).reportsTargetChangesAsynchronously)
    }

    @Test
    fun `no machine link has no token`() {
        assertNull(NoUpstreamMachine.machineToken)
    }
}
