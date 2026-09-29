package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds
import com.gmail.absolutebollockscreations.gattlibrary.central.BtServiceProfiles
import com.gmail.absolutebollockscreations.gattlibrary.central.BtEngineState
import com.gmail.absolutebollockscreations.gattlibrary.central.ConnectionId
import com.gmail.absolutebollockscreations.gattlibrary.central.FusionPersona
import com.gmail.absolutebollockscreations.gattlibrary.central.KayakFirstMeasurement
import com.gmail.absolutebollockscreations.gattlibrary.central.KayakFirstMeasurementDecodeResult
import com.gmail.absolutebollockscreations.gattlibrary.central.KayakFirstDerivedMetrics
import com.gmail.absolutebollockscreations.gattlibrary.central.KayakFirstEnergyEstimate
import com.gmail.absolutebollockscreations.gattlibrary.central.MetricEmitter
import com.gmail.absolutebollockscreations.gattlibrary.central.MetricFusion
import com.gmail.absolutebollockscreations.gattlibrary.central.MetricProtocol
import com.gmail.absolutebollockscreations.gattlibrary.central.MetricSourceClass
import com.gmail.absolutebollockscreations.gattlibrary.central.MetricSourceId
import com.gmail.absolutebollockscreations.gattlibrary.central.kayakFirstSamples
import com.gmail.absolutebollockscreations.gattlibrary.central.kayakFirstEstimatedPowerSamples
import com.gmail.absolutebollockscreations.gattlibrary.central.kayakFirstEstimatedEnergySamples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.EngineMetricPump
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.FtmsMetricStore
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.providerMeasurementCapabilities

class KayakFirstDownstreamContractTest {
    @Test
    fun `native reading crosses fusion into the downstream rower store`() {
        val state = BtEngineState()
        val fusion = MetricFusion(state).also { it.startSession(FusionPersona.ROW_PADDLE, 1_000) }
        val emitter = MetricEmitter(
            fusion,
            MetricSourceId(
                ConnectionId("AA:BB:CC:DD:EE:FF", 8),
                MetricProtocol.VENDOR,
                BluetoothIds.UUID_KAYAK_FIRST_IO,
            ),
            MetricSourceClass.VENDOR_NATIVE,
            1_001,
        )
        // Sanitized physical-corpus vector `s1-10s`, not a synthetic reading.
        val reading = (KayakFirstMeasurement.decode(
            "6;900000010449;0;0;0;0.00;0;1;0;11;0.94;2.75;98;76;229;72;573;180;" +
                "1147;360;96;82;10;1",
        ) as KayakFirstMeasurementDecodeResult.Decoded).reading
        kayakFirstSamples(reading).forEach { sample ->
            emitter.emit(
                sample.metric,
                sample.value,
                eventMs = reading.elapsedSeconds * 1_000L,
                nativeCumulative = sample.nativeCumulative,
                quality = sample.quality,
            )
        }
        val estimatedPower = KayakFirstDerivedMetrics.estimatePowerFromSpeed(
            reading.speedMetresPerSecond,
            athleteWeightKg = 80.0,
        )!!
        kayakFirstEstimatedPowerSamples(estimatedPower, estimatedPower).forEach { sample ->
            MetricEmitter(
                fusion,
                MetricSourceId(
                    ConnectionId("AA:BB:CC:DD:EE:FF", 8),
                    MetricProtocol.VENDOR,
                    BluetoothIds.UUID_KAYAK_FIRST_IO,
                ),
                sample.sourceClass,
                1_001,
            ).emit(sample.metric, sample.value, quality = sample.quality)
        }
        val energy = kayakFirstEstimatedEnergySamples(KayakFirstEnergyEstimate(7.0, 250.0))
        energy.forEach { sample ->
            MetricEmitter(
                fusion,
                MetricSourceId(
                    ConnectionId("AA:BB:CC:DD:EE:FF", 8),
                    MetricProtocol.VENDOR,
                    BluetoothIds.UUID_KAYAK_FIRST_IO,
                ),
                sample.sourceClass,
                1_001,
            ).emit(
                sample.metric,
                sample.value,
                nativeCumulative = sample.nativeCumulative,
                quality = sample.quality,
            )
        }

        val store = FtmsMetricStore()
        EngineMetricPump(state, store) { BtServiceProfiles.FtmsMachineProfile.INDOOR_ROWER }
            .sampleOnce()
        assertEquals(9.9, store.snapshot.speedKph!!, 1e-9)
        assertEquals(76.0, store.snapshot.cadence!!, 1e-9)
        assertEquals(estimatedPower.roundToInt(), store.snapshot.powerWatts)
        assertEquals(7, store.snapshot.caloriesTotal)
        assertEquals(11, store.snapshot.distanceMeters)
        assertEquals(10, store.snapshot.elapsedSeconds)
        assertEquals(1_800.0 / 9.9, store.snapshot.paceSecondsPer500m!!, 1e-9)
    }

    @Test
    fun `rower pace prefers canonical speed and average speed over conflicting pace state`() {
        val state = BtEngineState().apply {
            appendSpeedKphSample(12.875)
            setAvgSpeedKph(10.0)
            setInstantPace(209.0 / 30.0)
            setAvgPace(8.0)
        }
        val store = FtmsMetricStore()

        EngineMetricPump(state, store) { BtServiceProfiles.FtmsMachineProfile.INDOOR_ROWER }
            .sampleOnce()

        assertEquals(1_800.0 / 12.875, store.snapshot.paceSecondsPer500m!!, 1e-9)
        assertEquals(180.0, store.snapshot.averagePaceSecondsPer500m!!, 1e-9)
    }

    @Test
    fun `rower pace falls back only when canonical speed is absent`() {
        val fallbackState = BtEngineState().apply {
            setInstantPace(6.0)
            setAvgPace(7.0)
        }
        val fallbackStore = FtmsMetricStore()
        EngineMetricPump(fallbackState, fallbackStore) {
            BtServiceProfiles.FtmsMachineProfile.INDOOR_ROWER
        }.sampleOnce()
        assertEquals(180.0, fallbackStore.snapshot.paceSecondsPer500m!!, 1e-9)
        assertEquals(210.0, fallbackStore.snapshot.averagePaceSecondsPer500m!!, 1e-9)

        val stationaryState = BtEngineState().apply {
            appendSpeedKphSample(0.0)
            setAvgSpeedKph(0.0)
            setInstantPace(6.0)
            setAvgPace(7.0)
        }
        val stationaryStore = FtmsMetricStore()
        EngineMetricPump(stationaryState, stationaryStore) {
            BtServiceProfiles.FtmsMachineProfile.INDOOR_ROWER
        }.sampleOnce()
        assertEquals(null, stationaryStore.snapshot.paceSecondsPer500m)
        assertEquals(null, stationaryStore.snapshot.averagePaceSecondsPer500m)
    }

    @Test
    fun `Kayak First rower advertises only evidenced fresh measurements`() {
        val capabilities = providerMeasurementCapabilities(
            BtServiceProfiles.FtmsMachineProfile.INDOOR_ROWER,
            setOf(BluetoothIds.UUID_KAYAK_FIRST_SERVICE),
        )
        assertTrue(capabilities.cadence)
        assertTrue(capabilities.totalDistance)
        assertTrue(capabilities.pace)
        assertTrue(capabilities.power)
        assertTrue(capabilities.elapsedTime)
        assertFalse(capabilities.resistance)
        assertTrue(capabilities.expendedEnergy)
        assertFalse(capabilities.heartRate)
    }
}
