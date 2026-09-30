package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.PrecorUuids

class PrecorChronoGattProfileTest {
    @Test
    fun `advertising fixture reproduces the captured physical Chrono identity`() {
        val plan = PrecorChronoAdvertisingPlan.create()

        assertEquals(PrecorUuids.UUID_PRECOR_SERVICE, plan.serviceUuid)
        assertEquals("CHRONO 0", plan.localName)
        assertTrue(plan.includeNameInAdvertising)
        assertTrue(plan.includeTxPowerInScanResponse)
        assertEquals(0x01FC, plan.manufacturerId)
        assertArrayEquals(byteArrayOf(0x02, 0x06), plan.manufacturerPayload)
        assertEquals(13, plan.advertisingBytes)
        assertEquals(27, plan.scanResponseBytes)
        assertTrue(plan.advertisingBytes <= PrecorChronoAdvertisingPlan.MAX_LEGACY_BYTES)
        assertTrue(plan.scanResponseBytes <= PrecorChronoAdvertisingPlan.MAX_LEGACY_BYTES)
    }

    @Test
    fun `Gatt manifest exposes one notify-only measurement and no control surface`() {
        val characteristic = PrecorChronoGattProfile.characteristics.single()

        assertEquals(PrecorUuids.UUID_PRECOR_MEASUREMENT, characteristic.uuid)
        assertFalse(characteristic.readable)
        assertFalse(characteristic.writable)
        assertTrue(characteristic.notifiable)
    }

    @Test
    fun `notification queue preserves pairs and drops only stale backlog`() {
        val queue = PrecorNotificationQueue(capacity = 4)
        val first = listOf(byteArrayOf(0x53, 0x01), byteArrayOf(0x24, 0x08, 0x00))
        val second = listOf(byteArrayOf(0x53, 0x02), byteArrayOf(0x24, 0x08, 0x00))
        val latest = listOf(byteArrayOf(0x53, 0x03), byteArrayOf(0x24, 0x08, 0x00))

        queue.offerAll(first)
        queue.offerAll(second)
        queue.offerAll(latest)
        latest[0][1] = 0x7F

        assertEquals(4, queue.size())
        assertArrayEquals(second[0], queue.poll())
        assertArrayEquals(second[1], queue.poll())
        assertArrayEquals(byteArrayOf(0x53, 0x03), queue.poll())
        assertArrayEquals(latest[1], queue.poll())
        assertEquals(null, queue.poll())
    }
}
