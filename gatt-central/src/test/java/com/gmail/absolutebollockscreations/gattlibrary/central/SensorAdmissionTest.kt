package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorAdmissionTest {
    private val speed = SensorRoleCapability(setOf(SensorRole.SPEED), SensorRoleEvidence.FEATURE)
    private val cadence = SensorRoleCapability(setOf(SensorRole.CADENCE), SensorRoleEvidence.FEATURE)
    private val both = SensorRoleCapability(SensorRole.entries.toSet(), SensorRoleEvidence.FEATURE)

    private fun link(
        address: String,
        service: DeviceService,
        status: ConnectionStatus = ConnectionStatus.CONNECTED,
        capability: SensorRoleCapability? = null,
        assigned: Set<SensorRole> = emptySet(),
    ) = SensorLink(address, status, setOf(service), capability, assigned)

    @Test
    fun `connection limits match the engine for every service`() {
        DeviceService.entries.forEach {
            assertEquals(it.name, sensorConnectionLimit(BtEngineState.BtServiceType.valueOf(it.name)), SensorAdmission.connectionLimit(it))
        }
        assertEquals(2, SensorAdmission.connectionLimit(DeviceService.CSC))
        assertEquals(0, SensorAdmission.connectionLimit(DeviceService.FTMS))
    }

    @Test
    fun `appearance maps to a capability with appearance evidence`() {
        assertEquals(setOf(SensorRole.SPEED), SensorAdmission.roleCapabilityFromAppearance(APPEARANCE_CYCLING_SPEED_SENSOR)?.roles)
        assertEquals(setOf(SensorRole.CADENCE), SensorAdmission.roleCapabilityFromAppearance(APPEARANCE_CYCLING_CADENCE_SENSOR)?.roles)
        val combined = SensorAdmission.roleCapabilityFromAppearance(APPEARANCE_CYCLING_SPEED_CADENCE_SENSOR)
        assertEquals(SensorRole.entries.toSet(), combined?.roles)
        assertEquals(SensorRoleEvidence.APPEARANCE, combined?.evidence)
        assertNull(SensorAdmission.roleCapabilityFromAppearance(null))
        assertNull(SensorAdmission.roleCapabilityFromAppearance(0x0340))
    }

    @Test
    fun `an unclassified sensor occupies both roles`() {
        assertEquals(SensorRole.entries.toSet(), SensorAdmission.occupiedRoles(null))
        assertEquals(setOf(SensorRole.SPEED), SensorAdmission.occupiedRoles(speed))
    }

    @Test
    fun `speed and cadence sensors can coexist but two speed sensors cannot`() {
        val existing = listOf(SensorRoleAssignment("AA:01", speed))
        assertTrue(SensorAdmission.canAssignRole("AA:02", cadence, existing))
        assertFalse(SensorAdmission.canAssignRole("AA:02", speed, existing))
        assertTrue("same device is idempotent", SensorAdmission.canAssignRole("aa:01", speed, existing))
    }

    @Test
    fun `a third cycling sensor is refused even when roles are free`() {
        val existing = listOf(SensorRoleAssignment("AA:01", speed), SensorRoleAssignment("AA:02", cadence))
        assertFalse(SensorAdmission.canAssignRole("AA:03", both, existing))
    }

    @Test
    fun `service admission honours limits, idempotence and disconnected links`() {
        val hr = listOf(link("AA:01", DeviceService.HeartRate))
        assertFalse(SensorAdmission.canAssignService("AA:02", DeviceService.HeartRate, hr))
        assertTrue(SensorAdmission.canAssignService(" aa:01 ", DeviceService.HeartRate, hr))
        assertFalse(SensorAdmission.canAssignService("", DeviceService.HeartRate, emptyList()))
        assertFalse(SensorAdmission.canAssignService("AA:09", DeviceService.FTMS, emptyList()))
        val gone = listOf(link("AA:01", DeviceService.HeartRate, ConnectionStatus.DISCONNECTED))
        assertTrue(SensorAdmission.canAssignService("AA:02", DeviceService.HeartRate, gone))
    }

    @Test
    fun `a connecting or disconnecting link still holds its slot`() {
        listOf(ConnectionStatus.CONNECTING, ConnectionStatus.DISCONNECTING).forEach {
            val links = listOf(link("AA:01", DeviceService.Power, it))
            assertFalse(it.name, SensorAdmission.canAssignService("AA:02", DeviceService.Power, links))
        }
    }

    @Test
    fun `csc service admission uses the roles each link holds`() {
        val links = listOf(link("AA:01", DeviceService.CSC, capability = speed, assigned = setOf(SensorRole.SPEED)))
        assertTrue(SensorAdmission.canAssignService("AA:02", DeviceService.CSC, links, cadence))
        assertFalse(SensorAdmission.canAssignService("AA:02", DeviceService.CSC, links, speed))
    }

    @Test
    fun `active connection count is per distinct device`() {
        val links = listOf(
            link("AA:01", DeviceService.CSC),
            link("aa:01", DeviceService.CSC),
            link("AA:02", DeviceService.CSC, ConnectionStatus.DISCONNECTED),
            link("AA:03", DeviceService.HeartRate),
        )
        assertEquals(1, SensorAdmission.activeConnectionCount(links, DeviceService.CSC))
        assertEquals(1, SensorAdmission.activeConnectionCount(links, DeviceService.HeartRate))
    }

    @Test
    fun `single-instance sensors allow the same device idempotently and reject another`() {
        listOf(DeviceService.HeartRate, DeviceService.RSC, DeviceService.Power).forEach { service ->
            val active = listOf(link("AA:01", service))
            assertTrue(service.name, SensorAdmission.canAssignService("aa:01", service, active))
            assertFalse(service.name, SensorAdmission.canAssignService("AA:02", service, active))
        }
    }

    @Test
    fun `csc admits only complementary classified roles`() {
        val speedOnly = SensorAdmission.roleCapabilityFromAppearance(APPEARANCE_CYCLING_SPEED_SENSOR)!!
        val cadenceOnly = SensorAdmission.roleCapabilityFromAppearance(APPEARANCE_CYCLING_CADENCE_SENSOR)!!
        val combined = SensorAdmission.roleCapabilityFromAppearance(APPEARANCE_CYCLING_SPEED_CADENCE_SENSOR)!!
        val active = listOf(link("AA:01", DeviceService.CSC, capability = speedOnly))

        assertTrue(SensorAdmission.canAssignService("AA:02", DeviceService.CSC, active, cadenceOnly))
        assertFalse(SensorAdmission.canAssignService("AA:02", DeviceService.CSC, active, speedOnly))
        assertTrue(SensorAdmission.canAssignService("AA:02", DeviceService.CSC, active, combined))
    }

    @Test
    fun `an assigned combined or unresolved csc blocks a second while the same device stays idempotent`() {
        val combined = SensorAdmission.roleCapabilityFromAppearance(APPEARANCE_CYCLING_SPEED_CADENCE_SENSOR)!!
        val cadenceOnly = SensorAdmission.roleCapabilityFromAppearance(APPEARANCE_CYCLING_CADENCE_SENSOR)
        listOf(combined, null).forEach { capability ->
            val active = listOf(
                link("AA:01", DeviceService.CSC, capability = capability, assigned = capability?.roles.orEmpty()),
            )
            assertFalse(SensorAdmission.canAssignService("AA:02", DeviceService.CSC, active, cadenceOnly))
            assertTrue(SensorAdmission.canAssignService("aa:01", DeviceService.CSC, active, null))
        }
    }
}
