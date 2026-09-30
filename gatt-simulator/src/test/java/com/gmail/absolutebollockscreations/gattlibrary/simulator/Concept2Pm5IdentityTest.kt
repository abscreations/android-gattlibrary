package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.central.FixtureJson
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.advertisedName
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.Concept2Uuids

class Concept2Pm5IdentityTest {
    @Test
    fun `hardware corpus pins the modern RowErg discovery and disconnect exchange`() {
        val text = requireNotNull(javaClass.classLoader?.getResource(
            "fixtures/hardware/concept2_pm5_431225189_20260912.json",
        )).readText()
        val root = FixtureJson.obj(FixtureJson.parse(text))
        val hardware = FixtureJson.obj(root["hardware"])
        val advertising = FixtureJson.obj(root["advertising"])
        val disconnect = FixtureJson.obj(root["ergdata_disconnect_pm5"])

        assertEquals("H", FixtureJson.string(root["evidence_tier"]))
        assertEquals("PM5 431225189 Row", FixtureJson.string(hardware["advertised_name"]))
        assertEquals(
            "03190000020106020a041209504d352034333132323531383920526f77",
            FixtureJson.string(advertising["primary_payload_hex"]),
        )
        assertEquals(
            "030326181107669a0c2000086c91e411e543000006ce06162618011000",
            FixtureJson.string(advertising["scan_response_payload_hex"]),
        )
        assertEquals("01f176041302010260f2", FixtureJson.string(disconnect["request_hex"]))
        assertEquals("f189760113edf2", FixtureJson.string(disconnect["response_hex"]))
    }

    @Test
    fun `modern advertisement carries name while scan response carries both services and FTMS data`() {
        Concept2Machine.entries.forEach { machine ->
            val plan = Concept2Pm5AdvertisingPlan.forPersona(SimulatorPersona.Concept2Pm5(machine))
            assertTrue(plan.includeNameInAdvertisement)
            assertTrue(plan.includeTxPowerInAdvertisement)
            assertTrue(plan.advertisingServiceUuids.isEmpty())
            assertEquals(
                listOf(FtmsUuids.UUID_FTMS_SERVICE, Concept2Uuids.UUID_C2_DISCOVERY_SERVICE),
                plan.scanResponseServiceUuids,
            )
            assertArrayEquals(
                if (machine == Concept2Machine.BIKE_ERG) byteArrayOf(0x01, 0x20, 0x00)
                else byteArrayOf(0x01, 0x10, 0x00),
                plan.scanResponseFtmsServiceData,
            )
            assertEquals(3 + 3 + 2 + plan.localName.length, plan.advertisingBytes)
            assertEquals(29, plan.scanResponseBytes)
            assertTrue(plan.advertisingBytes <= 31)
            assertTrue(plan.scanResponseBytes <= 31)
        }
    }

    @Test
    fun `device information has documented widths machine values and offset behavior`() {
        Concept2Machine.entries.forEach { machine ->
            val profile = Concept2Pm5GattProfile(SimulatorPersona.Concept2Pm5(machine))
            assertEquals(9, profile.readDeviceInformation(Concept2Uuids.UUID_C2_SERIAL_NUMBER, 0)!!.size)
            assertEquals(3, profile.readDeviceInformation(Concept2Uuids.UUID_C2_HARDWARE_REVISION, 0)!!.size)
            assertEquals(20, profile.readDeviceInformation(Concept2Uuids.UUID_C2_FIRMWARE_REVISION, 0)!!.size)
            assertArrayEquals(
                machine.let { SimulatorPersona.Concept2Pm5(it).compatibility.firmwareIdentity }
                    .toByteArray(Charsets.US_ASCII),
                profile.readDeviceInformation(Concept2Uuids.UUID_C2_FIRMWARE_REVISION, 0),
            )
            assertEquals(16, profile.readDeviceInformation(Concept2Uuids.UUID_C2_MANUFACTURER_NAME, 0)!!.size)
            assertEquals(16, profile.readDeviceInformation(Concept2Uuids.UUID_C2_MODEL_NUMBER, 0)!!.size)
            assertArrayEquals(
                byteArrayOf(machine.wireMachineType.toByte()),
                profile.readDeviceInformation(Concept2Uuids.UUID_C2_MACHINE_TYPE, 0),
            )
            assertArrayEquals(byteArrayOf(0x05, 0x02), profile.readDeviceInformation(Concept2Uuids.UUID_C2_ATT_RECEIVE_MTU, 0, 517))
            assertArrayEquals(byteArrayOf(0x02), profile.readDeviceInformation(Concept2Uuids.UUID_C2_ATT_RECEIVE_MTU, 1, 517))
            assertArrayEquals(byteArrayOf(), profile.readDeviceInformation(Concept2Uuids.UUID_C2_SERIAL_NUMBER, 9))
            assertNull(profile.readDeviceInformation(Concept2Uuids.UUID_C2_SERIAL_NUMBER, 10))
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
                Concept2Uuids.UUID_C2_DEVICE_INFO_SERVICE,
                Concept2Uuids.UUID_C2_PM_CONTROL_SERVICE,
                Concept2Uuids.UUID_C2_ERG_SERVICE,
            ),
            services.keys.toList(),
        )
        assertFalse(services.containsKey(Concept2Uuids.UUID_C2_HEART_RATE_RECEIVE_SERVICE))

        // Device Information as the pre-FTMS unit exposes it: 0x0012-0x0015 and nothing else.
        assertEquals(
            listOf(
                Concept2Uuids.UUID_C2_SERIAL_NUMBER,
                Concept2Uuids.UUID_C2_HARDWARE_REVISION,
                Concept2Uuids.UUID_C2_FIRMWARE_REVISION,
                Concept2Uuids.UUID_C2_MANUFACTURER_NAME,
            ),
            services.getValue(Concept2Uuids.UUID_C2_DEVICE_INFO_SERVICE).map { it.uuid },
        )

        val control = services.getValue(Concept2Uuids.UUID_C2_PM_CONTROL_SERVICE)
        assertEquals(2, control.size)
        assertEquals(
            Triple(false, true, false),
            control.single { it.uuid == Concept2Uuids.UUID_C2_CSAFE_REQUEST }
                .let { Triple(it.readable, it.writable, it.notifiable) },
        )
        assertEquals(
            Triple(true, false, true),
            control.single { it.uuid == Concept2Uuids.UUID_C2_CSAFE_RESPONSE }
                .let { Triple(it.readable, it.writable, it.notifiable) },
        )

        val erg = services.getValue(Concept2Uuids.UUID_C2_ERG_SERVICE)
        assertTrue(erg.all { !it.writable || it.uuid == Concept2Uuids.UUID_C2_STATUS_SAMPLE_RATE })
        // 0x003C-0x003F are absent on the pre-FTMS unit. 0x003D and 0x003E matter most:
        // ErgData subscribes to whichever of them it finds, and a failed subscription fails
        // its initialization, so not publishing them removes that branch outright.
        listOf(
            Concept2Uuids.UUID_C2_ADDITIONAL_WORKOUT_SUMMARY_2,
            Concept2Uuids.UUID_C2_FORCE_CURVE_DATA,
            Concept2Uuids.UUID_C2_ADDITIONAL_STATUS_3,
            Concept2Uuids.UUID_C2_LOGGED_WORKOUT,
        ).forEach { absent -> assertTrue(erg.none { it.uuid == absent }) }
        assertEquals(
            listOf(0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x3B, 0x80),
            erg.map { it.lowByte() },
        )
    }

    @Test
    fun `modern manifest mirrors the captured 207 RowErg surface`() {
        Concept2Machine.entries.forEach { machine ->
            val persona = SimulatorPersona.Concept2Pm5(
                machine,
                Concept2Pm5CompatibilityProfile.forMachine(machine, Concept2Pm5GattSurface.MODERN),
            )
            val services = Concept2Pm5GattProfile(persona).services
            assertEquals(
                listOf(
                    Concept2Uuids.UUID_C2_PM_CONTROL_SERVICE,
                    Concept2Uuids.UUID_C2_DEVICE_INFO_SERVICE,
                    Concept2Uuids.UUID_C2_ERG_SERVICE,
                    FtmsUuids.UUID_FTMS_SERVICE,
                ),
                services.keys.toList(),
            )
            assertEquals(
                listOf(0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x3B,
                    0x80, 0x3D),
                services.getValue(Concept2Uuids.UUID_C2_ERG_SERVICE).map { it.lowByte() },
            )
            assertEquals(8, services.getValue(Concept2Uuids.UUID_C2_DEVICE_INFO_SERVICE).size)
            assertFalse(services.containsKey(Concept2Uuids.UUID_C2_HEART_RATE_RECEIVE_SERVICE))
            assertEquals(
                listOf(
                    FtmsUuids.UUID_FITNESS_MACHINE_FEATURE,
                    if (machine == Concept2Machine.BIKE_ERG) FtmsUuids.UUID_BIKING_DATA
                    else FtmsUuids.UUID_ROWING_MACHINE_DATA,
                ),
                services.getValue(FtmsUuids.UUID_FTMS_SERVICE).map { it.uuid },
            )
        }
    }

    /** Both surfaces keep the identity, CSAFE transport, sample-rate control, and multiplex path. */
    @Test
    fun `both surfaces satisfy every ErgData-required object`() {
        Concept2Pm5CompatibilityProfile.entries.forEach { compatibility ->
            val services = Concept2Pm5GattProfile(
                SimulatorPersona.Concept2Pm5(compatibility.machine, compatibility),
            ).services
            val info = services.getValue(Concept2Uuids.UUID_C2_DEVICE_INFO_SERVICE).map { it.uuid }
            val control = services.getValue(Concept2Uuids.UUID_C2_PM_CONTROL_SERVICE)
            val erg = services.getValue(Concept2Uuids.UUID_C2_ERG_SERVICE)
            listOf(
                Concept2Uuids.UUID_C2_SERIAL_NUMBER,
                Concept2Uuids.UUID_C2_HARDWARE_REVISION,
                Concept2Uuids.UUID_C2_FIRMWARE_REVISION,
            ).forEach { assertTrue(it in info) }
            assertTrue(control.any { it.uuid == Concept2Uuids.UUID_C2_CSAFE_REQUEST && it.writable })
            control.single { it.uuid == Concept2Uuids.UUID_C2_CSAFE_RESPONSE }.let {
                assertEquals(
                    compatibility.gattSurface == Concept2Pm5GattSurface.PRE_FTMS,
                    it.readable,
                )
                assertTrue(it.notifiable)
            }
            assertTrue(erg.any { it.uuid == Concept2Uuids.UUID_C2_STATUS_SAMPLE_RATE && it.writable })
            assertTrue(erg.any { it.uuid == Concept2Uuids.UUID_C2_MULTIPLEXED_INFORMATION && it.notifiable })
            // Never published on either surface; ErgData is observed skipping it cleanly.
            assertTrue(erg.none { it.uuid.toString().startsWith("ce060043") })
        }
    }

    @Test
    fun `pre-FTMS advertises only Concept2 while modern scan response includes FTMS`() {
        Concept2Pm5CompatibilityProfile.entries.forEach { compatibility ->
            val persona = SimulatorPersona.Concept2Pm5(compatibility.machine, compatibility)
            val plan = Concept2Pm5AdvertisingPlan.forPersona(persona)
            assertTrue(plan.localName.startsWith("PM"))
            if (compatibility.gattSurface == Concept2Pm5GattSurface.MODERN) {
                assertTrue(FtmsUuids.UUID_FTMS_SERVICE in plan.scanResponseServiceUuids)
                assertTrue(Concept2Uuids.UUID_C2_DISCOVERY_SERVICE in plan.scanResponseServiceUuids)
            } else {
                assertEquals(listOf(Concept2Uuids.UUID_C2_DISCOVERY_SERVICE), plan.advertisingServiceUuids)
                assertEquals(3 + 18, plan.advertisingBytes)
                assertEquals(2 + plan.localName.length, plan.scanResponseBytes)
            }
        }
    }

    @Test
    fun `modern RowErg identity and FTMS feature are capture exact`() {
        val persona = SimulatorPersona.Concept2Pm5(Concept2Machine.ROW_ERG)
        val profile = Concept2Pm5GattProfile(persona)
        assertEquals("PM5 431225189 Row", persona.advertisedName)
        assertArrayEquals("431225189".toByteArray(), profile.readDeviceInformation(Concept2Uuids.UUID_C2_SERIAL_NUMBER, 0))
        assertArrayEquals("903".toByteArray(), profile.readDeviceInformation(Concept2Uuids.UUID_C2_HARDWARE_REVISION, 0))
        assertArrayEquals(
            "8200-000409-207.000\u0000".toByteArray(),
            profile.readDeviceInformation(Concept2Uuids.UUID_C2_FIRMWARE_REVISION, 0),
        )
        assertArrayEquals(
            byteArrayOf(0xA6.toByte(), 0x56, 0, 0, 0, 0, 0, 0),
            profile.readFtms(FtmsUuids.UUID_FITNESS_MACHINE_FEATURE),
        )
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
