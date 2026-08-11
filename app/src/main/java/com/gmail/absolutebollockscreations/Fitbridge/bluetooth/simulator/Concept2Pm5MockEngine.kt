package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

enum class Concept2Scenario { STEADY, INCREASING, RECOVERY, INTERVALS, FINISH }

internal object Concept2Pm5OperationalState {
    const val READY = 1
    const val WORKOUT = 2
}

/** One coherent, protocol-rich PM5 workout snapshot. Values are in documented wire units. */
data class Concept2Pm5Snapshot(
    val elapsedCentiseconds: Int,
    val distanceDecimetres: Int,
    val speedMillimetresPerSecond: Int,
    val strokeRate: Int,
    val heartRate: Int,
    val currentPaceCentiseconds: Int,
    val averagePaceCentiseconds: Int,
    val currentPowerWatts: Int,
    val averagePowerWatts: Int,
    val totalCalories: Int,
    val caloriesPerHour: Int,
    val dragFactor: Int,
    val strokeCount: Int,
    val workoutType: Int = 0,
    val intervalType: Int = 0,
    val workoutState: Int = 1,
    val rowingState: Int = 1,
    val strokeState: Int = 4,
    val intervalCount: Int = 1,
    val driveLengthCentimetres: Int = 120,
    val driveTimeCentiseconds: Int = 75,
    val recoveryTimeCentiseconds: Int = 145,
    val strokeDistanceCentimetres: Int = 850,
    val peakForceTenthsPound: Int = 920,
    val averageForceTenthsPound: Int = 610,
    val workPerStrokeTenthsJoule: Int = 420,
    val restDistanceMetres: Int = 0,
    val restTimeCentiseconds: Int = 0,
    val projectedWorkTimeSeconds: Int = 1800,
    val projectedWorkDistanceMetres: Int = 7000,
    val operationalState: Int = Concept2Pm5OperationalState.WORKOUT,
    val workoutVerificationState: Int = 0,
    val screenNumber: Int = 0,
    val lastError: Int = 0,
    val calibrationMode: Int = 0,
    val calibrationState: Int = 0,
    val calibrationStatus: Int = 0,
    val gameId: Int = 0,
    val gameScore: Int = 0,
    val machineType: Int,
    val splitSequence: Int = 0,
    val summarySequence: Int = 0,
    val forceCurveSequence: Int = 0,
)

/** Deterministic autonomous PM5 workout; it never accepts a Bluetooth control operation. */
class Concept2Pm5MockEngine(
    private val machine: Concept2Machine,
    var scenario: Concept2Scenario = Concept2Scenario.STEADY,
) {
    private var elapsedSeconds = 0.0
    private var distanceMetres = 0.0
    private var energyKcal = 0.0
    private var powerIntegral = 0.0
    private var strokeCount = 0.0

    fun snapshot(): Concept2Pm5Snapshot = buildSnapshot(0.0)

    fun tick(dtSeconds: Double): Concept2Pm5Snapshot {
        require(dtSeconds >= 0.0)
        return buildSnapshot(dtSeconds)
    }

    private fun buildSnapshot(dt: Double): Concept2Pm5Snapshot {
        val previousElapsed = elapsedSeconds
        elapsedSeconds += dt
        val wave = (sin(elapsedSeconds * 2.0 * PI / 45.0) + 1.0) / 2.0
        val scenarioEffort = when (scenario) {
            Concept2Scenario.STEADY -> 0.45 + wave * 0.15
            Concept2Scenario.INCREASING -> (0.3 + elapsedSeconds / 300.0).coerceAtMost(0.95)
            Concept2Scenario.RECOVERY -> (0.85 - elapsedSeconds / 300.0).coerceAtLeast(0.25)
            Concept2Scenario.INTERVALS -> if ((elapsedSeconds.toInt() / 30) % 2 == 0) 0.82 else 0.28
            Concept2Scenario.FINISH -> if (elapsedSeconds < 60.0) 0.6 else 0.0
        }
        val speed = when (machine) {
            Concept2Machine.BIKE_ERG -> 7.0 + 5.0 * scenarioEffort
            Concept2Machine.ROW_ERG -> 2.7 + 1.7 * scenarioEffort
            Concept2Machine.SKI_ERG -> 2.5 + 1.8 * scenarioEffort
        }
        val rate = when (machine) {
            Concept2Machine.BIKE_ERG -> 68.0 + 32.0 * scenarioEffort
            Concept2Machine.ROW_ERG -> 18.0 + 16.0 * scenarioEffort
            Concept2Machine.SKI_ERG -> 24.0 + 18.0 * scenarioEffort
        }
        val power = 65.0 + 290.0 * scenarioEffort
        distanceMetres += speed * dt
        powerIntegral += power * dt
        energyKcal += power * dt / (0.23 * 4184.0)
        strokeCount += rate * dt / 60.0
        val averagePower = if (elapsedSeconds > 0.0) powerIntegral / elapsedSeconds else power
        val paceCs = (500.0 / speed * 100.0).roundToInt()
        val averageSpeed = if (elapsedSeconds > 0.0) distanceMetres / elapsedSeconds else speed
        val avgPaceCs = (500.0 / averageSpeed * 100.0).roundToInt()
        val cycle = if (rate > 0.0) (elapsedSeconds * rate / 60.0) % 1.0 else 0.0
        val strokeState = when {
            scenario == Concept2Scenario.FINISH && elapsedSeconds >= 60.0 -> 0
            cycle < 0.35 -> 2 // driving
            cycle < 0.45 -> 3 // dwelling
            else -> 4 // recovery
        }
        val finished = scenario == Concept2Scenario.FINISH && elapsedSeconds >= 60.0
        val intervalRest = scenario == Concept2Scenario.INTERVALS &&
            (elapsedSeconds.toInt() / 30) % 2 == 1
        return Concept2Pm5Snapshot(
            elapsedCentiseconds = (elapsedSeconds * 100.0).roundToInt(),
            distanceDecimetres = (distanceMetres * 10.0).roundToInt(),
            speedMillimetresPerSecond = (speed * 1000.0).roundToInt(),
            strokeRate = rate.roundToInt(),
            heartRate = (96.0 + scenarioEffort * 76.0).roundToInt(),
            currentPaceCentiseconds = paceCs,
            averagePaceCentiseconds = avgPaceCs,
            currentPowerWatts = power.roundToInt(),
            averagePowerWatts = averagePower.roundToInt(),
            totalCalories = energyKcal.roundToInt(),
            caloriesPerHour = (power / (0.23 * 4184.0) * 3600.0).roundToInt(),
            dragFactor = if (machine == Concept2Machine.BIKE_ERG) 100 else 120,
            strokeCount = strokeCount.toInt(),
            workoutState = if (finished) 10 else if (intervalRest) 8 else 1,
            rowingState = if (finished) 0 else if (intervalRest) 2 else 1,
            strokeState = strokeState,
            intervalType = if (intervalRest) 3 else 1,
            intervalCount = if (scenario == Concept2Scenario.INTERVALS) elapsedSeconds.toInt() / 60 + 1 else 1,
            restTimeCentiseconds = if (intervalRest) ((elapsedSeconds.toInt() % 30) * 100) else 0,
            projectedWorkTimeSeconds = 1800,
            projectedWorkDistanceMetres = (averageSpeed * 1800.0).roundToInt(),
            // The autonomous simulator is already exercising when a client connects. CSAFE is
            // not required to enter WORKOUT; FINISH returns the virtual monitor to READY.
            operationalState = if (finished) {
                Concept2Pm5OperationalState.READY
            } else {
                Concept2Pm5OperationalState.WORKOUT
            },
            machineType = machine.wireMachineType,
            splitSequence = if (scenario == Concept2Scenario.INTERVALS) elapsedSeconds.toInt() / 30 else 0,
            summarySequence = if (finished) 1 else 0,
            forceCurveSequence = strokeCount.toInt(),
        )
    }
}
