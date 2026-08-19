package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

/**
 * Deterministic synthetic workout model (plan §12). Coherent and controllable, not physically
 * accurate — every formula here is a **simulator behaviour choice**, pinned for repeatable tests,
 * and none of it is a claim about a physical Tacx trainer's response curves (plan §1.5, §12.6).
 */
internal object FeCBleSyntheticModel {

    /** Model tick period; matches the telemetry scheduler's base cadence (plan §11.1). */
    const val TICK_SECONDS = 0.25

    /** Plan §12.3: first-order power convergence, 25 W per 250 ms tick. */
    const val MAX_POWER_CHANGE_PER_TICK = 25.0

    /** Advance time/distance/power by one [TICK_SECONDS] tick. No-op outside [FeCEquipmentState.IN_USE]. */
    fun advanceTick(state: FeCBleMachineState) {
        if (state.feState != FeCEquipmentState.IN_USE) return
        state.elapsedQuarterSeconds += 1
        state.distanceMeters += state.speedMps * TICK_SECONDS

        val target = targetPowerForCurrentMode(state) ?: return
        val current = (state.instantaneousPowerWatts ?: 0).toDouble()
        val delta = (target - current).coerceIn(-MAX_POWER_CHANGE_PER_TICK, MAX_POWER_CHANGE_PER_TICK)
        state.instantaneousPowerWatts = (current + delta).roundToIntSafely()
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
                    windCoefficient = command.windCoefficient ?: existing?.windCoefficient ?: 0.0,
                    windSpeedEncodedUnit = command.windSpeedEncodedUnit ?: existing?.windSpeedEncodedUnit ?: 0,
                    draftingFactor = command.draftingFactor ?: existing?.draftingFactor ?: 0.0,
                )
            }
            is FeCBleControlCommand.UserConfiguration -> {
                state.userConfig = FeCBleUserConfig(command.userWeightKg, command.rawPage)
                // Plan §12.8: user configuration is recorded but never changes telemetry.
            }
            is FeCBleControlCommand.Unsupported, is FeCBleControlCommand.Rejected -> Unit
        }
        clientKey?.let { state.lastControlClient = it }
    }

    /** Plan §12.2/§12.4/§12.5/§12.6: the deterministic target the current mode is converging toward. */
    private fun targetPowerForCurrentMode(state: FeCBleMachineState): Double? = when (state.controlMode) {
        FeCBleControlMode.FREE -> FeCBleMachineState.DEFAULT_POWER_WATTS.toDouble()
        FeCBleControlMode.BASIC_RESISTANCE -> {
            val resistance = state.basicResistancePercent ?: 0.0
            (80.0 + 4.0 * resistance).coerceIn(25.0, 800.0)
        }
        FeCBleControlMode.ERG -> state.targetPowerWatts?.coerceIn(25.0, 800.0)
        FeCBleControlMode.SIMULATION -> {
            val grade = state.track?.gradePercent ?: 0.0
            val crr = state.track?.crr ?: 0.0033
            (180.0 + 12.0 * grade + 10_000.0 * (crr - 0.0033)).coerceIn(25.0, 800.0)
        }
    }

    private fun Double.roundToIntSafely(): Int =
        kotlin.math.round(this).toInt().coerceIn(0, 0xFFE)
}
