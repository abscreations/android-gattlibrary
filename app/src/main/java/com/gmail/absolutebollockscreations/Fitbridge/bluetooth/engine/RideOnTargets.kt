package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal sealed interface RideOnControlTarget {
    data class Erg(val watts: Int) : RideOnControlTarget
    data class Simulation(
        val windMps: Double,
        val gradePercent: Double,
        val crr: Double,
        val cw: Double,
        val gearRatio: Double?,
        val bicycleMassKg: Double,
        val riderMassKg: Double,
    ) : RideOnControlTarget
}

internal fun encodeRideOnTarget(target: RideOnControlTarget): ByteArray? = runCatching {
    when (target) {
        is RideOnControlTarget.Erg -> {
            require(target.watts in 0..2_000)
            RideOnProtoCodec.hubCommand(targetPowerWatts = target.watts.toLong())
        }
        is RideOnControlTarget.Simulation -> {
            val values = listOf(
                target.windMps, target.gradePercent, target.crr, target.cw,
                target.bicycleMassKg, target.riderMassKg,
            ) + listOfNotNull(target.gearRatio)
            require(values.all { it.isFinite() })
            fun signed(value: Double, scale: Double): Int {
                val scaled = value * scale
                require(scaled in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble())
                return scaled.roundToInt()
            }
            fun unsigned(value: Double, scale: Double): Long {
                val scaled = value * scale
                require(scaled in 0.0..0xFFFF_FFFFL.toDouble())
                return scaled.roundToLong()
            }
            RideOnProtoCodec.hubCommand(
                simulation = RideOnSimulationParam(
                    signed(target.windMps, 100.0),
                    signed(target.gradePercent, 100.0),
                    unsigned(target.cw, 10_000.0),
                    unsigned(target.crr, 100_000.0),
                ),
                physical = RideOnPhysicalParam(
                    target.gearRatio?.let { unsigned(it, 10_000.0) },
                    unsigned(target.bicycleMassKg, 100.0),
                    unsigned(target.riderMassKg, 100.0),
                ),
            )
        }
    }
}.getOrNull()
