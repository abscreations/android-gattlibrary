package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class CscProductionPlumbingTest {

    @Test
    fun `Measurement and Feature are both mandatory`() {
        listOf(
            emptySet(),
            setOf(BluetoothIds.UUID_CSC_MEASUREMENT),
            setOf(BluetoothIds.UUID_CSC_FEATURE),
        ).forEach { characteristics ->
            val plan = planCscDiscovery(characteristics)
            assertFalse(plan.activate)
            assertTrue(plan.operations.isEmpty())
        }
    }

    @Test
    fun `Feature read precedes Measurement subscription`() {
        val plan = planCscDiscovery(
            setOf(
                BluetoothIds.UUID_CSC_MEASUREMENT,
                BluetoothIds.UUID_CSC_FEATURE,
                BluetoothIds.UUID_SENSOR_LOCATION,
                BluetoothIds.UUID_SC_CONTROL_POINT,
            ),
        )

        assertTrue(plan.activate)
        assertEquals(
            listOf(
                CscGattOperation(CscGattOperationKind.READ, BluetoothIds.UUID_CSC_FEATURE),
                CscGattOperation(
                    CscGattOperationKind.ENABLE_NOTIFICATIONS,
                    BluetoothIds.UUID_CSC_MEASUREMENT,
                ),
            ),
            plan.operations,
        )
    }

    @Test
    fun `Feature read decodes unsigned bits and authoritative roles`() {
        val wheel = decodeCscFeatureRead(
            BluetoothIds.UUID_CSC_FEATURE,
            byteArrayOf(0x01, 0x00),
            success = true,
        ) as CscFeatureReadOutcome.Features
        assertEquals(0x0001, wheel.bits)
        assertEquals(setOf(BtEngineState.CscRole.SPEED), wheel.capability?.roles)
        assertEquals(BtEngineState.CscRoleSource.FEATURE, wheel.capability?.source)

        val highBits = decodeCscFeatureRead(
            BluetoothIds.UUID_CSC_FEATURE,
            byteArrayOf(0x03, 0x80.toByte()),
            success = true,
        ) as CscFeatureReadOutcome.Features
        assertEquals(0x8003, highBits.bits)
        assertEquals(BtEngineState.CscRole.entries.toSet(), highBits.capability?.roles)
    }

    @Test
    fun `Feature failure truncation and no usable role stay explicit`() {
        assertEquals(
            CscFeatureReadOutcome.Unavailable,
            decodeCscFeatureRead(
                BluetoothIds.UUID_CSC_FEATURE,
                byteArrayOf(0x03),
                success = true,
            ),
        )
        assertEquals(
            CscFeatureReadOutcome.Unavailable,
            decodeCscFeatureRead(
                BluetoothIds.UUID_CSC_FEATURE,
                byteArrayOf(0x03, 0x00),
                success = false,
            ),
        )
        val zero = decodeCscFeatureRead(
            BluetoothIds.UUID_CSC_FEATURE,
            byteArrayOf(0x00, 0x00),
            success = true,
        ) as CscFeatureReadOutcome.Features
        assertNull(zero.capability)
        assertEquals(
            CscFeatureReadOutcome.Ignored,
            decodeCscFeatureRead(UUID.randomUUID(), ByteArray(0), success = false),
        )
    }

    @Test
    fun `factory and sensor connection path register CSC`() {
        val factory = HandlerFactory.standard(enablePhase05HardwareTest = false)
        val first = factory.create(ProtocolKey.CSC)
        val second = factory.create(ProtocolKey.CSC)

        assertTrue(first is CscProtocolHandler)
        assertTrue(second is CscProtocolHandler)
        assertNotSame(first, second)
        assertEquals(ProtocolKey.CSC, sensorProtocolFor(BtEngineState.BtServiceType.CSC))
    }

    @Test
    fun `CSC activates alone or beside HRM on one link`() {
        val cscOnly = activateMatchingProtocolHandlers(
            candidates = listOf(CscProtocolHandler(), HrmProtocolHandler()),
            discoveredServiceUuids = setOf(BluetoothIds.UUID_CSC_SERVICE),
            offer = { true },
        )
        assertEquals(1, cscOnly.size)
        assertTrue(cscOnly.single() is CscProtocolHandler)

        val combined = activateMatchingProtocolHandlers(
            candidates = listOf(CscProtocolHandler(), HrmProtocolHandler()),
            discoveredServiceUuids = setOf(
                BluetoothIds.UUID_CSC_SERVICE,
                BluetoothIds.UUID_HRM_SERVICE,
            ),
            offer = { true },
        )
        assertEquals(2, combined.size)
        assertTrue(combined.any { it is CscProtocolHandler })
        assertTrue(combined.any { it is HrmProtocolHandler })
    }

    @Test
    fun `CSC characteristic ownership is exact beside other handlers`() {
        val csc = CscProtocolHandler()
        val hrm = HrmProtocolHandler()
        val measurementRoute = resolveProtocolHandlerRoute(
            listOf(hrm, csc),
            BluetoothIds.UUID_CSC_MEASUREMENT,
            BluetoothIds.UUID_CSC_SERVICE,
        )
        val featureRoute = resolveProtocolHandlerRoute(
            listOf(hrm, csc),
            BluetoothIds.UUID_CSC_FEATURE,
            BluetoothIds.UUID_CSC_SERVICE,
        )

        assertEquals(listOf(csc), measurementRoute.owners)
        assertEquals(listOf(csc), featureRoute.owners)
        assertSame(csc, measurementRoute.owners.single())
        assertNull(resolveProtocolHandlerRoute(listOf(hrm, csc), UUID.randomUUID(), null).ownership)
    }

    @Test
    fun `fresh handlers own independent tracker baselines`() {
        val first = CscProtocolHandler()
        val second = CscProtocolHandler()
        val initial = byteArrayOf(0x01, 0x0A, 0x00, 0x00, 0x00, 0x10, 0x00)
        val next = byteArrayOf(0x01, 0x0C, 0x00, 0x00, 0x00, 0x20, 0x00)

        assertNull(first.acceptMeasurement(initial)?.wheelDelta)
        assertEquals(2L, first.acceptMeasurement(next)?.wheelDelta?.revolutions)
        assertNull(second.acceptMeasurement(next)?.wheelDelta)
    }

    @Test
    fun `CSC CCCD failure prevents readiness`() {
        val cccd = CccdState()
        cccd.mergeEnablePlan(
            listOf(CccdTarget(BluetoothIds.UUID_CSC_SERVICE, BluetoothIds.UUID_CSC_MEASUREMENT)),
        )
        cccd.incrementPending()
        cccd.markEnablePlanFailed()

        assertFalse(cccd.completePending())
        assertFalse(cccd.ready.value)
    }
}
