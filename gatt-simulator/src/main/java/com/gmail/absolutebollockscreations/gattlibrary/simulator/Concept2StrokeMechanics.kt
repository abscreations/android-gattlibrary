package com.gmail.absolutebollockscreations.gattlibrary.simulator

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** One immutable completed-stroke result, expressed in PM5 wire units. */
internal data class Concept2StrokeMechanics(
    val strokeIndex: Int,
    val strokeRate: Int,
    val powerWatts: Int,
    val driveLengthCentimetres: Int,
    val driveTimeCentiseconds: Int,
    val recoveryTimeCentiseconds: Int,
    val strokeDistanceCentimetres: Int,
    val peakForceTenthsPound: Int,
    val averageForceTenthsPound: Int,
    val workPerStrokeTenthsJoule: Int,
    val dragFactor: Int,
    val forceCurvePounds: List<Int>,
)

/**
 * Deterministic stroke-to-stroke variation. The model chooses timing and technique once, then
 * derives work and force so clients never receive mutually contradictory rowing metrics.
 */
internal object Concept2StrokeMechanicsModel {
    fun generate(
        machine: Concept2Machine,
        strokeIndex: Int,
        displayedStrokeRate: Int,
        displayedPowerWatts: Int,
        speedMetresPerSecond: Double,
    ): Concept2StrokeMechanics {
        val maximumRate = when (machine) {
            Concept2Machine.BIKE_ERG -> 120
            Concept2Machine.ROW_ERG -> 60
            Concept2Machine.SKI_ERG -> 80
        }
        val rate = displayedStrokeRate.coerceIn(1, maximumRate)
        val power = displayedPowerWatts.coerceAtLeast(1)
        val effort = ((power - 80.0) / 320.0).coerceIn(0.0, 1.0)
        val rateEffort = when (machine) {
            Concept2Machine.BIKE_ERG -> ((rate - 65.0) / 40.0).coerceIn(0.0, 1.0)
            Concept2Machine.ROW_ERG -> ((rate - 18.0) / 18.0).coerceIn(0.0, 1.0)
            Concept2Machine.SKI_ERG -> ((rate - 24.0) / 20.0).coerceIn(0.0, 1.0)
        }
        val combinedEffort = 0.7 * effort + 0.3 * rateEffort
        val quickNoise = variation(strokeIndex, 0.73, 1.91)
        val shapeNoise = variation(strokeIndex, 1.37, 0.44)
        val slowNoise = variation(strokeIndex, 0.06, 2.63)

        // Quantize against the rate that is actually placed on the wire.
        val periodCs = (6_000.0 / rate).roundToInt().coerceAtLeast(1)
        val nominalDriveCs = when (machine) {
            Concept2Machine.BIKE_ERG -> 45.0 - 8.0 * combinedEffort
            Concept2Machine.ROW_ERG -> 86.0 - 18.0 * combinedEffort
            Concept2Machine.SKI_ERG -> 78.0 - 16.0 * combinedEffort
        }
        val driveCs = (nominalDriveCs + 2.5 * quickNoise).roundToInt()
            .coerceIn(30, (periodCs - 20).coerceAtLeast(30))
        val recoveryCs = (periodCs - driveCs).coerceAtLeast(20)

        val baseLengthCm = when (machine) {
            Concept2Machine.BIKE_ERG -> 100.0
            Concept2Machine.ROW_ERG -> 112.0
            Concept2Machine.SKI_ERG -> 118.0
        }
        val driveLengthCm = (baseLengthCm + 16.0 * combinedEffort + 2.5 * quickNoise + 1.5 * slowNoise)
            .roundToInt().coerceIn(70, 180)
        val strokeDistanceCm = (speedMetresPerSecond * 6_000.0 / rate)
            .roundToInt().coerceIn(1, 0xFFFF)

        val workJoules = power * 60.0 / rate
        val averageForceNewtons = workJoules / (driveLengthCm / 100.0)
        val averageForcePounds = averageForceNewtons / NEWTONS_PER_POUND_FORCE

        val peakPosition = (0.43 + 0.055 * shapeNoise + 0.025 * combinedEffort).coerceIn(0.34, 0.56)
        val concentration = 3.3 + 0.5 * combinedEffort
        val alpha = (peakPosition * concentration).coerceAtLeast(0.65)
        val beta = ((1.0 - peakPosition) * concentration).coerceAtLeast(0.65)
        val normalized = (0 until FORCE_CURVE_POINT_COUNT).map { index ->
            if (index == 0 || index == FORCE_CURVE_POINT_COUNT - 1) {
                // Real PM5 curves return to baseline at both stroke boundaries. ErgData also uses
                // these baseline samples to recognize and close the rendered force curve.
                0.0
            } else {
                val x = (index + 0.5) / FORCE_CURVE_POINT_COUNT
                x.pow(alpha) * (1.0 - x).pow(beta)
            }
        }
        val normalizedMean = normalized.average().coerceAtLeast(0.0001)
        val curve = normalized.map { value ->
            if (value == 0.0) {
                0
            } else {
                (averageForcePounds * value / normalizedMean).roundToInt().coerceIn(1, 0xFFFF)
            }
        }
        return Concept2StrokeMechanics(
            strokeIndex = strokeIndex,
            strokeRate = rate,
            powerWatts = power,
            driveLengthCentimetres = driveLengthCm,
            driveTimeCentiseconds = driveCs,
            recoveryTimeCentiseconds = recoveryCs,
            strokeDistanceCentimetres = strokeDistanceCm,
            peakForceTenthsPound = curve.maxOrNull()!!.times(10).coerceAtMost(0xFFFF),
            averageForceTenthsPound = (averageForcePounds * 10.0).roundToInt().coerceIn(1, 0xFFFF),
            workPerStrokeTenthsJoule = (workJoules * 10.0).roundToInt().coerceIn(1, 0xFFFF),
            dragFactor = ((if (machine == Concept2Machine.BIKE_ERG) 100 else 120) + 2.0 * slowNoise)
                .roundToInt().coerceIn(80, 150),
            forceCurvePounds = curve,
        )
    }

    private fun variation(index: Int, frequency: Double, phase: Double): Double =
        0.62 * sin(index * frequency + phase) + 0.38 * sin(index * frequency * 0.37 + phase * PI)

    private const val FORCE_CURVE_POINT_COUNT = 24
    private const val NEWTONS_PER_POUND_FORCE = 4.448_221_615_3
}
