package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsControlPointHandler
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsControlSink
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsProcedureSession
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsTargetRange
import com.gmail.absolutebollockscreations.gattlibrary.server.TargetCapabilities
import java.util.UUID
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.roundToInt
import com.gmail.absolutebollockscreations.gattlibrary.core.bytes.LittleEndian.u16Wrapping
import com.gmail.absolutebollockscreations.gattlibrary.core.bytes.LittleEndian.s16Wrapping
import com.gmail.absolutebollockscreations.gattlibrary.core.bytes.LittleEndian.u32Wrapping
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.WahooUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.ZwiftUuids

@SimulatorProbeApi
public enum class KickrControlMode { AUTONOMOUS, ERG, SIMULATION, RESISTANCE }

/**
 * Which protocol surface last wrote a control target on this shared model.
 *
 * [KickrSyntheticModel] is deliberately one physics engine behind three independent protocol
 * servers (standard FTMS via [KickrSyntheticFtmsControlSink], legacy Wahoo BLE via
 * [KickrLegacyControlServer], Zwift RideOn via `ZwiftTrainerPeripheralServer`), because a real
 * KICKR is one machine no matter which app is steering it. Previously nothing recorded *which*
 * of the three last wrote, so the debug UI's "Control mode" line only ever showed the physics
 * mode ("ERG"/"SIMULATION"/"RESISTANCE") with no protocol attribution - indistinguishable from a
 * legacy app driving ERG and a RideOn app driving simulation. [KickrControlKind] carries the
 * companion "was this a direct target, an ERG power target, or a full simulation payload"
 * axis; the two together select one [SimulatorControlModeLabels] string.
 */
@SimulatorProbeApi
public enum class KickrControlSource { NONE, FTMS, LEGACY_KICKR, RIDE_ON }

/** DIRECT covers resistance-level and virtual-gear writes; see [KickrControlSource]. */
internal enum class KickrControlKind { DIRECT, ERG, SIMULATION }

@SimulatorProbeApi
public data class KickrSimulationTarget(
    val windMps: Double = 0.0,
    val gradePercent: Double = 0.0,
    val crr: Double = 0.004,
    val cw: Double = 0.51,
    val totalMassKg: Double = CyclingSimulationPhysics.DEFAULT_TOTAL_MASS_KG,
)

/** One immutable state read by every standard and proprietary encoder. */
@SimulatorProbeApi
public data class KickrMachineSnapshot(
    val elapsedSeconds: Double,
    val running: Boolean,
    val mode: KickrControlMode,
    val speedKph: Double,
    val cadenceRpm: Double,
    val powerWatts: Int,
    val heartRateBpm: Int,
    val distanceMetres: Double,
    val energyKcal: Double,
    val resistanceLevel: Double,
    val targetPowerWatts: Int?,
    val simulation: KickrSimulationTarget,
    val wheelCircumferenceMm: Double,
    val cumulativeWheelRevolutions: Long,
    val lastWheelEventTicks: Int,
    val cumulativeCrankRevolutions: Int,
    val lastCrankEventTicks: Int,
    val accumulatedTorqueRaw: Int,
    val virtualGearIndex: Int,
    val gearRatio: Double,
    /** "Control mode" debug label; see [SimulatorControlModeLabels]. */
    val controlLabel: String,
)

/**
 * Deterministic indoor-bike model shared by CPS, FTMS, legacy Wahoo and Zwift-protocol servers.
 *
 * This is an interoperability model, not a mechanical KICKR claim. Its bounded convergence makes
 * accepted controls externally observable while keeping all wire surfaces coherent.
 */
@SimulatorProbeApi
public class KickrSyntheticModel(
    initialWheelCircumferenceMm: Double = DEFAULT_WHEEL_CIRCUMFERENCE_MM,
) {
    /**
     * Whether this run answers FTMS target-setting procedures at all - the simulator's
     * "Accept target commands" switch, resolved once per start and read live by
     * [KickrSyntheticFtmsControlSink.canControl].
     *
     * Deliberately NOT cleared by [reset]: reset models the machine returning to an idle ride,
     * not the operator changing what the persona is willing to be asked. Turning this off leaves
     * the persona's captured 0x2ACC feature word and Supported-* ranges untouched - those are
     * device identity replicated from a capture - so target writes are refused with
     * OPCODE_NOT_SUPPORTED while the advertised surface still describes the real trainer.
     */
    @Volatile
    public var ftmsControlEnabled: Boolean = true

    private var elapsedSeconds = 0.0
    private var running = false
    private var mode = KickrControlMode.AUTONOMOUS
    private var controlSource = KickrControlSource.NONE
    private var controlKind: KickrControlKind? = null
    private var speedKph = 0.0
    private var cadenceRpm = 0.0
    private var powerWatts = 0.0
    private var heartRateBpm = IDLE_HEART_RATE.toDouble()
    private var distanceMetres = 0.0
    private var energyKcal = 0.0
    private var resistanceLevel = DEFAULT_RESISTANCE
    private var targetPowerWatts: Int? = null
    private var simulation = KickrSimulationTarget()
    private var virtualGearIndex = DEFAULT_VIRTUAL_GEAR_INDEX
    private var gearRatio = DEFAULT_GEAR_RATIO
    private var wheelCircumferenceMm = checkedCircumference(initialWheelCircumferenceMm)

    private var wheelPhase = 0.0
    private var crankPhase = 0.0
    private var cumulativeWheelRevolutions = 0L
    private var lastWheelEventTicks = 0
    private var cumulativeCrankRevolutions = 0
    private var lastCrankEventTicks = 0
    private var accumulatedTorque = 0.0

    @Synchronized
    public fun startOrResume(): Boolean {
        running = true
        return true
    }

    @Synchronized
    public fun stopOrPause(stop: Boolean): Boolean {
        running = false
        speedKph = 0.0
        cadenceRpm = 0.0
        powerWatts = 0.0
        if (stop) {
            mode = KickrControlMode.AUTONOMOUS
            controlSource = KickrControlSource.NONE
            controlKind = null
            targetPowerWatts = null
        }
        return true
    }

    @Synchronized
    public fun reset(): Boolean {
        elapsedSeconds = 0.0
        running = false
        mode = KickrControlMode.AUTONOMOUS
        controlSource = KickrControlSource.NONE
        controlKind = null
        speedKph = 0.0
        cadenceRpm = 0.0
        powerWatts = 0.0
        heartRateBpm = IDLE_HEART_RATE.toDouble()
        distanceMetres = 0.0
        energyKcal = 0.0
        resistanceLevel = DEFAULT_RESISTANCE
        targetPowerWatts = null
        simulation = KickrSimulationTarget()
        virtualGearIndex = DEFAULT_VIRTUAL_GEAR_INDEX
        gearRatio = DEFAULT_GEAR_RATIO
        wheelPhase = 0.0
        crankPhase = 0.0
        cumulativeWheelRevolutions = 0L
        lastWheelEventTicks = 0
        cumulativeCrankRevolutions = 0
        lastCrankEventTicks = 0
        accumulatedTorque = 0.0
        return true
    }

    @Synchronized
    public fun setTargetPower(watts: Int, source: KickrControlSource = KickrControlSource.NONE): Boolean {
        if (watts !in MIN_POWER_WATTS..MAX_POWER_WATTS) return false
        mode = KickrControlMode.ERG
        targetPowerWatts = watts
        controlSource = source
        controlKind = KickrControlKind.ERG
        return true
    }

    @Synchronized
    public fun setResistance(level: Double, source: KickrControlSource = KickrControlSource.NONE): Boolean {
        if (!level.isFinite() || level !in MIN_RESISTANCE..MAX_RESISTANCE) return false
        mode = KickrControlMode.RESISTANCE
        resistanceLevel = level
        targetPowerWatts = null
        controlSource = source
        controlKind = KickrControlKind.DIRECT
        return true
    }

    @Synchronized
    public fun setSimulation(
        target: KickrSimulationTarget,
        source: KickrControlSource = KickrControlSource.NONE,
    ): Boolean {
        if (!target.windMps.isFinite() || target.windMps !in -32.768..32.767) return false
        if (!target.gradePercent.isFinite() || target.gradePercent !in -327.68..327.67) return false
        if (!target.crr.isFinite() || target.crr !in 0.0..0.0255) return false
        if (!target.cw.isFinite() || target.cw !in 0.0..2.55) return false
        if (!target.totalMassKg.isFinite() || target.totalMassKg <= 0.0 || target.totalMassKg > 327.67) return false
        mode = KickrControlMode.SIMULATION
        simulation = target
        targetPowerWatts = null
        controlSource = source
        controlKind = KickrControlKind.SIMULATION
        return true
    }

    @Synchronized
    public fun setWheelCircumference(millimetres: Double): Boolean {
        if (!millimetres.isFinite() || millimetres !in MIN_WHEEL_CIRCUMFERENCE_MM..MAX_WHEEL_CIRCUMFERENCE_MM) {
            return false
        }
        wheelCircumferenceMm = millimetres
        return true
    }

    @Synchronized
    public fun setVirtualGear(index: Int, ratio: Double): Boolean {
        if (index !in 1..24 || !ratio.isFinite() || ratio <= 0.0) return false
        virtualGearIndex = index
        gearRatio = ratio
        return true
    }

    /**
     * Record a standalone gear-shift command as the active "Direct" control for [source].
     *
     * Deliberately separate from [setVirtualGear]: a RideOn HubCommand can carry a gear ratio
     * *alongside* a simulation payload (plan/RideOn protocol - see
     * `ZwiftTrainerPeripheralServer.handleHubCommand`), and that combined write must keep
     * reporting [KickrControlKind.SIMULATION], not get silently downgraded to Direct because a
     * gear ratio happened to ride along. Only a bare gear-shift command - a `VirtualShift`, or a
     * `HubCommand{Physical{gearRatio}}` with no Simulation alongside it (the form a real KICKR
     * Core sends on every actual gear shift - see the KDoc on
     * `ZwiftTrainerPeripheralServer.handleHubCommand`) - calls this.
     */
    @Synchronized
    public fun markDirectControl(source: KickrControlSource) {
        controlSource = source
        controlKind = KickrControlKind.DIRECT
    }

    @Synchronized
    public fun advance(dtSeconds: Double): KickrMachineSnapshot {
        if (!dtSeconds.isFinite() || dtSeconds <= 0.0 || dtSeconds > MAX_TICK_SECONDS) return snapshot()
        elapsedSeconds += dtSeconds
        if (!running) return snapshot()

        val simulationPhysicsPowerWatts = if (mode == KickrControlMode.SIMULATION) {
            speedKph = approach(
                speedKph,
                SIMULATION_SPEED_KPH,
                dtSeconds,
                SPEED_TIME_CONSTANT_SECONDS,
            )
            CyclingSimulationPhysics.requiredRiderPowerWatts(
                speedKph = speedKph,
                gradePercent = simulation.gradePercent,
                crr = simulation.crr,
                cwKgPerM = simulation.cw,
                windMps = simulation.windMps,
                totalMassKg = simulation.totalMassKg,
            ).coerceAtMost(MAX_POWER_WATTS.toDouble())
        } else {
            null
        }
        val desiredPower = when (mode) {
            KickrControlMode.AUTONOMOUS -> 160.0
            KickrControlMode.ERG -> targetPowerWatts?.toDouble() ?: 160.0
            KickrControlMode.RESISTANCE -> 45.0 + resistanceLevel * 2.1
            KickrControlMode.SIMULATION -> simulationPhysicsPowerWatts!!
        }
        // Every mode's power - including a fresh SIMULATION-mode physics solve - is smoothed
        // through the same approach() step. Without this, entering SIMULATION on a stray
        // Set Indoor Bike Simulation Parameters write (e.g. Kinomap interleaving grade updates
        // with its periodic ERG keepalive) computes the physically "correct" power for the new
        // grade against whatever speed the PREVIOUS mode had settled at, producing an
        // instantaneous, discontinuous power spike before the model ever gets a chance to
        // converge speed toward that grade. See docs/evidence/phase-10 Zwift Hub Kinomap
        // capture 2026-08-30: a grade write landing ~1s after an ERG 75W target produced a
        // 654-670W spike that then exponentially decayed back to 75W as mode flipped back to ERG.
        powerWatts = approach(powerWatts, desiredPower, dtSeconds, POWER_TIME_CONSTANT_SECONDS)
        val desiredCadence = if (desiredPower <= 0.0) 0.0 else (78.0 + desiredPower / 35.0).coerceAtMost(105.0)
        cadenceRpm = approach(cadenceRpm, desiredCadence, dtSeconds, CADENCE_TIME_CONSTANT_SECONDS)
        if (mode != KickrControlMode.SIMULATION) {
            val desiredSpeed = (20.0 + powerWatts / 18.0).coerceIn(0.0, MAX_SPEED_KPH)
            speedKph = approach(speedKph, desiredSpeed, dtSeconds, SPEED_TIME_CONSTANT_SECONDS)
        }
        heartRateBpm = approach(
            heartRateBpm,
            (88.0 + powerWatts / 3.5).coerceIn(IDLE_HEART_RATE.toDouble(), MAX_HEART_RATE.toDouble()),
            dtSeconds,
            HEART_RATE_TIME_CONSTANT_SECONDS,
        )

        distanceMetres += speedKph / 3.6 * dtSeconds
        energyKcal += powerWatts * dtSeconds / (JOULES_PER_KCAL * METABOLIC_EFFICIENCY)
        advanceEvents(dtSeconds)
        return snapshot()
    }

    @Synchronized
    public fun snapshot(): KickrMachineSnapshot = KickrMachineSnapshot(
        elapsedSeconds = elapsedSeconds,
        running = running,
        mode = mode,
        speedKph = speedKph,
        cadenceRpm = cadenceRpm,
        powerWatts = powerWatts.roundToInt(),
        heartRateBpm = heartRateBpm.roundToInt(),
        distanceMetres = distanceMetres,
        energyKcal = energyKcal,
        resistanceLevel = resistanceLevel,
        targetPowerWatts = targetPowerWatts,
        simulation = simulation,
        wheelCircumferenceMm = wheelCircumferenceMm,
        cumulativeWheelRevolutions = cumulativeWheelRevolutions,
        lastWheelEventTicks = lastWheelEventTicks,
        cumulativeCrankRevolutions = cumulativeCrankRevolutions,
        lastCrankEventTicks = lastCrankEventTicks,
        accumulatedTorqueRaw = accumulatedTorque.roundToInt() and 0xFFFF,
        virtualGearIndex = virtualGearIndex,
        gearRatio = gearRatio,
        controlLabel = labelFor(controlSource, controlKind),
    )

    /** Maps (protocol source, target kind) to one [SimulatorControlModeLabels] string. */
    private fun labelFor(source: KickrControlSource, kind: KickrControlKind?): String {
        if (source == KickrControlSource.NONE || kind == null) return SimulatorControlModeLabels.AUTOMATIC
        return when (source) {
            KickrControlSource.NONE -> SimulatorControlModeLabels.AUTOMATIC
            KickrControlSource.FTMS -> when (kind) {
                KickrControlKind.DIRECT -> SimulatorControlModeLabels.FTMS_DIRECT
                KickrControlKind.ERG -> SimulatorControlModeLabels.FTMS_ERG
                KickrControlKind.SIMULATION -> SimulatorControlModeLabels.FTMS_SIM
            }
            // The legacy Wahoo and RideOn wire protocols have no distinct ERG bucket in the debug
            // UI's vocabulary (plan: simulator control-mode display) - an ERG target set through
            // either reads as that protocol's Direct label, same as a resistance or gear write.
            KickrControlSource.LEGACY_KICKR -> when (kind) {
                KickrControlKind.SIMULATION -> SimulatorControlModeLabels.KICKR_SIM
                KickrControlKind.DIRECT, KickrControlKind.ERG -> SimulatorControlModeLabels.KICKR_DIRECT
            }
            KickrControlSource.RIDE_ON -> when (kind) {
                KickrControlKind.SIMULATION -> SimulatorControlModeLabels.RIDEON_SIM
                KickrControlKind.DIRECT, KickrControlKind.ERG -> SimulatorControlModeLabels.RIDEON_DIRECT
            }
        }
    }

    private fun advanceEvents(dtSeconds: Double) {
        val intervalStartSeconds = elapsedSeconds - dtSeconds
        val wheelRate = if (wheelCircumferenceMm > 0.0) {
            (speedKph / 3.6) / (wheelCircumferenceMm / 1000.0)
        } else {
            0.0
        }
        val wheelPhaseAtStart = wheelPhase
        val totalWheel = wheelPhaseAtStart + wheelRate * dtSeconds
        val completedWheel = floor(totalWheel).toLong()
        wheelPhase = totalWheel - completedWheel
        if (completedWheel > 0L) {
            cumulativeWheelRevolutions =
                (cumulativeWheelRevolutions + completedWheel) and UINT32_MASK
            lastWheelEventTicks = eventTicks(
                lastEventSeconds(
                    intervalStartSeconds,
                    wheelPhaseAtStart,
                    wheelRate,
                    completedWheel.toDouble(),
                ),
                WHEEL_EVENT_HZ,
            )
            // CPS flags 0x0034 declare accumulated torque as wheel-based. Each completed wheel
            // revolution contributes the current wheel torque once; using crank torque here made
            // standards-aware clients derive alternating 0/233 W from a steady 160 W stream.
            val wheelAngularVelocity = wheelRate * 2.0 * PI
            val wheelTorqueNm = if (wheelAngularVelocity > 0.0) {
                powerWatts / wheelAngularVelocity
            } else {
                0.0
            }
            accumulatedTorque = (
                accumulatedTorque + wheelTorqueNm * completedWheel * TORQUE_UNITS_PER_NEWTON_METRE
                ) % UINT16_MODULUS
        }

        val crankRate = cadenceRpm / 60.0
        val crankPhaseAtStart = crankPhase
        val totalCrank = crankPhaseAtStart + crankRate * dtSeconds
        val completedCrank = floor(totalCrank).toInt()
        crankPhase = totalCrank - completedCrank
        if (completedCrank > 0) {
            cumulativeCrankRevolutions = (cumulativeCrankRevolutions + completedCrank) and 0xFFFF
            lastCrankEventTicks = eventTicks(
                lastEventSeconds(
                    intervalStartSeconds,
                    crankPhaseAtStart,
                    crankRate,
                    completedCrank.toDouble(),
                ),
                CRANK_EVENT_HZ,
            )
        }
    }

    /** Time of the last complete revolution within a constant-rate model interval. */
    private fun lastEventSeconds(
        intervalStartSeconds: Double,
        phaseAtStart: Double,
        revolutionsPerSecond: Double,
        completedRevolutions: Double,
    ): Double = intervalStartSeconds +
        (completedRevolutions - phaseAtStart) / revolutionsPerSecond

    private fun eventTicks(eventSeconds: Double, hz: Double): Int =
        floor(eventSeconds * hz).toLong().toInt() and 0xFFFF

    private fun approach(current: Double, target: Double, dt: Double, timeConstant: Double): Double =
        current + (target - current) * (dt / timeConstant).coerceIn(0.0, 1.0)

    private fun checkedCircumference(value: Double): Double {
        require(value.isFinite() && value in MIN_WHEEL_CIRCUMFERENCE_MM..MAX_WHEEL_CIRCUMFERENCE_MM)
        return value
    }

    public companion object {
        public const val MIN_POWER_WATTS: Int = 0
        public const val MAX_POWER_WATTS: Int = 2_000
        public const val MIN_RESISTANCE: Double = 1.0
        public const val MAX_RESISTANCE: Double = 100.0
        public const val MIN_WHEEL_CIRCUMFERENCE_MM: Double = 0.1
        public const val MAX_WHEEL_CIRCUMFERENCE_MM: Double = 6_553.5
        public const val DEFAULT_WHEEL_CIRCUMFERENCE_MM: Double = 2_096.0
        public const val MAX_TICK_SECONDS: Double = 10.0
        public const val DEFAULT_VIRTUAL_GEAR_INDEX: Int = 12
        public const val DEFAULT_GEAR_RATIO: Double = 2.4
        private const val DEFAULT_RESISTANCE = 35.0
        private const val IDLE_HEART_RATE = 80
        private const val MAX_HEART_RATE = 220
        private const val MAX_SPEED_KPH = 100.0
        private const val SIMULATION_SPEED_KPH = 28.0
        private const val POWER_TIME_CONSTANT_SECONDS = 3.0
        private const val CADENCE_TIME_CONSTANT_SECONDS = 2.0
        private const val SPEED_TIME_CONSTANT_SECONDS = 4.0
        private const val HEART_RATE_TIME_CONSTANT_SECONDS = 12.0
        private const val WHEEL_EVENT_HZ = 2048.0
        private const val CRANK_EVENT_HZ = 1024.0
        private const val TORQUE_UNITS_PER_NEWTON_METRE = 32.0
        private const val UINT16_MODULUS = 65_536.0
        private const val UINT32_MASK = 0xFFFF_FFFFL
        private const val JOULES_PER_KCAL = 4_184.0
        private const val METABOLIC_EFFICIENCY = 0.24
    }
}

@SimulatorProbeApi
public object KickrStandardEncoders {
    public const val CPS_FLAGS_CAPTURE_SHAPE: Int = 0x0034
    public const val FTMS_FLAGS_CAPTURE_SHAPE: Int = 0x0044

    public val CORE_FTMS_FEATURE: ByteArray = byteArrayOf(0x03, 0x40, 0x00, 0x00, 0x0C, 0x60, 0x00, 0x00)
    public val CPS_FEATURE: ByteArray = byteArrayOf(0x08, 0x00, 0x00, 0x00)
    public val SENSOR_LOCATION: ByteArray = byteArrayOf(0x0D)
    /**
     * Derived from [KickrFtmsCapabilities] rather than restated, so the range a client reads from
     * 0x2AD6 and the range [FtmsControlPointHandler] validates against can never drift apart.
     * This deliberately diverges from the captured `0a0096000a00`: that value came from QZ's
     * hardcoded Dircon table, not from a read of a physical trainer, and its 1.0-15.0 ceiling with
     * a 1.0 step rejected every whole-level resistance the Wahoo app writes.
     */
    public val SUPPORTED_RESISTANCE_RANGE: ByteArray =
        requireNotNull(KickrFtmsCapabilities.VALUE.resistanceRange).encodeS16()
    public val SUPPORTED_POWER_RANGE: ByteArray = s16Wrapping(0) + s16Wrapping(2_000) + u16Wrapping(1)

    public fun cyclingPowerMeasurement(snapshot: KickrMachineSnapshot): ByteArray =
        u16Wrapping(CPS_FLAGS_CAPTURE_SHAPE) +
            s16Wrapping(snapshot.powerWatts.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())) +
            u16Wrapping(snapshot.accumulatedTorqueRaw) +
            u32Wrapping(snapshot.cumulativeWheelRevolutions.toInt().toLong()) +
            u16Wrapping(snapshot.lastWheelEventTicks) +
            u16Wrapping(snapshot.cumulativeCrankRevolutions) +
            u16Wrapping(snapshot.lastCrankEventTicks)

    public fun indoorBikeData(snapshot: KickrMachineSnapshot): ByteArray =
        u16Wrapping(FTMS_FLAGS_CAPTURE_SHAPE) +
            u16Wrapping((snapshot.speedKph * 100.0).roundToInt().coerceIn(0, 0xFFFF)) +
            u16Wrapping((snapshot.cadenceRpm * 2.0).roundToInt().coerceIn(0, 0xFFFF)) +
            s16Wrapping(snapshot.powerWatts.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))

    public fun readValue(
        persona: KickrSimulatorPersona,
        uuid: UUID,
        trainingStatus: ByteArray = byteArrayOf(0x00, 0x01),
    ): KickrReadValue? {
        val identity = KickrPersonaManifest.forPersona(persona).identity
        val standardEvidence = when (persona) {
            KickrSimulatorPersona.CORE_ORIGINAL_ZWIFT -> setOf(KickrEvidenceLabel.HCI)
            KickrSimulatorPersona.CORE2_FC82_PROVISIONAL ->
                setOf(KickrEvidenceLabel.M, KickrEvidenceLabel.SYN)
            KickrSimulatorPersona.ZWIFT_HUB_CAPTURED ->
                setOf(KickrEvidenceLabel.LOG, KickrEvidenceLabel.SYN)
            KickrSimulatorPersona.V5_STANDARD_LEGACY ->
                setOf(KickrEvidenceLabel.LOG, KickrEvidenceLabel.SYN)
        }
        return when (uuid) {
            FtmsUuids.UUID_FITNESS_MACHINE_FEATURE -> KickrReadValue(
                CORE_FTMS_FEATURE.copyOf(),
                standardEvidence,
            )
            FtmsUuids.UUID_TRAINING_STATUS -> KickrReadValue(trainingStatus.copyOf(), setOf(KickrEvidenceLabel.P))
            FtmsUuids.UUID_SUPPORTED_RESISTANCE_RANGE ->
                KickrReadValue(SUPPORTED_RESISTANCE_RANGE.copyOf(), setOf(KickrEvidenceLabel.LOG))
            FtmsUuids.UUID_SUPPORTED_POWER_RANGE ->
                KickrReadValue(SUPPORTED_POWER_RANGE.copyOf(), setOf(KickrEvidenceLabel.SYN))
            SigUuids.UUID_CYCLING_POWER_FEATURE -> KickrReadValue(CPS_FEATURE.copyOf(), setOf(KickrEvidenceLabel.LOG))
            SigUuids.UUID_SENSOR_LOCATION -> KickrReadValue(SENSOR_LOCATION.copyOf(), setOf(KickrEvidenceLabel.LOG))
            SigUuids.UUID_BODY_SENSOR_LOCATION -> KickrReadValue(
                byteArrayOf(0x00),
                setOf(KickrEvidenceLabel.SYN),
            )
            ZwiftUuids.UUID_ZWIFT_HUB_SUPPORT_READ,
            ZwiftUuids.UUID_ZWIFT_HUB_SUPPORT_NOTIFY
            -> KickrReadValue(byteArrayOf(0x00), setOf(KickrEvidenceLabel.SYN))
            WahooUuids.UUID_WAHOO_RIDEON_SYNC_TX ->
                KickrReadValue(byteArrayOf(), setOf(KickrEvidenceLabel.SYN))
            SigUuids.UUID_MANUFACTURER_NAME -> stringValue(
                identity.manufacturer ?: if (persona == KickrSimulatorPersona.ZWIFT_HUB_CAPTURED) {
                    "FitBridge Simulator"
                } else {
                    "Wahoo Fitness"
                },
                identity.evidence,
            )
            SigUuids.UUID_MODEL_NUMBER -> stringValue(
                if (persona == KickrSimulatorPersona.ZWIFT_HUB_CAPTURED) "Zwift Hub Simulator" else "Simulator",
                setOf(KickrEvidenceLabel.SYN),
            )
            SigUuids.UUID_SERIAL_NUMBER -> stringValue(
                when (persona) {
                    KickrSimulatorPersona.CORE_ORIGINAL_ZWIFT -> "SIM-C3FB"
                    KickrSimulatorPersona.CORE2_FC82_PROVISIONAL -> "SIM-1A2B"
                    KickrSimulatorPersona.ZWIFT_HUB_CAPTURED -> "SIM-HUB"
                    KickrSimulatorPersona.V5_STANDARD_LEGACY -> "SIM-AEE4"
                },
                setOf(KickrEvidenceLabel.SYN),
            )
            SigUuids.UUID_HARDWARE_REVISION -> stringValue(identity.hardwareRevision ?: "SIM", identity.evidence)
            SigUuids.UUID_FIRMWARE_REVISION -> stringValue(identity.firmwareRevision ?: "SIM.1", identity.evidence)
            // SIG Weight is uint16 at 0.005 kg resolution: 15,000 represents synthetic 75.0 kg.
            SigUuids.UUID_USER_WEIGHT -> KickrReadValue(u16Wrapping(15_000), setOf(KickrEvidenceLabel.SYN))
            else -> null
        }
    }

    private fun stringValue(value: String, evidence: Set<KickrEvidenceLabel>) =
        KickrReadValue(value.toByteArray(Charsets.UTF_8), evidence)
}

@SimulatorProbeApi
public data class KickrReadValue(
    val bytes: ByteArray,
    val evidence: Set<KickrEvidenceLabel>,
)

@SimulatorProbeApi
public object KickrFtmsCapabilities {
    public val VALUE: TargetCapabilities = TargetCapabilities(
        resistance = true,
        power = true,
        indoorBikeSimulation = true,
        wheelCircumference = true,
        // 0x2AD6 wire units are 0.1 level, so this advertises 1.0-100.0 in whole-level
        // steps. Wahoo's app writes the legacy UINT8 form with the level as a whole number
        // (5 / 15 / 25 / 35 observed on the wire), which the control point normalizes into
        // these units before the range check.
        resistanceRange = FtmsTargetRange(10, 1_000, 10),
        powerRange = FtmsTargetRange(0, 2_000, 1),
    )
}

internal class KickrSyntheticFtmsControlSink(
    private val model: KickrSyntheticModel,
) : FtmsControlSink {
    // Read per call, not captured: the simulator's "Accept target commands" switch is settled
    // before the run starts but the sink outlives any single write, and the GATT callback thread
    // is not the thread that set it.
    override val canControl: Boolean get() = model.ftmsControlEnabled
    override val targetCapabilities: TargetCapabilities = KickrFtmsCapabilities.VALUE
    override val clampsTargetsToAdvertisedRange: Boolean = false

    override fun requestControl(): Boolean = true
    override fun reset(): Boolean = model.reset()
    override fun startOrResume(): Boolean = model.startOrResume()
    override fun stopOrPause(stop: Boolean): Boolean = model.stopOrPause(stop)
    override fun setTargetSpeedKph(kph: Double): Boolean = false
    override fun setTargetInclinationPercent(percent: Double): Boolean = false
    override fun setTargetResistance(level: Double): Boolean =
        model.setResistance(level, KickrControlSource.FTMS)
    override fun setTargetPowerWatts(watts: Int): Boolean =
        model.setTargetPower(watts, KickrControlSource.FTMS)
    override fun setTargetHeartRate(bpm: Int): Boolean = false
    override fun setWheelCircumferenceMm(millimetres: Double): Boolean =
        model.setWheelCircumference(millimetres)

    override fun setIndoorBikeSimulation(
        windMps: Double,
        gradePercent: Double,
        crr: Double,
        cw: Double,
    ): Boolean = model.setSimulation(
        KickrSimulationTarget(windMps, gradePercent, crr, cw),
        KickrControlSource.FTMS,
    )
}

/** One pure FTMS procedure session; Android indication ordering is added in KS-S3. */
@SimulatorProbeApi
public class KickrFtmsControlProcessor(
    public val model: KickrSyntheticModel = KickrSyntheticModel(),
) {
    public val session: FtmsProcedureSession = FtmsProcedureSession()
    private val handler = FtmsControlPointHandler(KickrSyntheticFtmsControlSink(model))

    public fun handle(value: ByteArray): FtmsControlPointHandler.Outcome = handler.handle(value, session)

    public fun disconnect() {
        session.resetForDisconnect()
    }
}
