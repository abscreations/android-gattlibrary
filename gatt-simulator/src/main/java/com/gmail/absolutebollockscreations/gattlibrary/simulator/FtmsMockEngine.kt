package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.central.BtServiceProfiles.FtmsMachineProfile
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.FtmsTargetRange
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.TargetCapabilities
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Synthetic FTMS data generation, ported from TrackMyIndoorWorkout's
 * `mock_data_engine.dart` and `mock_control_profile.dart`.
 *
 * Stands in for a physical machine so the whole bridge - engine state, the app's
 * own metric screens, and the peripheral's advertise/subscribe/notify path - can
 * be exercised with nothing plugged in. Every field the encoders can carry is
 * populated, which makes this double as a regression check for field packing: if
 * a metric is present here but missing on a downstream watch, the bug is in the
 * encoder or packer, not upstream.
 *
 * Pure Kotlin with no Android or Bluetooth dependency, so the whole generator is
 * unit-testable.
 */

/** Inclusive bounds for one synthetic instantaneous metric. */
data class FtmsMockRange(val min: Double, val max: Double) {
    fun at(fraction: Double): Double = min + (max - min) * fraction
    fun clamp(value: Double): Double = value.coerceIn(min, max)
}

/**
 * Sport-appropriate behaviour for one selectable simulator persona.
 *
 * These ranges describe an ordinary steady workout, not the full limits of a
 * machine or athlete. The engine moves inside them using a shared effort signal
 * so speed, cadence, power and heart rate stay mutually plausible - a random walk
 * per metric would produce 300 W at 40 rpm and make the output useless for
 * judging whether a downstream display is sane.
 */
data class FtmsMockProfile(
    val machine: FtmsMachineProfile,
    val power: FtmsMockRange,
    val heartRate: FtmsMockRange,
    val speed: FtmsMockRange? = null,
    val cadence: FtmsMockRange? = null,
    val resistance: FtmsMockRange? = null,
    val inclination: FtmsMockRange? = null,
    /** Cumulative strokes, strides or steps added per cadence event. */
    val countPerCadence: Double = 0.0,
    /** Vertical gain per step. Non-zero only for the climber personas. */
    val verticalMetersPerStep: Double = 0.0,
    /** Mechanical-to-metabolic efficiency used for synthetic energy totals. */
    val metabolicEfficiency: Double = 0.25,
) {
    val isBike get() = machine == FtmsMachineProfile.INDOOR_BIKE
    val isTreadmill get() = machine == FtmsMachineProfile.TREADMILL
    val isCrossTrainer get() = machine == FtmsMachineProfile.ELLIPTICAL
    val isRower get() = machine == FtmsMachineProfile.INDOOR_ROWER
    val isClimber get() =
        machine == FtmsMachineProfile.STAIR_STEPPER || machine == FtmsMachineProfile.STAIR_CLIMBER

    companion object {
        val profiles: Map<FtmsMachineProfile, FtmsMockProfile> = mapOf(
            FtmsMachineProfile.INDOOR_BIKE to FtmsMockProfile(
                machine = FtmsMachineProfile.INDOOR_BIKE,
                speed = FtmsMockRange(24.0, 38.0),
                cadence = FtmsMockRange(75.0, 95.0),
                power = FtmsMockRange(100.0, 260.0),
                heartRate = FtmsMockRange(105.0, 160.0),
                resistance = FtmsMockRange(3.0, 12.0),
                metabolicEfficiency = 0.24,
            ),
            FtmsMachineProfile.TREADMILL to FtmsMockProfile(
                machine = FtmsMachineProfile.TREADMILL,
                speed = FtmsMockRange(8.0, 13.0),
                power = FtmsMockRange(170.0, 330.0),
                heartRate = FtmsMockRange(110.0, 168.0),
                inclination = FtmsMockRange(0.0, 4.0),
                metabolicEfficiency = 0.25,
            ),
            FtmsMachineProfile.ELLIPTICAL to FtmsMockProfile(
                machine = FtmsMachineProfile.ELLIPTICAL,
                speed = FtmsMockRange(7.0, 11.0),
                cadence = FtmsMockRange(110.0, 150.0),
                power = FtmsMockRange(90.0, 240.0),
                heartRate = FtmsMockRange(105.0, 165.0),
                resistance = FtmsMockRange(3.0, 12.0),
                inclination = FtmsMockRange(2.0, 10.0),
                countPerCadence = 0.5,
                metabolicEfficiency = 0.24,
            ),
            FtmsMachineProfile.INDOOR_ROWER to FtmsMockProfile(
                machine = FtmsMachineProfile.INDOOR_ROWER,
                speed = FtmsMockRange(11.0, 15.0),
                cadence = FtmsMockRange(20.0, 32.0),
                power = FtmsMockRange(80.0, 280.0),
                heartRate = FtmsMockRange(105.0, 168.0),
                resistance = FtmsMockRange(3.0, 8.0),
                countPerCadence = 1.0,
                metabolicEfficiency = 0.23,
            ),
            // 0x2ACF Step Climber.
            FtmsMachineProfile.STAIR_STEPPER to FtmsMockProfile(
                machine = FtmsMachineProfile.STAIR_STEPPER,
                cadence = FtmsMockRange(50.0, 80.0),
                power = FtmsMockRange(90.0, 190.0),
                heartRate = FtmsMockRange(110.0, 165.0),
                countPerCadence = 1.0,
                verticalMetersPerStep = 0.2,
                metabolicEfficiency = 0.24,
            ),
            // 0x2AD0 Stair Climber.
            FtmsMachineProfile.STAIR_CLIMBER to FtmsMockProfile(
                machine = FtmsMachineProfile.STAIR_CLIMBER,
                cadence = FtmsMockRange(45.0, 75.0),
                power = FtmsMockRange(90.0, 180.0),
                heartRate = FtmsMockRange(110.0, 165.0),
                countPerCadence = 0.5,
                verticalMetersPerStep = 0.2,
                metabolicEfficiency = 0.24,
            ),
        )

        fun forMachine(machine: FtmsMachineProfile): FtmsMockProfile =
            profiles.getValue(machine)
    }
}

/**
 * Full target-setting limits advertised by one simulator persona.
 *
 * These are *machine* limits, deliberately wider than the ordinary-workout
 * ranges in [FtmsMockProfile]. Values are in FTMS wire units so the same object
 * drives feature publication, the Supported-* Range characteristics, Control
 * Point validation and the simulator's own target application - the four places
 * that must agree, and the reason FitBridge's resistance handling was wrong for
 * as long as nothing exercised it.
 */
object FtmsMockControlProfile {

    /**
     * One shared set of full machine limits for every simulator persona.
     *
     * [FtmsMockProfile] remains the source of ordinary autonomous workout
     * values. These ranges are deliberately wider and are the only limits used
     * for advertised target features and externally requested targets.
     */
    val speedRange = FtmsTargetRange(0, 3_000, 10)
    val inclinationRange = FtmsTargetRange(-100, 300, 5)
    val resistanceRange = FtmsTargetRange(10, 300, 10)
    val powerRange = FtmsTargetRange(1, 1_000, 1)

    private val bike = TargetCapabilities(
        speed = true,
        resistance = true,
        power = true,
        // Only the Indoor Bike machine type has an FTMS Set Indoor Bike Simulation Parameters
        // procedure at all (spec 4.16); advertising it on the other machine types would claim a
        // Control Point opcode none of them implement.
        indoorBikeSimulation = true,
        speedRange = speedRange,
        resistanceRange = resistanceRange,
        powerRange = powerRange,
    )

    private val treadmill = TargetCapabilities(
        speed = true,
        inclination = true,
        power = true,
        speedRange = speedRange,
        inclinationRange = inclinationRange,
        powerRange = powerRange,
    )

    private val crossTrainer = TargetCapabilities(
        speed = true,
        inclination = true,
        resistance = true,
        power = true,
        speedRange = speedRange,
        inclinationRange = inclinationRange,
        resistanceRange = resistanceRange,
        powerRange = powerRange,
    )

    private val rower = TargetCapabilities(
        speed = true,
        resistance = true,
        power = true,
        speedRange = speedRange,
        resistanceRange = resistanceRange,
        powerRange = powerRange,
    )

    fun forMachine(machine: FtmsMachineProfile): TargetCapabilities = when (machine) {
        FtmsMachineProfile.INDOOR_BIKE -> bike
        FtmsMachineProfile.TREADMILL -> treadmill
        FtmsMachineProfile.ELLIPTICAL -> crossTrainer
        FtmsMachineProfile.INDOOR_ROWER -> rower
        // FTMS has no target step-rate procedure, and these personas publish no
        // speed, inclination, resistance or power measurement to steer.
        FtmsMachineProfile.STAIR_STEPPER,
        FtmsMachineProfile.STAIR_CLIMBER,
        -> TargetCapabilities.NONE
    }
}

/** Which simulated metric an accepted Control Point target drives. */
enum class FtmsMockTarget { SPEED, INCLINATION, RESISTANCE, POWER }

/**
 * Which family of Control Point write last steered this machine, for the debug UI's "Control
 * mode" line (see [SimulatorControlModeLabels]). Independent of [FtmsMockTarget]: DIRECT covers
 * speed, inclination, and resistance; ERG is power-only; and SIMULATION is the 0x11 Set Indoor
 * Bike Simulation Parameters procedure, which carries its own four-value payload rather than a
 * single [FtmsMockTarget].
 */
enum class FtmsControlKind { DIRECT, ERG, SIMULATION }

/**
 * Decoded FTMS 0x11 Set Indoor Bike Simulation Parameters payload, in public units.
 *
 * Stored verbatim so the simulator can both display what a client last requested and answer
 * honestly that simulation mode is active - this is the "register and store received parameters"
 * half of end-to-end Set Indoor Bike Simulation Parameters support; see
 * [FtmsMockEngine.setSimulationParams].
 */
data class FtmsMockSimulationParams(
    val windMps: Double,
    val gradePercent: Double,
    val crr: Double,
    val cw: Double,
)

/** One coherent sample of the simulated machine, in FitBridge engine units. */
data class FtmsMockSample(
    val elapsedSeconds: Int,
    val heartRate: Int,
    val caloriesTotal: Int,
    val caloriesPerHour: Int,
    val caloriesPerMinute: Int,
    val speedKph: Double? = null,
    val averageSpeedKph: Double? = null,
    val distanceMeters: Int? = null,
    val cadence: Double? = null,
    val averageCadence: Double? = null,
    val resistance: Int? = null,
    val inclinationPercent: Double? = null,
    val powerWatts: Int? = null,
    val averagePowerWatts: Int? = null,
    val forceOnBeltNewtons: Int? = null,
    /** Minutes per km, matching BtEngineState.setInstantPace. */
    val instantPaceMinPerKm: Double? = null,
    val averagePaceMinPerKm: Double? = null,
    val movementCount: Int? = null,
    val positiveElevationMeters: Double? = null,
    val floors: Int? = null,
)

/**
 * Stateful, deterministic-when-seeded metric generator.
 *
 * Not thread-safe by itself; [FtmsSimulator] owns the only instance and drives it
 * from a single coroutine.
 */
class FtmsMockEngine(
    machine: FtmsMachineProfile,
    val controlsEnabled: Boolean = false,
    private val random: Random = Random.Default,
) {
    val profile: FtmsMockProfile = FtmsMockProfile.forMachine(machine)

    val controlCapabilities: TargetCapabilities =
        if (controlsEnabled) FtmsMockControlProfile.forMachine(machine) else TargetCapabilities.NONE

    private val targets = mutableMapOf<FtmsMockTarget, Double>()

    private var elapsedSeconds = 0.0
    private var distanceMeters = 0.0
    private var caloriesKcal = 0.0
    private var movementCount = 0.0
    private var positiveElevationMeters = 0.0
    private var cadenceIntegral = 0.0
    private var powerIntegral = 0.0
    private var effort = INITIAL_EFFORT
    private var targetEffort = INITIAL_TARGET_EFFORT
    private var targetSecondsRemaining = 45.0
    private var power = 0.0
    private var heartRate = 0.0
    private var speed: Double? = null
    private var cadence: Double? = null
    private var resistance: Double? = null
    private var inclination: Double? = null

    /** Last accepted Set Indoor Bike Simulation Parameters payload, or null before one arrives. */
    private var simulationParams: FtmsMockSimulationParams? = null

    /** Last control-write family, for the "Control mode" debug line; null means Automatic. */
    private var lastControlKind: FtmsControlKind? = null

    init {
        reset()
    }

    /** Accepted targets, normalized to the advertised range and increment. */
    @Synchronized
    fun controlTargets(): Map<FtmsMockTarget, Double> = targets.toMap()

    /** Last accepted Set Indoor Bike Simulation Parameters payload, or null. */
    @Synchronized
    fun simulationParams(): FtmsMockSimulationParams? = simulationParams

    /** "Control mode" label for the debug UI; see [SimulatorControlModeLabels]. */
    @Synchronized
    fun controlModeLabel(): String = when (lastControlKind) {
        null -> SimulatorControlModeLabels.AUTOMATIC
        FtmsControlKind.DIRECT -> SimulatorControlModeLabels.FTMS_DIRECT
        FtmsControlKind.ERG -> SimulatorControlModeLabels.FTMS_ERG
        FtmsControlKind.SIMULATION -> SimulatorControlModeLabels.FTMS_SIM
    }

    @Synchronized
    fun reset() {
        elapsedSeconds = 0.0
        distanceMeters = 0.0
        caloriesKcal = 0.0
        movementCount = 0.0
        positiveElevationMeters = 0.0
        cadenceIntegral = 0.0
        powerIntegral = 0.0
        effort = INITIAL_EFFORT
        targetEffort = INITIAL_TARGET_EFFORT
        targetSecondsRemaining = 45.0
        targets.clear()
        speed = profile.speed?.at(INITIAL_EFFORT)
        cadence = profile.cadence?.at(INITIAL_EFFORT)
        power = profile.power.at(INITIAL_EFFORT)
        heartRate = profile.heartRate.at(INITIAL_EFFORT)
        resistance = profile.resistance?.at(INITIAL_EFFORT)
        inclination = profile.inclination?.at(INITIAL_EFFORT)
        simulationParams = null
        lastControlKind = null
    }

    /**
     * Apply one downstream request to the synthetic machine.
     *
     * @param value already in public units (km/h, percent, resistance level, W)
     * as decoded by the Control Point.
     * @return false when this persona does not advertise the requested target.
     */
    @Synchronized
    fun setTarget(type: FtmsMockTarget, value: Double): Boolean {
        val range = rangeFor(type) ?: return false
        if (!controlsEnabled) return false
        val normalized = normalize(type, value, range)
        targets[type] = normalized
        when (type) {
            FtmsMockTarget.SPEED -> speed = normalized
            FtmsMockTarget.INCLINATION -> inclination = normalized
            FtmsMockTarget.RESISTANCE -> resistance = normalized
            FtmsMockTarget.POWER -> power = normalized
        }
        lastControlKind = when (type) {
            FtmsMockTarget.POWER -> FtmsControlKind.ERG
            FtmsMockTarget.SPEED, FtmsMockTarget.INCLINATION, FtmsMockTarget.RESISTANCE ->
                FtmsControlKind.DIRECT
        }
        return true
    }

    /**
     * Apply one downstream Set Indoor Bike Simulation Parameters procedure.
     *
     * Registers and stores the payload so it can be displayed and, per [controlModeLabel],
     * reported as the active control mode - the end-to-end half of FTMS Indoor Bike Simulation
     * support that previously stopped at the peripheral's Control Point decoder and never reached
     * a downstream that could honestly accept it.
     *
     * @return false when this persona/session does not advertise indoor bike simulation support.
     */
    @Synchronized
    fun setSimulationParams(params: FtmsMockSimulationParams): Boolean {
        if (!controlsEnabled || !controlCapabilities.indoorBikeSimulation) return false
        simulationParams = params
        targets.remove(FtmsMockTarget.POWER)
        lastControlKind = FtmsControlKind.SIMULATION
        return true
    }

    /** Advance the workout by [dtSeconds] and return the new sample. */
    @Synchronized
    fun tick(dtSeconds: Double): FtmsMockSample {
        if (dtSeconds <= 0.0) return snapshot()

        elapsedSeconds += dtSeconds
        advanceEffort(dtSeconds)

        // Each target is independent. A client-owned metric is held while every
        // non-overridden metric continues its normal autonomous workout walk.
        if (FtmsMockTarget.SPEED !in targets) {
            speed = advanceMetric(speed, profile.speed, dtSeconds, timeConstant = 5.0, noise = 0.025)
        }
        cadence = advanceMetric(cadence, profile.cadence, dtSeconds, timeConstant = 4.0, noise = 0.02)
        val activeSimulation = lastControlKind == FtmsControlKind.SIMULATION && profile.isBike
        if (activeSimulation) {
            val sim = requireNotNull(simulationParams)
            power = CyclingSimulationPhysics.requiredRiderPowerWatts(
                speedKph = speed ?: 0.0,
                gradePercent = sim.gradePercent,
                crr = sim.crr,
                cwKgPerM = sim.cw,
                windMps = sim.windMps,
            ).coerceAtMost(Short.MAX_VALUE.toDouble())
        } else if (FtmsMockTarget.POWER !in targets) {
            power = advanceMetric(power, profile.power, dtSeconds, timeConstant = 3.0, noise = 0.035)
                ?: power
        }
        heartRate =
            advanceMetric(heartRate, profile.heartRate, dtSeconds, timeConstant = 25.0, noise = 0.005)
                ?: heartRate
        if (FtmsMockTarget.RESISTANCE !in targets) {
            resistance =
                advanceMetric(resistance, profile.resistance, dtSeconds, timeConstant = 18.0, noise = 0.01)
        }
        if (FtmsMockTarget.INCLINATION !in targets) {
            inclination =
                advanceMetric(inclination, profile.inclination, dtSeconds, timeConstant = 20.0, noise = 0.01)
        }

        speed?.let { distanceMeters += it / 3.6 * dtSeconds }
        cadence?.let { c ->
            cadenceIntegral += c * dtSeconds
            if (profile.countPerCadence > 0.0) {
                movementCount += c / 60.0 * dtSeconds * profile.countPerCadence
            }
            if (profile.verticalMetersPerStep > 0.0) {
                positiveElevationMeters += c / 60.0 * dtSeconds * profile.verticalMetersPerStep
            }
        }
        powerIntegral += power * dtSeconds
        caloriesKcal += kcalPerSecond() * dtSeconds

        return snapshot()
    }

    @Synchronized
    fun snapshot(): FtmsMockSample {
        val caloriesPerHour = (kcalPerSecond() * 3600.0).roundToInt()
        val s = speed

        val emitsPower = profile.isTreadmill || profile.isBike ||
            profile.isCrossTrainer || profile.isRower

        return FtmsMockSample(
            elapsedSeconds = elapsedSeconds.roundToInt(),
            heartRate = heartRate.roundToInt(),
            caloriesTotal = caloriesKcal.roundToInt(),
            caloriesPerHour = caloriesPerHour,
            caloriesPerMinute = (caloriesPerHour / 60.0).roundToInt(),
            speedKph = s,
            averageSpeedKph = if (s == null) {
                null
            } else if (elapsedSeconds > 0.0) {
                distanceMeters / elapsedSeconds * 3.6
            } else {
                s
            },
            distanceMeters = if (s == null) null else distanceMeters.roundToInt(),
            cadence = cadence,
            averageCadence = cadence?.let { current ->
                if (elapsedSeconds > 0.0) cadenceIntegral / elapsedSeconds else current
            },
            resistance = resistance?.roundToInt(),
            inclinationPercent = inclination,
            powerWatts = if (emitsPower) power.roundToInt() else null,
            averagePowerWatts = if (emitsPower) {
                if (elapsedSeconds > 0.0) {
                    (powerIntegral / elapsedSeconds).roundToInt()
                } else {
                    power.roundToInt()
                }
            } else {
                null
            },
            // Treadmills report belt force alongside power; F = P / v.
            forceOnBeltNewtons = if (profile.isTreadmill && s != null) {
                val metresPerSecond = s / 3.6
                if (metresPerSecond > 0.0) (power / metresPerSecond).roundToInt() else 0
            } else {
                null
            },
            // BtEngineState carries pace as minutes per km; the peripheral
            // converts to seconds per 500 m for the rower characteristic.
            instantPaceMinPerKm = if (profile.isRower && s != null && s > 0.0) 60.0 / s else null,
            averagePaceMinPerKm = if (profile.isRower && s != null && s > 0.0) {
                if (distanceMeters > 0.0) {
                    elapsedSeconds / 60.0 / (distanceMeters / 1000.0)
                } else {
                    60.0 / s
                }
            } else {
                null
            },
            movementCount = if (profile.countPerCadence > 0.0) movementCount.roundToInt() else null,
            positiveElevationMeters = if (profile.isClimber) positiveElevationMeters else null,
            floors = if (profile.isClimber) {
                floor(positiveElevationMeters / METRES_PER_FLOOR).toInt()
            } else {
                null
            },
        )
    }

    private fun kcalPerSecond(): Double = power / (JOULES_PER_KCAL * profile.metabolicEfficiency)

    private fun rangeFor(type: FtmsMockTarget): FtmsTargetRange? = when (type) {
        FtmsMockTarget.SPEED -> controlCapabilities.speedRange
        FtmsMockTarget.INCLINATION -> controlCapabilities.inclinationRange
        FtmsMockTarget.RESISTANCE -> controlCapabilities.resistanceRange
        FtmsMockTarget.POWER -> controlCapabilities.powerRange
    }

    /** Wire units -> the public units the simulated metric is stored in. */
    private fun publicOf(type: FtmsMockTarget, wire: Int): Double = when (type) {
        FtmsMockTarget.SPEED -> wire / 100.0        // 0.01 km/h
        FtmsMockTarget.INCLINATION -> wire / 10.0   // 0.1 %
        FtmsMockTarget.RESISTANCE -> wire / 10.0    // 0.1 level
        FtmsMockTarget.POWER -> wire.toDouble()     // W
    }

    /** Public units -> wire units using the FTMS resolution for this target. */
    private fun wireOf(type: FtmsMockTarget, value: Double): Int = when (type) {
        FtmsMockTarget.SPEED -> (value * 100.0).roundToInt()
        FtmsMockTarget.INCLINATION,
        FtmsMockTarget.RESISTANCE,
        -> (value * 10.0).roundToInt()
        FtmsMockTarget.POWER -> value.roundToInt()
    }

    private fun normalize(
        type: FtmsMockTarget,
        value: Double,
        range: FtmsTargetRange,
    ): Double {
        val snappedWire = range.clampAndSnap(wireOf(type, value))
        return publicOf(type, snappedWire)
    }

    private fun advanceEffort(dt: Double) {
        targetSecondsRemaining -= dt
        if (targetSecondsRemaining <= 0.0) {
            targetEffort = MIN_TARGET_EFFORT +
                random.nextDouble() * (MAX_TARGET_EFFORT - MIN_TARGET_EFFORT)
            targetSecondsRemaining = 20.0 + random.nextDouble() * 40.0
        }
        effort = approach(effort, targetEffort, dt, 12.0).coerceIn(0.0, 1.0)
    }

    private fun advanceMetric(
        current: Double?,
        range: FtmsMockRange?,
        dt: Double,
        timeConstant: Double,
        noise: Double,
    ): Double? {
        if (current == null || range == null) return null
        val span = range.max - range.min
        val target = range.at(effort) + (random.nextDouble() * 2 - 1) * span * noise
        return range.clamp(approach(current, target, dt, timeConstant))
    }

    /** Exponential approach, so a changing tick rate cannot change the shape. */
    private fun approach(current: Double, target: Double, dt: Double, timeConstant: Double): Double {
        val alpha = 1 - exp(-dt / timeConstant)
        return current + (target - current) * alpha
    }

    companion object {
        private const val JOULES_PER_KCAL = 4184.0
        private const val INITIAL_EFFORT = 0.35
        private const val INITIAL_TARGET_EFFORT = 0.52
        private const val MIN_TARGET_EFFORT = 0.30
        private const val MAX_TARGET_EFFORT = 0.78
        private const val METRES_PER_FLOOR = 3.0

        /** Personas the simulator can emulate, in menu order. */
        val supportedMachines: List<FtmsMachineProfile> = listOf(
            FtmsMachineProfile.INDOOR_BIKE,
            FtmsMachineProfile.TREADMILL,
            FtmsMachineProfile.ELLIPTICAL,
            FtmsMachineProfile.INDOOR_ROWER,
            FtmsMachineProfile.STAIR_STEPPER,
            FtmsMachineProfile.STAIR_CLIMBER,
        )
    }
}
