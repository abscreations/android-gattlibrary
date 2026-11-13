package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.CommandResult
import com.gmail.absolutebollockscreations.gattlibrary.central.MachineIdentity
import com.gmail.absolutebollockscreations.gattlibrary.central.RejectionReason
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

    /**
     * A connected machine whose session transitions answer [startResult] / [stopResult]. Kayak First
     * accepts Start/Stop inside the central client, so to the sink it is a machine that answers Queued.
     */
    private class RecordingLink(
        private val startResult: CommandResult = CommandResult.Rejected(RejectionReason.NOT_ACCEPTED),
        private val stopResult: CommandResult = CommandResult.Rejected(RejectionReason.NOT_ACCEPTED),
    ) : UpstreamMachineLink {
        private val machine = UpstreamMachineRef("machine")
        var requestedSpeedKph: Float? = null
        var startCalls = 0
        var stopCalls = 0

        override val machineToken: UpstreamMachineRef = machine
        override val machineConnected: Boolean = true
        override fun machineIdentity(): MachineIdentity? = null
        override fun connectedAddresses(): Set<String> = emptySet()

        override fun startOrResume(token: UpstreamMachineRef): CommandResult { startCalls++; return startResult }
        override fun stopOrPause(token: UpstreamMachineRef): CommandResult { stopCalls++; return stopResult }
        override fun setTargetSpeedKph(token: UpstreamMachineRef, kph: Float): CommandResult {
            requestedSpeedKph = kph
            return CommandResult.Queued
        }
        override fun setTargetInclinePercent(token: UpstreamMachineRef, percent: Float) = CommandResult.Queued
        override fun setTargetResistanceLevel(token: UpstreamMachineRef, level: Float) = CommandResult.Queued
        override fun setTargetPowerWatts(token: UpstreamMachineRef, watts: Int) = CommandResult.Queued
        override fun setWheelCircumferenceMm(token: UpstreamMachineRef, millimetres: Float) = CommandResult.Queued
        override fun setIndoorBikeSimulation(
            token: UpstreamMachineRef,
            windSpeedMps: Float,
            gradePercent: Float,
            rollingResistanceCoefficient: Float,
            windResistanceCoefficient: Float,
        ) = CommandResult.Queued
    }

    @Test
    fun `measurement-only Kayak First session transitions succeed without opening targets`() {
        val upstream = RecordingLink(startResult = CommandResult.Queued, stopResult = CommandResult.Queued)
        val sink = ClientFtmsControlSink(upstream).apply { beginSession(TargetCapabilities.NONE) }
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
        val upstream = RecordingLink()
        val session = FtmsProcedureSession().apply { hasControl = true }
        val sink = ClientFtmsControlSink(upstream).apply { beginSession(TargetCapabilities.NONE) }
        val outcome = FtmsControlPointHandler(sink)
            .handle(byteArrayOf(FtmsSpec.Opcode.START_RESUME), session)

        assertEquals(FtmsSpec.Result.OPERATION_FAILED, outcome.response[2])
        assertEquals(1, upstream.startCalls)
    }

    @Test
    fun `downstream target writes upstream and publishes no optimistic status`() {
        val upstream = RecordingLink()
        val passThrough = ClientFtmsControlSink(upstream).apply {
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
    }

    // The upstream half (the machine's 2ADA Target Speed Changed reaching engine state and control
    // events, which the downstream mirror follows) and the target-incline status decoding moved to
    // gatt-central's FitnessMachineStatusTargetTest in LIB-76: they exercise central internals.
}
