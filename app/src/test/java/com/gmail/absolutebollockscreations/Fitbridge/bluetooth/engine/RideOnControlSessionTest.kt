package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.UUID

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
            assertEquals(RideOnUuids.SYNC_RX, uuid)
            assertEquals(GattWriteMode.WITHOUT_RESPONSE, mode)
            writes += payload.copyOf()
            onWrite(payload)
            return accept
        }
    }

    private val timing = RideOnSessionTiming(negotiationTimeoutMs = 160, retryIntervalMs = 10, writeTimeoutMs = 50)

    private suspend fun await(condition: () -> Boolean) {
        repeat(100) { if (condition()) return else delay(5) }
        error("condition not reached")
    }

    @Test
    fun `subscription gate requires both exact surfaces on the same generation`() {
        val id = ConnectionId("AA:BB:CC:DD:EE:82", 1)
        val gate = RideOnSubscriptionGate(id)
        assertFalse(gate.markReady(id, RideOnUuids.ASYNC_TX))
        assertFalse(gate.markReady(id.copy(linkGeneration = 2), RideOnUuids.SYNC_TX))
        assertFalse(gate.markReady(id, UUID.randomUUID()))
        assertTrue(gate.markReady(id, RideOnUuids.SYNC_TX))
    }

    @Test
    fun `original form negotiates only 02 02 then initializes in order`() = runBlocking {
        val transport = FakeTransport()
        lateinit var session: RideOnControlSession
        session = RideOnControlSession(transport, RideOnServiceForm.ORIGINAL, timing)
        transport.onWrite = { payload ->
            if (payload.contentEquals(RideOnUuids.HANDSHAKE_ORIGINAL)) {
                session.onPacket(transport.connectionId, RideOnUuids.SYNC_TX, payload)
            }
        }
        session.startAfterSubscriptionsReady()
        await { session.controlGranted }
        assertEquals(3, transport.writes.size)
        assertArrayEquals(RideOnUuids.HANDSHAKE_ORIGINAL, transport.writes[0])
        assertArrayEquals(byteArrayOf(0x41, 0x08, 0x05), transport.writes[1])
        assertArrayEquals(hex("00 08 00"), transport.writes[2])
        assertFalse(transport.writes.any { it.contentEquals(RideOnUuids.HANDSHAKE_CORE2) })
        session.close()
    }

    @Test
    fun `CORE2 accepts first valid protocol packet and riding data before general response`() = runBlocking {
        val transport = FakeTransport()
        val riding = mutableListOf<RideOnDecodedPacket>()
        lateinit var session: RideOnControlSession
        session = RideOnControlSession(transport, RideOnServiceForm.CORE2, timing, onRidingData = { riding += it })
        transport.onWrite = { payload ->
            if (payload.contentEquals(RideOnUuids.HANDSHAKE_CORE2)) {
                session.onPacket(transport.connectionId, RideOnUuids.ASYNC_TX, hex("03 08 64"))
            }
        }
        session.startAfterSubscriptionsReady()
        await { session.controlGranted }
        assertEquals(1, riding.size)
        assertArrayEquals(RideOnUuids.HANDSHAKE_CORE2, transport.writes.first())
        assertFalse(transport.writes.any { it.contentEquals(RideOnUuids.HANDSHAKE_ORIGINAL) })
        session.close()
    }

    @Test
    fun `wrong surface stale generation and malformed packet cannot negotiate`() = runBlocking {
        val transport = FakeTransport()
        lateinit var session: RideOnControlSession
        session = RideOnControlSession(transport, RideOnServiceForm.CORE2, timing)
        transport.onWrite = { payload ->
            if (payload.contentEquals(RideOnUuids.HANDSHAKE_CORE2)) {
                session.onPacket(transport.connectionId, UUID.randomUUID(), hex("03 08 01"))
                session.onPacket(transport.connectionId.copy(linkGeneration = 2), RideOnUuids.SYNC_TX, hex("03 08 01"))
                session.onPacket(transport.connectionId, RideOnUuids.SYNC_TX, hex("03 08 80"))
            }
        }
        session.startAfterSubscriptionsReady()
        await { transport.writes.size == 2 }
        delay(100)
        assertFalse(session.controlGranted)
        assertEquals(2, transport.writes.size)
        session.close()
    }

    @Test
    fun `failed initialization and close never grant authority`() = runBlocking {
        val transport = FakeTransport()
        var failed = 0
        lateinit var session: RideOnControlSession
        session = RideOnControlSession(
            transport, RideOnServiceForm.ORIGINAL, timing,
            onInitializationFailed = { failed++ },
        )
        transport.onWrite = { payload ->
            if (payload.contentEquals(RideOnUuids.HANDSHAKE_ORIGINAL)) {
                session.onPacket(transport.connectionId, RideOnUuids.SYNC_TX, payload)
                transport.accept = false
            }
        }
        session.startAfterSubscriptionsReady()
        await { failed == 1 }
        assertFalse(session.controlGranted)
        session.close()
        assertTrue(session.isClosed)
        assertFalse(session.startAfterSubscriptionsReady())
    }

    private fun hex(value: String) = value.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
