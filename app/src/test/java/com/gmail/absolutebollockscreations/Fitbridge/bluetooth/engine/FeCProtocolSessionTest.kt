package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class FeCProtocolSessionTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { (v[it] and 0xFF).toByte() }

    private fun newSession(): FeCProtocolSession =
        FeCProtocolSession(connectionId = ConnectionId("AA:BB:CC:DD:EE:FF", linkGeneration = 1L))

    @Test
    fun `ignores packets from a characteristic that is not FEC2`() {
        val session = newSession()
        val payload = FeCFrameCodec.encode(0x4E, 5, bytes(0x10, 25, 40, 120, 0x88, 0x13, 145, 0x34))
        session.onPacket(UUID.randomUUID(), payload, arrivalMs = 0L)
        assertNull(session.snapshot.equipmentType)
    }

    @Test
    fun `decodes a page 16 notification into the snapshot`() {
        val session = newSession()
        val payload = FeCFrameCodec.encode(0x4E, 5, bytes(0x10, 25, 40, 120, 0x88, 0x13, 145, 0x34))
        session.onPacket(BluetoothIds.UUID_FEC_MEASUREMENT, payload, arrivalMs = 0L)

        val snap = session.snapshot
        assertEquals(25, snap.equipmentType)
        assertEquals(10.0, snap.elapsedTimeSeconds!!, 1e-9)
        assertTrue(snap.distanceEnabled)
        assertEquals(120.0, snap.distanceMeters!!, 1e-9)
        assertEquals(5.0, snap.speedMetersPerSecond!!, 1e-9)
        assertEquals(145, snap.heartRateBpm)
        assertEquals(FeCEquipmentState.IN_USE, snap.equipmentState)
    }

    @Test
    fun `elapsed time accumulates across successive page 16 notifications`() {
        val session = newSession()
        fun page16(elapsedRaw: Int) = FeCFrameCodec.encode(
            0x4E,
            5,
            bytes(0x10, 25, elapsedRaw, 0, 0, 0, 0xFF, 0x00),
        )
        session.onPacket(BluetoothIds.UUID_FEC_MEASUREMENT, page16(40), arrivalMs = 0L)
        assertEquals(10.0, session.snapshot.elapsedTimeSeconds!!, 1e-9)

        session.onPacket(BluetoothIds.UUID_FEC_MEASUREMENT, page16(44), arrivalMs = 1000L)
        assertEquals(11.0, session.snapshot.elapsedTimeSeconds!!, 1e-9)
    }

    @Test
    fun `malformed frame is ignored without throwing and without changing the snapshot`() {
        val session = newSession()
        val good = FeCFrameCodec.encode(0x4E, 5, bytes(0x10, 25, 40, 120, 0x88, 0x13, 145, 0x34))
        session.onPacket(BluetoothIds.UUID_FEC_MEASUREMENT, good, arrivalMs = 0L)
        val before = session.snapshot

        val corrupted = good.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        session.onPacket(BluetoothIds.UUID_FEC_MEASUREMENT, corrupted, arrivalMs = 1L)

        assertEquals(before, session.snapshot)
    }

    @Test
    fun `page 25 and product info merge into the same snapshot as page 16`() {
        val session = newSession()
        session.onPacket(
            BluetoothIds.UUID_FEC_MEASUREMENT,
            FeCFrameCodec.encode(0x4E, 5, bytes(0x10, 25, 40, 120, 0x88, 0x13, 145, 0x34)),
            arrivalMs = 0L,
        )
        session.onPacket(
            BluetoothIds.UUID_FEC_MEASUREMENT,
            FeCFrameCodec.encode(0x4E, 5, bytes(0x19, 0x0A, 0x55, 0xB0, 0x04, 0xFA, 0x50, 0x31)),
            arrivalMs = 1L,
        )
        session.onPacket(
            BluetoothIds.UUID_FEC_MEASUREMENT,
            FeCFrameCodec.encode(0x4E, 5, bytes(0x51, 0xFF, 0x01, 0x02, 0xEF, 0xCD, 0xAB, 0x89)),
            arrivalMs = 2L,
        )

        val snap = session.snapshot
        // From page 16, still present after page 25/81 arrived.
        assertEquals(145, snap.heartRateBpm)
        // From page 25.
        assertEquals(85, snap.cadenceRpm)
        assertEquals(250, snap.instantaneousPowerWatts)
        // From page 81.
        assertEquals(0x89ABCDEFL, snap.productInfo?.serialNumber)
    }

    @Test
    fun `close discards state and further packets are ignored`() {
        val session = newSession()
        session.onPacket(
            BluetoothIds.UUID_FEC_MEASUREMENT,
            FeCFrameCodec.encode(0x4E, 5, bytes(0x10, 25, 40, 120, 0x88, 0x13, 145, 0x34)),
            arrivalMs = 0L,
        )
        assertEquals(25, session.snapshot.equipmentType)

        session.close()
        assertNull(session.snapshot.equipmentType)

        session.onPacket(
            BluetoothIds.UUID_FEC_MEASUREMENT,
            FeCFrameCodec.encode(0x4E, 5, bytes(0x10, 21, 1, 1, 0, 0, 100, 0x00)),
            arrivalMs = 5L,
        )
        assertFalse(session.snapshot.equipmentType == 21)
        assertNull(session.snapshot.equipmentType)
    }
}
