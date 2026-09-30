package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import android.bluetooth.BluetoothProfile
import com.gmail.absolutebollockscreations.gattlibrary.central.BtEngineState
import com.gmail.absolutebollockscreations.gattlibrary.central.LegacyConnectionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the two live reads the server controller makes through [EngineUpstreamPort] (unit-1 migration, L24). */
class EngineUpstreamPortCharacterizationTest {

    @Test
    fun `no central connections means no addresses`() {
        assertTrue(EngineUpstreamPort(BtEngineState()).connectedCentralAddresses().isEmpty())
    }

    @Test
    fun `connected central addresses are reported lower-cased and read live`() {
        val state = BtEngineState()
        val port = EngineUpstreamPort(state)

        val ctx = state.openConnectionContext(
            role = LegacyConnectionRole.HRM,
            address = "AA:BB:CC:DD:EE:FF",
            handlers = emptyList(),
        )
        state.setConnectionState(ctx, BluetoothProfile.STATE_CONNECTED)

        assertEquals(setOf("aa:bb:cc:dd:ee:ff"), port.connectedCentralAddresses())
    }

    @Test
    fun `pace support is read from the engine each call`() {
        val state = BtEngineState()
        val port = EngineUpstreamPort(state)
        assertFalse(port.supportsPaceMeasurement())
        state.supportsPaceMeasurement = true
        assertTrue(port.supportsPaceMeasurement())
    }

    @Test
    fun `the control sink is bound to the engine provider and is inert without one`() {
        val port = EngineUpstreamPort(BtEngineState())
        assertFalse(port.controlSink.canControl)
        assertTrue(port.controlSink.requestControl())
        assertFalse(port.controlSink.setTargetSpeedKph(10.0))
    }
}
