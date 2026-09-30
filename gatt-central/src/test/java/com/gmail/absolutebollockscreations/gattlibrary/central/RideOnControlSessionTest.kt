package com.gmail.absolutebollockscreations.gattlibrary.central

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.RideOnServiceForm
import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.RideOnSpec

@OptIn(ExperimentalCoroutinesApi::class)
class RideOnControlSessionTest {
    private class FakeTransport(
        override val connectionId: ConnectionId = ConnectionId("AA:BB:CC:DD:EE:82", 1),
    ) : GattTransport {
        val writes = Collections.synchronizedList(mutableListOf<ByteArray>())
        var accept = true
        var onWrite: suspend (ByteArray) -> Unit = {}
        override fun enableNotifications(uuid: UUID) = true
        override suspend fun write(
            uuid: UUID,
            payload: ByteArray,
            mode: GattWriteMode,
            timeoutMs: Long,
            operationName: String,
        ): Boolean {
            assertEquals(RideOnSpec.SYNC_RX, uuid)
            assertEquals(GattWriteMode.WITHOUT_RESPONSE, mode)
            writes += payload.copyOf()
            onWrite(payload)
            return accept
        }
    }

    private val timing = RideOnSessionTiming(negotiationTimeoutMs = 160, retryIntervalMs = 10, writeTimeoutMs = 50)

    @Test
    fun `subscription gate requires both exact surfaces on the same generation`() {
        val id = ConnectionId("AA:BB:CC:DD:EE:82", 1)
        val gate = RideOnSubscriptionGate(id)
        assertFalse(gate.markReady(id, RideOnSpec.ASYNC_TX))
        assertFalse(gate.markReady(id.copy(linkGeneration = 2), RideOnSpec.SYNC_TX))
        assertFalse(gate.markReady(id, UUID.randomUUID()))
        assertTrue(gate.markReady(id, RideOnSpec.SYNC_TX))
    }

    @Test
    fun `original form negotiates only 02 02 then initializes in order`() = runTest {
        val transport = FakeTransport()
        lateinit var session: RideOnControlSession
        session = RideOnControlSession(
            transport,
            RideOnServiceForm.ORIGINAL,
            timing,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        transport.onWrite = { payload ->
            if (payload.contentEquals(RideOnSpec.HANDSHAKE_ORIGINAL)) {
                session.onPacket(transport.connectionId, RideOnSpec.SYNC_TX, payload)
            }
        }
        session.startAfterSubscriptionsReady()
        runCurrent()
        assertTrue(session.controlGranted)
        assertEquals(3, transport.writes.size)
        assertArrayEquals(RideOnSpec.HANDSHAKE_ORIGINAL, transport.writes[0])
        assertArrayEquals(byteArrayOf(0x41, 0x08, 0x05), transport.writes[1])
        assertArrayEquals(hex("00 08 00"), transport.writes[2])
        assertFalse(transport.writes.any { it.contentEquals(RideOnSpec.HANDSHAKE_CORE2) })
        session.close()
    }

    @Test
    fun `CORE2 accepts first valid protocol packet and riding data before general response`() = runTest {
        val transport = FakeTransport()
        val riding = mutableListOf<RideOnTelemetry>()
        lateinit var session: RideOnControlSession
        session = RideOnControlSession(
            transport,
            RideOnServiceForm.CORE2,
            timing,
            dispatcher = StandardTestDispatcher(testScheduler),
            onRidingData = { riding += it },
        )
        transport.onWrite = { payload ->
            if (payload.contentEquals(RideOnSpec.HANDSHAKE_CORE2)) {
                session.onPacket(transport.connectionId, RideOnSpec.ASYNC_TX, hex("03 08 64"))
            }
        }
        session.startAfterSubscriptionsReady()
        runCurrent()
        assertTrue(session.controlGranted)
        assertEquals(1, riding.size)
        assertArrayEquals(RideOnSpec.HANDSHAKE_CORE2, transport.writes.first())
        assertFalse(transport.writes.any { it.contentEquals(RideOnSpec.HANDSHAKE_ORIGINAL) })
        session.close()
    }

    @Test
    fun `wrong surface stale generation and malformed packet cannot negotiate`() = runTest {
        val transport = FakeTransport()
        lateinit var session: RideOnControlSession
        session = RideOnControlSession(
            transport,
            RideOnServiceForm.CORE2,
            timing,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        transport.onWrite = { payload ->
            if (payload.contentEquals(RideOnSpec.HANDSHAKE_CORE2)) {
                session.onPacket(transport.connectionId, UUID.randomUUID(), hex("03 08 01"))
                session.onPacket(transport.connectionId.copy(linkGeneration = 2), RideOnSpec.SYNC_TX, hex("03 08 01"))
                session.onPacket(transport.connectionId, RideOnSpec.SYNC_TX, hex("03 08 80"))
            }
        }
        session.startAfterSubscriptionsReady()
        advanceUntilIdle()
        assertFalse(session.controlGranted)
        assertEquals(2, transport.writes.size)
        session.close()
    }

    @Test
    fun `failed initialization and close never grant authority`() = runTest {
        val transport = FakeTransport()
        var failed = 0
        lateinit var session: RideOnControlSession
        session = RideOnControlSession(
            transport, RideOnServiceForm.ORIGINAL, timing,
            dispatcher = StandardTestDispatcher(testScheduler),
            onInitializationFailed = { failed++ },
        )
        transport.onWrite = { payload ->
            if (payload.contentEquals(RideOnSpec.HANDSHAKE_ORIGINAL)) {
                session.onPacket(transport.connectionId, RideOnSpec.SYNC_TX, payload)
                transport.accept = false
            }
        }
        session.startAfterSubscriptionsReady()
        advanceUntilIdle()
        assertEquals(1, failed)
        assertFalse(session.controlGranted)
        session.close()
        assertTrue(session.isClosed)
        assertFalse(session.startAfterSubscriptionsReady())
    }

    private fun hex(value: String) = value.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
