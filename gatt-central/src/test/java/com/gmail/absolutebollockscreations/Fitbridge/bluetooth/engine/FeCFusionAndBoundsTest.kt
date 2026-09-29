package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.capture.BleCapture
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.capture.CaptureTrigger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Phase 9 §11.6 and §11b using decoded and mapped FE-C samples. */
class FeCFusionAndBoundsTest {

    private val address = "FE:C0:00:00:00:11"

    @Before
    fun resetBefore() = BleCapture.resetSession()

    @After
    fun resetAfter() = BleCapture.resetSession()

    private fun source(generation: Long) = MetricSourceId(
        ConnectionId(address, generation),
        MetricProtocol.VENDOR,
        BluetoothIds.UUID_FEC_MEASUREMENT,
    )

    private fun emit(
        fusion: MetricFusion,
        generation: Long,
        arrivalMs: Long,
        page: ByteArray,
    ): MetricSourceId {
        val connection = ConnectionId(address, generation)
        val session = FeCProtocolSession(connection)
        session.onPacket(
            BluetoothIds.UUID_FEC_MEASUREMENT,
            FeCFrameCodec.encode(0x4E, 5, page),
            arrivalMs,
        )
        val decoded = requireNotNull(session.lastAppliedPage)
        val snapshot = session.snapshot
        val samples = when (decoded) {
            is FeCPage.GeneralFe -> FeCMetricMapper.samplesForGeneralFe(decoded, snapshot)
            is FeCPage.GeneralSettings -> FeCMetricMapper.samplesForGeneralSettings(decoded)
            is FeCPage.GeneralMetabolicData -> FeCMetricMapper.samplesForMetabolicData(decoded, snapshot)
            is FeCPage.TrainerData -> FeCMetricMapper.samplesForTrainerData(
                decoded,
                requireNotNull(snapshot.eventCountRaw),
                requireNotNull(snapshot.accumulatedPowerRaw),
                null,
                null,
            )
            is FeCPage.SpecificNordicSkierData -> FeCMetricMapper.samplesForNordicSkierData(decoded, snapshot)
            else -> error("unsupported test page $decoded")
        }
        val source = source(generation)
        val emitter = MetricEmitter(fusion, source, MetricSourceClass.VENDOR_NATIVE, arrivalMs)
        samples.forEach { sample ->
            emitter.emit(sample.metric, sample.value, sample.eventMs, sample.nativeCumulative)
        }
        return source
    }

    @Test
    fun `FE-C metrics obey source priority manual assignment and freshness`() {
        val state = BtEngineState()
        val fusion = MetricFusion(state).also { it.setPersona(FusionPersona.RIDE, 0) }
        val fec = emit(fusion, 1, 1_000, page25(cadence = 90, power = 200))
        assertEquals(fec, fusion.selectedSource(MetricKind.POWER))
        assertEquals(200, state.instantPower.value)

        val cps = MetricSourceId(
            ConnectionId("FE:C0:00:00:00:22", 1),
            MetricProtocol.CPS,
            BluetoothIds.UUID_CYCLING_POWER_MEASUREMENT,
        )
        MetricEmitter(fusion, cps, MetricSourceClass.CPS, 1_100).emit(MetricKind.POWER, 240)
        assertEquals(cps, fusion.selectedSource(MetricKind.POWER))

        fusion.assign(MetricKind.POWER, fec, 1_100)
        assertEquals(fec, fusion.selectedSource(MetricKind.POWER))
        fusion.clearAssignment(MetricKind.POWER, 1_100)
        assertEquals(cps, fusion.selectedSource(MetricKind.POWER))

        fusion.refresh(4_101)
        assertNull(fusion.selectedSource(MetricKind.POWER))
        assertNull(state.instantPower.value)
    }

    @Test
    fun `FE-C cumulative distance and calories remain continuous across reconnect`() {
        val state = BtEngineState()
        val fusion = MetricFusion(state).also { it.setPersona(FusionPersona.RIDE, 0) }

        emit(fusion, 1, 1_000, page16(distance = 100))
        emit(fusion, 1, 2_000, page16(distance = 120))
        emit(fusion, 1, 2_100, page18(calories = 10))
        emit(fusion, 1, 2_200, page18(calories = 12))
        assertEquals(120, state.totalDistanceMeters.value)
        assertEquals(12, state.expEnergyTotal.value)

        fusion.onDisconnected(ConnectionId(address, 1), 2_300)
        emit(fusion, 2, 2_400, page16(distance = 2))
        emit(fusion, 2, 2_500, page18(calories = 1))
        assertEquals(120, state.totalDistanceMeters.value)
        assertEquals(12, state.expEnergyTotal.value)

        emit(fusion, 2, 2_600, page16(distance = 5))
        emit(fusion, 2, 2_700, page18(calories = 3))
        assertEquals(123, state.totalDistanceMeters.value)
        assertEquals(14, state.expEnergyTotal.value)
    }

    @Test
    fun `out-of-range FE-C speed cadence power inclination energy-rate and ski rate are dropped and captured`() {
        val cases = listOf(
            FusionPersona.RIDE to page16(speedRaw = 0xFFFF),
            FusionPersona.RIDE to page25(cadence = 254, power = 200),
            FusionPersona.RIDE to page25(cadence = 90, power = 4_094),
            // 0x7FFF is ANT+'s signed not-available sentinel; use the largest real value.
            FusionPersona.RUN to page17(inclineRaw = 0x7FFE),
            FusionPersona.RIDE to page18(caloriesPerHourRaw = 0xFFFE),
            FusionPersona.ROW_PADDLE to page24(strideRate = 254),
        )
        val expectedMetrics = listOf(
            MetricKind.SPEED,
            MetricKind.CADENCE,
            MetricKind.POWER,
            MetricKind.INCLINATION,
            MetricKind.ENERGY_RATE,
            MetricKind.STROKE_RATE,
        )

        cases.zip(expectedMetrics).forEachIndexed { index, (case, metric) ->
            BleCapture.resetSession()
            val fusion = MetricFusion(BtEngineState()).also { it.setPersona(case.first, 0) }
            emit(fusion, index.toLong() + 1, 1_000, case.second)
            assertNull("$metric must not publish", fusion.selectedSource(metric))
            assertEquals("$metric must arm capture", CaptureTrigger.PARSER_REJECTION, BleCapture.armedBy())
        }
    }

    @Test
    fun `FE-C cumulative metrics are deliberately unbounded`() {
        for (metric in listOf(MetricKind.DISTANCE, MetricKind.ENERGY, MetricKind.ELAPSED, MetricKind.TOTAL_STRIDES)) {
            assertNull(PlausibilityBounds.rangeFor(metric, FusionPersona.RIDE))
        }
    }

    private fun page16(distance: Int = 10, speedRaw: Int = 5_000) = byteArrayOf(
        0x10, 25, 4, distance.toByte(), speedRaw.toByte(), (speedRaw ushr 8).toByte(), 140.toByte(), 0x34,
    )

    private fun page17(inclineRaw: Int = 0) = byteArrayOf(
        0x11, 0xFF.toByte(), 0xFF.toByte(), 200.toByte(), inclineRaw.toByte(), (inclineRaw ushr 8).toByte(), 50, 0x30,
    )

    private fun page18(calories: Int = 5, caloriesPerHourRaw: Int = 600, metRaw: Int = 800) = byteArrayOf(
        0x12, 0xFF.toByte(), metRaw.toByte(), (metRaw ushr 8).toByte(), caloriesPerHourRaw.toByte(),
        (caloriesPerHourRaw ushr 8).toByte(), calories.toByte(), 0x31,
    )

    private fun page24(strideRate: Int) = byteArrayOf(
        0x18, 0xFF.toByte(), 0xFF.toByte(), 10, strideRate.toByte(), 200.toByte(), 0, 0x31,
    )

    private fun page25(cadence: Int, power: Int): ByteArray = byteArrayOf(
        0x19, 1, cadence.toByte(), power.toByte(), 0, power.toByte(), ((power ushr 8) and 0x0F).toByte(), 0x30,
    )
}
