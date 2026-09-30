package com.gmail.absolutebollockscreations.gattlibrary.central

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.RideOnServiceForm
import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.RideOnSpec

class RideOnTargetMappingTest {
    private class Transport : GattTransport {
        override val connectionId = ConnectionId("AA:BB:CC:DD:EE:82", 1)
        val writes = Collections.synchronizedList(mutableListOf<ByteArray>())
        lateinit var session: RideOnControlSession
        override fun enableNotifications(uuid: UUID) = true
        override suspend fun write(uuid: UUID, payload: ByteArray, mode: GattWriteMode, timeoutMs: Long, operationName: String): Boolean {
            writes += payload.copyOf()
            if (payload.contentEquals(RideOnSpec.HANDSHAKE_CORE2)) {
                session.onPacket(connectionId, RideOnSpec.SYNC_TX, payload)
            }
            return true
        }
    }

    @Test
    fun `ERG bounds and exact 250 watt vector`() {
        assertArrayEquals(hex("04 18 FA 01"), encodeRideOnTarget(RideOnControlTarget.Erg(250)))
        assertTrue(encodeRideOnTarget(RideOnControlTarget.Erg(0)) != null)
        assertTrue(encodeRideOnTarget(RideOnControlTarget.Erg(2_000)) != null)
        assertEquals(null, encodeRideOnTarget(RideOnControlTarget.Erg(-1)))
        assertEquals(null, encodeRideOnTarget(RideOnControlTarget.Erg(2_001)))
    }

    @Test
    fun `simulation uses RideOn scales and signed ZigZag independently`() {
        val encoded = encodeRideOnTarget(RideOnControlTarget.Simulation(
            windMps = -1.25, gradePercent = -4.0, crr = 0.004, cw = 0.51,
            gearRatio = 2.4, bicycleMassKg = 10.0, riderMassKg = 75.0,
        ))!!
        val outer = decoded(encoded)
        val sim = decoded(byteArrayOf(0) + (outer.fields.first { it.number == 4 } as RideOnProtoField.Bytes).value)
        assertEquals(RideOnProtoCodec.zigZagEncode32(-125), value(sim, 1))
        assertEquals(RideOnProtoCodec.zigZagEncode32(-400), value(sim, 2))
        assertEquals(5_100, value(sim, 3))
        assertEquals(400, value(sim, 4)) // Crr ×100000, never legacy ×10000 (=40).
    }

    @Test
    fun `nonfinite negative unsigned and overflow simulation inputs reject`() {
        fun target(crr: Double = 0.004, rider: Double = 75.0) = RideOnControlTarget.Simulation(
            0.0, 0.0, crr, 0.51, null, 10.0, rider,
        )
        assertEquals(null, encodeRideOnTarget(target(Double.NaN)))
        assertEquals(null, encodeRideOnTarget(target(-0.1)))
        assertEquals(null, encodeRideOnTarget(target(rider = Double.MAX_VALUE)))
    }

    @Test
    fun `endpoint is negotiated-only publishes exact capabilities and never resistance`() = runBlocking {
        val transport = Transport()
        val timing = RideOnSessionTiming(200, 10, 50, ergMinIntervalMs = 1)
        val session = RideOnControlSession(transport, RideOnServiceForm.CORE2, timing)
        transport.session = session
        val endpoint = RideOnMachineControlEndpoint(session, riderMassKg = { 75.0 })
        assertFalse(endpoint.requestControl())
        assertFalse(endpoint.setTargetPowerWatts(250))
        session.startAfterSubscriptionsReady()
        repeat(100) { if (session.controlGranted) return@repeat else delay(5) }
        assertTrue(endpoint.requestControl())
        assertFalse(endpoint.setTargetResistance(50f))
        assertTrue(endpoint.setTargetPowerWatts(250))
        // `writes` is a Collections.synchronizedList: individual add()/get() calls are thread-safe,
        // but iteration (which `.any {}` performs via iterator()) is not - it must be wrapped in a
        // `synchronized(writes) { ... }` block itself, per the JDK's own synchronizedList contract,
        // or a concurrent write() append while this polls can throw ConcurrentModificationException.
        fun sawErgWrite() = synchronized(transport.writes) { transport.writes.any { it.contentEquals(hex("04 18 FA 01")) } }
        repeat(100) { if (sawErgWrite()) return@repeat else delay(5) }
        assertTrue(sawErgWrite())
        val caps = rideOnProviderCapabilities()
        assertTrue(caps.supportsPower && caps.supportsIndoorBikeSimulation)
        assertFalse(caps.supportsResistance || caps.supportsSpeed || caps.supportsInclination)
        session.close()
    }

    private fun decoded(packet: ByteArray) = (RideOnProtoCodec.decode(packet) as RideOnDecodeResult.Decoded).packet
    private fun value(packet: RideOnDecodedPacket, field: Int) =
        (packet.fields.first { it.number == field } as RideOnProtoField.Varint).value
    private fun hex(text: String) = text.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
