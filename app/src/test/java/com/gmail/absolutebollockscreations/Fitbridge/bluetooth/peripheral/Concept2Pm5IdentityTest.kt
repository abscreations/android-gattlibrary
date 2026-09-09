package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.Concept2CharacteristicManifest
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.Concept2Machine
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.Concept2Pm5CompatibilityProfile
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.Concept2Pm5GattSurface
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
            assertEquals(9, profile.readDeviceInformation(BluetoothIds.UUID_C2_SERIAL_NUMBER, 0)!!.size)
            assertEquals(3, profile.readDeviceInformation(BluetoothIds.UUID_C2_HARDWARE_REVISION, 0)!!.size)
            assertEquals(20, profile.readDeviceInformation(BluetoothIds.UUID_C2_FIRMWARE_REVISION, 0)!!.size)
            assertArrayEquals(
                machine.let { SimulatorPersona.Concept2Pm5(it).compatibility.firmwareIdentity }
                    .toByteArray(Charsets.US_ASCII),
                profile.readDeviceInformation(BluetoothIds.UUID_C2_FIRMWARE_REVISION, 0),
            )
            assertEquals(16, profile.readDeviceInformation(BluetoothIds.UUID_C2_MANUFACTURER_NAME, 0)!!.size)
            assertEquals(16, profile.readDeviceInformation(BluetoothIds.UUID_C2_MODEL_NUMBER, 0)!!.size)
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
    fun `pre-FTMS manifest mirrors PM5 430392046 and keeps the response notify contract`() {
        val services = Concept2Pm5GattProfile(
            SimulatorPersona.Concept2Pm5(
                Concept2Machine.ROW_ERG,
                Concept2Pm5CompatibilityProfile.PRE_FTMS_ROW,
            ),
        ).services
        assertEquals(
            listOf(
                BluetoothIds.UUID_C2_DEVICE_INFO_SERVICE,
                BluetoothIds.UUID_C2_PM_CONTROL_SERVICE,
                BluetoothIds.UUID_C2_ERG_SERVICE,
            ),
            services.keys.toList(),
        )
        assertFalse(services.containsKey(BluetoothIds.UUID_C2_HEART_RATE_RECEIVE_SERVICE))

        // Device Information as the pre-FTMS unit exposes it: 0x0012-0x0015 and nothing else.
        assertEquals(
            listOf(
                BluetoothIds.UUID_C2_SERIAL_NUMBER,
                BluetoothIds.UUID_C2_HARDWARE_REVISION,
                BluetoothIds.UUID_C2_FIRMWARE_REVISION,
                BluetoothIds.UUID_C2_MANUFACTURER_NAME,
            ),
            services.getValue(BluetoothIds.UUID_C2_DEVICE_INFO_SERVICE).map { it.uuid },
        )

        val control = services.getValue(BluetoothIds.UUID_C2_PM_CONTROL_SERVICE)
        assertEquals(2, control.size)
        assertEquals(
            Triple(false, true, false),
            control.single { it.uuid == BluetoothIds.UUID_C2_CSAFE_REQUEST }
                .let { Triple(it.readable, it.writable, it.notifiable) },
        )
        assertEquals(
            Triple(true, false, true),
            control.single { it.uuid == BluetoothIds.UUID_C2_CSAFE_RESPONSE }
                .let { Triple(it.readable, it.writable, it.notifiable) },
        )

        val erg = services.getValue(BluetoothIds.UUID_C2_ERG_SERVICE)
        assertTrue(erg.all { !it.writable || it.uuid == BluetoothIds.UUID_C2_STATUS_SAMPLE_RATE })
        // 0x003C-0x003F are absent on the pre-FTMS unit. 0x003D and 0x003E matter most:
        // ErgData subscribes to whichever of them it finds, and a failed subscription fails
        // its initialization, so not publishing them removes that branch outright.
        listOf(
            BluetoothIds.UUID_C2_ADDITIONAL_WORKOUT_SUMMARY_2,
            BluetoothIds.UUID_C2_FORCE_CURVE_DATA,
            BluetoothIds.UUID_C2_ADDITIONAL_STATUS_3,
            BluetoothIds.UUID_C2_LOGGED_WORKOUT,
        ).forEach { absent -> assertTrue(erg.none { it.uuid == absent }) }
        assertEquals(
            listOf(0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x3B, 0x80),
            erg.map { it.lowByte() },
        )
    }

    @Test
    fun `modern manifest keeps the surface an ErgData client initialized against`() {
        Concept2Machine.entries.forEach { machine ->
            val persona = SimulatorPersona.Concept2Pm5(
                machine,
                Concept2Pm5CompatibilityProfile.forMachine(machine, Concept2Pm5GattSurface.MODERN),
            )
            val services = Concept2Pm5GattProfile(persona).services
            assertEquals(
                listOf(
                    BluetoothIds.UUID_C2_DEVICE_INFO_SERVICE,
                    BluetoothIds.UUID_C2_PM_CONTROL_SERVICE,
                    BluetoothIds.UUID_C2_ERG_SERVICE,
                    BluetoothIds.UUID_C2_HEART_RATE_RECEIVE_SERVICE,
                ),
                services.keys.toList(),
            )
            assertEquals(
                listOf(0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x3B,
                    0x3C, 0x3D, 0x3E, 0x3F, 0x80),
                services.getValue(BluetoothIds.UUID_C2_ERG_SERVICE).map { it.lowByte() },
            )
            assertEquals(8, services.getValue(BluetoothIds.UUID_C2_DEVICE_INFO_SERVICE).size)
        }
    }

    /**
     * The 2026-09-08 capture shows ErgData reading only 0x0012, 0x0013 and 0x0014, writing 02
     * to 0x0034, then arming CCCDs on 0x003E, 0x003D, 0x0080 and 0x0022 — and skipping 0x0043
     * because it is absent. Both surfaces must therefore keep the objects ErgData requires;
     * only the optional pair may differ between them.
     */
    @Test
    fun `both surfaces satisfy every ErgData-required object`() {
        Concept2Pm5CompatibilityProfile.entries.forEach { compatibility ->
            val services = Concept2Pm5GattProfile(
                SimulatorPersona.Concept2Pm5(compatibility.machine, compatibility),
            ).services
            val info = services.getValue(BluetoothIds.UUID_C2_DEVICE_INFO_SERVICE).map { it.uuid }
            val control = services.getValue(BluetoothIds.UUID_C2_PM_CONTROL_SERVICE)
            val erg = services.getValue(BluetoothIds.UUID_C2_ERG_SERVICE)
            listOf(
                BluetoothIds.UUID_C2_SERIAL_NUMBER,
                BluetoothIds.UUID_C2_HARDWARE_REVISION,
                BluetoothIds.UUID_C2_FIRMWARE_REVISION,
            ).forEach { assertTrue(it in info) }
            assertTrue(control.any { it.uuid == BluetoothIds.UUID_C2_CSAFE_REQUEST && it.writable })
            control.single { it.uuid == BluetoothIds.UUID_C2_CSAFE_RESPONSE }.let {
                assertTrue(it.readable)
                assertTrue(it.notifiable)
            }
            assertTrue(erg.any { it.uuid == BluetoothIds.UUID_C2_STATUS_SAMPLE_RATE && it.writable })
            assertTrue(erg.any { it.uuid == BluetoothIds.UUID_C2_MULTIPLEXED_INFORMATION && it.notifiable })
            // Never published on either surface; ErgData is observed skipping it cleanly.
            assertTrue(erg.none { it.uuid.toString().startsWith("ce060043") })
        }
    }

    @Test
    fun `no PM5 persona advertises FTMS CSC or CPS`() {
        Concept2Pm5CompatibilityProfile.entries.forEach { compatibility ->
            val persona = SimulatorPersona.Concept2Pm5(compatibility.machine, compatibility)
            val plan = Concept2Pm5AdvertisingPlan.forPersona(persona)
            assertEquals(BluetoothIds.UUID_C2_DISCOVERY_SERVICE, plan.serviceUuid)
            assertTrue(plan.localName.startsWith("PM"))
            assertEquals(3 + 18, plan.advertisingBytes)
            assertEquals(2 + plan.localName.length, plan.scanResponseBytes)
        }
    }

    @Test
    fun `every machine and surface pair resolves to exactly one profile`() {
        Concept2Machine.entries.forEach { machine ->
            Concept2Pm5GattSurface.entries.forEach { surface ->
                val profile = Concept2Pm5CompatibilityProfile.forMachine(machine, surface)
                assertEquals(machine, profile.machine)
                assertEquals(surface, profile.gattSurface)
            }
        }
        assertEquals(
            Concept2Pm5CompatibilityProfile.entries.size,
            Concept2Pm5CompatibilityProfile.entries.map { it.serialNumber }.distinct().size,
        )
    }

    /** Low byte of a C2 UUID, which is also its multiplexed identification byte. */
    private fun Concept2CharacteristicManifest.lowByte(): Int =
        (uuid.mostSignificantBits ushr 32).toInt() and 0xFF
}
