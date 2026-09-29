package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds
import com.gmail.absolutebollockscreations.gattlibrary.central.BtEngineFtmsParsers
import com.gmail.absolutebollockscreations.gattlibrary.central.BtEngineState
import com.gmail.absolutebollockscreations.gattlibrary.central.ConnectionId
import com.gmail.absolutebollockscreations.gattlibrary.central.CpsMeasurement
import com.gmail.absolutebollockscreations.gattlibrary.central.MetricEmitter
import com.gmail.absolutebollockscreations.gattlibrary.central.MetricFusion
import com.gmail.absolutebollockscreations.gattlibrary.central.MetricKind
import com.gmail.absolutebollockscreations.gattlibrary.central.MetricProtocol
import com.gmail.absolutebollockscreations.gattlibrary.central.MetricSourceClass
import com.gmail.absolutebollockscreations.gattlibrary.central.MetricSourceId
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.FtmsSpec
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
        // 0x2AD6 deliberately diverges from the captured `0a0096000a00`: that byte string came
        // from QZ's hardcoded Dircon table rather than a read of a physical trainer, and its
        // 1.0-15.0 ceiling rejected every whole-level resistance the Wahoo app writes. 1.0-100.0
        // step 1.0, derived from KickrFtmsCapabilities so the advertised and validated ranges
        // cannot drift.
        assertArrayEquals(
            byteArrayOf(0x0A, 0x00, 0xE8.toByte(), 0x03, 0x0A, 0x00),
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
        // Power is smoothed toward the physics solve (see the Kinomap grade-spike fix in
        // advance()) rather than snapping to it every tick, so a long, steady-grade run is
        // needed for both speed and power to reach their fixed points; a 1W tolerance absorbs
        // the residual asymptotic gap that smoothing never fully closes.
        repeat(200) { model.advance(0.5) }
        val snapshot = model.snapshot()
        val expected = CyclingSimulationPhysics.requiredRiderPowerWatts(
            speedKph = snapshot.speedKph,
            gradePercent = sim.gradePercent,
            crr = sim.crr,
            cwKgPerM = sim.cw,
            windMps = sim.windMps,
            totalMassKg = sim.totalMassKg,
        ).roundToInt()

        assertEquals(expected.toDouble(), snapshot.powerWatts.toDouble(), 1.0)
        assertEquals(
            snapshot.powerWatts,
            requireNotNull(
                CpsMeasurement.parse(KickrStandardEncoders.cyclingPowerMeasurement(snapshot)),
            ).instantaneousPowerWatts,
        )
        assertEquals(
            snapshot.powerWatts,
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
        // The first tick after entering SIMULATION smooths power toward the physics solve
        // instead of snapping to it (see the Kinomap grade-spike fix in advance()), so let the
        // model run long enough at this fixed grade for both speed and power to converge before
        // comparing against the closed-form steady-state result.
        var simulated = processor.model.advance(0.5)
        repeat(200) { simulated = processor.model.advance(0.5) }
        assertEquals(
            CyclingSimulationPhysics.requiredRiderPowerWatts(
                speedKph = simulated.speedKph,
                gradePercent = 5.0,
                crr = 0.004,
                cwKgPerM = 0.51,
                windMps = 0.0,
            ).roundToInt().toDouble(),
            simulated.powerWatts.toDouble(),
            1.0,
        )

        assertResult(FtmsSpec.Result.SUCCESS, processor.handle(bytes(0x04, 100, 0)))
        assertEquals(KickrControlMode.RESISTANCE, processor.model.snapshot().mode)
        assertEquals(10.0, processor.model.snapshot().resistanceLevel, 0.0)
        assertEquals(SimulatorControlModeLabels.FTMS_DIRECT, processor.model.snapshot().controlLabel)

        assertResult(FtmsSpec.Result.SUCCESS, processor.handle(bytes(0x12, 0x52, 0x52)))
        assertEquals(2107.4, processor.model.snapshot().wheelCircumferenceMm, 0.0)
    }

    @Test
    fun `simulation clamps to KICKR power range and ERG clears physics ownership`() {
        val model = KickrSyntheticModel()
        model.startOrResume()
        assertTrue(model.setSimulation(KickrSimulationTarget(gradePercent = 200.0)))
        // 200 ticks (100s) gives both speed and power time to fully converge under the shared
        // approach() smoothing (see KickrSyntheticModel.advance()) rather than settling a couple
        // of watts short of the clamp.
        repeat(200) { model.advance(0.5) }
        assertEquals(KickrSyntheticModel.MAX_POWER_WATTS, model.snapshot().powerWatts)

        assertTrue(model.setTargetPower(250, KickrControlSource.FTMS))
        model.advance(3.0)
        assertEquals(KickrControlMode.ERG, model.snapshot().mode)
        assertEquals(250, model.snapshot().powerWatts)
        assertEquals(SimulatorControlModeLabels.FTMS_ERG, model.snapshot().controlLabel)
    }

    @Test
    fun `a stray simulation write during ERG does not spike power on the very next tick`() {
        // Regression test for the 2026-08-30 Kinomap Zwift Hub capture: Kinomap re-sent an FTMS
        // Set Target Power keepalive roughly once a second while ERG was active, but also sent
        // one Set Indoor Bike Simulation Parameters write per route segment. When that grade
        // write landed between two ERG keepalives, the model briefly flipped into SIMULATION
        // and, before this fix, computed the physics-correct power to hold the STALE ERG speed
        // at the NEW steep grade in a single unsmoothed tick - a 654-670W spike off a 75W
        // steady state (docs/evidence/phase-10, 2026-08-30 Kinomap/Zwift Hub btsnoop capture).
        val model = KickrSyntheticModel()
        model.startOrResume()
        assertTrue(model.setTargetPower(70))
        repeat(200) { model.advance(0.5) }
        val ergSteadyState = model.snapshot()
        assertEquals(70, ergSteadyState.powerWatts)

        // The naive, unsmoothed physics result for the new grade at the stale ERG speed - this
        // is the value the old code snapped straight to.
        val steepGrade = KickrSimulationTarget(gradePercent = 9.12)
        val naiveSpikeWatts = CyclingSimulationPhysics.requiredRiderPowerWatts(
            speedKph = ergSteadyState.speedKph,
            gradePercent = steepGrade.gradePercent,
            crr = steepGrade.crr,
            cwKgPerM = steepGrade.cw,
            windMps = steepGrade.windMps,
            totalMassKg = steepGrade.totalMassKg,
        )
        assertTrue("fixture should reproduce a large naive spike", naiveSpikeWatts > 400.0)

        // One stray Set Indoor Bike Simulation Parameters write lands, exactly like the capture.
        assertTrue(model.setSimulation(steepGrade, KickrControlSource.FTMS))
        val firstSimulationTick = model.advance(0.5)

        assertEquals(KickrControlMode.SIMULATION, model.snapshot().mode)
        assertTrue(
            "power should be smoothed toward the spike, not snapped to it: " +
                "was ${firstSimulationTick.powerWatts}W, naive spike was ${naiveSpikeWatts.roundToInt()}W",
            firstSimulationTick.powerWatts < naiveSpikeWatts.roundToInt() / 2,
        )

        // The next ERG keepalive (as Kinomap sends about a second later) flips mode back, and
        // power settles smoothly to the original target rather than staying pinned to the spike.
        assertTrue(model.setTargetPower(70, KickrControlSource.FTMS))
        repeat(200) { model.advance(0.5) }
        assertEquals(KickrControlMode.ERG, model.snapshot().mode)
        assertEquals(70, model.snapshot().powerWatts)
    }

    @Test
    fun `malformed and out of range controls do not mutate model`() {
        val processor = KickrFtmsControlProcessor()
        assertResult(FtmsSpec.Result.SUCCESS, processor.handle(bytes(0x00)))
        val before = processor.model.snapshot()

        assertResult(FtmsSpec.Result.INVALID_PARAMETER, processor.handle(bytes(0x12, 1)))
        assertResult(FtmsSpec.Result.INVALID_PARAMETER, processor.handle(bytes(0x12, 0, 0)))
        assertResult(FtmsSpec.Result.INVALID_PARAMETER, processor.handle(bytes(0x05, 0x01, 0x08)))
        // SINT16 resistance past the persona's advertised 1.0-100.0 ceiling. Was 0x00A0 (160,
        // i.e. 16.0) back when the persona claimed a 15.0 maximum; that value is now legal, so
        // the case is restated against the range actually published on 0x2AD6 instead of being
        // left as an assertion that no longer tests anything.
        assertResult(FtmsSpec.Result.INVALID_PARAMETER, processor.handle(bytes(0x04, 0xF2, 0x03)))
        // Inside the range but off the advertised 1.0 step.
        assertResult(FtmsSpec.Result.INVALID_PARAMETER, processor.handle(bytes(0x04, 0x9D, 0x00)))
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

    private fun assertResult(expected: Byte, outcome: com.gmail.absolutebollockscreations.gattlibrary.peripheral.FtmsControlPointHandler.Outcome) {
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
