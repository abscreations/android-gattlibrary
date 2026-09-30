package com.gmail.absolutebollockscreations.gattlibrary.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FtmsPeripheralStatusRelayTest {

    @Test
    fun `connection and control history survive stale publication disconnect and reset until new session`() {
        val published = mutableListOf<FtmsPeripheralController.PeripheralStatus>()
        val relay = FtmsPeripheralStatusRelay(
            initial = FtmsPeripheralController.PeripheralStatus(clientAdvertising = true),
            enrich = { it }, onStatus = published::add,
        )
        val stale = relay.baseStatus()
        relay.publish(stale.copy(clientLinkConnected = true))
        relay.recordClientControl()
        relay.publish(stale.copy(clientLinkConnected = false))
        relay.refreshDiagnostics()
        assertTrue(published.last().clientEverConnected)
        assertTrue(published.last().clientControlReceived)
        assertEquals(FtmsClientConnectionStatus.DISCONNECTED, published.last().clientConnectionStatus())
        assertEquals(FtmsClientControlStatus.CLIENT_IS_CONTROLLING, published.last().clientControlStatus())

        relay.publish(relay.baseStatus().copy(clientLinkConnected = true))
        relay.publish(relay.baseStatus().copy()) // Procedure reset leaves receipt history intact.
        assertEquals(FtmsClientConnectionStatus.CONNECTED, relay.snapshot().clientConnectionStatus())
        assertTrue(relay.snapshot().clientControlReceived)

        relay.resetClientHistory()
        relay.publish(relay.baseStatus().copy(clientAdvertising = true))
        assertEquals(FtmsClientConnectionStatus.READY, relay.snapshot().clientConnectionStatus())
        assertEquals(FtmsClientControlStatus.NOT_CONTROLLABLE, relay.snapshot().clientControlStatus())
    }

    @Test
    fun `diagnostic refresh publishes current counters without losing lifecycle state`() {
        var packets = 0L
        var complete = 0L
        var fragmented = 0L
        var compacted = 0L
        val published = mutableListOf<FtmsPeripheralController.PeripheralStatus>()
        val relay = FtmsPeripheralStatusRelay(
            initial = FtmsPeripheralController.PeripheralStatus(connected = true),
            enrich = { base ->
                base.copy(
                    packetsSent = packets,
                    completeRecords = complete,
                    fragmentedRecords = fragmented,
                    compactedRecords = compacted,
                )
            },
            onStatus = published::add,
        )

        packets = 3
        complete = 1
        fragmented = 1
        compacted = 1
        relay.refreshDiagnostics()

        assertEquals(1, published.size)
        assertTrue(published.single().connected)
        assertEquals(3, published.single().packetsSent)
        assertEquals(1, published.single().completeRecords)
        assertEquals(1, published.single().fragmentedRecords)
        assertEquals(1, published.single().compactedRecords)

        relay.publish(relay.baseStatus().copy(subscribed = true))
        packets = 5
        complete = 2
        relay.refreshDiagnostics()

        assertTrue(published.last().connected)
        assertTrue(published.last().subscribed)
        assertEquals(5, published.last().packetsSent)
        assertEquals(2, relay.snapshot().completeRecords)
    }
}
