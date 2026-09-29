package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds
import com.gmail.absolutebollockscreations.gattlibrary.central.BtEngineState
import com.gmail.absolutebollockscreations.gattlibrary.central.BtServiceProfiles.FtmsMachineProfile
import com.gmail.absolutebollockscreations.gattlibrary.central.ConnectionId
import com.gmail.absolutebollockscreations.gattlibrary.central.CpsDerivationState
import com.gmail.absolutebollockscreations.gattlibrary.central.CpsMeasurement
import com.gmail.absolutebollockscreations.gattlibrary.central.CpsMeasurementTracker
import com.gmail.absolutebollockscreations.gattlibrary.central.CpsNativeEventSnapshot
import com.gmail.absolutebollockscreations.gattlibrary.central.FusionPersona
import com.gmail.absolutebollockscreations.gattlibrary.central.MetricEmitter
import com.gmail.absolutebollockscreations.gattlibrary.central.MetricFusion
import com.gmail.absolutebollockscreations.gattlibrary.central.MetricKind
import com.gmail.absolutebollockscreations.gattlibrary.central.MetricProtocol
import com.gmail.absolutebollockscreations.gattlibrary.central.MetricQuality
import com.gmail.absolutebollockscreations.gattlibrary.central.MetricSourceClass
import com.gmail.absolutebollockscreations.gattlibrary.central.MetricSourceId
import com.gmail.absolutebollockscreations.gattlibrary.central.sensorOnlyProviderCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CpsDownstreamEndToEndTest {
    @Test
    fun `CPS power and crank cadence fuse into FTMS Indoor Bike data`() {
        val state = BtEngineState()
        val fusion = MetricFusion(state)
        fusion.startSession(FusionPersona.RIDE, 100)
        val connection = ConnectionId("CPS", 2)
        val source = MetricSourceId(connection, MetricProtocol.CPS, BluetoothIds.UUID_CYCLING_POWER_MEASUREMENT)
        MetricEmitter(fusion, source, MetricSourceClass.CPS, 101).emit(MetricKind.POWER, 237.0)
        val tracker = CpsMeasurementTracker()
        tracker.accept(hex("30 00 EC 00 64 00 00 00 00 08 64 00 00 04"))
        val update = tracker.accept(hex("30 00 ED 00 66 00 00 00 00 10 66 00 00 08"))!!
        CpsDerivationState().derive(update, 2_405).forEach { metric ->
            MetricEmitter(fusion, source, metric.sourceClass, 102).emit(
                metric.metric,
                metric.value,
                nativeCumulative = metric.nativeCumulative,
                quality = MetricQuality.DERIVED,
            )
        }

        val store = FtmsMetricStore()
        EngineMetricPump(state, store) { FtmsMachineProfile.INDOOR_BIKE }.sampleOnce()
        val snapshot = store.snapshot
        val packet = FtmsDataEncoders.encode(
            FtmsMachineProfile.INDOOR_BIKE,
            snapshot,
            snapshot.speedKph,
            maxPayload = 509,
        ).single()
        val flags = u16(packet, 0)
        assertTrue(flags and (1 shl 2) != 0)
        assertTrue(flags and (1 shl 6) != 0)
        val cadenceOffset = 4 + if (flags and (1 shl 1) != 0) 2 else 0
        assertEquals(240, u16(packet, cadenceOffset))
        var powerOffset = cadenceOffset + 2
        if (flags and (1 shl 3) != 0) powerOffset += 2
        if (flags and (1 shl 4) != 0) powerOffset += 3
        if (flags and (1 shl 5) != 0) powerOffset += 2
        assertEquals(237, s16(packet, powerOffset))
    }

    @Test
    fun `manual assignment can choose CSC cadence over higher-priority CPS crank`() {
        val state = BtEngineState()
        val fusion = MetricFusion(state)
        fusion.startSession(FusionPersona.RIDE, 100)
        val cps = MetricSourceId(ConnectionId("CPS", 1), MetricProtocol.CPS, BluetoothIds.UUID_CYCLING_POWER_MEASUREMENT)
        val csc = MetricSourceId(ConnectionId("CSC", 1), MetricProtocol.CSC, BluetoothIds.UUID_CSC_MEASUREMENT)
        MetricEmitter(fusion, cps, MetricSourceClass.CPS_CRANK, 101).emit(MetricKind.CADENCE, 92.0, quality = MetricQuality.DERIVED)
        MetricEmitter(fusion, csc, MetricSourceClass.CSC_CRANK, 102).emit(MetricKind.CADENCE, 88.0, quality = MetricQuality.DERIVED)
        assertEquals(cps, fusion.selectedSource(MetricKind.CADENCE))
        fusion.assign(MetricKind.CADENCE, csc, 103)
        assertEquals(csc, fusion.selectedSource(MetricKind.CADENCE))
        assertEquals(88.0, state.instantCadence.value!!, 0.0001)
    }

    @Test
    fun `native CPS sensor-only source exposes measurement but no target controls`() {
        val targets = TargetCapabilities.fromEngineCapabilities(sensorOnlyProviderCapabilities())
        assertEquals(0, targets.toTargetFeatureBits())
        assertEquals(0L, u32(buildFtmsFeaturePayload(FtmsMachineProfile.INDOOR_BIKE, targets), 4))
        val payload = CyclingSensorEncoders.cyclingPowerMeasurement(
            200,
            CyclingRevolutionSnapshot(),
            CpsNativeEventSnapshot(cumulativeCrankRevolutions = 9, lastCrankEventTimeTicks = 10),
        )
        assertEquals(CyclingSensorEncoders.CPS_FLAG_CRANK_REVOLUTION_DATA_PRESENT, u16(payload, 0))
    }

    private fun hex(value: String): ByteArray = value.trim().split(Regex("\\s+"))
        .map { it.toInt(16).toByte() }.toByteArray()
    private fun u16(value: ByteArray, offset: Int): Int =
        (value[offset].toInt() and 0xFF) or ((value[offset + 1].toInt() and 0xFF) shl 8)
    private fun s16(value: ByteArray, offset: Int): Int = u16(value, offset).let { if (it >= 0x8000) it - 0x10000 else it }
    private fun u32(value: ByteArray, offset: Int): Long =
        u16(value, offset).toLong() or (u16(value, offset + 2).toLong() shl 16)
}
