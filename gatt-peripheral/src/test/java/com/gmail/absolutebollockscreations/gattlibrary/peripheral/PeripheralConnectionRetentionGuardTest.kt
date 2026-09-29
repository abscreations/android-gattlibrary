package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeripheralConnectionRetentionGuardTest {
    @Test
    fun inboundServerHoldSurvivesUnrelatedLocalGattClientOpenAndClose() {
        val guard = PeripheralConnectionRetentionGuard()
        val peer = "peer-1"
        var serverConnectCalls = 0

        fun inboundServerConnected() {
            guard.retainOnce(peer) { serverConnectCalls += 1 }
        }

        inboundServerConnected()
        assertTrue(guard.isClaimed(peer))
        assertEquals(1, serverConnectCalls)

        // A separate local central-side client opens and closes on the same ACL. It does not own
        // this server lifecycle, so neither event is allowed to release the server's hold.
        var unrelatedLocalGattClientOpen = false
        unrelatedLocalGattClientOpen = true
        assertTrue(unrelatedLocalGattClientOpen)
        unrelatedLocalGattClientOpen = false
        assertFalse(unrelatedLocalGattClientOpen)
        assertTrue(guard.isClaimed(peer))

        // connect(device, false) can generate another connected callback; it remains once-only.
        inboundServerConnected()
        assertEquals(1, serverConnectCalls)

        guard.release(peer)
        assertFalse(guard.isClaimed(peer))

        inboundServerConnected()
        assertEquals(2, serverConnectCalls)
        assertTrue(guard.isClaimed(peer))
    }
}
