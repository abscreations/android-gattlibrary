package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

enum class Concept2Scenario { STEADY, INCREASING, RECOVERY, INTERVALS, FINISH }

internal object Concept2Pm5OperationalState {
    const val READY = 1
    const val WORKOUT = 2
    const val IDLE = 10
}

internal enum class Concept2Pm5StartupPhase {
    IDLE,
    SUBSCRIBED_IDLE,
    WAITING_FOR_ACCELERATION,
    ACCELERATING,
    ACTIVE,
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
    val averageCadence: Double = strokeRate.toDouble(),
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
    val workoutDurationType: Int = 0,
    val operationalState: Int = Concept2Pm5OperationalState.IDLE,
    val workoutVerificationState: Int = 0,
    val screenNumber: Int = 1,
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
    private var cadenceIntegral = 0.0
    private var strokeCount = 0.0
    private var subscribedIdleSeconds = 0.0

    internal var startupPhase: Concept2Pm5StartupPhase = Concept2Pm5StartupPhase.IDLE
        private set

    /** A PM5 remains on its wireless/idle screen until a workout data consumer is present. */
    fun beginFirstPull(): Boolean {
        if (startupPhase != Concept2Pm5StartupPhase.IDLE) return false
        startupPhase = Concept2Pm5StartupPhase.SUBSCRIBED_IDLE
        return true
    }

    fun snapshot(): Concept2Pm5Snapshot = when (startupPhase) {
        Concept2Pm5StartupPhase.IDLE -> idleSnapshot()
        Concept2Pm5StartupPhase.SUBSCRIBED_IDLE -> idleSnapshot()
        Concept2Pm5StartupPhase.WAITING_FOR_ACCELERATION -> preparationSnapshot(strokeState = 1)
        Concept2Pm5StartupPhase.ACCELERATING -> preparationSnapshot(strokeState = 2)
        Concept2Pm5StartupPhase.ACTIVE -> buildActiveSnapshot(0.0)
    }

    fun tick(dtSeconds: Double): Concept2Pm5Snapshot {
        require(dtSeconds >= 0.0)
        if (startupPhase == Concept2Pm5StartupPhase.IDLE) return idleSnapshot()

        var transitionDtSeconds = dtSeconds
        if (startupPhase == Concept2Pm5StartupPhase.SUBSCRIBED_IDLE) {
            val previousSubscribedIdle = subscribedIdleSeconds
            subscribedIdleSeconds += dtSeconds
            if (subscribedIdleSeconds <= SUBSCRIBED_IDLE_SECONDS) return idleSnapshot()
            transitionDtSeconds =
                (subscribedIdleSeconds - SUBSCRIBED_IDLE_SECONDS) -
                (previousSubscribedIdle - SUBSCRIBED_IDLE_SECONDS).coerceAtLeast(0.0)
        }

        val previousElapsed = elapsedSeconds
        elapsedSeconds += transitionDtSeconds
        val activeBefore = (previousElapsed - FIRST_PULL_PREPARATION_SECONDS).coerceAtLeast(0.0)
        val activeAfter = (elapsedSeconds - FIRST_PULL_PREPARATION_SECONDS).coerceAtLeast(0.0)
        return when {
            elapsedSeconds <= WAITING_FOR_ACCELERATION_SECONDS -> {
                startupPhase = Concept2Pm5StartupPhase.WAITING_FOR_ACCELERATION
                preparationSnapshot(strokeState = 1)
            }
            elapsedSeconds <= FIRST_PULL_PREPARATION_SECONDS -> {
                startupPhase = Concept2Pm5StartupPhase.ACCELERATING
                preparationSnapshot(strokeState = 2)
            }
            else -> {
                startupPhase = Concept2Pm5StartupPhase.ACTIVE
                buildActiveSnapshot(activeAfter - activeBefore)
            }
        }
    }

    fun tick(dtSeconds: Double, workoutRequested: Boolean): Concept2Pm5Snapshot {
        if (workoutRequested) beginFirstPull()
        return tick(dtSeconds)
    }

    private fun buildActiveSnapshot(dt: Double): Concept2Pm5Snapshot {
        val activeElapsedSeconds = (elapsedSeconds - FIRST_PULL_PREPARATION_SECONDS).coerceAtLeast(0.0)
        val wave = (sin(activeElapsedSeconds * 2.0 * PI / 45.0) + 1.0) / 2.0
        val scenarioEffort = when (scenario) {
            Concept2Scenario.STEADY -> 0.45 + wave * 0.15
            Concept2Scenario.INCREASING -> (0.3 + activeElapsedSeconds / 300.0).coerceAtMost(0.95)
            Concept2Scenario.RECOVERY -> (0.85 - activeElapsedSeconds / 300.0).coerceAtLeast(0.25)
            Concept2Scenario.INTERVALS -> if ((activeElapsedSeconds.toInt() / 30) % 2 == 0) 0.82 else 0.28
            Concept2Scenario.FINISH -> if (activeElapsedSeconds < 60.0) 0.6 else 0.0
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
        cadenceIntegral += rate * dt
        energyKcal += power * dt / (0.23 * 4184.0)
        strokeCount += rate * dt / 60.0
        val averagePower = if (activeElapsedSeconds > 0.0) powerIntegral / activeElapsedSeconds else power
        val paceCs = (500.0 / speed * 100.0).roundToInt()
        val averageSpeed = if (activeElapsedSeconds > 0.0) distanceMetres / activeElapsedSeconds else speed
        val avgPaceCs = (500.0 / averageSpeed * 100.0).roundToInt()
        val cycle = if (rate > 0.0) (activeElapsedSeconds * rate / 60.0) % 1.0 else 0.0
        val strokeState = when {
            scenario == Concept2Scenario.FINISH && elapsedSeconds >= 60.0 -> 0
            cycle < 0.35 -> 2 // driving
            cycle < 0.45 -> 3 // dwelling
            else -> 4 // recovery
        }
        val finished = scenario == Concept2Scenario.FINISH && activeElapsedSeconds >= 60.0
        val intervalRest = scenario == Concept2Scenario.INTERVALS &&
            (activeElapsedSeconds.toInt() / 30) % 2 == 1
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
            averageCadence = if (activeElapsedSeconds > 0.0) cadenceIntegral / activeElapsedSeconds else rate,
            workoutState = if (finished) 10 else if (intervalRest) 8 else 1,
            rowingState = if (finished || intervalRest) 0 else 1,
            strokeState = strokeState,
            intervalType = if (intervalRest) 3 else 1,
            intervalCount = if (scenario == Concept2Scenario.INTERVALS) activeElapsedSeconds.toInt() / 60 + 1 else 1,
            restTimeCentiseconds = if (intervalRest) ((activeElapsedSeconds.toInt() % 30) * 100) else 0,
            projectedWorkTimeSeconds = 1800,
            projectedWorkDistanceMetres = (averageSpeed * 1800.0).roundToInt(),
            operationalState = if (finished) Concept2Pm5OperationalState.READY else Concept2Pm5OperationalState.WORKOUT,
            machineType = machine.wireMachineType,
            splitSequence = if (scenario == Concept2Scenario.INTERVALS) activeElapsedSeconds.toInt() / 30 else 0,
            summarySequence = if (finished) 1 else 0,
            forceCurveSequence = strokeCount.toInt(),
        )
    }

    private fun idleSnapshot() = stoppedSnapshot(
        elapsedCentiseconds = 0,
        workoutType = 0,
        strokeState = 0,
        operationalState = Concept2Pm5OperationalState.IDLE,
        workoutDurationType = 0x80,
    )

    private fun preparationSnapshot(strokeState: Int) = stoppedSnapshot(
        elapsedCentiseconds = (elapsedSeconds * 100.0).roundToInt(),
        workoutType = 1,
        strokeState = strokeState,
        operationalState = Concept2Pm5OperationalState.READY,
        workoutDurationType = 0x80,
    )

    private fun stoppedSnapshot(
        elapsedCentiseconds: Int,
        workoutType: Int,
        strokeState: Int,
        operationalState: Int,
        workoutDurationType: Int,
    ) = Concept2Pm5Snapshot(
        elapsedCentiseconds = elapsedCentiseconds,
        distanceDecimetres = 0,
        speedMillimetresPerSecond = 0,
        strokeRate = 0,
        heartRate = 255,
        currentPaceCentiseconds = 0,
        averagePaceCentiseconds = 0,
        currentPowerWatts = 0,
        averagePowerWatts = 0,
        totalCalories = 0,
        caloriesPerHour = 0,
        dragFactor = 0,
        strokeCount = 0,
        averageCadence = 0.0,
        workoutType = workoutType,
        intervalType = 1,
        workoutState = 0,
        rowingState = 0,
        strokeState = strokeState,
        projectedWorkTimeSeconds = 0,
        projectedWorkDistanceMetres = 0,
        workoutDurationType = workoutDurationType,
        operationalState = operationalState,
        machineType = machine.wireMachineType,
    )

    companion object {
        private const val SUBSCRIBED_IDLE_SECONDS = 2.0
        private const val WAITING_FOR_ACCELERATION_SECONDS = 0.5
        private const val FIRST_PULL_PREPARATION_SECONDS = 1.0
    }
}
