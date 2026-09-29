package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicLong
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.AdapterNameCoordinator
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.CyclingEventAccumulator
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.CyclingSensorConfig
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.CyclingSensorEncoders
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.FtmsForegroundService
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.advertisedName

/**
 * Lifecycle, adapter-name, scheduler, and diagnostics owner for the FE-C-over-BLE simulator
 * (plan §16).
 *
 * Unlike the other simulator personas, this controller owns its own coherent [FeCBleMachineState]
 * and 250 ms [FeCBleTelemetryScheduler] tick loop rather than being driven by [FtmsMockEngine] —
 * the plan's synthetic model (§12) is self-contained and ERG/SIM convergence needs to answer FEC3
 * writes on the same state the scheduler is reading, which a shared engine snapshot cannot give it
 * (plan §4: architecture diagram, `FeCBlePeripheralController` owns state/model/scheduler).
 */
internal class FeCBlePeripheralController(
    private val context: Context,
    private val onStatus: (Status) -> Unit = {},
) : FeCBlePeripheralAdvertiser.Listener {

    data class Status(
        val advertising: Boolean = false,
        val connectedClients: Int = 0,
        val subscribedClients: Int = 0,
        val advertisedName: String? = null,
        val equipmentType: String? = null,
        val feState: String? = null,
        val controlMode: String? = null,
        val targetPowerWatts: Int? = null,
        val basicResistancePercent: Int? = null,
        val gradePercent: Double? = null,
        val crr: Double? = null,
        /** Page 50 fields, `null` until a Wind Resistance write has been decoded this session. */
        val windSpeedMps: Double? = null,
        val windCoefficient: Double? = null,
        val windDraftingFactor: Double? = null,
        val speedKph: Double? = null,
        val cadenceRpm: Int? = null,
        val powerWatts: Int? = null,
        val distanceMeters: Int? = null,
        val elapsedSeconds: Int? = null,
        val intervalName: String? = null,
        val intervalSecondsRemaining: Int? = null,
        val intervalIntensityPercent: Int? = null,
        val heartRateBpm: Int? = null,
        val cycleLengthMeters: Double? = null,
        val inclinePercent: Double? = null,
        val resistanceLevelPercent: Double? = null,
        val metabolicEquivalent: Double? = null,
        val caloricBurnRateKcalPerHour: Double? = null,
        val accumulatedCaloriesKcal: Double? = null,
        val movementCount: Int? = null,
        val positiveVerticalDistanceMeters: Double? = null,
        val negativeVerticalDistanceMeters: Double? = null,
        val wheelTorqueNewtonMeters: Double? = null,
        val packetsSent: Long = 0,
        val conflatedCount: Long = 0,
        val controlWriteCount: Long = 0,
        val malformedControlWriteCount: Long = 0,
        val unsupportedControlPageCount: Long = 0,
        val lastAcceptedControlPage: String? = null,
        val lastRejectReason: String? = null,
        val lastError: String? = null,
    )

    private val main = Handler(Looper.getMainLooper())
    private val operationEpoch = AtomicLong(0)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile private var status = Status()
    @Volatile private var active: Boolean = false
    @Volatile private var state: FeCBleMachineState? = null
    @Volatile private var scheduler: FeCBleTelemetryScheduler? = null
    private val generationGate = SimulatorGenerationGate()
    @Volatile private var advertiser: FeCBlePeripheralAdvertiser? = null

    /**
     * Owns wheel/crank phase for the Incyclist CPS overlay's synthetic events (plan §18.2). Only
     * crank revolutions are ever emitted on the wire — [CyclingSensorEncoders.cyclingPowerMeasurement]
     * omits wheel data unless native events are supplied, which this simulator never has — but the
     * accumulator still needs continuous state across ticks to keep event timestamps coherent.
     */
    @Volatile private var cpsAccumulator: CyclingEventAccumulator? = null
    @Volatile private var activeIdentity: FeCBleSimulatorIdentity = FeCBleSimulatorIdentity.GENERIC

    private var packets = 0L
    private var conflated = 0L
    private var controlWrites = 0L
    private var malformedWrites = 0L
    private var unsupportedPages = 0L

    private val adapterNames = AdapterNameCoordinator(
        gateway = object : AdapterNameCoordinator.Gateway {
            @get:SuppressLint("MissingPermission", "HardwareIds")
            override val currentName: String?
                get() = adapter()?.name

            @SuppressLint("MissingPermission")
            override fun requestName(name: String): Boolean = adapter()?.setName(name) == true
        },
        store = object : AdapterNameCoordinator.OriginalNameStore {
            override fun read(): String? = prefs.getString(KEY_ORIGINAL_NAME, null)
            override fun write(name: String): Boolean = prefs.edit().putString(KEY_ORIGINAL_NAME, name).commit()
            override fun clear(): Boolean = prefs.edit().remove(KEY_ORIGINAL_NAME).commit()
        },
        scheduler = object : AdapterNameCoordinator.Scheduler {
            private val tasks = mutableMapOf<Any, Runnable>()
            override fun postDelayed(token: Any, delayMs: Long, action: () -> Unit) {
                cancel(token)
                val task = Runnable { tasks.remove(token); action() }
                tasks[token] = task
                main.postDelayed(task, delayMs)
            }

            override fun cancel(token: Any) {
                tasks.remove(token)?.let(main::removeCallbacks)
            }
        },
    )

    val isAdvertising: Boolean get() = advertiser?.isAdvertising == true

    @SuppressLint("MissingPermission")
    fun start(persona: SimulatorPersona.FeCBle, onResult: (Result<Unit>) -> Unit) {
        generationGate.reset()
        val epochValue = operationEpoch.incrementAndGet()
        if (!hasPermissions()) {
            val error = IllegalStateException("Bluetooth advertising permission has not been granted")
            publishStatus(Status(lastError = error.message))
            onResult(Result.failure(error))
            return
        }
        stopResources(restoreName = false)
        packets = 0; conflated = 0; controlWrites = 0; malformedWrites = 0; unsupportedPages = 0
        active = true
        activeIdentity = persona.identity

        val includeCps = persona.overlay == FeCBleSimulatorOverlay.CPS_INCYCLIST_COMPAT
        val newState = FeCBleMachineState(
            equipmentType = persona.equipmentType,
            controlEnabled = persona.controlEnabled,
            serialNumber = persona.identity.syntheticSerial.toLong(),
            softwareRevisionSupplemental = persona.identity.supplementalRevision,
            softwareRevisionMain = persona.identity.mainRevision,
        )
        state = newState
        scheduler = FeCBleTelemetryScheduler(persona.equipmentType)
        cpsAccumulator = if (includeCps) {
            CyclingEventAccumulator(CyclingSensorConfig.DEFAULT_WHEEL_CIRCUMFERENCE_MM)
        } else {
            null
        }
        val newAdvertiser = FeCBlePeripheralAdvertiser(
            context,
            newState,
            persona.controlEnabled,
            includeCps,
            persona.identity,
            this,
        )
        advertiser = newAdvertiser

        adapterNames.apply(persona.advertisedName) nameCallback@{ nameResult ->
            if (operationEpoch.get() != epochValue) return@nameCallback
            nameResult.fold(
                onSuccess = {
                    newAdvertiser.start(advertiserCallback@{ result ->
                        if (operationEpoch.get() != epochValue) return@advertiserCallback
                        result.fold(
                            onSuccess = {
                                FtmsForegroundService.start(
                                    context,
                                    "Sharing as ${persona.advertisedName}",
                                    "Simulated FE-C over BLE; ${persona.equipmentType.displayName}",
                                ) { foreground ->
                                    foreground.fold(
                                        onSuccess = {
                                            // Plan §9.1: READY at start, IN_USE after a 750 ms
                                            // simulator-convenience delay. Not a hardware claim.
                                            main.postDelayed({
                                                if (operationEpoch.get() == epochValue) {
                                                    synchronized(newState) { newState.enterInUseWithDefaults() }
                                                }
                                            }, AUTO_IN_USE_DELAY_MS)
                                            scheduleTick(epochValue)
                                            publishStatus(
                                                status.copy(
                                                    advertisedName = persona.advertisedName,
                                                    equipmentType = persona.equipmentType.displayName,
                                                ),
                                            )
                                            onResult(Result.success(Unit))
                                        },
                                        onFailure = { failure -> failStart(failure, onResult) },
                                    )
                                }
                            },
                            onFailure = { failure -> failStart(failure, onResult) },
                        )
                    })
                },
                onFailure = { failure -> failStart(failure, onResult) },
            )
        }
    }

    fun stop() {
        generationGate.reset()
        operationEpoch.incrementAndGet()
        active = false
        stopResources(restoreName = true)
        state = null
        scheduler = null
        advertiser = null
        cpsAccumulator = null
        activeIdentity = FeCBleSimulatorIdentity.GENERIC
        publishStatus(Status())
    }

    fun dispose() {
        stop()
        adapterNames.cancelAll("FE-C controller disposed")
    }

    private fun scheduleTick(epochAtStart: Long) {
        main.postDelayed(
            {
                if (operationEpoch.get() != epochAtStart || !active) return@postDelayed
                val currentState = state
                val currentScheduler = scheduler
                val currentAdvertiser = advertiser
                if (
                    generationGate.observe(status.subscribedClients > 0) && currentState != null &&
                    currentScheduler != null && currentAdvertiser != null
                ) {
                    val frame = synchronized(currentState) { currentScheduler.tick(currentState) }
                    currentAdvertiser.publish(frame)
                    // Plan §18.2: CPS power must equal this same tick's page-25 power, so it is
                    // read from the same state right after the scheduler advanced it — never an
                    // independently generated value.
                    cpsAccumulator?.let { accumulator ->
                        val cpsFrame = synchronized(currentState) {
                            val revolutions = accumulator.update(
                                currentState.speedMps * 3.6,
                                currentState.cadenceRpm?.toDouble(),
                                System.currentTimeMillis(),
                            )
                            if (activeIdentity == FeCBleSimulatorIdentity.TACX_FLUX_2) {
                                TacxFlux2StandardEncoders.cyclingPowerMeasurement(currentState, revolutions)
                            } else {
                                CyclingSensorEncoders.cyclingPowerMeasurement(
                                    currentState.instantaneousPowerWatts ?: 0,
                                    revolutions,
                                )
                            }
                                .also { currentAdvertiser.publishCsc(CyclingSensorEncoders.cscMeasurement(revolutions)) }
                        }
                        currentAdvertiser.publishCps(cpsFrame)
                    }
                    if (activeIdentity == FeCBleSimulatorIdentity.TACX_FLUX_2) {
                        val ftmsFrame = synchronized(currentState) {
                            TacxFlux2StandardEncoders.indoorBikeData(currentState)
                        }
                        currentAdvertiser.publishFtms(ftmsFrame)
                    }
                    publishDiagnostics(currentState)
                }
                scheduleTick(epochAtStart)
            },
            FeCBleTelemetryScheduler.TICK_PERIOD_MS,
        )
    }

    private fun publishDiagnostics(state: FeCBleMachineState) {
        val snapshot = synchronized(state) {
            Status(
                advertising = isAdvertising,
                connectedClients = status.connectedClients,
                subscribedClients = status.subscribedClients,
                advertisedName = status.advertisedName,
                equipmentType = status.equipmentType,
                feState = state.feState.name,
                controlMode = state.controlMode.name,
                targetPowerWatts = state.targetPowerWatts?.toInt(),
                basicResistancePercent = state.basicResistancePercent?.toInt(),
                gradePercent = state.track?.gradePercent,
                crr = state.track?.crr,
                // Page 50's speed byte is ANT+ FE-C km/h (offset-decoded, `raw - 127`); converted
                // to m/s here to match FTMS's own simulation units. Coefficient/drafting are
                // already unit-less/fractional off the wire, see FeCBleControlDecoder.
                windSpeedMps = state.wind?.windSpeedEncodedUnit?.let { it / 3.6 },
                windCoefficient = state.wind?.windCoefficient,
                windDraftingFactor = state.wind?.draftingFactor,
                speedKph = state.speedMps * 3.6,
                cadenceRpm = state.cadenceRpm,
                powerWatts = state.instantaneousPowerWatts,
                distanceMeters = state.distanceMeters.toInt(),
                elapsedSeconds = (state.elapsedQuarterSeconds / 4).toInt(),
                intervalName = state.intervalName,
                intervalSecondsRemaining = state.intervalSecondsRemaining,
                intervalIntensityPercent = state.intervalIntensityPercent,
                heartRateBpm = state.heartRateBpm,
                cycleLengthMeters = state.cycleLengthMeters,
                inclinePercent = state.inclinePercent,
                resistanceLevelPercent = state.resistanceLevelPercent,
                metabolicEquivalent = state.metabolicEquivalent,
                caloricBurnRateKcalPerHour = state.caloricBurnRateKcalPerHour,
                accumulatedCaloriesKcal = state.accumulatedCaloriesKcal,
                movementCount = state.movementCount.toInt(),
                positiveVerticalDistanceMeters = state.positiveVerticalDistanceMeters,
                negativeVerticalDistanceMeters = state.negativeVerticalDistanceMeters,
                wheelTorqueNewtonMeters = state.instantaneousPowerWatts?.let { power ->
                    val angularVelocity = state.speedMps / state.cycleLengthMeters * 2.0 * Math.PI
                    if (angularVelocity > 0.0) power / angularVelocity else null
                },
                packetsSent = packets,
                conflatedCount = conflated,
                controlWriteCount = controlWrites,
                malformedControlWriteCount = malformedWrites,
                unsupportedControlPageCount = unsupportedPages,
                lastAcceptedControlPage = status.lastAcceptedControlPage,
                lastRejectReason = status.lastRejectReason,
                lastError = status.lastError,
            )
        }
        publishStatus(snapshot)
    }

    override fun onState(advertising: Boolean, connectedClients: Int, subscribedClients: Int, error: String?) {
        publishStatus(
            status.copy(
                advertising = advertising,
                connectedClients = connectedClients,
                subscribedClients = subscribedClients,
                lastError = error ?: status.lastError,
            ),
        )
    }

    override fun onPacketSent() {
        packets++
        publishStatus(status.copy(packetsSent = packets))
    }

    override fun onNotificationConflated() {
        conflated++
        publishStatus(status.copy(conflatedCount = conflated))
    }

    override fun onControlWriteAccepted(page: Int, clientKey: String) {
        controlWrites++
        publishStatus(
            status.copy(
                controlWriteCount = controlWrites,
                lastAcceptedControlPage = controlPageName(page),
            ),
        )
    }

    override fun onControlWriteRejected(reason: RejectReason) {
        malformedWrites++
        publishStatus(
            status.copy(
                malformedControlWriteCount = malformedWrites,
                lastRejectReason = reason.name,
            ),
        )
    }

    override fun onUnsupportedControlPage(page: Int) {
        unsupportedPages++
        publishStatus(status.copy(unsupportedControlPageCount = unsupportedPages))
    }

    private fun failStart(failure: Throwable, callback: (Result<Unit>) -> Unit) {
        stopResources(restoreName = true)
        active = false
        state = null
        scheduler = null
        advertiser = null
        cpsAccumulator = null
        publishStatus(Status(lastError = failure.message))
        callback(Result.failure(failure))
    }

    private fun stopResources(restoreName: Boolean) {
        advertiser?.stop()
        FtmsForegroundService.stop(context)
        if (restoreName) adapterNames.restore()
    }

    private fun publishStatus(next: Status) {
        status = next
        onStatus(next)
    }

    private fun hasPermissions(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun adapter() =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun controlPageName(page: Int): String = when (page) {
        0x30 -> "Basic Resistance (0x30)"
        0x31 -> "Target Power (0x31)"
        0x32 -> "Wind Resistance (0x32)"
        0x33 -> "Track Resistance (0x33)"
        0x37 -> "User Configuration (0x37)"
        else -> "0x%02X".format(page)
    }

    companion object {
        private const val PREFS = "com.gmail.absolutebollockscreations.Fitbridge.peripheral"
        private const val KEY_ORIGINAL_NAME = "original_adapter_name_fec"
        private const val AUTO_IN_USE_DELAY_MS = 750L
    }
}
