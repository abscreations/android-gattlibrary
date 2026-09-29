package com.gmail.absolutebollockscreations.gattlibrary.central

import android.bluetooth.BluetoothProfile
import org.junit.Assert.*
import org.junit.Test

/** Pins the registry/lease behavior the consumer facade must preserve. */
class CentralDiscoveryConnectionCharacterizationTest {
    @Test
    fun `replacing a machine preserves independent sensors and invalidates the old key`() {
        val registry = ConnectionRegistry()
        val sensor = registry.openAdditional("AA:01", null, BluetoothProfile.STATE_CONNECTED)
        val old = registry.open(LegacyConnectionRole.FTMS, "AA:02", null, BluetoothProfile.STATE_CONNECTED)
        val oldKey = registry.connectedDevices.value.single { it.machineConnection }.connectionKey
        val replacement = registry.open(LegacyConnectionRole.FTMS, "AA:02", null, BluetoothProfile.STATE_CONNECTING)

        assertNull(registry.contextForKey(oldKey))
        assertFalse(registry.isCurrent(old))
        assertTrue(registry.isCurrent(sensor))
        assertTrue(registry.isCurrent(replacement))
        assertEquals(1, registry.connectedDevices.value.count { it.machineConnection })
        assertFalse(registry.clear(old))
        assertTrue(registry.isCurrent(replacement))
    }

    @Test
    fun `disconnecting one of several sensors preserves the other generation`() {
        val registry = ConnectionRegistry()
        val first = registry.openAdditional("AA:01", null, BluetoothProfile.STATE_CONNECTED)
        val second = registry.openAdditional("AA:02", null, BluetoothProfile.STATE_CONNECTED)
        assertTrue(registry.clear(first))
        assertEquals(second.address, registry.connectedDevices.value.single().address)
        assertFalse(registry.clear(first))
        assertTrue(registry.isCurrent(second))
    }

    @Test
    fun `refreshing discovery then timing out leaves advertisement telemetry leased`() {
        val leases = ScanLeaseRegistry()
        assertTrue(leases.acquire(ScanLeaseOwner.ADVERTISEMENT_MACHINE))
        assertFalse(leases.acquire(ScanLeaseOwner.DISCOVERY_UI))
        assertFalse(leases.acquire(ScanLeaseOwner.DISCOVERY_UI))
        assertFalse(leases.release(ScanLeaseOwner.DISCOVERY_UI))
        assertTrue(leases.holds(ScanLeaseOwner.ADVERTISEMENT_MACHINE))
        assertFalse(leases.holds(ScanLeaseOwner.DISCOVERY_UI))
    }

    @Test
    fun `advertisement identity routes without inventing a GATT service`() {
        val device = BtEngineState.DiscoveredDevice(
            "M3i", "AA:01", -40, 1L, null,
            machineCandidate = true,
            transport = BtEngineState.DeviceTransport.ADVERTISEMENT_ONLY,
            advertisementSystemId = 7,
        )
        assertEquals(MachineSelectionRoute.ADVERTISEMENT_SELECT, machineSelectionRoute(device))
        assertTrue(device.services.isEmpty())
        assertEquals(7, device.copy(address = "AA:02").advertisementSystemId)
    }
}
