package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what a Fitness Machine Status (0x2ADA) does to engine state and to the control sink today (LIB-47, step 1
 * of unit 4b), before the sink becomes the public `controlEvents` stream. The status callbacks are momentary
 * mirrors for downstream FTMS clients, so the cases that matter most are the ones a state flow would get wrong:
 * equal repeats still call the sink, and engine state is updated before the sink is called.
 *
 * `FitnessMachineStatusEventTest` (step 3) must keep the same assertions against the event stream.
 */
class FitnessMachineStatusCharacterizationTest {
    private val state = BtEngineState()
    private val sink = RecordingSink(state)

    private fun apply(vararg bytes: Int, to: BtServiceControlSink? = sink) =
        applyFitnessMachineStatus(state, to, ByteArray(bytes.size) { bytes[it].toByte() })

    private fun status(code: Byte, vararg payload: Int): IntArray =
        intArrayOf(code.toInt() and 0xFF, *payload)

    @Test fun `stopped by safety key sets state first then calls the sink once`() {
        state.setIsRunning(true)
        apply(*status(FtmsSpec.Status.STOPPED_BY_SAFETY_KEY))

        assertTrue(state.safetyKeyEngaged.value)
        assertEquals(false, state.isRunning.value)
        assertEquals(listOf("safetyKey engaged=true"), sink.events)
    }

    @Test fun `control permission lost clears control and calls the sink once`() {
        state.setHaveControl(true)
        apply(*status(FtmsSpec.Status.CONTROL_PERMISSION_LOST))

        assertEquals(false, state.haveControlFlow.value)
        assertEquals(listOf("controlLost haveControl=false"), sink.events)
    }

    @Test fun `machine reset clears safety key and control and never reaches the sink`() {
        state.setSafetyKeyEngaged(true)
        state.setHaveControl(true)
        apply(*status(FtmsSpec.Status.RESET))

        assertEquals(false, state.safetyKeyEngaged.value)
        assertEquals(false, state.haveControlFlow.value)
        assertTrue(sink.events.isEmpty())
    }

    @Test fun `user start and stop only move the running flag`() {
        apply(*status(FtmsSpec.Status.STARTED_OR_RESUMED_BY_USER))
        assertEquals(true, state.isRunning.value)
        apply(*status(FtmsSpec.Status.STOPPED_OR_PAUSED_BY_USER))
        assertEquals(false, state.isRunning.value)
        assertTrue(sink.events.isEmpty())
    }

    @Test fun `target speed decodes hundredths of km per hour and mirrors after state is set`() {
        apply(*status(FtmsSpec.Status.TARGET_SPEED_CHANGED, 0xD2, 0x04))

        assertEquals(12.34f, state.targetSpeedKmh.value!!, 0.0001f)
        assertEquals(listOf("speed 12.34 state=12.34"), sink.events)
    }

    @Test fun `a short target speed payload is read as zero and still mirrored`() {
        apply(*status(FtmsSpec.Status.TARGET_SPEED_CHANGED))

        assertEquals(0f, state.targetSpeedKmh.value!!, 0f)
        assertEquals(listOf("speed 0.0 state=0.0"), sink.events)
    }

    @Test fun `target resistance accepts the uint8 and sint16 forms and rejects other lengths`() {
        apply(*status(FtmsSpec.Status.TARGET_RESISTANCE_CHANGED, 0x32))
        assertEquals(5.0f, state.targetResistanceLevel.value!!, 0f)

        apply(*status(FtmsSpec.Status.TARGET_RESISTANCE_CHANGED, 0xCE, 0xFF))
        assertEquals(-5.0f, state.targetResistanceLevel.value!!, 0f)

        apply(*status(FtmsSpec.Status.TARGET_RESISTANCE_CHANGED, 0x01, 0x02, 0x03))
        assertEquals(-5.0f, state.targetResistanceLevel.value!!, 0f) // unchanged

        assertEquals(listOf("resistance 5.0 state=5.0", "resistance -5.0 state=-5.0"), sink.events)
    }

    @Test fun `target power is whole watts`() {
        apply(*status(FtmsSpec.Status.TARGET_POWER_CHANGED, 0xC8, 0x00))

        assertEquals(200, state.targetPowerWatts.value)
        assertEquals(listOf("power 200 state=200"), sink.events)
    }

    @Test fun `target heart rate zero clears state but the sink still receives zero`() {
        apply(*status(FtmsSpec.Status.TARGET_HEART_RATE_CHANGED, 140))
        assertEquals(140, state.targetHeartRateBpm.value)

        apply(*status(FtmsSpec.Status.TARGET_HEART_RATE_CHANGED, 0))
        assertNull(state.targetHeartRateBpm.value)

        apply(*status(FtmsSpec.Status.TARGET_HEART_RATE_CHANGED)) // short payload reads as zero
        assertNull(state.targetHeartRateBpm.value)

        assertEquals(listOf("hr 140 state=140", "hr 0 state=null", "hr 0 state=null"), sink.events)
    }

    @Test fun `an equal repeat still mirrors every time even though state does not change`() {
        repeat(3) { apply(*status(FtmsSpec.Status.TARGET_SPEED_CHANGED, 0xD2, 0x04)) }

        assertEquals(12.34f, state.targetSpeedKmh.value!!, 0.0001f)
        assertEquals(3, sink.events.size)
        assertTrue(sink.events.all { it == "speed 12.34 state=12.34" })
    }

    @Test fun `malformed incline and resistance record the opcode but change nothing and mirror nothing`() {
        apply(*status(FtmsSpec.Status.TARGET_INCLINE_CHANGED, 0xCE))
        assertEquals(FtmsSpec.Status.TARGET_INCLINE_CHANGED, state.lastFtmsStatusCode.value)
        assertNull(state.targetInclinePercent.value)

        apply(*status(FtmsSpec.Status.TARGET_RESISTANCE_CHANGED))
        assertEquals(FtmsSpec.Status.TARGET_RESISTANCE_CHANGED, state.lastFtmsStatusCode.value)
        assertNull(state.targetResistanceLevel.value)

        assertTrue(sink.events.isEmpty())
    }

    @Test fun `statuses the engine does not handle only record their opcode`() {
        for (code in intArrayOf(
            FtmsSpec.Status.NO_STATUS.toInt() and 0xFF,
            FtmsSpec.Status.INDOOR_BIKE_SIMULATION_PARAMETERS_CHANGED.toInt() and 0xFF,
            FtmsSpec.Status.WHEEL_CIRCUMFERENCE_CHANGED.toInt() and 0xFF,
            0x55,
        )) {
            apply(code, 0x01, 0x02, 0x03)
            assertEquals(code.toByte(), state.lastFtmsStatusCode.value)
        }

        assertTrue(sink.events.isEmpty())
        assertNull(state.targetSpeedKmh.value)
        assertNull(state.targetInclinePercent.value)
        assertNull(state.targetPowerWatts.value)
    }

    @Test fun `an empty payload changes nothing`() {
        apply()

        assertNull(state.lastFtmsStatusCode.value)
        assertTrue(sink.events.isEmpty())
    }

    @Test fun `without a sink the state is still updated`() {
        apply(*status(FtmsSpec.Status.TARGET_POWER_CHANGED, 0xC8, 0x00), to = null)
        apply(*status(FtmsSpec.Status.STOPPED_BY_SAFETY_KEY), to = null)

        assertEquals(200, state.targetPowerWatts.value)
        assertTrue(state.safetyKeyEngaged.value)
    }

    /** Records each callback with the engine state visible at that moment. */
    private class RecordingSink(private val state: BtEngineState) : BtServiceControlSink {
        val events = mutableListOf<String>()

        override fun startAdvertisingFtms(ftmsType: FtmsMachineProfile, capabilities: BtEngineState.FtmsCapabilities) = true
        override fun startAdvertisingCscCps() = Unit
        override fun stopAdvertising() = Unit
        override fun setNotifyIntervalMs(intervalMs: Long) = Unit
        override fun onControlPermissionLost() { events += "controlLost haveControl=${state.haveControlFlow.value}" }
        override fun onSafetyKeyPulled() { events += "safetyKey engaged=${state.safetyKeyEngaged.value}" }
        override fun onTargetSpeedChangedKmh(kmh: Float) { events += "speed $kmh state=${state.targetSpeedKmh.value}" }
        override fun onTargetInclineChangedPercent(percent: Float) { events += "incline $percent state=${state.targetInclinePercent.value}" }
        override fun onTargetResistanceLevelChanged(level: Float) { events += "resistance $level state=${state.targetResistanceLevel.value}" }
        override fun onTargetPowerChangedWatts(watts: Int) { events += "power $watts state=${state.targetPowerWatts.value}" }
        override fun onTargetHeartRateChangedBpm(bpm: Int) { events += "hr $bpm state=${state.targetHeartRateBpm.value}" }
    }
}
