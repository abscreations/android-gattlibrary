package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.simulatorPersonaOptions
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtServiceProfiles.FtmsMachineProfile
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.advertisedName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatorPersonaTest {
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
    fun `simulator selector places the four FE-C over BLE personas last, after Precor`() {
        // FE-C over BLE was added 2026-08-19, after every other persona here (plan
        // `docs/fe-c-ble_simulator_implementation.md` §3.3). Precor is no longer the list's last
        // entry; the four FE-C equipment types are. There is no separate "+ CPS" persona: the
        // Incyclist overlay is implicit to equipment type, not a UI-selectable option.
        val options = simulatorPersonaOptions()
        val precorIndex = options.indexOf(SimulatorPersona.PrecorChrono)
        assertTrue(precorIndex >= 0)
        assertEquals(
            FeCEquipmentType.entries,
            options.drop(precorIndex + 1).filterIsInstance<SimulatorPersona.FeCBle>().map { it.equipmentType },
        )
        assertEquals(4, FeCEquipmentType.entries.size)
        assertEquals(options.lastIndex, options.indexOfLast { it is SimulatorPersona.FeCBle })
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
        assertEquals(FeCBleSimulatorOverlay.NONE, SimulatorPersona.FeCBle(FeCEquipmentType.ROWER).overlay)
    }
}
