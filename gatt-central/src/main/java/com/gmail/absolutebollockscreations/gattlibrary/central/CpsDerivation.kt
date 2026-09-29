package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.FitBridgeBleInternalApi

/** Session-scoped derivation from native CPS wheel/crank events. */
@FitBridgeBleInternalApi
class CpsDerivationState {
    private var distanceMeters = 0.0

    fun derive(update: CpsMeasurementTracker.Update, wheelCircumferenceMm: Int): List<CscDerivedMetric> = buildList {
        update.wheelDelta?.let { delta ->
            val rpm = wheelCadenceRpm(delta) ?: return@let
            val circumferenceMeters = wheelCircumferenceMm / 1_000.0
            distanceMeters += delta.revolutions * circumferenceMeters
            add(CscDerivedMetric(MetricKind.SPEED, rpm * circumferenceMeters * 60.0 / 1_000.0, MetricSourceClass.CPS_WHEEL))
            add(CscDerivedMetric(MetricKind.DISTANCE, distanceMeters, MetricSourceClass.CPS_WHEEL, distanceMeters))
        }
        update.crankDelta?.let { delta ->
            crankCadenceRpm(delta)?.let { rpm ->
                add(CscDerivedMetric(MetricKind.CADENCE, rpm, MetricSourceClass.CPS_CRANK))
            }
        }
    }

    fun reset() {
        distanceMeters = 0.0
    }

    companion object {
        fun crankCadenceRpm(delta: CpsMeasurementTracker.CrankDelta): Double? =
            validRate(delta.revolutions.toDouble(), delta.eventTimeTicks, 1024.0, 510.0)

        fun wheelCadenceRpm(delta: CpsMeasurementTracker.WheelDelta): Double? =
            validRate(delta.revolutions.toDouble(), delta.eventTimeTicks, 2048.0, 1024.0)

        private fun validRate(revolutions: Double, ticks: Int, hz: Double, maximumRpm: Double): Double? {
            if (revolutions <= 0.0 || ticks <= 0) return null
            return (revolutions * 60.0 * hz / ticks).takeIf { it.isFinite() && it <= maximumRpm }
        }
    }
}
