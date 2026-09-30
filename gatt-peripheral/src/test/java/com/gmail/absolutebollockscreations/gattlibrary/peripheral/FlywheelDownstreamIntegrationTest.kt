package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import com.gmail.absolutebollockscreations.gattlibrary.central.flywheelProviderCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlywheelDownstreamIntegrationTest {
    @Test
    fun `Flywheel Indoor Bike payload carries synthetic motion plus native cadence and power`() {
        val snapshot = FtmsMetricSnapshot(
            speedKph = 31.25,
            averageSpeedKph = 29.5,
            cadence = 90.0,
            distanceMeters = 1234,
            powerWatts = 290,
        )
        val packet = FtmsDataEncoders.encode(
            FtmsMachineProfile.INDOOR_BIKE,
            snapshot,
            speedKph = snapshot.speedKph,
            maxPayload = 509,
        ).single()
        val flags = u16(packet)

        assertEquals(13, packet.size)
        assertFalse("synthetic instantaneous speed is present", flags.bit(0))
        for (bit in listOf(1, 2, 4, 6)) assertTrue("Flywheel field $bit", flags.bit(bit))
        for (bit in listOf(3, 5, 7, 8, 9, 10, 11, 12)) {
            assertFalse("unsupported Flywheel field $bit", flags.bit(bit))
        }
    }

    @Test
    fun `Flywheel capabilities include derived motion but no resistance or targets`() {
        val measurements = providerMeasurementCapabilities(
            FtmsMachineProfile.INDOOR_BIKE,
            setOf(BluetoothIds.UUID_FLYWHEEL_SERVICE),
        )
        assertTrue(measurements.averageSpeed)
        assertTrue(measurements.cadence)
        assertTrue(measurements.totalDistance)
        assertTrue(measurements.power)
        assertFalse(measurements.resistance)
        assertFalse(measurements.heartRate)

        val engineCapabilities = flywheelProviderCapabilities()
        assertTrue("measurement-only Flywheel capabilities are settled", engineCapabilities.featuresRead)
        assertFalse(engineCapabilities.supportsSpeed)
        assertFalse(engineCapabilities.supportsInclination)
        assertFalse(engineCapabilities.supportsResistance)
        assertFalse(engineCapabilities.supportsPower)

        val targets = TargetCapabilities.fromEngineCapabilities(engineCapabilities)
        assertEquals(0, targets.toTargetFeatureBits())
        assertEquals(0L, u32(buildFtmsFeaturePayload(FtmsMachineProfile.INDOOR_BIKE, targets, measurementCapabilities = measurements), 4))
    }

    private fun u16(bytes: ByteArray, offset: Int = 0): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        u16(bytes, offset).toLong() or (u16(bytes, offset + 2).toLong() shl 16)

    private fun Int.bit(bit: Int): Boolean = this and (1 shl bit) != 0
}
