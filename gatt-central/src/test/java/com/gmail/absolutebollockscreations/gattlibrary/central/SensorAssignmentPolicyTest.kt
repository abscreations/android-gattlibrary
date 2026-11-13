package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigAppearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CSC feature classification and race resolution; moved from the app's SensorClassTest (LIB-66). */
class SensorAssignmentPolicyTest {
    @Test
    fun `CSC appearance and feature bits classify speed cadence and combined roles`() {
        assertEquals(
            setOf(BtEngineState.CscRole.SPEED),
            cscRoleCapabilityFromAppearance(SigAppearance.CYCLING_SPEED_SENSOR)?.roles,
        )
        assertEquals(
            setOf(BtEngineState.CscRole.CADENCE),
            cscRoleCapabilityFromAppearance(SigAppearance.CYCLING_CADENCE_SENSOR)?.roles,
        )
        assertEquals(
            BtEngineState.CscRole.entries.toSet(),
            cscRoleCapabilityFromAppearance(SigAppearance.CYCLING_SPEED_CADENCE_SENSOR)?.roles,
        )
        assertEquals(
            BtEngineState.CscRole.entries.toSet(),
            cscRoleCapabilityFromFeature(0x0003)?.roles,
        )
        assertEquals(null, cscRoleCapabilityFromFeature(0x0000))
    }

    @Test
    fun `Feature conflict keeps existing CSC and identifies newer link for disconnect`() {
        val cadence = cscRoleCapabilityFromFeature(0x0002)
        val handlers = listOf(
            ActiveCscHandler("AA:01#1", 1, "AA:01", cadence),
            ActiveCscHandler("AA:02#2", 2, "AA:02", null),
        )

        val resolution = resolveCscFeatureRoles(
            resolvedConnectionKey = "AA:02#2",
            featureBits = 0x0002,
            activeHandlers = handlers,
        )!!

        assertEquals("AA:02#2", resolution.disconnectConnectionKey)
        assertEquals("CSC role conflict: cadence already occupied", resolution.disconnectReason)
        assertEquals(cadence, resolution.capability)
        assertTrue(resolution.assignedRoles.isEmpty())
    }

    @Test
    fun `Feature resolution accepts complementary CSC after a provisional race`() {
        val handlers = listOf(
            ActiveCscHandler(
                "AA:01#1",
                1,
                "AA:01",
                cscRoleCapabilityFromFeature(0x0001),
            ),
            ActiveCscHandler("AA:02#2", 2, "AA:02", null),
        )

        val resolution = resolveCscFeatureRoles("AA:02#2", 0x0002, handlers)!!

        assertEquals(null, resolution.disconnectConnectionKey)
        assertEquals(null, resolution.disconnectReason)
        assertEquals(setOf(BtEngineState.CscRole.CADENCE), resolution.capability.roles)
        assertEquals(setOf(BtEngineState.CscRole.CADENCE), resolution.assignedRoles)
    }

    @Test
    fun `combined Feature support takes only the role left free by an older link`() {
        val speed = cscRoleCapabilityFromFeature(0x0001)
        val handlers = listOf(
            ActiveCscHandler(
                "AA:01#1",
                1,
                "AA:01",
                speed,
                assignedRoles = setOf(BtEngineState.CscRole.SPEED),
            ),
            ActiveCscHandler("AA:02#2", 2, "AA:02", null),
        )

        val resolution = resolveCscFeatureRoles("AA:02#2", 0x0003, handlers)!!

        assertEquals(BtEngineState.CscRole.entries.toSet(), resolution.capability.roles)
        assertEquals(setOf(BtEngineState.CscRole.CADENCE), resolution.assignedRoles)
        assertNull(resolution.disconnectConnectionKey)
    }

    @Test
    fun `Feature resolution honors a supported requested role and rejects unsupported requests`() {
        val requestedCadence = resolveCscFeatureRoles(
            "AA:01#1",
            0x0003,
            listOf(
                ActiveCscHandler(
                    "AA:01#1",
                    1,
                    "AA:01",
                    null,
                    requestedRoles = setOf(BtEngineState.CscRole.CADENCE),
                ),
            ),
        )!!
        assertEquals(setOf(BtEngineState.CscRole.CADENCE), requestedCadence.assignedRoles)
        assertNull(requestedCadence.disconnectConnectionKey)

        val unsupported = resolveCscFeatureRoles(
            "AA:02#2",
            0x0001,
            listOf(
                ActiveCscHandler(
                    "AA:02#2",
                    2,
                    "AA:02",
                    null,
                    requestedRoles = setOf(BtEngineState.CscRole.CADENCE),
                ),
            ),
        )!!
        assertEquals("AA:02#2", unsupported.disconnectConnectionKey)
        assertEquals("CSC requested role unsupported by Feature", unsupported.disconnectReason)
    }

    // Moved from the app's SensorClassTest (LIB-66). The app's add bar offers exactly the sensor
    // services below; checking every slot-bearing service is the stronger form of that gate.
    private val sensorServices = DeviceService.entries.filter { SensorAdmission.connectionLimit(it) > 0 }

    @Test
    fun `every sensor service can open a new link, not only join one`() {
        // The 2026-08-07 defect: RSC reached the add bar and same-link activation worked, but
        // connecting a dedicated footpod failed silently because connectSensor had no protocol.
        // sensorProtocolFor is the one answer both paths ask.
        assertEquals(
            setOf(DeviceService.HeartRate, DeviceService.CSC, DeviceService.RSC, DeviceService.Power),
            sensorServices.toSet(),
        )
        sensorServices.forEach {
            assertNotNull("$it has no connection protocol", sensorProtocolFor(BtEngineState.BtServiceType.valueOf(it.name)))
        }
    }

    @Test
    fun `CSC and CPS connection paths open with their handlers`() {
        assertEquals(ProtocolKey.CSC, sensorProtocolFor(BtEngineState.BtServiceType.CSC))
        assertEquals(ProtocolKey.CPS, sensorProtocolFor(BtEngineState.BtServiceType.Power))
        // FTMS is a machine, admitted through the machine path, never as a sensor.
        assertNull(sensorProtocolFor(BtEngineState.BtServiceType.FTMS))
    }

    @Test
    fun `every sensor service has a handler the standard factory can build`() {
        val factory = HandlerFactory.standard(enablePhase05HardwareTest = false)
        sensorServices.forEach {
            val protocol = sensorProtocolFor(BtEngineState.BtServiceType.valueOf(it.name))!!
            assertNotNull("$it is offered to users but cannot be built", factory.create(protocol))
        }
    }
}
