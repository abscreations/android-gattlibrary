package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Upstream Fitness Machine Status (2ADA) target reports reaching engine state and the public control
 * events the downstream mirror follows. Moved from gatt-peripheral's FtmsPassThroughCharacterizationTest
 * in LIB-76; that test keeps the pass-through half (the downstream write reaches the upstream machine and
 * no optimistic status is published).
 */
class FitnessMachineStatusTargetTest {

    /** Records the control events the engine publishes for a status, standing in for the downstream mirror. */
    private class RecordingStatusSink(state: BtEngineState) {
        var targetSpeedKph: Float? = null
        var targetInclinePercent: Float? = null
        var inclineCallbacks = 0

        init {
            CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
                state.controlEvents.collect { event ->
                    when (event) {
                        is ControlEvent.TargetSpeedKph -> targetSpeedKph = event.kph
                        is ControlEvent.TargetInclinePercent -> {
                            targetInclinePercent = event.percent
                            inclineCallbacks++
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    @Test
    fun `upstream 2ADA target speed status reaches state and control events`() {
        val engineState = BtEngineState()
        val downstreamStatus = RecordingStatusSink(engineState)
        assertNull(downstreamStatus.targetSpeedKph)

        applyFitnessMachineStatus(
            engineState,
            byteArrayOf(FtmsSpec.Status.TARGET_SPEED_CHANGED, 0xD2.toByte(), 0x04),
        )

        assertEquals(12.34f, engineState.targetSpeedKmh.value!!, 0.001f)
        assertEquals(12.34f, downstreamStatus.targetSpeedKph!!, 0.001f)
    }

    @Test
    fun `target incline status decodes sint16 percent including a decline`() {
        val state = BtEngineState()
        val sink = RecordingStatusSink(state)

        // -5.0 % grade = -50 = 0xFFCE little-endian. Read unsigned this reported 6553.5 %.
        applyFitnessMachineStatus(
            state,
            byteArrayOf(FtmsSpec.Status.TARGET_INCLINE_CHANGED, 0xCE.toByte(), 0xFF.toByte()),
        )

        assertEquals(-5.0f, state.targetInclinePercent.value!!, 0.001f)
        assertEquals(-5.0f, sink.targetInclinePercent!!, 0.001f)

        // +10.0 % grade = 100 = 0x0064.
        applyFitnessMachineStatus(
            state,
            byteArrayOf(FtmsSpec.Status.TARGET_INCLINE_CHANGED, 0x64, 0x00),
        )

        assertEquals(10.0f, state.targetInclinePercent.value!!, 0.001f)
        assertEquals(10.0f, sink.targetInclinePercent!!, 0.001f)
    }

    @Test
    fun `truncated target incline status is rejected rather than reported as zero`() {
        val state = BtEngineState()
        val sink = RecordingStatusSink(state)

        applyFitnessMachineStatus(
            state,
            byteArrayOf(FtmsSpec.Status.TARGET_INCLINE_CHANGED, 0xCE.toByte()),
        )

        // Zero is a valid movement target, so a short payload must not synthesise one.
        assertNull(state.targetInclinePercent.value)
        assertEquals(0, sink.inclineCallbacks)
    }
}
