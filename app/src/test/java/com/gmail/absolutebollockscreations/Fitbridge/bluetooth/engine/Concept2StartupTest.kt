package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.*
import org.junit.Test

class Concept2StartupTest {
    private fun hex(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `startup requires a writable request and notifiable response with CCCD`() {
        assertEquals(StackEntry.WriteMode.WITH_RESPONSE, concept2StartupWriteMode(0x0C, 0x10, true))
        assertEquals(StackEntry.WriteMode.WITHOUT_RESPONSE, concept2StartupWriteMode(0x04, 0x10, true))
        assertNull(concept2StartupWriteMode(0x02, 0x10, true))
        assertNull(concept2StartupWriteMode(0x0C, 0x02, true))
        assertNull(concept2StartupWriteMode(0x0C, 0x10, false))
    }

    @Test
    fun `startup sends only the documented Just Row splits and Prepare frame once`() {
        val startup = Concept2StartupHandshake()
        assertArrayEquals(hex("01 F1 76 07 01 01 01 13 02 01 01 61 F2"), startup.issue(100))
        assertNull(startup.issue(200))
        assertTrue(startup.acceptAcknowledgement(hex("F1 89 76 02 01 13 EF F2")))
        assertEquals(Concept2StartupHandshake.State.ACKNOWLEDGED, startup.state)
        assertFalse(startup.acceptAcknowledgement(hex("F1 89 76 02 01 13 EF F2")))
    }

    @Test
    fun `startup rejects truncated corrupt and unrelated replies and times out without retry`() {
        val startup = Concept2StartupHandshake()
        startup.issue(100)
        assertFalse(startup.acceptAcknowledgement(hex("F1 89 76 02 01 13 EE F2")))
        assertFalse(startup.acceptAcknowledgement(hex("F1 89 76 01 13 ED F2")))
        assertFalse(startup.acceptAcknowledgement(hex("F1 89 76 02 01 13 EF")))
        startup.tick(100 + Concept2StartupHandshake.ACK_TIMEOUT_MS)
        assertEquals(Concept2StartupHandshake.State.TIMED_OUT, startup.state)
        assertNull(startup.issue(20_000))
        assertFalse(startup.acceptAcknowledgement(hex("F1 89 76 02 01 13 EF F2")))
    }

    @Test
    fun `write failure is terminal and a new link has independent startup state`() {
        val first = Concept2StartupHandshake()
        first.issue(0)
        first.writeCompleted(false)
        assertEquals(Concept2StartupHandshake.State.FAILED, first.state)
        assertNull(first.issue(100))
        assertNotNull(Concept2StartupHandshake().issue(100))
    }

    @Test
    fun `distance floor applies only when the first reading exceeds fifty metres`() {
        for (first in listOf(0.0, 49.9, 50.0)) {
            val origin = Concept2DistanceOrigin()
            assertEquals(first, origin.align(first), 1e-9)
            assertEquals(first + 1.0, origin.align(first + 1.0), 1e-9)
            assertEquals(0.0, origin.floorMetres, 1e-9)
        }
        val origin = Concept2DistanceOrigin()
        assertEquals(0.0, origin.align(3114.9), 1e-9)
        assertEquals(3114.9, origin.floorMetres, 1e-9)
        assertEquals(10.0, origin.align(3124.9), 1e-9)
        assertEquals(0.0, origin.align(0.0), 1e-9)
        assertEquals(0.0, origin.floorMetres, 1e-9)
        assertEquals(200.0, origin.align(200.0), 1e-9)
    }

    @Test
    fun `fusion keeps raw PM5 evidence but projects aligned distance across reset and source change`() {
        val state = BtEngineState()
        val fusion = MetricFusion(state)
        fusion.startSession(FusionPersona.ROW_PADDLE, 0)
        val connection = ConnectionId("PM5_TEST", 1)
        fun emit(distance: Double, at: Long, mux: Boolean = true) {
            val emitter = MetricEmitter(fusion, MetricSourceId(connection, MetricProtocol.VENDOR,
                if (mux) BluetoothIds.UUID_C2_MULTIPLEXED_INFORMATION else BluetoothIds.UUID_C2_GENERAL_STATUS),
                MetricSourceClass.VENDOR_NATIVE, at)
            emitter.emit(MetricKind.DISTANCE, distance, nativeCumulative = distance)
        }
        emit(3114.9, 100)
        assertEquals(0, state.totalDistanceMeters.value)
        assertEquals(3114.9, fusion.latestSample(MetricKind.DISTANCE)!!.nativeCumulative!!, 1e-9)
        emit(3124.9, 1_000)
        assertEquals(10, state.totalDistanceMeters.value)
        emit(3124.9, 2_000, mux = false)
        assertEquals(10, state.totalDistanceMeters.value)
        emit(3125.9, 3_000, mux = false)
        assertEquals(11, state.totalDistanceMeters.value)
        emit(0.0, 4_000, mux = false)
        emit(200.0, 64_000, mux = false)
        assertEquals(211, state.totalDistanceMeters.value)
        fusion.startSession(FusionPersona.ROW_PADDLE, 65_000)
        emit(200.0, 66_000, mux = false)
        assertEquals(0, state.totalDistanceMeters.value)
    }
}
