package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.FeCBleMachineState
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.FeCBleRequestedPageEncoder
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.FeCBleSimulatorIdentity
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.FeCEquipmentType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeCBleTacxAdvertisingTest {
    private fun hex(frame: ByteArray): String = frame.joinToString(" ") { "%02X".format(it) }

    @Test fun `Neo GATT manifest includes captured CSC vector and Device Information surface`() {
        val services = FeCBleGattProfile.capturedManifest(FeCBleSimulatorIdentity.TACX_NEO)
        assertEquals(
            listOf(BluetoothIds.UUID_FEC_SERVICE, BluetoothIds.UUID_DEVICE_INFORMATION_SERVICE, BluetoothIds.UUID_CSC_SERVICE, BluetoothIds.UUID_CYCLING_POWER_SERVICE, TacxGattIds.SERVICE_A501),
            services.map { it.uuid },
        )
        val cps = services.single { it.uuid == BluetoothIds.UUID_CYCLING_POWER_SERVICE }
        assertTrue(cps.characteristics.any { it.uuid == TacxGattIds.CYCLING_POWER_VECTOR })
        val dis = services.single { it.uuid == BluetoothIds.UUID_DEVICE_INFORMATION_SERVICE }
        assertEquals(5, dis.characteristics.size)
    }

    @Test fun `captured Neo Device Information reads are reproduced`() {
        fun read(identity: FeCBleSimulatorIdentity, uuid: java.util.UUID) = tacxReadValue(identity, uuid, includeCps = true)
        assertArrayEquals("Tacx".toByteArray(), read(FeCBleSimulatorIdentity.TACX_NEO, BluetoothIds.UUID_MANUFACTURER_NAME))
        assertArrayEquals("Neo".toByteArray(), read(FeCBleSimulatorIdentity.TACX_NEO, BluetoothIds.UUID_MODEL_NUMBER))
        assertArrayEquals("90002".toByteArray(), read(FeCBleSimulatorIdentity.TACX_NEO, BluetoothIds.UUID_SERIAL_NUMBER))
        assertArrayEquals("0.7.4".toByteArray(), read(FeCBleSimulatorIdentity.TACX_NEO, BluetoothIds.UUID_FIRMWARE_REVISION))
    }

    @Test fun `Neo advertising plan matches captured service list and records API appearance gap`() {
        val plan = tacxAdvertisingPlan(FeCBleSimulatorIdentity.TACX_NEO, includeCps = true)
        assertEquals(
            listOf(BluetoothIds.UUID_CYCLING_POWER_SERVICE, BluetoothIds.UUID_CSC_SERVICE, BluetoothIds.UUID_DEVICE_INFORMATION_SERVICE, BluetoothIds.UUID_FEC_SERVICE),
            plan.scanResponseServices,
        )
        assertNull(plan.manufacturerId)
        assertTrue(plan.serviceData.isEmpty())
        assertEquals(0x0480, plan.capturedAppearance)
    }

    @Test fun `generic FE-C plan retains service-first discovery`() {
        val plan = tacxAdvertisingPlan(FeCBleSimulatorIdentity.GENERIC, includeCps = true)
        assertEquals(listOf(BluetoothIds.UUID_FEC_SERVICE, BluetoothIds.UUID_CYCLING_POWER_SERVICE), plan.advertiseServices)
        assertTrue(plan.scanResponseServices.isEmpty())
    }

    @Test fun `only Neo answers requested page 80 with physical identity values`() {
        val state = FeCBleMachineState(FeCEquipmentType.TRAINER)
        val neoResponse = FeCBleRequestedPageEncoder.encode(
            requestedPage = BluetoothIds.FEC_PAGE_MANUFACTURER_INFORMATION,
            messageType = BluetoothIds.FEC_ANT_MSG_ACKNOWLEDGED_DATA,
            identity = FeCBleSimulatorIdentity.TACX_NEO,
            state = state,
        )
        assertEquals("A4 09 4F 05 50 FF FF 01 59 00 F0 0A 15", hex(neoResponse!!))
        assertNull(
            FeCBleRequestedPageEncoder.encode(
                requestedPage = BluetoothIds.FEC_PAGE_MANUFACTURER_INFORMATION,
                messageType = BluetoothIds.FEC_ANT_MSG_ACKNOWLEDGED_DATA,
                identity = FeCBleSimulatorIdentity.GENERIC,
                state = state,
            ),
        )
    }

    @Test fun `page 54 request remains available to generic and Neo identities`() {
        val state = FeCBleMachineState(FeCEquipmentType.TRAINER)
        FeCBleSimulatorIdentity.entries.forEach { identity ->
            val response = FeCBleRequestedPageEncoder.encode(
                requestedPage = BluetoothIds.FEC_PAGE_FE_CAPABILITIES,
                messageType = BluetoothIds.FEC_ANT_MSG_BROADCAST_DATA,
                identity = identity,
                state = state,
            )
            assertEquals(BluetoothIds.FEC_PAGE_FE_CAPABILITIES, response!![4].toInt() and 0xFF)
        }
    }
}
