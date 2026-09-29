package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.BleDefaults
import com.gmail.absolutebollockscreations.gattlibrary.core.FitBridgeBleInternalApi

@FitBridgeBleInternalApi
enum class CscWorkoutPersona { BIKE, ELLIPTICAL }

internal data class CscSessionSnapshot(
    val generation: Long,
    val persona: CscWorkoutPersona,
    val calibration: CscCalibration,
)

@FitBridgeBleInternalApi
data class CscCalibration(
    val wheelCircumferenceMm: Int = BleDefaults.DEFAULT_CSC_WHEEL_CIRCUMFERENCE_MM,
    val ellipticalStrideLengthMm: Int = BleDefaults.DEFAULT_ELLIPTICAL_STRIDE_LENGTH_MM,
) {
    init {
        require(wheelCircumferenceMm in
            BleDefaults.MIN_CSC_WHEEL_CIRCUMFERENCE_MM..BleDefaults.MAX_CSC_WHEEL_CIRCUMFERENCE_MM)
        require(ellipticalStrideLengthMm in
            BleDefaults.MIN_ELLIPTICAL_STRIDE_LENGTH_MM..BleDefaults.MAX_ELLIPTICAL_STRIDE_LENGTH_MM)
    }
}

@FitBridgeBleInternalApi
data class CscDerivedMetric(
    val metric: MetricKind,
    val value: Double,
    val sourceClass: MetricSourceClass,
    val nativeCumulative: Double? = null,
)

/** Session-scoped metric accumulation over one link's native CSC event deltas. */
@FitBridgeBleInternalApi
class CscDerivationState {
    private var bikeDistanceMeters = 0.0
    private var ellipticalDistanceMeters = 0.0
    private var ellipticalStrides = 0.0

    fun derive(
        update: CscMeasurementTracker.Update,
        persona: CscWorkoutPersona,
        supportedRoles: Set<BtEngineState.CscRole>,
        assignedRoles: Set<BtEngineState.CscRole>,
        calibration: CscCalibration,
    ): List<CscDerivedMetric> = when (persona) {
        CscWorkoutPersona.BIKE -> deriveBike(update, assignedRoles, calibration)
        CscWorkoutPersona.ELLIPTICAL -> deriveElliptical(update, supportedRoles, calibration)
    }

    fun reset() {
        bikeDistanceMeters = 0.0
        ellipticalDistanceMeters = 0.0
        ellipticalStrides = 0.0
    }

    private fun deriveBike(
        update: CscMeasurementTracker.Update,
        assignedRoles: Set<BtEngineState.CscRole>,
        calibration: CscCalibration,
    ): List<CscDerivedMetric> = buildList {
        if (BtEngineState.CscRole.SPEED in assignedRoles) {
            update.wheelDelta?.let { delta ->
                val rpm = validRateRpm(
                    revolutions = delta.revolutions.toDouble(),
                    eventTimeTicks = delta.eventTimeTicks,
                    maximumRpm = MAX_WHEEL_RPM,
                ) ?: return@let
                val circumferenceMeters = calibration.wheelCircumferenceMm / 1_000.0
                bikeDistanceMeters += delta.revolutions * circumferenceMeters
                add(
                    CscDerivedMetric(
                        MetricKind.SPEED,
                        rpm * circumferenceMeters * 60.0 / 1_000.0,
                        MetricSourceClass.CSC_WHEEL,
                    ),
                )
                add(
                    CscDerivedMetric(
                        MetricKind.DISTANCE,
                        bikeDistanceMeters,
                        MetricSourceClass.CSC_WHEEL,
                        nativeCumulative = bikeDistanceMeters,
                    ),
                )
            }
        }
        if (BtEngineState.CscRole.CADENCE in assignedRoles) {
            update.crankDelta?.let { delta ->
                val rpm = validRateRpm(
                    revolutions = delta.revolutions.toDouble(),
                    eventTimeTicks = delta.eventTimeTicks,
                    maximumRpm = MAX_CRANK_RPM,
                ) ?: return@let
                add(CscDerivedMetric(MetricKind.CADENCE, rpm, MetricSourceClass.CSC_CRANK))
            }
        }
    }

    private fun deriveElliptical(
        update: CscMeasurementTracker.Update,
        supportedRoles: Set<BtEngineState.CscRole>,
        calibration: CscCalibration,
    ): List<CscDerivedMetric> {
        // Feature support selects the native field, not packet availability. A combined sensor
        // therefore never falls back to wheel when its preferred crank event is absent/rejected.
        val selected = if (BtEngineState.CscRole.CADENCE in supportedRoles) {
            update.crankDelta?.let { delta ->
                Triple(
                    delta.revolutions.toDouble(),
                    delta.eventTimeTicks,
                    MetricSourceClass.CSC_CRANK,
                )
            }
        } else {
            update.wheelDelta?.let { delta ->
                Triple(
                    delta.revolutions.toDouble(),
                    delta.eventTimeTicks,
                    MetricSourceClass.CSC_WHEEL,
                )
            }
        } ?: return emptyList()
        val maximumRpm = if (selected.third == MetricSourceClass.CSC_CRANK) {
            MAX_CRANK_RPM
        } else {
            MAX_WHEEL_RPM
        }
        val strideRate = validRateRpm(selected.first, selected.second, maximumRpm)
            ?: return emptyList()
        val strideLengthMeters = calibration.ellipticalStrideLengthMm / 1_000.0
        ellipticalStrides += selected.first
        ellipticalDistanceMeters += selected.first * strideLengthMeters
        return listOf(
            CscDerivedMetric(MetricKind.CADENCE, strideRate, selected.third),
            CscDerivedMetric(
                MetricKind.TOTAL_STRIDES,
                ellipticalStrides,
                selected.third,
                nativeCumulative = ellipticalStrides,
            ),
            CscDerivedMetric(
                MetricKind.SPEED,
                strideRate * strideLengthMeters * 60.0 / 1_000.0,
                selected.third,
            ),
            CscDerivedMetric(
                MetricKind.DISTANCE,
                ellipticalDistanceMeters,
                selected.third,
                nativeCumulative = ellipticalDistanceMeters,
            ),
        )
    }

    private fun validRateRpm(
        revolutions: Double,
        eventTimeTicks: Int,
        maximumRpm: Double,
    ): Double? {
        if (revolutions <= 0.0 || eventTimeTicks <= 0) return null
        val rpm = revolutions * SECONDS_PER_MINUTE * EVENT_TIME_HZ / eventTimeTicks
        return rpm.takeIf { it.isFinite() && it <= maximumRpm }
    }

    private companion object {
        const val EVENT_TIME_HZ = 1_024.0
        const val SECONDS_PER_MINUTE = 60.0
        const val MAX_WHEEL_RPM = 1_024.0
        const val MAX_CRANK_RPM = 510.0
    }
}
