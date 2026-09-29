package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanListTrackingTest {

    @Test
    fun `machine and sensor observations share one discovery registry`() {
        val state = BtEngineState()
        val machine = BtEngineState.DiscoveredDevice(
            name = "Bike",
            address = "AA:00",
            rssi = -40,
            lastSeenMs = 1,
            scanResult = null,
            services = setOf(BtEngineState.BtServiceType.FTMS),
        )
        val sensor = BtEngineState.DiscoveredDevice(
            name = "HRM",
            address = "AA:01",
            rssi = -50,
            lastSeenMs = 2,
            scanResult = null,
            services = setOf(BtEngineState.BtServiceType.HeartRate),
        )

        state.putDiscoveredDevices(mapOf(machine.address to machine, sensor.address to sensor))

        assertEquals(setOf(machine.address, sensor.address), state.discoveredDevices.value.keys)
    }

    private data class SeenEntry(
        val address: String,
        val lastSeenMs: Long,
        val connected: Boolean = false,
    )

    @Test
    fun `entries older than five seconds are removed at a strict boundary`() {
        val now = 20_000L
        val entries = mapOf(
            "fresh" to SeenEntry("AA:00", now - 4_999L),
            "boundary" to SeenEntry("AA:01", now - 5_000L),
            "stale" to SeenEntry("AA:02", now - 5_001L),
        )

        val retained = retainFreshScanEntries(
            entries = entries,
            nowMs = now,
            staleAfterMs = 5_000L,
            lastSeenMs = { it.lastSeenMs },
        )

        assertTrue("fresh entry", retained.containsKey("fresh"))
        assertTrue("exactly five seconds old", retained.containsKey("boundary"))
        assertFalse("more than five seconds old", retained.containsKey("stale"))
    }

    @Test
    fun `connected entries remain visible even when advertisements stop`() {
        val retained = retainFreshScanEntries(
            entries = mapOf(
                "connected" to SeenEntry("AA:00", lastSeenMs = 0L, connected = true),
                "stale" to SeenEntry("AA:01", lastSeenMs = 0L),
            ),
            nowMs = 10_000L,
            staleAfterMs = 5_000L,
            lastSeenMs = { it.lastSeenMs },
            alwaysRetain = { it.connected },
        )

        assertEquals(setOf("connected"), retained.keys)
    }

    @Test
    fun `duplicate MAC addresses are matched case insensitively`() {
        val entries = mapOf(
            "old-bike" to SeenEntry("  AA:BB:CC:DD:EE:FF  ", 1L),
            "new-rower" to SeenEntry("aa:bb:cc:dd:ee:ff", 2L),
            "other" to SeenEntry("11:22:33:44:55:66", 3L),
        )

        val duplicates = duplicateAddressKeys(
            entries = entries,
            keepKey = "new-rower",
            address = "AA:BB:CC:DD:EE:FF",
            addressOf = { it.address },
        )

        assertEquals(setOf("old-bike"), duplicates)
    }

    @Test
    fun `scan addresses use one canonical map identity`() {
        assertEquals(
            "AA:BB:CC:DD:EE:FF",
            canonicalScanAddress("  aa:bb:cc:dd:ee:ff  "),
        )
    }

    @Test
    fun `blank addresses are not deduplicated`() {
        val entries = mapOf(
            "temporary-one" to SeenEntry("", 1L),
            "temporary-two" to SeenEntry("", 2L),
        )

        assertTrue(
            duplicateAddressKeys(
                entries = entries,
                keepKey = "temporary-two",
                address = "",
                addressOf = { it.address },
            ).isEmpty(),
        )
    }

    @Test
    fun `GAP appearance parser reads little endian cycling appearance`() {
        val advertisingData = byteArrayOf(
            2, 0x01, 0x06,
            3, 0x19, 0x82.toByte(), 0x04,
            0,
        )

        assertEquals(0x0482, gapAppearanceFromAdvertisingData(advertisingData))
    }

    @Test
    fun `GAP appearance parser rejects truncated and undersized structures`() {
        assertEquals(null, gapAppearanceFromAdvertisingData(byteArrayOf(3, 0x19, 0x82.toByte())))
        assertEquals(null, gapAppearanceFromAdvertisingData(byteArrayOf(2, 0x19, 0x82.toByte())))
        assertEquals(null, gapAppearanceFromAdvertisingData(byteArrayOf(0)))
    }
}
