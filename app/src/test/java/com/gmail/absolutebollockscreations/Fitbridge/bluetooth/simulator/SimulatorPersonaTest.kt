package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.simulatorPersonaOptions
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtServiceProfiles.FtmsMachineProfile
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.advertisedName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatorPersonaTest {
    @Test
    fun `the two native sensor personas lead, then every standard FTMS persona`() {
        // The footpod joined the head of the list on 2026-09-02. It belongs there rather than
        // among the capture-backed consoles below: like the indoor bike it publishes native
        // sensor services rather than a fitness machine, and neither accepts control.
        val options = simulatorPersonaOptions(includeKickr = false)
        assertEquals(SimulatorPersona.IndoorBikeSpeedAndPower, options.first())
        assertEquals(SimulatorPersona.StrydFootpod, options[1])
        assertEquals(
            FtmsMockEngine.supportedMachines,
            options.drop(2)
                .take(FtmsMockEngine.supportedMachines.size)
                .filterIsInstance<SimulatorPersona.Ftms>()
                .map { it.profile },
        )
    }

    @Test
    fun `footpod persona advertises the captured local name and routes to no standard surface`() {
        assertEquals("Stryd", SimulatorPersona.StrydFootpod.advertisedName)
        // It is not an FTMS machine and not the native-bike surface, so it must own its own
        // controller rather than fall through to the shared FTMS/CSC/CPS provider.
        assertNull(SimulatorPersona.StrydFootpod.standardSurface())
    }

    @Test
    fun `speed and power persona is named for the two services it publishes`() {
        // It used to alias the FTMS bike's name, so both personas appeared on a scan list as
        // "FBRG-Bike" and only service discovery could tell them apart.
        assertEquals(
            "CSC/CPS",
            SimulatorPersona.IndoorBikeSpeedAndPower.advertisedName,
        )
        assertNotEquals(
            FtmsMachineProfile.INDOOR_BIKE.advertisedName,
            SimulatorPersona.IndoorBikeSpeedAndPower.advertisedName,
        )
    }

    @Test
    fun `speed and power persona routes to indoor bike with native sensors forced`() {
        val surface = requireNotNull(SimulatorPersona.IndoorBikeSpeedAndPower.standardSurface())
        assertEquals(FtmsMachineProfile.INDOOR_BIKE, surface.profile)
        assertTrue(surface.nativeBikeSensors)

        val ordinaryBike = requireNotNull(
            SimulatorPersona.Ftms(FtmsMachineProfile.INDOOR_BIKE).standardSurface(),
        )
        assertFalse(ordinaryBike.nativeBikeSensors)
    }

    @Test
    fun `FTMS persona preserves its existing advertised name`() {
        FtmsMachineProfile.entries.forEach { profile ->
            assertEquals(profile.advertisedName, SimulatorPersona.Ftms(profile).advertisedName)
        }
    }

    @Test
    fun `physical PM5 personas have stable valid identities`() {
        assertEquals(listOf(0, 128, 192), Concept2Machine.entries.map { it.wireMachineType })
        Concept2Machine.entries.forEach { machine ->
            val persona = SimulatorPersona.Concept2Pm5(machine)
            assertEquals(9, persona.serialNumber.length)
            assertTrue(persona.serialNumber.all(Char::isDigit))
            assertEquals("PM5 ${persona.serialNumber}", persona.advertisedName)
        }
    }

    @Test
    fun `baseline manifest includes measurement services and no controls`() {
        val uuids = Concept2FirmwareProfile.PM5_V1_30.characteristics.map { it.uuid }.toSet()
        assertTrue(BluetoothIds.UUID_C2_GENERAL_STATUS in uuids)
        assertTrue(BluetoothIds.UUID_C2_MULTIPLEXED_INFORMATION in uuids)
        assertFalse(uuids.any { it.toString().startsWith("ce06002") })
    }

    @Test
    fun `Precor Chrono persona has the explicit simulator identity`() {
        assertEquals("CHRONO 0", SimulatorPersona.PrecorChrono.advertisedName)
    }

    @Test
    fun `Keiser personas have distinct M3 discovery identities and units`() {
        val personas = KeiserMSeriesSimulatorProfile.entries.map { SimulatorPersona.KeiserMSeries(it) }
        assertEquals(listOf("M3i", "M3m", "M3o"), personas.map { it.advertisedName })
        assertEquals(listOf(1, 2, 3), personas.map { it.profile.systemId })
        assertEquals(
            listOf(
                KeiserMSeriesSimulatorDistanceUnit.MILES,
                KeiserMSeriesSimulatorDistanceUnit.KILOMETRES,
                KeiserMSeriesSimulatorDistanceUnit.MILES,
            ),
            personas.map { it.profile.distanceUnit },
        )
    }

    @Test
    fun `simulator selector places the four Life Fitness personas before Precor`() {
        val options = simulatorPersonaOptions()
        val precorIndex = options.indexOf(SimulatorPersona.PrecorChrono)
        assertTrue(precorIndex >= 0)
        assertEquals(
            LifeFitnessMachine.entries,
            options.take(precorIndex).filterIsInstance<SimulatorPersona.LifeFitness>().map { it.machine },
        )
        assertEquals(4, LifeFitnessMachine.entries.size)
    }

    @Test
    fun `simulator selector orders Keiser then Precor then Schwinn`() {
        val options = simulatorPersonaOptions(includeKickr = false)
        val keiserPersonas = KeiserMSeriesSimulatorProfile.entries.map { SimulatorPersona.KeiserMSeries(it) }
        val keiser = options.indexOf(keiserPersonas.first())
        assertTrue(keiser >= 0)
        assertEquals(keiserPersonas, options.subList(keiser, keiser + 3))
        assertEquals(SimulatorPersona.PrecorChrono, options[keiser + 3])
        assertEquals(SimulatorPersona.SchwinnX70, options[keiser + 4])
        assertTrue(options[keiser - 1] is SimulatorPersona.LifeFitness)
    }

    @Test
    fun `simulator selector places generic FE-C personas then Tacx identities after Precor`() {
        // FE-C over BLE was added 2026-08-19, after every other persona here (plan
        // `docs/fe-c-ble_simulator_implementation.md` §3.3). Precor is no longer the list's last
        // entry; Schwinn and the seven FE-C equipment types follow it. There is no separate "+ CPS" persona: the
        // Incyclist overlay is implicit to equipment type, not a UI-selectable option.
        val options = simulatorPersonaOptions()
        val precorIndex = options.indexOf(SimulatorPersona.PrecorChrono)
        assertTrue(precorIndex >= 0)
        val fec = options.drop(precorIndex + 1).filterIsInstance<SimulatorPersona.FeCBle>()
        assertEquals(
            FeCEquipmentType.entries,
            fec.take(FeCEquipmentType.entries.size).map { it.equipmentType },
        )
        assertEquals(7, FeCEquipmentType.entries.size)
        assertEquals(
            listOf(FeCBleSimulatorIdentity.TACX_NEO, FeCBleSimulatorIdentity.TACX_FLUX_2),
            fec.takeLast(2).map { it.identity },
        )
        assertEquals(
            options.indexOfFirst { it is SimulatorPersona.Kickr } - 1,
            options.indexOfLast { it is SimulatorPersona.FeCBle },
        )
    }

    @Test
    fun `debug selector adds all KICKR and captured Hub personas in evidence order`() {
        val kickr = simulatorPersonaOptions(includeKickr = true)
            .filterIsInstance<SimulatorPersona.Kickr>()

        assertEquals(KickrSimulatorPersona.entries, kickr.map { it.device })
        assertEquals(
            KickrSimulatorPersona.entries.map { KickrPersonaManifest.forPersona(it).advertising.localName },
            kickr.map { it.advertisedName },
        )
    }

    @Test
    fun `release selector contains no KICKR persona`() {
        assertTrue(simulatorPersonaOptions(includeKickr = false).none { it is SimulatorPersona.Kickr })
    }

    @Test
    fun `Tacx Neo identity uses a synthetic serial suffix and captured software revisions`() {
        val neo = SimulatorPersona.FeCBle(FeCEquipmentType.TRAINER, identity = FeCBleSimulatorIdentity.TACX_NEO)
        assertEquals("Tacx Neo 90002", neo.advertisedName)
        assertEquals(0, neo.identity.supplementalRevision)
        assertEquals(7, neo.identity.mainRevision)
    }

    @Test
    fun `Tacx Flux 2 follows Neo and uses a synthetic physical-name suffix`() {
        val tacx = simulatorPersonaOptions(includeKickr = false)
            .filterIsInstance<SimulatorPersona.FeCBle>()
            .filter { it.identity != FeCBleSimulatorIdentity.GENERIC }
        assertEquals(
            listOf(FeCBleSimulatorIdentity.TACX_NEO, FeCBleSimulatorIdentity.TACX_FLUX_2),
            tacx.map { it.identity },
        )
        assertEquals("Tacx Flux-2 90001", tacx.last().advertisedName)
    }

    @Test
    fun `FE-C over BLE persona control defaults match the plan's equipment table`() {
        FeCEquipmentType.entries.forEach { equipmentType ->
            val persona = SimulatorPersona.FeCBle(equipmentType)
            assertEquals(equipmentType.supportsControl, persona.controlEnabled)
        }
        assertTrue(SimulatorPersona.FeCBle(FeCEquipmentType.TRAINER).controlEnabled)
        assertFalse(SimulatorPersona.FeCBle(FeCEquipmentType.STATIONARY_BIKE).controlEnabled)
    }

    @Test
    fun `FE-C over BLE persona CPS overlay is implicit to equipment type, not user-selectable`() {
        // Trainer and Stationary Bike always carry the Incyclist CPS overlay; Treadmill and Rower
        // never do. There is no constructor path that produces a Trainer without it or a Treadmill
        // with it unless a test explicitly overrides `overlay`, which the plan is explicit is a
        // test configuration and not a physical-equipment claim (mirrors `controlEnabled`).
        assertEquals(
            FeCBleSimulatorOverlay.CPS_INCYCLIST_COMPAT,
            SimulatorPersona.FeCBle(FeCEquipmentType.TRAINER).overlay,
        )
        assertEquals(
            FeCBleSimulatorOverlay.CPS_INCYCLIST_COMPAT,
            SimulatorPersona.FeCBle(FeCEquipmentType.STATIONARY_BIKE).overlay,
        )
        assertEquals(FeCBleSimulatorOverlay.NONE, SimulatorPersona.FeCBle(FeCEquipmentType.TREADMILL).overlay)
        assertEquals(FeCBleSimulatorOverlay.NONE, SimulatorPersona.FeCBle(FeCEquipmentType.ELLIPTICAL).overlay)
        assertEquals(FeCBleSimulatorOverlay.NONE, SimulatorPersona.FeCBle(FeCEquipmentType.ROWER).overlay)
        assertEquals(FeCBleSimulatorOverlay.NONE, SimulatorPersona.FeCBle(FeCEquipmentType.CLIMBER).overlay)
        assertEquals(FeCBleSimulatorOverlay.NONE, SimulatorPersona.FeCBle(FeCEquipmentType.NORDIC_SKIER).overlay)
    }
}
