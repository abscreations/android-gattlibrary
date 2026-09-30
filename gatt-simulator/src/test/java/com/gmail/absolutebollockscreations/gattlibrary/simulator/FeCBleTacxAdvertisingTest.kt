package com.gmail.absolutebollockscreations.gattlibrary.simulator

import android.bluetooth.BluetoothGattCharacteristic
import com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.FecBleUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids

class FeCBleTacxAdvertisingTest {
    private fun hex(frame: ByteArray): String = frame.joinToString(" ") { "%02X".format(it) }

    @Test fun `Neo GATT manifest includes captured CSC vector and Device Information surface`() {
        val services = FeCBleGattProfile.capturedManifest(FeCBleSimulatorIdentity.TACX_NEO)
        assertEquals(
            listOf(FecBleUuids.UUID_FEC_SERVICE, SigUuids.UUID_DEVICE_INFORMATION_SERVICE, SigUuids.UUID_CSC_SERVICE, SigUuids.UUID_CYCLING_POWER_SERVICE, TacxGattIds.SERVICE_A501),
            services.map { it.uuid },
        )
        val cps = services.single { it.uuid == SigUuids.UUID_CYCLING_POWER_SERVICE }
        assertTrue(cps.characteristics.any { it.uuid == TacxGattIds.CYCLING_POWER_VECTOR })
        val dis = services.single { it.uuid == SigUuids.UUID_DEVICE_INFORMATION_SERVICE }
        assertEquals(5, dis.characteristics.size)
    }

    @Test fun `captured Neo Device Information reads are reproduced`() {
        fun read(identity: FeCBleSimulatorIdentity, uuid: java.util.UUID) = tacxReadValue(identity, uuid, includeCps = true)
        assertArrayEquals("Tacx".toByteArray(), read(FeCBleSimulatorIdentity.TACX_NEO, SigUuids.UUID_MANUFACTURER_NAME))
        assertArrayEquals("Neo".toByteArray(), read(FeCBleSimulatorIdentity.TACX_NEO, SigUuids.UUID_MODEL_NUMBER))
        assertArrayEquals("90002".toByteArray(), read(FeCBleSimulatorIdentity.TACX_NEO, SigUuids.UUID_SERIAL_NUMBER))
        assertArrayEquals("0.7.4".toByteArray(), read(FeCBleSimulatorIdentity.TACX_NEO, SigUuids.UUID_FIRMWARE_REVISION))
    }

    @Test fun `Neo advertising plan matches captured service list and records API appearance gap`() {
        val plan = tacxAdvertisingPlan(FeCBleSimulatorIdentity.TACX_NEO, includeCps = true)
        assertEquals(
            listOf(SigUuids.UUID_CYCLING_POWER_SERVICE, SigUuids.UUID_CSC_SERVICE, SigUuids.UUID_DEVICE_INFORMATION_SERVICE, FecBleUuids.UUID_FEC_SERVICE),
            plan.scanResponseServices,
        )
        assertNull(plan.manufacturerId)
        assertTrue(plan.serviceData.isEmpty())
        assertEquals(0x0480, plan.capturedAppearance)
    }

    @Test fun `generic FE-C plan retains service-first discovery`() {
        val plan = tacxAdvertisingPlan(FeCBleSimulatorIdentity.GENERIC, includeCps = true)
        assertEquals(listOf(FecBleUuids.UUID_FEC_SERVICE, SigUuids.UUID_CYCLING_POWER_SERVICE), plan.advertiseServices)
        assertTrue(plan.scanResponseServices.isEmpty())
    }

    @Test fun `Flux 2 advertising reproduces captured split without cloning its suffix`() {
        val plan = tacxAdvertisingPlan(FeCBleSimulatorIdentity.TACX_FLUX_2, includeCps = true)
        assertTrue(plan.advertiseServices.isEmpty())
        assertEquals(
            listOf(FtmsUuids.UUID_FTMS_SERVICE, SigUuids.UUID_CYCLING_POWER_SERVICE, FecBleUuids.UUID_FEC_SERVICE),
            plan.scanResponseServices,
        )
        assertEquals(0x0689, plan.manufacturerId)
        assertArrayEquals(byteArrayOf(0x29, 0x80.toByte()), plan.manufacturerData)
        assertArrayEquals(
            byteArrayOf(0x01, 0x00, 0x20),
            plan.serviceData[FtmsUuids.UUID_FTMS_SERVICE],
        )
        assertTrue(plan.includeNameInAdvertisement)
    }

    @Test fun `Flux 2 GATT manifest matches complete physical discovery`() {
        val services = FeCBleGattProfile.capturedManifest(FeCBleSimulatorIdentity.TACX_FLUX_2)
        assertEquals(
            listOf(
                SigUuids.UUID_DEVICE_INFORMATION_SERVICE,
                TacxGattIds.SERVICE_A605,
                TacxGattIds.SERVICE_A501,
                SigUuids.UUID_CYCLING_POWER_SERVICE,
                FtmsUuids.UUID_FTMS_SERVICE,
                FecBleUuids.UUID_FEC_SERVICE,
            ),
            services.map { it.uuid },
        )
        val write = BluetoothGattCharacteristic.PROPERTY_WRITE
        val writeNr = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        val notify = BluetoothGattCharacteristic.PROPERTY_NOTIFY
        val proprietary = services.single { it.uuid == TacxGattIds.SERVICE_A605 }
        assertEquals(
            listOf(
                TacxGattIds.CHAR_AB01 to write,
                TacxGattIds.CHAR_AB02 to notify,
                TacxGattIds.CHAR_AB21 to (write or writeNr),
                TacxGattIds.CHAR_AB22 to notify,
            ),
            proprietary.characteristics.map { it.uuid to it.properties },
        )
        assertEquals(
            setOf(TacxGattIds.USER_DESCRIPTION, SigUuids.UUID_CCCD),
            proprietary.characteristics.single { it.uuid == TacxGattIds.CHAR_AB02 }.descriptors,
        )
        val ftms = services.single { it.uuid == FtmsUuids.UUID_FTMS_SERVICE }
        assertEquals(
            listOf(
                FtmsUuids.UUID_BIKING_DATA,
                FtmsUuids.UUID_FITNESS_MACHINE_FEATURE,
                FtmsUuids.UUID_SUPPORTED_RESISTANCE_RANGE,
                FtmsUuids.UUID_SUPPORTED_POWER_RANGE,
                FtmsUuids.UUID_FITNESS_MACHINE_STATUS,
                FtmsUuids.UUID_FITNESS_MACHINE_CONTROL_POINT,
            ),
            ftms.characteristics.map { it.uuid },
        )
        val read = BluetoothGattCharacteristic.PROPERTY_READ
        val indicate = BluetoothGattCharacteristic.PROPERTY_INDICATE
        fun properties(service: java.util.UUID) = services.single { it.uuid == service }
            .characteristics.map { it.uuid to it.properties }
        assertEquals(
            listOf(
                SigUuids.UUID_FIRMWARE_REVISION to read,
                SigUuids.UUID_MANUFACTURER_NAME to read,
                SigUuids.UUID_MODEL_NUMBER to read,
            ),
            properties(SigUuids.UUID_DEVICE_INFORMATION_SERVICE),
        )
        assertEquals(listOf(TacxGattIds.CHAR_AC01 to (read or write)), properties(TacxGattIds.SERVICE_A501))
        assertEquals(
            listOf(
                SigUuids.UUID_CYCLING_POWER_MEASUREMENT to notify,
                SigUuids.UUID_CYCLING_POWER_FEATURE to read,
                SigUuids.UUID_SENSOR_LOCATION to read,
                SigUuids.UUID_CYCLING_POWER_CONTROL_POINT to (write or indicate),
            ),
            properties(SigUuids.UUID_CYCLING_POWER_SERVICE),
        )
        assertEquals(
            listOf(
                FtmsUuids.UUID_BIKING_DATA to notify,
                FtmsUuids.UUID_FITNESS_MACHINE_FEATURE to read,
                FtmsUuids.UUID_SUPPORTED_RESISTANCE_RANGE to read,
                FtmsUuids.UUID_SUPPORTED_POWER_RANGE to read,
                FtmsUuids.UUID_FITNESS_MACHINE_STATUS to notify,
                FtmsUuids.UUID_FITNESS_MACHINE_CONTROL_POINT to (write or indicate),
            ),
            properties(FtmsUuids.UUID_FTMS_SERVICE),
        )
        assertEquals(
            listOf(
                FecBleUuids.UUID_FEC_MEASUREMENT to notify,
                FecBleUuids.UUID_FEC_CONTROL to (write or writeNr),
            ),
            properties(FecBleUuids.UUID_FEC_SERVICE),
        )
    }

    @Test fun `Flux 2 captured identity and FTMS feature reads are exact`() {
        fun read(uuid: java.util.UUID) = tacxReadValue(FeCBleSimulatorIdentity.TACX_FLUX_2, uuid, includeCps = true)
        assertArrayEquals("Tacx".toByteArray(), read(SigUuids.UUID_MANUFACTURER_NAME))
        assertArrayEquals("T2980".toByteArray(), read(SigUuids.UUID_MODEL_NUMBER))
        assertArrayEquals("3.3.45".toByteArray(), read(SigUuids.UUID_FIRMWARE_REVISION))
        assertNull(read(SigUuids.UUID_SERIAL_NUMBER))
        assertArrayEquals(
            byteArrayOf(0x82.toByte(), 0x40, 0x00, 0x00, 0x0C, 0x20, 0x00, 0x00),
            read(FtmsUuids.UUID_FITNESS_MACHINE_FEATURE),
        )
        assertArrayEquals(byteArrayOf(0x0C, 0x00, 0x00, 0x00), read(SigUuids.UUID_CYCLING_POWER_FEATURE))
        assertArrayEquals(
            byteArrayOf(0x82.toByte(), 0x40, 0x00, 0x00, 0x0C, 0xA0.toByte(), 0x00, 0x00),
            TACX_FLUX_2_CAPTURED_FTMS_FEATURE,
        )
        assertNull(read(TacxGattIds.CHAR_AC01))
    }

    @Test fun `Tacx identities answer requested page 80 with their capture-backed identity values`() {
        val state = FeCBleMachineState(FeCEquipmentType.TRAINER)
        val neoResponse = FeCBleRequestedPageEncoder.encode(
            requestedPage = FecBleUuids.FEC_PAGE_MANUFACTURER_INFORMATION,
            messageType = FecBleUuids.FEC_ANT_MSG_ACKNOWLEDGED_DATA,
            identity = FeCBleSimulatorIdentity.TACX_NEO,
            state = state,
        )
        assertEquals("A4 09 4F 05 50 FF FF 01 59 00 F0 0A 15", hex(neoResponse!!))
        assertNull(
            FeCBleRequestedPageEncoder.encode(
                requestedPage = FecBleUuids.FEC_PAGE_MANUFACTURER_INFORMATION,
                messageType = FecBleUuids.FEC_ANT_MSG_ACKNOWLEDGED_DATA,
                identity = FeCBleSimulatorIdentity.GENERIC,
                state = state,
            ),
        )
        val fluxResponse = FeCBleRequestedPageEncoder.encode(
            requestedPage = FecBleUuids.FEC_PAGE_MANUFACTURER_INFORMATION,
            messageType = FecBleUuids.FEC_ANT_MSG_ACKNOWLEDGED_DATA,
            identity = FeCBleSimulatorIdentity.TACX_FLUX_2,
            state = state,
        )
        assertEquals(FecBleUuids.FEC_PAGE_MANUFACTURER_INFORMATION, fluxResponse!![4].toInt() and 0xFF)
        assertEquals("A4 09 4F 05 50 FF FF 01 59 00 A4 0B 40", hex(fluxResponse))
    }

    @Test fun `page 54 request remains available to generic and Neo identities`() {
        val state = FeCBleMachineState(FeCEquipmentType.TRAINER)
        FeCBleSimulatorIdentity.entries.forEach { identity ->
            val response = FeCBleRequestedPageEncoder.encode(
                requestedPage = FecBleUuids.FEC_PAGE_FE_CAPABILITIES,
                messageType = FecBleUuids.FEC_ANT_MSG_BROADCAST_DATA,
                identity = identity,
                state = state,
            )
            assertEquals(FecBleUuids.FEC_PAGE_FE_CAPABILITIES, response!![4].toInt() and 0xFF)
        }
    }
}
