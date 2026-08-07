// =================================================================================================
// File: BtEngine.kt
// Package: com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine
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
//   • Optionally talk to a provider-side btService layer via BtServiceControlSink so that events
//       from a *remote* FTMS can be mirrored back out over local GATT services (FTMS / CSC / CPS).
//
//   Provider-side advertising integration (FitBridge virtual device):
//   • All local advertising (FTMS and CSC/CPS) is gated by [ENABLE_BTSERVICE_FTMS]. When this
//     flag is false, BtEngine will *never* call into BtServiceControlSink to start advertising.
//   • FTMS advertising is only started once per upstream FTMS session, and only *after* the
//     full FTMS capabilities snapshot has been populated (features + Supported-* ranges).
//     - In practice this means we:
//         • attempt to read FEATURES (0x2ACC) as early as possible;
//         • attempt Supported-* ranges based on the FEATURES bitmap;
//         • allow normal FTMS control (REQUEST_CONTROL / START / STOP / targets) to proceed
//           even if FEATURES / ranges are slow or partially unavailable;
//         • only start provider advertising once we have a “reasonable” snapshot to expose.
//   • BtEngine runs a small retry loop that keeps attempting to start advertising while the
//     upstream FTMS link remains connected:
//       - It first enforces a minimal readiness check via [FtmsCapabilities.isReadyForProviderAdvertising].
//       - It then calls [BtServiceControlSink.startAdvertisingFtms(...)] and expects a Boolean
//         return value: `true` = advertising actually started, `false` = btService not ready yet
//         (e.g. capabilities still converging or platform constraints).
//       - The loop retries when `startAdvertisingFtms` returns `false` or throws, with a small
//         backoff, until either advertising succeeds or the FTMS link drops.
//   • If both [ENABLE_BTSERVICE_FTMS] and [ENABLE_BTSERVICE_CSCCPS] are true, BtEngine will
//     also request CSC+CPS advertising via BtServiceControlSink.startAdvertisingCscCps() when
//     FTMS advertising is started.
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
//     val engine = BtEngine(context)
//     engine.attachServiceControlSink(btServiceManager)   // optional: mirror to local GATT
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

package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

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
import com.gmail.absolutebollockscreations.Fitbridge.ActivityType
import com.gmail.absolutebollockscreations.Fitbridge.HrZoneMode
import com.gmail.absolutebollockscreations.Fitbridge.canAssignSensorService
import com.gmail.absolutebollockscreations.Fitbridge.cscRoleCapabilityFromAppearance
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_FITNESS_MACHINE_CONTROL_POINT
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_FITNESS_MACHINE_FEATURE
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_FTMS_SERVICE
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_SUPPORTED_INCLINATION_RANGE
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_SUPPORTED_POWER_RANGE
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_SUPPORTED_RESISTANCE_RANGE
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_SUPPORTED_SPEED_RANGE
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtEngineState.FtmsCapabilities
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtServiceProfiles.FtmsMachineProfile
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
import timber.log.Timber
import java.util.UUID

const val ENABLE_BTSERVICE_FTMS = true     // When enabled, support advertising virtual devices matching the connected device properties
const val ENABLE_BTSERVICE_CSCCPS = false  // When enabled (and ENABLE_BTSERVICE_FTMS is also true), emulate fake CSC and CPS device characteristics

class BtEngine internal constructor(
    internal val context: Context,
    private val handlerFactory: HandlerFactory,
) : BtServiceEngineFacade {

    constructor(context: Context) : this(context, HandlerFactory.standard())

    companion object {
        private const val RECOVERY_CONNECT_TIMEOUT_MS = 12_000L

        // Polled faster than 1 Hz so a published second never drifts a whole tick late.
        private const val SESSION_ELAPSED_TICK_MS = 250L

        /** §0.7a liveness poll. Far finer than the 30 s timeout it evaluates. */
        private const val LIVENESS_TICK_MS = 1_000L
        @Volatile private var isStarted = false
        @Volatile private var stopped = false
        private val startLock = Any()
    }

    internal val state: BtEngineState = BtEngineState()
    internal val metricFusion: MetricFusion = MetricFusion(state)
    private val linkRecovery = InSessionLinkRecovery()
    private val recoveryJobs = mutableMapOf<String, Job>()

    // Optional sink into the provider-side btService layer.
    // Implemented by something like BtServiceManager in the service module.
    @Volatile
    var serviceControlSink: BtServiceControlSink? = null

    fun attachServiceControlSink(sink: BtServiceControlSink?) {
        serviceControlSink = sink
    }

    // BtServiceEngineFacade implementation ─────────────────────────────────────────────

    override val isFtmsConnected: Boolean
        get() = connectionState.value == android.bluetooth.BluetoothProfile.STATE_CONNECTED

    override val engineState: BtEngineState
        get() = state

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
    @Volatile private var advertisingStarted: Boolean = false
    @Volatile private var advertisedProfile: FtmsMachineProfile? = null
    @Volatile private var providerAdvertProfile: FtmsMachineProfile? = null
    @Volatile private var providerAdvertLoopRunning: Boolean = false

    // When true, we've already scheduled a retry of the FEATURES (0x2ACC) read after gaining control.
    @Volatile internal var featureReadRetryScheduled: Boolean = false

    // When true, we've already scheduled an early bonding attempt for the current FTMS connection.
    @Volatile internal var earlyBondScheduled: Boolean = false

    /** Call to clear provider-side advertising state whenever our upstream FTMS link (“session”) ends. */
    private fun resetProviderAdvertisingState() {
        advertisingStarted = false
        advertisedProfile = null
        providerAdvertProfile = null
        providerAdvertLoopRunning = false
        featureReadRetryScheduled = false
        earlyBondScheduled = false
    }

    /**
     * Return true when the current FTMS capabilities snapshot is "ready" to be mirrored as a
     * provider-side virtual device:
     *  - FEATURES (0x2ACC) must have been parsed (featuresRead = true).
     *  - For any dimension the upstream machine claims to support, we require that its range
     *    payload has been discovered. Today we enforce this for resistance, which is the primary
     *    control surface for bikes/ellipticals.
     *
     * Note: this is a *minimal* readiness gate on the engine side. The btService layer can
     * apply stricter criteria and express “not ready yet” by returning `false` from
     * [BtServiceControlSink.startAdvertisingFtms].
     *
     * IMPORTANT: callers should treat FEATURES as *optional* for data/control:
     *  - If 2ACC never converges, the app can still infer a machine persona from the data chars
     *    (e.g., Elliptical Data) and start btService with conservative “all controls unsupported”
     *    defaults. In that case this gate may remain false, but core FTMS usage still works.
     */
    private fun FtmsCapabilities.isReadyForProviderAdvertising(): Boolean {
        if (!featuresRead) return false
        if (supportsResistance && !resistanceRangeKnown) return false
        return true
    }

    /**
     * Entry point to be called when we have inferred a provider persona (FtmsMachineProfile)
     * from upstream FEATURES / data. This does **not** start advertising immediately; instead it
     * records the desired profile and kicks off a small retry loop that will:
     *
     *  - Wait until FTMS capabilities are minimally populated (FEATURES + key ranges).
     *  - Only attempt advertising while the upstream FTMS link remains connected.
     *  - Call startAdvertisingFtms() and:
     *      • treat `true` as success → mark [advertisingStarted] / [advertisedProfile] and exit.
     *      • treat `false` as “btService not ready yet” → log and retry after a small backoff.
     *      • catch and log any thrown exceptions, then retry with backoff.
     *
     * Connection and control are **not** blocked on this: REQUEST_CONTROL, START/STOP, and target
     * setters can proceed even if FEATURES / Supported-* ranges are still converging or partially
     * unavailable. We only gate *provider advertising* on the capabilities snapshot.
     */
    fun maybeStartProviderAdvertisingAfterFeatures(profile: FtmsMachineProfile?) {
        // Global gate: if FTMS provider is disabled, we never start ANY local advertising
        if (!ENABLE_BTSERVICE_FTMS) {
            Timber.tag(BtEngineState.TAG).d(
                "Provider advertising skipped: ENABLE_BTSERVICE_FTMS=false (no FTMS/CSC/CPS advertising)"
            )
            return
        }

        if (profile == null) {
            Timber.tag(BtEngineState.TAG).w(
                "Provider advertising skipped: profile=null (no inferred machine)"
            )
            return
        }

        providerAdvertProfile = profile

        val sink = serviceControlSink ?: run {
            Timber.tag(BtEngineState.TAG).d("Provider advertising skipped: no serviceControlSink")
            return
        }

        if (advertisingStarted) {
            Timber.tag(BtEngineState.TAG).d(
                "Provider advertising already started for %s",
                advertisedProfile
            )
            return
        }

        // Launch a single retry loop per FTMS session.
        if (providerAdvertLoopRunning) {
            Timber.tag(BtEngineState.TAG).d(
                "Provider advertising retry loop already running; updated profile=%s",
                profile
            )
            return
        }

        providerAdvertLoopRunning = true
        Timber.tag(BtEngineState.TAG).d(
            "Provider advertising retry loop starting for profile=%s (sink=%s)",
            profile,
            sink.javaClass.simpleName
        )

        scope.launch {
            var attempt = 0
            try {
                while (ENABLE_BTSERVICE_FTMS && !advertisingStarted) {
                    val currentSink = serviceControlSink ?: run {
                        Timber.tag(BtEngineState.TAG).w(
                            "Provider advertising retry: serviceControlSink lost; aborting loop"
                        )
                        break
                    }

                    val currentProfile = providerAdvertProfile ?: run {
                        Timber.tag(BtEngineState.TAG).w(
                            "Provider advertising retry: profile=null; waiting for profile"
                        )
                        delay(1_000)
                        continue
                    }

                    val connected =
                        (connectionState.value == android.bluetooth.BluetoothProfile.STATE_CONNECTED)
                    if (!connected) {
                        Timber.tag(BtEngineState.TAG).d(
                            "Provider advertising retry: upstream FTMS not connected; stopping loop"
                        )
                        break
                    }

                    val capabilities = state.ftmsCapabilities
                    if (!capabilities.isReadyForProviderAdvertising()) {
                        Timber.tag(BtEngineState.TAG).d(
                            "Provider advertising retry: capabilities not ready yet (caps=%s); waiting",
                            capabilities
                        )
                        delay(1_000)
                        continue
                    }

                    attempt++
                    Timber.tag(BtEngineState.TAG).i(
                        "Provider advertising retry attempt #%d: requesting local FTMS advertising " +
                                "(profile=%s, capabilities=%s)",
                        attempt,
                        currentProfile,
                        capabilities
                    )

                    val success: Boolean = try {
                        // expect Boolean from btService; false means “not ready yet”
                        currentSink.startAdvertisingFtms(currentProfile, capabilities)
                    } catch (t: Throwable) {
                        Timber.tag(BtEngineState.TAG).w(
                            t,
                            "Provider advertising retry attempt #%d threw; will retry",
                            attempt
                        )
                        false
                    }

                    if (success) {
                        advertisedProfile = currentProfile
                        advertisingStarted = true
                        Timber.tag(BtEngineState.TAG).i(
                            "Provider advertising: FTMS advertising started successfully on attempt #%d",
                            attempt
                        )

                        // If CSC/CPS mirroring is enabled alongside FTMS, also start CSC+CPS advertising.
                        if (ENABLE_BTSERVICE_FTMS && ENABLE_BTSERVICE_CSCCPS) {
                            Timber.tag(BtEngineState.TAG).i(
                                "ENABLE_BTSERVICE_CSCCPS=true → also starting local CSC+CPS advertising"
                            )
                            runCatching { currentSink.startAdvertisingCscCps() }
                                .onFailure {
                                    Timber.tag(BtEngineState.TAG).w(
                                        it,
                                        "Failed to start CSC+CPS advertising alongside FTMS"
                                    )
                                }
                        }
                        break
                    } else {
                        Timber.tag(BtEngineState.TAG).d(
                            "Provider advertising retry attempt #%d: startAdvertisingFtms returned false; " +
                                    "btService not ready yet (capabilities may still be converging). Retrying…",
                            attempt
                        )
                        // Simple backoff to avoid hammering the stack if btService refuses.
                        delay(2_000)
                    }
                }
            } finally {
                providerAdvertLoopRunning = false
                Timber.tag(BtEngineState.TAG).d(
                    "Provider advertising retry loop finished (advertisingStarted=%s, connected=%s)",
                    advertisingStarted,
                    connectionState.value == android.bluetooth.BluetoothProfile.STATE_CONNECTED
                )
            }
        }
    }

    /** Convenience overload if the caller only has an ActivityType (e.g., inferred from data chars). */
    fun maybeStartProviderAdvertisingAfterFeatures(activity: ActivityType?) {
        maybeStartProviderAdvertisingAfterFeatures(activity?.toFtmsProfile())
    }

    /** Map ActivityType (consumer) → FtmsMachineProfile (provider persona). */
    private fun ActivityType.toFtmsProfile(): FtmsMachineProfile? = when (this) {
        ActivityType.TREADMILL    -> FtmsMachineProfile.TREADMILL
        ActivityType.ELLIPTICAL   -> FtmsMachineProfile.ELLIPTICAL
        ActivityType.ROWING       -> FtmsMachineProfile.INDOOR_ROWER
        ActivityType.BIKING       -> FtmsMachineProfile.INDOOR_BIKE
        ActivityType.STAIRCLIMBER -> FtmsMachineProfile.STAIR_CLIMBER
        ActivityType.STAIRSTEPPER -> FtmsMachineProfile.STAIR_STEPPER
        ActivityType.HEARTRATE    -> null // HR-only, no FTMS persona
    }

    /** Apply a user-selected session mode to the downstream FTMS persona. */
    @RequiresPermission(
        allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT]
    )
    fun overrideProviderActivityForSession(activity: ActivityType) {
        val profile = activity.toFtmsProfile()
        if (profile == null) {
            Timber.tag(BtEngineState.TAG).i(
                "Session mode override is HRM-only; no FTMS persona override applied"
            )
            return
        }

        providerAdvertProfile = profile
        if (connectionState.value != android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
            Timber.tag(BtEngineState.TAG).i(
                "Session mode override=%s recorded without an upstream FTMS connection",
                profile,
            )
            return
        }
        if (advertisingStarted && advertisedProfile == profile) return

        if (advertisingStarted) {
            scope.launch {
                stopProviderAdvertising("session mode override to $profile")
                advertisingStarted = false
                advertisedProfile = null
                while (providerAdvertLoopRunning) delay(25)
                maybeStartProviderAdvertisingAfterFeatures(profile)
            }
        } else {
            // An active retry loop reads providerAdvertProfile on each attempt; this call is
            // otherwise responsible for starting a new loop.
            maybeStartProviderAdvertisingAfterFeatures(profile)
        }
    }

    // Public flows (read-only)
    val connectionState: StateFlow<Int> get() = state.connectionState
    val hrmConnectionState: StateFlow<Int> get() = state.hrmConnectionState
    val haveControlFlow: StateFlow<Boolean> get() = state.haveControlFlow
    val activeSession: StateFlow<Boolean> get() = state.activeSession

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
            runCatching { state.adapterLiveness.tick(elapsedRealtimeMs()) }
                .onFailure { Timber.tag(BtEngineState.TAG).w(it, "liveness tick failed") }
        }
    }

    @Volatile private var desiredAddress: String? = null
    @Volatile private var desiredActivity: ActivityType? = null
    @Volatile private var sessionElapsedJob: Job? = null

    // Android primitives
    private val btManager: BluetoothManager? = runCatching {
        context.getSystemService(BluetoothManager::class.java)
    }.getOrNull()

    internal val adapter: BluetoothAdapter? = btManager?.adapter

    val discoveredDevices: StateFlow<Map<String, BtEngineState.DiscoveredDevice>>
        get() = state.discoveredDevices
    val connectedDevices: StateFlow<List<BtEngineState.ConnectedDeviceLink>>
        get() = state.connectedDevices

    /* Clear the canonical discovery registry. */
    fun clearDiscovered() {
        state.putDiscoveredDevices(emptyMap())
    }

    private fun elapsedRealtimeMs(): Long = android.os.SystemClock.elapsedRealtime()

    /** Public entry point for UI/service to clear short-term series and reset baselines. */
    fun clearTimeSeries(keepTotals: Boolean = true) {
        state.clearTimeSeries(keepTotals)
    }

    /** Convenience passthrough if callers prefer a zero-based distance after clear. */
    fun distanceSinceLastClear(): Int = state.distanceSinceLastClear()

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
    ) = guardScanPerm("startScan()") { startScanImpl(useFtmsHardwareFilter, onDevice) }

    fun stopScan() = guardScanPerm("stopScan()") { stopScanImpl() }

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
        if (machineConnection) addAll(handlerFactory.createForConnection(ProtocolKey.FTMS))
        if (BtEngineState.BtServiceType.HeartRate in services) {
            addAll(handlerFactory.createForConnection(ProtocolKey.HRM))
        }
        // Independent of the HRM branch on purpose: a running strap advertises
        // both 0x180D and 0x1814 on one connection, and both handlers must
        // activate. Phase 0.5 made that possible; this is the first device
        // family that actually needs it.
        if (BtEngineState.BtServiceType.RSC in services) {
            addAll(handlerFactory.createForConnection(ProtocolKey.RSC))
        }
    }.distinctBy { handler -> handler::class.java.name to handler.primaryServices }

    private fun recoveryTarget(
        address: String,
        machineConnection: Boolean,
        services: Set<BtEngineState.BtServiceType>,
    ) = LinkRecoveryTarget(
        recoveryKey = recoveryKey(address, machineConnection),
        address = canonicalConnectionAddress(address),
        machineConnection = machineConnection,
        activeServices = services,
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
        return recoveryTarget(context.address, context.machineConnection, activeServices)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connectMachine(address: String): Boolean = connectFtms(address)

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
        Timber.tag(BtEngineState.TAG).i(
            "connectFtms address=%s activity=%s",
            address,
            activity ?: "AUTO"
        )

        val dev = try { adapter?.getRemoteDevice(address) } catch (_: IllegalArgumentException) { null }
        if (dev == null) return@guardConnectPerm false

        stopScan()
        if (!recoveryAttempt) resetProviderAdvertisingState()

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
            stopProviderAdvertising("disconnectFtms() without BLUETOOTH_CONNECT")
            resetProviderAdvertisingState()
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
            stopProviderAdvertising("disconnectFtms() explicit FTMS disconnect")
            resetProviderAdvertisingState()
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connectSensor(
        address: String,
        serviceType: BtEngineState.BtServiceType,
    ): Boolean = connectSensorInternal(address, setOf(serviceType), recoveryAttempt = false)

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun connectSensorInternal(
        address: String,
        assignedServices: Set<BtEngineState.BtServiceType>,
        recoveryAttempt: Boolean,
    ): Boolean = guardConnectPerm("connectSensor(address=$address, services=$assignedServices)", false) {
            Timber.tag(BtEngineState.TAG).i(
                "connectSensor %s services=%s recovery=%s",
                address,
                assignedServices,
                recoveryAttempt,
            )
            if (address.isBlank()) return@guardConnectPerm false
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
                    activateServiceAtAddress(address, serviceType)
                }
            }
            if (assignedServices.any { it != BtEngineState.BtServiceType.HeartRate }) {
                return@guardConnectPerm false
            }
            stopScan()
            val dev = try { adapter?.getRemoteDevice(address) } catch (_: IllegalArgumentException) { null }
            if (dev == null) {
                Timber.tag(BtEngineState.TAG).w("connectSensor: invalid address %s", address)
                return@guardConnectPerm false
            }
            val connection = state.openAdditionalSensorContext(
                address,
                handlers = handlersForServices(assignedServices, machineConnection = false),
            )
            if (BtEngineState.BtServiceType.CSC in assignedServices) {
                state.setCscRoleCapability(connection, candidateCscCapability)
            }
            val cb = BtEngineGattCore.build(this@BtEngine, connection)
            val connectedGatt = connectLeGatt(dev, cb)
            val attached = connectedGatt?.let { state.attachGatt(connection, it) } == true
            if (!attached) state.clearConnection(connection)
            if (attached && !recoveryAttempt) {
                linkRecovery.registerSelection(
                    recoveryTarget(address, machineConnection = false, assignedServices)
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
                stopProviderAdvertising("explicit device disconnect")
                resetProviderAdvertisingState()
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
    ): Boolean = guardConnectPerm("activateServiceAtAddress($address, $serviceType)", default = false) {
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
        val protocol = when (serviceType) {
            BtEngineState.BtServiceType.HeartRate -> ProtocolKey.HRM
            BtEngineState.BtServiceType.RSC -> ProtocolKey.RSC
            else -> return@guardConnectPerm false
        }
        val activated = state.connectionRegistry.activateHandlerOn(
            address,
            handlerFactory.create(protocol),
        )
        if (activated) {
            state.connectionRegistry.contextsForAddress(address).firstOrNull()?.let { context ->
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
    ): Boolean {
        val context = state.connectionRegistry.contextForKey(connectionKey) ?: return false
        return activateServiceAtAddress(context.address, serviceType)
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
        runCatching { stopScan() }
        state.connectedDevices.value.map { it.connectionKey }.forEach { connectionKey ->
            runCatching { disconnectDevice(connectionKey) }
        }
        stopProviderAdvertising("release")
        resetProviderAdvertisingState()
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
                        startScanImpl(useFtmsHardwareFilter = true) { _, _, _ -> }
                        val found = withTimeoutOrNull(12_000) {
                            state.devices.first { it.containsKey(address) }
                        }
                        stopScanImpl()
                        if (found != null) connectFtms(address, activity) else false
                    }
                } catch (t: Throwable) {
                    Timber.tag(BtEngineState.TAG).w(t, "start(): scan/connect fallback failed")
                    runCatching { stopScanImpl() }
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
        state.setIsRunning(false)
        state.setActiveSession(false)
        metricFusion.endSession()
        runCatching { stopOrPause() }
        release()
    }

    fun markSessionStarted(activity: ActivityType, address: String?) {
        desiredActivity = activity
        desiredAddress = address
        metricFusion.startSession(activity.fusionPersona())
        state.setActiveSession(true)
        startSessionElapsedClock()
    }

    fun markSessionStopped() {
        stopSessionElapsedClock()
        state.setActiveSession(false)
        metricFusion.endSession()
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
            val ok: Boolean = BtEngineControlPoint.setTargetSpeedKph(this, kph)
            ok
        }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun setTargetInclinationPercent(percent: Float): Boolean =
        guardConnectPerm(op = "setTargetInclinationPercent()", default = false) {
            val ok: Boolean = BtEngineControlPoint.setTargetInclinationPercent(this, percent)
            ok
        }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun setTargetResistance(level: Float): Boolean =
        guardConnectPerm(op = "setTargetResistance()", default = false) {
            val ok: Boolean = BtEngineControlPoint.setTargetResistance(this, level)
            ok
        }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun setTargetPowerWatts(watts: Int): Boolean =
        guardConnectPerm(op = "setTargetPowerWatts()", default = false) {
            val ok: Boolean = BtEngineControlPoint.setTargetPowerWatts(this, watts)
            ok
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

    fun setUnits(value: Int?) {
        state.setUnits(value)
        recomputeAllDisplayUnits(this)
    }

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
        state.setLapDistanceDisplay(0.0)
        state.setLapAvgSpeedMps(null)
        state.setLapAvgSpeedDisplay(null)
        state.setLapRunPaceMinPerUnit(null)
        state.setLapRowPaceMinPer500m(null)
        state.setLapStrokeCount(0)
        state.setLapSteps(0)
        state.setLapCadenceSpm(null)
    }

    // Manual reset hook — call from VM when RunMode closes
    fun clearAllTimeSeriesAndState() {
        stopSessionElapsedClock()
        metricFusion.endSession()
        runCatching { state.clearAllTimeSeries() }
            .onFailure {
                Timber.tag(BtEngineState.TAG).w(it, "clearAllTimeSeries() not available on BtEngineState")
            }

        state.setElapsedSeconds(0)
        state.setLapElapsedTimeSec(0)
        state.setLapDistanceMeters(0)
        state.setLapDistanceDisplay(0.0)

        state.setLapAvgSpeedMps(null)
        state.setLapAvgSpeedDisplay(null)
        state.setLapRunPaceMinPerUnit(null)
        state.setLapRowPaceMinPer500m(null)
        state.setLapStrokeCount(0)
        state.setLapSteps(0)
        state.setLapCadenceSpm(null)

        state.setActiveSession(false)
        state.setIsRunning(false)
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

}

internal fun BtEngine.machineContext(operation: String): ConnectionContext? =
    state.connectionRegistry.current(LegacyConnectionRole.FTMS) ?: run {
        Timber.tag(BtEngineState.TAG).w("%s skipped: no machine connection context", operation)
        null
    }

/* ───────────────────────────── provider-side advertising helpers ───────────────────────────── */

/** Centralized helper to stop local/provider-side advertising (FTMS/CSC/CPS). */
private fun BtEngine.stopProviderAdvertising(reason: String) {
    val sink = serviceControlSink ?: return
    try {
        Timber.tag(BtEngineState.TAG).i("Stopping local advertising: %s", reason)
        sink.stopAdvertising()
    } catch (t: Throwable) {
        Timber.tag(BtEngineState.TAG).w(t, "stopProviderAdvertising failed: %s", reason)
    }
}

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
            stopProviderAdvertising("FTMS GATT error / remote disconnect (no BLUETOOTH_CONNECT)")
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
        stopProviderAdvertising("FTMS GATT error / remote disconnect")
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
