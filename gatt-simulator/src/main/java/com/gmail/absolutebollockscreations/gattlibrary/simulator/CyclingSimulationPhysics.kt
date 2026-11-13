package com.gmail.absolutebollockscreations.gattlibrary.simulator

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Shared steady-state road-load model for every controllable cycling simulator persona.
 *
 * `Cw` is the protocol's aerodynamic coefficient in kg/m, so `Cw * v²` is force in newtons.
 * Positive wind is treated as a headwind. Negative power demand (a descent that would require
 * braking to hold the published speed) is clamped to zero because these personas publish rider
 * power, not brake power.
 */
@SimulatorProbeApi
public object CyclingSimulationPhysics {
    public const val DEFAULT_RIDER_MASS_KG: Double = 75.0
    public const val DEFAULT_BIKE_MASS_KG: Double = 10.0
    public const val DEFAULT_TOTAL_MASS_KG: Double = DEFAULT_RIDER_MASS_KG + DEFAULT_BIKE_MASS_KG
    public const val DEFAULT_CRR: Double = 0.004
    public const val DEFAULT_CW_KG_PER_M: Double = 0.51
    public const val DEFAULT_DRIVETRAIN_EFFICIENCY: Double = 0.975

    public fun requiredRiderPowerWatts(
        speedKph: Double,
        gradePercent: Double,
        crr: Double,
        cwKgPerM: Double,
        windMps: Double,
        totalMassKg: Double = DEFAULT_TOTAL_MASS_KG,
        draftingFactor: Double = 1.0,
        drivetrainEfficiency: Double = DEFAULT_DRIVETRAIN_EFFICIENCY,
    ): Double {
        require(speedKph.isFinite() && speedKph >= 0.0)
        require(gradePercent.isFinite())
        require(crr.isFinite() && crr >= 0.0)
        require(cwKgPerM.isFinite() && cwKgPerM >= 0.0)
        require(windMps.isFinite())
        require(totalMassKg.isFinite() && totalMassKg > 0.0)
        require(draftingFactor.isFinite() && draftingFactor >= 0.0)
        require(drivetrainEfficiency.isFinite() && drivetrainEfficiency > 0.0 && drivetrainEfficiency <= 1.0)

        val groundSpeedMps = speedKph / 3.6
        if (groundSpeedMps == 0.0) return 0.0

        val gradeAngle = atan(gradePercent / 100.0)
        val gravityForce = totalMassKg * GRAVITY_MPS2 * sin(gradeAngle)
        val rollingForce = totalMassKg * GRAVITY_MPS2 * cos(gradeAngle) * crr
        val relativeAirSpeed = groundSpeedMps + windMps
        val aerodynamicForce = cwKgPerM * draftingFactor * relativeAirSpeed * abs(relativeAirSpeed)
        val wheelPower = (gravityForce + rollingForce + aerodynamicForce) * groundSpeedMps
        return max(0.0, wheelPower / drivetrainEfficiency)
    }

    private const val GRAVITY_MPS2 = 9.80665
}
