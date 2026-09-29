package com.gmail.absolutebollockscreations.gattlibrary.simulator

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sin

enum class Concept2Scenario {
    STEADY,
    INCREASING,
    RECOVERY,
    INTERVALS,
    UNDEFINED_REST_INTERVALS,
    FINISH,
}

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
    val splitIntervalSize: Int = 7000,
    /** Raw programmed target: centiseconds, metres, or calories according to workoutDurationType. */
    val workoutDuration: Int = 180_000,
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
    /** Force samples in whole pounds, held stable for the completed stroke. */
    val forceCurvePounds: List<Int> = emptyList(),
    /** False when stroke-detail and force-curve characteristics must remain silent. */
    val strokeDataAvailable: Boolean = true,
)

/** Deterministic autonomous PM5 workout; it never accepts a Bluetooth control operation. */
internal class Concept2Pm5MockEngine(
    private val machine: Concept2Machine,
    var scenario: Concept2Scenario = Concept2Scenario.STEADY,
    startsPaused: Boolean = false,
) {
    private var elapsedSeconds = 0.0
    private var distanceMetres = 0.0
    private var energyKcal = 0.0
    private var powerIntegral = 0.0
    private var cadenceIntegral = 0.0
    private var strokeCount = 0.0
    private var strokeMechanics: Concept2StrokeMechanics? = null
    private var subscribedIdleSeconds = 0.0
    private var workoutProgram: Concept2WorkoutProgram? = null
    @Volatile private var programPhase = Concept2ProgramPhase.ACTIVE
    private var intervalNumber = 1
    private var intervalStartDistanceMetres = 0.0
    private var intervalStartCalories = 0.0
    private var intervalWorkSeconds = 0.0
    private var restRemainingSeconds = 0.0
    private var restIsUndefined = false
    private var autonomousIntervalNumber = 1
    private var autonomousRestRemainingSeconds: Double? = null
    @Volatile
    var isWorkoutRunning: Boolean = !startsPaused
        private set
    val isWorkoutComplete: Boolean get() = programPhase == Concept2ProgramPhase.COMPLETE

    internal var startupPhase: Concept2Pm5StartupPhase = Concept2Pm5StartupPhase.IDLE
        private set

    /** Start the first pull, or resume model advancement without resetting accumulated metrics. */
    @Synchronized
    fun startWorkout(): Boolean {
        if (programPhase == Concept2ProgramPhase.COMPLETE) return false
        val changed = !isWorkoutRunning
        isWorkoutRunning = true
        beginFirstPull()
        return changed
    }

    /** Freeze the workout model while the peripheral, subscriptions and CSAFE session stay alive. */
    @Synchronized
    fun pauseWorkout(): Boolean {
        val changed = isWorkoutRunning
        isWorkoutRunning = false
        return changed
    }

    /** End the current workout while retaining totals for PM summary notifications. */
    @Synchronized
    fun endWorkout(): Boolean {
        if (programPhase == Concept2ProgramPhase.COMPLETE) return false
        programPhase = Concept2ProgramPhase.COMPLETE
        startupPhase = Concept2Pm5StartupPhase.ACTIVE
        isWorkoutRunning = false
        return true
    }

    /** Clear the workout and return to a paused wireless-idle screen for fresh CSAFE setup. */
    @Synchronized
    fun resetWorkout() {
        elapsedSeconds = 0.0
        distanceMetres = 0.0
        energyKcal = 0.0
        powerIntegral = 0.0
        cadenceIntegral = 0.0
        strokeCount = 0.0
        strokeMechanics = null
        subscribedIdleSeconds = 0.0
        workoutProgram = null
        programPhase = Concept2ProgramPhase.ACTIVE
        intervalNumber = 1
        intervalStartDistanceMetres = 0.0
        intervalStartCalories = 0.0
        intervalWorkSeconds = 0.0
        restRemainingSeconds = 0.0
        restIsUndefined = false
        autonomousIntervalNumber = 1
        autonomousRestRemainingSeconds = null
        startupPhase = Concept2Pm5StartupPhase.IDLE
        isWorkoutRunning = false
    }

    /** Install an accepted CSAFE program without starting physical motion. */
    @Synchronized
    fun configureWorkout(program: Concept2WorkoutProgram) {
        resetWorkout()
        workoutProgram = program
    }

    @Synchronized
    fun programProgress(): Concept2ProgramProgress? {
        val program = workoutProgram ?: return null
        val remaining = when (programPhase) {
            Concept2ProgramPhase.REST -> if (restIsUndefined) -1 else ceil(restRemainingSeconds).toInt().coerceAtLeast(0)
            Concept2ProgramPhase.COMPLETE -> 0
            Concept2ProgramPhase.ACTIVE -> goalRemaining(program)
        }
        return Concept2ProgramProgress(program, currentProgramInterval(program), programPhase, remaining, intervalNumber)
    }

    private fun beginFirstPull(): Boolean {
        if (startupPhase != Concept2Pm5StartupPhase.IDLE) return false
        startupPhase = Concept2Pm5StartupPhase.SUBSCRIBED_IDLE
        return true
    }

    @Synchronized
    fun snapshot(): Concept2Pm5Snapshot = when (startupPhase) {
        Concept2Pm5StartupPhase.IDLE -> idleSnapshot()
        Concept2Pm5StartupPhase.SUBSCRIBED_IDLE -> idleSnapshot()
        Concept2Pm5StartupPhase.WAITING_FOR_ACCELERATION -> preparationSnapshot(strokeState = 1)
        Concept2Pm5StartupPhase.ACCELERATING -> preparationSnapshot(strokeState = 2)
        Concept2Pm5StartupPhase.ACTIVE -> if (isAutonomousUndefinedRest()) {
            restSnapshot(autonomousIntervalNumber)
        } else {
            when (programPhase) {
                Concept2ProgramPhase.ACTIVE -> buildActiveSnapshot(0.0)
                Concept2ProgramPhase.REST -> restSnapshot()
                Concept2ProgramPhase.COMPLETE -> completedSnapshot()
            }
        }
    }

    /**
     * Snapshot exposed over BLE. A pause preserves cumulative workout state internally, while
     * presenting a motionless PM and withholding stroke-detail channels until motion resumes.
     */
    @Synchronized
    fun wireSnapshot(): Concept2Pm5Snapshot {
        val current = snapshot()
        if (isWorkoutRunning || startupPhase != Concept2Pm5StartupPhase.ACTIVE) return current
        return current.copy(
            speedMillimetresPerSecond = 0,
            strokeRate = 0,
            heartRate = 255,
            currentPaceCentiseconds = 0,
            averagePaceCentiseconds = 0,
            currentPowerWatts = 0,
            averagePowerWatts = 0,
            caloriesPerHour = 0,
            dragFactor = 0,
            averageCadence = 0.0,
            rowingState = 0,
            strokeState = 0,
            driveLengthCentimetres = 0,
            driveTimeCentiseconds = 0,
            recoveryTimeCentiseconds = 0,
            strokeDistanceCentimetres = 0,
            peakForceTenthsPound = 0,
            averageForceTenthsPound = 0,
            workPerStrokeTenthsJoule = 0,
            operationalState = Concept2Pm5OperationalState.READY,
            strokeDataAvailable = false,
        )
    }

    @Synchronized
    fun tick(dtSeconds: Double): Concept2Pm5Snapshot {
        require(dtSeconds >= 0.0)
        if (!isWorkoutRunning) return snapshot()
        if (startupPhase == Concept2Pm5StartupPhase.IDLE) return idleSnapshot()

        if (startupPhase == Concept2Pm5StartupPhase.ACTIVE && programPhase == Concept2ProgramPhase.REST) {
            restRemainingSeconds = (restRemainingSeconds - dtSeconds).coerceAtLeast(0.0)
            if (restRemainingSeconds == 0.0) {
                advanceToNextVariableInterval()
            }
            return if (programPhase == Concept2ProgramPhase.REST) restSnapshot() else buildActiveSnapshot(0.0)
        }
        if (startupPhase == Concept2Pm5StartupPhase.ACTIVE && isAutonomousUndefinedRest()) {
            val remaining = (autonomousRestRemainingSeconds!! - dtSeconds).coerceAtLeast(0.0)
            autonomousRestRemainingSeconds = remaining.takeIf { it > 0.0 }
            if (remaining > 0.0) return restSnapshot(autonomousIntervalNumber)
            autonomousIntervalNumber++
            return buildActiveSnapshot(0.0)
        }

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
                val active = buildActiveSnapshot(activeAfter - activeBefore)
                if (workoutProgram != null) applyProgramBoundary(active)
                else applyAutonomousUndefinedRestBoundary(active)
            }
        }
    }

    private fun buildActiveSnapshot(dt: Double): Concept2Pm5Snapshot {
        val activeElapsedSeconds = (elapsedSeconds - FIRST_PULL_PREPARATION_SECONDS).coerceAtLeast(0.0)
        val wave = (sin(activeElapsedSeconds * 2.0 * PI / 45.0) + 1.0) / 2.0
        val interval = workoutProgram?.let(::currentProgramInterval)
        val scenarioEffort = if (interval != null) 0.55 else when (scenario) {
            Concept2Scenario.STEADY -> 0.45 + wave * 0.15
            Concept2Scenario.INCREASING -> (0.3 + activeElapsedSeconds / 300.0).coerceAtMost(0.95)
            Concept2Scenario.RECOVERY -> (0.85 - activeElapsedSeconds / 300.0).coerceAtLeast(0.25)
            Concept2Scenario.INTERVALS -> if ((activeElapsedSeconds.toInt() / 30) % 2 == 0) 0.82 else 0.28
            Concept2Scenario.UNDEFINED_REST_INTERVALS -> 0.88
            Concept2Scenario.FINISH -> if (activeElapsedSeconds < 60.0) 0.6 else 0.0
        }
        val generatedSpeed = when (machine) {
            Concept2Machine.BIKE_ERG -> 7.0 + 5.0 * scenarioEffort
            Concept2Machine.ROW_ERG -> 2.7 + 1.7 * scenarioEffort
            Concept2Machine.SKI_ERG -> 2.5 + 1.8 * scenarioEffort
        }
        val speed = interval?.targetPaceCentiseconds?.let { 50_000.0 / it } ?: generatedSpeed
        val baseRate = when (machine) {
            Concept2Machine.BIKE_ERG -> 68.0 + 32.0 * scenarioEffort
            Concept2Machine.ROW_ERG -> 18.0 + 16.0 * scenarioEffort
            Concept2Machine.SKI_ERG -> 24.0 + 18.0 * scenarioEffort
        }
        val highRateUndefinedRestProfile = scenario == Concept2Scenario.UNDEFINED_REST_INTERVALS ||
            (workoutProgram != null && interval?.restSeconds == null)
        val rate = interval?.targetStrokeRate?.let { target ->
            target * (1.0 + 0.08 * sin(activeElapsedSeconds * 2.0 * PI / 12.0))
        } ?: if (highRateUndefinedRestProfile) {
            45.0 + 5.0 * sin(activeElapsedSeconds * 2.0 * PI / 12.0)
        } else {
            baseRate
        }
        val generatedPower = 65.0 + 290.0 * scenarioEffort
        val power = interval?.targetPowerWatts?.toDouble()
            ?: interval?.targetCaloriesPerHour?.let { it * 0.23 * 4184.0 / 3600.0 }
            ?: generatedPower
        distanceMetres += speed * dt
        powerIntegral += power * dt
        cadenceIntegral += rate * dt
        energyKcal += power * dt / (0.23 * 4184.0)
        strokeCount += rate * dt / 60.0
        val displayedRate = rate.roundToInt().coerceAtLeast(1)
        val displayedPower = power.roundToInt().coerceAtLeast(1)
        val completedStrokeIndex = strokeCount.toInt()
        val mechanics = strokeMechanics
            ?.takeIf { it.strokeIndex == completedStrokeIndex }
            ?: Concept2StrokeMechanicsModel.generate(
                machine = machine,
                strokeIndex = completedStrokeIndex,
                displayedStrokeRate = displayedRate,
                displayedPowerWatts = displayedPower,
                speedMetresPerSecond = speed,
            ).also { strokeMechanics = it }
        if (workoutProgram != null) intervalWorkSeconds += dt
        val averagePower = if (activeElapsedSeconds > 0.0) powerIntegral / activeElapsedSeconds else power
        val paceCs = (500.0 / speed * 100.0).roundToInt()
        val averageSpeed = if (activeElapsedSeconds > 0.0) distanceMetres / activeElapsedSeconds else speed
        val avgPaceCs = (500.0 / averageSpeed * 100.0).roundToInt()
        val cycle = strokeCount % 1.0
        val driveFraction = mechanics.driveTimeCentiseconds.toDouble() /
            (mechanics.driveTimeCentiseconds + mechanics.recoveryTimeCentiseconds)
        val strokeState = when {
            workoutProgram == null && scenario == Concept2Scenario.FINISH && elapsedSeconds >= 60.0 -> 0
            cycle < driveFraction -> 2 // driving
            cycle < (driveFraction + 0.05).coerceAtMost(0.95) -> 3 // dwelling
            else -> 4 // recovery
        }
        val finished = workoutProgram == null && scenario == Concept2Scenario.FINISH && activeElapsedSeconds >= 60.0
        val intervalRest = workoutProgram == null && scenario == Concept2Scenario.INTERVALS &&
            (activeElapsedSeconds.toInt() / 30) % 2 == 1
        val isBikeErg = machine == Concept2Machine.BIKE_ERG
        return Concept2Pm5Snapshot(
            elapsedCentiseconds = (elapsedSeconds * 100.0).roundToInt(),
            distanceDecimetres = (distanceMetres * 10.0).roundToInt(),
            speedMillimetresPerSecond = (speed * 1000.0).roundToInt(),
            strokeRate = mechanics.strokeRate,
            heartRate = interval?.targetHeartRateBpm?.let { target ->
                (target * (1.0 + 0.08 * sin(activeElapsedSeconds * 2.0 * PI / 17.0))).roundToInt()
            } ?: (96.0 + scenarioEffort * 76.0).roundToInt(),
            currentPaceCentiseconds = paceCs,
            averagePaceCentiseconds = avgPaceCs,
            currentPowerWatts = mechanics.powerWatts,
            averagePowerWatts = averagePower.roundToInt(),
            totalCalories = energyKcal.roundToInt(),
            caloriesPerHour = (mechanics.powerWatts / (0.23 * 4184.0) * 3600.0).roundToInt(),
            dragFactor = mechanics.dragFactor,
            strokeCount = strokeCount.toInt(),
            averageCadence = if (activeElapsedSeconds > 0.0) cadenceIntegral / activeElapsedSeconds else rate,
            workoutType = workoutProgram?.workoutType ?: 0,
            workoutState = when {
                finished -> 10
                workoutProgram?.let { it.isInterval || it.isVariableInterval } == true ->
                    if (interval?.goalKind == Concept2WorkoutGoalKind.DISTANCE) 5 else 4
                intervalRest -> 8
                else -> 1
            },
            rowingState = if (finished || intervalRest) 0 else 1,
            strokeState = strokeState,
            // The physical BikeErg multiplex corpus retains 0x35/0x36 but reports its
            // rower-specific drive/recovery/distance/force fields as zero. Keep the records and
            // their bike cadence/power/count data without fabricating handle mechanics.
            driveLengthCentimetres = if (isBikeErg) 0 else mechanics.driveLengthCentimetres,
            driveTimeCentiseconds = if (isBikeErg) 0 else mechanics.driveTimeCentiseconds,
            recoveryTimeCentiseconds = if (isBikeErg) 0 else mechanics.recoveryTimeCentiseconds,
            strokeDistanceCentimetres = if (isBikeErg) 0 else mechanics.strokeDistanceCentimetres,
            peakForceTenthsPound = if (isBikeErg) 0 else mechanics.peakForceTenthsPound,
            averageForceTenthsPound = if (isBikeErg) 0 else mechanics.averageForceTenthsPound,
            workPerStrokeTenthsJoule = mechanics.workPerStrokeTenthsJoule,
            intervalType = interval?.let { programIntervalType(it.goalKind) } ?: if (intervalRest) 3 else 1,
            intervalCount = workoutProgram?.let { intervalNumber }
                ?: when (scenario) {
                    Concept2Scenario.INTERVALS -> activeElapsedSeconds.toInt() / 60 + 1
                    Concept2Scenario.UNDEFINED_REST_INTERVALS -> autonomousIntervalNumber
                    else -> 1
                },
            restTimeCentiseconds = if (intervalRest) ((activeElapsedSeconds.toInt() % 30) * 100) else 0,
            projectedWorkTimeSeconds = interval?.takeIf { it.goalKind == Concept2WorkoutGoalKind.TIME }
                ?.let { it.goalValue / 100 } ?: 1800,
            projectedWorkDistanceMetres = interval?.takeIf { it.goalKind == Concept2WorkoutGoalKind.DISTANCE }
                ?.goalValue ?: (averageSpeed * 1800.0).roundToInt(),
            splitIntervalSize = workoutProgram?.let { program ->
                program.splitGoalValue ?: if (program.isInterval || program.isVariableInterval) {
                    interval?.goalValue
                } else {
                    null
                }
            } ?: 7000,
            workoutDuration = interval?.goalValue ?: 180_000,
            workoutDurationType = interval?.let { durationType(it.goalKind) } ?: 0,
            operationalState = if (finished) Concept2Pm5OperationalState.READY else Concept2Pm5OperationalState.WORKOUT,
            machineType = machine.wireMachineType,
            splitSequence = workoutProgram?.let(::programmedSplitSequence)
                ?: when (scenario) {
                    Concept2Scenario.INTERVALS -> activeElapsedSeconds.toInt() / 30
                    Concept2Scenario.UNDEFINED_REST_INTERVALS -> autonomousIntervalNumber - 1
                    else -> 0
                },
            summarySequence = if (finished) 1 else 0,
            forceCurveSequence = strokeCount.toInt(),
            forceCurvePounds = if (isBikeErg) emptyList() else mechanics.forceCurvePounds,
        )
    }

    private fun applyProgramBoundary(active: Concept2Pm5Snapshot): Concept2Pm5Snapshot {
        val program = workoutProgram ?: return active
        if (!goalReached(program)) return active
        if (program.isInterval || (program.isVariableInterval && intervalNumber < program.intervals.size)) {
            programPhase = Concept2ProgramPhase.REST
            val rest = currentProgramInterval(program).restSeconds
            restIsUndefined = rest == null
            restRemainingSeconds = rest?.toDouble()
                ?: simulatedUndefinedRestDurationSeconds(intervalNumber).toDouble()
            return restSnapshot(intervalNumber)
        }
        programPhase = Concept2ProgramPhase.COMPLETE
        isWorkoutRunning = false
        return completedSnapshot()
    }

    private fun applyAutonomousUndefinedRestBoundary(active: Concept2Pm5Snapshot): Concept2Pm5Snapshot {
        if (scenario != Concept2Scenario.UNDEFINED_REST_INTERVALS) return active
        val activeElapsedSeconds = (elapsedSeconds - FIRST_PULL_PREPARATION_SECONDS).coerceAtLeast(0.0)
        if (activeElapsedSeconds < autonomousIntervalNumber * AUTONOMOUS_INTERVAL_WORK_SECONDS) return active
        autonomousRestRemainingSeconds = simulatedUndefinedRestDurationSeconds(autonomousIntervalNumber).toDouble()
        return restSnapshot(autonomousIntervalNumber)
    }

    private fun isAutonomousUndefinedRest(): Boolean =
        workoutProgram == null && scenario == Concept2Scenario.UNDEFINED_REST_INTERVALS &&
            autonomousRestRemainingSeconds != null

    private fun simulatedUndefinedRestDurationSeconds(interval: Int): Int =
        AUTONOMOUS_REST_MIN_SECONDS + ((interval * 17 + 11) % AUTONOMOUS_REST_VARIATION_SECONDS)

    private fun goalReached(program: Concept2WorkoutProgram): Boolean = when (currentProgramInterval(program).goalKind) {
        Concept2WorkoutGoalKind.TIME ->
            (intervalWorkSeconds * 100.0).roundToInt() >= currentProgramInterval(program).goalValue
        Concept2WorkoutGoalKind.DISTANCE ->
            distanceMetres - intervalStartDistanceMetres >= currentProgramInterval(program).goalValue
        Concept2WorkoutGoalKind.CALORIES -> energyKcal - intervalStartCalories >= currentProgramInterval(program).goalValue
    }

    private fun goalRemaining(program: Concept2WorkoutProgram): Int {
        val interval = currentProgramInterval(program)
        return when (interval.goalKind) {
        Concept2WorkoutGoalKind.TIME -> ceil(
            (interval.goalValue / 100.0) - intervalWorkSeconds,
        ).toInt().coerceAtLeast(0)
        Concept2WorkoutGoalKind.DISTANCE -> ceil(
            interval.goalValue - (distanceMetres - intervalStartDistanceMetres),
        ).toInt().coerceAtLeast(0)
        Concept2WorkoutGoalKind.CALORIES -> ceil(
            interval.goalValue - (energyKcal - intervalStartCalories),
        ).toInt().coerceAtLeast(0)
        }
    }

    private fun programmedSplitSequence(program: Concept2WorkoutProgram): Int {
        if (program.isInterval || program.isVariableInterval) return 0
        val splitKind = program.splitGoalKind ?: return 0
        val splitValue = program.splitGoalValue?.takeIf { it > 0 } ?: return 0
        if (splitKind != currentProgramInterval(program).goalKind) return 0
        val progress = when (splitKind) {
            Concept2WorkoutGoalKind.TIME -> (intervalWorkSeconds * 100.0).toInt()
            Concept2WorkoutGoalKind.DISTANCE ->
                (distanceMetres - intervalStartDistanceMetres).toInt()
            Concept2WorkoutGoalKind.CALORIES ->
                (energyKcal - intervalStartCalories).toInt()
        }
        return (progress / splitValue).coerceAtLeast(0)
    }

    private fun restSnapshot(splitSequence: Int = intervalNumber): Concept2Pm5Snapshot =
        motionlessSnapshot(
            workoutState = 3,
            intervalType = 2,
            operationalState = Concept2Pm5OperationalState.WORKOUT,
            splitSequence = splitSequence,
        )

    private fun completedSnapshot(): Concept2Pm5Snapshot = motionlessSnapshot(
        workoutState = 10,
        intervalType = workoutProgram?.let { programIntervalType(currentProgramInterval(it).goalKind) } ?: 1,
        operationalState = Concept2Pm5OperationalState.READY,
        summarySequence = 1,
    )

    private fun motionlessSnapshot(
        workoutState: Int,
        intervalType: Int,
        operationalState: Int,
        splitSequence: Int = 0,
        summarySequence: Int = 0,
    ): Concept2Pm5Snapshot = buildActiveSnapshot(0.0).copy(
        speedMillimetresPerSecond = 0,
        strokeRate = 0,
        heartRate = 255,
        currentPaceCentiseconds = 0,
        currentPowerWatts = 0,
        caloriesPerHour = 0,
        dragFactor = 0,
        workoutState = workoutState,
        rowingState = 0,
        strokeState = 0,
        intervalType = intervalType,
        driveLengthCentimetres = 0,
        driveTimeCentiseconds = 0,
        recoveryTimeCentiseconds = 0,
        strokeDistanceCentimetres = 0,
        peakForceTenthsPound = 0,
        averageForceTenthsPound = 0,
        workPerStrokeTenthsJoule = 0,
        operationalState = operationalState,
        splitSequence = splitSequence,
        summarySequence = summarySequence,
        strokeDataAvailable = false,
        forceCurvePounds = emptyList(),
    )

    private fun programIntervalType(kind: Concept2WorkoutGoalKind): Int = when (kind) {
        Concept2WorkoutGoalKind.TIME -> 0
        Concept2WorkoutGoalKind.DISTANCE -> 1
        Concept2WorkoutGoalKind.CALORIES -> 6
    }

    private fun currentProgramInterval(program: Concept2WorkoutProgram): Concept2WorkoutInterval =
        program.intervals[(intervalNumber - 1).coerceIn(program.intervals.indices)]

    private fun advanceToNextVariableInterval() {
        programPhase = Concept2ProgramPhase.ACTIVE
        intervalNumber++
        intervalStartDistanceMetres = distanceMetres
        intervalStartCalories = energyKcal
        intervalWorkSeconds = 0.0
        restIsUndefined = false
        strokeMechanics = null
    }

    private fun durationType(kind: Concept2WorkoutGoalKind): Int = when (kind) {
        Concept2WorkoutGoalKind.TIME -> 0x00
        Concept2WorkoutGoalKind.CALORIES -> 0x40
        Concept2WorkoutGoalKind.DISTANCE -> 0x80
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
        splitIntervalSize = 0,
        workoutDuration = 0,
        workoutDurationType = workoutDurationType,
        operationalState = operationalState,
        machineType = machine.wireMachineType,
        driveLengthCentimetres = 0,
        driveTimeCentiseconds = 0,
        recoveryTimeCentiseconds = 0,
        strokeDistanceCentimetres = 0,
        peakForceTenthsPound = 0,
        averageForceTenthsPound = 0,
        workPerStrokeTenthsJoule = 0,
        strokeDataAvailable = false,
    )

    companion object {
        private const val SUBSCRIBED_IDLE_SECONDS = 2.0
        private const val WAITING_FOR_ACCELERATION_SECONDS = 0.5
        private const val FIRST_PULL_PREPARATION_SECONDS = 1.0
        private const val AUTONOMOUS_INTERVAL_WORK_SECONDS = 30.0
        private const val AUTONOMOUS_REST_MIN_SECONDS = 30
        private const val AUTONOMOUS_REST_VARIATION_SECONDS = 31
    }
}
