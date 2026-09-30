package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids

class CpsProductionPlumbingTest {
    @Test
    fun `Measurement is mandatory and reads precede subscription`() {
        assertFalse(planCpsDiscovery(emptySet()).activate)
        assertFalse(planCpsDiscovery(setOf(SigUuids.UUID_CYCLING_POWER_FEATURE)).activate)
        val plan = planCpsDiscovery(
            setOf(
                SigUuids.UUID_CYCLING_POWER_MEASUREMENT,
                SigUuids.UUID_CYCLING_POWER_FEATURE,
                SigUuids.UUID_SENSOR_LOCATION,
            ),
        )
        assertTrue(plan.activate)
        assertEquals(
            listOf(
                CpsGattOperation(CpsGattOperationKind.READ, SigUuids.UUID_CYCLING_POWER_FEATURE),
                CpsGattOperation(CpsGattOperationKind.READ, SigUuids.UUID_SENSOR_LOCATION),
                CpsGattOperation(CpsGattOperationKind.ENABLE_NOTIFICATIONS, SigUuids.UUID_CYCLING_POWER_MEASUREMENT),
            ),
            plan.operations,
        )
    }

    @Test
    fun `Feature and location reads decode with explicit unavailable outcomes`() {
        val feature = decodeCpsRead(
            SigUuids.UUID_CYCLING_POWER_FEATURE,
            byteArrayOf(0x04, 0x03, 0x02, 0x81.toByte()),
            success = true,
        ) as CpsReadOutcome.Features
        assertEquals(0x81020304L, feature.bits)
        assertEquals(
            CpsReadOutcome.SensorLocation(13),
            decodeCpsRead(SigUuids.UUID_SENSOR_LOCATION, byteArrayOf(13), success = true),
        )
        assertEquals(
            CpsReadOutcome.Unavailable(SigUuids.UUID_CYCLING_POWER_FEATURE),
            decodeCpsRead(SigUuids.UUID_CYCLING_POWER_FEATURE, byteArrayOf(1, 2, 3), true),
        )
        assertEquals(CpsReadOutcome.Ignored, decodeCpsRead(UUID.randomUUID(), byteArrayOf(), false))
    }

    @Test
    fun `factory and sensor paths register fresh CPS handlers`() {
        val factory = HandlerFactory.standard(enablePhase05HardwareTest = false)
        val first = factory.create(ProtocolKey.CPS)
        val second = factory.create(ProtocolKey.CPS)
        assertTrue(first is CpsProtocolHandler)
        assertTrue(second is CpsProtocolHandler)
        assertNotSame(first, second)
        assertEquals(ProtocolKey.CPS, sensorProtocolFor(BtEngineState.BtServiceType.Power))
    }

    @Test
    fun `CPS activates beside FTMS CSC and RSC on one link with exact ownership`() {
        val cps = CpsProtocolHandler()
        val csc = CscProtocolHandler()
        val rsc = RscProtocolHandler()
        val ftms = FtmsProtocolHandler()
        val active = activateMatchingProtocolHandlers(
            listOf(ftms, csc, rsc, cps),
            setOf(
                FtmsUuids.UUID_FTMS_SERVICE,
                SigUuids.UUID_CSC_SERVICE,
                SigUuids.UUID_RSC_SERVICE,
                SigUuids.UUID_CYCLING_POWER_SERVICE,
            ),
            offer = { true },
        )
        assertEquals(4, active.size)
        val route = resolveProtocolHandlerRoute(
            active,
            SigUuids.UUID_CYCLING_POWER_MEASUREMENT,
            SigUuids.UUID_CYCLING_POWER_SERVICE,
        )
        assertEquals(listOf(cps), route.owners)
        assertSame(cps, route.owners.single())
        assertNull(resolveProtocolHandlerRoute(active, UUID.randomUUID(), null).ownership)
    }

    @Test
    fun `fresh CPS handlers own independent event baselines`() {
        val first = CpsProtocolHandler()
        val second = CpsProtocolHandler()
        val initial = hex("20 00 64 00 0A 00 00 10")
        val next = hex("20 00 65 00 0C 00 00 18")
        assertNull(first.acceptMeasurement(initial)?.crankDelta)
        assertEquals(2, first.acceptMeasurement(next)?.crankDelta?.revolutions)
        assertNull(second.acceptMeasurement(next)?.crankDelta)
    }

    @Test
    fun `CPS CCCD failure prevents readiness`() {
        val cccd = CccdState()
        cccd.mergeEnablePlan(
            listOf(
                CccdTarget(
                    SigUuids.UUID_CYCLING_POWER_SERVICE,
                    SigUuids.UUID_CYCLING_POWER_MEASUREMENT,
                ),
            ),
        )
        cccd.incrementPending()
        cccd.markEnablePlanFailed()
        assertFalse(cccd.completePending())
        assertFalse(cccd.ready.value)
    }

    private fun hex(value: String): ByteArray = value.trim().split(Regex("\\s+"))
        .map { it.toInt(16).toByte() }.toByteArray()
}
