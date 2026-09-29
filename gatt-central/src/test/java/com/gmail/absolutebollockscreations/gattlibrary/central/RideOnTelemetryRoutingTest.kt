package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class RideOnTelemetryRoutingTest {
    @Test
    fun `riding data decodes known metrics and retains only diagnostic 5 and 6`() {
        val telemetry = decodeRideOnTelemetry(hex("03 08 FA 01 10 5A 18 FC 11 20 96 01 28 07 30 09"))!!
        assertEquals(250, telemetry.powerWatts)
        assertEquals(90, telemetry.cadenceRpm)
        assertEquals(23.0, telemetry.speedKph!!, 0.0001)
        assertEquals(150, telemetry.heartRateBpm)
        assertEquals(mapOf(5 to 7L, 6 to 9L), telemetry.diagnosticFields)
    }

    @Test
    fun `malformed duplicates wrong wire and implausible fields fail or drop safely`() {
        assertNull(decodeRideOnTelemetry(hex("03 08 80")))
        assertNull(decodeRideOnTelemetry(hex("03 08 01 08 02")))
        assertNull(decodeRideOnTelemetry(hex("03 0A 01 01")))
        val bounded = decodeRideOnTelemetry(hex("03 08 B9 17 10 AD 02 18 A1 9C 01 20 00"))!!
        assertNull(bounded.powerWatts)
        assertNull(bounded.cadenceRpm)
        assertNull(bounded.speedKph)
        assertNull(bounded.heartRateBpm)
    }

    @Test
    fun `fresh CPS and FTMS outrank RideOn alternate metrics`() {
        val fusion = MetricFusion(BtEngineState())
        val connection = ConnectionId("AA:BB:CC:DD:EE:82", 1)
        fun source(protocol: MetricProtocol, n: Long) = MetricSourceId(connection, protocol, UUID(0, n))
        fun submit(metric: MetricKind, value: Double, source: MetricSourceId, sourceClass: MetricSourceClass, at: Long) {
            fusion.submit(MetricSample(connection, source, source.protocol, sourceClass, metric, at, value = value))
        }
        val rideOn = source(MetricProtocol.VENDOR, 1)
        val cps = source(MetricProtocol.CPS, 2)
        val ftms = source(MetricProtocol.FTMS, 3)
        submit(MetricKind.POWER, 240.0, rideOn, MetricSourceClass.VENDOR_NATIVE, 100)
        submit(MetricKind.CADENCE, 88.0, rideOn, MetricSourceClass.VENDOR_NATIVE, 100)
        submit(MetricKind.SPEED, 22.0, rideOn, MetricSourceClass.VENDOR_NATIVE, 100)
        submit(MetricKind.POWER, 250.0, cps, MetricSourceClass.CPS, 101)
        submit(MetricKind.CADENCE, 90.0, cps, MetricSourceClass.CPS_CRANK, 101)
        submit(MetricKind.SPEED, 23.0, ftms, MetricSourceClass.FTMS, 101)
        assertEquals(cps, fusion.selectedSource(MetricKind.POWER))
        assertEquals(cps, fusion.selectedSource(MetricKind.CADENCE))
        assertEquals(ftms, fusion.selectedSource(MetricKind.SPEED))
    }

    @Test
    fun `RideOn route and capabilities remain closed without negotiated selection`() {
        assertEquals(
            MachineControlRoute.UNAVAILABLE,
            machineControlRoute(AdapterLivenessCoordinator.RIDEON_ADAPTER_ID, false, false, false),
        )
        assertEquals(
            MachineControlRoute.RIDEON,
            machineControlRoute(AdapterLivenessCoordinator.RIDEON_ADAPTER_ID, false, false, true),
        )
        val caps = rideOnProviderCapabilities()
        assertTrue(caps.supportsPower && caps.supportsIndoorBikeSimulation)
        assertFalse(caps.supportsResistance || caps.supportsSpeed || caps.supportsInclination)
    }

    private fun hex(text: String) = text.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
