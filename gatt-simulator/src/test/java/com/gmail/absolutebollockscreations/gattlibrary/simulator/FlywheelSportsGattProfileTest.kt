package com.gmail.absolutebollockscreations.gattlibrary.simulator

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.FlywheelUuids

class FlywheelSportsGattProfileTest {
    @Test
    fun `advertising fits legacy packets and exposes the full service UUID`() {
        val plan = FlywheelSportsAdvertisingPlan.create()

        assertEquals("Flywheel 1", plan.localName)
        assertEquals(FlywheelUuids.UUID_FLYWHEEL_SERVICE, plan.serviceUuid)
        assertEquals(15, plan.advertisingBytes)
        assertEquals(18, plan.scanResponseBytes)
        assertTrue(plan.advertisingBytes <= FlywheelSportsAdvertisingPlan.MAX_LEGACY_BYTES)
        assertTrue(plan.scanResponseBytes <= FlywheelSportsAdvertisingPlan.MAX_LEGACY_BYTES)
    }

    @Test
    fun `Gatt manifest is writable RX and notify plus CCCD TX`() {
        val rx = FlywheelSportsGattProfile.characteristics.single { it.uuid == FlywheelUuids.UUID_FLYWHEEL_RX }
        val tx = FlywheelSportsGattProfile.characteristics.single { it.uuid == FlywheelUuids.UUID_FLYWHEEL_TX }

        assertTrue(rx.writable)
        assertTrue(rx.writeWithoutResponse)
        assertFalse(rx.notifiable)
        assertFalse(rx.hasCccd)
        assertFalse(tx.writable)
        assertTrue(tx.notifiable)
        assertTrue(tx.hasCccd)
    }

    @Test
    fun `RX writes are accepted only on the exact surface and remain semantically inert`() {
        assertTrue(FlywheelSportsGattProfile.acceptsInertRxWrite(FlywheelUuids.UUID_FLYWHEEL_RX, false, 0))
        assertFalse(FlywheelSportsGattProfile.acceptsInertRxWrite(FlywheelUuids.UUID_FLYWHEEL_TX, false, 0))
        assertFalse(FlywheelSportsGattProfile.acceptsInertRxWrite(UUID.randomUUID(), false, 0))
        assertFalse(FlywheelSportsGattProfile.acceptsInertRxWrite(FlywheelUuids.UUID_FLYWHEEL_RX, true, 0))
        assertFalse(FlywheelSportsGattProfile.acceptsInertRxWrite(FlywheelUuids.UUID_FLYWHEEL_RX, false, 1))
    }
}
