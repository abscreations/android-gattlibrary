package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.BtEngineState
import com.gmail.absolutebollockscreations.gattlibrary.central.BtServiceEngineFacade
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsTargetRange
import com.gmail.absolutebollockscreations.gattlibrary.server.TargetCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization for the unit-1 migration (L24): pins what [EngineFtmsControlSink] does today so the
 * typed public command API can replace it without silently changing behavior. Where a test records
 * behavior the new API deliberately should NOT keep, the test says so; those are decisions to make,
 * not properties to preserve.
 *
 * Complements [FtmsPassThroughCharacterizationTest] (Kayak First local termination, a failed start,
 * and the speed-target status round trip), which is not repeated here.
 */
class EngineFtmsControlSinkCharacterizationTest {

    private class FakeFacade : BtServiceEngineFacade {
        override var isFtmsConnected: Boolean = true
        override val engineState = BtEngineState()
        override var handlesDownstreamSessionTransitionsLocally: Boolean = false

        /** Result every command returns, or a throw when [throwOnCall]. */
        var answer = true
        var throwOnCall = false
        val calls = mutableListOf<String>()

        private fun record(call: String): Boolean {
            calls += call
            if (throwOnCall) error("boom")
            return answer
        }

        override fun buildSupportedSpeedRange() = ByteArray(0)
        override fun buildSupportedInclineRange() = ByteArray(0)
        override fun buildSupportedResistanceRange() = ByteArray(0)
        override fun buildSupportedHeartRateRange() = ByteArray(0)
        override fun buildSupportedPowerRange() = ByteArray(0)
        override fun startOrResume() = record("start")
        override fun stopOrPause() = record("stop")
        override fun setTargetSpeedKph(kph: Float) = record("speed=$kph")
        override fun setTargetInclinationPercent(percent: Float) = record("incline=$percent")
        override fun setTargetResistance(level: Float) = record("resistance=$level")
        override fun setTargetPowerWatts(watts: Int) = record("power=$watts")
        override fun setWheelCircumferenceMm(millimetres: Float) = record("wheel=$millimetres")
        override fun setIndoorBikeSimulation(
            windSpeedMps: Float,
            gradePercent: Float,
            rollingResistanceCoefficient: Float,
            windResistanceCoefficient: Float,
        ) = record("sim=$windSpeedMps,$gradePercent,$rollingResistanceCoefficient,$windResistanceCoefficient")
    }

    private fun EngineFtmsControlSink.allTargets(): List<Boolean> = listOf(
        setTargetSpeedKph(10.0),
        setTargetInclinationPercent(1.0),
        setTargetResistance(2.0),
        setTargetPowerWatts(100),
        setTargetHeartRate(120),
        setWheelCircumferenceMm(2100.0),
        setIndoorBikeSimulation(0.0, 1.0, 0.004, 0.5),
    )

    private fun EngineFtmsControlSink.sessionCalls(): List<Boolean> =
        listOf(requestControl(), reset(), startOrResume(), stopOrPause(stop = true), stopOrPause(stop = false))

    @Test
    fun `no engine attached accepts session procedures and refuses every target`() {
        val sink = EngineFtmsControlSink { null }
        assertFalse(sink.canControl)
        assertTrue(sink.sessionCalls().all { it })
        assertTrue(sink.allTargets().none { it })
    }

    @Test
    fun `an engine that is not connected behaves like no engine and is never called`() {
        val facade = FakeFacade().apply { isFtmsConnected = false }
        val sink = EngineFtmsControlSink { facade }
        assertFalse(sink.canControl)
        assertTrue(sink.sessionCalls().all { it })
        assertTrue(sink.allTargets().none { it })
        assertEquals(emptyList<String>(), facade.calls)
    }

    @Test
    fun `canControl follows the live connection state`() {
        val facade = FakeFacade()
        val sink = EngineFtmsControlSink { facade }
        assertTrue(sink.canControl)
        facade.isFtmsConnected = false
        assertFalse(sink.canControl)
    }

    @Test
    fun `request control and reset are accepted locally and never reach the machine`() {
        val facade = FakeFacade().apply { answer = false }
        val sink = EngineFtmsControlSink { facade }
        assertTrue(sink.requestControl())
        assertTrue(sink.reset())
        assertEquals(emptyList<String>(), facade.calls)
    }

    @Test
    fun `stop and pause both collapse to the single upstream stopOrPause`() {
        val facade = FakeFacade()
        val sink = EngineFtmsControlSink { facade }
        sink.stopOrPause(stop = true)
        sink.stopOrPause(stop = false)
        assertEquals(listOf("stop", "stop"), facade.calls)
    }

    @Test
    fun `a connected machine's refusal is reported verbatim for sessions and targets`() {
        val facade = FakeFacade().apply { answer = false }
        val sink = EngineFtmsControlSink { facade }
        assertFalse(sink.startOrResume())
        assertFalse(sink.stopOrPause(stop = true))
        // Heart rate has no upstream path, so it is false regardless of the machine's answer.
        assertTrue(sink.allTargets().none { it })
    }

    @Test
    fun `targets are forwarded to a connected machine with float conversion and argument order kept`() {
        val facade = FakeFacade()
        val sink = EngineFtmsControlSink { facade }
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
            facade.calls,
        )
    }

    @Test
    fun `heart rate target has no upstream path and is refused without calling the machine`() {
        val facade = FakeFacade()
        assertFalse(EngineFtmsControlSink { facade }.setTargetHeartRate(150))
        assertEquals(emptyList<String>(), facade.calls)
    }

    @Test
    fun `an upstream exception becomes a refusal rather than propagating`() {
        val facade = FakeFacade().apply { throwOnCall = true }
        val sink = EngineFtmsControlSink { facade }
        assertFalse(sink.startOrResume())
        assertFalse(sink.stopOrPause(stop = false))
        assertTrue(sink.allTargets().none { it })
        // request control and reset never touch the machine, so they cannot throw.
        assertTrue(sink.requestControl())
    }

    @Test
    fun `session-local machines terminate transitions locally but targets still forward`() {
        // Kayak First style: the sink itself does not gate targets on this flag. Gating happens in
        // the control point through empty TargetCapabilities; the sink would forward if asked.
        val facade = FakeFacade().apply { handlesDownstreamSessionTransitionsLocally = true }
        val sink = EngineFtmsControlSink { facade }
        assertTrue(sink.startOrResume())
        assertTrue(sink.stopOrPause(stop = true))
        assertEquals(emptyList<String>(), facade.calls)
        assertTrue(sink.setTargetSpeedKph(5.0))
        assertEquals(listOf("speed=5.0"), facade.calls)
    }

    @Test
    fun `the engine is resolved at dispatch time so a command reaches whichever machine is current`() {
        // DECISION FOR THE NEW API, not a property to keep: the LIB-17a design requires a typed
        // command to capture the machine handle/generation at submission and to fail rather than be
        // redirected to a replacement. Today nothing does, and this test records that.
        val first = FakeFacade()
        val second = FakeFacade()
        var current: BtServiceEngineFacade? = first
        val sink = EngineFtmsControlSink { current }

        sink.setTargetPowerWatts(150)
        current = second
        sink.setTargetPowerWatts(160)
        current = null
        val afterDetach = sink.setTargetPowerWatts(170)

        assertEquals(listOf("power=150"), first.calls)
        assertEquals(listOf("power=160"), second.calls)
        assertFalse(afterDetach)
    }

    @Test
    fun `advertised target capability is empty until a session begins and cleared when it ends`() {
        val sink = EngineFtmsControlSink { FakeFacade() }
        assertEquals(TargetCapabilities.NONE, sink.targetCapabilities)
        val caps = TargetCapabilities(speed = true, speedRange = FtmsTargetRange(0, 5_000, 1))
        sink.beginSession(caps)
        assertEquals(caps, sink.targetCapabilities)
        sink.endSession()
        assertEquals(TargetCapabilities.NONE, sink.targetCapabilities)
    }

    @Test
    fun `target changes are reported asynchronously through the upstream status characteristic`() {
        assertTrue(EngineFtmsControlSink { null }.reportsTargetChangesAsynchronously)
    }
}
