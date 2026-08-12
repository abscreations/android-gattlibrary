package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtEngineState
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtServiceProfiles.FtmsMachineProfile
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.ConnectionId
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.FusionPersona
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.MetricEmitter
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.MetricFusion
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.MetricProtocol
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.MetricSourceClass
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.MetricSourceId
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.PrecorMeasurement
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.precorSamples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 5b §11.8: proprietary notification through fusion into FTMS Indoor Bike bytes. */
class PrecorDownstreamEndToEndTest {

    @Test
    fun `a Chrono frame becomes a complete FTMS Indoor Bike measurement`() {
        val state = BtEngineState()
        val fusion = MetricFusion(state)
        fusion.startSession(FusionPersona.RIDE, 100)
        val source = MetricSourceId(
            ConnectionId("C0:00:00:00:00:01", 1),
            MetricProtocol.VENDOR,
            BluetoothIds.UUID_PRECOR_MEASUREMENT,
        )
        val reading = requireNotNull(
            PrecorMeasurement.parse(
                byteArrayOf(
                    83, 89, 22, 0xD8.toByte(), 0x04, 77,
                    0xEB.toByte(), 0x08, 0xAC.toByte(), 0x03,
                    0xDC.toByte(), 0x1D, 0,
                    124, 0, 103, 0, 94, 0,
                ),
            ),
        )
        val emitter = MetricEmitter(fusion, source, MetricSourceClass.VENDOR_NATIVE, 101)
        precorSamples(reading).forEach { sample ->
            emitter.emit(
                sample.metric,
                sample.value,
                eventMs = sample.eventMs,
                nativeCumulative = sample.nativeCumulative,
            )
        }

        val store = FtmsMetricStore()
        EngineMetricPump(state, store) { FtmsMachineProfile.INDOOR_BIKE }.sampleOnce()
        val snapshot = store.snapshot
        assertEquals(22.83, snapshot.speedKph!!, 1e-9)
        assertEquals(94.0, snapshot.cadence!!, 1e-9)
        assertEquals(94, snapshot.powerWatts)
        assertEquals(7_644, snapshot.distanceMeters)
        assertEquals(124, snapshot.caloriesTotal)
        assertEquals(77, snapshot.heartRate)

        val packet = FtmsDataEncoders.encode(
            FtmsMachineProfile.INDOOR_BIKE,
            snapshot,
            snapshot.speedKph,
            maxPayload = 509,
        ).single()
        val flags = u16(packet, 0)
        for (bit in listOf(1, 2, 3, 4, 6, 7, 8, 9, 11)) {
            assertTrue("Indoor Bike flag $bit must be present", flags and (1 shl bit) != 0)
        }
        assertFalse("Chrono has no resistance field", flags and (1 shl 5) != 0)

        var offset = 2
        assertEquals(2_283, u16(packet, offset))
        offset += 2
        assertEquals(2_283, u16(packet, offset))
        offset += 2
        assertEquals(188, u16(packet, offset))
        offset += 2
        assertEquals(188, u16(packet, offset))
        offset += 2
        assertEquals(7_644, u24(packet, offset))
        offset += 3
        assertEquals(94, s16(packet, offset))
        offset += 2
        assertEquals(94, s16(packet, offset))
        offset += 2
        assertEquals(124, u16(packet, offset))
        assertEquals(0xFFFF, u16(packet, offset + 2))
        assertEquals(0xFF, packet[offset + 4].toInt() and 0xFF)
        offset += 5
        assertEquals(77, packet[offset].toInt() and 0xFF)
    }

    @Test
    fun `a Chrono-backed Indoor Bike feature payload exposes no target controls`() {
        val targets = TargetCapabilities.fromEngineCapabilities(
            BtEngineState.FtmsCapabilities(featuresRead = true),
        )

        assertEquals(0, targets.toTargetFeatureBits())
        assertEquals(
            0L,
            u32(buildFtmsFeaturePayload(FtmsMachineProfile.INDOOR_BIKE, targets), 4),
        )
    }

    private fun u16(value: ByteArray, offset: Int): Int =
        (value[offset].toInt() and 0xFF) or ((value[offset + 1].toInt() and 0xFF) shl 8)

    private fun u24(value: ByteArray, offset: Int): Int =
        u16(value, offset) or ((value[offset + 2].toInt() and 0xFF) shl 16)

    private fun s16(value: ByteArray, offset: Int): Int =
        u16(value, offset).let { if (it >= 0x8000) it - 0x10000 else it }

    private fun u32(value: ByteArray, offset: Int): Long =
        u16(value, offset).toLong() or (u16(value, offset + 2).toLong() shl 16)
}
