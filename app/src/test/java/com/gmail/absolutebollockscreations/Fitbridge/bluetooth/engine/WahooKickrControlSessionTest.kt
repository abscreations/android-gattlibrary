package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class WahooKickrControlSessionTest {
    private class FakeTransport(
        override val connectionId: ConnectionId = ConnectionId("AA:BB:CC:DD:EE:10", 1L),
    ) : GattTransport {
        val writes: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
        var accepted = true
        var onWrite: suspend (ByteArray) -> Unit = {}

        override fun enableNotifications(uuid: UUID): Boolean = true

        override suspend fun write(
            uuid: UUID,
            payload: ByteArray,
            mode: GattWriteMode,
            timeoutMs: Long,
            operationName: String,
        ): Boolean {
            assertEquals(WahooKickrUuids.CONTROL, uuid)
            assertEquals(GattWriteMode.WITH_RESPONSE, mode)
            writes += payload.copyOf()
            onWrite(payload)
            return accepted
        }
    }

    private val timing = WahooKickrSessionTiming(
        unlockOverallTimeoutMs = 300,
        unlockAttemptIntervalMs = 20,
        attWriteTimeoutMs = 100,
        indicationTimeoutMs = 30,
        ergMinIntervalMs = 40,
    )

    private fun TestScope.acknowledgingSession(
        transport: FakeTransport,
        outcomes: MutableList<Pair<Int, WahooKickrCommandOutcome>> = mutableListOf(),
        acceptedCommands: MutableList<WahooKickrCommand> = mutableListOf(),
    ): WahooKickrControlSession {
        lateinit var session: WahooKickrControlSession
        session = WahooKickrControlSession(
            transport = transport,
            timing = timing,
            dispatcher = StandardTestDispatcher(testScheduler),
            monotonicMs = { testScheduler.currentTime },
            onOutcome = { opcode, outcome -> outcomes += opcode to outcome },
            onCommandAccepted = { command, _ -> acceptedCommands += command },
        )
        transport.onWrite = { payload ->
            val opcode = payload[0].toInt() and 0xFF
            session.onIndication(
                transport.connectionId,
                byteArrayOf(1, opcode.toByte(), 1, 0),
            )
        }
        return session
    }

    @Test
    fun `no unlock or target write occurs before confirmed CCCD readiness`() = runTest {
        val transport = FakeTransport()
        val session = acknowledgingSession(transport)

        assertFalse(session.submit(WahooKickrControlTarget.Erg(200)))
        advanceTimeBy(50)
        runCurrent()
        assertTrue(transport.writes.isEmpty())

        assertTrue(session.startAfterCccdReady())
        runCurrent()
        assertTrue(session.controlGranted)
        assertEquals(0x20, transport.writes.single()[0].toInt() and 0xFF)
        session.close()
    }

    @Test
    fun `unlock retries are bounded and silence never grants control`() = runTest {
        val transport = FakeTransport()
        val outcomes = mutableListOf<Pair<Int, WahooKickrCommandOutcome>>()
        val session = WahooKickrControlSession(
            transport,
            timing,
            dispatcher = StandardTestDispatcher(testScheduler),
            onOutcome = { op, result -> outcomes += op to result },
        )

        session.startAfterCccdReady()
        advanceUntilIdle()

        assertFalse(session.controlGranted)
        assertEquals(3, transport.writes.size)
        assertTrue(outcomes.all { it.second == WahooKickrCommandOutcome.REJECTED })
        session.close()
    }

    @Test
    fun `stale-generation and wrong-opcode indications cannot unlock`() = runTest {
        val transport = FakeTransport()
        lateinit var session: WahooKickrControlSession
        session = WahooKickrControlSession(
            transport,
            timing,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        transport.onWrite = {
            session.onIndication(
                transport.connectionId.copy(linkGeneration = 2L),
                byteArrayOf(1, 0x20, 1, 0),
            )
            session.onIndication(transport.connectionId, byteArrayOf(1, 0x42, 1, 0))
        }

        session.startAfterCccdReady()
        advanceUntilIdle()
        assertEquals(WahooKickrUuids.UNLOCK_MAX_ATTEMPTS, transport.writes.size)
        assertFalse(session.controlGranted)
        session.close()
    }

    @Test
    fun `ATT success plus indication silence is accepted-unconfirmed after unlock`() = runTest {
        val transport = FakeTransport()
        val outcomes = mutableListOf<Pair<Int, WahooKickrCommandOutcome>>()
        val session = acknowledgingSession(transport, outcomes)
        session.startAfterCccdReady()
        runCurrent()
        assertTrue(session.controlGranted)
        transport.onWrite = {}

        assertTrue(session.submit(WahooKickrControlTarget.Erg(250)))
        runCurrent()
        advanceTimeBy(timing.indicationTimeoutMs)
        runCurrent()

        assertEquals(WahooKickrControlMode.ERG, session.mode)
        assertTrue(outcomes.contains(0x42 to WahooKickrCommandOutcome.ACCEPTED_UNCONFIRMED))
        session.close()
    }

    @Test
    fun `ATT failure does not commit a new mode`() = runTest {
        val transport = FakeTransport()
        val session = acknowledgingSession(transport)
        session.startAfterCccdReady()
        runCurrent()
        assertTrue(session.controlGranted)
        transport.accepted = false

        assertTrue(session.submit(WahooKickrControlTarget.Resistance(0.5)))
        runCurrent()

        assertTrue(transport.writes.any { (it[0].toInt() and 0xFF) == 0x40 })
        assertEquals(WahooKickrControlMode.NONE, session.mode)
        session.close()
    }

    @Test
    fun `pending targets coalesce to the latest while a write is in flight`() = runTest {
        val transport = FakeTransport()
        val session = acknowledgingSession(transport)
        session.startAfterCccdReady()
        runCurrent()
        assertTrue(session.controlGranted)

        val gate = CompletableDeferred<Unit>()
        transport.onWrite = { payload ->
            if ((payload[0].toInt() and 0xFF) == 0x42 && !gate.isCompleted) gate.await()
        }
        session.submit(WahooKickrControlTarget.Erg(100))
        runCurrent()
        assertEquals(1, transport.writes.count { (it[0].toInt() and 0xFF) == 0x42 })
        session.submit(WahooKickrControlTarget.Erg(200))
        session.submit(WahooKickrControlTarget.Erg(300))
        gate.complete(Unit)

        // The first write becomes accepted-unconfirmed, then ERG pacing admits the latest target.
        advanceUntilIdle()
        assertEquals(2, transport.writes.count { (it[0].toInt() and 0xFF) == 0x42 })
        val ergWrites = transport.writes.filter { (it[0].toInt() and 0xFF) == 0x42 }
        assertEquals(listOf(100, 300), ergWrites.map { (it[1].toInt() and 0xFF) or ((it[2].toInt() and 0xFF) shl 8) })
        session.close()
    }

    @Test
    fun `simulation transaction is atomic before a newly pending resistance target`() = runTest {
        val transport = FakeTransport()
        lateinit var session: WahooKickrControlSession
        session = acknowledgingSession(transport)
        session.startAfterCccdReady()
        runCurrent()
        assertTrue(session.controlGranted)

        transport.onWrite = { payload ->
            val opcode = payload[0].toInt() and 0xFF
            session.onIndication(transport.connectionId, byteArrayOf(1, opcode.toByte(), 1, 0))
            if (opcode == 0x46) session.submit(WahooKickrControlTarget.Resistance(0.5))
        }
        session.submit(WahooKickrControlTarget.Simulation(85.0, 0.004, 0.51, 5.0, 0.0))
        runCurrent()

        assertTrue(transport.writes.any { (it[0].toInt() and 0xFF) == 0x40 })
        assertEquals(
            listOf(0x20, 0x43, 0x46, 0x47, 0x40),
            transport.writes.map { it[0].toInt() and 0xFF },
        )
        session.close()
    }

    @Test
    fun `close clears authority and rejects future submissions`() = runTest {
        val transport = FakeTransport()
        val session = acknowledgingSession(transport)
        session.startAfterCccdReady()
        runCurrent()
        assertTrue(session.controlGranted)
        session.close()

        assertTrue(session.isClosed)
        assertFalse(session.controlGranted)
        assertEquals(WahooKickrControlMode.NONE, session.mode)
        assertFalse(session.submit(WahooKickrControlTarget.Erg(200)))
    }

    @Test
    fun `machine endpoint stays closed until unlock and never exposes motion targets`() = runTest {
        val session = acknowledgingSession(FakeTransport())
        val endpoint = WahooKickrMachineControlEndpoint(session) { 75.0 }

        assertFalse(endpoint.requestControl())
        assertFalse(endpoint.startOrResume())
        assertFalse(endpoint.stopOrPause())
        assertFalse(endpoint.setTargetSpeedKph(20f))
        assertFalse(endpoint.setTargetInclinationPercent(5f))
        assertFalse(endpoint.setTargetPowerWatts(200))
        session.close()
    }

    @Test
    fun `machine endpoint maps resistance erg simulation and circumference to approved commands`() = runTest {
        val transport = FakeTransport()
        val acceptedCommands = mutableListOf<WahooKickrCommand>()
        val session = acknowledgingSession(transport, acceptedCommands = acceptedCommands)
        val endpoint = WahooKickrMachineControlEndpoint(session) { 75.0 }
        session.startAfterCccdReady()
        runCurrent()
        assertTrue(session.controlGranted)

        assertTrue(endpoint.setTargetResistance(50f))
        runCurrent()
        assertEquals(2, transport.writes.size)
        assertTrue(endpoint.setTargetPowerWatts(250))
        runCurrent()
        assertEquals(3, transport.writes.size)
        assertTrue(endpoint.setIndoorBikeSimulation(1.5f, 4f, 0.004f, 0.51f))
        runCurrent()
        assertEquals(6, transport.writes.size)
        assertTrue(endpoint.setWheelCircumferenceMm(2107.4f))
        runCurrent()
        assertEquals(7, transport.writes.size)

        assertEquals(listOf(0x20, 0x40, 0x42, 0x43, 0x46, 0x47, 0x48),
            transport.writes.map { it[0].toInt() and 0xFF })
        assertEquals(
            (WahooKickrCodec.encode(WahooKickrCommand.Resistance(0.5)) as WahooKickrEncodeResult.Encoded)
                .bytes.toList(),
            transport.writes[1].toList(),
        )
        assertEquals(
            (WahooKickrCodec.encode(WahooKickrCommand.Simulation(85.0, 0.004, 0.51)) as
                WahooKickrEncodeResult.Encoded).bytes.toList(),
            transport.writes[3].toList(),
        )
        assertTrue(acceptedCommands.last() is WahooKickrCommand.WheelCircumference)
        assertTrue(endpoint.stopOrPause())
        session.close()
    }
}
