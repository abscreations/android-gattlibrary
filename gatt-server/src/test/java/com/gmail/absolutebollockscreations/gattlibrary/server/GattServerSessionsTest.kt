package com.gmail.absolutebollockscreations.gattlibrary.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** LIB-9g: a GATT server is never closed while a service add is unconfirmed. */
class GattServerSessionsTest {

    /** Records calls; [database] mimics what the stack would expose to a remote client. */
    private class FakeServer : GattServerOps<String> {
        val database = mutableListOf<String>()
        val calls = mutableListOf<String>()
        var closed = false
        var acceptAdds = true

        override fun addService(service: String): Boolean {
            calls += "add:$service"
            return acceptAdds
        }
        /** The stack's asynchronous confirmation: the service lands, closed or not. */
        fun land(service: String) { database += service }
        override fun removeService(service: String): Boolean {
            calls += "remove:$service"
            return database.remove(service)
        }
        override fun clearServices() {
            calls += "clear"
            if (!closed) database.clear()
        }
        override fun close() {
            calls += "close"
            closed = true
        }
    }

    private class FakeTimer : GattTimer {
        val pending = mutableListOf<Pair<Long, () -> Unit>>()
        override fun schedule(delayMs: Long, action: () -> Unit) { pending += delayMs to action }
        fun fire() { pending.toList().forEach { it.second() }; pending.clear() }
    }

    private val timer = FakeTimer()
    private val sessions = GattServerSessions<String>(timer, closeWaitMs = 2_000)

    @Test
    fun `close with nothing in flight clears and closes at once`() {
        val server = FakeServer()
        sessions.open(1, server)
        assertTrue(sessions.addService("C2-INFO"))
        server.land("C2-INFO")
        assertFalse(sessions.onServiceAdded(1, "C2-INFO"))

        sessions.closeCurrent()

        assertEquals(listOf("add:C2-INFO", "clear", "close"), server.calls)
        assertTrue(server.database.isEmpty())
        assertFalse(sessions.hasPendingClose)
    }

    @Test
    fun `stop during an add waits for the confirmation then removes that service`() {
        // The PM5 leak: stopped while the rowing service add was still unconfirmed.
        val server = FakeServer()
        sessions.open(1, server)
        sessions.addService("C2-INFO"); server.land("C2-INFO"); sessions.onServiceAdded(1, "C2-INFO")
        sessions.addService("C2-ROWING")

        sessions.closeCurrent()
        assertFalse(server.closed)
        assertTrue(sessions.hasPendingClose)
        assertTrue(server.database.isEmpty()) // already-published services withdrawn at once

        server.land("C2-ROWING") // lands after the stop
        assertTrue(sessions.onServiceAdded(1, "C2-ROWING")) // owner must ignore it

        assertTrue(server.closed)
        assertTrue(server.database.isEmpty())
        assertEquals(listOf("remove:C2-ROWING", "clear", "close"), server.calls.takeLast(3))
        assertFalse(sessions.hasPendingClose)
    }

    @Test
    fun `a confirmation that never arrives closes after the wait`() {
        val server = FakeServer()
        sessions.open(1, server)
        sessions.addService("C2-ROWING")
        sessions.closeCurrent()
        assertFalse(server.closed)
        assertEquals(2_000L, timer.pending.single().first)

        timer.fire()

        assertTrue(server.closed)
        assertFalse(sessions.hasPendingClose)
        // A very late confirmation for the closed session is ignored and closes nothing twice.
        assertTrue(sessions.onServiceAdded(1, "C2-ROWING"))
        assertEquals(1, server.calls.count { it == "close" })
    }

    @Test
    fun `a new server can open while the old one waits and each keeps its own callbacks`() {
        val pm5 = FakeServer()
        val ftms = FakeServer()
        sessions.open(1, pm5)
        sessions.addService("C2-ROWING")
        sessions.closeCurrent()

        sessions.open(2, ftms)
        assertTrue(sessions.addService("FTMS"))
        assertFalse(sessions.onServiceAdded(2, "FTMS")) // current server: owner continues

        pm5.land("C2-ROWING")
        assertTrue(sessions.onServiceAdded(1, "C2-ROWING"))
        assertTrue(pm5.closed)
        assertFalse(ftms.closed)
        assertTrue(pm5.database.isEmpty())
    }

    @Test
    fun `opening over a live server closes the previous one`() {
        val first = FakeServer()
        sessions.open(1, first)
        sessions.open(2, FakeServer())
        assertTrue(first.closed)
    }

    @Test
    fun `a rejected add leaves nothing in flight`() {
        val server = FakeServer().apply { acceptAdds = false }
        sessions.open(1, server)
        assertFalse(sessions.addService("FTMS"))
        sessions.closeCurrent()
        assertTrue(server.closed)
        assertFalse(sessions.hasPendingClose)
    }

    @Test
    fun `callbacks for unknown epochs are ignored and adds without a server fail`() {
        assertTrue(sessions.onServiceAdded(99, "X"))
        assertFalse(sessions.addService("X"))
    }
}
