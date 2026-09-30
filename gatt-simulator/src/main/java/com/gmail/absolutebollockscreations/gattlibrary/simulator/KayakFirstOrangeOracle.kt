package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.KayakFirstSpec
import kotlin.math.PI
import kotlin.math.pow

/**
 * Simulator-owned Orange flywheel model (LIB-19l): the manufacturer equations recovered from the
 * official application (E2 4.2.0), restated so the simulator's timing generator and its oracle need
 * no central type. Central's `KayakFirstOrangePhysics` implements the same equations for decoding;
 * tests compare the two.
 */
internal data class KayakFirstOrangeTimingTuple(
    val tMinSeconds: Double,
    val tHSeconds: Double,
    val tMaxSeconds: Double,
    val tVSeconds: Double,
)

internal data class KayakFirstOrangePhysicsResult(
    val powerWatts: Double,
    val forceNewtons: Double,
    val speedMetresPerSecond: Double,
    val strokeRateSpm: Double,
    val pace500Seconds: Double?,
)

internal object KayakFirstOrangePhysics {
    fun calculate(
        timing: KayakFirstOrangeTimingTuple,
        athleteWeightKg: Double?,
    ): KayakFirstOrangePhysicsResult? {
        val values = listOf(
            timing.tMinSeconds,
            timing.tHSeconds,
            timing.tMaxSeconds,
            timing.tVSeconds,
        )
        if (values.any { !it.isFinite() || it <= 0.0 }) return null
        if (timing.tMaxSeconds < timing.tMinSeconds) return null
        val mass = athleteWeightKg?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val omegaMax = 2.0 * PI / timing.tMinSeconds
        val omegaMin = 2.0 * PI / timing.tMaxSeconds
        val power = (0.5 * KayakFirstSpec.FLYWHEEL_INERTIA * omegaMax.pow(2.0) -
            0.5 * KayakFirstSpec.FLYWHEEL_INERTIA * omegaMin.pow(2.0)) / timing.tHSeconds
        val force = KayakFirstSpec.FLYWHEEL_INERTIA * (omegaMax - omegaMin) /
            (KayakFirstSpec.FORCE_RADIUS_METRES * timing.tHSeconds)
        if (!power.isFinite() || !force.isFinite() || power < 0.0 || force < 0.0) return null
        val effectiveMass = mass + KayakFirstSpec.BOAT_MASS_OFFSET_KG
        val speed = if (power == 0.0) 0.0 else {
            (power / (KayakFirstSpec.DRAG_COEFFICIENT * effectiveMass.pow(2.0 / 3.0))).pow(1.0 / 3.0)
        }
        val strokeRate = 60.0 / (timing.tHSeconds + timing.tVSeconds)
        if (!speed.isFinite() || !strokeRate.isFinite()) return null
        return KayakFirstOrangePhysicsResult(
            powerWatts = power,
            forceNewtons = force,
            speedMetresPerSecond = speed,
            strokeRateSpm = strokeRate,
            pace500Seconds = speed.takeIf { it > 0.0 }?.let { 500.0 / it },
        )
    }
}
