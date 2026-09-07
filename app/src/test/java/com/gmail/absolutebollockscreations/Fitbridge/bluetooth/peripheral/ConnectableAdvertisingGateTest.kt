package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectableAdvertisingGateTest {
    @Test
    fun first_connection_pauses_and_final_disconnect_resumes() {
        val gate = ConnectableAdvertisingGate()

        assertEquals(ConnectableAdvertisingGate.Action.PAUSE, gate.onConnected("a"))
        assertEquals(ConnectableAdvertisingGate.Action.NONE, gate.onConnected("b"))
        assertEquals(ConnectableAdvertisingGate.Action.NONE, gate.onDisconnected("a"))
        assertEquals(ConnectableAdvertisingGate.Action.RESUME, gate.onDisconnected("b"))
    }

    @Test
    fun duplicate_and_unknown_callbacks_are_idempotent() {
        val gate = ConnectableAdvertisingGate()

        assertEquals(ConnectableAdvertisingGate.Action.PAUSE, gate.onConnected("a"))
        assertEquals(ConnectableAdvertisingGate.Action.NONE, gate.onConnected("a"))
        assertEquals(ConnectableAdvertisingGate.Action.NONE, gate.onDisconnected("missing"))
        assertEquals(ConnectableAdvertisingGate.Action.RESUME, gate.onDisconnected("a"))
        assertEquals(ConnectableAdvertisingGate.Action.NONE, gate.onDisconnected("a"))
    }

    @Test
    fun reset_makes_the_next_connection_first_again() {
        val gate = ConnectableAdvertisingGate()
        gate.onConnected("a")
        gate.reset()

        assertEquals(ConnectableAdvertisingGate.Action.PAUSE, gate.onConnected("b"))
    }
}
