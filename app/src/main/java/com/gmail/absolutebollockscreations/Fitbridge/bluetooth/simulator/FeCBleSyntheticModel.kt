package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

/**
 * Deterministic synthetic workout model (plan §12). Simulation mode shares FitBridge's realistic
 * steady-state cycling road-load calculation; transient behavior remains a **simulator behaviour
 * choice**, and none of it claims a physical Tacx trainer's response curve (plan §1.5, §12.6).
 */
internal object FeCBleSyntheticModel {

    internal data class IntervalSnapshot(
        val name: String,
        val cycleSeconds: Double,
        val secondsRemaining: Int,
        val intensity: Double,
    )

    private data class IntervalStage(
        val name: String,
        val durationSeconds: Int,
        val startingIntensity: Double,
        val endingIntensity: Double,
    )

    /** A short, deterministic workout that exercises changing values without requiring randomness. */
    private val intervalStages = listOf(
        IntervalStage("Warm-up", 20, 0.32, 0.50),
        IntervalStage("Build", 20, 0.50, 0.72),
        IntervalStage("Work", 20, 0.82, 0.94),
        IntervalStage("Recovery", 20, 0.36, 0.30),
        IntervalStage("Tempo", 20, 0.68, 0.78),
        IntervalStage("Sprint", 10, 0.90, 1.00),
        IntervalStage("Easy", 10, 0.38, 0.32),
    )

    val intervalCycleSeconds: Int = intervalStages.sumOf { it.durationSeconds }
    val initialIntervalName: String = intervalStages.first().name
    val initialIntervalSeconds: Int = intervalStages.first().durationSeconds

    /** Model tick period; matches the telemetry scheduler's base cadence (plan §11.1). */
    const val TICK_SECONDS = 0.25

    /** Plan §12.3: first-order power convergence, 25 W per 250 ms tick. */
    const val MAX_POWER_CHANGE_PER_TICK = 25.0

    /** Advance one persona-aware interval tick. No-op outside [FeCEquipmentState.IN_USE]. */
    fun advanceTick(state: FeCBleMachineState) {
        if (state.feState != FeCEquipmentState.IN_USE) return
        state.elapsedQuarterSeconds += 1

        val interval = intervalAt(state.elapsedQuarterSeconds)
        applyAutonomousMetrics(state, interval)

        val currentPower = state.instantaneousPowerWatts
        val simulationPower = if (state.controlMode == FeCBleControlMode.SIMULATION && currentPower != null) {
            simulationPowerAtPublishedSpeed(state)
        } else {
            null
        }
        val target = targetPowerForCurrentMode(state)
        if (simulationPower != null) {
            state.instantaneousPowerWatts = simulationPower.roundToIntSafely()
        } else if (target != null && currentPower != null) {
            val delta = (target - currentPower).coerceIn(-MAX_POWER_CHANGE_PER_TICK, MAX_POWER_CHANGE_PER_TICK)
            state.instantaneousPowerWatts = (currentPower + delta).roundToIntSafely()
        }

        state.distanceMeters += state.speedMps * TICK_SECONDS
        state.accumulatedCaloriesKcal += state.caloricBurnRateKcalPerHour * TICK_SECONDS / 3600.0

        val verticalMeters = state.speedMps * (state.inclinePercent / 100.0) * TICK_SECONDS
        if (verticalMeters >= 0.0) {
            state.positiveVerticalDistanceMeters += verticalMeters
        } else {
            state.negativeVerticalDistanceMeters += -verticalMeters
        }

        when (state.equipmentType) {
            FeCEquipmentType.ELLIPTICAL,
            FeCEquipmentType.ROWER,
            FeCEquipmentType.CLIMBER,
            FeCEquipmentType.NORDIC_SKIER,
            -> state.cadenceRpm?.let { state.movementCount += it * TICK_SECONDS / 60.0 }
            else -> Unit
        }

        if (state.equipmentType == FeCEquipmentType.TRAINER ||
            state.equipmentType == FeCEquipmentType.STATIONARY_BIKE
        ) {
            state.wheelPeriodTicks += WHEEL_PERIOD_TICKS_PER_MODEL_TICK
            state.wheelRevolutions += state.speedMps * TICK_SECONDS / state.cycleLengthMeters
            val completeRevolutions = kotlin.math.floor(state.wheelRevolutions).toLong()
            state.torqueEventCount = completeRevolutions
            state.wheelTicks = completeRevolutions
            state.instantaneousPowerWatts?.let { power ->
                state.accumulatedTorqueThirtySecondNm +=
                    power * WHEEL_PERIOD_TICKS_PER_MODEL_TICK / (128.0 * Math.PI)
            }
        }
    }

    internal fun intervalAt(elapsedQuarterSeconds: Long): IntervalSnapshot {
        val totalQuarters = intervalCycleSeconds * 4L
        val cycleQuarters = elapsedQuarterSeconds.mod(totalQuarters)
        var stageStartQuarters = 0L
        intervalStages.forEach { stage ->
            val durationQuarters = stage.durationSeconds * 4L
            if (cycleQuarters < stageStartQuarters + durationQuarters) {
                val stageQuarter = cycleQuarters - stageStartQuarters
                val progress = stageQuarter.toDouble() / durationQuarters.toDouble()
                val intensity = lerp(stage.startingIntensity, stage.endingIntensity, progress)
                return IntervalSnapshot(
                    name = stage.name,
                    cycleSeconds = cycleQuarters * TICK_SECONDS,
                    secondsRemaining = kotlin.math.ceil((durationQuarters - stageQuarter) * TICK_SECONDS)
                        .toInt(),
                    intensity = intensity,
                )
            }
            stageStartQuarters += durationQuarters
        }
        error("Interval cycle must contain at least one positive-duration stage")
    }

    private fun applyAutonomousMetrics(state: FeCBleMachineState, interval: IntervalSnapshot) {
        val range = state.equipmentType.workoutRange
        val intensity = interval.intensity.coerceIn(0.0, 1.0)

        state.intervalName = interval.name
        state.intervalSecondsRemaining = interval.secondsRemaining
        state.intervalIntensityPercent = kotlin.math.round(intensity * 100.0).toInt()
        state.speedMps = lerp(range.speedKph.start, range.speedKph.endInclusive, intensity) / 3.6
        state.cadenceRpm = lerp(range.cadencePerMinute.first, range.cadencePerMinute.last, intensity)
        state.heartRateBpm = lerp(range.heartRateBpm.first, range.heartRateBpm.last, intensity)
        state.heartRateSource = FeCHeartRateSource.EM
        state.metabolicEquivalent = lerp(
            range.metabolicEquivalent.start,
            range.metabolicEquivalent.endInclusive,
            intensity,
        )
        state.caloricBurnRateKcalPerHour =
            state.metabolicEquivalent * FeCBleMachineState.METABOLIC_FIXTURE_BODY_MASS_KG

        if (state.controlMode == FeCBleControlMode.FREE) {
            state.instantaneousPowerWatts = range.powerWatts?.let {
                lerp(it.first, it.last, intensity)
            }
        }

        range.resistancePercent?.let {
            if (state.controlMode != FeCBleControlMode.BASIC_RESISTANCE) {
                state.resistanceLevelPercent = lerp(it.start, it.endInclusive, intensity)
            }
        }
        range.inclinePercent?.let {
            state.inclinePercent = lerp(it.start, it.endInclusive, intensity)
        }
        if (state.controlMode == FeCBleControlMode.SIMULATION) {
            state.track?.let { state.inclinePercent = it.gradePercent }
        }
    }

    /**
     * Record one page-25 trainer event (plan §10.2). Called by the scheduler immediately before it
     * encodes a page-25 frame, not once per model tick, since the accumulation policy is defined in
     * terms of *emitted* page-25 frames.
     */
    fun recordTrainerEvent(state: FeCBleMachineState) {
        if (state.feState != FeCEquipmentState.IN_USE) return
        state.eventCount += 1
        state.instantaneousPowerWatts?.let { power -> state.accumulatedPower += power }
    }

    /** Apply one decoded control command (plan §13.6). Malformed writes never reach this. */
    fun applyControlCommand(state: FeCBleMachineState, command: FeCBleControlCommand, clientKey: String?) {
        if (!state.equipmentType.supportsControl) return
        when (command) {
            is FeCBleControlCommand.BasicResistance -> {
                state.controlMode = FeCBleControlMode.BASIC_RESISTANCE
                state.basicResistancePercent = command.resistancePercent
                state.resistanceLevelPercent = command.resistancePercent
            }
            is FeCBleControlCommand.TargetPower -> {
                state.controlMode = FeCBleControlMode.ERG
                state.targetPowerWatts = command.watts
            }
            is FeCBleControlCommand.TrackResistance -> {
                state.controlMode = FeCBleControlMode.SIMULATION
                state.track = FeCBleTrackConfig(command.gradePercent, command.crr)
            }
            is FeCBleControlCommand.WindResistance -> {
                if (state.controlMode != FeCBleControlMode.SIMULATION) {
                    state.controlMode = FeCBleControlMode.SIMULATION
                }
                val existing = state.wind
                state.wind = FeCBleWindConfig(
                    windCoefficient = command.windCoefficient ?: existing?.windCoefficient
                        ?: CyclingSimulationPhysics.DEFAULT_CW_KG_PER_M,
                    windSpeedEncodedUnit = command.windSpeedEncodedUnit ?: existing?.windSpeedEncodedUnit ?: 0,
                    draftingFactor = command.draftingFactor ?: existing?.draftingFactor ?: 1.0,
                )
            }
            is FeCBleControlCommand.UserConfiguration -> {
                state.userConfig = FeCBleUserConfig(command.userWeightKg, command.rawPage)
                // Simulation physics consumes an established rider mass; raw unresolved page-55
                // fields remain retained only for diagnostics.
            }
            is FeCBleControlCommand.RequestDataPage -> Unit
            is FeCBleControlCommand.Unsupported, is FeCBleControlCommand.Rejected -> Unit
        }
        clientKey?.let { state.lastControlClient = it }
    }

    /** Plan §12.2/§12.4/§12.5/§12.6: the deterministic target the current mode is converging toward. */
    private fun targetPowerForCurrentMode(state: FeCBleMachineState): Double? = when (state.controlMode) {
        FeCBleControlMode.FREE -> null
        FeCBleControlMode.BASIC_RESISTANCE -> {
            val resistance = state.basicResistancePercent ?: 0.0
            (80.0 + 4.0 * resistance).coerceIn(25.0, 800.0)
        }
        FeCBleControlMode.ERG -> state.targetPowerWatts?.coerceIn(25.0, 800.0)
        FeCBleControlMode.SIMULATION -> null
    }

    private fun simulationPowerAtPublishedSpeed(state: FeCBleMachineState): Double {
        val wind = state.wind
        val riderMass = state.userConfig?.userWeightKg ?: CyclingSimulationPhysics.DEFAULT_RIDER_MASS_KG
        return CyclingSimulationPhysics.requiredRiderPowerWatts(
            speedKph = state.speedMps * 3.6,
            gradePercent = state.track?.gradePercent ?: 0.0,
            crr = state.track?.crr ?: CyclingSimulationPhysics.DEFAULT_CRR,
            cwKgPerM = wind?.windCoefficient ?: CyclingSimulationPhysics.DEFAULT_CW_KG_PER_M,
            windMps = (wind?.windSpeedEncodedUnit ?: 0) / 3.6,
            totalMassKg = riderMass + CyclingSimulationPhysics.DEFAULT_BIKE_MASS_KG,
            draftingFactor = wind?.draftingFactor ?: 1.0,
        ).coerceAtMost(0xFFE.toDouble())
    }

    private fun Double.roundToIntSafely(): Int =
        kotlin.math.round(this).toInt().coerceIn(0, 0xFFE)

    private fun lerp(start: Double, end: Double, fraction: Double): Double =
        start + (end - start) * fraction

    private fun lerp(start: Int, end: Int, fraction: Double): Int =
        kotlin.math.round(lerp(start.toDouble(), end.toDouble(), fraction)).toInt()

    /** 0.25 s at the page-26 wheel-period resolution of 1/2048 s. */
    private const val WHEEL_PERIOD_TICKS_PER_MODEL_TICK = 512L
}
