package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.FtmsControlPointHandler
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.FtmsControlSink
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.FtmsProcedureSession
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.FtmsTargetRange
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.TargetCapabilities
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.s16le
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.u16le
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.u32le
import java.util.UUID
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.roundToInt

internal enum class KickrControlMode { AUTONOMOUS, ERG, SIMULATION, RESISTANCE }

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
 * companion "was this a direct/gear target, an ERG power target, or a full simulation payload"
 * axis; the two together select one [SimulatorControlModeLabels] string.
 */
internal enum class KickrControlSource { NONE, FTMS, LEGACY_KICKR, RIDE_ON }

/** See [KickrControlSource]. DIRECT covers resistance/standard-level and virtual-gear writes. */
internal enum class KickrControlKind { DIRECT, ERG, SIMULATION }

internal data class KickrSimulationTarget(
    val windMps: Double = 0.0,
    val gradePercent: Double = 0.0,
    val crr: Double = 0.004,
    val cw: Double = 0.51,
    val totalMassKg: Double = CyclingSimulationPhysics.DEFAULT_TOTAL_MASS_KG,
)

/** One immutable state read by every standard and proprietary encoder. */
internal data class KickrMachineSnapshot(
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
internal class KickrSyntheticModel(
    initialWheelCircumferenceMm: Double = DEFAULT_WHEEL_CIRCUMFERENCE_MM,
) {
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
    fun startOrResume(): Boolean {
        running = true
        return true
    }

    @Synchronized
    fun stopOrPause(stop: Boolean): Boolean {
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
    fun reset(): Boolean {
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
    fun setTargetPower(watts: Int, source: KickrControlSource = KickrControlSource.NONE): Boolean {
        if (watts !in MIN_POWER_WATTS..MAX_POWER_WATTS) return false
        mode = KickrControlMode.ERG
        targetPowerWatts = watts
        controlSource = source
        controlKind = KickrControlKind.ERG
        return true
    }

    @Synchronized
    fun setResistance(level: Double, source: KickrControlSource = KickrControlSource.NONE): Boolean {
        if (!level.isFinite() || level !in MIN_RESISTANCE..MAX_RESISTANCE) return false
        mode = KickrControlMode.RESISTANCE
        resistanceLevel = level
        targetPowerWatts = null
        controlSource = source
        controlKind = KickrControlKind.DIRECT
        return true
    }

    @Synchronized
    fun setSimulation(
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
    fun setWheelCircumference(millimetres: Double): Boolean {
        if (!millimetres.isFinite() || millimetres !in MIN_WHEEL_CIRCUMFERENCE_MM..MAX_WHEEL_CIRCUMFERENCE_MM) {
            return false
        }
        wheelCircumferenceMm = millimetres
        return true
    }

    @Synchronized
    fun setVirtualGear(index: Int, ratio: Double): Boolean {
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
     * gear ratio happened to ride along. Only a bare virtual-shift command - gear change with no
     * power or simulation target - calls this.
     */
    @Synchronized
    fun markDirectControl(source: KickrControlSource) {
        controlSource = source
        controlKind = KickrControlKind.DIRECT
    }

    @Synchronized
    fun advance(dtSeconds: Double): KickrMachineSnapshot {
        if (!dtSeconds.isFinite() || dtSeconds <= 0.0 || dtSeconds > MAX_TICK_SECONDS) return snapshot()
        elapsedSeconds += dtSeconds
        if (!running) return snapshot()

        if (mode == KickrControlMode.SIMULATION) {
            speedKph = approach(
                speedKph,
                SIMULATION_SPEED_KPH,
                dtSeconds,
                SPEED_TIME_CONSTANT_SECONDS,
            )
            powerWatts = CyclingSimulationPhysics.requiredRiderPowerWatts(
                speedKph = speedKph,
                gradePercent = simulation.gradePercent,
                crr = simulation.crr,
                cwKgPerM = simulation.cw,
                windMps = simulation.windMps,
                totalMassKg = simulation.totalMassKg,
            ).coerceAtMost(MAX_POWER_WATTS.toDouble())
        }
        val desiredPower = when (mode) {
            KickrControlMode.AUTONOMOUS -> 160.0
            KickrControlMode.ERG -> targetPowerWatts?.toDouble() ?: 160.0
            KickrControlMode.RESISTANCE -> 45.0 + resistanceLevel * 14.0
            KickrControlMode.SIMULATION -> powerWatts
        }
        if (mode != KickrControlMode.SIMULATION) {
            powerWatts = approach(powerWatts, desiredPower, dtSeconds, POWER_TIME_CONSTANT_SECONDS)
        }
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
    fun snapshot(): KickrMachineSnapshot = KickrMachineSnapshot(
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

    companion object {
        const val MIN_POWER_WATTS = 0
        const val MAX_POWER_WATTS = 2_000
        const val MIN_RESISTANCE = 1.0
        const val MAX_RESISTANCE = 15.0
        const val MIN_WHEEL_CIRCUMFERENCE_MM = 0.1
        const val MAX_WHEEL_CIRCUMFERENCE_MM = 6_553.5
        const val DEFAULT_WHEEL_CIRCUMFERENCE_MM = 2_096.0
        const val MAX_TICK_SECONDS = 10.0
        const val DEFAULT_VIRTUAL_GEAR_INDEX = 12
        const val DEFAULT_GEAR_RATIO = 2.4
        private const val DEFAULT_RESISTANCE = 5.0
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

internal object KickrStandardEncoders {
    const val CPS_FLAGS_CAPTURE_SHAPE = 0x0034
    const val FTMS_FLAGS_CAPTURE_SHAPE = 0x0044

    val CORE_FTMS_FEATURE: ByteArray = byteArrayOf(0x03, 0x40, 0x00, 0x00, 0x0C, 0x60, 0x00, 0x00)
    val CPS_FEATURE: ByteArray = byteArrayOf(0x08, 0x00, 0x00, 0x00)
    val SENSOR_LOCATION: ByteArray = byteArrayOf(0x0D)
    val SUPPORTED_RESISTANCE_RANGE: ByteArray = u16le(10) + u16le(150) + u16le(10)
    val SUPPORTED_POWER_RANGE: ByteArray = s16le(0) + s16le(2_000) + u16le(1)

    fun cyclingPowerMeasurement(snapshot: KickrMachineSnapshot): ByteArray =
        u16le(CPS_FLAGS_CAPTURE_SHAPE) +
            s16le(snapshot.powerWatts.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())) +
            u16le(snapshot.accumulatedTorqueRaw) +
            u32le(snapshot.cumulativeWheelRevolutions.toInt()) +
            u16le(snapshot.lastWheelEventTicks) +
            u16le(snapshot.cumulativeCrankRevolutions) +
            u16le(snapshot.lastCrankEventTicks)

    fun indoorBikeData(snapshot: KickrMachineSnapshot): ByteArray =
        u16le(FTMS_FLAGS_CAPTURE_SHAPE) +
            u16le((snapshot.speedKph * 100.0).roundToInt().coerceIn(0, 0xFFFF)) +
            u16le((snapshot.cadenceRpm * 2.0).roundToInt().coerceIn(0, 0xFFFF)) +
            s16le(snapshot.powerWatts.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))

    fun readValue(
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
            BluetoothIds.UUID_FITNESS_MACHINE_FEATURE -> KickrReadValue(
                CORE_FTMS_FEATURE.copyOf(),
                standardEvidence,
            )
            BluetoothIds.UUID_TRAINING_STATUS -> KickrReadValue(trainingStatus.copyOf(), setOf(KickrEvidenceLabel.P))
            BluetoothIds.UUID_SUPPORTED_RESISTANCE_RANGE ->
                KickrReadValue(SUPPORTED_RESISTANCE_RANGE.copyOf(), setOf(KickrEvidenceLabel.LOG))
            BluetoothIds.UUID_SUPPORTED_POWER_RANGE ->
                KickrReadValue(SUPPORTED_POWER_RANGE.copyOf(), setOf(KickrEvidenceLabel.SYN))
            BluetoothIds.UUID_CYCLING_POWER_FEATURE -> KickrReadValue(CPS_FEATURE.copyOf(), setOf(KickrEvidenceLabel.LOG))
            BluetoothIds.UUID_SENSOR_LOCATION -> KickrReadValue(SENSOR_LOCATION.copyOf(), setOf(KickrEvidenceLabel.LOG))
            BluetoothIds.UUID_BODY_SENSOR_LOCATION -> KickrReadValue(
                byteArrayOf(0x00),
                setOf(KickrEvidenceLabel.SYN),
            )
            BluetoothIds.UUID_ZWIFT_HUB_SUPPORT_READ,
            BluetoothIds.UUID_ZWIFT_HUB_SUPPORT_NOTIFY
            -> KickrReadValue(byteArrayOf(0x00), setOf(KickrEvidenceLabel.SYN))
            BluetoothIds.UUID_WAHOO_RIDEON_SYNC_TX ->
                KickrReadValue(byteArrayOf(), setOf(KickrEvidenceLabel.SYN))
            BluetoothIds.UUID_MANUFACTURER_NAME -> stringValue(
                identity.manufacturer ?: if (persona == KickrSimulatorPersona.ZWIFT_HUB_CAPTURED) {
                    "FitBridge Simulator"
                } else {
                    "Wahoo Fitness"
                },
                identity.evidence,
            )
            BluetoothIds.UUID_MODEL_NUMBER -> stringValue(
                if (persona == KickrSimulatorPersona.ZWIFT_HUB_CAPTURED) "Zwift Hub Simulator" else "Simulator",
                setOf(KickrEvidenceLabel.SYN),
            )
            BluetoothIds.UUID_SERIAL_NUMBER -> stringValue(
                when (persona) {
                    KickrSimulatorPersona.CORE_ORIGINAL_ZWIFT -> "SIM-C3FB"
                    KickrSimulatorPersona.CORE2_FC82_PROVISIONAL -> "SIM-1A2B"
                    KickrSimulatorPersona.ZWIFT_HUB_CAPTURED -> "SIM-HUB"
                    KickrSimulatorPersona.V5_STANDARD_LEGACY -> "SIM-AEE4"
                },
                setOf(KickrEvidenceLabel.SYN),
            )
            BluetoothIds.UUID_HARDWARE_REVISION -> stringValue(identity.hardwareRevision ?: "SIM", identity.evidence)
            BluetoothIds.UUID_FIRMWARE_REVISION -> stringValue(identity.firmwareRevision ?: "SIM.1", identity.evidence)
            // SIG Weight is uint16 at 0.005 kg resolution: 15,000 represents synthetic 75.0 kg.
            BluetoothIds.UUID_USER_WEIGHT -> KickrReadValue(u16le(15_000), setOf(KickrEvidenceLabel.SYN))
            else -> null
        }
    }

    private fun stringValue(value: String, evidence: Set<KickrEvidenceLabel>) =
        KickrReadValue(value.toByteArray(Charsets.UTF_8), evidence)
}

internal data class KickrReadValue(
    val bytes: ByteArray,
    val evidence: Set<KickrEvidenceLabel>,
)

internal object KickrFtmsCapabilities {
    val VALUE = TargetCapabilities(
        resistance = true,
        power = true,
        indoorBikeSimulation = true,
        wheelCircumference = true,
        resistanceRange = FtmsTargetRange(10, 150, 10),
        powerRange = FtmsTargetRange(0, 2_000, 1),
    )
}

internal class KickrSyntheticFtmsControlSink(
    private val model: KickrSyntheticModel,
) : FtmsControlSink {
    override val canControl: Boolean = true
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
internal class KickrFtmsControlProcessor(
    val model: KickrSyntheticModel = KickrSyntheticModel(),
) {
    val session = FtmsProcedureSession()
    private val handler = FtmsControlPointHandler(KickrSyntheticFtmsControlSink(model))

    fun handle(value: ByteArray): FtmsControlPointHandler.Outcome = handler.handle(value, session)

    fun disconnect() {
        session.resetForDisconnect()
    }
}
