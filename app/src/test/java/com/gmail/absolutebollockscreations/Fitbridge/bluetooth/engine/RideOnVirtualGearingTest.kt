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

class RideOnVirtualGearingTest {
    private inner class Transport : GattTransport {
        override val connectionId = ConnectionId("AA:BB:CC:DD:EE:82", 1)
        val writes = Collections.synchronizedList(mutableListOf<ByteArray>())
        lateinit var session: RideOnControlSession
        var respondToShifts = true
        override fun enableNotifications(uuid: UUID) = true
        override suspend fun write(uuid: UUID, payload: ByteArray, mode: GattWriteMode, timeoutMs: Long, operationName: String): Boolean {
            writes += payload.copyOf()
            if (payload.contentEquals(RideOnUuids.HANDSHAKE_CORE2)) {
                session.onPacket(connectionId, RideOnUuids.SYNC_TX, payload)
            } else if ((payload[0].toInt() and 0xFF) == 0x3F && respondToShifts) {
                val decoded = (RideOnProtoCodec.decode(payload) as RideOnDecodeResult.Decoded).packet
                val sequence = (decoded.fields.first { it.number == 2 } as RideOnProtoField.Varint).value
                session.onPacket(connectionId, RideOnUuids.SYNC_TX, byteArrayOf(0x40, 0x10) + varint(sequence))
            }
            return true
        }
    }

    @Test
    fun `gear table is fixed monotonic and gear 12 sequence zero matches vector`() = runBlocking {
        assertEquals(24, RIDEON_GEAR_RATIOS.size)
        assertTrue(RIDEON_GEAR_RATIOS.zipWithNext().all { it.first < it.second })
        assertEquals(7500, RIDEON_GEAR_RATIOS.first())
        assertEquals(54899, RIDEON_GEAR_RATIOS.last())
        val transport = readyTransport()
        assertTrue(transport.session.submitVirtualGear(12))
        await { transport.writes.any { (it[0].toInt() and 0xFF) == 0x3F } }
        assertArrayEquals(hex("3F 08 01 10 00 22 04 38 C0 BB 01"), transport.writes.last())
        transport.session.close()
    }

    @Test
    fun `invalid gears send nothing and mismatched response timeout cannot block next shift`() = runBlocking {
        val transport = readyTransport(shiftTimeout = 20)
        assertFalse(transport.session.submitVirtualGear(0))
        assertFalse(transport.session.submitVirtualGear(25))
        val before = transport.writes.size
        transport.respondToShifts = false
        assertTrue(transport.session.submitVirtualGear(1))
        await { transport.writes.size == before + 1 }
        transport.session.onPacket(transport.connectionId, RideOnUuids.SYNC_TX, hex("40 10 63"))
        assertTrue(transport.session.submitVirtualGear(2))
        await { transport.writes.size == before + 2 }
        assertEquals(listOf(7500L, 8700L), transport.writes.takeLast(2).map(::ratio))
        transport.session.close()
    }

    @Test
    fun `accepted sequence wraps after uint32 maximum`() = runBlocking {
        val transport = readyTransport(initialSequence = 0xFFFF_FFFFL)
        transport.session.submitVirtualGear(1)
        await { transport.writes.count { (it[0].toInt() and 0xFF) == 0x3F } == 1 }
        transport.session.submitVirtualGear(2)
        await { transport.writes.count { (it[0].toInt() and 0xFF) == 0x3F } == 2 }
        val sequences = transport.writes.filter { (it[0].toInt() and 0xFF) == 0x3F }.map { packet ->
            val decoded = (RideOnProtoCodec.decode(packet) as RideOnDecodeResult.Decoded).packet
            (decoded.fields.first { it.number == 2 } as RideOnProtoField.Varint).value
        }
        assertEquals(listOf(0xFFFF_FFFFL, 0L), sequences)
        transport.session.close()
    }

    private suspend fun readyTransport(shiftTimeout: Long = 50, initialSequence: Long = 0): Transport {
        val transport = Transport()
        transport.session = RideOnControlSession(
            transport, RideOnServiceForm.CORE2,
            RideOnSessionTiming(200, 5, 50, 1, shiftTimeout),
            initialShiftSequence = initialSequence,
        )
        transport.session.startAfterSubscriptionsReady()
        await { transport.session.controlGranted }
        return transport
    }

    private suspend fun await(condition: () -> Boolean) {
        repeat(100) { if (condition()) return else delay(5) }
        error("condition not reached")
    }

    private fun ratio(packet: ByteArray): Long {
        val outer = (RideOnProtoCodec.decode(packet) as RideOnDecodeResult.Decoded).packet
        val nested = (outer.fields.first { it.number == 4 } as RideOnProtoField.Bytes).value
        val payload = (RideOnProtoCodec.decode(byteArrayOf(0) + nested) as RideOnDecodeResult.Decoded).packet
        return (payload.fields.first { it.number == 7 } as RideOnProtoField.Varint).value
    }
    private fun varint(value: Long): ByteArray {
        var v = value
        val out = mutableListOf<Byte>()
        do { var b = (v and 0x7F).toInt(); v = v ushr 7; if (v != 0L) b = b or 0x80; out += b.toByte() } while (v != 0L)
        return out.toByteArray()
    }
    private fun hex(text: String) = text.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
