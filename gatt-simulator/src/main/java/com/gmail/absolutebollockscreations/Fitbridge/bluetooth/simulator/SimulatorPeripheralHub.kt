package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.FtmsControlSink
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.FtmsPeripheralController
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.ManagedPeripheralGroup
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.SimulatorHostEnvironment
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.TargetCapabilities
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The debug data simulator's peripherals (LIB-9a, S9 part 1).
 *
 * Moved verbatim from `BtServiceManager`: the ten per-family simulator controllers, their
 * active-persona flags, the [FtmsSimulator.SimulatorPeripheralHost] the simulator drives, and the
 * per-family start helpers. The manager is reached only through [SimulatorHostEnvironment], and
 * the manager reaches this hub only through [ManagedPeripheralGroup]; both contracts live in
 * gatt-peripheral so neither side imports the other's package.
 *
 * Former manager-level documentation for the host:
 *
 * Peripheral operations the debug data simulator needs.
 *
 * Exposed as a small adapter rather than handing the simulator the whole
 * controller, so the simulator cannot start a second GATT server or reach
 * around the manager's permission gating.
 */
class SimulatorPeripheralHub(
    context: Context,
    private val environment: SimulatorHostEnvironment,
) : FtmsSimulator.SimulatorPeripheralHost, ManagedPeripheralGroup {

    private companion object {
        // Same value as BtServiceManager's, so start timing is unchanged.
        const val START_TIMEOUT_MS = 10_000L
    }

    @Volatile private var simulatorPm5Active = false
    @Volatile private var simulatorKeiserActive = false
    @Volatile private var simulatorPrecorActive = false
    @Volatile private var simulatorFlywheelActive = false
    @Volatile private var simulatorKayakFirstActive = false
    @Volatile private var simulatorStrydActive = false
    @Volatile private var simulatorLifeFitnessActive = false
    @Volatile private var simulatorSchwinnX70Active = false
    @Volatile private var simulatorFeCBleActive = false
    @Volatile private var simulatorKickrActive = false
    private val concept2Controller = Concept2Pm5PeripheralController(
        context = context.applicationContext,
        onStatus = { status ->
            if (simulatorPm5Active) {
                environment.peripheralStatus.value = FtmsPeripheralController.PeripheralStatus(
                    advertising = status.advertising,
                    connected = status.connected,
                    subscribed = status.subscribed,
                    machineType = status.machineType,
                    advertisedName = status.advertisedName,
                    packetsSent = status.packetsSent,
                    completeRecords = status.packetsSent,
                    lastError = status.lastError,
                )
            }
        },
    )
    private val keiserController = KeiserMSeriesPeripheralController(
        context = context.applicationContext,
        onStatus = { status ->
            if (simulatorKeiserActive) {
                environment.peripheralStatus.value = FtmsPeripheralController.PeripheralStatus(
                    advertising = status.advertising,
                    connected = false,
                    subscribed = false,
                    machineType = "Keiser M-series · advertisement only",
                    advertisedName = status.advertisedName,
                    packetsSent = status.framesApplied,
                    completeRecords = status.framesApplied,
                    lastError = status.lastError,
                )
            }
        },
    )
    private val precorController = PrecorChronoPeripheralController(
        context = context.applicationContext,
        onStatus = { status ->
            if (simulatorPrecorActive) {
                environment.peripheralStatus.value = FtmsPeripheralController.PeripheralStatus(
                    advertising = status.advertising,
                    connected = status.connected,
                    subscribed = status.subscribed,
                    machineType = "Precor Spinner Chrono Power",
                    advertisedName = status.advertisedName,
                    packetsSent = status.packetsSent,
                    completeRecords = status.packetsSent,
                    lastError = status.lastError,
                )
            }
        },
    )
    private val flywheelController = FlywheelSportsPeripheralController(
        context = context.applicationContext,
        onStatus = { status ->
            if (simulatorFlywheelActive) {
                environment.peripheralStatus.value = FtmsPeripheralController.PeripheralStatus(
                    advertising = status.advertising,
                    connected = status.connected,
                    subscribed = status.subscribed,
                    machineType = "Flywheel Sports · measurements only",
                    advertisedName = status.advertisedName,
                    packetsSent = status.packetsSent,
                    completeRecords = status.packetsSent,
                    lastError = status.lastError,
                )
            }
        },
    )
    private val kayakFirstController = KayakFirstPeripheralController(
        context = context.applicationContext,
        onStatus = { status ->
            if (simulatorKayakFirstActive) {
                environment.peripheralStatus.value = FtmsPeripheralController.PeripheralStatus(
                    advertising = status.advertising,
                    connected = status.connected,
                    subscribed = status.subscribed,
                    simulatorGenerationReady = status.generationReady,
                    simulatorGenerationAdvancing = status.generationAdvancing,
                    machineType = if (status.advertisedName == SimulatorPersona.KayakFirstOrange.advertisedName) {
                        "KayakFirst Orange · timing MCU · measurements only"
                    } else {
                        "KayakFirst Blue · session control only"
                    },
                    advertisedName = status.advertisedName,
                    packetsSent = status.packetsSent,
                    completeRecords = status.completeRecords,
                    lastError = status.lastError,
                    kayakFirstSetupStage = status.setupStage,
                    kayakFirstLastCommand = status.lastCommand,
                    kayakFirstDisplayCodes = status.displayConfiguration.slots.map { it.wireCode },
                    kayakFirstPollRequests = status.pollRequests,
                    kayakFirstMalformedCommands = status.malformedCommands,
                    kayakFirstQueuedFragments = status.queuedFragments,
                    kayakFirstDroppedRecords = status.droppedRecords,
                )
            }
        },
    )
    private val strydController = StrydFootpodPeripheralController(
        context = context.applicationContext,
        onStatus = { status ->
            if (simulatorStrydActive) {
                environment.peripheralStatus.value = FtmsPeripheralController.PeripheralStatus(
                    advertising = status.advertising,
                    connected = status.connected,
                    subscribed = status.subscribed,
                    machineType = "Stryd running footpod",
                    advertisedName = status.advertisedName,
                    packetsSent = status.packetsSent,
                    completeRecords = status.packetsSent,
                    lastError = status.lastError,
                )
            }
        },
    )
    private val schwinnX70Controller = SchwinnX70PeripheralController(
        context = context,
        onStatus = { status ->
            if (simulatorSchwinnX70Active) {
                environment.peripheralStatus.value = environment.peripheralStatus.value.copy(
                    advertising = status.advertising,
                    connected = status.connected,
                    subscribed = status.subscribed,
                    packetsSent = status.packetsSent,
                    completeRecords = status.packetsSent,
                    lastError = status.lastError,
                )
            }
        },
    )

    /**
     * FE-C-over-BLE simulator (plan `docs/fe-c-ble_simulator_implementation.md`). Diagnostics are
     * mapped onto the shared [FtmsPeripheralController.PeripheralStatus] shape, same as every other
     * simulator-only controller here. Unlike the other controllers, this one also has a live
     * synthetic model of its own (speed/cadence/power/distance/elapsed, control mode, target
     * power/resistance/grade) that no `FtmsMockEngine`-backed `lastSample` ever carries for this
     * persona, so those fields are copied across explicitly rather than left null.
     */
    private val feCBleController = FeCBlePeripheralController(
        context = context.applicationContext,
        onStatus = { status ->
            if (simulatorFeCBleActive) {
                environment.peripheralStatus.value = FtmsPeripheralController.PeripheralStatus(
                    advertising = status.advertising,
                    connected = status.connectedClients > 0,
                    subscribed = status.subscribedClients > 0,
                    machineType = status.equipmentType?.let { "FE-C over BLE \u00b7 $it \u00b7 ${status.feState ?: "waiting"}" },
                    advertisedName = status.advertisedName,
                    packetsSent = status.packetsSent,
                    completeRecords = status.packetsSent,
                    lastError = status.lastError,
                    fecFeState = status.feState,
                    fecControlMode = status.controlMode,
                    fecSpeedKph = status.speedKph,
                    fecCadenceRpm = status.cadenceRpm,
                    fecPowerWatts = status.powerWatts,
                    fecDistanceMeters = status.distanceMeters,
                    fecElapsedSeconds = status.elapsedSeconds,
                    fecIntervalName = status.intervalName,
                    fecIntervalSecondsRemaining = status.intervalSecondsRemaining,
                    fecIntervalIntensityPercent = status.intervalIntensityPercent,
                    fecHeartRateBpm = status.heartRateBpm,
                    fecCycleLengthMeters = status.cycleLengthMeters,
                    fecInclinePercent = status.inclinePercent,
                    fecResistanceLevelPercent = status.resistanceLevelPercent,
                    fecMetabolicEquivalent = status.metabolicEquivalent,
                    fecCaloricBurnRateKcalPerHour = status.caloricBurnRateKcalPerHour,
                    fecAccumulatedCaloriesKcal = status.accumulatedCaloriesKcal,
                    fecMovementCount = status.movementCount,
                    fecPositiveVerticalDistanceMeters = status.positiveVerticalDistanceMeters,
                    fecNegativeVerticalDistanceMeters = status.negativeVerticalDistanceMeters,
                    fecWheelTorqueNewtonMeters = status.wheelTorqueNewtonMeters,
                    fecTargetPowerWatts = status.targetPowerWatts,
                    fecBasicResistancePercent = status.basicResistancePercent,
                    fecGradePercent = status.gradePercent,
                    fecCrr = status.crr,
                    fecWindSpeedMps = status.windSpeedMps,
                    fecWindCoefficient = status.windCoefficient,
                    fecWindDraftingFactor = status.windDraftingFactor,
                )
            }
        },
    )

    private val lifeFitnessController = LifeFitnessPeripheralController(
        context = context.applicationContext,
        onStatus = { status ->
            if (simulatorLifeFitnessActive) {
                val persona = simulatorLifeFitnessPersona
                environment.peripheralStatus.value = FtmsPeripheralController.PeripheralStatus(
                    advertising = status.advertising,
                    connected = status.connected,
                    subscribed = status.subscribed,
                    ftmsSubscribed = status.statusSubscribed,
                    machineType = persona?.let {
                        "Life Fitness ${it.machine.displayName} · ${status.setupStage?.name ?: "waiting"}"
                    },
                    advertisedName = status.advertisedName,
                    packetsSent = status.packetsSent,
                    completeRecords = status.packetsSent,
                    lastError = status.lastError,
                )
            }
        },
    )
    @Volatile private var simulatorLifeFitnessPersona: SimulatorPersona.LifeFitness? = null

    private val kickrController = KickrPeripheralController(
        context = context.applicationContext,
        onStatus = { status ->
            if (simulatorKickrActive) {
                environment.peripheralStatus.value = FtmsPeripheralController.PeripheralStatus(
                    advertising = status.advertising,
                    connected = status.connected,
                    subscribed = status.subscribed,
                    machineType = status.persona?.displayLabel,
                    advertisedName = status.advertisedName,
                    packetsSent = status.packetsSent,
                    completeRecords = status.packetsSent,
                    lastError = status.lastError,
                    kickrEvidence = status.evidence,
                    kickrRoutes = status.routes,
                    kickrProtocolStages = status.protocolStages,
                    kickrControlMode = status.controlMode,
                    kickrSpeedKph = status.speedKph,
                    kickrCadenceRpm = status.cadenceRpm,
                    kickrPowerWatts = status.powerWatts,
                    kickrHeartRateBpm = status.heartRateBpm,
                    kickrVirtualGearIndex = status.virtualGearIndex,
                    kickrGearRatio = status.gearRatio,
                    kickrResistanceLevel = status.resistanceLevel,
                    kickrPendingFrames = status.pendingFrames,
                    kickrDroppedFrames = status.droppedFrames,
                    kickrRejectedWrites = status.rejectedWrites,
                    kickrSimWindMps = status.simWindMps,
                    kickrSimGradePercent = status.simGradePercent,
                    kickrSimCrr = status.simCrr,
                    kickrSimCw = status.simCw,
                )
            }
        },
    )


    @RequiresPermission(
        allOf = [
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
        ],
    )
    override fun startAdvertising(
        persona: SimulatorPersona,
        targets: TargetCapabilities,
    ): Boolean {
        if (persona != SimulatorPersona.KayakFirstBlue &&
            persona != SimulatorPersona.KayakFirstOrange
        ) {
            kayakFirstController.stop()
            simulatorKayakFirstActive = false
        }
        when (persona) {
            is SimulatorPersona.Concept2Pm5 -> {
                if (!environment.canAdvertise()) return false
                environment.ftmsController.stop()
                keiserController.stop()
        precorController.stop()
        flywheelController.stop()
        strydController.stop()
                lifeFitnessController.stop()
                schwinnX70Controller.stop()
                feCBleController.stop()
                kickrController.stop()
                simulatorPm5Active = true
                simulatorKeiserActive = false
        simulatorPrecorActive = false
        simulatorFlywheelActive = false
        simulatorStrydActive = false
                simulatorLifeFitnessActive = false
                simulatorLifeFitnessPersona = null
                simulatorSchwinnX70Active = false
                simulatorFeCBleActive = false
                simulatorKickrActive = false
                return awaitConcept2Start(persona)
            }
            is SimulatorPersona.KeiserMSeries -> {
                if (!environment.canAdvertise()) return false
                environment.ftmsController.stop()
                concept2Controller.stop()
                precorController.stop()
                flywheelController.stop()
                strydController.stop()
                lifeFitnessController.stop()
                schwinnX70Controller.stop()
                feCBleController.stop()
                kickrController.stop()
                simulatorPm5Active = false
                simulatorKeiserActive = true
                simulatorPrecorActive = false
                simulatorFlywheelActive = false
                simulatorStrydActive = false
                simulatorLifeFitnessActive = false
                simulatorLifeFitnessPersona = null
                simulatorSchwinnX70Active = false
                simulatorFeCBleActive = false
                simulatorKickrActive = false
                return awaitKeiserStart(persona)
            }
            SimulatorPersona.PrecorChrono -> {
                if (!environment.canAdvertise()) return false
                environment.ftmsController.stop()
                concept2Controller.stop()
                keiserController.stop()
                flywheelController.stop()
                strydController.stop()
                lifeFitnessController.stop()
                schwinnX70Controller.stop()
                feCBleController.stop()
                kickrController.stop()
                simulatorPm5Active = false
                simulatorKeiserActive = false
                simulatorPrecorActive = true
                simulatorFlywheelActive = false
                simulatorStrydActive = false
                simulatorLifeFitnessActive = false
                simulatorLifeFitnessPersona = null
                simulatorSchwinnX70Active = false
                simulatorFeCBleActive = false
                simulatorKickrActive = false
                return awaitPrecorStart()
            }
            SimulatorPersona.FlywheelSports -> {
                if (!environment.canAdvertise()) return false
                environment.ftmsController.stop()
                concept2Controller.stop()
                keiserController.stop()
                precorController.stop()
                flywheelController.stop()
                strydController.stop()
                lifeFitnessController.stop()
                schwinnX70Controller.stop()
                feCBleController.stop()
                kickrController.stop()
                simulatorPm5Active = false
                simulatorKeiserActive = false
                simulatorPrecorActive = false
                simulatorFlywheelActive = true
                simulatorStrydActive = false
                simulatorLifeFitnessActive = false
                simulatorLifeFitnessPersona = null
                simulatorSchwinnX70Active = false
                simulatorFeCBleActive = false
                simulatorKickrActive = false
                return awaitFlywheelStart()
            }
            SimulatorPersona.KayakFirstBlue,
            SimulatorPersona.KayakFirstOrange,
            -> {
                if (!environment.canAdvertise()) return false
                environment.ftmsController.stop()
                concept2Controller.stop()
                keiserController.stop()
                precorController.stop()
                flywheelController.stop()
                strydController.stop()
                lifeFitnessController.stop()
                schwinnX70Controller.stop()
                feCBleController.stop()
                kickrController.stop()
                simulatorPm5Active = false
                simulatorKeiserActive = false
                simulatorPrecorActive = false
                simulatorFlywheelActive = false
                simulatorStrydActive = false
                simulatorLifeFitnessActive = false
                simulatorLifeFitnessPersona = null
                simulatorSchwinnX70Active = false
                simulatorFeCBleActive = false
                simulatorKickrActive = false
                simulatorKayakFirstActive = true
                return awaitKayakFirstStart(persona)
            }
            SimulatorPersona.StrydFootpod -> {
                if (!environment.canAdvertise()) return false
                environment.ftmsController.stop()
                concept2Controller.stop()
                keiserController.stop()
                precorController.stop()
                flywheelController.stop()
                lifeFitnessController.stop()
                schwinnX70Controller.stop()
                feCBleController.stop()
                kickrController.stop()
                simulatorPm5Active = false
                simulatorKeiserActive = false
                simulatorPrecorActive = false
                simulatorFlywheelActive = false
                simulatorStrydActive = true
                simulatorLifeFitnessActive = false
                simulatorLifeFitnessPersona = null
                simulatorSchwinnX70Active = false
                simulatorFeCBleActive = false
                simulatorKickrActive = false
                return awaitStrydStart()
            }
            is SimulatorPersona.LifeFitness -> {
                if (!environment.canAdvertise()) return false
                environment.ftmsController.stop()
                concept2Controller.stop()
                keiserController.stop()
                precorController.stop()
                flywheelController.stop()
                strydController.stop()
                schwinnX70Controller.stop()
                feCBleController.stop()
                kickrController.stop()
                simulatorPm5Active = false
                simulatorKeiserActive = false
                simulatorPrecorActive = false
                simulatorFlywheelActive = false
                simulatorStrydActive = false
                simulatorLifeFitnessActive = true
                simulatorLifeFitnessPersona = persona
                simulatorSchwinnX70Active = false
                simulatorFeCBleActive = false
                simulatorKickrActive = false
                return awaitLifeFitnessStart(persona)
            }
            is SimulatorPersona.SchwinnX70 -> {
                if (!environment.canAdvertise()) return false
                environment.ftmsController.stop()
                concept2Controller.stop()
                keiserController.stop()
                precorController.stop()
                flywheelController.stop()
                strydController.stop()
                lifeFitnessController.stop()
                feCBleController.stop()
                kickrController.stop()
                simulatorPm5Active = false
                simulatorKeiserActive = false
                simulatorPrecorActive = false
                simulatorFlywheelActive = false
                simulatorStrydActive = false
                simulatorLifeFitnessActive = false
                simulatorLifeFitnessPersona = null
                simulatorSchwinnX70Active = true
                simulatorFeCBleActive = false
                simulatorKickrActive = false
                return awaitSchwinnX70Start(persona)
            }
            is SimulatorPersona.FeCBle -> {
                if (!environment.canAdvertise()) return false
                environment.ftmsController.stop()
                concept2Controller.stop()
                keiserController.stop()
                precorController.stop()
                flywheelController.stop()
                strydController.stop()
                lifeFitnessController.stop()
                schwinnX70Controller.stop()
                kickrController.stop()
                simulatorPm5Active = false
                simulatorKeiserActive = false
                simulatorPrecorActive = false
                simulatorFlywheelActive = false
                simulatorStrydActive = false
                simulatorLifeFitnessActive = false
                simulatorLifeFitnessPersona = null
                simulatorSchwinnX70Active = false
                simulatorFeCBleActive = true
                simulatorKickrActive = false
                return awaitFeCBleStart(persona)
            }
            is SimulatorPersona.Kickr -> {
                // This second gate is intentional: release code cannot start the server
                // even if a caller constructs a persona without going through the UI.
                if (!environment.kickrRuntimeAllowed || !environment.canAdvertise()) return false
                environment.ftmsController.stop()
                concept2Controller.stop()
                keiserController.stop()
                precorController.stop()
                flywheelController.stop()
                strydController.stop()
                lifeFitnessController.stop()
                schwinnX70Controller.stop()
                feCBleController.stop()
                simulatorPm5Active = false
                simulatorKeiserActive = false
                simulatorPrecorActive = false
                simulatorFlywheelActive = false
                simulatorStrydActive = false
                simulatorLifeFitnessActive = false
                simulatorLifeFitnessPersona = null
                simulatorSchwinnX70Active = false
                simulatorFeCBleActive = false
                simulatorKickrActive = true
                return awaitKickrStart(persona)
            }
            SimulatorPersona.IndoorBikeSpeedAndPower -> Unit
            is SimulatorPersona.Ftms -> Unit
        }
        val surface = requireNotNull(persona.standardSurface())
        val profile = surface.profile
        concept2Controller.stop()
        keiserController.stop()
        precorController.stop()
        flywheelController.stop()
        strydController.stop()
        lifeFitnessController.stop()
        schwinnX70Controller.stop()
        feCBleController.stop()
        kickrController.stop()
        simulatorPm5Active = false
        simulatorKeiserActive = false
        simulatorPrecorActive = false
        simulatorFlywheelActive = false
        simulatorStrydActive = false
        simulatorLifeFitnessActive = false
        simulatorLifeFitnessPersona = null
        simulatorSchwinnX70Active = false
        simulatorFeCBleActive = false
        simulatorKickrActive = false
        return environment.startStandardPersona(
            profile = profile,
            nativeBikeSensors = surface.nativeBikeSensors,
            targets = targets,
            advertisedMachineType = surface.advertisedMachineType,
        )
    }

    @RequiresPermission(
        allOf = [
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
        ],
    )
    override fun stopAdvertising() {
        environment.ftmsController.stop()
        concept2Controller.stop()
        keiserController.stop()
                precorController.stop()
                flywheelController.stop()
                kayakFirstController.stop()
                strydController.stop()
        lifeFitnessController.stop()
        schwinnX70Controller.stop()
        feCBleController.stop()
        kickrController.stop()
        simulatorPm5Active = false
        simulatorKeiserActive = false
                simulatorPrecorActive = false
        simulatorFlywheelActive = false
        simulatorKayakFirstActive = false
                simulatorStrydActive = false
        simulatorLifeFitnessActive = false
        simulatorLifeFitnessPersona = null
        simulatorSchwinnX70Active = false
        simulatorFeCBleActive = false
        simulatorKickrActive = false
    }

    override fun setControlOverride(sink: FtmsControlSink?) {
        environment.ftmsController.setControlOverride(sink)
    }

    override fun publishConcept2(snapshot: Concept2Pm5Snapshot) {
        concept2Controller.publish(snapshot)
    }

    override fun resetConcept2WorkoutState() {
        if (simulatorPm5Active) concept2Controller.resetWorkoutState()
    }

    override val concept2WorkoutProgram: Concept2WorkoutProgram?
        get() = if (simulatorPm5Active) concept2Controller.workoutProgram() else null

    override fun consumeConcept2WorkoutTerminationRequest(): Boolean =
        simulatorPm5Active && concept2Controller.consumeWorkoutTerminationRequest()

    override fun publishPrecor(sample: FtmsMockSample) {
        precorController.publish(sample)
    }

    override fun publishFlywheel(sample: FtmsMockSample) {
        if (simulatorFlywheelActive) flywheelController.publish(sample)
    }

    override fun publishKayakFirst(sample: FtmsMockSample) {
        if (simulatorKayakFirstActive) kayakFirstController.publish(sample)
    }

    override fun publishStrydFootpod(
        sample: FtmsMockSample,
    ) {
        // Gated on the active flag for the same reason publishSchwinnX70 is: a tick still
        // in flight when the user switches persona must not reach a controller that is no
        // longer advertising.
        if (simulatorStrydActive) strydController.publish(sample)
    }

    override fun publishKeiser(manufacturerPayload: ByteArray?) {
        if (simulatorKeiserActive) keiserController.publish(manufacturerPayload)
    }

    override fun publishLifeFitness(sample: FtmsMockSample) {
        lifeFitnessController.publish(sample)
    }

    override fun publishSchwinnX70(frames: List<Pair<java.util.UUID, ByteArray>>) {
        // Gated on the active flag rather than published unconditionally. A tick in flight
        // when the user switches persona would otherwise reach a controller that is no
        // longer the one advertising — and a write-only flag would be the
        // declared-but-unreachable shape §11 obligation 0 exists to catch.
        if (simulatorSchwinnX70Active) schwinnX70Controller.publish(frames)
    }

    override fun sendZwiftClickShift(
        direction: ZwiftClickDirection,
    ): Boolean = if (simulatorKickrActive) kickrController.sendZwiftClickShift(direction) else false

    override val generationReady: Boolean
        get() = simulatorGenerationReady(
            isKeiser = simulatorKeiserActive,
            isKayakFirst = simulatorKayakFirstActive,
            subscribed = environment.peripheralStatus.value.subscribed,
            kayakFirstSetupReady = environment.peripheralStatus.value.simulatorGenerationReady,
        )

    override val generationAdvancing: Boolean
        get() = if (simulatorKayakFirstActive) {
            environment.peripheralStatus.value.simulatorGenerationAdvancing
        } else {
            generationReady
        }

    override val isAdvertising: Boolean
        get() = environment.ftmsController.isAdvertising || concept2Controller.isAdvertising || keiserController.isAdvertising ||
            precorController.isAdvertising || strydController.isAdvertising ||
            flywheelController.isAdvertising || kayakFirstController.isAdvertising ||
            lifeFitnessController.isAdvertising || schwinnX70Controller.isAdvertising ||
            feCBleController.isAdvertising || kickrController.isAdvertising

    private fun awaitConcept2Start(persona: SimulatorPersona.Concept2Pm5): Boolean {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<Unit>>()
        concept2Controller.start(persona) { startResult -> result.set(startResult); latch.countDown() }
        if (!latch.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            concept2Controller.stop()
            environment.peripheralStatus.value = FtmsPeripheralController.PeripheralStatus(lastError = "PM5 advertising start timed out")
            return false
        }
        return result.get()?.isSuccess == true
    }

    private fun awaitPrecorStart(): Boolean {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<Unit>>()
        precorController.start { startResult -> result.set(startResult); latch.countDown() }
        if (!latch.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            precorController.stop()
            flywheelController.stop()
            strydController.stop()
            environment.peripheralStatus.value = FtmsPeripheralController.PeripheralStatus(
                lastError = "Precor Chrono advertising start timed out",
            )
            return false
        }
        return result.get()?.isSuccess == true
    }

    private fun awaitFlywheelStart(): Boolean {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<Unit>>()
        flywheelController.start { startResult -> result.set(startResult); latch.countDown() }
        if (!latch.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            flywheelController.stop()
            environment.peripheralStatus.value = FtmsPeripheralController.PeripheralStatus(
                lastError = "Flywheel advertising start timed out",
            )
            return false
        }
        return result.get()?.isSuccess == true
    }

    private fun awaitKayakFirstStart(persona: SimulatorPersona): Boolean {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<Unit>>()
        kayakFirstController.start(persona) { startResult -> result.set(startResult); latch.countDown() }
        if (!latch.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            kayakFirstController.stop()
            environment.peripheralStatus.value = FtmsPeripheralController.PeripheralStatus(
                lastError = "KayakFirst advertising start timed out",
            )
            return false
        }
        return result.get()?.isSuccess == true
    }

    private fun awaitStrydStart(): Boolean {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<Unit>>()
        strydController.start { startResult -> result.set(startResult); latch.countDown() }
        if (!latch.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            strydController.stop()
            environment.peripheralStatus.value = FtmsPeripheralController.PeripheralStatus(
                lastError = "Stryd footpod advertising start timed out",
            )
            return false
        }
        return result.get()?.isSuccess == true
    }

    private fun awaitKeiserStart(persona: SimulatorPersona.KeiserMSeries): Boolean {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<Unit>>()
        keiserController.start(persona) { startResult -> result.set(startResult); latch.countDown() }
        if (!latch.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            keiserController.stop()
            environment.peripheralStatus.value = FtmsPeripheralController.PeripheralStatus(
                lastError = "Keiser M-series advertising start timed out",
            )
            return false
        }
        return result.get()?.isSuccess == true
    }

    private fun awaitSchwinnX70Start(persona: SimulatorPersona.SchwinnX70): Boolean {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<Unit>>()
        schwinnX70Controller.start { startResult ->
            result.set(startResult)
            latch.countDown()
        }
        if (!latch.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            schwinnX70Controller.stop()
            feCBleController.stop()
            environment.peripheralStatus.value = FtmsPeripheralController.PeripheralStatus(
                lastError = "Schwinn x70 advertising start timed out",
            )
            return false
        }
        return result.get()?.isSuccess == true
    }

    private fun awaitFeCBleStart(persona: SimulatorPersona.FeCBle): Boolean {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<Unit>>()
        feCBleController.start(persona) { startResult ->
            result.set(startResult)
            latch.countDown()
        }
        if (!latch.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            feCBleController.stop()
            environment.peripheralStatus.value = FtmsPeripheralController.PeripheralStatus(
                lastError = "FE-C over BLE advertising start timed out",
            )
            return false
        }
        return result.get()?.isSuccess == true
    }

    private fun awaitLifeFitnessStart(persona: SimulatorPersona.LifeFitness): Boolean {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<Unit>>()
        lifeFitnessController.start(persona.machine) { startResult ->
            result.set(startResult)
            latch.countDown()
        }
        if (!latch.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            lifeFitnessController.stop()
            schwinnX70Controller.stop()
            feCBleController.stop()
            environment.peripheralStatus.value = FtmsPeripheralController.PeripheralStatus(
                lastError = "Life Fitness advertising start timed out",
            )
            return false
        }
        return result.get()?.isSuccess == true
    }

    private fun awaitKickrStart(persona: SimulatorPersona.Kickr): Boolean {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<Unit>>()
        kickrController.start(
            persona.device,
            ftmsControlEnabled = persona.controlEnabled,
        ) { startResult ->
            result.set(startResult)
            latch.countDown()
        }
        if (!latch.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            kickrController.stop()
            environment.peripheralStatus.value = FtmsPeripheralController.PeripheralStatus(
                lastError = "KICKR advertising start timed out",
            )
            return false
        }
        return result.get()?.isSuccess == true
    }

    private fun clearSimulatorControllerSelection() {
        simulatorPm5Active = false
        simulatorKeiserActive = false
        simulatorPrecorActive = false
        simulatorFlywheelActive = false
        simulatorKayakFirstActive = false
        simulatorStrydActive = false
        simulatorLifeFitnessActive = false
        simulatorLifeFitnessPersona = null
        simulatorSchwinnX70Active = false
        simulatorFeCBleActive = false
        simulatorKickrActive = false
    }

    // ── ManagedPeripheralGroup ────────────────────────────────────────────────────────────

    // All ten family controllers (L17). Before LIB-9a the manager's own check omitted Flywheel
    // and Kayak First while the host's check below included them; they now agree.
    override val anyAdvertising: Boolean
        get() = concept2Controller.isAdvertising || keiserController.isAdvertising ||
            precorController.isAdvertising || strydController.isAdvertising ||
            flywheelController.isAdvertising || kayakFirstController.isAdvertising ||
            lifeFitnessController.isAdvertising || schwinnX70Controller.isAdvertising ||
            feCBleController.isAdvertising || kickrController.isAdvertising

    override fun stopAll() {
        concept2Controller.stop()
        keiserController.stop()
        precorController.stop()
        flywheelController.stop()
        kayakFirstController.stop()
        strydController.stop()
        lifeFitnessController.stop()
        schwinnX70Controller.stop()
        feCBleController.stop()
        kickrController.stop()
        clearSimulatorControllerSelection()
    }

    override fun disposeAll() {
        concept2Controller.dispose()
        keiserController.dispose()
        precorController.dispose()
        flywheelController.dispose()
        kayakFirstController.dispose()
        strydController.dispose()
        lifeFitnessController.dispose()
        schwinnX70Controller.dispose()
        feCBleController.dispose()
        kickrController.dispose()
        clearSimulatorControllerSelection()
    }
}
