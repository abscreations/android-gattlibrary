package com.gmail.absolutebollockscreations.gattlibrary.simulator

public enum class Concept2WorkoutGoalKind { TIME, DISTANCE, CALORIES }

public data class Concept2WorkoutInterval(
    val goalKind: Concept2WorkoutGoalKind,
    /** Centiseconds for time, metres for distance, whole calories for calories. */
    val goalValue: Int,
    /** Null means the CSAFE undefined-rest variant; zero means no rest. */
    val restSeconds: Int? = 0,
    val targetPaceCentiseconds: Int? = null,
    val targetPowerWatts: Int? = null,
    val targetCaloriesPerHour: Int? = null,
    val targetStrokeRate: Int? = null,
    val targetHeartRateBpm: Int? = null,
)

/** A complete, validated CSAFE workout configuration that can drive the virtual PM. */
public data class Concept2WorkoutProgram(
    val workoutType: Int,
    val goalKind: Concept2WorkoutGoalKind,
    /** Centiseconds for time, metres for distance, whole calories for calories. */
    val goalValue: Int,
    val restSeconds: Int? = null,
    val targetPaceCentiseconds: Int? = null,
    val targetPowerWatts: Int? = null,
    val targetCaloriesPerHour: Int? = null,
    val targetStrokeRate: Int? = null,
    val targetHeartRateBpm: Int? = null,
    val splitGoalKind: Concept2WorkoutGoalKind? = null,
    val splitGoalValue: Int? = null,
    val intervals: List<Concept2WorkoutInterval> = listOf(
        Concept2WorkoutInterval(
            goalKind = goalKind,
            goalValue = goalValue,
            restSeconds = restSeconds,
            targetPaceCentiseconds = targetPaceCentiseconds,
            targetPowerWatts = targetPowerWatts,
            targetCaloriesPerHour = targetCaloriesPerHour,
            targetStrokeRate = targetStrokeRate,
            targetHeartRateBpm = targetHeartRateBpm,
        ),
    ),
) {
    public val isInterval: Boolean get() = workoutType in setOf(6, 7, 12)
    public val isVariableInterval: Boolean get() = workoutType in setOf(8, 9)
}

public enum class Concept2ProgramPhase { ACTIVE, REST, COMPLETE }

public data class Concept2ProgramProgress(
    val program: Concept2WorkoutProgram,
    val currentInterval: Concept2WorkoutInterval,
    val phase: Concept2ProgramPhase,
    val remaining: Int,
    val intervalNumber: Int,
)
