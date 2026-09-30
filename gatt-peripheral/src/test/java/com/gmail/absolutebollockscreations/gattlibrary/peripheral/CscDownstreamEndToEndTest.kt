package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.BtEngineState
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import com.gmail.absolutebollockscreations.gattlibrary.central.ConnectionId
import com.gmail.absolutebollockscreations.gattlibrary.central.CscCalibration
import com.gmail.absolutebollockscreations.gattlibrary.central.CscDerivationState
import com.gmail.absolutebollockscreations.gattlibrary.central.CscDerivedMetric
import com.gmail.absolutebollockscreations.gattlibrary.central.CscMeasurement
import com.gmail.absolutebollockscreations.gattlibrary.central.CscMeasurementTracker
import com.gmail.absolutebollockscreations.gattlibrary.central.CscWorkoutPersona
import com.gmail.absolutebollockscreations.gattlibrary.central.FusionPersona
import com.gmail.absolutebollockscreations.gattlibrary.central.MetricEmitter
import com.gmail.absolutebollockscreations.gattlibrary.central.MetricFusion
import com.gmail.absolutebollockscreations.gattlibrary.central.MetricProtocol
import com.gmail.absolutebollockscreations.gattlibrary.central.MetricQuality
import com.gmail.absolutebollockscreations.gattlibrary.central.MetricSourceId
import com.gmail.absolutebollockscreations.gattlibrary.central.sensorOnlyProviderCapabilities
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids

class CscDownstreamEndToEndTest {

    @Test
    fun `combined CSC bike derives through fusion and encodes FTMS plus exact native CSC`() {
        val state = BtEngineState()
        val fusion = MetricFusion(state)
        fusion.startSession(FusionPersona.RIDE, nowMs = 100)
        val connection = ConnectionId("BIKE", 4)
        val roles = BtEngineState.CscRole.entries.toSet()
        val reading = CscMeasurement.Reading(
            wheel = CscMeasurement.WheelEvent(0xFEDC_BA98L, 0xFFFF),
            crank = CscMeasurement.CrankEvent(0xFFFE, 0xFFFD),
        )
        state.cscNativeEvents.publish(connection, reading, roles)
        emit(
            fusion,
            connection,
            CscDerivationState().derive(
                combinedUpdate(wheelRevolutions = 2, crankRevolutions = 1, ticks = 1_024),
                CscWorkoutPersona.BIKE,
                roles,
                roles,
                CscCalibration(wheelCircumferenceMm = 2_405),
            ),
        )

        val store = FtmsMetricStore()
        EngineMetricPump(state, store) { FtmsMachineProfile.INDOOR_BIKE }.sampleOnce()
        val snapshot = store.snapshot
        val packet = FtmsDataEncoders.encode(
            FtmsMachineProfile.INDOOR_BIKE,
            snapshot,
            snapshot.speedKph,
        ).single()

        val flags = u16(packet, 0)
        assertTrue(flags and (1 shl 1) != 0)
        assertTrue(flags and (1 shl 2) != 0)
        assertTrue(flags and (1 shl 4) != 0)
        assertEquals(1_732, u16(packet, 2))
        assertEquals(1_732, u16(packet, 4))
        val cadenceOffset = 4 + if (flags and (1 shl 1) != 0) 2 else 0
        assertEquals(120, u16(packet, cadenceOffset))
        var distanceOffset = cadenceOffset + 2
        if (flags and (1 shl 3) != 0) distanceOffset += 2
        assertEquals(5, u24(packet, distanceOffset))

        val downstreamCsc = CyclingRevolutionSnapshot(
            cumulativeWheelRevolutions = 10,
            lastWheelEventTime = 11,
            cumulativeCrankRevolutions = 12,
            lastCrankEventTime = 13,
        ).withNativeEvents(snapshot.cscNativeEvents)
        assertArrayEquals(
            byteArrayOf(
                0x03,
                0x98.toByte(), 0xBA.toByte(), 0xDC.toByte(), 0xFE.toByte(),
                0xFF.toByte(), 0xFF.toByte(),
                0xFE.toByte(), 0xFF.toByte(),
                0xFD.toByte(), 0xFF.toByte(),
            ),
            CyclingSensorEncoders.cscMeasurement(downstreamCsc),
        )
    }

    @Test
    fun `combined CSC elliptical prefers crank and encodes speed distance step rate and strides`() {
        val state = BtEngineState()
        val fusion = MetricFusion(state)
        fusion.startSession(FusionPersona.RIDE, nowMs = 100)
        val connection = ConnectionId("ELLIPTICAL", 1)
        val roles = BtEngineState.CscRole.entries.toSet()
        emit(
            fusion,
            connection,
            CscDerivationState().derive(
                combinedUpdate(wheelRevolutions = 20, crankRevolutions = 2, ticks = 1_024),
                CscWorkoutPersona.ELLIPTICAL,
                roles,
                roles,
                CscCalibration(ellipticalStrideLengthMm = 1_600),
            ),
        )

        val store = FtmsMetricStore()
        EngineMetricPump(state, store) { FtmsMachineProfile.ELLIPTICAL }.sampleOnce()
        val snapshot = store.snapshot
        val packet = FtmsDataEncoders.encode(
            FtmsMachineProfile.ELLIPTICAL,
            snapshot,
            snapshot.speedKph,
            maxPayload = 509,
        ).single()

        val flags = u24(packet, 0)
        assertTrue(flags and (1 shl 2) != 0)
        assertTrue(flags and (1 shl 3) != 0)
        assertTrue(flags and (1 shl 4) != 0)
        assertEquals(1_152, u16(packet, 3))
        val distanceOffset = 5 + if (flags and (1 shl 1) != 0) 2 else 0
        assertEquals(3, u24(packet, distanceOffset))
        val stepRateOffset = distanceOffset + 3
        assertEquals(120, u16(packet, stepRateOffset))
        assertEquals(120, u16(packet, stepRateOffset + 2))
        assertEquals(20, u16(packet, stepRateOffset + 4))
    }

    @Test
    fun `CSC sensor-only bike and elliptical advertise no target controls`() {
        val targets = TargetCapabilities.fromEngineCapabilities(sensorOnlyProviderCapabilities())

        assertEquals(0, targets.toTargetFeatureBits())
        listOf(FtmsMachineProfile.INDOOR_BIKE, FtmsMachineProfile.ELLIPTICAL).forEach { profile ->
            assertEquals(0L, u32(buildFtmsFeaturePayload(profile, targets), 4))
        }
    }

    private fun emit(
        fusion: MetricFusion,
        connection: ConnectionId,
        metrics: List<CscDerivedMetric>,
    ) {
        val source = MetricSourceId(connection, MetricProtocol.CSC, SigUuids.UUID_CSC_MEASUREMENT)
        metrics.forEach { metric ->
            MetricEmitter(fusion, source, metric.sourceClass, arrivalMs = 101).emit(
                metric.metric,
                metric.value,
                nativeCumulative = metric.nativeCumulative,
                quality = MetricQuality.DERIVED,
            )
        }
    }

    private fun combinedUpdate(
        wheelRevolutions: Long,
        crankRevolutions: Int,
        ticks: Int,
    ) = CscMeasurementTracker.Update(
        reading = CscMeasurement.Reading(),
        wheelDelta = CscMeasurementTracker.WheelDelta(
            revolutions = wheelRevolutions,
            eventTimeTicks = ticks,
            rebaselinedAfterDecrease = false,
        ),
        crankDelta = CscMeasurementTracker.CrankDelta(crankRevolutions, ticks),
    )

    private fun u16(value: ByteArray, offset: Int): Int =
        (value[offset].toInt() and 0xFF) or ((value[offset + 1].toInt() and 0xFF) shl 8)

    private fun u24(value: ByteArray, offset: Int): Int =
        u16(value, offset) or ((value[offset + 2].toInt() and 0xFF) shl 16)

    private fun u32(value: ByteArray, offset: Int): Long =
        u16(value, offset).toLong() or (u16(value, offset + 2).toLong() shl 16)
}
