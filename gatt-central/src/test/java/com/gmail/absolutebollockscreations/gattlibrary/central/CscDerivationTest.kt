package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids

class CscDerivationTest {

    @Test
    fun `bike wheel derives speed and cumulative distance using live circumference`() {
        val state = CscDerivationState()
        val first = state.derive(
            wheelUpdate(revolutions = 2, ticks = 1_024),
            CscWorkoutPersona.BIKE,
            supportedRoles = setOf(BtEngineState.CscRole.SPEED),
            assignedRoles = setOf(BtEngineState.CscRole.SPEED),
            calibration = CscCalibration(wheelCircumferenceMm = 2_405),
        )

        assertEquals(17.316, first.metric(MetricKind.SPEED).value, 1e-9)
        assertEquals(4.81, first.metric(MetricKind.DISTANCE).value, 1e-9)
        assertEquals(4.81, first.metric(MetricKind.DISTANCE).nativeCumulative!!, 1e-9)
        assertEquals(MetricSourceClass.CSC_WHEEL, first.metric(MetricKind.SPEED).sourceClass)

        val afterSettingChange = state.derive(
            wheelUpdate(revolutions = 1, ticks = 1_024),
            CscWorkoutPersona.BIKE,
            supportedRoles = setOf(BtEngineState.CscRole.SPEED),
            assignedRoles = setOf(BtEngineState.CscRole.SPEED),
            calibration = CscCalibration(wheelCircumferenceMm = 1_000),
        )
        assertEquals(3.6, afterSettingChange.metric(MetricKind.SPEED).value, 1e-9)
        assertEquals(5.81, afterSettingChange.metric(MetricKind.DISTANCE).value, 1e-9)
    }

    @Test
    fun `bike crank derives cadence and assigned roles suppress the other native field`() {
        val combined = combinedUpdate(wheelRevolutions = 1, crankRevolutions = 2, ticks = 1_024)
        val cadenceOnly = CscDerivationState().derive(
            combined,
            CscWorkoutPersona.BIKE,
            supportedRoles = BtEngineState.CscRole.entries.toSet(),
            assignedRoles = setOf(BtEngineState.CscRole.CADENCE),
            calibration = CscCalibration(),
        )
        assertEquals(setOf(MetricKind.CADENCE), cadenceOnly.mapTo(linkedSetOf()) { it.metric })
        assertEquals(120.0, cadenceOnly.single().value, 0.0)
        assertEquals(MetricSourceClass.CSC_CRANK, cadenceOnly.single().sourceClass)

        val speedOnly = CscDerivationState().derive(
            combined,
            CscWorkoutPersona.BIKE,
            supportedRoles = BtEngineState.CscRole.entries.toSet(),
            assignedRoles = setOf(BtEngineState.CscRole.SPEED),
            calibration = CscCalibration(),
        )
        assertEquals(setOf(MetricKind.SPEED, MetricKind.DISTANCE), speedOnly.mapTo(linkedSetOf()) { it.metric })
    }

    @Test
    fun `zero time and implausible bike rates emit nothing and do not accumulate distance`() {
        val state = CscDerivationState()
        val roles = setOf(BtEngineState.CscRole.SPEED)
        assertTrue(
            state.derive(
                wheelUpdate(1, 0),
                CscWorkoutPersona.BIKE,
                roles,
                roles,
                CscCalibration(),
            ).isEmpty(),
        )
        assertTrue(
            state.derive(
                wheelUpdate(1_025, 60 * 1_024),
                CscWorkoutPersona.BIKE,
                roles,
                roles,
                CscCalibration(),
            ).isEmpty(),
        )
        val valid = state.derive(
            wheelUpdate(1, 1_024),
            CscWorkoutPersona.BIKE,
            roles,
            roles,
            CscCalibration(),
        )
        assertEquals(2.405, valid.metric(MetricKind.DISTANCE).value, 1e-9)
    }

    @Test
    fun `crank rate above 510 rpm is rejected`() {
        val roles = setOf(BtEngineState.CscRole.CADENCE)
        val output = CscDerivationState().derive(
            crankUpdate(revolutions = 511, ticks = 60 * 1_024),
            CscWorkoutPersona.BIKE,
            roles,
            roles,
            CscCalibration(),
        )
        assertTrue(output.isEmpty())
    }

    @Test
    fun `elliptical wheel-only treats every revolution as one stride`() {
        val output = CscDerivationState().derive(
            wheelUpdate(revolutions = 1, ticks = 1_024),
            CscWorkoutPersona.ELLIPTICAL,
            supportedRoles = setOf(BtEngineState.CscRole.SPEED),
            assignedRoles = setOf(BtEngineState.CscRole.SPEED),
            calibration = CscCalibration(ellipticalStrideLengthMm = 1_600),
        )

        assertEquals(60.0, output.metric(MetricKind.CADENCE).value, 0.0)
        assertEquals(1.0, output.metric(MetricKind.TOTAL_STRIDES).value, 0.0)
        assertEquals(5.76, output.metric(MetricKind.SPEED).value, 1e-9)
        assertEquals(1.6, output.metric(MetricKind.DISTANCE).value, 1e-9)
        assertTrue(output.all { it.sourceClass == MetricSourceClass.CSC_WHEEL })
    }

    @Test
    fun `elliptical combined prefers crank and never double counts wheel`() {
        val output = CscDerivationState().derive(
            combinedUpdate(wheelRevolutions = 20, crankRevolutions = 2, ticks = 1_024),
            CscWorkoutPersona.ELLIPTICAL,
            supportedRoles = BtEngineState.CscRole.entries.toSet(),
            assignedRoles = BtEngineState.CscRole.entries.toSet(),
            calibration = CscCalibration(ellipticalStrideLengthMm = 1_600),
        )

        assertEquals(120.0, output.metric(MetricKind.CADENCE).value, 0.0)
        assertEquals(2.0, output.metric(MetricKind.TOTAL_STRIDES).value, 0.0)
        assertEquals(3.2, output.metric(MetricKind.DISTANCE).value, 1e-9)
        assertTrue(output.all { it.sourceClass == MetricSourceClass.CSC_CRANK })
    }

    @Test
    fun `elliptical combined does not fall back to wheel when crank is rejected`() {
        val state = CscDerivationState()
        val combinedRoles = BtEngineState.CscRole.entries.toSet()
        val rejected = state.derive(
            combinedUpdate(wheelRevolutions = 1, crankRevolutions = 511, ticks = 60 * 1_024),
            CscWorkoutPersona.ELLIPTICAL,
            combinedRoles,
            combinedRoles,
            CscCalibration(),
        )
        assertTrue(rejected.isEmpty())

        val valid = state.derive(
            combinedUpdate(wheelRevolutions = 10, crankRevolutions = 1, ticks = 1_024),
            CscWorkoutPersona.ELLIPTICAL,
            combinedRoles,
            combinedRoles,
            CscCalibration(),
        )
        assertEquals(1.0, valid.metric(MetricKind.TOTAL_STRIDES).value, 0.0)
    }

    @Test
    fun `elliptical stride length change applies to next event`() {
        val state = CscDerivationState()
        val roles = setOf(BtEngineState.CscRole.CADENCE)
        state.derive(
            crankUpdate(1, 1_024),
            CscWorkoutPersona.ELLIPTICAL,
            roles,
            roles,
            CscCalibration(ellipticalStrideLengthMm = 1_600),
        )
        val changed = state.derive(
            crankUpdate(1, 1_024),
            CscWorkoutPersona.ELLIPTICAL,
            roles,
            roles,
            CscCalibration(ellipticalStrideLengthMm = 2_000),
        )
        assertEquals(7.2, changed.metric(MetricKind.SPEED).value, 1e-9)
        assertEquals(3.6, changed.metric(MetricKind.DISTANCE).value, 1e-9)
    }

    @Test
    fun `fusion preserves CSC distance across a reconnect source generation`() {
        val engineState = BtEngineState()
        val fusion = MetricFusion(engineState)
        fusion.startSession(FusionPersona.RIDE, 100)
        val firstSource = MetricSourceId(
            ConnectionId("CSC", 1),
            MetricProtocol.CSC,
            SigUuids.UUID_CSC_MEASUREMENT,
        )
        val secondSource = firstSource.copy(
            originId = MetricOriginId.Gatt(ConnectionId("CSC", 2)),
        )

        MetricEmitter(fusion, firstSource, MetricSourceClass.CSC_WHEEL, 101).emit(
            MetricKind.DISTANCE,
            2.405,
            nativeCumulative = 2.405,
            quality = MetricQuality.DERIVED,
        )
        assertEquals(2, engineState.totalDistanceMeters.value)

        MetricEmitter(fusion, secondSource, MetricSourceClass.CSC_WHEEL, 102).emit(
            MetricKind.DISTANCE,
            2.405,
            nativeCumulative = 2.405,
            quality = MetricQuality.DERIVED,
        )
        assertEquals(2, engineState.totalDistanceMeters.value)

        MetricEmitter(fusion, secondSource, MetricSourceClass.CSC_WHEEL, 103).emit(
            MetricKind.DISTANCE,
            4.81,
            nativeCumulative = 4.81,
            quality = MetricQuality.DERIVED,
        )
        assertEquals(5, engineState.totalDistanceMeters.value)
    }

    private fun List<CscDerivedMetric>.metric(kind: MetricKind): CscDerivedMetric =
        single { it.metric == kind }

    private fun wheelUpdate(revolutions: Long, ticks: Int) = CscMeasurementTracker.Update(
        reading = CscMeasurement.Reading(),
        wheelDelta = CscMeasurementTracker.WheelDelta(
            revolutions,
            ticks,
            rebaselinedAfterDecrease = false,
        ),
    )

    private fun crankUpdate(revolutions: Int, ticks: Int) = CscMeasurementTracker.Update(
        reading = CscMeasurement.Reading(),
        crankDelta = CscMeasurementTracker.CrankDelta(revolutions, ticks),
    )

    private fun combinedUpdate(
        wheelRevolutions: Long,
        crankRevolutions: Int,
        ticks: Int,
    ) = CscMeasurementTracker.Update(
        reading = CscMeasurement.Reading(),
        wheelDelta = CscMeasurementTracker.WheelDelta(
            wheelRevolutions,
            ticks,
            rebaselinedAfterDecrease = false,
        ),
        crankDelta = CscMeasurementTracker.CrankDelta(crankRevolutions, ticks),
    )
}
