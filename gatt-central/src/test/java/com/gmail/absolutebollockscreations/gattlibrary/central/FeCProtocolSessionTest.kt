package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.FecBleUuids

class FeCProtocolSessionTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { (v[it] and 0xFF).toByte() }

    private fun newSession(): FeCProtocolSession =
        FeCProtocolSession(connectionId = ConnectionId("AA:BB:CC:DD:EE:FF", linkGeneration = 1L))

    private fun send(session: FeCProtocolSession, vararg page: Int) {
        session.onPacket(
            FecBleUuids.UUID_FEC_MEASUREMENT,
            FeCFrameCodec.encode(0x4E, 5, bytes(*page)),
            arrivalMs = 0L,
        )
    }

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
        session.onPacket(FecBleUuids.UUID_FEC_MEASUREMENT, payload, arrivalMs = 0L)

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
        session.onPacket(FecBleUuids.UUID_FEC_MEASUREMENT, page16(40), arrivalMs = 0L)
        assertEquals(10.0, session.snapshot.elapsedTimeSeconds!!, 1e-9)

        session.onPacket(FecBleUuids.UUID_FEC_MEASUREMENT, page16(44), arrivalMs = 1000L)
        assertEquals(11.0, session.snapshot.elapsedTimeSeconds!!, 1e-9)
    }

    @Test
    fun `malformed frame is ignored without throwing and without changing the snapshot`() {
        val session = newSession()
        val good = FeCFrameCodec.encode(0x4E, 5, bytes(0x10, 25, 40, 120, 0x88, 0x13, 145, 0x34))
        session.onPacket(FecBleUuids.UUID_FEC_MEASUREMENT, good, arrivalMs = 0L)
        val before = session.snapshot

        val corrupted = good.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        session.onPacket(FecBleUuids.UUID_FEC_MEASUREMENT, corrupted, arrivalMs = 1L)

        assertEquals(before, session.snapshot)
        assertNull(session.lastAppliedPage)
    }

    @Test
    fun `page 25 and common identity pages merge into the same snapshot as page 16`() {
        val session = newSession()
        session.onPacket(
            FecBleUuids.UUID_FEC_MEASUREMENT,
            FeCFrameCodec.encode(0x4E, 5, bytes(0x10, 25, 40, 120, 0x88, 0x13, 145, 0x34)),
            arrivalMs = 0L,
        )
        session.onPacket(
            FecBleUuids.UUID_FEC_MEASUREMENT,
            FeCFrameCodec.encode(0x4E, 5, bytes(0x19, 0x0A, 0x55, 0xB0, 0x04, 0xFA, 0x50, 0x31)),
            arrivalMs = 1L,
        )
        session.onPacket(
            FecBleUuids.UUID_FEC_MEASUREMENT,
            FeCFrameCodec.encode(0x4E, 5, bytes(0x50, 0xFF, 0xFF, 0x01, 0x59, 0x00, 0xF0, 0x0A)),
            arrivalMs = 2L,
        )
        session.onPacket(
            FecBleUuids.UUID_FEC_MEASUREMENT,
            FeCFrameCodec.encode(0x4E, 5, bytes(0x51, 0xFF, 0x01, 0x02, 0xEF, 0xCD, 0xAB, 0x89)),
            arrivalMs = 3L,
        )

        val snap = session.snapshot
        // From page 16, still present after page 25/81 arrived.
        assertEquals(145, snap.heartRateBpm)
        // From page 25.
        assertEquals(85, snap.cadenceRpm)
        assertEquals(250, snap.instantaneousPowerWatts)
        // From page 80.
        assertEquals(89, snap.manufacturerInfo?.manufacturerId)
        assertEquals(2800, snap.manufacturerInfo?.modelNumber)
        // From page 81.
        assertEquals(0x89ABCDEFL, snap.productInfo?.serialNumber)
    }

    @Test
    fun `metabolic calories and elliptical counters remain continuous across rollover`() {
        val session = newSession()
        send(session, 0x12, 0xFF, 0x52, 0x03, 0x05, 0x19, 254, 0x31)
        send(session, 0x12, 0xFF, 0x52, 0x03, 0x05, 0x19, 2, 0x31)
        assertEquals(258L, session.snapshot.accumulatedCalories)

        send(session, 0x14, 0xFF, 250, 254, 72, 220, 0, 0x33)
        send(session, 0x14, 0xFF, 2, 1, 72, 220, 0, 0x33)
        assertEquals(25.8, session.snapshot.positiveVerticalDistanceMeters!!, 1e-9)
        assertEquals(257L, session.snapshot.strideCount)
    }

    @Test
    fun `rower climber and Nordic counters use independent rollover domains`() {
        val session = newSession()
        send(session, 0x16, 0xFF, 0xFF, 250, 30, 200, 0, 0x31)
        send(session, 0x16, 0xFF, 0xFF, 3, 30, 200, 0, 0x31)
        send(session, 0x17, 0xFF, 0xFF, 200, 40, 44, 1, 0x31)
        send(session, 0x18, 0xFF, 0xFF, 100, 50, 144, 1, 0x31)

        assertEquals(259L, session.snapshot.strokeCount)
        assertEquals(200L, session.snapshot.climberCycleCount)
        assertEquals(100L, session.snapshot.strideCount)
    }

    @Test
    fun `trainer torque power uses rollover-safe deltas and ignores duplicate events`() {
        val session = newSession()
        send(session, 0x1A, 255, 255, 0xDC, 0xFF, 0xDC, 0xFF, 0x30)
        assertNull(session.snapshot.torquePowerWatts)

        send(session, 0x1A, 0, 0, 64, 0, 32, 0, 0x30)
        assertEquals(128.0 * Math.PI * 68.0 / 100.0, session.snapshot.torquePowerWatts!!, 1e-9)
        assertEquals(256L, session.snapshot.torqueEventCount)
        assertEquals(256L, session.snapshot.torqueWheelTicks)
        assertEquals(65_600L, session.snapshot.accumulatedWheelPeriodRaw)
        assertEquals(65_568L, session.snapshot.accumulatedTorqueRaw)

        send(session, 0x1A, 0, 0, 64, 0, 32, 0, 0x30)
        assertNull(session.snapshot.torquePowerWatts)
    }

    @Test
    fun `close discards state and further packets are ignored`() {
        val session = newSession()
        session.onPacket(
            FecBleUuids.UUID_FEC_MEASUREMENT,
            FeCFrameCodec.encode(0x4E, 5, bytes(0x10, 25, 40, 120, 0x88, 0x13, 145, 0x34)),
            arrivalMs = 0L,
        )
        assertEquals(25, session.snapshot.equipmentType)

        session.close()
        assertNull(session.snapshot.equipmentType)

        session.onPacket(
            FecBleUuids.UUID_FEC_MEASUREMENT,
            FeCFrameCodec.encode(0x4E, 5, bytes(0x10, 21, 1, 1, 0, 0, 100, 0x00)),
            arrivalMs = 5L,
        )
        assertFalse(session.snapshot.equipmentType == 21)
        assertNull(session.snapshot.equipmentType)
    }
}
