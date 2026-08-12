package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrecorChronoGattProfileTest {
    @Test
    fun `advertising fixture uses the current registry UUID and fits legacy budgets`() {
        val plan = PrecorChronoAdvertisingPlan.create()

        assertEquals(BluetoothIds.UUID_PRECOR_SERVICE, plan.serviceUuid)
        assertEquals("CHRONO", plan.localName)
        assertEquals(21, plan.advertisingBytes)
        assertEquals(8, plan.scanResponseBytes)
        assertTrue(plan.advertisingBytes <= PrecorChronoAdvertisingPlan.MAX_LEGACY_BYTES)
        assertTrue(plan.scanResponseBytes <= PrecorChronoAdvertisingPlan.MAX_LEGACY_BYTES)
    }

    @Test
    fun `Gatt manifest exposes one notify-only measurement and no control surface`() {
        val characteristic = PrecorChronoGattProfile.characteristics.single()

        assertEquals(BluetoothIds.UUID_PRECOR_MEASUREMENT, characteristic.uuid)
        assertFalse(characteristic.readable)
        assertFalse(characteristic.writable)
        assertTrue(characteristic.notifiable)
    }
}
