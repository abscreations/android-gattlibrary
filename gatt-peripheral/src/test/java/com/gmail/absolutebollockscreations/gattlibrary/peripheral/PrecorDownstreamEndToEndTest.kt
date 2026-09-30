package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.BtEngineState
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import com.gmail.absolutebollockscreations.gattlibrary.central.ConnectionId
import com.gmail.absolutebollockscreations.gattlibrary.central.FusionPersona
import com.gmail.absolutebollockscreations.gattlibrary.central.MetricEmitter
import com.gmail.absolutebollockscreations.gattlibrary.central.MetricFusion
import com.gmail.absolutebollockscreations.gattlibrary.central.MetricProtocol
import com.gmail.absolutebollockscreations.gattlibrary.central.MetricSourceClass
import com.gmail.absolutebollockscreations.gattlibrary.central.MetricSourceId
import com.gmail.absolutebollockscreations.gattlibrary.central.PrecorMeasurement
import com.gmail.absolutebollockscreations.gattlibrary.central.precorSamples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsSpec
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.PrecorUuids
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsDataEncoders
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsMetricStore
import com.gmail.absolutebollockscreations.gattlibrary.server.TargetCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.server.buildFtmsFeaturePayload
import com.gmail.absolutebollockscreations.gattlibrary.server.measurementCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.server.providerMeasurementCapabilities

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
            PrecorUuids.UUID_PRECOR_MEASUREMENT,
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

    @Test
    fun `a Precor-only machine context omits resistance from the measurement Feature word`() {
        val profile = FtmsMachineProfile.INDOOR_BIKE
        val capabilities = providerMeasurementCapabilities(
            profile,
            setOf(PrecorUuids.UUID_PRECOR_SERVICE),
        )
        val measurementWord = u32(
            buildFtmsFeaturePayload(
                profile = profile,
                targets = TargetCapabilities.NONE,
                measurementCapabilities = capabilities,
            ),
            0,
        )

        for (bit in listOf(0, 1, 2, 9, 10, 12, 14)) {
            assertTrue("Chrono measurement Feature bit $bit must be present", measurementWord and (1L shl bit) != 0L)
        }
        assertEquals(
            "the Chrono has no resistance measurement",
            0L,
            measurementWord and (1L shl FtmsSpec.Feature.RESISTANCE_LEVEL),
        )
    }

    @Test
    fun `the Precor override is not applied to a standard or simulated FTMS bike`() {
        val profile = FtmsMachineProfile.INDOOR_BIKE

        assertTrue(
            providerMeasurementCapabilities(profile, emptySet()).resistance,
        )
        assertTrue(
            providerMeasurementCapabilities(
                profile,
                setOf(FtmsUuids.UUID_FTMS_SERVICE, PrecorUuids.UUID_PRECOR_SERVICE),
            ).resistance,
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
