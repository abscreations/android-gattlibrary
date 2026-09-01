package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtEngineState
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtServiceProfiles.FtmsMachineProfile
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.FusionPersona
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.KEISER_M_SERIES_CANDIDATE_ID
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.KeiserMSeriesMeasurement
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.MetricEmitter
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.MetricEndpointId
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.MetricFusion
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.MetricKind
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.MetricOriginId
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.MetricProtocol
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.MetricSourceClass
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.MetricSourceId
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.keiserSamples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeiserDownstreamIntegrationTest {

    @Test
    fun `Keiser metrics become an optional-only Indoor Bike record without speed`() {
        val state = BtEngineState()
        val fusion = MetricFusion(state)
        fusion.startSession(FusionPersona.RIDE, 0L)
        val origin = MetricOriginId.Advertisement("keiser:test#56", 1L)
        fusion.selectMachineOrigin(origin)
        val emitter = emitter(fusion, origin, MetricSourceClass.VENDOR_NATIVE, 100L)
        val reading = KeiserMSeriesMeasurement.Reading(
            firmwareMajor = 6,
            firmwareMinor = 0x40,
            statsTimeoutMs = 1_000L,
            dataType = 0,
            isRealtime = true,
            systemId = 56,
            cadenceRpm = 91.5,
            heartRateBpm = 148.0,
            powerWatts = 237,
            energyKcal = 42,
            elapsedSeconds = 754,
            distanceUnit = KeiserMSeriesMeasurement.DistanceUnit.KILOMETRES,
            distanceRawTenths = 123,
            distanceMetres = 12_300.0,
            resistance = 17,
        )
        keiserSamples(reading).forEach {
            emitter.emit(it.metric, it.value, nativeCumulative = it.nativeCumulative)
        }

        assertNull(state.speedKph.value)
        val store = FtmsMetricStore()
        EngineMetricPump(state, store) { FtmsMachineProfile.INDOOR_BIKE }.sampleOnce()
        val packet = FtmsDataEncoders.encode(
            FtmsMachineProfile.INDOOR_BIKE,
            store.snapshot,
            speedKph = null,
            maxPayload = 509,
        ).single()
        val flags = u16(packet)
        assertTrue("More Data marks Instantaneous Speed absent", flags.bit(0))
        for (bit in listOf(2, 4, 5, 6, 8, 9, 11)) {
            assertTrue("Keiser Indoor Bike flag $bit must be present", flags.bit(bit))
        }
        assertFalse("average speed is never synthesized", flags.bit(1))
    }

    @Test
    fun `Keiser feature and target claims are measurement-only`() {
        val measurements = providerMeasurementCapabilities(
            FtmsMachineProfile.INDOOR_BIKE,
            emptySet(),
            KEISER_M_SERIES_CANDIDATE_ID,
        )
        assertFalse(measurements.averageSpeed)
        assertTrue(measurements.cadence)
        assertTrue(measurements.totalDistance)
        assertTrue(measurements.resistance)
        assertTrue(measurements.power)
        assertTrue(measurements.expendedEnergy)
        assertTrue(measurements.heartRate)
        assertTrue(measurements.elapsedTime)

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
    fun `external HRM outranks Keiser heart rate while bike cadence and power stay available`() {
        val state = BtEngineState()
        val fusion = MetricFusion(state)
        fusion.startSession(FusionPersona.RIDE, 0L)
        val origin = MetricOriginId.Advertisement("keiser:test#7", 1L)
        fusion.selectMachineOrigin(origin)
        val bike = emitter(fusion, origin, MetricSourceClass.VENDOR_NATIVE, 100L)
        bike.emit(MetricKind.HEART_RATE, 141)
        bike.emit(MetricKind.CADENCE, 88)
        bike.emit(MetricKind.POWER, 205)

        val hrmOrigin = MetricOriginId.Advertisement("hrm:test", 1L)
        emitter(fusion, hrmOrigin, MetricSourceClass.HRM, 101L)
            .emit(MetricKind.HEART_RATE, 151)

        assertEquals(151, state.heartRate.value)
        assertEquals(88.0, state.instantCadence.value!!, 0.0)
        assertEquals(205, state.instantPower.value)
    }

    @Test
    fun `cumulative distance remains continuous when advertisement generations switch`() {
        val state = BtEngineState()
        val fusion = MetricFusion(state)
        fusion.startSession(FusionPersona.RIDE, 0L)
        val first = MetricOriginId.Advertisement("keiser:first#1", 1L)
        val second = MetricOriginId.Advertisement("keiser:second#2", 2L)
        fusion.selectMachineOrigin(first)
        emitter(fusion, first, MetricSourceClass.VENDOR_NATIVE, 1L)
            .emit(MetricKind.DISTANCE, 100, nativeCumulative = 100.0)
        emitter(fusion, first, MetricSourceClass.VENDOR_NATIVE, 2L)
            .emit(MetricKind.DISTANCE, 110, nativeCumulative = 110.0)
        assertEquals(110, state.totalDistanceMeters.value)

        fusion.selectMachineOrigin(second)
        emitter(fusion, second, MetricSourceClass.VENDOR_NATIVE, 3L)
            .emit(MetricKind.DISTANCE, 500, nativeCumulative = 500.0)
        assertEquals(110, state.totalDistanceMeters.value)
        emitter(fusion, second, MetricSourceClass.VENDOR_NATIVE, 4L)
            .emit(MetricKind.DISTANCE, 505, nativeCumulative = 505.0)
        assertEquals(115, state.totalDistanceMeters.value)
    }

    private fun emitter(
        fusion: MetricFusion,
        origin: MetricOriginId,
        sourceClass: MetricSourceClass,
        arrivalMs: Long,
    ) = MetricEmitter(
        fusion,
        MetricSourceId(
            origin,
            MetricProtocol.VENDOR,
            MetricEndpointId.ManufacturerData(KeiserMSeriesMeasurement.COMPANY_ID),
        ),
        sourceClass,
        arrivalMs,
        freshnessMs = 1_000L,
    )

    private fun u16(bytes: ByteArray, offset: Int = 0): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        u16(bytes, offset).toLong() or (u16(bytes, offset + 2).toLong() shl 16)

    private fun Int.bit(bit: Int): Boolean = this and (1 shl bit) != 0
}
