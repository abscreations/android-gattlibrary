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
        assertEquals(options.lastIndex, precorIndex)
        assertEquals(
            LifeFitnessMachine.entries,
            options.take(precorIndex).filterIsInstance<SimulatorPersona.LifeFitness>().map { it.machine },
        )
        assertEquals(4, LifeFitnessMachine.entries.size)
    }
}
