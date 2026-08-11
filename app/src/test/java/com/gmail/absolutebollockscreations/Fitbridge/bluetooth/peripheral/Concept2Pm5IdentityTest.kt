package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.Concept2Machine
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.SimulatorPersona
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Concept2Pm5IdentityTest {
    @Test
    fun `advertisement and scan response fit legacy budgets for every PM5 persona`() {
        Concept2Machine.entries.forEach { machine ->
            val plan = Concept2Pm5AdvertisingPlan.forPersona(SimulatorPersona.Concept2Pm5(machine))
            assertEquals(BluetoothIds.UUID_C2_DISCOVERY_SERVICE, plan.serviceUuid)
            assertEquals(21, plan.advertisingBytes)
            assertEquals(15, plan.scanResponseBytes)
            assertTrue(plan.advertisingBytes <= 31)
            assertTrue(plan.scanResponseBytes <= 31)
        }
    }

    @Test
    fun `device information has documented widths machine values and offset behavior`() {
        Concept2Machine.entries.forEach { machine ->
            val profile = Concept2Pm5GattProfile(SimulatorPersona.Concept2Pm5(machine))
            assertEquals(16, profile.readDeviceInformation(BluetoothIds.UUID_C2_MODEL_NUMBER, 0)!!.size)
            assertEquals(9, profile.readDeviceInformation(BluetoothIds.UUID_C2_SERIAL_NUMBER, 0)!!.size)
            assertEquals(3, profile.readDeviceInformation(BluetoothIds.UUID_C2_HARDWARE_REVISION, 0)!!.size)
            assertEquals(20, profile.readDeviceInformation(BluetoothIds.UUID_C2_FIRMWARE_REVISION, 0)!!.size)
            assertEquals(16, profile.readDeviceInformation(BluetoothIds.UUID_C2_MANUFACTURER_NAME, 0)!!.size)
            assertArrayEquals(
                byteArrayOf(machine.wireMachineType.toByte()),
                profile.readDeviceInformation(BluetoothIds.UUID_C2_MACHINE_TYPE, 0),
            )
            assertArrayEquals(byteArrayOf(0x05, 0x02), profile.readDeviceInformation(BluetoothIds.UUID_C2_ATT_RECEIVE_MTU, 0, 517))
            assertArrayEquals(byteArrayOf(0x02), profile.readDeviceInformation(BluetoothIds.UUID_C2_ATT_RECEIVE_MTU, 1, 517))
            assertArrayEquals(byteArrayOf(), profile.readDeviceInformation(BluetoothIds.UUID_C2_SERIAL_NUMBER, 9))
            assertNull(profile.readDeviceInformation(BluetoothIds.UUID_C2_SERIAL_NUMBER, 10))
            assertNull(profile.readDeviceInformation(UUID.randomUUID(), 0))
        }
    }

    @Test
    fun `V199 service manifest has documented order and exact C1 properties`() {
        val services = Concept2Pm5GattProfile(
            SimulatorPersona.Concept2Pm5(Concept2Machine.ROW_ERG),
        ).services
        assertEquals(
            listOf(
                BluetoothIds.UUID_C2_DEVICE_INFO_SERVICE,
                BluetoothIds.UUID_C2_PM_CONTROL_SERVICE,
                BluetoothIds.UUID_C2_ERG_SERVICE,
                BluetoothIds.UUID_C2_HEART_RATE_RECEIVE_SERVICE,
            ),
            services.keys.toList(),
        )

        val control = services.getValue(BluetoothIds.UUID_C2_PM_CONTROL_SERVICE)
        assertEquals(2, control.size)
        assertEquals(
            Triple(false, true, false),
            control.single { it.uuid == BluetoothIds.UUID_C2_CSAFE_REQUEST }
                .let { Triple(it.readable, it.writable, it.notifiable) },
        )
        assertEquals(
            Triple(true, false, false),
            control.single { it.uuid == BluetoothIds.UUID_C2_CSAFE_RESPONSE }
                .let { Triple(it.readable, it.writable, it.notifiable) },
        )

        val erg = services.getValue(BluetoothIds.UUID_C2_ERG_SERVICE)
        assertTrue(erg.single { it.uuid == BluetoothIds.UUID_C2_ADDITIONAL_STATUS_3 }.notifiable)
        assertTrue(erg.single { it.uuid == BluetoothIds.UUID_C2_LOGGED_WORKOUT }.notifiable)
        assertTrue(erg.all { !it.writable || it.uuid == BluetoothIds.UUID_C2_STATUS_SAMPLE_RATE })

        val heartRate = services.getValue(BluetoothIds.UUID_C2_HEART_RATE_RECEIVE_SERVICE).single()
        assertEquals(BluetoothIds.UUID_C2_HEART_RATE_RECEIVE, heartRate.uuid)
        assertFalse(heartRate.readable)
        assertTrue(heartRate.writable)
        assertFalse(heartRate.notifiable)
    }
}
