package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Production planning, read decoding, emission, and same-link ownership for Phase 1 RSC. */
class RscProductionPlumbingTest {

    @Test
    fun `measurement is required and optional reads do not gate activation`() {
        val absent = planRscDiscovery(
            setOf(BluetoothIds.UUID_RSC_FEATURE, BluetoothIds.UUID_SENSOR_LOCATION),
        )
        assertFalse(absent.activate)
        assertTrue(absent.operations.isEmpty())

        val measurementOnly = planRscDiscovery(setOf(BluetoothIds.UUID_RSC_MEASUREMENT))
        assertTrue(measurementOnly.activate)
        assertEquals(
            listOf(
                RscGattOperation(
                    RscGattOperationKind.ENABLE_NOTIFICATIONS,
                    BluetoothIds.UUID_RSC_MEASUREMENT,
                ),
            ),
            measurementOnly.operations,
        )
    }

    @Test
    fun `subscription is queued before feature and sensor-location reads`() {
        val plan = planRscDiscovery(
            setOf(
                BluetoothIds.UUID_RSC_MEASUREMENT,
                BluetoothIds.UUID_RSC_FEATURE,
                BluetoothIds.UUID_SENSOR_LOCATION,
            ),
        )

        assertEquals(
            listOf(
                RscGattOperation(RscGattOperationKind.ENABLE_NOTIFICATIONS, BluetoothIds.UUID_RSC_MEASUREMENT),
                RscGattOperation(RscGattOperationKind.READ, BluetoothIds.UUID_RSC_FEATURE),
                RscGattOperation(RscGattOperationKind.READ, BluetoothIds.UUID_SENSOR_LOCATION),
            ),
            plan.operations,
        )
    }

    @Test
    fun `feature and location reads decode success and remain best effort on failure`() {
        assertEquals(
            RscReadOutcome.Features(0x0007),
            decodeRscRead(BluetoothIds.UUID_RSC_FEATURE, byteArrayOf(0x07, 0x00), success = true),
        )
        assertEquals(
            RscReadOutcome.SensorLocation(0x01, footMounted = true),
            decodeRscRead(BluetoothIds.UUID_SENSOR_LOCATION, byteArrayOf(0x01), success = true),
        )
        assertEquals(
            RscReadOutcome.SensorLocation(0x0E, footMounted = false),
            decodeRscRead(BluetoothIds.UUID_SENSOR_LOCATION, byteArrayOf(0x0E), success = true),
        )
        assertEquals(
            RscReadOutcome.Unavailable(BluetoothIds.UUID_RSC_FEATURE),
            decodeRscRead(BluetoothIds.UUID_RSC_FEATURE, byteArrayOf(0x07), success = true),
        )
        assertEquals(
            RscReadOutcome.Unavailable(BluetoothIds.UUID_SENSOR_LOCATION),
            decodeRscRead(BluetoothIds.UUID_SENSOR_LOCATION, ByteArray(0), success = false),
        )
        assertEquals(RscReadOutcome.Ignored, decodeRscRead(UUID.randomUUID(), ByteArray(0), false))
    }

    @Test
    fun `notification emission is source tagged and starts RSC distance at zero`() {
        val state = BtEngineState()
        val fusion = MetricFusion(state)
        fusion.startSession(FusionPersona.RUN, 100)
        val source = MetricSourceId(
            ConnectionId("RUNPOD", 7),
            MetricProtocol.RSC,
            BluetoothIds.UUID_RSC_MEASUREMENT,
        )

        emitRscMetrics(
            RscMeasurement.parse(hex("032601387b0024000000"))!!,
            MetricEmitter(fusion, source, MetricSourceClass.RSC, arrivalMs = 101),
        )

        assertEquals(source, fusion.selectedSource(MetricKind.SPEED))
        assertEquals(source, fusion.selectedSource(MetricKind.CADENCE))
        assertEquals(source, fusion.selectedSource(MetricKind.DISTANCE))
        assertEquals(4.134375, fusion.latestSample(MetricKind.SPEED)?.value ?: 0.0, 1e-9)
        assertEquals(56.0, fusion.latestSample(MetricKind.CADENCE)?.value ?: 0.0, 0.0)
        assertEquals(0, state.totalDistanceMeters.value)

        emitRscMetrics(
            RscMeasurement.parse(hex("03260135830042000000"))!!,
            MetricEmitter(fusion, source, MetricSourceClass.RSC, arrivalMs = 102),
        )
        assertEquals(3, state.totalDistanceMeters.value)
    }

    @Test
    fun `RSC CCCD failure prevents readiness and HRM plus RSC remain distinct owners`() {
        val cccd = CccdState()
        val service = BluetoothIds.UUID_RSC_SERVICE
        cccd.mergeEnablePlan(listOf(CccdTarget(service, BluetoothIds.UUID_RSC_MEASUREMENT)))
        cccd.incrementPending()
        cccd.markEnablePlanFailed()
        assertFalse(cccd.completePending())
        assertFalse(cccd.ready.value)

        val rsc = RscProtocolHandler()
        val hrm = HrmProtocolHandler()
        val handlers = listOf(hrm, rsc)
        val rscRoute = resolveProtocolHandlerRoute(
            handlers,
            BluetoothIds.UUID_RSC_MEASUREMENT,
            BluetoothIds.UUID_RSC_SERVICE,
        )
        val hrmRoute = resolveProtocolHandlerRoute(
            handlers,
            BluetoothIds.UUID_HEART_RATE_MEASUREMENT,
            BluetoothIds.UUID_HRM_SERVICE,
        )

        assertEquals(listOf(rsc), rscRoute.owners)
        assertEquals(listOf(hrm), hrmRoute.owners)
        assertSame(rsc, rscRoute.owners.single())
        assertSame(hrm, hrmRoute.owners.single())
        assertNull(resolveProtocolHandlerRoute(handlers, UUID.randomUUID(), null).ownership)
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
