package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtEngineFtmsParsers
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtEngineState
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.ConnectionId
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.CpsMeasurement
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.MetricEmitter
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.MetricFusion
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.MetricKind
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.MetricProtocol
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.MetricSourceClass
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.MetricSourceId
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.FtmsSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt

class KickrSyntheticModelTest {
    @Test
    fun `same controls and ticks produce identical deterministic snapshots`() {
        val first = KickrSyntheticModel()
        val second = KickrSyntheticModel()
        listOf(first, second).forEach {
            it.startOrResume()
            it.setTargetPower(250)
        }

        repeat(20) {
            assertEquals(first.advance(0.5), second.advance(0.5))
        }
    }

    @Test
    fun `CPS and FTMS frames are coherent capture-shaped views of one snapshot`() {
        val model = KickrSyntheticModel()
        model.startOrResume()
        model.setTargetPower(250)
        repeat(20) { model.advance(0.5) }
        val snapshot = model.snapshot()

        val cpsBytes = KickrStandardEncoders.cyclingPowerMeasurement(snapshot)
        val cps = requireNotNull(CpsMeasurement.parse(cpsBytes))
        assertEquals(16, cpsBytes.size)
        assertEquals(KickrStandardEncoders.CPS_FLAGS_CAPTURE_SHAPE, cps.flags)
        assertEquals(snapshot.powerWatts, cps.instantaneousPowerWatts)
        assertEquals(snapshot.cumulativeWheelRevolutions, cps.wheel?.cumulativeRevolutions)
        assertEquals(snapshot.cumulativeCrankRevolutions, cps.crank?.cumulativeRevolutions)

        val bikeBytes = KickrStandardEncoders.indoorBikeData(snapshot)
        assertEquals(8, bikeBytes.size)
        val parsed = parseBike(bikeBytes)
        assertEquals(snapshot.speedKph, parsed.state.speedKph.value!!, 0.01)
        assertEquals(snapshot.cadenceRpm, parsed.state.instantCadence.value!!, 0.5)
        assertEquals(snapshot.powerWatts, parsed.state.instantPower.value)
    }

    @Test
    fun `consecutive CPS events derive the shared cadence and wheel based power`() {
        val model = KickrSyntheticModel()
        model.startOrResume()

        // Let both first-order targets settle, then validate long enough to cross the uint16
        // accumulated-torque and event-time rollovers seen in the Wahoo-client capture.
        repeat(80) { model.advance(0.5) }
        assertCpsDeltasTrackModel(model, expectedPowerWatts = 160, packets = 300)

        assertTrue(model.setTargetPower(250))
        repeat(80) { model.advance(0.5) }
        assertCpsDeltasTrackModel(model, expectedPowerWatts = 250, packets = 300)
    }

    @Test
    fun `Core features and ranges retain captured bytes`() {
        assertArrayEquals(
            byteArrayOf(0x03, 0x40, 0x00, 0x00, 0x0C, 0x60, 0x00, 0x00),
            KickrStandardEncoders.CORE_FTMS_FEATURE,
        )
        assertArrayEquals(
            byteArrayOf(0x0A, 0x00, 0x96.toByte(), 0x00, 0x0A, 0x00),
            KickrStandardEncoders.SUPPORTED_RESISTANCE_RANGE,
        )
        assertEquals(0x600C, KickrFtmsCapabilities.VALUE.toTargetFeatureBits())
    }

    @Test
    fun `CORE 2 serves synthetic standard values for its approved compatibility GATT`() {
        val value = requireNotNull(
            KickrStandardEncoders.readValue(
                KickrSimulatorPersona.CORE2_FC82_PROVISIONAL,
                BluetoothIds.UUID_FITNESS_MACHINE_FEATURE,
            ),
        )
        assertArrayEquals(KickrStandardEncoders.CORE_FTMS_FEATURE, value.bytes)
        assertTrue(KickrEvidenceLabel.M in value.evidence)
        assertTrue(KickrEvidenceLabel.SYN in value.evidence)
    }

    @Test
    fun `simulation publishes shared road load through CPS and FTMS`() {
        val model = KickrSyntheticModel()
        model.startOrResume()
        val sim = KickrSimulationTarget(
            windMps = 2.0,
            gradePercent = 4.0,
            crr = 0.0045,
            cw = 0.51,
            totalMassKg = 92.0,
        )
        assertTrue(model.setSimulation(sim, KickrControlSource.RIDE_ON))
        repeat(40) { model.advance(0.5) }
        val snapshot = model.snapshot()
        val expected = CyclingSimulationPhysics.requiredRiderPowerWatts(
            speedKph = snapshot.speedKph,
            gradePercent = sim.gradePercent,
            crr = sim.crr,
            cwKgPerM = sim.cw,
            windMps = sim.windMps,
            totalMassKg = sim.totalMassKg,
        ).roundToInt()

        assertEquals(expected, snapshot.powerWatts)
        assertEquals(
            expected,
            requireNotNull(
                CpsMeasurement.parse(KickrStandardEncoders.cyclingPowerMeasurement(snapshot)),
            ).instantaneousPowerWatts,
        )
        assertEquals(
            expected,
            parseBike(KickrStandardEncoders.indoorBikeData(snapshot)).state.instantPower.value,
        )
    }

    @Test
    fun `FTMS control owns session and applies power simulation resistance and circumference`() {
        val processor = KickrFtmsControlProcessor()

        assertResult(FtmsSpec.Result.CONTROL_NOT_PERMITTED, processor.handle(bytes(0x05, 200, 0)))
        assertResult(FtmsSpec.Result.SUCCESS, processor.handle(bytes(0x00)))
        assertResult(FtmsSpec.Result.SUCCESS, processor.handle(bytes(0x07)))
        assertResult(FtmsSpec.Result.SUCCESS, processor.handle(bytes(0x05, 250, 0)))
        assertEquals(250, processor.model.snapshot().targetPowerWatts)
        assertEquals(KickrControlMode.ERG, processor.model.snapshot().mode)

        assertResult(FtmsSpec.Result.SUCCESS, processor.handle(bytes(0x11, 0, 0, 0xF4, 0x01, 40, 51)))
        assertEquals(KickrControlMode.SIMULATION, processor.model.snapshot().mode)
        assertEquals(5.0, processor.model.snapshot().simulation.gradePercent, 0.0)
        val simulated = processor.model.advance(0.5)
        assertEquals(
            CyclingSimulationPhysics.requiredRiderPowerWatts(
                speedKph = simulated.speedKph,
                gradePercent = 5.0,
                crr = 0.004,
                cwKgPerM = 0.51,
                windMps = 0.0,
            ).roundToInt(),
            simulated.powerWatts,
        )

        assertResult(FtmsSpec.Result.SUCCESS, processor.handle(bytes(0x04, 100, 0)))
        assertEquals(KickrControlMode.RESISTANCE, processor.model.snapshot().mode)
        assertEquals(10.0, processor.model.snapshot().resistanceLevel, 0.0)

        assertResult(FtmsSpec.Result.SUCCESS, processor.handle(bytes(0x12, 0x52, 0x52)))
        assertEquals(2107.4, processor.model.snapshot().wheelCircumferenceMm, 0.0)
    }

    @Test
    fun `simulation clamps to KICKR power range and ERG clears physics ownership`() {
        val model = KickrSyntheticModel()
        model.startOrResume()
        assertTrue(model.setSimulation(KickrSimulationTarget(gradePercent = 200.0)))
        repeat(40) { model.advance(0.5) }
        assertEquals(KickrSyntheticModel.MAX_POWER_WATTS, model.snapshot().powerWatts)

        assertTrue(model.setTargetPower(250, KickrControlSource.FTMS))
        model.advance(3.0)
        assertEquals(KickrControlMode.ERG, model.snapshot().mode)
        assertEquals(250, model.snapshot().powerWatts)
        assertEquals(SimulatorControlModeLabels.FTMS_ERG, model.snapshot().controlLabel)
    }

    @Test
    fun `malformed and out of range controls do not mutate model`() {
        val processor = KickrFtmsControlProcessor()
        assertResult(FtmsSpec.Result.SUCCESS, processor.handle(bytes(0x00)))
        val before = processor.model.snapshot()

        assertResult(FtmsSpec.Result.INVALID_PARAMETER, processor.handle(bytes(0x12, 1)))
        assertResult(FtmsSpec.Result.INVALID_PARAMETER, processor.handle(bytes(0x12, 0, 0)))
        assertResult(FtmsSpec.Result.INVALID_PARAMETER, processor.handle(bytes(0x05, 0x01, 0x08)))
        assertResult(FtmsSpec.Result.INVALID_PARAMETER, processor.handle(bytes(0x04, 0xA0, 0x00)))
        assertEquals(before, processor.model.snapshot())
        assertEquals(before, processor.model.advance(Double.NaN))
        assertEquals(before, processor.model.advance(11.0))
    }

    @Test
    fun `disconnect clears procedure authority without resetting machine state`() {
        val processor = KickrFtmsControlProcessor()
        assertResult(FtmsSpec.Result.SUCCESS, processor.handle(bytes(0x00)))
        assertTrue(processor.session.hasControl)
        processor.disconnect()
        assertFalse(processor.session.hasControl)
        assertResult(FtmsSpec.Result.CONTROL_NOT_PERMITTED, processor.handle(bytes(0x07)))
    }

    private data class BikeHarness(val state: BtEngineState, val fusion: MetricFusion)

    private fun parseBike(value: ByteArray): BikeHarness {
        val state = BtEngineState()
        val fusion = MetricFusion(state)
        BtEngineFtmsParsers.doBike(
            MetricEmitter(
                fusion = fusion,
                sourceId = MetricSourceId(
                    ConnectionId("00:00:00:00:00:01", 1L),
                    MetricProtocol.FTMS,
                    BluetoothIds.UUID_BIKING_DATA,
                ),
                sourceClass = MetricSourceClass.FTMS,
                arrivalMs = 1L,
            ),
            value,
        )
        assertTrue(fusion.latestSample(MetricKind.SPEED) != null)
        return BikeHarness(state, fusion)
    }

    private fun assertResult(expected: Byte, outcome: com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.FtmsControlPointHandler.Outcome) {
        assertEquals(expected, outcome.response[2])
    }

    private fun assertCpsDeltasTrackModel(
        model: KickrSyntheticModel,
        expectedPowerWatts: Int,
        packets: Int,
    ) {
        var previous = requireNotNull(
            CpsMeasurement.parse(KickrStandardEncoders.cyclingPowerMeasurement(model.snapshot())),
        )
        var cadenceEvents = 0
        var torqueEvents = 0
        repeat(packets) {
            val snapshot = model.advance(0.5)
            val current = requireNotNull(
                CpsMeasurement.parse(KickrStandardEncoders.cyclingPowerMeasurement(snapshot)),
            )
            val previousCrank = requireNotNull(previous.crank)
            val currentCrank = requireNotNull(current.crank)
            val crankRevolutions = delta16(
                currentCrank.cumulativeRevolutions,
                previousCrank.cumulativeRevolutions,
            )
            val crankTicks = delta16(currentCrank.lastEventTimeTicks, previousCrank.lastEventTimeTicks)
            if (crankRevolutions > 0 && crankTicks > 0) {
                val derivedCadence = crankRevolutions * 60.0 * 1024.0 / crankTicks
                assertEquals(snapshot.cadenceRpm, derivedCadence, 0.25)
                cadenceEvents++
            }

            val previousWheel = requireNotNull(previous.wheel)
            val currentWheel = requireNotNull(current.wheel)
            val wheelRevolutions = delta32(
                currentWheel.cumulativeRevolutions,
                previousWheel.cumulativeRevolutions,
            )
            val wheelTicks = delta16(currentWheel.lastEventTimeTicks, previousWheel.lastEventTimeTicks)
            if (wheelRevolutions > 0 && wheelTicks > 0) {
                assertFalse(current.accumulatedTorqueCrankSource)
                val torqueDeltaNm = deltaTorque(
                    requireNotNull(current.accumulatedTorqueNewtonMetres),
                    requireNotNull(previous.accumulatedTorqueNewtonMetres),
                )
                val derivedPower = torqueDeltaNm * 2.0 * PI * 2048.0 / wheelTicks
                assertEquals(expectedPowerWatts.toDouble(), derivedPower, 1.5)
                torqueEvents++
            }
            previous = current
        }
        assertTrue("expected many crank events", cadenceEvents > packets / 2)
        assertTrue("expected a wheel event in every packet", torqueEvents == packets)
    }

    private fun delta16(current: Int, previous: Int): Int = (current - previous) and 0xFFFF

    private fun delta32(current: Long, previous: Long): Long =
        (current - previous) and 0xFFFF_FFFFL

    private fun deltaTorque(currentNm: Double, previousNm: Double): Double {
        val modulusNm = 65_536.0 / 32.0
        return (currentNm - previousNm + modulusNm) % modulusNm
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
