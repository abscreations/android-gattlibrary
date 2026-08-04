package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.bluetooth.BluetoothProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
        first.queue.enqueue(StackEntry(StackEntry.Op.READ, charUuid = java.util.UUID.randomUUID()))
        first.queue.beginDispatch()
        registry.setCccdReady(first, true)
        assertTrue(registry.machineCccdReady.value)

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
        assertEquals(0, first.queue.size)
        assertFalse(first.queue.isBusy)
        assertFalse(first.cccd.ready.value)
        assertFalse(registry.machineCccdReady.value)
    }

    @Test
    fun `clearing one slot leaves the other context intact`() {
        val registry = ConnectionRegistry()
        val ftms = registry.open(
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
        ftms.queue.enqueue(StackEntry(StackEntry.Op.READ, charUuid = java.util.UUID.randomUUID()))
        hrm.queue.enqueue(StackEntry(StackEntry.Op.READ, charUuid = java.util.UUID.randomUUID()))
        ftms.queue.beginDispatch()
        hrm.queue.beginDispatch()
        ftms.cccd.nextRecoveryAttempt()
        hrm.cccd.nextRecoveryAttempt()

        registry.clear(LegacyConnectionRole.FTMS)

        assertNull(registry.current(LegacyConnectionRole.FTMS))
        assertSame(hrm, registry.current(LegacyConnectionRole.HRM))
        assertEquals(0, ftms.queue.size)
        assertFalse(ftms.queue.isBusy)
        assertEquals(0, ftms.cccd.recoveryAttempts)
        assertEquals(1, hrm.queue.size)
        assertTrue(hrm.queue.isBusy)
        assertEquals(1, hrm.cccd.recoveryAttempts)
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
