package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import com.gmail.absolutebollockscreations.gattlibrary.core.bytes.LittleEndian.u8Wrapping
import com.gmail.absolutebollockscreations.gattlibrary.core.bytes.LittleEndian.u16Wrapping
import com.gmail.absolutebollockscreations.gattlibrary.core.bytes.LittleEndian.s16Wrapping
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsSpec

/** Serializes lifecycle state and live diagnostic refreshes onto one status stream. */
internal class FtmsPeripheralStatusRelay(
    initial: FtmsPeripheralController.PeripheralStatus,
    private val enrich: (FtmsPeripheralController.PeripheralStatus) ->
        FtmsPeripheralController.PeripheralStatus,
    private val onStatus: (FtmsPeripheralController.PeripheralStatus) -> Unit,
) {
    private var current = initial

    @Synchronized
    fun baseStatus(): FtmsPeripheralController.PeripheralStatus = current

    @Synchronized
    fun snapshot(): FtmsPeripheralController.PeripheralStatus = enrich(current)

    @Synchronized
    fun publish(next: FtmsPeripheralController.PeripheralStatus) {
        current = next.copy(
            clientEverConnected = current.clientEverConnected || next.clientEverConnected || next.clientLinkConnected,
            clientControlReceived = current.clientControlReceived || next.clientControlReceived,
        )
        onStatus(enrich(current))
    }

    @Synchronized
    fun recordClientControl() {
        publish(current.copy(clientControlReceived = true))
    }

    @Synchronized
    fun resetClientHistory() {
        current = current.copy(
            advertising = false, connected = false, subscribed = false, clientId = null,
            clientLinkConnected = false,
            clientAdvertising = false,
            clientEverConnected = false, clientControlReceived = false, clientControllable = false,
        )
    }

    @Synchronized
    fun refreshDiagnostics() {
        onStatus(enrich(current))
    }
}

/**
 * Owns the peripheral lifecycle and the adapter-name save/restore dance.
 *
 * A start is treated as a transaction:
 *
 *   adapter name -> GATT database -> advertising -> foreground-service ack
 *   -> metric pump -> notifier -> RUNNING
 *
 * Failures roll those resources back in reverse ownership order. Asynchronous
 * callbacks carry an [operationEpoch] so a late success cannot revive a start
 * that a newer stop, retry, persona change, or adapter-off event superseded.
 *
 * The old `BtServiceManager` had none of this: `startFtmsAdvertising` set
 * `isAdvertising = true` on a synchronous Boolean, kept no epoch, had no
 * foreground service (so the GATT server died whenever Android trimmed the
 * backgrounded process), and renamed the Bluetooth adapter without ever
 * restoring it.
 */
class FtmsPeripheralController(
    private val context: Context,
    /** Everything the controller needs from the upstream machine; the bridge supplies [EngineUpstreamPort]. */
    private val upstream: FtmsUpstreamPort,
    private val onStatus: (PeripheralStatus) -> Unit = {},
    /** Host-app settings (LIB-6/S6). The default keeps treadmill speed control off (D26). */
    private val config: PeripheralConfig = PeripheralConfig(),
) : FtmsPeripheralAdvertiser.Listener {

    data class PeripheralStatus(
        val advertising: Boolean = false,
        val connected: Boolean = false,
        /** FTMS-only link state, including peers still performing service discovery. */
        val clientLinkConnected: Boolean = false,
        val clientAdvertising: Boolean = false,
        val clientEverConnected: Boolean = false,
        val clientControlReceived: Boolean = false,
        val clientControllable: Boolean = false,
        val clientId: String? = null,
        val subscribed: Boolean = false,
        val simulatorGenerationReady: Boolean = false,
        val simulatorGenerationAdvancing: Boolean = false,
        val machineType: String? = null,
        val advertisedName: String? = null,
        val packetsSent: Long = 0,
        val metricsReceived: Long = 0,
        val completeRecords: Long = 0,
        val compactedRecords: Long = 0,
        val fragmentedRecords: Long = 0,
        val nativeBikingSensorsEnabled: Boolean = false,
        val wheelCircumferenceMm: Int? = null,
        val ftmsSubscribed: Boolean = false,
        val cscSubscribed: Boolean = false,
        val cpsSubscribed: Boolean = false,
        val ftmsPacketsSent: Long = 0,
        val cscPacketsSent: Long = 0,
        val cpsPacketsSent: Long = 0,
        val cumulativeWheelRevolutions: Long = 0,
        val cumulativeCrankRevolutions: Int = 0,
        /**
         * Downstream-lag diagnostics.
         *
         * **[lastStalenessMs] is the age of the data when Android's stack
         * *accepted* it, not when the client received it.** Android reports no
         * transmission event, so that is the most that can be measured. It is
         * systematically optimistic, because acceptance gets *faster* as the
         * controller buffer fills — which is exactly how three sessions reported
         * "ack under 30 ms, nothing superseded" while a sniffer measured the
         * on-air rate at a third of the production rate.
         *
         * Read it against [maxStalenessUpperMs], which adds one connection
         * interval wherever acceptance was provably too fast for the packet to
         * have been transmitted, and against [bufferedAckCount]: a high buffered
         * fraction means the measured figures describe the buffer rather than
         * the link.
         */
        val lastStalenessMs: Long = 0,
        val meanStalenessMs: Long = 0,
        val maxStalenessMs: Long = 0,
        /** Worst-case age at transmission. See [lastStalenessMs]. */
        val lastStalenessUpperMs: Long = 0,
        val maxStalenessUpperMs: Long = 0,
        /** Acknowledgements slow enough to have involved a real transmission. */
        val transmittedAckCount: Long = 0,
        /** Acknowledgements provably returned before the packet could reach the air. */
        val bufferedAckCount: Long = 0,
        /** Negotiated connection interval of the first active client, or 0 if unknown. */
        val connectionIntervalMs: Int = 0,
        val supersededRecords: Long = 0,
        val meanAckLatencyMs: Long = 0,
        val maxAckLatencyMs: Long = 0,
        val lastDisconnectStatus: Int? = null,
        val lastDisconnectReason: String? = null,
        val lastError: String? = null,
        /** Debug-only KICKR simulator diagnostics. No client identity is exposed here. */
        val kickrEvidence: String? = null,
        val kickrRoutes: Set<String> = emptySet(),
        val kickrProtocolStages: Set<String> = emptySet(),
        val kickrControlMode: String? = null,
        val kickrSpeedKph: Double? = null,
        val kickrCadenceRpm: Double? = null,
        val kickrPowerWatts: Int? = null,
        val kickrHeartRateBpm: Int? = null,
        val kickrVirtualGearIndex: Int? = null,
        val kickrGearRatio: Double? = null,
        /** Set only while a resistance write is the active control mode; null otherwise. */
        val kickrResistanceLevel: Double? = null,
        val kickrPendingFrames: Int = 0,
        val kickrDroppedFrames: Long = 0,
        val kickrRejectedWrites: Long = 0,
        /** Debug-only KayakFirst simulator diagnostics; null/default for every other persona. */
        val kayakFirstSetupStage: String? = null,
        val kayakFirstLastCommand: String? = null,
        /** Five validated KayakFirst panel wire codes; avoids leaking the internal protocol type. */
        val kayakFirstDisplayCodes: List<Int>? = null,
        val kayakFirstPollRequests: Long = 0,
        val kayakFirstMalformedCommands: Long = 0,
        val kayakFirstQueuedFragments: Int = 0,
        val kayakFirstDroppedRecords: Long = 0,
        /** Set only while [kickrControlMode] reflects an active simulation write; null otherwise. */
        val kickrSimWindMps: Double? = null,
        val kickrSimGradePercent: Double? = null,
        val kickrSimCrr: Double? = null,
        val kickrSimCw: Double? = null,
        /**
         * FE-C-over-BLE simulator telemetry (plan `docs/fe-c-ble_simulator_implementation.md`).
         * Null for every other persona/controller — this is the one place `FeCBlePeripheralController`
         * surfaces its self-owned model onto the shared status shape the debug UI reads, since that
         * controller is not driven by [FtmsMockEngine] and has no other route into this StateFlow.
         */
        val fecFeState: String? = null,
        val fecControlMode: String? = null,
        val fecSpeedKph: Double? = null,
        val fecCadenceRpm: Int? = null,
        val fecPowerWatts: Int? = null,
        val fecDistanceMeters: Int? = null,
        val fecElapsedSeconds: Int? = null,
        val fecIntervalName: String? = null,
        val fecIntervalSecondsRemaining: Int? = null,
        val fecIntervalIntensityPercent: Int? = null,
        val fecHeartRateBpm: Int? = null,
        val fecCycleLengthMeters: Double? = null,
        val fecInclinePercent: Double? = null,
        val fecResistanceLevelPercent: Double? = null,
        val fecMetabolicEquivalent: Double? = null,
        val fecCaloricBurnRateKcalPerHour: Double? = null,
        val fecAccumulatedCaloriesKcal: Double? = null,
        val fecMovementCount: Int? = null,
        val fecPositiveVerticalDistanceMeters: Double? = null,
        val fecNegativeVerticalDistanceMeters: Double? = null,
        val fecWheelTorqueNewtonMeters: Double? = null,
        val fecTargetPowerWatts: Int? = null,
        val fecBasicResistancePercent: Int? = null,
        val fecGradePercent: Double? = null,
        val fecCrr: Double? = null,
        val fecWindSpeedMps: Double? = null,
        val fecWindCoefficient: Double? = null,
        val fecWindDraftingFactor: Double? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())

    private val engineControlSink: UpstreamControlSink = upstream.controlSink

    /**
     * Control Point target. Normally the real machine; the data simulator swaps
     * itself in so a watch's target writes steer the synthetic machine.
     */
    private val controlSink = SwitchableFtmsControlSink(engineControlSink) {
        profile != FtmsMachineProfile.TREADMILL || treadmillSpeedControlsEnabled()
    }
    private val store = FtmsMetricStore()

    private val advertiser = FtmsPeripheralAdvertiser(
        context = context,
        controlSink = controlSink,
        listener = this,
        // Read live rather than snapshotted: the user connects sensors after the
        // GATT server is already open, and one of those reconnecting used to be
        // admitted as a downstream client. The registry lists a device from the
        // moment its context opens, which precedes any server callback for it.
        centralRoleAddresses = { upstream.connectedCentralAddresses() },
    ).apply {
        // Mirror the bridged machine's Pace Supported bit. Read live for the same reason as
        // the address set above: the upstream 0x2ACC read completes during machine setup and
        // may land after the server is already open.
        upstreamPaceSupported = { upstream.supportsPaceMeasurement() }
    }
    private val notifier = FtmsPeripheralNotifier(
        advertiser,
        store,
        onCountersChanged = ::refreshPublishedDiagnostics,
    )
    private val pump: MetricSampler = upstream.createMetricSampler(store) { profile }
    private val statusRelay = FtmsPeripheralStatusRelay(
        initial = PeripheralStatus(),
        enrich = ::withDiagnostics,
        onStatus = onStatus,
    )

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun treadmillSpeedControlsEnabled(): Boolean = config.treadmillSpeedControlsEnabled()

    /**
     * True when the treadmill speed opt-in removes target speed from [profile]'s surface.
     *
     * Only bridged sessions are gated. The data simulator installs its control override before it
     * starts the server, and a simulated treadmill has no belt to move, so it keeps whatever speed
     * capability "Accept target commands" gave it (LIB-9d).
     */
    private fun treadmillSpeedGated(profile: FtmsMachineProfile): Boolean =
        profile == FtmsMachineProfile.TREADMILL &&
            controlSink.override == null &&
            !treadmillSpeedControlsEnabled()

    /** Rebuild the GATT range and feature surface after a settings change. */
    fun refreshTreadmillSpeedControls() {
        val desired = desiredStart ?: return
        if (runState != RunState.RUNNING || desired.profile != FtmsMachineProfile.TREADMILL) return
        val effectiveSpeed = desired.targets.supportsSpeed && !treadmillSpeedGated(desired.profile)
        if (targetCapabilities.supportsSpeed == effectiveSpeed) return
        start(
            desired.profile,
            desired.upstreamAddress,
            desired.targets,
            desired.cyclingConfig,
            desired.measurementCapabilities,
            desired.advertisedMachineType,
            upstreamName = desired.upstreamName,
        ) { result ->
            result.onFailure { Timber.tag(TAG).w(it, "FTMS speed-control setting refresh failed") }
        }
    }

    /**
     * All adapter-name changes go through here, serialised and confirmed.
     *
     * Everything Android-specific is confined to these three seams so the
     * ordering rules can be unit-tested; see AdapterNameCoordinatorTest.
     */
    private val adapterNames = AdapterNameCoordinator(
        gateway = object : AdapterNameCoordinator.Gateway {
            @get:SuppressLint("MissingPermission", "HardwareIds")
            override val currentName: String?
                get() = currentAdapterName()

            @SuppressLint("MissingPermission")
            override fun requestName(name: String): Boolean {
                if (!hasConnectPermission()) return false
                val adapter =
                    (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                        ?.adapter ?: return false
                return adapter.setName(name)
            }
        },
        store = object : AdapterNameCoordinator.OriginalNameStore {
            override fun read(): String? = prefs.getString(KEY_ORIGINAL_NAME, null)

            // commit() rather than apply(): the value has to survive a process
            // death that happens moments later, or the user's phone keeps our
            // persona name permanently.
            override fun write(name: String): Boolean =
                prefs.edit().putString(KEY_ORIGINAL_NAME, name).commit()

            override fun clear(): Boolean = prefs.edit().remove(KEY_ORIGINAL_NAME).commit()
        },
        scheduler = object : AdapterNameCoordinator.Scheduler {
            private val runnables = mutableMapOf<Any, Runnable>()

            override fun postDelayed(token: Any, delayMs: Long, action: () -> Unit) {
                cancel(token)
                val runnable = Runnable {
                    runnables.remove(token)
                    action()
                }
                runnables[token] = runnable
                main.postDelayed(runnable, delayMs)
            }

            override fun cancel(token: Any) {
                runnables.remove(token)?.let(main::removeCallbacks)
            }
        },
    )

    private data class DesiredStart(
        val profile: FtmsMachineProfile,
        val upstreamAddress: String?,
        val targets: TargetCapabilities,
        val cyclingConfig: CyclingSensorConfig,
        val measurementCapabilities: FtmsMeasurementCapabilities,
        val advertisedMachineType: FtmsMachineProfile,
        val upstreamName: String?,
    )

    /**
     * User/workout intent, kept separate from [runState].
     *
     * Temporary stack failures and an adapter power cycle set runState to STOPPED
     * but retain this value, allowing a bounded automatic restart. Explicit
     * stop/dispose clears it and therefore cancels that intent.
     */
    @Volatile
    private var desiredStart: DesiredStart? = null
    private var retryAttempt = 0
    private var retryRunnable: Runnable? = null
    private var adapterReceiverRegistered = false

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            when (intent?.action) {
                // Android confirming a rename actually took effect. This is what
                // unblocks a pending start; see AdapterNameCoordinator.
                BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED ->
                    adapterNames.onNameObserved(
                        intent.getStringExtra(BluetoothAdapter.EXTRA_LOCAL_NAME),
                    )

                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                        BluetoothAdapter.STATE_TURNING_OFF,
                        BluetoothAdapter.STATE_OFF,
                        -> {
                            operationEpoch.incrementAndGet()
                            cancelRetry()
                            // A rename can never complete with the radio off;
                            // fail it now rather than letting it time out.
                            adapterNames.cancelAll("Bluetooth adapter is off")
                            stopResources(publishIdle = false)
                            publish(PeripheralStatus(lastError = "Bluetooth adapter is off"))
                        }

                        BluetoothAdapter.STATE_ON -> restartDesired("adapter-on")
                    }
                }
            }
        }
    }

    private enum class RunState { STOPPED, STARTING, RUNNING, STOPPING }

    /** Generation fence for advertiser and foreground-service callbacks. */
    private val operationEpoch = AtomicLong(0L)

    @Volatile
    private var runState: RunState = RunState.STOPPED

    private val status: PeripheralStatus
        get() = statusRelay.baseStatus()

    val isRunning: Boolean
        get() = runState == RunState.RUNNING

    val isAdvertising: Boolean
        get() = advertiser.isAdvertising

    @Volatile
    private var profile: FtmsMachineProfile? = null

    /** FTMS Service Data machine type of the running advertisement; null when not running. */
    private var advertisedMachineType: FtmsMachineProfile? = null

    /** Advertised (and adapter) name of the running session; null when not running (LIB-18e). */
    private var localName: String? = null
    private var targetCapabilities: TargetCapabilities = TargetCapabilities.NONE
    private var advertiserMeasurementCapabilities: FtmsMeasurementCapabilities =
        FtmsMachineProfile.INDOOR_BIKE.measurementCapabilities
    private var cyclingConfig: CyclingSensorConfig = CyclingSensorConfig.DISABLED

    init {
        // Receiver first: the stranded restore below needs ACTION_LOCAL_NAME_CHANGED
        // to confirm, and registering afterwards would leave it relying on the
        // slower poll fallback.
        registerAdapterReceiver()
        // If the process died mid-session the phone is still named e.g. "FB-Treadmill"
        // (or "FBRG-Treadmill" from a build before LIB-18e).
        // Put it back before doing anything else. This is queued, and the
        // coordinator is FIFO, so a start() arriving immediately cannot have its
        // rename overwritten by this restore landing late - which is the second
        // half of the advertised-name bug.
        restoreAdapterNameIfStranded()
    }

    // ── public API ────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun start(
        profile: FtmsMachineProfile,
        upstreamAddress: String?,
        targets: TargetCapabilities,
        cyclingConfig: CyclingSensorConfig,
        measurementCapabilities: FtmsMeasurementCapabilities = profile.measurementCapabilities,
        advertisedMachineType: FtmsMachineProfile = profile,
        /** The bridged machine's own name, advertised after the prefix; null for none (simulator, unknown). */
        upstreamName: String? = null,
        onResult: (Result<Unit>) -> Unit,
    ) {
        val effectiveTargets = if (treadmillSpeedGated(profile)) {
            targets.copy(speed = false, speedRange = null)
        } else {
            targets
        }
        val effectiveCyclingConfig = cyclingConfig.effectiveFor(profile)
        val sessionName = FtmsAdvertisingPlan.bridgeLocalName(
            profile,
            upstreamName,
            includeNativeBikingSensors = effectiveCyclingConfig.enabled,
            advertisedMachineType = advertisedMachineType,
        )
        desiredStart = DesiredStart(
            profile,
            upstreamAddress,
            targets,
            effectiveCyclingConfig,
            measurementCapabilities,
            advertisedMachineType,
            upstreamName,
        )
        if (
            runState == RunState.RUNNING && this.profile == profile &&
            targetCapabilities == effectiveTargets && this.cyclingConfig == effectiveCyclingConfig &&
            advertiserMeasurementCapabilities == measurementCapabilities &&
            this.advertisedMachineType == advertisedMachineType &&
            this.localName == sessionName
        ) {
            advertiser.updateUpstreamAddress(upstreamAddress)
            onResult(Result.success(Unit))
            return
        }
        if (runState == RunState.STARTING && this.profile == profile) {
            onResult(Result.failure(IllegalStateException("FTMS peripheral is already starting")))
            return
        }

        val epoch = operationEpoch.incrementAndGet()
        if (runState != RunState.STOPPED || this.profile != null) {
            stopResources(publishIdle = false)
        }

        if (!hasAdvertisePermission()) {
            val e = IllegalStateException("Bluetooth advertising permission has not been granted")
            publish(status.copy(lastError = e.message))
            onResult(Result.failure(e))
            return
        }

        this.profile = profile
        this.advertisedMachineType = advertisedMachineType
        this.localName = sessionName
        statusRelay.resetClientHistory()
        targetCapabilities = effectiveTargets
        advertiserMeasurementCapabilities = measurementCapabilities
        this.cyclingConfig = effectiveCyclingConfig
        notifier.configureCycling(effectiveCyclingConfig)
        engineControlSink.beginSession(effectiveTargets)
        runState = RunState.STARTING

        // Publication order matters, and the adapter name is the first step for a
        // reason: Android splices the GLOBAL adapter name into the advertisement
        // at the moment advertising starts, so renaming and immediately
        // advertising races the stack and can put the machine on the air under
        // the phone's previous name. The coordinator does not call back until the
        // adapter genuinely reports the new name, and fails rather than letting
        // us advertise under the wrong identity.
        adapterNames.apply(sessionName) nameCallback@{ nameResult ->
            if (operationEpoch.get() != epoch) {
                onResult(Result.failure(CancellationException("FTMS start was superseded")))
                return@nameCallback
            }
            nameResult.fold(
                onSuccess = {
                    startAdvertisingAfterRename(
                        profile = profile,
                        targets = effectiveTargets,
                        effectiveCyclingConfig = effectiveCyclingConfig,
                        upstreamAddress = upstreamAddress,
                        measurementCapabilities = measurementCapabilities,
                        advertisedMachineType = advertisedMachineType,
                        localName = sessionName,
                        epoch = epoch,
                        onResult = onResult,
                    )
                },
                onFailure = { failure ->
                    Timber.tag(TAG).w(failure, "adapter rename failed; not advertising")
                    // Put the name back if we changed it half-way, then give up.
                    adapterNames.restore()
                    rollbackSessionState()
                    publish(PeripheralStatus(lastError = failure.message))
                    onResult(Result.failure(failure))
                    scheduleRetryIfRecoverable(failure)
                },
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertisingAfterRename(
        profile: FtmsMachineProfile,
        targets: TargetCapabilities,
        effectiveCyclingConfig: CyclingSensorConfig,
        upstreamAddress: String?,
        measurementCapabilities: FtmsMeasurementCapabilities,
        advertisedMachineType: FtmsMachineProfile,
        localName: String,
        epoch: Long,
        onResult: (Result<Unit>) -> Unit,
    ) {
        advertiser.start(
            profile = profile,
            targets = targets,
            includeTrainingStatus = true,
            cyclingConfig = effectiveCyclingConfig,
            upstreamAddress = upstreamAddress,
            measurementCapabilities = measurementCapabilities,
            advertisedMachineType = advertisedMachineType,
            localName = localName,
        ) startCallback@{ result ->
            if (operationEpoch.get() != epoch) {
                onResult(Result.failure(CancellationException("FTMS start was superseded")))
                return@startCallback
            }
            result.fold(
                onSuccess = {
                    FtmsForegroundService.start(
                        context,
                        title = "Sharing as $localName",
                        text = "Other apps and watches can read this workout",
                    ) foregroundCallback@{ foregroundResult ->
                        if (operationEpoch.get() != epoch) {
                            onResult(
                                Result.failure(
                                    CancellationException("FTMS start was superseded"),
                                ),
                            )
                            return@foregroundCallback
                        }
                        foregroundResult.fold(
                            onSuccess = {
                                runState = RunState.RUNNING
                                retryAttempt = 0
                                cancelRetry()
                                pump.start(scope)
                                notifier.start(scope)
                                publish(
                                    status.copy(
                                        advertising = advertiser.isAdvertising,
                                        connected = advertiser.hasClients(),
                                        clientId = advertiser.firstClientAddress(),
                                        machineType = profile.wireName,
                                        // Report what is actually on the air, not
                                        // what we asked for. The two used to be
                                        // allowed to differ silently.
                                        advertisedName = currentAdapterName()
                                            ?: localName,
                                        nativeBikingSensorsEnabled = effectiveCyclingConfig.enabled,
                                        wheelCircumferenceMm = effectiveCyclingConfig
                                            .wheelCircumferenceMm
                                            .takeIf { effectiveCyclingConfig.enabled },
                                        lastError = null,
                                    ),
                                )
                                onResult(Result.success(Unit))
                                // The setting may have changed while this asynchronous start
                                // was adding services. Reconcile before a stale speed range stays
                                // published for the rest of the session.
                                refreshTreadmillSpeedControls()
                            },
                            onFailure = { failure ->
                                Timber.tag(TAG).w(failure, "foreground service start failed")
                                runCatching { advertiser.stop() }
                                FtmsForegroundService.stop(context)
                                restoreAdapterName()
                                store.clear()
                                rollbackSessionState()
                                publish(PeripheralStatus(lastError = failure.message))
                                onResult(Result.failure(failure))
                                scheduleRetryIfRecoverable(failure)
                            },
                        )
                    }
                },
                onFailure = { t ->
                    Timber.tag(TAG).w(t, "start failed")
                    restoreAdapterName()
                    rollbackSessionState()
                    publish(PeripheralStatus(lastError = t.message))
                    onResult(Result.failure(t))
                    scheduleRetryIfRecoverable(t)
                },
            )
        }
    }

    private fun rollbackSessionState() {
        profile = null
        advertisedMachineType = null
        localName = null
        targetCapabilities = TargetCapabilities.NONE
        advertiserMeasurementCapabilities = FtmsMachineProfile.INDOOR_BIKE.measurementCapabilities
        cyclingConfig = CyclingSensorConfig.DISABLED
        notifier.configureCycling(CyclingSensorConfig.DISABLED)
        engineControlSink.endSession()
        runState = RunState.STOPPED
    }

    /**
     * The upstream address identifies a central-role link that must not consume a
     * downstream client slot. It is not part of the FTMS persona, so changing it
     * must never rebuild the GATT database.
     */
    fun updateUpstreamAddress(upstreamAddress: String?) {
        desiredStart = desiredStart?.copy(upstreamAddress = upstreamAddress)
        advertiser.updateUpstreamAddress(upstreamAddress)
    }

    fun setGarminCompatibilityFraming(enabled: Boolean) {
        notifier.garminCompatibilityFraming = enabled
        advertiser.setGarminCompatibility(enabled)
    }

    fun setNotifyIntervalMs(intervalMs: Long) {
        notifier.periodMs = intervalMs
    }

    /**
     * Redirect FTMS Control Point procedures away from the real machine.
     *
     * Used by the data simulator so a watch can take control of, and actually
     * steer, a synthetic machine. Pass null to hand control back to the engine.
     */
    fun setControlOverride(sink: FtmsControlSink?) {
        controlSink.override = sink
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        desiredStart = null
        cancelRetry()
        operationEpoch.incrementAndGet()
        stopResources(publishIdle = true)
    }

    @SuppressLint("MissingPermission")
    private fun stopResources(publishIdle: Boolean) {
        if (runState == RunState.STOPPED && profile == null) {
            targetCapabilities = TargetCapabilities.NONE
            cyclingConfig = CyclingSensorConfig.DISABLED
            notifier.configureCycling(CyclingSensorConfig.DISABLED)
            engineControlSink.endSession()
            FtmsForegroundService.stop(context)
            restoreAdapterName()
            store.clear()
            if (publishIdle) {
                statusRelay.resetClientHistory()
                publish(PeripheralStatus())
            }
            return
        }
        runState = RunState.STOPPING
        // Stop producers before consumers: cancel ticks first, then close the
        // queue/server, then release process lifetime and identity. Clear the
        // store last so an in-flight notifier cannot publish a torn-down sample.
        pump.stop()
        notifier.stop()
        runCatching { advertiser.stop() }
        FtmsForegroundService.stop(context)
        restoreAdapterName()
        store.clear()
        rollbackSessionState()
        if (publishIdle) {
            statusRelay.resetClientHistory()
            publish(PeripheralStatus())
        }
    }

    fun dispose() {
        desiredStart = null
        cancelRetry()
        operationEpoch.incrementAndGet()
        stopResources(publishIdle = true)
        advertiser.dispose()
        unregisterAdapterReceiver()
        scope.cancel()
    }

    fun currentStatus(): PeripheralStatus = statusRelay.snapshot()

    private fun withDiagnostics(base: PeripheralStatus): PeripheralStatus {
        val revolutions = notifier.cyclingRevolutions()
        return base.copy(
            clientAdvertising = advertiser.isAdvertising,
            clientLinkConnected = advertiser.hasDownstreamLinks(),
            clientControllable = runState == RunState.RUNNING && targetCapabilities.any && controlSink.canControl,
            packetsSent = notifier.packetsSent,
            metricsReceived = store.updateCount,
            completeRecords = notifier.completeRecords,
            compactedRecords = notifier.compactedRecords,
            fragmentedRecords = notifier.fragmentedRecords,
            ftmsSubscribed = advertiser.anyFtmsSubscribed(),
            cscSubscribed = advertiser.anyCscSubscribed(),
            cpsSubscribed = advertiser.anyCpsSubscribed(),
            ftmsPacketsSent = notifier.ftmsPacketsSent,
            cscPacketsSent = notifier.cscPacketsSent,
            cpsPacketsSent = notifier.cpsPacketsSent,
            cumulativeWheelRevolutions = revolutions.cumulativeWheelRevolutions,
            cumulativeCrankRevolutions = revolutions.cumulativeCrankRevolutions,
            lastStalenessUpperMs = notifier.lastStalenessUpperMs,
            maxStalenessUpperMs = notifier.maxStalenessUpperMs,
            transmittedAckCount = notifier.transmittedAckCount,
            bufferedAckCount = notifier.bufferedAckCount,
            connectionIntervalMs = advertiser.activeConnections()
                .firstOrNull()?.connectionIntervalMs ?: 0,
            lastStalenessMs = notifier.lastStalenessMs,
            meanStalenessMs = notifier.meanStalenessMs,
            maxStalenessMs = notifier.maxStalenessMs,
            supersededRecords = notifier.supersededRecords,
            meanAckLatencyMs = advertiser.txStats().meanAckLatencyMs,
            maxAckLatencyMs = advertiser.txStats().maxAckLatencyMs,
        )
    }

    // ── upstream-originated Fitness Machine Status mirroring ──────────────────
    //
    // These preserve a FitBridge capability the TrackMyIndoorWorkout peripheral
    // does not have: the real machine can change state on its own (safety key
    // pulled, console-set target, control taken back), and downstream clients
    // must hear about it. Every one now goes through the serialised TX queue and
    // only to clients that actually subscribed to 0x2ADA.

    fun emitStatusControlPermissionLost() {
        // The machine has taken control back. Drop every downstream client's
        // control ownership so a subsequent target write is answered with
        // CONTROL_NOT_PERMITTED rather than being forwarded and ignored.
        advertiser.revokeAllControl()
        broadcastStatus(byteArrayOf(FtmsSpec.Status.CONTROL_PERMISSION_LOST))
    }

    fun emitStatusSafetyKeyPulled() {
        broadcastStatus(byteArrayOf(FtmsSpec.Status.STOPPED_BY_SAFETY_KEY))
    }

    fun emitStatusTargetSpeedChanged(kmh: Float) {
        broadcastStatus(
            byteArrayOf(FtmsSpec.Status.TARGET_SPEED_CHANGED) +
                u16Wrapping((kmh * 100f).toInt().coerceIn(0, 0xFFFF)),
        )
    }

    fun emitStatusTargetInclineChanged(percent: Float) {
        broadcastStatus(
            byteArrayOf(FtmsSpec.Status.TARGET_INCLINE_CHANGED) +
                s16Wrapping((percent * 10f).toInt()),
        )
    }

    fun emitStatusTargetResistanceLevelChanged(level: Float) {
        broadcastStatus(
            byteArrayOf(FtmsSpec.Status.TARGET_RESISTANCE_CHANGED) +
                u8Wrapping((level * 10f).toInt().coerceIn(0, 0xFF)),
        )
    }

    fun emitStatusTargetPowerChanged(watts: Int) {
        broadcastStatus(byteArrayOf(FtmsSpec.Status.TARGET_POWER_CHANGED) + s16Wrapping(watts))
    }

    fun emitStatusWheelCircumferenceChanged(millimetres: Double) {
        val raw = (millimetres * 10.0).roundToInt().coerceIn(1, 0xFFFF)
        broadcastStatus(byteArrayOf(FtmsSpec.Status.WHEEL_CIRCUMFERENCE_CHANGED) + u16Wrapping(raw))
    }

    fun emitStatusTargetHeartRateChanged(bpm: Int) {
        broadcastStatus(
            byteArrayOf(FtmsSpec.Status.TARGET_HEART_RATE_CHANGED) + u8Wrapping(bpm.coerceIn(0, 0xFF)),
        )
    }

    private fun broadcastStatus(payload: ByteArray) {
        if (runState != RunState.RUNNING) return
        scope.launch {
            runCatching { advertiser.broadcastMachineStatus(payload) }
                .onFailure { Timber.tag(TAG).w(it, "machine status broadcast failed") }
        }
    }

    // ── FtmsPeripheralAdvertiser.Listener ─────────────────────────────────────

    override fun onClientConnected(device: BluetoothDevice) {
        // Advertising keeps running with clients attached, so reflect its real
        // state rather than forcing it false.
        publish(
            status.copy(
                advertising = advertiser.isAdvertising,
                connected = true,
                clientLinkConnected = true,
                clientId = advertiser.firstClientAddress() ?: device.address,
            ),
        )
    }

    override fun onClientDisconnected(device: BluetoothDevice, status: Int) {
        // Other clients may still be attached.
        publish(
            this.status.copy(
                connected = advertiser.hasClients(),
                clientId = advertiser.firstClientAddress(),
                subscribed = advertiser.anyDataSubscribed(),
                lastDisconnectStatus = status,
                lastDisconnectReason = disconnectStatusName(status),
            ),
        )
    }

    override fun onSubscriptionChanged(connection: PeripheralConnection) {
        publish(status.copy(subscribed = advertiser.anyDataSubscribed()))
        if (connection.anyMeasurementSubscribed) {
            // Do not make the client wait a full period for its first packet.
            pump.sampleOnce()
            notifier.kick(scope)
        }
    }

    override fun onProcedureReset() {
        store.clear()
        notifier.configureCycling(cyclingConfig)
        publish(status.copy())
    }

    override fun onClientControlReceived() {
        statusRelay.recordClientControl()
    }

    override fun onClientLinkStateChanged(connected: Boolean) {
        publish(status.copy(clientLinkConnected = connected))
    }

    override fun onCscSetCumulativeWheelRevolutions(value: Long): Boolean =
        notifier.setCumulativeWheelRevolutions(value)

    override fun onAdvertisingStateChanged(advertising: Boolean, error: String?) {
        publish(status.copy(advertising = advertising, lastError = error ?: status.lastError))
    }

    // ── adapter name ──────────────────────────────────────────────────────────

    /**
     * Android has no per-advertisement name API: setIncludeDeviceName(true)
     * publishes the GLOBAL adapter name. So we rename the phone while advertising
     * and put it back afterwards.
     *
     * The original is persisted rather than held in memory because a process death
     * would otherwise leave the user's phone permanently called "FB-Treadmill"
     * every Bluetooth context. The old advertiser set the name and never restored
     * it at all.
     */
    @SuppressLint("MissingPermission", "HardwareIds")
    private fun currentAdapterName(): String? = runCatching {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.name
    }.getOrNull()

    /** Restore the user's own name. Fire-and-forget; the coordinator serialises. */
    private fun restoreAdapterName() {
        adapterNames.restore { result ->
            result.onFailure {
                Timber.tag(TAG).w(
                    it,
                    "could not restore adapter name; retaining saved name for retry next launch",
                )
            }
        }
    }

    private fun restoreAdapterNameIfStranded() {
        if (prefs.getString(KEY_ORIGINAL_NAME, null) != null) {
            Timber.tag(TAG).i("found a stranded adapter name from a previous session; restoring")
            restoreAdapterName()
        }
    }

    private fun registerAdapterReceiver() {
        if (adapterReceiverRegistered) return
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
            // The rename confirmation the coordinator waits on. Without this the
            // only signal is the poll fallback, which is slower and, on stacks
            // that never settle, indistinguishable from a genuine failure.
            addAction(BluetoothAdapter.ACTION_LOCAL_NAME_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The system, not this app, is the sender, so this must be exported.
            context.registerReceiver(adapterReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(adapterReceiver, filter)
        }
        adapterReceiverRegistered = true
    }

    private fun unregisterAdapterReceiver() {
        if (!adapterReceiverRegistered) return
        runCatching { context.unregisterReceiver(adapterReceiver) }
        adapterReceiverRegistered = false
    }

    private fun restartDesired(reason: String) {
        val desired = desiredStart ?: return
        if (runState != RunState.STOPPED) return
        Timber.tag(TAG).i("restarting intended FTMS peripheral after %s", reason)
        start(
            desired.profile,
            desired.upstreamAddress,
            desired.targets,
            desired.cyclingConfig,
            desired.measurementCapabilities,
            desired.advertisedMachineType,
            upstreamName = desired.upstreamName,
        ) { result ->
            result.onFailure { Timber.tag(TAG).w(it, "automatic FTMS restart failed") }
        }
    }

    private fun scheduleRetryIfRecoverable(failure: Throwable) {
        // Retry short-lived Android stack failures only. Permission, capability,
        // payload, user cancellation, and adapter-off failures need an external
        // state change; spinning on them would waste battery and obscure the
        // actionable error. Delays are intentionally short and bounded.
        if (desiredStart == null ||
            !isRecoverable(failure) ||
            retryAttempt >= RETRY_DELAYS_MS.size
        ) {
            return
        }
        val delayMs = RETRY_DELAYS_MS[retryAttempt++]
        cancelRetry()
        val runnable = Runnable { restartDesired("recoverable-failure-$retryAttempt") }
        retryRunnable = runnable
        main.postDelayed(runnable, delayMs)
    }

    private fun cancelRetry() {
        retryRunnable?.let(main::removeCallbacks)
        retryRunnable = null
    }

    private fun isRecoverable(failure: Throwable): Boolean {
        val message = failure.message.orEmpty().lowercase()
        return !message.contains("permission") &&
            !message.contains("cannot advertise") &&
            !message.contains("unavailable") &&
            !message.contains("payload") &&
            !message.contains("too large") &&
            !message.contains("unsupported") &&
            !message.contains("turned off") &&
            !message.contains("superseded") &&
            !message.contains("cancel")
    }

    // ── permissions ───────────────────────────────────────────────────────────

    fun hasAdvertisePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            true
        }

    private fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            true
        }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    // ── status plumbing ───────────────────────────────────────────────────────

    private fun publish(next: PeripheralStatus) {
        statusRelay.publish(next)
    }

    private fun refreshPublishedDiagnostics() = statusRelay.refreshDiagnostics()

    companion object {
        private const val TAG = "FtmsController"
        private const val PREFS = "com.gmail.absolutebollockscreations.Fitbridge.peripheral"
        private const val KEY_ORIGINAL_NAME = "original_adapter_name"
        private val RETRY_DELAYS_MS = longArrayOf(250L, 750L, 1_500L)
    }
}
