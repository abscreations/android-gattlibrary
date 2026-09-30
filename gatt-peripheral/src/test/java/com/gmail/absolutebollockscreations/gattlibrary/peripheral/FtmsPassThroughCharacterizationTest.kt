package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.BtEngineState
import com.gmail.absolutebollockscreations.gattlibrary.central.BtServiceControlSink
import com.gmail.absolutebollockscreations.gattlibrary.central.BtServiceEngineFacade
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import com.gmail.absolutebollockscreations.gattlibrary.central.applyFitnessMachineStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsSpec
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsControlPointHandler
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsProcedureSession
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsTargetRange
import com.gmail.absolutebollockscreations.gattlibrary.server.TargetCapabilities

class FtmsPassThroughCharacterizationTest {

    private class RecordingEngineFacade : BtServiceEngineFacade {
        override val isFtmsConnected: Boolean = true
        override val engineState = BtEngineState()
        override var handlesDownstreamSessionTransitionsLocally: Boolean = false
        var requestedSpeedKph: Float? = null
        var startCalls = 0
        var stopCalls = 0

        override fun buildSupportedSpeedRange() = ByteArray(0)
        override fun buildSupportedInclineRange() = ByteArray(0)
        override fun buildSupportedResistanceRange() = ByteArray(0)
        override fun buildSupportedHeartRateRange() = ByteArray(0)
        override fun buildSupportedPowerRange() = ByteArray(0)
        override fun startOrResume(): Boolean {
            startCalls++
            return false
        }
        override fun stopOrPause(): Boolean {
            stopCalls++
            return false
        }
        override fun setTargetSpeedKph(kph: Float): Boolean {
            requestedSpeedKph = kph
            return true
        }
        override fun setTargetInclinationPercent(percent: Float) = true
        override fun setTargetResistance(level: Float) = true
        override fun setTargetPowerWatts(watts: Int) = true
        override fun setIndoorBikeSimulation(
            windSpeedMps: Float,
            gradePercent: Float,
            rollingResistanceCoefficient: Float,
            windResistanceCoefficient: Float,
        ) = true
    }

    @Test
    fun `measurement-only Kayak First session transitions terminate locally without opening targets`() {
        val upstream = RecordingEngineFacade().apply {
            handlesDownstreamSessionTransitionsLocally = true
        }
        val sink = EngineFtmsControlSink { upstream }
        val handler = FtmsControlPointHandler(sink)
        val session = FtmsProcedureSession()

        assertEquals(
            FtmsSpec.Result.SUCCESS,
            handler.handle(byteArrayOf(FtmsSpec.Opcode.REQUEST_CONTROL), session).response[2],
        )
        assertEquals(
            FtmsSpec.Result.SUCCESS,
            handler.handle(byteArrayOf(FtmsSpec.Opcode.START_RESUME), session).response[2],
        )
        assertEquals(
            FtmsSpec.Result.SUCCESS,
            handler.handle(byteArrayOf(FtmsSpec.Opcode.STOP_PAUSE, 0x01), session).response[2],
        )
        assertEquals(0, upstream.startCalls)
        assertEquals(0, upstream.stopCalls)
        assertFalse(sink.targetCapabilities.any)
        assertEquals(
            FtmsSpec.Result.OPCODE_NOT_SUPPORTED,
            handler.handle(
                byteArrayOf(FtmsSpec.Opcode.SET_TARGET_SPEED, 0xE8.toByte(), 0x03),
                session,
            ).response[2],
        )
        assertNull(upstream.requestedSpeedKph)
    }

    @Test
    fun `connected pass-through still propagates upstream session failure`() {
        val upstream = RecordingEngineFacade()
        val session = FtmsProcedureSession().apply { hasControl = true }
        val outcome = FtmsControlPointHandler(EngineFtmsControlSink { upstream })
            .handle(byteArrayOf(FtmsSpec.Opcode.START_RESUME), session)

        assertEquals(FtmsSpec.Result.OPERATION_FAILED, outcome.response[2])
        assertEquals(1, upstream.startCalls)
    }

    private class RecordingStatusSink : BtServiceControlSink {
        var targetSpeedKph: Float? = null
        var targetInclinePercent: Float? = null
        var inclineCallbacks = 0

        override fun startAdvertisingFtms(
            ftmsType: FtmsMachineProfile,
            capabilities: BtEngineState.FtmsCapabilities,
        ) = true
        override fun startAdvertisingCscCps() = Unit
        override fun stopAdvertising() = Unit
        override fun setNotifyIntervalMs(intervalMs: Long) = Unit
        override fun onControlPermissionLost() = Unit
        override fun onSafetyKeyPulled() = Unit
        override fun onTargetSpeedChangedKmh(kmh: Float) {
            targetSpeedKph = kmh
        }
        override fun onTargetInclineChangedPercent(percent: Float) {
            targetInclinePercent = percent
            inclineCallbacks++
        }
        override fun onTargetResistanceLevelChanged(level: Float) = Unit
        override fun onTargetPowerChangedWatts(watts: Int) = Unit
        override fun onTargetHeartRateChangedBpm(bpm: Int) = Unit
    }

    @Test
    fun `downstream target writes upstream and status waits for upstream 2ADA`() {
        val upstream = RecordingEngineFacade()
        val passThrough = EngineFtmsControlSink { upstream }.apply {
            beginSession(
                TargetCapabilities(
                    speed = true,
                    speedRange = FtmsTargetRange(0, 5_000, 1),
                ),
            )
        }
        val session = FtmsProcedureSession().apply { hasControl = true }
        val request = byteArrayOf(FtmsSpec.Opcode.SET_TARGET_SPEED, 0xD2.toByte(), 0x04)

        val outcome = FtmsControlPointHandler(passThrough).handle(request, session)

        assertArrayEquals(
            byteArrayOf(
                FtmsSpec.Opcode.RESPONSE_CODE,
                FtmsSpec.Opcode.SET_TARGET_SPEED,
                FtmsSpec.Result.SUCCESS,
            ),
            outcome.response,
        )
        assertEquals(12.34f, upstream.requestedSpeedKph!!, 0.001f)
        assertNull("pass-through must not publish optimistic status", outcome.status)

        val downstreamStatus = RecordingStatusSink()
        assertNull(downstreamStatus.targetSpeedKph)

        applyFitnessMachineStatus(
            upstream.engineState,
            downstreamStatus,
            byteArrayOf(FtmsSpec.Status.TARGET_SPEED_CHANGED, 0xD2.toByte(), 0x04),
        )

        assertEquals(12.34f, upstream.engineState.targetSpeedKmh.value!!, 0.001f)
        assertEquals(12.34f, downstreamStatus.targetSpeedKph!!, 0.001f)
    }

    @Test
    fun `target incline status decodes sint16 percent including a decline`() {
        val state = BtEngineState()
        val sink = RecordingStatusSink()

        // -5.0 % grade = -50 = 0xFFCE little-endian. Read unsigned this reported 6553.5 %.
        applyFitnessMachineStatus(
            state,
            sink,
            byteArrayOf(FtmsSpec.Status.TARGET_INCLINE_CHANGED, 0xCE.toByte(), 0xFF.toByte()),
        )

        assertEquals(-5.0f, state.targetInclinePercent.value!!, 0.001f)
        assertEquals(-5.0f, sink.targetInclinePercent!!, 0.001f)

        // +10.0 % grade = 100 = 0x0064.
        applyFitnessMachineStatus(
            state,
            sink,
            byteArrayOf(FtmsSpec.Status.TARGET_INCLINE_CHANGED, 0x64, 0x00),
        )

        assertEquals(10.0f, state.targetInclinePercent.value!!, 0.001f)
        assertEquals(10.0f, sink.targetInclinePercent!!, 0.001f)
    }

    @Test
    fun `truncated target incline status is rejected rather than reported as zero`() {
        val state = BtEngineState()
        val sink = RecordingStatusSink()

        applyFitnessMachineStatus(
            state,
            sink,
            byteArrayOf(FtmsSpec.Status.TARGET_INCLINE_CHANGED, 0xCE.toByte()),
        )

        // Zero is a valid movement target, so a short payload must not synthesise one.
        assertNull(state.targetInclinePercent.value)
        assertEquals(0, sink.inclineCallbacks)
    }
}
