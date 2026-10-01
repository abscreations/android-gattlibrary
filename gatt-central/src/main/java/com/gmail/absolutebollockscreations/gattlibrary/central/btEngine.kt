// =================================================================================================
// File: BtEngine.kt
// Package: com.gmail.absolutebollockscreations.gattlibrary.central
// -------------------------------------------------------------------------------------------------
// ROLE
//   Core Bluetooth LE “engine” used by the FitBridge app to talk to external FTMS / HRM devices
//   and, optionally, drive the local provider-side btService stack.
//
//   Responsibilities:
//   • Own a single BtEngineState instance (source of truth for all BLE + workout metrics).
//   • Wrap Android BLE primitives and permissions (scan, connect, bond, GATT queue).
//   • Maintain two GATT pipes:
//       - FTMS: Fitness Machine Service (external treadmill/rower/bike/etc).
//       - HRM : Heart Rate Monitor service (external HR strap / watch).
//   • Route all GATT callbacks through BtEngineGattCore + protocol handlers
//       (FtmsProtocolHandler, HrmProtocolHandler).
//   • Orchestrate sessions: start/stop, elapsed time, laps, “isRunning” flag.
//   • Expose convenience APIs around the FTMS Control Point
//       (requestControl, start/resume, stop/pause, set target speed/incline/resistance/power).
//   • Trigger capability reads (supported speed / incline / resistance / power ranges) and
//       populate BtEngineState.
//   • Bind app-supplied HR configuration (max HR, resting HR, zone mode) and kick HR zone
//       recomputation via MetricFusion.
//   • Publish what the machine is (BtEngineState.inferredActivity) and what it can do (the
//       capability snapshot). The peripheral bridge (gatt-peripheral FtmsBridgeAdvertising) decides
//       from those when, and as what, to advertise, and mirrors machine events from `controlEvents`.
//
//   Provider-side advertising (FitBridge virtual device):
//   • The engine does not advertise and holds no reference to a provider layer.
//   • The bridge starts advertising only once the capability snapshot is reasonable (FEATURES plus
//     every claimed Supported-* range, or 5 s spent waiting for them) and the machine link is up.
//   • FEATURES and range reads, and normal FTMS control, proceed regardless of advertising.
//
//   2ACC (FEATURES) READ SEMANTICS
//   • A failed FEATURES (0x2ACC) read is treated as *non-fatal* at the engine layer:
//       - Connection, CCCD enablement, and Control Point operations continue independently.
//       - FtmsCapabilities may remain partially populated, but workout data and control can
//         still flow as long as DATA/STATUS/CP work.
//       - Provider-side advertising will prefer a full snapshot, but callers can still infer a
//         machine persona from the available data characteristics and start btService with
//         conservative “all controls unsupported” defaults when FEATURES never converge.
//
//   CCCD / CONTROL ORCHESTRATION ORDER
//   • After FTMS services are discovered, BtEngine is intended to:
//       1) Enable NOTIFY/INDICATE on the chosen DATA characteristic and Control Point.
//       2) Best-effort REQUEST_CONTROL via CP (non-fatal if denied or unsupported).
//       3) Queue a FEATURES (0x2ACC) read *last*, as an optional enrichment step.
//   • This ordering avoids treating 2ACC as a hard dependency for data/control and matches
//     behaviour of permissive FTMS clients that successfully interoperate with “picky” devices.
//
//   START/STOP RESILIENCE
//   • Start/Stop commands are allowed to proceed after a *best-effort* attempt to obtain
//     control and enable CCCDs. Failure to gain control within a timeout is logged, but does
//     not block sending a Start/Stop CP packet; this helps with devices that don’t strictly
//     implement REQUEST_CONTROL but accept Start/Stop directly.
//
//   Typical usage:
//     val engine = BtEngine.create(context)
//     engine.start(address, activityType)
// -------------------------------------------------------------------------------------------------
// NOTES
//   - This class is UI-free; callers interact via the StateFlows exposed from BtEngineState and
//     via the public control methods here.
//   - All Bluetooth entry points are permission-guarded (BLUETOOTH_CONNECT / BLUETOOTH_SCAN) and
//     degrade gracefully when permissions are missing.
//   - Connection + control are *not* blocked on FEATURES / Supported-* range discovery; those
//     reads are best-effort and retried when we gain control, but regular FTMS use can continue.
// =================================================================================================

package com.gmail.absolutebollockscreations.gattlibrary.central

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattConnectionSettings
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.gmail.absolutebollockscreations.gattlibrary.core.ActivityType
import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi
import com.gmail.absolutebollockscreations.gattlibrary.core.HrZoneMode
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids.UUID_FITNESS_MACHINE_CONTROL_POINT
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids.UUID_FITNESS_MACHINE_FEATURE
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids.UUID_FTMS_SERVICE
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids.UUID_SUPPORTED_INCLINATION_RANGE
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids.UUID_SUPPORTED_POWER_RANGE
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids.UUID_SUPPORTED_RESISTANCE_RANGE
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids.UUID_SUPPORTED_SPEED_RANGE
import com.gmail.absolutebollockscreations.gattlibrary.central.BtEngineState.FtmsCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import timber.log.Timber
import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.core.BleConfig
import com.gmail.absolutebollockscreations.gattlibrary.core.BleDefaults
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.catalog.MachineFamilyIds
import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.KayakFirstHandshakeVariant
import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.KayakFirstSpec

/**
 * Settled capabilities for a session supplied only by sensors.
 *
 * There is no upstream machine to read or control, so every target capability and range must
 * remain absent. `featuresRead` means the answer is final, not that a `0x2ACC` read occurred.
 */
@GattLibraryInternalApi
fun sensorOnlyProviderCapabilities(): FtmsCapabilities = FtmsCapabilities(
    featuresRead = true,
    supportsSpeed = false,
    supportsInclination = false,
    supportsResistance = false,
    supportsPower = false,
)

/** Settled control capabilities for a measurement-only Flywheel Sports upstream. */
@GattLibraryInternalApi
fun flywheelProviderCapabilities(): FtmsCapabilities = FtmsCapabilities(
    featuresRead = true,
    supportsSpeed = false,
    supportsInclination = false,
    supportsResistance = false,
    supportsPower = false,
)

/** Settled, measurement-only capabilities for the unverified Kayak First adapter. */
internal fun kayakFirstProviderCapabilities(): FtmsCapabilities = FtmsCapabilities(
    featuresRead = true,
    supportsSpeed = false,
    supportsInclination = false,
    supportsResistance = false,
    supportsPower = false,
)

class BtEngine internal constructor(
    internal val context: Context,
    private val handlerFactory: HandlerFactory,
    /** Host-app configuration (LIB-2/S2); replaces direct reads of the app's BuildConfig. */
    internal val bleConfig: BleConfig,
    private val handlerSet: HandlerSet,
) : BtServiceEngineFacade {

    companion object {
        fun create(
            context: Context,
            config: BleConfig = BleConfig(),
            handlers: HandlerSet = HandlerSet.all(),
        ): BtEngine = BtEngine(
            context,
            HandlerFactory.standard(
                enablePhase05HardwareTest = config.phase05HardwareTest,
                handlerSet = handlers,
            ),
            config,
            handlers,
        )

        private const val RECOVERY_CONNECT_TIMEOUT_MS = 12_000L

        // Polled faster than 1 Hz so a published second never drifts a whole tick late.
        private const val SESSION_ELAPSED_TICK_MS = 250L

        /** §0.7a liveness poll. Far finer than the 30 s timeout it evaluates. */
        // Fast Keiser firmware has a two-second freshness window. Four checks per second keep the
        // visible stale transition close to that protocol boundary without reading a wall clock in
        // the source itself; the existing GATT supervisor is idempotent at this cadence.
        private const val LIVENESS_TICK_MS = 250L
        @Volatile private var isStarted = false
        @Volatile private var stopped = false
        private val startLock = Any()
    }

    val state: BtEngineState = BtEngineState()
    /** Transitional wiring for FitBridge's still-unmigrated metric/provider paths. One engine only. */
    @GattLibraryInternalApi
    val discoveryClient: FitnessGattClient by lazy {
        FitnessGattClient(EngineConnectionBackend(this), CoroutineScope(Dispatchers.Main.immediate + SupervisorJob()))
    }
    internal val scanFailureCode = kotlinx.coroutines.flow.MutableStateFlow<Int?>(null)
    internal val metricFusion: MetricFusion = MetricFusion(state)

    /** Set before connection; each Kayak First handler snapshots it for one link generation. */
    @Volatile
    internal var kayakFirstDisplayConfiguration: KayakFirstDisplayConfiguration? = null
        private set

    fun setKayakFirstDisplayConfiguration(configuration: KayakFirstDisplayConfiguration) {
        kayakFirstDisplayConfiguration = configuration
    }

    /** Set before selection; the Kayak First handler snapshots this for one link generation. */
    @Volatile
    internal var kayakFirstSportMode: KayakFirstSportMode = KayakFirstSportMode.CANOEING
        private set

    fun setKayakFirstSportMode(mode: KayakFirstSportMode) {
        kayakFirstSportMode = mode
    }

    /**
     * Handshake dialect used for the next Kayak First link generation.
     *
     * LEGACY is the default because command 2 is the dialect proven end to end by the FitBridge
     * captures. BULL selects the command-21 form shipped by the KayakFit client for newer Bull
     * consoles. Snapshotted per generation like the other Kayak First settings.
     */
    @Volatile
    internal var kayakFirstHandshakeVariant: KayakFirstHandshakeVariant =
        KayakFirstHandshakeVariant.LEGACY
        private set

    fun setKayakFirstHandshakeVariant(variant: KayakFirstHandshakeVariant) {
        kayakFirstHandshakeVariant = variant
    }

    /** Boat mass transmitted by the command-21 handshake. */
    @Volatile
    internal var kayakFirstBoatWeightKg: Int = KayakFirstSpec.DEFAULT_BOAT_WEIGHT_KG
        private set

    internal fun setKayakFirstBoatWeightKg(weightKg: Int) {
        kayakFirstBoatWeightKg = weightKg.coerceIn(
            KayakFirstCommandCodec.BOAT_WEIGHT_RANGE_KG.first,
            KayakFirstCommandCodec.BOAT_WEIGHT_RANGE_KG.last,
        )
    }

    /**
     * Who currently needs the platform scan running (plan §5.3).
     *
     * Before phase 11 the picker was the only owner, so "the picker closed" and "stop the radio"
     * were the same instruction. An advertisement-only machine's telemetry *is* the scan, so it
     * must outlive the picker and its five-minute timeout.
     */
    internal val scanLeases = ScanLeaseRegistry()

    /**
     * The selected Keiser bike, if any.
     *
     * Constructed unconditionally and idle until something selects a bike, which costs one object
     * and keeps the wiring in one place rather than behind a nullable that every call site has to
     * remember to check.
     */
    internal val keiserSource = KeiserAdvertisementSource(
        fusion = metricFusion,
        leases = scanLeases,
        // The source takes and gives up the lease; the engine owns the radio. Selecting a bike
        // from a screen with no scan running must still start one, and the last owner leaving
        // must stop it.
        // Through the same permission guard the public scan API uses. A lease is a claim on the
        // radio, and a claim the OS will refuse must fail the same way here as anywhere else.
        onScanLeaseAcquired = { guardScanPerm("advertisement lease acquired") { startScanImpl() } },
        onScanLeaseReleased = { guardScanPerm("advertisement lease released") { stopScanImpl() } },
        onSourceStateChanged = { snapshot ->
            state.setActiveAdvertisementMachine(
                snapshot
                    ?.takeUnless { it.state == KeiserSourceState.GONE }
                    ?.let {
                        BtEngineState.ActiveAdvertisementMachine(
                            address = it.selection.address,
                            candidateId = MachineFamilyIds.KEISER_M_SERIES,
                            systemId = it.selection.systemId,
                            model = it.selection.model,
                            firmware = it.firmware,
                            generation = it.generation,
                            sourceState = when (it.state) {
                                KeiserSourceState.LIVE ->
                                    BtEngineState.AdvertisementSourceState.RECEIVING
                                KeiserSourceState.STALE ->
                                    BtEngineState.AdvertisementSourceState.STALE
                                KeiserSourceState.GONE ->
                                    BtEngineState.AdvertisementSourceState.OUT_OF_RANGE
                                KeiserSourceState.IDLE ->
                                    BtEngineState.AdvertisementSourceState.STALE
                            },
                        )
                    },
            )
        },
    )
    private val linkRecovery = InSessionLinkRecovery()
    private val recoveryJobs = mutableMapOf<String, Job>()

    // BtServiceEngineFacade implementation ─────────────────────────────────────────────

    override val isFtmsConnected: Boolean
        get() = connectionState.value == android.bluetooth.BluetoothProfile.STATE_CONNECTED

    override val engineState: BtEngineState
        get() = state

    override val handlesDownstreamSessionTransitionsLocally: Boolean
        get() = hasSelectedKayakFirstAdapter()

    override fun buildSupportedSpeedRange(): ByteArray =
        BtServiceSupportedRangesAdapter.buildSpeedRange(state)

    override fun buildSupportedInclineRange(): ByteArray =
        BtServiceSupportedRangesAdapter.buildInclineRange(state)

    override fun buildSupportedResistanceRange(): ByteArray =
        BtServiceSupportedRangesAdapter.buildResistanceRange(state)

    override fun buildSupportedHeartRateRange(): ByteArray =
        BtServiceSupportedRangesAdapter.buildHeartRateRange(state)

    override fun buildSupportedPowerRange(): ByteArray =
        BtServiceSupportedRangesAdapter.buildPowerRange(state)



    // ───────────────────────── Provider advertising state (moved here from FtmsProtocolHandler) ─────────────────────────
    // When true, we've already scheduled a retry of the FEATURES (0x2ACC) read after gaining control.
    @Volatile internal var featureReadRetryScheduled: Boolean = false

    // When true, we've already scheduled an early bonding attempt for the current FTMS connection.
    @Volatile internal var earlyBondScheduled: Boolean = false

    /**
     * Clear per-machine-session state whenever the upstream link ("session") ends or starts over.
     * Clearing the inferred activity is what tells the peripheral bridge to start afresh.
     */
    private fun resetMachineSessionState() {
        state.setInferredActivity(null)
        featureReadRetryScheduled = false
        earlyBondScheduled = false
    }

    // Public flows (read-only)
    val connectionState: StateFlow<Int> get() = state.connectionState
    val hrmConnectionState: StateFlow<Int> get() = state.hrmConnectionState
    val haveControlFlow: StateFlow<Boolean> get() = state.haveControlFlow
    val activeSession: StateFlow<Boolean> get() = state.activeSession

    /** Profile fields a Life Fitness connection asked for and did not get (plan §6, unit 6.5c). */
    val lifeFitnessProfilePrompt:
        StateFlow<List<LifeFitnessProfileField>>
        get() = state.lifeFitnessProfilePrompt

    /** Dismiss the prompt. The connection is unaffected; the machine simply stays silent. */
    fun dismissLifeFitnessProfilePrompt() {
        state.clearLifeFitnessProfilePrompt()
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    /**
     * §0.7a liveness tick.
     *
     * A timeout is by definition the absence of events, so there is nothing to
     * piggyback on and a poll is required. One second is far finer than the
     * 30-second timeout it evaluates; the work is a map lookup per live machine
     * link and allocates nothing when nothing has expired.
     *
     * Tied to the engine's own scope, so it dies with the engine rather than
     * outliving it.
     */
    private val livenessTickJob: Job = scope.launch {
        while (isActive) {
            delay(LIVENESS_TICK_MS)
            val metricNowMs = MonotonicMetricClock.SYSTEM.nowMs()
            runCatching { state.adapterLiveness.tick(metricNowMs) }
                .onFailure { Timber.tag(BtEngineState.TAG).w(it, "liveness tick failed") }
            // The Life Fitness settle bound rides the same poll. A timeout is the absence of an
            // event either way, and reusing this tick means the deadline lives on the connection
            // context and dies with it, rather than being a scheduled callback that has to be
            // remembered and cancelled on every disconnect path.
            runCatching { tickLifeFitnessSetup(metricNowMs) }
                .onFailure { Timber.tag(BtEngineState.TAG).w(it, "life fitness setup tick failed") }
            // Protocol handlers that act on silence rather than on an event. Concept2's
            // multiplexed-to-individual fallback (D41) is the first, and it sat uncalled for two
            // phases because nothing delivered a tick to a handler. See `ProtocolHandler.
            // onLivenessTick`.
            runCatching { tickProtocolHandlers() }
                .onFailure { Timber.tag(BtEngineState.TAG).w(it, "protocol handler tick failed") }
            // An advertisement-only machine expires on silence rather than on an event, so
            // something has to look. One second is coarse against a fast bike's one-second
            // statistics window - its values can linger up to a tick past it - but the source is
            // driven entirely by the time it is handed, so a finer poll is a constant away if the
            // 11.6 UI work shows the lag.
            runCatching { keiserSource.tick(elapsedRealtimeMs()) }
                .onFailure { Timber.tag(BtEngineState.TAG).w(it, "Keiser source tick failed") }
        }
    }

    @Volatile private var desiredAddress: String? = null
    @Volatile private var desiredActivity: ActivityType? = null
    @Volatile private var sessionElapsedJob: Job? = null
    private val cscSessionLock = Any()
    private var cscSessionGeneration = 0L
    @Volatile private var cscCalibration = CscCalibration()
    @Volatile private var activeCscSession: CscSessionSnapshot? = null

    // Android primitives
    private val btManager: BluetoothManager? = runCatching {
        context.getSystemService(BluetoothManager::class.java)
    }.getOrNull()

    internal val adapter: BluetoothAdapter? = btManager?.adapter

    val discoveredDevices: StateFlow<Map<String, BtEngineState.DiscoveredDevice>>
        get() = state.discoveredDevices
    val connectedDevices: StateFlow<List<BtEngineState.ConnectedDeviceLink>>
        get() = state.connectedDevices
    val kayakFirstSetupProgress: StateFlow<KayakFirstSetupProgress?>
        get() = state.kayakFirstSetupProgress
    val activeAdvertisementMachine: StateFlow<BtEngineState.ActiveAdvertisementMachine?>
        get() = state.activeAdvertisementMachine

    /**
     * Whether the radio is actually scanning.
     *
     * Exposed because the engine stops its own scan on connect - see [connectFtms] and
     * [connectSensor] - and a UI holding a second, independent scanning flag would silently
     * drift out of step with the radio the first time that happened.
     */
    val isScanning: StateFlow<Boolean>
        get() = state.isScanning

    /* Clear the canonical discovery registry. */
    fun clearDiscovered() {
        state.putDiscoveredDevices(emptyMap())
    }

    internal fun elapsedRealtimeMs(): Long = android.os.SystemClock.elapsedRealtime()

    /**
     * Starts fresh accumulation for the next run: clears every history series, the running averages (including
     * the average heart-rate zone) and the lap speed and pace derived from the series. Keeps cumulative totals,
     * elapsed time, laps already recorded and whether a session is active. A session going active does this
     * itself, so call it to discard accumulated data without ending the session.
     */
    fun clearSessionData() {
        state.clearSessionData()
    }

    /* ───────────────────────────── permission helpers ───────────────────────────── */

    // BLUETOOTH_CONNECT / BLUETOOTH_SCAN do not exist before API 31, so checking
    // them on an older device always returns DENIED and silently disables the
    // whole engine - the symptom being a permission gate that opens, a scan that
    // reports "skipped", and an empty device list with no error.
    //
    // Pre-31 the equivalents are install-time permissions (BLUETOOTH,
    // BLUETOOTH_ADMIN) declared in the manifest and therefore always held, plus
    // ACCESS_FINE_LOCATION at runtime for scanning only. Note that on API 23-30
    // a scan additionally needs system Location to be switched on; that is a
    // device setting rather than a permission, so it cannot be checked here.

    private fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            // BLUETOOTH is install-time and declared in the manifest.
            true
        }

    private fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        }

    internal fun clientPlatformFailure(scan: Boolean): OperationFailure? = when {
        !hasConnectPermission() || (scan && !hasScanPermission()) -> OperationFailure.PERMISSION_REQUIRED
        adapter?.isEnabled != true -> OperationFailure.BLUETOOTH_UNAVAILABLE
        else -> null
    }

    private inline fun <T> guardConnectPerm(op: String, default: T, block: () -> T): T {
        if (!hasConnectPermission()) {
            Timber.tag(BtEngineState.TAG).w("%s skipped: BLUETOOTH_CONNECT not granted", op)
            return default
        }
        return try {
            block()
        } catch (se: SecurityException) {
            Timber.tag(BtEngineState.TAG).w(se, "%s rejected by platform", op); default
        }
    }

    private inline fun guardScanPerm(op: String, block: () -> Unit) {
        if (!hasScanPermission()) {
            Timber.tag(BtEngineState.TAG).w("%s skipped: BLUETOOTH_SCAN not granted", op); return
        }
        try {
            block()
        } catch (se: SecurityException) {
            Timber.tag(BtEngineState.TAG).w(se, "%s rejected by platform", op)
        }
    }

    /* ───────────────────────────── scan API ───────────────────────────── */

    fun startScan(
        useFtmsHardwareFilter: Boolean = true,
        onDevice: (name: String, address: String, result: ScanResult) -> Unit = { _, _, _ -> }
    ) = guardScanPerm("startScan()") {
        startScanForImpl(ScanLeaseOwner.DISCOVERY_UI, useFtmsHardwareFilter, onDevice)
    }

    /**
     * Give up the discovery UI's claim on the scan.
     *
     * The radio stops only if nothing else needs it. A selected advertisement-only machine does,
     * for the whole session, which is why this is no longer an unconditional stop.
     */
    fun stopScan() = guardScanPerm("stopScan()") {
        releaseScanLease(ScanLeaseOwner.DISCOVERY_UI)
    }

    /* ───────────────────────────── connections ───────────────────────────── */

    /**
     * FTMS connect.
     *
     * ## Bonding policy: do not bond proactively
     *
     * FTMS does not require a bond. The Fitness Machine Service exposes its
     * characteristics with plain read/write permissions, not the ENCRYPTED
     * variants, so pairing buys nothing on a conformant machine - and on several
     * it actively costs a connection.
     *
     * This engine previously forced a bond twice on every connect: once before
     * `connectGatt` and again from a background coroutine as soon as the GATT
     * object existed. The observed failure was:
     *
     *     ensureBondOrPrompt: requestBondCompat() -> false (initialState=11)
     *     onClientConnectionState() - status=22        (TERMINATE_LOCAL_HOST)
     *     ensureBondOrPrompt: final bond state=10      (BOND_NONE)
     *
     * The bond attempt raced service discovery, failed, and the local host tore
     * the link down before onServicesDiscovered ever arrived - so CCCDs were
     * never armed, control was never granted, and Start/Resume timed out. A
     * peripheral that requires no encryption has no reason to accept a bond
     * request, so this is the expected outcome, not a device quirk.
     *
     * The correct behaviour is to let Android escalate security **on demand**:
     * if a peer answers a read or write with Insufficient Authentication or
     * Insufficient Encryption, the platform starts bonding by itself and retries
     * the operation. Machines that genuinely require encryption still work; ones
     * that do not are left alone.
     *
     * [preBondIfNeeded] therefore defaults to false. It is retained rather than
     * deleted so a specific machine that is known to need a bond up front can opt
     * in at the call site, and so [promptPairing] remains available as an
     * explicit user action.
     */
    @Suppress("DEPRECATION")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun connectLeGatt(
        device: BluetoothDevice,
        callback: BluetoothGattCallback,
    ): BluetoothGatt? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
        val settings = BluetoothGattConnectionSettings.Builder()
            .setAutoConnectEnabled(false)
            .setTransport(BluetoothDevice.TRANSPORT_LE)
            .build()
        device.connectGatt(settings, context.mainExecutor, callback)
    } else {
        // API 30-36 compatibility path; all context-based overloads are deprecated in API 37.
        device.connectGatt(context, /* autoConnect = */ false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun handlersForServices(
        services: Set<BtEngineState.BtServiceType>,
        machineConnection: Boolean,
    ): List<BtEngineGattCore.ProtocolHandler> = buildList {
        // Every machine protocol is offered as a candidate; the discovered service UUIDs
        // decide which of them actually activates. `activateMatchingProtocolHandlers` retains
        // only handlers whose `primaryServices` are present, so an FTMS-only treadmill still
        // ends up with exactly one active handler and a Chrono with exactly one.
        //
        // **This also closes a Phase 5a gap.** Until 2026-08-12 this line named FTMS alone,
        // so a Concept2 PM5 advertising its Erg service and no FTMS was given only an
        // `FtmsProtocolHandler`, which could not activate — a dead session on a machine whose
        // adapter, matcher, candidate ranking and decoders were all complete and correct. The
        // handler registry and `MachineAdapterPolicy` both knew about Concept2; the one line
        // that constructs handlers did not, and no test asked it to. Adding Precor beside it
        // rather than adding a second special case is what keeps the next family from
        // reproducing the same omission.
        if (machineConnection) {
            MACHINE_PROTOCOLS.filter(handlerFactory::supports)
                .forEach { addAll(handlerFactory.createForConnection(it)) }
        }
        // One handler per assigned service that has one, with no per-service
        // branches: a running strap advertises both 0x180D and 0x1814 on one
        // connection and both must activate, which Phase 0.5 made possible and
        // Phase 1 was the first family to need. See [sensorProtocolFor] for why
        // the mapping is not repeated here.
        services.mapNotNull(::sensorProtocolFor).distinct()
            .filter(handlerFactory::supports).forEach { protocol ->
                addAll(handlerFactory.createForConnection(protocol))
            }
    }.distinctBy { handler -> handler::class.java.name to handler.primaryServices }

    private fun recoveryTarget(
        address: String,
        machineConnection: Boolean,
        services: Set<BtEngineState.BtServiceType>,
        cscRequestedRoles: Set<BtEngineState.CscRole>? = null,
    ) = LinkRecoveryTarget(
        recoveryKey = recoveryKey(address, machineConnection),
        address = canonicalConnectionAddress(address),
        machineConnection = machineConnection,
        activeServices = services,
        cscRequestedRoles = cscRequestedRoles,
    )

    private fun recoveryTargetFor(context: ConnectionContext): LinkRecoveryTarget {
        val activeServices = context.handlers.flatMapTo(linkedSetOf()) { handler ->
            handler.primaryServices.mapNotNull(::serviceTypeForUuid)
        }.ifEmpty {
            setOf(
                if (context.machineConnection) BtEngineState.BtServiceType.FTMS
                else BtEngineState.BtServiceType.HeartRate
            )
        }
        return recoveryTarget(
            context.address,
            context.machineConnection,
            activeServices,
            context.cscRequestedRoles,
        )
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connectMachine(address: String): Boolean =
        selectMachine(address) != MachineSelectionOutcome.FAILED

    /**
     * Select the machine at [address] by whatever route its transport requires (plan §6.6).
     *
     * The branch is here rather than in the UI because the evidence for it is here: the discovery
     * catalog knows whether this device was admitted as a GATT family or an advertisement-only
     * one. Callers need the outcome because the affordances differ — a GATT machine gets the
     * CCCD/control watcher and a disconnect button, and an advertisement-only one has neither,
     * because there is no link to watch or to close.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun selectMachine(address: String): MachineSelectionOutcome {
        val device = state.discoveredDevices.value.values.firstOrNull {
            canonicalScanAddress(it.address) == canonicalScanAddress(address)
        }
        return when (machineSelectionRoute(device)) {
            MachineSelectionRoute.ADVERTISEMENT_SELECT -> {
                if (selectAdvertisementMachine(requireNotNull(device))) {
                    MachineSelectionOutcome.ADVERTISEMENT_SELECTED
                } else {
                    MachineSelectionOutcome.FAILED
                }
            }
            MachineSelectionRoute.GATT_CONNECT ->
                if (connectFtms(address)) {
                    MachineSelectionOutcome.GATT_CONNECTING
                } else {
                    MachineSelectionOutcome.FAILED
                }
        }
    }

    /**
     * Select an advertisement-only machine.
     *
     * Deliberately short, and deliberately not `connectFtms`: no `getRemoteDevice`, no
     * `ConnectionContext`, no GATT callback, no bonding, no recovery target, and the scan is
     * *started* rather than stopped, because for this machine the scan is the telemetry.
     */
    private fun selectAdvertisementMachine(device: BtEngineState.DiscoveredDevice): Boolean {
        if (!handlerSet.includesAdvertisementMachine()) return false
        val systemId = device.advertisementSystemId
        val address = canonicalScanAddress(device.address)
        if (systemId == null || address.isBlank()) {
            // The row is admitted but has not yet carried a decodable field, so there is no system
            // ID to filter on. Refusing is honest: selecting on address alone would attach to
            // whichever bike answers first, which is the mistake this feature exists to avoid.
            Timber.tag(BtEngineState.TAG).w(
                "selectAdvertisementMachine refused: no system ID observed yet for %s",
                device.name,
            )
            return false
        }
        // Exactly one machine origin at a time (§6.6): a GATT machine is disconnected here rather
        // than left running beside a bike that has replaced it in fusion.
        state.connectedDevices.value
            .filter { it.machineConnection }
            .forEach { runCatching { disconnectDevice(it.connectionKey) } }

        state.resetFtmsCapabilities()
        metricFusion.setPersona(FusionPersona.RIDE, elapsedRealtimeMs())
        keiserSource.select(
            selection = KeiserSelection(
                address = address,
                systemId = systemId,
                // Read once, here, from the row that was actually chosen. The row itself is pruned
                // the moment the bike rotates its address, so re-deriving this later would find
                // nothing to derive it from.
                model = KeiserSelection.modelFromAdvertisedName(device.name),
            ),
            nowMs = elapsedRealtimeMs(),
        )
        return true
    }

    /**
     * End an advertisement-only machine selection without affecting ordinary discovery scanning.
     * The source owns the telemetry scan lease, fusion origin, and displayed selection state, so
     * all teardown must pass through its lifecycle rather than clearing UI state independently.
     */
    fun deselectAdvertisementMachine() {
        keiserSource.end(KeiserEndReason.DESELECTED, elapsedRealtimeMs())
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connectFtms(
        address: String,
        activity: ActivityType,
        preBondIfNeeded: Boolean = false
    ): Boolean = connectFtmsInternal(
        address = address,
        activity = activity,
        preBondIfNeeded = preBondIfNeeded
    )

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connectFtms(
        address: String,
        preBondIfNeeded: Boolean = false
    ): Boolean = connectFtmsInternal(
        address = address,
        activity = null,              // AUTO mode (no preferred activity)
        preBondIfNeeded = preBondIfNeeded
    )

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun connectFtmsInternal(
        address: String,
        activity: ActivityType?,      // nullable: null = AUTO
        preBondIfNeeded: Boolean,
        assignedServices: Set<BtEngineState.BtServiceType> =
            setOf(BtEngineState.BtServiceType.FTMS),
        recoveryAttempt: Boolean = false,
    ): Boolean = guardConnectPerm(
        op = "connectFtms(address=$address, activity=$activity, preBondIfNeeded=$preBondIfNeeded)",
        default = false
    ) {
        if (MACHINE_PROTOCOLS.none(handlerFactory::supports)) return@guardConnectPerm false
        Timber.tag(BtEngineState.TAG).i(
            "connectFtms address=%s activity=%s",
            address,
            activity ?: "AUTO"
        )

        val dev = try { adapter?.getRemoteDevice(address) } catch (_: IllegalArgumentException) { null }
        if (dev == null) return@guardConnectPerm false

        // Exactly one machine origin at a time (§6.6). Ending the advertisement source here also
        // gives up its scan lease, so the stopScan() below can actually stop the radio.
        keiserSource.end(KeiserEndReason.DESELECTED, elapsedRealtimeMs())
        stopScan()
        if (!recoveryAttempt) resetMachineSessionState()

        // Opt-in only. See the bonding-policy note on connectFtms: bonding
        // proactively raced service discovery and killed the link. Android
        // escalates security by itself if the peer ever demands it.
        if (preBondIfNeeded && dev.bondState != BluetoothDevice.BOND_BONDED) {
            Timber.tag(BtEngineState.TAG)
                .i("pre-connect bond explicitly requested for %s", address)
            runCatching { dev.requestBondCompat() }
                .onFailure {
                    Timber.tag(BtEngineState.TAG)
                        .w(it, "createBond() pre-connect failed/denied")
                }
        }

        // Record user intent (ActivityType may be null for FitBridge auto-mode).
        desiredAddress = address
        desiredActivity = activity

        state.resetFtmsControl()
        earlyBondScheduled = false

        val connection = state.openConnectionContext(
            LegacyConnectionRole.FTMS,
            address,
            handlers = handlersForServices(assignedServices, machineConnection = true),
        )
        val cb = BtEngineGattCore.build(this@BtEngine, connection)
        val connectedGatt = connectLeGatt(dev, cb)
        val attached = connectedGatt?.let { state.attachGatt(connection, it) } == true
        if (!attached) state.clearConnection(connection)

        val created = attached
        if (created && !recoveryAttempt) {
            linkRecovery.registerSelection(
                recoveryTarget(address, machineConnection = true, assignedServices)
            )
        }
        if (created && preBondIfNeeded) {
            // Only when bonding was explicitly asked for. This used to run on
            // every connect and is what produced the TERMINATE_LOCAL_HOST
            // disconnect before service discovery completed.
            scheduleEarlyBonding(dev)
        }
        created
    }

    /**
     * Schedule a single background bonding attempt for the current FTMS device.
     *
     * - Does not fail the connection if secure bonding is not available or is rejected.
     * - Intended to line up with FTMS requirements on some vendors that mandate encryption
     *   for Control Point or Supported-* range reads.
     * - If the device is already bonded, [ensureBondOrPrompt] will quickly return.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun scheduleEarlyBonding(device: BluetoothDevice) {
        if (earlyBondScheduled) {
            Timber.tag(BtEngineState.TAG).d(
                "scheduleEarlyBonding(): early bond already scheduled for %s; skipping",
                device.address
            )
            return
        }
        earlyBondScheduled = true

        scope.launch {
            runCatching {
                ensureBondOrPrompt(context, device, timeoutMs = 12_000)
            }.onFailure {
                Timber.tag(BtEngineState.TAG).w(
                    it,
                    "scheduleEarlyBonding(): ensureBondOrPrompt() threw for %s",
                    device.address
                )
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnectFtms() {
        val connection = state.connectionRegistry.current(LegacyConnectionRole.FTMS)
        if (!hasConnectPermission()) {
            Timber.tag(BtEngineState.TAG).w("disconnectFtms skipped: BLUETOOTH_CONNECT not granted — clearing local state only")
            connection?.let {
                state.setConnectionState(it, android.bluetooth.BluetoothProfile.STATE_DISCONNECTED)
                metricFusion.onDisconnected(it.connectionId)
                state.clearConnection(it)
            }
            state.resetFtmsControl()
            featureReadRetryScheduled = false
            earlyBondScheduled = false
            resetMachineSessionState()
            return
        }
        guardConnectPerm(op = "disconnectFtms()", default = Unit) {
            val termination = connection?.terminationHook
            runCatching { connection?.gatt?.disconnect() }
            if (termination != null) {
                termination("explicit FTMS disconnect")
            } else {
                runCatching { connection?.gatt?.close() }
                connection?.let {
                    state.setConnectionState(it, android.bluetooth.BluetoothProfile.STATE_DISCONNECTED)
                    metricFusion.onDisconnected(it.connectionId)
                    state.clearConnection(it)
                }
            }
            state.resetFtmsControl()
            featureReadRetryScheduled = false
            earlyBondScheduled = false
            resetMachineSessionState()
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connectSensor(
        address: String,
        serviceType: BtEngineState.BtServiceType,
        cscRequestedRoles: Set<BtEngineState.CscRole>? = null,
    ): Boolean = connectSensorInternal(
        address,
        setOf(serviceType),
        recoveryAttempt = false,
        cscRequestedRoles = cscRequestedRoles,
    )

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun connectSensorInternal(
        address: String,
        assignedServices: Set<BtEngineState.BtServiceType>,
        recoveryAttempt: Boolean,
        cscRequestedRoles: Set<BtEngineState.CscRole>? = null,
    ): Boolean = guardConnectPerm("connectSensor(address=$address, services=$assignedServices)", false) {
            Timber.tag(BtEngineState.TAG).i(
                "connectSensor %s services=%s recovery=%s",
                address,
                assignedServices,
                recoveryAttempt,
            )
            if (address.isBlank()) return@guardConnectPerm false
            if (!assignedServices.all(handlerSet::includesSensor)) return@guardConnectPerm false
            val candidateCscCapability = cscRoleCapabilityForAddress(address)
            if (assignedServices.any { serviceType ->
                    !canAssignSensorService(
                        address,
                        serviceType,
                        state.connectionRegistry.connectedDevices.value,
                        candidateCscCapability,
                    )
                }
            ) {
                Timber.tag(BtEngineState.TAG).w(
                    "connectSensor rejected by per-class limit: address=%s services=%s",
                    address,
                    assignedServices,
                )
                return@guardConnectPerm false
            }
            if (state.connectionRegistry.contextsForAddress(address).isNotEmpty()) {
                return@guardConnectPerm assignedServices.all { serviceType ->
                    activateServiceAtAddress(address, serviceType, cscRequestedRoles)
                }
            }
            // A new sensor link is admissible when a handler exists that can decode
            // the service being asked for.
            //
            // This used to read `assignedServices.any { it != HeartRate } -> false`,
            // dating from when HRM was the only sensor handler. It outlived that:
            // Phase 1 added RSC to the picker, and selecting a footpod that was not
            // already connected returned false with no log line at all. Same-link
            // activation worked - the branch above - so adding RSC to an
            // already-connected strap succeeded while a dedicated footpod was
            // silently refused. That is the exact §4a case, failing in the exact
            // half that §4a exists to protect, observed 2026-08-07 with a Zwift
            // RunPod.
            //
            // Deriving the gate from the handler factory instead of naming a
            // service means Phases 2 and 3 open CSC and CPS by registering their
            // handlers, with nothing here to remember to change.
            val handlers = handlersForServices(assignedServices, machineConnection = false)
            if (handlers.isEmpty()) {
                Timber.tag(BtEngineState.TAG).w(
                    "connectSensor refused: no handler is registered for %s on %s",
                    assignedServices,
                    address,
                )
                return@guardConnectPerm false
            }
            stopScan()
            val dev = try { adapter?.getRemoteDevice(address) } catch (_: IllegalArgumentException) { null }
            if (dev == null) {
                Timber.tag(BtEngineState.TAG).w("connectSensor: invalid address %s", address)
                return@guardConnectPerm false
            }
            val connection = state.openAdditionalSensorContext(address, handlers = handlers)
            if (BtEngineState.BtServiceType.CSC in assignedServices) {
                state.setCscRoleCapability(connection, candidateCscCapability)
                state.connectionRegistry.setCscRequestedRoles(connection, cscRequestedRoles)
            }
            val cb = BtEngineGattCore.build(this@BtEngine, connection)
            val connectedGatt = connectLeGatt(dev, cb)
            val attached = connectedGatt?.let { state.attachGatt(connection, it) } == true
            if (!attached) state.clearConnection(connection)
            if (attached && !recoveryAttempt) {
                linkRecovery.registerSelection(
                    recoveryTarget(
                        address,
                        machineConnection = false,
                        assignedServices,
                        cscRequestedRoles,
                    )
                )
            }
            attached
        }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connectHrm(address: String): Boolean =
        connectSensor(address, BtEngineState.BtServiceType.HeartRate)

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnectHrm() {
        val connection = state.connectionRegistry.current(LegacyConnectionRole.HRM)
        if (!hasConnectPermission()) {
            Timber.tag(BtEngineState.TAG).w("disconnectHrm skipped: BLUETOOTH_CONNECT not granted — clearing local HRM state only")
            connection?.let {
                state.setConnectionState(it, android.bluetooth.BluetoothProfile.STATE_DISCONNECTED)
                state.clearConnection(it)
            }
            state.hrmMeasurementChar = null
            state.hrmCtrlPointChar = null
            state.hrmBodyLocChar = null
            state.setExternalHeartRate(null)
            connection?.let { metricFusion.onDisconnected(it.connectionId) }
            return
        }
        guardConnectPerm(op = "disconnectHrm()", default = Unit) {
            val termination = connection?.terminationHook
            runCatching { connection?.gatt?.disconnect() }
            if (termination != null) {
                termination("explicit HRM disconnect")
            } else {
                runCatching { connection?.gatt?.close() }
                connection?.let {
                    state.setConnectionState(it, android.bluetooth.BluetoothProfile.STATE_DISCONNECTED)
                    state.clearConnection(it)
                }
                state.hrmMeasurementChar = null
                state.hrmCtrlPointChar = null
                state.hrmBodyLocChar = null
                state.setExternalHeartRate(null)
                connection?.let { metricFusion.onDisconnected(it.connectionId) }
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnectDevice(connectionKey: String): Boolean =
        guardConnectPerm("disconnectDevice($connectionKey)", default = false) {
            val connection = state.connectionRegistry.contextForKey(connectionKey)
                ?: return@guardConnectPerm false
            cancelRecoveryTarget(recoveryTargetFor(connection).recoveryKey)
            val termination = connection.terminationHook
            runCatching { connection.gatt?.disconnect() }
            if (termination != null) {
                termination("explicit device disconnect")
            } else {
                runCatching { connection.gatt?.close() }
                state.setConnectionState(connection, BluetoothProfile.STATE_DISCONNECTED)
                metricFusion.onDisconnected(connection.connectionId)
                state.clearConnection(connection)
            }
            if (connection.machineConnection) {
                state.resetFtmsControl()
                featureReadRetryScheduled = false
                earlyBondScheduled = false
                resetMachineSessionState()
            }
            true
        }

    fun beginRecoverySession() {
        val targets = state.connectionRegistry.connectedDevices.value.mapNotNull { link ->
            state.connectionRegistry.contextForKey(link.connectionKey)?.let(::recoveryTargetFor)
        }
        linkRecovery.beginSession(targets)
    }

    fun endRecoverySession() {
        linkRecovery.endSession()
        synchronized(recoveryJobs) {
            recoveryJobs.values.forEach(Job::cancel)
            recoveryJobs.clear()
        }
    }

    internal fun onPhysicalLinkConnected(context: ConnectionContext) {
        val key = recoveryTargetFor(context).recoveryKey
        linkRecovery.onConnected(key)
        synchronized(recoveryJobs) { recoveryJobs.remove(key)?.cancel() }
    }

    internal fun onPhysicalLinkTerminating(context: ConnectionContext, reason: String) {
        state.cscNativeEvents.remove(context.connectionId)
        state.cpsNativeEvents.remove(context.connectionId)
        state.treadmillNativePace.remove(context.connectionId)
        context.handlers.filterIsInstance<FlywheelProtocolHandler>().forEach { handler ->
            handler.onLinkTerminating(context.connectionId, reason, elapsedRealtimeMs())
        }
        val target = recoveryTargetFor(context)
        if (reason.startsWith("platform disconnect") || reason == "recovery timeout") {
            linkRecovery.updateTarget(target)
            linkRecovery.onUnexpectedDisconnect(target.recoveryKey, elapsedRealtimeMs())
                ?.let(::scheduleRecovery)
        } else {
            cancelRecoveryTarget(target.recoveryKey)
        }
    }

    private fun cancelRecoveryTarget(recoveryKey: String) {
        linkRecovery.cancelTarget(recoveryKey)
        synchronized(recoveryJobs) { recoveryJobs.remove(recoveryKey)?.cancel() }
    }

    private fun scheduleRecovery(attempt: LinkRecoveryAttempt) {
        val key = attempt.target.recoveryKey
        val job = scope.launch {
            val waitMs = (attempt.dueAtMs - elapsedRealtimeMs()).coerceAtLeast(0L)
            delay(waitMs)
            if (!linkRecovery.shouldRun(attempt)) return@launch
            val connected = if (attempt.target.machineConnection) {
                connectFtmsInternal(
                    address = attempt.target.address,
                    activity = null,
                    preBondIfNeeded = false,
                    assignedServices = attempt.target.activeServices,
                    recoveryAttempt = true,
                )
            } else {
                connectSensorInternal(
                    address = attempt.target.address,
                    assignedServices = attempt.target.activeServices,
                    recoveryAttempt = true,
                    cscRequestedRoles = attempt.target.cscRequestedRoles,
                )
            }
            if (!connected) {
                linkRecovery.onAttemptFailed(attempt, elapsedRealtimeMs())
                    ?.let(::scheduleRecovery)
                return@launch
            }

            delay(RECOVERY_CONNECT_TIMEOUT_MS)
            if (!linkRecovery.shouldRun(attempt)) return@launch
            state.connectionRegistry.contextsForAddress(attempt.target.address)
                .firstOrNull { it.connectionState.value != LinkState.CONNECTED }
                ?.terminationHook
                ?.invoke("recovery timeout")
        }
        synchronized(recoveryJobs) {
            recoveryJobs.put(key, job)?.cancel()
        }
    }

    /** Activate another supported service handler on an existing physical link. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun activateServiceAtAddress(
        address: String,
        serviceType: BtEngineState.BtServiceType,
        cscRequestedRoles: Set<BtEngineState.CscRole>? = null,
        expectedContext: ConnectionContext? = null,
    ): Boolean = guardConnectPerm("activateServiceAtAddress($address, $serviceType)", default = false) {
        if (expectedContext != null && !state.connectionRegistry.isCurrent(expectedContext)) return@guardConnectPerm false
        val targetContext = expectedContext ?: state.connectionRegistry.contextsForAddress(address).firstOrNull()
        val candidateCscCapability = cscRoleCapabilityForAddress(address)
        if (!canAssignSensorService(
                address,
                serviceType,
                state.connectionRegistry.connectedDevices.value,
                candidateCscCapability,
            )
        ) {
            Timber.tag(BtEngineState.TAG).w(
                "activateServiceAtAddress rejected by per-class limit: address=%s service=%s",
                address,
                serviceType,
            )
            return@guardConnectPerm false
        }
        val protocol = sensorProtocolFor(serviceType) ?: return@guardConnectPerm false
        if (!handlerFactory.supports(protocol)) return@guardConnectPerm false
        if (serviceType == BtEngineState.BtServiceType.CSC) {
            targetContext?.let { context ->
                state.connectionRegistry.setCscRequestedRoles(context, cscRequestedRoles)
            }
        }
        val activated = if (expectedContext != null) {
            state.connectionRegistry.activateHandlerOn(expectedContext, handlerFactory.create(protocol))
        } else {
            state.connectionRegistry.activateHandlerOn(address, handlerFactory.create(protocol))
        }
        if (activated) {
            targetContext?.takeIf(state.connectionRegistry::isCurrent)?.let { context ->
                if (serviceType == BtEngineState.BtServiceType.CSC) {
                    state.setCscRoleCapability(context, candidateCscCapability)
                }
                linkRecovery.updateTarget(recoveryTargetFor(context))
            }
        }
        activated
    }

    private fun cscRoleCapabilityForAddress(
        address: String,
    ): BtEngineState.CscRoleCapability? = state.discoveredDevices.value.values
        .firstOrNull { device -> device.address.equals(address, ignoreCase = true) }
        ?.appearance
        ?.let(::cscRoleCapabilityFromAppearance)

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun activateServiceOn(
        connectionKey: String,
        serviceType: BtEngineState.BtServiceType,
        cscRequestedRoles: Set<BtEngineState.CscRole>? = null,
    ): Boolean {
        val context = state.connectionRegistry.contextForKey(connectionKey) ?: return false
        return activateServiceAtAddress(context.address, serviceType, cscRequestedRoles, context)
    }

    /** Manual hook to force a pairing UI for a known MAC (e.g., user action) */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun promptPairing(address: String) = guardConnectPerm(op = "promptPairing($address)", default = Unit) {
        val d = try { adapter?.getRemoteDevice(address) } catch (_: IllegalArgumentException) { null }
        if (d == null) {
            Timber.tag(BtEngineState.TAG).w("promptPairing: invalid address")
            return@guardConnectPerm
        }
        if (d.bondState == BluetoothDevice.BOND_BONDED) {
            Timber.tag(BtEngineState.TAG).i("Device %s already bonded", d.address)
            return@guardConnectPerm
        }
        Timber.tag(BtEngineState.TAG).i("Requesting bond for %s", d.address)
        runCatching { d.requestBondCompat() }.onFailure {
            Timber.tag(BtEngineState.TAG).w(it, "createBond() failed")
        }
    }

    // Release all Bluetooth resources. Each call has its own permission guards.
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun release() {
        endRecoverySession()
        keiserSource.end(KeiserEndReason.SESSION_ENDED, elapsedRealtimeMs())
        runCatching { stopScanForShutdown() }
        state.connectedDevices.value.map { it.connectionKey }.forEach { connectionKey ->
            runCatching { disconnectDevice(connectionKey) }
        }
        resetMachineSessionState()
    }

    /**
     * Identity of the machine a command would reach right now: the connected machine link's
     * connection id (a reconnect is a new id), or the selected advertisement-only machine's origin
     * (a reselection is a new origin), or null with neither.
     */
    internal fun currentMachineKey(): Any? =
        (if (isFtmsConnected) state.connectionRegistry.current(LegacyConnectionRole.FTMS)?.connectionId else null)
            ?: keiserSource.originId()

    /** Terminal client shutdown also cancels jobs; legacy release remains reusable during migration. */
    internal fun closeClient() {
        try {
            release()
        } finally {
            try {
                // Permission revocation must not leave a logically live context or a recovery job.
                state.connectedDevices.value.forEach { link ->
                    state.connectionRegistry.contextForKey(link.connectionKey)?.let { connection ->
                        val gatt = connection.gatt
                        runCatching { connection.terminationHook?.invoke("client closed") }
                        runCatching { gatt?.close() }
                        metricFusion.onDisconnected(connection.connectionId)
                        state.clearConnection(connection)
                    }
                }
            } finally {
                job.cancel()
            }
        }
    }

    /* ───────────────────────────── session orchestration ───────────────────────────── */

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun start(
        address: String,
        activity: ActivityType,
        allowScanFallback: Boolean = true
    ): Boolean = guardConnectPerm(
        op = "start(address=$address, activity=$activity, allowScanFallback=$allowScanFallback)",
        default = false
    ) {
        synchronized(startLock) {
            if (isStarted) {
                Timber.tag(BtEngineState.TAG)
                    .d("start(): already started → noop (addr=%s, activity=%s)", address, activity)
                return@guardConnectPerm true
            }
            isStarted = true
        }

        var result = false
        try {
            desiredAddress = address
            desiredActivity = activity

            // Direct connect. No pre-bond: see the bonding-policy note on connectFtms.
            val direct = connectFtms(address, activity)
            if (direct) {
                result = true
            } else if (!allowScanFallback) {
                Timber.tag(BtEngineState.TAG).d("start(): direct failed and scan fallback disabled")
                result = false
            } else {
                Timber.tag(BtEngineState.TAG).d("start(): direct failed → scanning for %s", address)
                result = try {
                    if (!hasScanPermission()) {
                        Timber.tag(BtEngineState.TAG)
                            .w("start(): scan fallback requested but BLUETOOTH_SCAN not granted")
                        false
                    } else {
                        startScanForImpl(ScanLeaseOwner.DISCOVERY_UI)
                        val found = withTimeoutOrNull(12_000) {
                            state.devices.first { it.containsKey(address) }
                        }
                        releaseScanLease(ScanLeaseOwner.DISCOVERY_UI)
                        if (found != null) connectFtms(address, activity) else false
                    }
                } catch (t: Throwable) {
                    Timber.tag(BtEngineState.TAG).w(t, "start(): scan/connect fallback failed")
                    runCatching { releaseScanLease(ScanLeaseOwner.DISCOVERY_UI) }
                    false
                }
            }
            result
        } finally {
            if (!result) {
                synchronized(startLock) { isStarted = false }
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun stop() = guardConnectPerm(op = "stop()", default = Unit) {
        synchronized(startLock) { isStarted = false }
        stopSessionElapsedClock()
        clearCscSession()
        state.setIsRunning(false)
        state.setActiveSession(false)
        metricFusion.endSession()
        runCatching { stopOrPause() }
        release()
    }

    fun markSessionStarted(activity: ActivityType, address: String?) {
        desiredActivity = activity
        desiredAddress = address
        resetFlywheelSyntheticSessions(state.connectionRegistry.machineContexts())
        metricFusion.startSession(activity.fusionPersona())
        synchronized(cscSessionLock) {
            cscSessionGeneration += 1
            state.cscNativeEvents.clear()
            state.cpsNativeEvents.clear()
            state.treadmillNativePace.clear()
            activeCscSession = when (activity) {
                ActivityType.BIKING -> CscSessionSnapshot(
                    cscSessionGeneration,
                    CscWorkoutPersona.BIKE,
                    cscCalibration,
                )
                ActivityType.ELLIPTICAL -> CscSessionSnapshot(
                    cscSessionGeneration,
                    CscWorkoutPersona.ELLIPTICAL,
                    cscCalibration,
                )
                else -> null
            }
        }
        state.setActiveSession(true)
        startSessionElapsedClock()
    }

    fun markSessionStopped() {
        stopSessionElapsedClock()
        clearCscSession()
        state.setActiveSession(false)
        metricFusion.endSession()
    }

    fun setCscCalibration(wheelCircumferenceMm: Int, ellipticalStrideLengthMm: Int) {
        val updated = CscCalibration(wheelCircumferenceMm, ellipticalStrideLengthMm)
        synchronized(cscSessionLock) {
            cscCalibration = updated
            activeCscSession = activeCscSession?.copy(calibration = updated)
        }
        Timber.tag(BtEngineState.TAG).i(
            "CSC calibration updated wheelMm=%d ellipticalStrideMm=%d",
            wheelCircumferenceMm,
            ellipticalStrideLengthMm,
        )
    }

    internal fun acceptWheelCircumference(millimetres: Double) {
        val wheelMm = millimetres.roundToInt()
        if (wheelMm !in BleDefaults.MIN_CSC_WHEEL_CIRCUMFERENCE_MM..
            BleDefaults.MAX_CSC_WHEEL_CIRCUMFERENCE_MM
        ) {
            Timber.tag(BtEngineState.TAG).w(
                "Accepted KICKR circumference %.1f mm is outside local CSC calibration bounds",
                millimetres,
            )
            return
        }
        synchronized(cscSessionLock) {
            val updated = cscCalibration.copy(wheelCircumferenceMm = wheelMm)
            cscCalibration = updated
            activeCscSession = activeCscSession?.copy(calibration = updated)
        }
    }

    internal fun cscSessionSnapshot(): CscSessionSnapshot? = activeCscSession

    private fun clearCscSession() = synchronized(cscSessionLock) {
        cscSessionGeneration += 1
        activeCscSession = null
        state.cscNativeEvents.clear()
        state.cpsNativeEvents.clear()
        state.treadmillNativePace.clear()
    }

    internal fun isSelectedEllipticalCsc(context: ConnectionContext): Boolean {
        val selected = state.connectionRegistry.connectedDevices.value
            .asSequence()
            .filter { BtEngineState.BtServiceType.CSC in it.activeServices }
            .minByOrNull { it.connectionKey.substringAfterLast('#').toLongOrNull() ?: Long.MAX_VALUE }
            ?.connectionKey
        return selected == "${context.connectionId.address}#${context.connectionId.linkGeneration}"
    }

    /**
     * Fallback session clock. Fusion prefers a machine's own Elapsed Time while it is fresh, so
     * this only reaches the UI for a machine that does not report elapsed at all, or one that has
     * stopped reporting it. Bounded by the session: it is cancelled wherever fusion's session ends.
     */
    private fun startSessionElapsedClock() {
        stopSessionElapsedClock()
        val startedAtMs = elapsedRealtimeMs()
        sessionElapsedJob = scope.launch {
            var publishedSeconds = -1
            metricFusion.updateSessionElapsed(0)
            while (true) {
                delay(SESSION_ELAPSED_TICK_MS)
                val seconds = ((elapsedRealtimeMs() - startedAtMs) / 1_000L).toInt()
                // Republish only on a second boundary; fusion reselects on every submission.
                if (seconds != publishedSeconds) {
                    publishedSeconds = seconds
                    metricFusion.updateSessionElapsed(seconds)
                }
            }
        }
        Timber.tag(BtEngineState.TAG).i("Session elapsed clock started")
    }

    private fun stopSessionElapsedClock() {
        sessionElapsedJob?.let {
            it.cancel()
            Timber.tag(BtEngineState.TAG).i("Session elapsed clock stopped")
        }
        sessionElapsedJob = null
    }

    fun setRunning(running: Boolean) {
        state.setIsRunning(running)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun ensureBluetoothEnabled(activity: Activity) = guardConnectPerm(op = "ensureBluetoothEnabled()", default = Unit) {
        if (adapter?.isEnabled == true) return@guardConnectPerm
        runCatching {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            activity.startActivity(intent)
        }.onFailure {
            runCatching {
                val settingsIntent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                activity.startActivity(settingsIntent)
            }
        }
    }

    /**
     * Unit 9.7: which [MachineControlEndpoint] drives control writes on the current
     * machine connection. FTMS by default -- including when nothing has been selected
     * yet, e.g. before [AdapterLivenessCoordinator] has built liveness candidates for
     * this link -- which is exactly the pre-9.7 behavior for every non-FE-C machine. An
     * FE-C over BLE link only switches once its [FeCOverBleProtocolHandler] instance is
     * actually present in the connection's active handler list, so a link mid-activation
     * is never routed to a not-yet-constructed handler.
     */
    private val ftmsControlEndpoint: MachineControlEndpoint by lazy { FtmsMachineControlEndpoint(this) }

    private fun controlEndpoint(): MachineControlEndpoint {
        val context = state.connectionRegistry.current(LegacyConnectionRole.FTMS)
        val adapterId = context?.let { state.adapterLiveness.selectedAdapterId(it.connectionId) }
        val fecHandler = context?.handlers?.filterIsInstance<FeCOverBleProtocolHandler>()?.firstOrNull()
        val wahooSession = context?.handlers
            ?.filterIsInstance<WahooKickrProtocolHandler>()
            ?.firstOrNull()
            ?.controlSession
            ?.takeIf { it.controlGranted && it.connectionId == context.connectionId }
        val rideOnSession = context?.handlers?.filterIsInstance<RideOnProtocolHandler>()
            ?.firstOrNull()?.controlSession
            ?.takeIf { it.controlGranted && it.connectionId == context.connectionId }
        return when (
            machineControlRoute(
                selectedAdapterId = adapterId,
                hasFeCHandler = fecHandler != null,
                hasWahooControl = wahooSession != null,
                hasRideOnControl = rideOnSession != null,
                // Asked of the source rather than of the connection registry: an advertisement-only
                // machine has no connection to ask about, which is exactly the case being detected.
                hasAdvertisementMachine = keiserSource.selection() != null,
            )
        ) {
            MachineControlRoute.FEC ->
                fecHandler?.let { FeCBleMachineControlEndpoint(this, it) }
                    ?: UnavailableMachineControlEndpoint
            MachineControlRoute.WAHOO ->
                wahooSession?.let {
                    WahooKickrMachineControlEndpoint(it) {
                        state.lifeFitnessProfile.weightKg.toDouble()
                    }
                }
                    ?: UnavailableMachineControlEndpoint
            MachineControlRoute.RIDEON ->
                rideOnSession?.let {
                    RideOnMachineControlEndpoint(
                        it,
                        riderMassKg = { state.lifeFitnessProfile.weightKg.toDouble() },
                    )
                } ?: UnavailableMachineControlEndpoint
            MachineControlRoute.ADVERTISEMENT_ONLY -> AdvertisementOnlyMachineControlEndpoint
            MachineControlRoute.UNAVAILABLE -> UnavailableMachineControlEndpoint
            MachineControlRoute.FTMS -> ftmsControlEndpoint
        }
    }

    internal fun providerMachineCapabilities(): BtEngineState.FtmsCapabilities =
        state.providerMachineCapabilities()

    fun hasSelectedKayakFirstAdapter(): Boolean {
        val context = state.connectionRegistry.current(LegacyConnectionRole.FTMS) ?: return false
        return state.adapterLiveness.selectedAdapterId(context.connectionId) ==
            AdapterLivenessCoordinator.KAYAK_FIRST_ADAPTER_ID
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun startLocalKayakFirstSession(): Boolean =
        guardConnectPerm(op = "startLocalKayakFirstSession()", default = false) {
            val context = state.connectionRegistry.current(LegacyConnectionRole.FTMS)
                ?: return@guardConnectPerm false
            if (!hasSelectedKayakFirstAdapter()) return@guardConnectPerm false
            context.handlers.filterIsInstance<KayakFirstProtocolHandler>()
                .firstOrNull()
                ?.requestLocalStart(this, context)
                ?: false
        }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun stopLocalKayakFirstSession(): Boolean =
        guardConnectPerm(op = "stopLocalKayakFirstSession()", default = false) {
            val context = state.connectionRegistry.current(LegacyConnectionRole.FTMS)
                ?: return@guardConnectPerm false
            if (!hasSelectedKayakFirstAdapter()) return@guardConnectPerm false
            context.handlers.filterIsInstance<KayakFirstProtocolHandler>()
                .firstOrNull()
                ?.requestLocalStop(this, context)
                ?: false
        }

    /* ───────────────────────────── FTMS Control Point ───────────────────────────── */

    /**
     * Best-effort REQUEST_CONTROL helper.
     *
     * - Returns the underlying CP helper result but *callers must treat this as advisory only*.
     * - Failure (false) is logged and should *not* block Start/Stop; those commands will still be
     *   attempted after a timeout in [startOrResume] / [stopOrPause].
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun requestControl(): Boolean = guardConnectPerm(op = "requestControl()", default = false) {
        if (state.haveControlFlow.value) return@guardConnectPerm true
        // Guarded here rather than at each call site so every path — the run screen, the FEATURES
        // retry, btService's facade — declines quietly against a machine that cannot receive.
        val endpoint = controlEndpoint()
        if (endpoint is AdvertisementOnlyMachineControlEndpoint ||
            endpoint === UnavailableMachineControlEndpoint
        ) {
            Timber.tag(BtEngineState.TAG).d(
                "requestControl(): advertisement-only machine has no control point; not requesting",
            )
            return@guardConnectPerm endpoint.requestControl()
        }
        val ok: Boolean = BtEngineControlPoint.requestControl(this)
        if (!ok) {
            Timber.tag(BtEngineState.TAG).w(
                "requestControl(): Control Point request returned false; will still allow Start/Stop after timeout"
            )
        }
        ok
    }

    /**
     * Start or resume the FTMS session via Control Point.
     *
     * Behaviour:
     *  - If CCCDs are not yet marked ready, we:
     *      • log a deferral,
     *      • in the background:
     *          – wait (with timeout) for CCCDs to become ready,
     *          – issue a best-effort [requestControl] and wait (with timeout) for haveControl=true,
     *          – finally send Start CP **even if** CCCDs or control never fully converge.
     *  - If CCCDs are already ready, we:
     *      • optionally issue a best-effort [requestControl] in the background,
     *      • immediately send Start CP once.
     *
     * This ensures devices that either:
     *  - don’t respond to REQUEST_CONTROL, or
     *  - require Start/Stop before acknowledging control,
     * can still be driven, while retaining good behaviour for fully spec-compliant FTMS devices.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun startOrResume(): Boolean = guardConnectPerm(op = "startOrResume()", default = false) {
        val endpoint = controlEndpoint()
        if (endpoint is FeCBleMachineControlEndpoint) {
            return@guardConnectPerm endpoint.startOrResume()
        }
        // A broadcaster is not a machine that has failed to converge; it is a machine with no
        // control surface at all. Taking the FTMS path below would spend sixteen seconds waiting
        // for a CCCD and a control grant that cannot arrive, and log a warning at every step of it.
        if (endpoint is AdvertisementOnlyMachineControlEndpoint) {
            Timber.tag(BtEngineState.TAG).d(
                "startOrResume(): advertisement-only machine has no control point; nothing to send",
            )
            return@guardConnectPerm endpoint.startOrResume()
        }
        if (endpoint === UnavailableMachineControlEndpoint) {
            return@guardConnectPerm false
        }
        if (!state.cccdReady.value) {
            Timber.tag(BtEngineState.TAG).d("startOrResume() deferred until CCCDs ready")
            scope.launch {
                val cccdOk = withTimeoutOrNull(8_000) {
                    state.cccdReady.first { it }
                } != null
                if (!cccdOk) {
                    Timber.tag(BtEngineState.TAG).w(
                        "startOrResume(): CCCDs not ready after timeout → sending Start anyway"
                    )
                }

                // Best-effort REQUEST_CONTROL; non-fatal on failure or timeout.
                val controlOk = withTimeoutOrNull(8_000) {
                    if (!haveControlFlow.value) {
                        val reqOk = requestControl()
                        if (!reqOk) {
                            Timber.tag(BtEngineState.TAG).w(
                                "startOrResume(): requestControl() returned false; still waiting briefly for haveControl"
                            )
                        }
                    }
                    haveControlFlow.first { it }
                } != null
                if (!controlOk) {
                    Timber.tag(BtEngineState.TAG).w(
                        "startOrResume(): did not gain control within timeout; sending Start CP anyway (best-effort)"
                    )
                }

                val ok = BtEngineControlPoint.startOrResume(this@BtEngine)
                if (!ok) {
                    Timber.tag(BtEngineState.TAG).w(
                        "startOrResume(): BtEngineControlPoint.startOrResume() returned false"
                    )
                }
            }
            true
        } else {
            // CCCDs already ready; fire Start immediately, but still try to obtain control in the background.
            scope.launch {
                if (!haveControlFlow.value) {
                    val reqOk = requestControl()
                    if (!reqOk) {
                        Timber.tag(BtEngineState.TAG).w(
                            "startOrResume(): immediate path → requestControl() returned false; proceeding with Start CP"
                        )
                    }
                }
            }

            val ok: Boolean = BtEngineControlPoint.startOrResume(this)
            if (!ok) {
                Timber.tag(BtEngineState.TAG).w(
                    "startOrResume(): immediate BtEngineControlPoint.startOrResume() returned false"
                )
            }
            ok
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun stopOrPause(): Boolean = guardConnectPerm(op = "stopOrPause()", default = false) {
        val endpoint = controlEndpoint()
        if (endpoint is FeCBleMachineControlEndpoint) {
            return@guardConnectPerm endpoint.stopOrPause()
        }
        if (endpoint is AdvertisementOnlyMachineControlEndpoint) {
            Timber.tag(BtEngineState.TAG).d(
                "stopOrPause(): advertisement-only machine has no control point; nothing to send",
            )
            return@guardConnectPerm endpoint.stopOrPause()
        }
        if (endpoint === UnavailableMachineControlEndpoint) {
            return@guardConnectPerm false
        }
        val ok: Boolean = BtEngineControlPoint.stopOrPause(this)
        if (!ok) {
            Timber.tag(BtEngineState.TAG).w(
                "stopOrPause(): BtEngineControlPoint.stopOrPause() returned false"
            )
        }
        ok
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun setTargetSpeedKph(kph: Float): Boolean =
        guardConnectPerm(op = "setTargetSpeedKmh()", default = false) {
            controlEndpoint().setTargetSpeedKph(kph)
        }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun setTargetInclinationPercent(percent: Float): Boolean =
        guardConnectPerm(op = "setTargetInclinationPercent()", default = false) {
            controlEndpoint().setTargetInclinationPercent(percent)
        }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun setTargetResistance(level: Float): Boolean =
        guardConnectPerm(op = "setTargetResistance()", default = false) {
            controlEndpoint().setTargetResistance(level)
        }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun setTargetPowerWatts(watts: Int): Boolean =
        guardConnectPerm(op = "setTargetPowerWatts()", default = false) {
            controlEndpoint().setTargetPowerWatts(watts)
        }

    override fun setWheelCircumferenceMm(millimetres: Float): Boolean =
        guardConnectPerm(op = "setWheelCircumferenceMm()", default = false) {
            controlEndpoint().setWheelCircumferenceMm(millimetres)
        }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun setIndoorBikeSimulation(
        windSpeedMps: Float,
        gradePercent: Float,
        rollingResistanceCoefficient: Float,
        windResistanceCoefficient: Float,
    ): Boolean =
        guardConnectPerm(op = "setIndoorBikeSimulation()", default = false) {
            controlEndpoint().setIndoorBikeSimulation(
                windSpeedMps,
                gradePercent,
                rollingResistanceCoefficient,
                windResistanceCoefficient,
            )
        }

    /* ───────────────────────────── Capability reads ───────────────────────────── */

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readSupportedSpeedRange() {
        if (!hasConnectPermission()) {
            Timber.tag(BtEngineState.TAG).w("readSupportedSpeedRange() skipped: BLUETOOTH_CONNECT not granted")
            return
        }
        val context = machineContext("readSupportedSpeedRange") ?: return
        try {
            pushRead(context, UUID_SUPPORTED_SPEED_RANGE)
        } catch (se: SecurityException) {
            Timber.tag(BtEngineState.TAG).w(se, "readSupportedSpeedRange() rejected by platform")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readSupportedInclinationRange() {
        if (!hasConnectPermission()) {
            Timber.tag(BtEngineState.TAG).w("readSupportedInclinationRange() skipped: BLUETOOTH_CONNECT not granted")
            return
        }
        val context = machineContext("readSupportedInclinationRange") ?: return
        try {
            pushRead(context, UUID_SUPPORTED_INCLINATION_RANGE)
        } catch (se: SecurityException) {
            Timber.tag(BtEngineState.TAG).w(se, "readSupportedInclinationRange() rejected by platform")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readSupportedResistanceRange() {
        if (!hasConnectPermission()) {
            Timber.tag(BtEngineState.TAG).w("readSupportedResistanceRange() skipped: BLUETOOTH_CONNECT not granted")
            return
        }
        val context = machineContext("readSupportedResistanceRange") ?: return
        try {
            pushRead(context, UUID_SUPPORTED_RESISTANCE_RANGE)
        } catch (se: SecurityException) {
            Timber.tag(BtEngineState.TAG).w(se, "readSupportedResistanceRange() rejected by platform")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun readSupportedPowerRange() {
        if (!hasConnectPermission()) {
            Timber.tag(BtEngineState.TAG).w("readSupportedPowerRange() skipped: BLUETOOTH_CONNECT not granted")
            return
        }
        val context = machineContext("readSupportedPowerRange") ?: return
        try {
            pushRead(context, UUID_SUPPORTED_POWER_RANGE)
        } catch (se: SecurityException) {
            Timber.tag(BtEngineState.TAG).w(se, "readSupportedPowerRange() rejected by platform")
        }
    }

    /**
     * Called when the initial FEATURES (0x2ACC) read fails.
     *
     * Behaviour:
     *  - Only schedules once per FTMS connection (guarded by [featureReadRetryScheduled]).
     *  - Requests FTMS control (if not already held), but treats failure as *non-fatal*.
     *  - Waits for [haveControlFlow] to become true (or a timeout).
     *  - Once control is established, re-issues a read of the FEATURES characteristic.
     *
     * NOTES:
     *  - This helper is strictly *best-effort* enrichment. A 2ACC failure must **not** be treated
     *    as a GATT-fatal condition; CCCDs and Control Point operations should already be active.
     *
     * Intended caller: FtmsProtocolHandler in the 2ACC read failure path.
     * Connection + CCCD enablement continue independently; this is a resilience helper only.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    internal fun scheduleFeatureReadRetryAfterControl() =
        guardConnectPerm(op = "scheduleFeatureReadRetryAfterControl()", default = Unit) {
            if (featureReadRetryScheduled) {
                Timber.tag(BtEngineState.TAG).d(
                    "scheduleFeatureReadRetryAfterControl(): retry already scheduled; skipping"
                )
                return@guardConnectPerm
            }

            featureReadRetryScheduled = true
            Timber.tag(BtEngineState.TAG).w(
                "scheduleFeatureReadRetryAfterControl(): initial FEATURES (0x2ACC) read failed (non-fatal); " +
                        "requesting control and will retry after haveControl=true"
            )

            scope.launch {
                try {
                    // Step 1: request control if we don't already have it (non-fatal).
                    if (!haveControlFlow.value) {
                        val controlOk = requestControl()
                        if (!controlOk) {
                            Timber.tag(BtEngineState.TAG).w(
                                "scheduleFeatureReadRetryAfterControl(): requestControl() returned false; " +
                                        "will still wait for haveControl or timeout"
                            )
                        }
                    }

                    // Step 2: wait for haveControl=true (or timeout).
                    val gotControl = withTimeoutOrNull(10_000) {
                        haveControlFlow.first { it }
                    } != null

                    if (!gotControl) {
                        Timber.tag(BtEngineState.TAG).w(
                            "scheduleFeatureReadRetryAfterControl(): did not gain control within timeout; giving up FEATURES retry"
                        )
                        return@launch
                    }

                    Timber.tag(BtEngineState.TAG).i(
                        "scheduleFeatureReadRetryAfterControl(): control established; retrying FEATURES (0x2ACC) read"
                    )

                    // Step 3: retry the FEATURES read (best-effort; non-fatal).
                    try {
                        val context = machineContext("scheduleFeatureReadRetryAfterControl")
                            ?: return@launch
                        pushRead(context, UUID_FITNESS_MACHINE_FEATURE)
                    } catch (se: SecurityException) {
                        Timber.tag(BtEngineState.TAG).w(
                            se,
                            "scheduleFeatureReadRetryAfterControl(): pushRead(FEATURES) rejected by platform (non-fatal)"
                        )
                    } catch (t: Throwable) {
                        Timber.tag(BtEngineState.TAG).w(
                            t,
                            "scheduleFeatureReadRetryAfterControl(): pushRead(FEATURES) threw (non-fatal)"
                        )
                    }
                } finally {
                    Timber.tag(BtEngineState.TAG).d(
                        "scheduleFeatureReadRetryAfterControl(): retry path finished (haveControl=%s, featuresRead=%s)",
                        haveControlFlow.value,
                        state.ftmsCapabilities.featuresRead
                    )
                }
            }
        }

    /* ───────────────────────────── Units / elapsed / laps / HR bindings ───────────────────────────── */

    fun setElapsedFromWorkout(totalSec: Int) {
        metricFusion.updateSessionElapsed(totalSec)
    }

    fun doLap() {
        state.setLapCount(state.lapCount.value + 1)
        state.primeLapFromCurrentTotals()
        state.lapAvgCadenceTotal = 0.0
        state.lapAvgCadenceCount = 0
        state.setLapElapsedTimeSec(0)
        state.setLapDistanceMeters(0)
        state.setLapAvgSpeedMps(null)
        state.setLapPaceMinPerKm(null)
        state.setLapRowPaceMinPer500m(null)
        state.setLapStrokeCount(0)
        state.setLapSteps(0)
        state.setLapCadenceSpm(null)
    }

    /**
     * Ends the session and resets everything it accumulated: stops the elapsed clock, clears the CSC session
     * and the fusion session, clears the session data (see [clearSessionData]), zeroes elapsed time and the lap
     * values, and marks the session inactive. Call it when the run screen closes.
     */
    fun resetSession() {
        stopSessionElapsedClock()
        clearCscSession()
        metricFusion.endSession()
        state.clearSessionData()

        state.setElapsedSeconds(0)
        state.setLapElapsedTimeSec(0)
        state.setLapDistanceMeters(0)

        state.setLapAvgSpeedMps(null)
        state.setLapPaceMinPerKm(null)
        state.setLapRowPaceMinPer500m(null)
        state.setLapStrokeCount(0)
        state.setLapSteps(0)
        state.setLapCadenceSpm(null)

        state.setActiveSession(false)
        state.setIsRunning(false)
    }

    /** Life Fitness handshake profile, pushed from the settings screen (plan §6, D56). */
    fun setLifeFitnessProfileFromApp(
        profile: LifeFitnessProfile,
        ageYears: Int,
    ) {
        state.setLifeFitnessProfileFromApp(profile, ageYears)
    }

    fun setHrMaxFromApp(value: Int?) {
        state.setHrMaxFromApp(value)
        metricFusion.recalculateHeartRateZones()
    }

    fun setRestingHrFromApp(value: Int?) {
        state.setRestingHrFromApp(value)
        metricFusion.recalculateHeartRateZones()
    }

    fun setHrZoneModeFromApp(mode: HrZoneMode) {
        state.setHrZoneModeFromApp(mode)
        metricFusion.recalculateHeartRateZones()
    }

    fun bindHrMax(flow: StateFlow<Int?>) {
        state.hrMaxFromApp = flow
        metricFusion.recalculateHeartRateZones()
    }

    fun bindRestingHr(flow: StateFlow<Int?>) {
        state.restingHrFromApp = flow
        metricFusion.recalculateHeartRateZones()
    }

    fun bindHrZoneMode(flow: StateFlow<HrZoneMode>) {
        state.hrZoneModeFromApp = flow
        metricFusion.recalculateHeartRateZones()
    }

    /* ───────────────────────────── machine capability probe (LIB-9f) ───────────────────────────── */

    private var machineProbeJob: Job? = null

    /**
     * One probe per machine link: PROBING from CONNECTED until [MachineProbe] says READY, IDLE
     * when the link is not connected. Feeds the device row status and the Start gate.
     */
    private fun runMachineProbe() {
        machineProbeJob?.cancel()
        val startedAtMs = System.nanoTime() / 1_000_000L
        state.setMachineProbe(MachineProbeState.PROBING)
        machineProbeJob = scope.launch {
            try {
                probeMachineUntilReady(startedAtMs)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                // Never leave Start held because the probe itself failed.
                Timber.tag(BtEngineState.TAG).w(t, "Machine probe failed; allowing Start")
                state.setMachineProbe(MachineProbeState.READY)
            }
        }
    }

    private suspend fun probeMachineUntilReady(startedAtMs: Long) {
        var rangeWaitStartedAtMs: Long? = null
        while (connectionState.value == BluetoothProfile.STATE_CONNECTED) {
            val nowMs = System.nanoTime() / 1_000_000L
            val context = state.connectionRegistry.current(LegacyConnectionRole.FTMS)
            val services = context?.let {
                runCatching { it.discoveredServices.toList() }.getOrDefault(emptyList())
            }.orEmpty()
            val hasFtmsFeature = services
                .firstOrNull { it.uuid == FtmsUuids.UUID_FTMS_SERVICE }
                ?.getCharacteristic(UUID_FITNESS_MACHINE_FEATURE) != null
            val rangeWaitMs = rangeWaitStartedAtMs?.let { nowMs - it } ?: 0L
            val readiness = ProviderAdvertisingReadiness.decide(
                providerMachineCapabilities(),
                rangeWaitMs,
            )
            if (readiness is ProviderAdvertisingReadiness.Decision.WaitForRanges &&
                rangeWaitStartedAtMs == null
            ) {
                rangeWaitStartedAtMs = nowMs
            }
            val probe = MachineProbe.state(
                servicesDiscovered = services.isNotEmpty(),
                hasFtmsFeature = hasFtmsFeature,
                readiness = readiness,
                probeElapsedMs = nowMs - startedAtMs,
            )
            if (probe == MachineProbeState.READY) {
                if (nowMs - startedAtMs >= MachineProbe.PROBE_CAP_MS) {
                    Timber.tag(BtEngineState.TAG).w(
                        "Machine probe: capped after %d ms (readiness=%s); allowing Start",
                        nowMs - startedAtMs,
                        readiness,
                    )
                } else {
                    Timber.tag(BtEngineState.TAG).i(
                        "Machine probe: ready after %d ms (ftmsFeature=%s, readiness=%s)",
                        nowMs - startedAtMs,
                        hasFtmsFeature,
                        readiness,
                    )
                }
                state.setMachineProbe(MachineProbeState.READY)
                return
            }
            delay(MachineProbe.POLL_MS)
        }
    }

    // Last in the class body so every property is initialised before the first emission.
    init {
        scope.launch {
            connectionState.collect { linkState ->
                if (linkState == BluetoothProfile.STATE_CONNECTED) {
                    if (state.machineProbe.value == MachineProbeState.IDLE) runMachineProbe()
                } else if (linkState == BluetoothProfile.STATE_DISCONNECTED) {
                    machineProbeJob?.cancel()
                    state.setMachineProbe(MachineProbeState.IDLE)
                }
            }
        }
    }

}

internal fun BtEngine.machineContext(operation: String): ConnectionContext? =
    state.connectionRegistry.current(LegacyConnectionRole.FTMS) ?: run {
        Timber.tag(BtEngineState.TAG).w("%s skipped: no machine connection context", operation)
        null
    }

/* ───────────────────────────── provider-side advertising helpers ───────────────────────────── */

/* ───────────────────────────── setGattErrorFor helper ───────────────────────────── */

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
internal fun BtEngine.setGattErrorFor(gatt: BluetoothGatt, msg: String) {
    val connection = state.connectionRegistry.contextForGatt(gatt)
    val role = connection?.let(state.connectionRegistry::roleOf)

    // Pre-31 BLUETOOTH_CONNECT does not exist; BLUETOOTH is install-time, so the
    // platform calls below are always permitted there.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
        != PackageManager.PERMISSION_GRANTED
    ) {
        Timber.tag(BtEngineState.TAG).w("setGattErrorFor skipped platform calls: BLUETOOTH_CONNECT not granted")
        state.setGattErrorFor(gatt, msg)
        if (connection != null) {
            state.setConnectionState(connection, android.bluetooth.BluetoothProfile.STATE_DISCONNECTED)
            metricFusion.onDisconnected(connection.connectionId)
            state.clearConnection(connection)
        }
        if (role == LegacyConnectionRole.FTMS) {
            featureReadRetryScheduled = false
            earlyBondScheduled = false
        }
        return
    }

    state.setGattErrorFor(gatt, msg)
    runCatching { gatt.disconnect() }
    runCatching { gatt.close() }
    if (connection != null) {
        state.setConnectionState(connection, android.bluetooth.BluetoothProfile.STATE_DISCONNECTED)
        metricFusion.onDisconnected(connection.connectionId)
        state.clearConnection(connection)
    }
    if (role == LegacyConnectionRole.FTMS) {
        featureReadRetryScheduled = false
        earlyBondScheduled = false
    }
}

/* =========================================================================================================
 * btBonding.kt — inlined here
 * Helpers to prompt/ensure bonding for unknown devices, with simple backoff/polling.
 * ========================================================================================================= */

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
internal suspend fun ensureBondOrPrompt(
    context: Context,
    device: BluetoothDevice,
    timeoutMs: Long = 12_000
): Boolean {
    val initialState = device.bondState
    val initiallyBonded = (initialState == BluetoothDevice.BOND_BONDED)

    val started = runCatching { device.requestBondCompat() }.getOrDefault(false)

    Timber.tag(BtEngineState.TAG).i(
        "ensureBondOrPrompt: requestBondCompat() → %s (initialState=%d)",
        started,
        initialState
    )

    if (!started && initiallyBonded) {
        Timber.tag(BtEngineState.TAG).i(
            "ensureBondOrPrompt: re-bond not started but device already bonded; keeping existing bond"
        )
        return true
    }

    val ok = withTimeoutOrNull(timeoutMs) {
        while (true) {
            when (device.bondState) {
                BluetoothDevice.BOND_BONDED -> return@withTimeoutOrNull true
                BluetoothDevice.BOND_NONE -> { /* keep waiting until timeout */ }
                BluetoothDevice.BOND_BONDING -> { /* in progress */ }
            }
            delay(250)
        }
    } ?: false

    Timber.tag(BtEngineState.TAG).i(
        "ensureBondOrPrompt: final bond state=%d (ok=%s)",
        device.bondState,
        ok
    )
    return ok as Boolean
}

@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
internal fun BluetoothDevice.requestBondCompat(): Boolean {
    return try {
        @Suppress("MissingPermission")
        this.createBond()
    } catch (_: Throwable) {
        false
    }
}

internal fun ActivityType.fusionPersona(): FusionPersona = when (this) {
    ActivityType.TREADMILL, ActivityType.HEARTRATE -> FusionPersona.RUN
    ActivityType.ROWING -> FusionPersona.ROW_PADDLE
    ActivityType.BIKING, ActivityType.ELLIPTICAL,
    ActivityType.STAIRCLIMBER, ActivityType.STAIRSTEPPER -> FusionPersona.RIDE
}
