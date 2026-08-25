package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import android.bluetooth.BluetoothGattCharacteristic
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.KickrGattProperty
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.KickrPersonaManifest
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.KickrSimulatorPersona
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.KickrSyntheticModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KickrGattRegistryTest {
    @Test
    fun `Android registry creates every service in each pure manifest`() {
        KickrSimulatorPersona.entries.forEach { persona ->
            val pure = KickrPersonaManifest.forPersona(persona)
            val android = KickrGattRegistry.buildServices(pure)
            assertEquals(pure.services.size, android.size)
        }
    }

    @Test
    fun `Android flags preserve manifest properties and permissions`() {
        KickrPersonaManifest.forPersona(KickrSimulatorPersona.V5_STANDARD_LEGACY).services
            .flatMap { it.characteristics }
            .forEach { characteristic ->
                val (actualProperties, actualPermissions) = KickrGattRegistry.flags(characteristic.properties)
                assertEquals(properties(characteristic.properties), actualProperties)
                val expectedPermissions =
                    (if (characteristic.properties.any { it.name == "READ" }) BluetoothGattCharacteristic.PERMISSION_READ else 0) or
                        (if (characteristic.properties.any { it.name.startsWith("WRITE") }) BluetoothGattCharacteristic.PERMISSION_WRITE else 0)
                assertEquals(expectedPermissions, actualPermissions)
            }
    }

    @Test
    fun `per-link session isolates subscriptions route and bounded queue`() {
        val session = KickrPeripheralSession(7L, KickrSyntheticModel(), queueCapacity = 2)
        session.subscribe(BluetoothIds.UUID_BIKING_DATA, PeripheralConnection.CccdMode.NOTIFY)
        assertEquals(PeripheralConnection.CccdMode.NOTIFY, session.subscription(BluetoothIds.UUID_BIKING_DATA))
        assertTrue(session.acquire(KickrControlRoute.FTMS))
        assertFalse(session.acquire(KickrControlRoute.LEGACY_WAHOO))

        repeat(3) { index ->
            session.enqueue(KickrTxFrame(BluetoothIds.UUID_BIKING_DATA, byteArrayOf(index.toByte()), false))
        }
        assertEquals(1L, session.droppedFrames)
        assertEquals(2, session.pendingCount)
        assertEquals(1, session.beginNext()!!.value[0].toInt())
        assertNull(session.beginNext())
        session.completeInFlight()
        assertEquals(2, session.beginNext()!!.value[0].toInt())

        session.clear()
        assertEquals(PeripheralConnection.CccdMode.DISABLED, session.subscription(BluetoothIds.UUID_BIKING_DATA))
        assertNull(session.route)
        assertNull(session.inFlight)
        assertEquals(0, session.pendingCount)
    }

    private fun properties(values: Set<KickrGattProperty>): Int {
        var result = 0
        if (KickrGattProperty.READ in values) result = result or BluetoothGattCharacteristic.PROPERTY_READ
        if (KickrGattProperty.WRITE in values) result = result or BluetoothGattCharacteristic.PROPERTY_WRITE
        if (KickrGattProperty.WRITE_WITHOUT_RESPONSE in values) {
            result = result or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        }
        if (KickrGattProperty.NOTIFY in values) result = result or BluetoothGattCharacteristic.PROPERTY_NOTIFY
        if (KickrGattProperty.INDICATE in values) result = result or BluetoothGattCharacteristic.PROPERTY_INDICATE
        return result
    }
}
