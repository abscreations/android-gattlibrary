package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.bluetooth.BluetoothProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ConnectionContextTest {

    @Test
    fun `FTMS and HRM contexts occupy independent compatibility slots`() {
        val registry = ConnectionRegistry()

        val ftms = registry.open(
            LegacyConnectionRole.FTMS,
            "aa:bb:cc:dd:ee:01",
            gatt = null,
            initialState = BluetoothProfile.STATE_CONNECTING,
        )
        val hrm = registry.open(
            LegacyConnectionRole.HRM,
            "AA:BB:CC:DD:EE:02",
            gatt = null,
            initialState = BluetoothProfile.STATE_CONNECTED,
        )

        assertSame(ftms, registry.current(LegacyConnectionRole.FTMS))
        assertSame(hrm, registry.current(LegacyConnectionRole.HRM))
        assertEquals("AA:BB:CC:DD:EE:01", ftms.address)
        assertEquals(LinkState.CONNECTING, ftms.connectionState.value)
        assertEquals(LinkState.CONNECTED, hrm.connectionState.value)
        assertNotSame(ftms.queue, hrm.queue)
        assertNotSame(ftms.cccd, hrm.cccd)
    }

    @Test
    fun `reopening an address creates a new link generation`() {
        val registry = ConnectionRegistry()
        val first = registry.open(
            LegacyConnectionRole.FTMS,
            "aa:bb:cc:dd:ee:01",
            gatt = null,
            initialState = BluetoothProfile.STATE_CONNECTING,
        )

        registry.clear(LegacyConnectionRole.FTMS)
        val second = registry.open(
            LegacyConnectionRole.FTMS,
            "AA:BB:CC:DD:EE:01",
            gatt = null,
            initialState = BluetoothProfile.STATE_CONNECTING,
        )

        assertEquals(first.connectionId.address, second.connectionId.address)
        assertNotEquals(first.connectionId, second.connectionId)
        assertEquals(first.connectionId.linkGeneration + 1, second.connectionId.linkGeneration)
        assertEquals(LinkState.DISCONNECTED, first.connectionState.value)
    }

    @Test
    fun `clearing one slot leaves the other context intact`() {
        val registry = ConnectionRegistry()
        registry.open(
            LegacyConnectionRole.FTMS,
            "AA:BB:CC:DD:EE:01",
            gatt = null,
            initialState = BluetoothProfile.STATE_CONNECTED,
        )
        val hrm = registry.open(
            LegacyConnectionRole.HRM,
            "AA:BB:CC:DD:EE:02",
            gatt = null,
            initialState = BluetoothProfile.STATE_CONNECTED,
        )

        registry.clear(LegacyConnectionRole.FTMS)

        assertNull(registry.current(LegacyConnectionRole.FTMS))
        assertSame(hrm, registry.current(LegacyConnectionRole.HRM))
    }

    @Test
    fun `legacy connection-state setters mirror into their owning contexts`() {
        val state = BtEngineState()
        val ftms = state.connectionRegistry.open(
            LegacyConnectionRole.FTMS,
            "AA:BB:CC:DD:EE:01",
            gatt = null,
            initialState = BluetoothProfile.STATE_CONNECTING,
        )
        val hrm = state.connectionRegistry.open(
            LegacyConnectionRole.HRM,
            "AA:BB:CC:DD:EE:02",
            gatt = null,
            initialState = BluetoothProfile.STATE_CONNECTING,
        )

        state.setConnectionState(BluetoothProfile.STATE_CONNECTED)
        state.setHrmConnectionState(BluetoothProfile.STATE_DISCONNECTED)

        assertEquals(LinkState.CONNECTED, ftms.connectionState.value)
        assertEquals(LinkState.DISCONNECTED, hrm.connectionState.value)
        assertEquals(BluetoothProfile.STATE_CONNECTED, state.connectionState.value)
        assertEquals(BluetoothProfile.STATE_DISCONNECTED, state.hrmConnectionState.value)
    }
}
