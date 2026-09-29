// =================================================================================================
// File: BtEngineState.kt
// Package: com.gmail.absolutebollockscreations.gattlibrary.central
// -------------------------------------------------------------------------------------------------
// ROLE
//   Central, in-memory state container for the Bluetooth engine.
//
//   BtEngineState is the single source of truth for:
//     • Device discovery (FTMS + HRM)
//     • Connection / error state
//     • FTMS capabilities + control state
//     • Fitness Machine Status / target set-points
//     • Instant / average / peak workout metrics
//     • Full-session time-series for graphing (no time-based trimming; UI decides window)
//     • Heart-rate and HR-zone aggregation
//     • Lap-level derived metrics
//
//   UI and other consumers read only the public StateFlow<T> properties. All mutation happens via
//   helper setters/appenders called from BtEngine, FtmsProtocolHandler, HrmProtocolHandler and
//   related parsers.
// -------------------------------------------------------------------------------------------------
// NOTES
//   - This class is intentionally Android-light: it holds BluetoothGatt references and types but
//     does not perform any platform calls itself.
//   - All fields that the UI or service layer should react to are exposed as StateFlow<T>; call-side
//     code must use the provided setters rather than mutating backing flows directly.
//   - Time-series are now *full-session*: we no longer trim to a rolling N-minute window. The
//     Composable layer (graphs) is responsible for choosing the visible window (e.g. last 5 min
//     vs entire workout).
// =================================================================================================

package com.gmail.absolutebollockscreations.gattlibrary.central

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanResult
import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi
import com.gmail.absolutebollockscreations.gattlibrary.core.HrZoneMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.util.UUID
import kotlin.math.roundToInt

fun netElevationChangeMeters(
    positiveMeters: Double?,
    negativeMeters: Double?,
): Double? = if (positiveMeters == null && negativeMeters == null) {
    null
} else {
    (positiveMeters ?: 0.0) - (negativeMeters ?: 0.0)
}

class BtEngineState : HrmStateSink {

    @GattLibraryInternalApi
    val cscNativeEvents = CscNativeEventStore()
    internal val cpsNativeEvents = CpsNativeEventStore()
    internal val treadmillNativePace = TreadmillNativePaceStore()

    fun cscNativeEventSnapshot(): CscNativeEventSnapshot? = cscNativeEvents.snapshot()

    fun cpsNativeEventSnapshot(): CpsNativeEventSnapshot? = cpsNativeEvents.snapshot()

    fun treadmillNativePaceSnapshot(): TreadmillNativePaceSnapshot? = treadmillNativePace.snapshot()

    /** Services from the selected machine link, never from a sensor or stale GATT. */
    fun currentMachineServiceUuids(): Set<UUID> =
        connectionRegistry.current(LegacyConnectionRole.FTMS)
            ?.discoveredServiceUuids
            ?.toSet()
            .orEmpty()

    /* ───────────────────────────── types & constants ───────────────────────────── */

    companion object {
        internal const val TAG = "BtEngine"
    }

    /** Timestamped sample for graph series. */
    data class TimedSample(val tMillis: Long, val v: Double)

    enum class BtServiceType {
        FTMS,         // Fitness Machine Service (0x1826)
        HeartRate,    // Heart Rate (0x180D)
        CSC,          // Cycling Speed & Cadence (0x1816)
        RSC,          // Running Speed & Cadence (0x1814)
        Power,        // Cycling Power (0x1818)
        Glucose       // Glucose (0x1808)
    }

    enum class CscRole { SPEED, CADENCE }

    enum class CscRoleSource { APPEARANCE, FEATURE }

    /** Vendor/protocol identity established from the connected link's GATT inventory. */
    /**
     * How a discovered device would deliver telemetry if selected.
     *
     * The state model's own projection of the matcher's transport, mapped at the scan boundary in
     * the same way [BtServiceType] is mapped from `KnownBleService`. The matcher's types are
     * internal to the engine and have no business in a state class the UI observes.
     *
     * An [ADVERTISEMENT_ONLY] row must never be offered a connect or disconnect affordance: there
     * is nothing to connect to, and presenting one would be a claim about the device rather than
     * about the UI.
     */
    enum class DeviceTransport { GATT, ADVERTISEMENT_ONLY }

    enum class AdvertisementSourceState { RECEIVING, STALE, OUT_OF_RANGE }

    enum class MachineIdentityFamily {
        FTMS,
        LIFE_FITNESS,
        PRECOR_SPINNER_CHRONO_POWER,
        CONCEPT2_PM5,
        FE_C_OVER_BLE,
        FLYWHEEL_SPORTS,
        KAYAK_FIRST,
    }

    /** Presentation-level machine kind; intentionally independent of session activity. */
    enum class MachineDeviceType {
        BIKE,
        BIKE_ERG,
        ELLIPTICAL,
        ROWER,
        SKI_ERG,
        STAIR_CLIMBER,
        STAIR_STEPPER,
        TREADMILL,
    }

    data class CscRoleCapability(
        val roles: Set<CscRole>,
        val source: CscRoleSource,
    )

    /** Discovery model for scan lists. */
    data class DiscoveredDevice(
        val name: String,
        val address: String,
        val rssi: Int,
        val lastSeenMs: Long,
        val scanResult: ScanResult?,
        val services: Set<BtServiceType> = emptySet(),
        // Discovery/matcher decision; future proprietary fingerprints may set this without FTMS.
        val machineCandidate: Boolean = BtServiceType.FTMS in services,
        /**
         * How this device would deliver telemetry if selected (phase 11.4).
         *
         * Defaults to [DeviceTransport.GATT], which is what every device discovered before
         * phase 11 is.
         */
        val transport: DeviceTransport = DeviceTransport.GATT,
        /** Matcher candidate that admitted this device, for diagnostics and selection routing. */
        val matchCandidateId: String? = null,
        /**
         * System ID an advertisement-only bike broadcasts — the number on its console (phase 11.5b).
         *
         * Identity rather than telemetry, so it is read from any decodable field including a
         * review-mode one: a bike parked on its summary screen is still bike number seven.
         */
        val advertisementSystemId: Int? = null,
        /** Firmware an advertisement-only device reports, for the row and for diagnostics. */
        val advertisementFirmware: String? = null,
        // Authoritative type inferred from GATT characteristics after connecting.
        val machineFeature: Int? = null,
        // Optimistic ActivityType ordinal decoded from advertised FTMS Service Data.
        val advertisedMachineFeature: Int? = null,
        // GAP Appearance AD type 0x19, when present in the advertisement.
        val appearance: Int? = null,
    )

    /** Selected advertisement-only machine; deliberately separate from GATT connected links. */
    data class ActiveAdvertisementMachine(
        val address: String,
        val candidateId: String,
        val systemId: Int,
        val firmware: String?,
        val generation: Long,
        val sourceState: AdvertisementSourceState,
        /**
         * Advertised model variant, e.g. `M3i` or `M3m`; `M3` when the row carried no letter.
         *
         * The bare `M3` this used to hardcode was the one place a rider running an imperial and a
         * metric bike side by side could not tell them apart — both rows read `M3 #n` while the
         * air, the simulator UI and the personas had said `M3i` and `M3m` all along.
         *
         * The literal default is spelled out rather than borrowed from
         * `KeiserSelection.DEFAULT_MODEL`, which is internal and cannot appear in this public
         * class's signature; `KeiserSelection.modelFromAdvertisedName` remains the one parser.
         */
        val model: String = "M3",
    ) {
        /**
         * Note that [address] is a current binding, not an identity: it changes under this object
         * when the bike rotates its random address, while [systemId] and [generation] do not.
         */
        val displayName: String get() = "$model #$systemId"
    }

    /** Registry projection for one physical-link generation; independent of scan visibility. */
    data class ConnectedDeviceLink(
        val connectionKey: String,
        val address: String,
        val connectionState: Int,
        val activeServices: Set<BtServiceType>,
        val machineConnection: Boolean,
        val machineIdentityFamily: MachineIdentityFamily? = null,
        val machineDeviceType: MachineDeviceType? = null,
        val cscRoleCapability: CscRoleCapability? = null,
        val cscAssignedRoles: Set<CscRole> = emptySet(),
    )

    // ⬇️ Container types for provider-side capabilities snapshot
    //    Place this right after DiscoveredDevice so all “type” models live together.
    /**
     * Snapshot of FTMS capabilities that btService can use to build provider-side
     * 0x2ACC (Fitness Machine Feature) and Supported-* Range characteristics.
     *
     * Floats are already in **spec units** as parsed from 0x2ACC / 0x2AD4–0x2AD8:
     *  - speed*:      0.01 m/s units
     *  - incline*:    0.1 degree units (or %, upstream decides – adapter just packs)
     *  - resistance*: device-specific “level” units
     *  - power*:      watts
     *
     * btService will treat these as ready-to-pack little-endian fields.
     */
    data class FtmsCapabilities(
        val featuresRead: Boolean = false,
        val supportsSpeed: Boolean = false,
        val supportsInclination: Boolean = false,
        val supportsResistance: Boolean = false,
        val supportsPower: Boolean = false,
        /** Target Setting Features bit 13. Gates the downstream `indoorBikeSimulation` claim. */
        val supportsIndoorBikeSimulation: Boolean = false,
        /** Target Setting Features bit 14. No Supported-* Range characteristic accompanies it. */
        val supportsWheelCircumference: Boolean = false,

        val speedMin: Float? = null,
        val speedMax: Float? = null,
        val speedIncr: Float? = null,
        val speedRangeKnown: Boolean = false,

        val inclineMin: Float? = null,
        val inclineMax: Float? = null,
        val inclineIncr: Float? = null,
        val inclineRangeKnown: Boolean = false,

        val resistanceMin: Float? = null,
        val resistanceMax: Float? = null,
        val resistanceIncr: Float? = null,
        val resistanceRangeKnown: Boolean = false,

        val powerMin: Float? = null,
        val powerMax: Float? = null,
        val powerIncr: Float? = null,
        val powerRangeKnown: Boolean = false,
    )

    /**
     * Canonical capabilities snapshot for provider-side usage.
     *
     * Populated by the FTMS capabilities reader once 0x2ACC and any relevant
     * Supported-* Range characteristics have been parsed for the currently
     * connected FTMS device.
     */
    var ftmsCapabilities: FtmsCapabilities = FtmsCapabilities()
        private set

    /**
     * Simple triple for Supported-* Range characteristics:
     *   - min / max / inc, already scaled to the raw FTMS units used on the wire.
     */
    data class RangeTriple(val min: Int, val max: Int, val inc: Int)

    fun mergeFtmsCapabilities(update: FtmsCapabilities) {
        val old = ftmsCapabilities
        ftmsCapabilities = FtmsCapabilities(
            featuresRead          = update.featuresRead          || old.featuresRead,
            supportsSpeed         = update.supportsSpeed         || old.supportsSpeed,
            supportsInclination   = update.supportsInclination   || old.supportsInclination,
            supportsResistance    = update.supportsResistance    || old.supportsResistance,
            supportsPower         = update.supportsPower         || old.supportsPower,
            supportsIndoorBikeSimulation =
                update.supportsIndoorBikeSimulation || old.supportsIndoorBikeSimulation,
            supportsWheelCircumference =
                update.supportsWheelCircumference || old.supportsWheelCircumference,

            speedMin              = update.speedMin              ?: old.speedMin,
            speedMax              = update.speedMax              ?: old.speedMax,
            speedIncr             = update.speedIncr             ?: old.speedIncr,
            speedRangeKnown       = update.speedRangeKnown       || old.speedRangeKnown,

            inclineMin            = update.inclineMin            ?: old.inclineMin,
            inclineMax            = update.inclineMax            ?: old.inclineMax,
            inclineIncr           = update.inclineIncr           ?: old.inclineIncr,
            inclineRangeKnown     = update.inclineRangeKnown     || old.inclineRangeKnown,

            resistanceMin         = update.resistanceMin         ?: old.resistanceMin,
            resistanceMax         = update.resistanceMax         ?: old.resistanceMax,
            resistanceIncr        = update.resistanceIncr        ?: old.resistanceIncr,
            resistanceRangeKnown  = update.resistanceRangeKnown  || old.resistanceRangeKnown,

            powerMin              = update.powerMin              ?: old.powerMin,
            powerMax              = update.powerMax              ?: old.powerMax,
            powerIncr             = update.powerIncr             ?: old.powerIncr,
            powerRangeKnown       = update.powerRangeKnown       || old.powerRangeKnown,
        )
    }

    fun resetFtmsCapabilities() {
        featuresRead = false
        supportsSpeed = false
        supportsInclination = false
        supportsResistance = false
        supportsPower = false
        // Must clear with the rest: a stale claim would advertise pace downstream for a machine
        // that never declared it.
        supportsPaceMeasurement = false
        supportsIndoorBikeSimulation = false
        supportsSpinDown = false

        speedMin = 0f; speedMax = 0f; speedIncr = 0f; speedRangeKnown = false
        inclineMin = 0f; inclineMax = 0f; inclineIncr = 0f; inclineRangeKnown = false
        resistanceMin = 0f; resistanceMax = 0f; resistanceIncr = 0f; resistanceRangeKnown = false
        resistanceIsRawLevels = false
        powerMin = 0f; powerMax = 0f; powerIncr = 0f; powerRangeKnown = false

        ftmsCapabilities = FtmsCapabilities()
    }

    // ⬆️ END OF NEW FTMS CAPABILITIES BLOCK


    /* ───────────────────────────── FTMS capabilities / ranges ───────────────────────────── */

    /**
     * Whether we have successfully read 0x2ACC + any Supported-* Range characteristics
     * from the *real* FTMS device for this session.
     *
     * This is used by consumers that still rely on the legacy boolean/float fields
     * below. Newer provider-side code should prefer [ftmsCapabilities] where possible.
     */
    var featuresRead = false

    /**
     * Legacy flags indicating what the real machine supports. Retained for engine-side
     * logic and any existing UI consumers. btService should prefer [ftmsCapabilities].
     */
    var supportsSpeed = false
    var supportsInclination = false
    var supportsResistance = false
    var supportsPower = false

    /**
     * Pace Supported, from the upstream machine's own `0x2ACC` measurement word (bit 5).
     *
     * Mirrored into the downstream Feature so FitBridge claims pace exactly when the machine it
     * is bridging claims it. Measurement capability, not a control capability, so it is
     * deliberately separate from the four control flags above.
     */
    var supportsPaceMeasurement = false

    /**
     * Target Setting Features bit 13 — Indoor Bike Simulation Parameters Supported (§9.5.6).
     *
     * Gates the upstream `0x11` write and the downstream `indoorBikeSimulation` capability. A
     * training app sending a road gradient must not be told the write succeeded by a machine
     * that never declared it could accept one.
     */
    var supportsIndoorBikeSimulation = false

    /**
     * Target Setting Features bit 15 — Spin Down Control Supported.
     *
     * Recorded but not acted on. Spin-down is a calibration procedure with its own status
     * sequence (requested → stop pedalling → success/error) rather than a single-acknowledgement
     * target, so it needs a state machine and a longer timeout that are not built. Knowing the
     * bit now costs nothing and claims nothing.
     */
    var supportsSpinDown = false

    /**
     * Legacy range fields (engine-side, usually in human-readable units such as km/h,
     * degrees, watts). These are still valid for existing math/UI.
     *
     * When building provider-side Supported-* Range characteristics, prefer the richer
     * [ftmsCapabilities] snapshot if it is non-null; otherwise, these can be used as a
     * fallback by converting to spec units in BtServiceSupportedRangesAdapter.
     */
    var speedMin = 0f
    var speedMax = 0f
    var speedIncr = 0f
    var speedRangeKnown = false

    var inclineMin = 0f
    var inclineMax = 0f
    var inclineIncr = 0f
    var inclineRangeKnown = false

    var resistanceMin = 0f
    var resistanceMax = 0f
    var resistanceIncr = 0f
    var resistanceRangeKnown = false

    /**
     * Latched once a machine's Cross Trainer resistance field has been shown to
     * carry raw levels rather than the spec's tenths. See [FtmsResistanceScale].
     *
     * Latched rather than re-decided per sample because the evidence is only
     * present while the machine is under load. At rest it reports 0, which is
     * outside its own declared range under either reading and therefore falls
     * back to the spec - so a per-sample decision would flip interpretation
     * every time the user stopped pedalling and put a 10x step in the recorded
     * activity. Cleared with the rest of the capability block on disconnect.
     */
    @Volatile
    var resistanceIsRawLevels = false

    var powerMin = 0f
    var powerMax = 0f
    var powerIncr = 0f
    var powerRangeKnown = false

    private val _discoveredDevices = MutableStateFlow<Map<String, DiscoveredDevice>>(emptyMap())
    val discoveredDevices: StateFlow<Map<String, DiscoveredDevice>> = _discoveredDevices

    private val _activeAdvertisementMachine =
        MutableStateFlow<ActiveAdvertisementMachine?>(null)
    val activeAdvertisementMachine: StateFlow<ActiveAdvertisementMachine?> =
        _activeAdvertisementMachine

    // Internal parser/capability compatibility only; scanner/UI callers use discoveredDevices.
    internal val devices: StateFlow<Map<String, DiscoveredDevice>> = discoveredDevices

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    /* ───────────────────────────── connections & characteristics ───────────────────────────── */

    internal val connectionRegistry = ConnectionRegistry()

    /**
     * §0.7a liveness supervision. Lives here because the three things that feed
     * it sit in different layers — CCCD completion in the GATT callbacks, samples
     * in fusion, and the periodic tick in the engine loop — and this is the one
     * object all three already hold.
     */
    internal val adapterLiveness = AdapterLivenessCoordinator()
    val connectedDevices: StateFlow<List<ConnectedDeviceLink>> =
        connectionRegistry.connectedDevices

    private val _machineProbe = MutableStateFlow(MachineProbeState.IDLE)

    /** Connected machine's capability probe, for the device row and the Start gate (LIB-9f). */
    val machineProbe: StateFlow<MachineProbeState> = _machineProbe

    internal fun setMachineProbe(value: MachineProbeState) {
        _machineProbe.value = value
    }

    private val _kayakFirstSetupProgress = MutableStateFlow<KayakFirstSetupProgress?>(null)
    val kayakFirstSetupProgress: StateFlow<KayakFirstSetupProgress?> =
        _kayakFirstSetupProgress

    internal fun setKayakFirstSetupProgress(progress: KayakFirstSetupProgress) {
        val current = _kayakFirstSetupProgress.value
        if (current == null || progress.connectionId.linkGeneration >= current.connectionId.linkGeneration) {
            _kayakFirstSetupProgress.value = progress
        }
    }

    internal fun clearKayakFirstSetupProgress(connectionId: ConnectionId) {
        if (_kayakFirstSetupProgress.value?.connectionId == connectionId) {
            _kayakFirstSetupProgress.value = null
        }
    }

    // FTMS connection (primary). Compatibility accessor over the Phase 0 registry.
    var gatt: BluetoothGatt?
        get() = connectionRegistry.current(LegacyConnectionRole.FTMS)?.gatt
        set(value) = bindFtmsGatt(value?.safeAddress().orEmpty(), value)

    val connectionState: StateFlow<Int> = connectionRegistry.machineConnectionState

    /** Wall-clock when the FTMS link *first* became CONNECTED (ms since epoch). Cleared on disconnect. */
    @Volatile
    var linkedAtMs: Long? = null
        private set

    private val _lastGattError = MutableStateFlow<String?>(null)
    val lastGattError: StateFlow<String?> = _lastGattError

    // HRM connection (separate GATT). Compatibility accessor over the Phase 0 registry.
    var gattHrm: BluetoothGatt?
        get() = connectionRegistry.current(LegacyConnectionRole.HRM)?.gatt
        set(value) = bindHrmGatt(value?.safeAddress().orEmpty(), value)

    val hrmConnectionState: StateFlow<Int> = connectionRegistry.hrmConnectionState
    private val _lastHrmError = MutableStateFlow<String?>(null)
    val lastHrmError: StateFlow<String?> = _lastHrmError

    // Characteristics (FTMS)
    var controlPointChar: BluetoothGattCharacteristic? = null
        set(value) {
            val old = field?.uuid
            val new = value?.uuid
            Timber.tag(TAG).d("FTMS controlPointChar %s → %s", old, new)
            field = value
        }

    // Characteristics (HRM)
    var hrmMeasurementChar: BluetoothGattCharacteristic? = null
        set(value) {
            val old = field?.uuid
            val new = value?.uuid
            Timber.tag(TAG).d("HRM measurementChar %s → %s", old, new)
            field = value
        }
    var hrmCtrlPointChar: BluetoothGattCharacteristic? = null
        set(value) {
            val old = field?.uuid
            val new = value?.uuid
            Timber.tag(TAG).d("HRM ctrlPointChar %s → %s", old, new)
            field = value
        }
    var hrmBodyLocChar: BluetoothGattCharacteristic? = null
        set(value) {
            val old = field?.uuid
            val new = value?.uuid
            Timber.tag(TAG).d("HRM bodyLocChar %s → %s", old, new)
            field = value
        }

    // FTMS control (per 0x2AD9 Request Control)
    private val _haveControl = MutableStateFlow(false)
    val haveControlFlow: StateFlow<Boolean> = _haveControl

    // Stable projection of the current machine context's per-link CCCD readiness.
    val cccdReady: StateFlow<Boolean> = connectionRegistry.machineCccdReady

    // ─────────────────────────────────────────────────────────────
    // FTMS Fitness Machine Status + Targets (0x2ADA)
    // ─────────────────────────────────────────────────────────────

    /**
     * Last raw FTMS Fitness Machine Status opcode received (or null if none yet).
     * This is the first byte of the 0x2ADA payload.
     */
    private val _lastFtmsStatusCode = MutableStateFlow<Byte?>(null)
    val lastFtmsStatusCode: StateFlow<Byte?> = _lastFtmsStatusCode

    /**
     * Whether the upstream machine has reported a safety-key / emergency-stop event.
     * Set to true on STATUS_FITNESS_MACHINE_STOPPED_BY_SAFETY_KEY; typically cleared
     * on successful RESET or new session.
     */
    private val _safetyKeyEngaged = MutableStateFlow(false)
    val safetyKeyEngaged: StateFlow<Boolean> = _safetyKeyEngaged

    /** Current target speed in km/h (decoded from 0.01 km/h units). */
    private val _targetSpeedKmh = MutableStateFlow<Float?>(null)
    val targetSpeedKmh: StateFlow<Float?> = _targetSpeedKmh

    /** Current target incline in percent grade (decoded from 0.1 % units). */
    private val _targetInclinePercent = MutableStateFlow<Float?>(null)
    val targetInclinePercent: StateFlow<Float?> = _targetInclinePercent

    /** Current target resistance level (unitless, 0.1 resolution already applied). */
    private val _targetResistanceLevel = MutableStateFlow<Float?>(null)
    val targetResistanceLevel: StateFlow<Float?> = _targetResistanceLevel

    /** Current target power in watts. */
    private val _targetPowerWatts = MutableStateFlow<Int?>(null)
    val targetPowerWatts: StateFlow<Int?> = _targetPowerWatts

    /** Current target heart rate in bpm. */
    private val _targetHeartRateBpm = MutableStateFlow<Int?>(null)
    val targetHeartRateBpm: StateFlow<Int?> = _targetHeartRateBpm

    /* ───────────────────────────── units / session flags ───────────────────────────── */

    private val _units: MutableStateFlow<Int?> = MutableStateFlow(null)
    val units: StateFlow<Int?> = _units

    private val _activeSession: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val activeSession: StateFlow<Boolean> = _activeSession

    private val _isRunning: MutableStateFlow<Boolean?> = MutableStateFlow(null)
    val isRunning: StateFlow<Boolean?> = _isRunning

    private val _elapsedTimeSec: MutableStateFlow<Int?> = MutableStateFlow(null)
    val elapsedTimeSec: StateFlow<Int?> = _elapsedTimeSec

    /* ───────────────────────────── FTMS metrics (instant/avg/peak) ─────────────────────────────
       Note: "display" fields are unit-aware (km/mi), intended for direct UI consumption. */

    // Stroke / rowing
    private val _strokeRate = MutableStateFlow<Double?>(null)
    val strokeRate: StateFlow<Double?> = _strokeRate
    private val _peakStrokeRate = MutableStateFlow<Double?>(null)
    val peakStrokeRate: StateFlow<Double?> = _peakStrokeRate
    private val _strokeCount = MutableStateFlow<Int?>(null)
    val strokeCount: StateFlow<Int?> = _strokeCount

    // Distance (raw meters + unit-aware display)
    private val _totalDistanceMeters = MutableStateFlow<Int?>(null)
    val totalDistanceMeters: StateFlow<Int?> = _totalDistanceMeters
    private val _totalDistanceDisplay = MutableStateFlow<Double?>(null)
    val totalDistanceDisplay: StateFlow<Double?> = _totalDistanceDisplay

    // Speed (kph/mph + unit-aware display)
    private val _speedKph = MutableStateFlow<Double?>(null)
    val speedKph: StateFlow<Double?> = _speedKph
    private val _speedMph = MutableStateFlow<Double?>(null)
    val speedMph: StateFlow<Double?> = _speedMph
    private val _peakSpeedKph = MutableStateFlow<Double?>(null)
    val peakSpeedKph: StateFlow<Double?> = _peakSpeedKph
    private val _peakSpeedMph = MutableStateFlow<Double?>(null)
    val peakSpeedMph: StateFlow<Double?> = _peakSpeedMph
    private val _avgSpeedKph = MutableStateFlow<Double?>(null)
    val avgSpeedKph: StateFlow<Double?> = _avgSpeedKph

    private val _speedDisplay = MutableStateFlow<Double?>(null)
    val speedDisplay: StateFlow<Double?> = _speedDisplay
    private val _avgSpeedDisplay = MutableStateFlow<Double?>(null)
    val avgSpeedDisplay: StateFlow<Double?> = _avgSpeedDisplay
    private val _peakSpeedDisplay = MutableStateFlow<Double?>(null)
    val peakSpeedDisplay: StateFlow<Double?> = _peakSpeedDisplay

    // Pace (min / unit)
    private val _instPace = MutableStateFlow<Double?>(null)
    val instPace: StateFlow<Double?> = _instPace
    private val _avgPace = MutableStateFlow<Double?>(null)
    val avgPace: StateFlow<Double?> = _avgPace

    // Canonical metric pace remains available for unit-independent consumers such as rowing.
    private val _instantPaceMinPerKm = MutableStateFlow<Double?>(null)
    val instantPaceMinPerKm: StateFlow<Double?> = _instantPaceMinPerKm
    private val _averagePaceMinPerKm = MutableStateFlow<Double?>(null)
    val averagePaceMinPerKm: StateFlow<Double?> = _averagePaceMinPerKm

    // Power (W)
    private val _instantPower = MutableStateFlow<Int?>(null)
    val instantPower: StateFlow<Int?> = _instantPower
    private val _averagePower = MutableStateFlow<Int?>(null)
    val averagePower: StateFlow<Int?> = _averagePower
    private val _peakPower = MutableStateFlow<Int?>(null)
    val peakPower: StateFlow<Int?> = _peakPower

    // Resistance (unitless "level" or device-specific units)
    private val _resistance = MutableStateFlow<Double?>(null)
    val resistance: StateFlow<Double?> = _resistance

    // Energy / METs
    private val _expEnergyTotal = MutableStateFlow<Int?>(null)
    val expEnergyTotal: StateFlow<Int?> = _expEnergyTotal
    private val _expEnergyRateKcalPerHour = MutableStateFlow<Int?>(null)
    val expEnergyRateKcalPerHour: StateFlow<Int?> = _expEnergyRateKcalPerHour
    private val _metabolicEquivalent = MutableStateFlow<Double?>(null)
    val metabolicEquivalent: StateFlow<Double?> = _metabolicEquivalent

    // Incline / ramp / elevation
    private val _inclination = MutableStateFlow<Double?>(null)
    val inclination: StateFlow<Double?> = _inclination
    private val _rampAngle = MutableStateFlow<Double?>(null)
    val rampAngle: StateFlow<Double?> = _rampAngle
    private val _elevationGainPos = MutableStateFlow<Double?>(null)
    val elevationGainPos: StateFlow<Double?> = _elevationGainPos
    private val _elevationGainNeg = MutableStateFlow<Double?>(null)
    val elevationGainNeg: StateFlow<Double?> = _elevationGainNeg

    // Belt force
    private val _forceOnBelt = MutableStateFlow<Int?>(null)
    val forceOnBelt: StateFlow<Int?> = _forceOnBelt

    // Cadence / strides / floors / steps
    private val _instantCadence = MutableStateFlow<Double?>(null)
    val instantCadence: StateFlow<Double?> = _instantCadence
    private val _averageCadence = MutableStateFlow<Double?>(null)
    val averageCadence: StateFlow<Double?> = _averageCadence
    private val _totalStrides = MutableStateFlow<Double?>(null)
    val totalStrides: StateFlow<Double?> = _totalStrides
    private val _totalFloors = MutableStateFlow<Int?>(null)
    val totalFloors: StateFlow<Int?> = _totalFloors
    private val _totalSteps = MutableStateFlow<Int?>(null)
    val totalSteps: StateFlow<Int?> = _totalSteps

    /* ───────────────────────────── graphable time-series (full session) ─────────────────────────────
       Series are stored in SI/neutral units for consistency:
       - Heart Rate: bpm
       - Speed: kph
       - Cadence: spm (steps or strokes per minute depending on activity)
       - Power: watts
       - Incline: degrees (if device reports % grade, upstream should convert)
       - Resistance: device-specific "level" (Double)
       - Distance: meters
       Time-series are now **unbounded for the duration of a session**. We never trim by time here;
       graphs choose their own visible window (e.g., last 5 minutes vs. full-session zoom).
     */

    /** Optional baseline to avoid UI “jumps” for delta-based charts after a screen swap. */
    private var distanceBaselineMetersForUi: Int = 0
    /** Timestamp of the last series clear (for debugging or pacing logic). */
    private var lastSeriesClearAtMs: Long = 0L

    /**
     * Clear time-series buffers and (optionally) zero certain rolling aggregates while
     * *preserving* cumulative totals by default. This is the API your UI/ViewModel should call.
     *
     * @param keepTotals If true (default), cumulative totals such as totalDistanceMeters are kept.
     *                   If false, those totals are also zeroed (use with care on provider side).
     */
    @Synchronized
    fun clearTimeSeries(keepTotals: Boolean = true, nowMs: Long = System.currentTimeMillis()) {
        Timber.tag(TAG).i("clearTimeSeries(keepTotals=%s)", keepTotals)

        // 1) Clear time-series + running means (reuse helper)
        clearAllTimeSeries()

        // 2) Move baselines so delta-based math doesn’t spike after UI transitions
        val curDist = _totalDistanceMeters.value ?: 0
        distanceBaselineMetersForUi = curDist
        lastSeriesClearAtMs = nowMs

        // 3) If requested, also zero provider-side totals (rarely desired)
        if (!keepTotals) {
            _totalDistanceMeters.value = 0
            _totalDistanceDisplay.value = 0.0
            _elapsedTimeSec.value = 0
            // Reset lap projections too, since totals got zeroed
            _lapAvgSpeedMps.value = null
            _lapAvgSpeedDisplay.value = null
        }
    }

    /**
     * Helper for UI/encoders that want distance starting at 0 after the last series clear.
     * Consumers can use this instead of raw totalDistanceMeters to avoid a jump.
     */
    fun distanceSinceLastClear(): Int {
        val cur = _totalDistanceMeters.value ?: 0
        return (cur - distanceBaselineMetersForUi).coerceAtLeast(0)
    }

    private val _hrSeries = MutableStateFlow<List<TimedSample>>(emptyList())
    val hrSeries: StateFlow<List<TimedSample>> = _hrSeries

    private val _speedSeriesKph = MutableStateFlow<List<TimedSample>>(emptyList())
    val speedSeriesKph: StateFlow<List<TimedSample>> = _speedSeriesKph

    private val _cadenceSeriesSpm = MutableStateFlow<List<TimedSample>>(emptyList())
    val cadenceSeriesSpm: StateFlow<List<TimedSample>> = _cadenceSeriesSpm

    private val _powerSeriesW = MutableStateFlow<List<TimedSample>>(emptyList())
    val powerSeriesW: StateFlow<List<TimedSample>> = _powerSeriesW

    private val _inclineSeriesPercent = MutableStateFlow<List<TimedSample>>(emptyList())
    val inclineSeriesPercent: StateFlow<List<TimedSample>> = _inclineSeriesPercent

    private val _resistanceSeries = MutableStateFlow<List<TimedSample>>(emptyList())
    val resistanceSeries: StateFlow<List<TimedSample>> = _resistanceSeries

    private val _forceOnBeltSeries = MutableStateFlow<List<TimedSample>>(emptyList())
    val forceOnBeltSeries: StateFlow<List<TimedSample>> = _forceOnBeltSeries

    private val _elevationNetSeries = MutableStateFlow<List<TimedSample>>(emptyList())
    val elevationNetSeries: StateFlow<List<TimedSample>> = _elevationNetSeries

    // Stroke-rate series (SPM). Alias of cadenceSeriesSpm.
    val strokeRateSeriesSpm: StateFlow<List<TimedSample>> get() = _cadenceSeriesSpm

    /** Append a belt-force sample (N). */
    fun appendForceOnBeltSample(n: Int, tsMillis: Long = System.currentTimeMillis()) {
        _forceOnBelt.value = n
        _forceOnBeltSeries.value = _forceOnBeltSeries.value + TimedSample(tsMillis, n.toDouble())
        Timber.tag(TAG).v(
            "appendForceOnBeltSample N=%d seriesSize=%d",
            n,
            _forceOnBeltSeries.value.size
        )
    }

    /** Update cumulative elevation gains and append their signed net change. */
    fun setElevationGains(
        positiveMeters: Double?,
        negativeMeters: Double?,
        tsMillis: Long = System.currentTimeMillis(),
    ) {
        _elevationGainPos.value = positiveMeters
        _elevationGainNeg.value = negativeMeters
        netElevationChangeMeters(positiveMeters, negativeMeters)?.let { net ->
            _elevationNetSeries.value =
                _elevationNetSeries.value + TimedSample(tsMillis, net)
        }
    }


    /* ───────────────────────────── lap & derived values ───────────────────────────── */

    var lapStartDistanceMeters: Int = 0
    var lapStartElapsedSec: Int = 0
    var lapStartStrokeCount: Int = 0
    var lapStartSteps: Int = 0

    private val _lapCount = MutableStateFlow(0)
    val lapCount: StateFlow<Int> = _lapCount
    private val _lapElapsedTimeSec = MutableStateFlow<Int?>(null)
    val lapElapsedTimeSec: StateFlow<Int?> = _lapElapsedTimeSec
    private val _lapDistanceMeters = MutableStateFlow<Int?>(null)
    val lapDistanceMeters: StateFlow<Int?> = _lapDistanceMeters
    private val _lapDistanceDisplay = MutableStateFlow<Double?>(null) // km or mi
    val lapDistanceDisplay: StateFlow<Double?> = _lapDistanceDisplay

    private val _lapAvgSpeedMps = MutableStateFlow<Double?>(null)
    val lapAvgSpeedMps: StateFlow<Double?> = _lapAvgSpeedMps
    private val _lapAvgSpeedDisplay = MutableStateFlow<Double?>(null)
    val lapAvgSpeedDisplay: StateFlow<Double?> = _lapAvgSpeedDisplay
    private val _lapRunPaceMinPerUnit = MutableStateFlow<Double?>(null)
    val lapRunPaceMinPerUnit: StateFlow<Double?> = _lapRunPaceMinPerUnit
    private val _lapRowPaceMinPer500m = MutableStateFlow<Double?>(null)
    val lapRowPaceMinPer500m: StateFlow<Double?> = _lapRowPaceMinPer500m

    private val _lapStrokeCount = MutableStateFlow<Int?>(null)
    val lapStrokeCount: StateFlow<Int?> = _lapStrokeCount
    private val _lapSteps = MutableStateFlow<Int?>(null)
    val lapSteps: StateFlow<Int?> = _lapSteps
    private val _lapCadenceSpm = MutableStateFlow<Double?>(null)
    val lapCadenceSpm: StateFlow<Double?> = _lapCadenceSpm

    var lapAvgCadenceTotal = 0.0
    var lapAvgCadenceCount = 0

    /* ───────────────────────────── smoothing / running averages ───────────────────────────── */

    // Running mean accumulators for HR/speed/cadence/power (kph/spm/watts).
    private var hrSum = 0.0
    private var hrCount = 0

    private var speedKphSum = 0.0
    private var speedCount = 0

    private var cadenceSpmSum = 0.0
    private var cadenceCount = 0

    private var powerSum = 0.0
    private var powerCount = 0

    /* ───────────────────────────── HRM aggregation (Bluetooth only) ───────────────────────────── */

    enum class HrSource { UNKNOWN, EXTERNAL_HRM }
    enum class BodyLocation { OTHER, CHEST, WRIST, FINGER, HAND, EAR_LOBE, FOOT, UNKNOWN }

    // Public HR outputs
    private val _heartRate = MutableStateFlow<Int?>(null)
    val heartRate: StateFlow<Int?> = _heartRate
    private val _heartRateSource = MutableStateFlow(HrSource.UNKNOWN)
    val heartRateSource: StateFlow<HrSource> = _heartRateSource
    private val _heartRateZone = MutableStateFlow<Int?>(null) // 0..5
    val heartRateZone: StateFlow<Int?> = _heartRateZone

    // Average heart rate over session (simple running mean)
    private val _averageHeartRate = MutableStateFlow<Int?>(null)
    val averageHeartRate: StateFlow<Int?> = _averageHeartRate
    // NEW: average HR zone (0..5) based on averageHeartRate
    private val _averageHeartRateZone = MutableStateFlow<Int?>(null)
    val averageHeartRateZone: StateFlow<Int?> = _averageHeartRateZone

    private val _sensorContact = MutableStateFlow<Boolean?>(null)
    val sensorContact: StateFlow<Boolean?> = _sensorContact
    private val _energyExpended = MutableStateFlow<Int?>(null)
    val energyExpended: StateFlow<Int?> = _energyExpended
    private val _rrIntervalsMs = MutableStateFlow<List<Int>>(emptyList())
    val rrIntervalsMs: StateFlow<List<Int>> = _rrIntervalsMs
    private val _bodySensorLocation = MutableStateFlow<BodyLocation?>(null)
    val bodySensorLocation: StateFlow<BodyLocation?> = _bodySensorLocation

    // Inputs (Bluetooth only)
    private val _externalHeartRate = MutableStateFlow<Int?>(null)
    val externalHeartRate: StateFlow<Int?> = _externalHeartRate
    var lastExternalAt: Long = 0L

    // HR zone bindings (provided by App layer)
    @Volatile var hrMaxFromApp: StateFlow<Int?>? = null
    @Volatile var restingHrFromApp: StateFlow<Int?>? = null
    @Volatile var hrZoneModeFromApp: StateFlow<HrZoneMode>? = null

    // make actual backing flows:
    private val _hrMaxFromApp = MutableStateFlow<Int?>(null)
    private val _restingHrFromApp = MutableStateFlow<Int?>(null)
    private val _hrZoneModeFromApp = MutableStateFlow(HrZoneMode.Off)

    init {
        // keep the old public Volatile vars pointing to these so older code
        // that expects them to be non-null StateFlows will now get a value
        hrMaxFromApp = _hrMaxFromApp
        restingHrFromApp = _restingHrFromApp
        hrZoneModeFromApp = _hrZoneModeFromApp
    }

    fun setHrMaxFromApp(value: Int?) {
        Timber.tag(TAG).d("setHrMaxFromApp(%s)", value?.toString() ?: "null")
        _hrMaxFromApp.value = value
    }

    fun setRestingHrFromApp(value: Int?) {
        Timber.tag(TAG).d("setRestingHrFromApp(%s)", value?.toString() ?: "null")
        _restingHrFromApp.value = value
    }

    fun setHrZoneModeFromApp(mode: HrZoneMode) {
        Timber.tag(TAG).d("setHrZoneModeFromApp(%s)", mode)
        _hrZoneModeFromApp.value = mode
    }

    /* ───────────────────────────── device helpers / mutation API ───────────────────────────── */

    // Discovery -> machine feature
    fun updateDeviceMachineFeatureByAddress(address: String, machineTypeOrdinal: Int) {
        if (address.isBlank()) return
        val map = _discoveredDevices.value.toMutableMap()
        val entry = map.entries.firstOrNull { it.value.address.equals(address, ignoreCase = true) } ?: return
        val oldFeature = entry.value.machineFeature
        if (oldFeature == machineTypeOrdinal) return
        map[entry.key] = entry.value.copy(machineFeature = machineTypeOrdinal)
        _discoveredDevices.value = map
        Timber.tag(TAG).i(
            "updateDeviceMachineFeatureByAddress(%s): %s → %d",
            address,
            oldFeature?.toString() ?: "null",
            machineTypeOrdinal
        )
    }

    // Scanning + lists
    internal fun setCccdReady(context: ConnectionContext, value: Boolean) {
        val oldEffective = cccdReady.value
        connectionRegistry.setCccdReady(context, value)
        val newEffective = cccdReady.value
        Timber.tag(TAG).d(
            "cccdReady[%s] %s → %s (raw=%s)",
            context.connectionId,
            oldEffective,
            newEffective,
            value,
        )
    }

    fun setScanning(v: Boolean) {
        val old = _isScanning.value
        _isScanning.value = v
        Timber.tag(TAG).d("isScanning %s → %s", old, v)
    }

    fun putDiscoveredDevices(map: Map<String, DiscoveredDevice>) {
        _discoveredDevices.value = map
        Timber.tag(TAG).d("putDiscoveredDevices(size=%d)", map.size)
    }

    internal fun setActiveAdvertisementMachine(machine: ActiveAdvertisementMachine?) {
        _activeAdvertisementMachine.value = machine
    }

    // Connections
    @GattLibraryInternalApi
    fun openConnectionContext(
        role: LegacyConnectionRole,
        address: String,
        handlers: Collection<BtEngineGattCore.ProtocolHandler>,
        initialState: Int = BluetoothProfile.STATE_CONNECTING,
    ): ConnectionContext {
        val context = connectionRegistry.open(role, address, gatt = null, initialState)
        context.handlers.addAll(handlers)
        if (role == LegacyConnectionRole.FTMS) linkedAtMs = null
        Timber.tag(TAG).i("Opened %s context %s", role, context.connectionId)
        return context
    }

    internal fun openAdditionalSensorContext(
        address: String,
        handlers: Collection<BtEngineGattCore.ProtocolHandler>,
        initialState: Int = BluetoothProfile.STATE_CONNECTING,
    ): ConnectionContext {
        val context = connectionRegistry.openAdditional(address, gatt = null, initialState)
        context.handlers.addAll(handlers)
        Timber.tag(TAG).i("Opened sensor context %s", context.connectionId)
        return context
    }

    internal fun attachGatt(context: ConnectionContext, gatt: BluetoothGatt): Boolean {
        val attached = connectionRegistry.attach(context, gatt)
        Timber.tag(TAG).d("Attach GATT context=%s accepted=%s", context.connectionId, attached)
        return attached
    }

    internal fun setCscRoleCapability(
        context: ConnectionContext,
        capability: CscRoleCapability?,
    ) {
        connectionRegistry.setCscRoleCapability(context, capability)
    }

    internal fun setCscAssignedRoles(
        context: ConnectionContext,
        assignedRoles: Set<CscRole>,
    ) {
        connectionRegistry.setCscAssignedRoles(context, assignedRoles)
    }

    /** Publish state by physical-link identity; legacy flows are registry projections. */
    @GattLibraryInternalApi
    fun setConnectionState(context: ConnectionContext, state: Int) {
        val old = context.connectionState.value.bluetoothProfileState
        val role = connectionRegistry.roleOf(context)
        connectionRegistry.updateState(context, state)
        Timber.tag(TAG).i(
            "Connection state context=%s role=%s %d → %d",
            context.connectionId,
            role ?: "none",
            old,
            state,
        )

        if (role == LegacyConnectionRole.FTMS) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (old != BluetoothProfile.STATE_CONNECTED) {
                        linkedAtMs = System.currentTimeMillis()
                        Timber.tag(TAG).d("linkedAtMs set → %d", linkedAtMs)
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (linkedAtMs != null) {
                        Timber.tag(TAG).d("linkedAtMs cleared (was %d)", linkedAtMs)
                    }
                    linkedAtMs = null
                }
            }
        }
    }

    internal fun clearConnection(context: ConnectionContext): Boolean {
        val role = connectionRegistry.roleOf(context)
        val cleared = connectionRegistry.clear(context)
        if (cleared) {
            // §0.7a policy state is per link generation. A reconnect gets a new
            // generation and therefore a fresh policy; keeping the old one would
            // both leak and let a stale deadline fire against a live link.
            adapterLiveness.forget(context.connectionId)
        }
        if (cleared && role == LegacyConnectionRole.FTMS) linkedAtMs = null
        Timber.tag(TAG).d("Clear context=%s accepted=%s", context.connectionId, cleared)
        return cleared
    }

    internal fun bindFtmsGatt(address: String, value: BluetoothGatt?) {
        bindGatt(
            role = LegacyConnectionRole.FTMS,
            label = "FTMS",
            address = address,
            value = value,
            initialState = connectionState.value,
        )
    }

    internal fun bindHrmGatt(address: String, value: BluetoothGatt?) {
        bindGatt(
            role = LegacyConnectionRole.HRM,
            label = "HRM",
            address = address,
            value = value,
            initialState = hrmConnectionState.value,
        )
    }

    private fun bindGatt(
        role: LegacyConnectionRole,
        label: String,
        address: String,
        value: BluetoothGatt?,
        initialState: Int,
    ) {
        val oldAddr = connectionRegistry.current(role)?.address
        val newAddr = value?.safeAddress()?.ifBlank { address }?.let(::canonicalConnectionAddress)
        Timber.tag(TAG).d("%s GATT reference change %s → %s", label, oldAddr, newAddr)
        if (value == null) {
            connectionRegistry.clear(role)
        } else {
            connectionRegistry.bind(role, newAddr.orEmpty(), value, initialState)
        }
    }

    fun setHaveControl(v: Boolean) {
        val old = _haveControl.value
        _haveControl.value = v
        Timber.tag(TAG).d("haveControl %s → %s", old, v)
    }

    // FTMS Status / Targets
    fun setLastFtmsStatusCode(code: Byte?) {
        val old = _lastFtmsStatusCode.value
        _lastFtmsStatusCode.value = code
        Timber.tag(TAG).d(
            "lastFtmsStatusCode %s → %s",
            old?.let { "0x%02X".format(it) } ?: "null",
            code?.let { "0x%02X".format(it) } ?: "null"
        )
    }

    fun setSafetyKeyEngaged(v: Boolean) {
        val old = _safetyKeyEngaged.value
        _safetyKeyEngaged.value = v
        Timber.tag(TAG).w("safetyKeyEngaged %s → %s", old, v)
    }

    fun setTargetSpeedKmh(kmh: Float?) {
        val old = _targetSpeedKmh.value
        _targetSpeedKmh.value = kmh
        Timber.tag(TAG).d("targetSpeedKmh %s → %s", old?.toString() ?: "null", kmh?.toString() ?: "null")
    }

    fun setTargetInclinePercent(percent: Float?) {
        val old = _targetInclinePercent.value
        _targetInclinePercent.value = percent
        Timber.tag(TAG).d("targetInclinePercent %s → %s", old?.toString() ?: "null", percent?.toString() ?: "null")
    }

    fun setTargetResistanceLevel(level: Float?) {
        val old = _targetResistanceLevel.value
        _targetResistanceLevel.value = level
        Timber.tag(TAG).d("targetResistanceLevel %s → %s", old?.toString() ?: "null", level?.toString() ?: "null")
    }

    fun setTargetPowerWatts(watts: Int?) {
        val old = _targetPowerWatts.value
        _targetPowerWatts.value = watts
        Timber.tag(TAG).d("targetPowerWatts %s → %s", old?.toString() ?: "null", watts?.toString() ?: "null")
    }

    fun setTargetHeartRateBpm(bpm: Int?) {
        val old = _targetHeartRateBpm.value
        _targetHeartRateBpm.value = bpm
        Timber.tag(TAG).d("targetHeartRateBpm %s → %s", old?.toString() ?: "null", bpm?.toString() ?: "null")
    }

    /**
     * The Life Fitness handshake profile, pushed from the settings screen (plan §6, D56).
     *
     * Held rather than read, following the same one-way push as heart-rate preferences and CSC
     * calibration: the engine has no access to `SharedPreferences` and should not acquire one
     * for a single family.
     *
     * **Never logged, and deliberately not a `StateFlow`.** It carries a name and an email; a
     * flow invites collectors, and a collector is a place a value can be rendered. The only
     * consumer is the setup transcript, which reads it once per link.
     */
    @Volatile
    internal var lifeFitnessProfile: LifeFitnessProfile =
        LifeFitnessProfile.EMPTY
        private set

    /** Derived age in years, from the birth month and year the settings screen holds. */
    @Volatile
    var lifeFitnessAgeYears: Int = 0
        private set

    /**
     * Which profile fields a Life Fitness connection was missing, for the 6.5c prompt.
     *
     * **Field identities, not values** — this is the one Life Fitness datum that is safe to put in
     * a flow, because `LifeFitnessProfileField` is an enum of labels and carries nothing the user
     * typed. [lifeFitnessProfile] itself stays off the flow for exactly the opposite reason.
     *
     * Empty means no prompt is owed.
     */
    private val _lifeFitnessProfilePrompt =
        MutableStateFlow<List<LifeFitnessProfileField>>(
            emptyList()
        )
    val lifeFitnessProfilePrompt:
        StateFlow<List<LifeFitnessProfileField>> =
        _lifeFitnessProfilePrompt

    /** Raise the prompt. Called when a connected machine's handshake could not start. */
    internal fun setLifeFitnessProfilePrompt(
        missing: List<LifeFitnessProfileField>,
    ) {
        _lifeFitnessProfilePrompt.value = missing
        Timber.tag(TAG).i("lifeFitnessProfilePrompt: %d field(s) requested", missing.size)
    }

    /** Dismiss the prompt without filling anything in. */
    internal fun clearLifeFitnessProfilePrompt() {
        _lifeFitnessProfilePrompt.value = emptyList()
    }

    /**
     * Push the profile from the app.
     *
     * Logs that a profile was set and how many required fields are still blank — never a value.
     * "Setup will not start because two fields are empty" is the diagnostic worth having; which
     * two, and what the others contain, is not FitBridge's to publish.
     */
    internal fun setLifeFitnessProfileFromApp(
        profile: LifeFitnessProfile,
        ageYears: Int,
    ) {
        lifeFitnessProfile = profile
        lifeFitnessAgeYears = ageYears
        // A prompt that outlived the fields being filled in would be a dialog the user cannot
        // dismiss by doing what it asked. Clearing on any push is deliberate even though the push
        // fires on every keystroke: the prompt is re-raised by the next connection attempt if the
        // profile is still short, and that attempt is the only thing that can know.
        if (profile.isPopulated) _lifeFitnessProfilePrompt.value = emptyList()
        Timber.tag(TAG).d(
            "setLifeFitnessProfileFromApp: %d required field(s) still blank, age %d",
            profile.missingFields.size,
            ageYears,
        )
    }

    // Session & units (starting a new session clears time-series + averages)
    fun setUnits(units: Int?) {
        val old = _units.value
        _units.value = units
        Timber.tag(TAG).i("setUnits %s → %s", old?.toString() ?: "null", units?.toString() ?: "null")

        // Re-emit pace in the newly selected units
        _instantPaceMinPerKm.value?.let { emitInstantPaceForUnits(it, units) }
        _averagePaceMinPerKm.value?.let { emitAvgPaceForUnits(it, units) }
    }

    fun setActiveSession(active: Boolean) {
        val was = _activeSession.value
        _activeSession.value = active
        Timber.tag(TAG).i("setActiveSession %s → %s", was, active)

        if (active && !was) {
            Timber.tag(TAG).i("New active session started → reset averages, series and FTMS status/targets")
            resetAveragesAndSeries()
            // Reset FTMS status-related state when a new session starts
            _lastFtmsStatusCode.value = null
            _safetyKeyEngaged.value = false
            _targetSpeedKmh.value = null
            _targetInclinePercent.value = null
            _targetResistanceLevel.value = null
            _targetPowerWatts.value = null
            _targetHeartRateBpm.value = null
            _haveControl.value = false
        }
    }

    fun setIsRunning(running: Boolean?) {
        val old = _isRunning.value
        _isRunning.value = running
        Timber.tag(TAG).d("isRunning %s → %s", old?.toString() ?: "null", running?.toString() ?: "null")
    }

    fun setElapsedSeconds(sec: Int?) {
        val old = _elapsedTimeSec.value
        _elapsedTimeSec.value = sec
        Timber.tag(TAG).v("elapsedTimeSec %s → %s", old?.toString() ?: "null", sec?.toString() ?: "null")
    }

    // Metrics setters (kept quiet to avoid log spam; still available if you want to instrument later)
    fun setStrokeRate(v: Double?) { _strokeRate.value = v }
    fun setStrokeCount(v: Int?) { _strokeCount.value = v }

    fun setTotalDistanceMeters(m: Int?) { _totalDistanceMeters.value = m }
    fun setTotalDistanceDisplay(dist: Double?) { _totalDistanceDisplay.value = dist }

    fun setSpeedKph(v: Double?) { _speedKph.value = v }
    fun setSpeedMph(v: Double?) { _speedMph.value = v }
    fun setAvgSpeedKph(v: Double?) { _avgSpeedKph.value = v }
    fun setPeakSpeedKph(v: Double?) { _peakSpeedKph.value = v }
    fun setPeakSpeedMph(v: Double?) { _peakSpeedMph.value = v }

    fun setSpeedDisplay(v: Double?) { _speedDisplay.value = v }
    fun setAvgSpeedDisplay(v: Double?) { _avgSpeedDisplay.value = v }
    fun setPeakSpeedDisplay(v: Double?) { _peakSpeedDisplay.value = v }

    // ─────────────────────────────────────────────────────────────
    // PACE (now expects **minutes per km**)
    // ─────────────────────────────────────────────────────────────

    /**
     * @param minPerKm canonical metric pace (minutes per kilometer)
     */
    fun setInstantPace(minPerKm: Double?) {
        _instantPaceMinPerKm.value = minPerKm
        Timber.tag(TAG).v("setInstantPace(minPerKm=%s)", minPerKm?.toString() ?: "null")
        val units = _units.value
        emitInstantPaceForUnits(minPerKm, units)
    }

    /**
     * @param minPerKm canonical metric average pace (minutes per kilometer)
     */
    fun setAvgPace(minPerKm: Double?) {
        _averagePaceMinPerKm.value = minPerKm
        Timber.tag(TAG).v("setAvgPace(minPerKm=%s)", minPerKm?.toString() ?: "null")
        val units = _units.value
        emitAvgPaceForUnits(minPerKm, units)
    }

    private fun emitInstantPaceForUnits(minPerKm: Double?, units: Int?) {
        if (minPerKm == null) {
            _instPace.value = null
            return
        }
        // 1 = imperial → min/mi
        // else → min/km
        val display = if (units == 1) {
            // 1 mi = 1.609344 km
            minPerKm * 1.609344
        } else {
            minPerKm
        }
        _instPace.value = display
    }

    private fun emitAvgPaceForUnits(minPerKm: Double?, units: Int?) {
        if (minPerKm == null) {
            _avgPace.value = null
            return
        }
        val display = if (units == 1) {
            minPerKm * 1.609344
        } else {
            minPerKm
        }
        _avgPace.value = display
    }

    fun setInstantPower(w: Int?) { _instantPower.value = w }
    fun setAveragePower(w: Int?) { _averagePower.value = w }
    fun setPeakPower(w: Int?) { _peakPower.value = w }

    fun setResistance(r: Double?) { _resistance.value = r }

    fun setExpEnergyTotal(kcal: Int?) { _expEnergyTotal.value = kcal }
    fun setExpEnergyRateKcalPerHour(kcalPerHour: Int?) {
        _expEnergyRateKcalPerHour.value = kcalPerHour
    }
    fun setMetabolicEquivalent(met: Double?) { _metabolicEquivalent.value = met }

    fun setInclination(v: Double?) { _inclination.value = v }
    fun setRampAngle(v: Double?) { _rampAngle.value = v }
    fun setElevationGainPos(v: Double?) { _elevationGainPos.value = v }
    fun setElevationGainNeg(v: Double?) { _elevationGainNeg.value = v }

    fun setForceOnBelt(n: Int?) { _forceOnBelt.value = n }

    fun setInstantCadence(v: Double?) { _instantCadence.value = v }
    fun setAverageCadence(v: Double?) { _averageCadence.value = v }
    fun setTotalStrides(v: Double?) { _totalStrides.value = v }
    fun setTotalFloors(v: Int?) { _totalFloors.value = v }
    fun setTotalSteps(v: Int?) { _totalSteps.value = v }

    fun setLapCount(v: Int) {
        val old = _lapCount.value
        _lapCount.value = v
        Timber.tag(TAG).i("lapCount %d → %d", old, v)
    }

    fun setLapElapsedTimeSec(v: Int?) { _lapElapsedTimeSec.value = v }
    fun setLapDistanceMeters(v: Int?) { _lapDistanceMeters.value = v }
    fun setLapDistanceDisplay(v: Double?) { _lapDistanceDisplay.value = v }

    fun setLapAvgSpeedMps(v: Double?) { _lapAvgSpeedMps.value = v }
    fun setLapAvgSpeedDisplay(v: Double?) { _lapAvgSpeedDisplay.value = v }
    fun setLapRunPaceMinPerUnit(v: Double?) { _lapRunPaceMinPerUnit.value = v }
    fun setLapRowPaceMinPer500m(v: Double?) { _lapRowPaceMinPer500m.value = v }

    fun setLapStrokeCount(v: Int?) { _lapStrokeCount.value = v }
    fun setLapSteps(v: Int?) { _lapSteps.value = v }
    fun setLapCadenceSpm(v: Double?) { _lapCadenceSpm.value = v }

    // HR reporting
    fun setHeartRate(bpm: Int?) {
        val old = _heartRate.value
        _heartRate.value = bpm
        Timber.tag(TAG).v("heartRate %s → %s", old?.toString() ?: "null", bpm?.toString() ?: "null")
    }

    fun setHeartRateSource(src: HrSource) {
        val old = _heartRateSource.value
        _heartRateSource.value = src
        Timber.tag(TAG).d("heartRateSource %s → %s", old, src)
    }

    fun setHeartRateZone(zone: Int?) {
        val old = _heartRateZone.value
        _heartRateZone.value = zone
        Timber.tag(TAG).d("heartRateZone %s → %s", old?.toString() ?: "null", zone?.toString() ?: "null")
    }

    // NEW: zone derived from averageHeartRate
    fun setAverageHeartRateZone(zone: Int?) {
        val old = _averageHeartRateZone.value
        _averageHeartRateZone.value = zone
        Timber.tag(TAG).d(
            "averageHeartRateZone %s → %s",
            old?.toString() ?: "null",
            zone?.toString() ?: "null"
        )
    }

    fun setExternalHeartRate(bpm: Int?) {
        val old = _externalHeartRate.value
        _externalHeartRate.value = bpm
        Timber.tag(TAG).v("externalHeartRate %s → %s", old?.toString() ?: "null", bpm?.toString() ?: "null")
    }

    fun updateLastExternalAt(ts: Long) {
        val old = lastExternalAt
        lastExternalAt = ts
        Timber.tag(TAG).v("lastExternalAt %d → %d", old, ts)
    }

    /* ───────────────────────────── time-series helpers (append) ─────────────────────────────
       NOTE: We no longer trim by a rolling time window here. All samples for a session are kept
       until cleared (e.g. on new session start or explicit clearTimeSeries/clearAllTimeSeries).
       Graphs decide what portion of the series to display.
     */

    /** Append a heart rate sample (bpm); updates averageHeartRate and series. */
    /**
     * Adds a point to the heart-rate graph without touching the readout, the
     * running total or the average.
     *
     * Exists for the strap-dropout marker: a gap should be visible on the chart,
     * but it is missing data rather than a measured zero. Folding it into the
     * average would drag a session's mean down because a chest strap lost
     * contact for a few seconds.
     */
    fun appendHeartRateSeriesPoint(value: Double, tsMillis: Long = System.currentTimeMillis()) {
        _hrSeries.value = _hrSeries.value + TimedSample(tsMillis, value)
        Timber.tag(TAG).v("appendHeartRateSeriesPoint value=%.0f seriesSize=%d", value, _hrSeries.value.size)
    }

    fun appendHeartRateSample(bpm: Int, tsMillis: Long = System.currentTimeMillis()) {
        _heartRate.value = bpm
        hrSum += bpm
        hrCount += 1
        _averageHeartRate.value = (hrSum / hrCount).roundToInt()
        _hrSeries.value = _hrSeries.value + TimedSample(tsMillis, bpm.toDouble())
        Timber.tag(TAG).v(
            "appendHeartRateSample bpm=%d avg=%s seriesSize=%d",
            bpm,
            _averageHeartRate.value?.toString() ?: "null",
            _hrSeries.value.size
        )
    }

    /** Append a speed sample (kph); updates avgSpeedKph and series. */
    fun appendSpeedKphSample(kph: Double, tsMillis: Long = System.currentTimeMillis()) {
        _speedKph.value = kph
        speedKphSum += kph
        speedCount += 1
        val averageKph = speedKphSum / speedCount
        _avgSpeedKph.value = averageKph
        _avgSpeedDisplay.value = if (_units.value == 1) averageKph / 1.609344 else averageKph
        _speedSeriesKph.value = _speedSeriesKph.value + TimedSample(tsMillis, kph)
        Timber.tag(TAG).v(
            "appendSpeedKphSample kph=%.2f avg=%.2f seriesSize=%d",
            kph,
            _avgSpeedKph.value ?: 0.0,
            _speedSeriesKph.value.size
        )
    }

    /** Append a cadence sample (spm = steps/strokes per minute); updates averageCadence and series. */
    fun appendCadenceSpmSample(spm: Double, tsMillis: Long = System.currentTimeMillis()) {
        _instantCadence.value = spm
        cadenceSpmSum += spm
        cadenceCount += 1
        _averageCadence.value = cadenceSpmSum / cadenceCount
        _cadenceSeriesSpm.value = _cadenceSeriesSpm.value + TimedSample(tsMillis, spm)
        Timber.tag(TAG).v(
            "appendCadenceSpmSample spm=%.1f avg=%.1f seriesSize=%d",
            spm,
            _averageCadence.value ?: 0.0,
            _cadenceSeriesSpm.value.size
        )
    }

    /** Append a rower stroke-rate sample and keep its graph/average cadence series in sync. */
    fun appendStrokeRateSample(spm: Double, tsMillis: Long = System.currentTimeMillis()) {
        _strokeRate.value = spm
        _peakStrokeRate.value = maxOf(_peakStrokeRate.value ?: spm, spm)
        appendCadenceSpmSample(spm, tsMillis)
    }

    /** Append a power sample (watts); updates averagePower and series. */
    fun appendPowerSample(watts: Int, tsMillis: Long = System.currentTimeMillis()) {
        _instantPower.value = watts
        powerSum += watts
        powerCount += 1
        _averagePower.value = (powerSum / powerCount).roundToInt()
        _powerSeriesW.value = _powerSeriesW.value + TimedSample(tsMillis, watts.toDouble())
        Timber.tag(TAG).v(
            "appendPowerSample watts=%d avg=%s seriesSize=%d",
            watts,
            _averagePower.value?.toString() ?: "null",
            _powerSeriesW.value.size
        )
    }

    /** Append an incline sample (percent grade). */
    fun appendInclinePercentSample(percent: Double, tsMillis: Long = System.currentTimeMillis()) {
        _inclination.value = percent
        _inclineSeriesPercent.value = _inclineSeriesPercent.value + TimedSample(tsMillis, percent)
        Timber.tag(TAG).v(
            "appendInclinePercentSample percent=%.2f seriesSize=%d",
            percent,
            _inclineSeriesPercent.value.size
        )
    }

    /** Append a resistance sample (device-specific level as Double). */
    fun appendResistanceSample(level: Double, tsMillis: Long = System.currentTimeMillis()) {
        _resistance.value = level
        _resistanceSeries.value = _resistanceSeries.value + TimedSample(tsMillis, level)
        Timber.tag(TAG).v(
            "appendResistanceSample level=%.2f seriesSize=%d",
            level,
            _resistanceSeries.value.size
        )
    }

    /** Clear all time-series (e.g., on new session start). */
    fun clearAllSeries() {
        Timber.tag(TAG).i("clearAllSeries()")
        _hrSeries.value = emptyList()
        _speedSeriesKph.value = emptyList()
        _cadenceSeriesSpm.value = emptyList()
        _powerSeriesW.value = emptyList()
        _inclineSeriesPercent.value = emptyList()
        _resistanceSeries.value = emptyList()
        _forceOnBeltSeries.value = emptyList()
        _elevationNetSeries.value = emptyList()
    }

    private fun resetAveragesAndSeries() {
        Timber.tag(TAG).d("resetAveragesAndSeries()")
        hrSum = 0.0
        hrCount = 0
        _averageHeartRate.value = null
        _averageHeartRateZone.value = null

        speedKphSum = 0.0
        speedCount = 0
        _avgSpeedKph.value = null
        _avgSpeedDisplay.value = null

        cadenceSpmSum = 0.0
        cadenceCount = 0
        _averageCadence.value = null

        powerSum = 0.0
        powerCount = 0
        _averagePower.value = null

        clearAllSeries()
    }

    /* ───────────────────────────── error routing ───────────────────────────── */

    fun setGattErrorFor(target: BluetoothGatt, msg: String) {
        if (target === gattHrm) {
            _lastHrmError.value = msg
            Timber.tag(TAG).w("HRM GATT error: %s", msg)
        } else {
            _lastGattError.value = msg
            Timber.tag(TAG).w("FTMS GATT error: %s", msg)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // ADD: distance series for rower-derived speed
    // ─────────────────────────────────────────────────────────────
    private val _distanceSeriesM = MutableStateFlow<List<TimedSample>>(emptyList())
    val distanceSeriesM: StateFlow<List<TimedSample>> = _distanceSeriesM

    fun appendDistanceMetersSample(meters: Int, tsMillis: Long = System.currentTimeMillis()) {
        val now = tsMillis
        val curr = _distanceSeriesM.value
        _distanceSeriesM.value = curr + TimedSample(now, meters.toDouble())
        Timber.tag(TAG).v(
            "appendDistanceMetersSample meters=%d seriesSize=%d",
            meters,
            _distanceSeriesM.value.size
        )
    }

    fun updateRowingSpeed(nowMs: Long = System.currentTimeMillis()) {
        // 3–5s window
        val maxAgeMs = 5_000L
        val minSpanMs = 2_000L
        val windowStart = nowMs - maxAgeMs

        val series = _distanceSeriesM.value
        if (series.isEmpty()) {
            _speedKph.value = null
            _speedMph.value = null
            _speedDisplay.value = null
            _avgSpeedKph.value = null
            _avgSpeedDisplay.value = null
            Timber.tag(TAG).v("updateRowingSpeed: no distance samples")
            return
        }

        // keep only last 5s *for the computation* (series itself is full-session)
        val recent = series.filter { it.tMillis >= windowStart && it.tMillis <= nowMs }
        if (recent.size < 2) {
            _speedKph.value = null
            _speedMph.value = null
            _speedDisplay.value = null
            Timber.tag(TAG).v("updateRowingSpeed: insufficient samples (recent=%d)", recent.size)
            return
        }

        val newest = recent.last()
        // pick the oldest sample that's at least 2s older than newest; else use the earliest in the window
        val oldest = recent.firstOrNull { newest.tMillis - it.tMillis >= minSpanMs } ?: recent.first()
        val dtMs = (newest.tMillis - oldest.tMillis).coerceAtLeast(0L)
        if (dtMs < minSpanMs) {
            _speedKph.value = null
            _speedMph.value = null
            _speedDisplay.value = null
            Timber.tag(TAG).v("updateRowingSpeed: dtMs too small (%d)", dtMs)
            return
        }

        // compute delta distance (m)
        val dm = newest.v - oldest.v
        if (dm <= 0.0) {
            _speedKph.value = null
            _speedMph.value = null
            _speedDisplay.value = null
            Timber.tag(TAG).v("updateRowingSpeed: non-positive delta distance dm=%.2f", dm)
            return
        }

        val dtSec = dtMs / 1000.0
        val mps = dm / dtSec
        val kph = mps * 3.6

        Timber.tag(TAG).v(
            "updateRowingSpeed: dm=%.2f dtMs=%d → mps=%.2f kph=%.2f",
            dm,
            dtMs,
            mps,
            kph
        )

        // Rower Data has no native speed field, so every distance window replaces
        // the previous derived value. Guarding on non-null froze the first value.
        _speedKph.value = kph
        _speedMph.value = kph * 0.621371
        _speedDisplay.value = when (_units.value) {
            1    -> kph * 0.621371    // mi/h
            else -> kph               // km/h
        }
        _speedSeriesKph.value = _speedSeriesKph.value + TimedSample(nowMs, kph)

        val peakKph = maxOf(_peakSpeedKph.value ?: 0.0, kph)
        _peakSpeedKph.value = peakKph
        _peakSpeedMph.value = peakKph * 0.621371
        _peakSpeedDisplay.value = when (_units.value) {
            1 -> peakKph * 0.621371
            else -> peakKph
        }

        // Global average speed is distance / elapsed and must refresh continuously.
        val totalDistM = _totalDistanceMeters.value
        val elapsedSec = _elapsedTimeSec.value
        if (totalDistM != null && elapsedSec != null && elapsedSec > 0) {
            val avgMps = totalDistM.toDouble() / elapsedSec.toDouble()
            val avgKph = avgMps * 3.6
            _avgSpeedKph.value = avgKph
            _avgSpeedDisplay.value = when (_units.value) {
                1    -> avgKph * 0.621371
                else -> avgKph
            }
            Timber.tag(TAG).v(
                "updateRowingSpeed: derived avgKph=%.2f from totalDist=%d elapsedSec=%d",
                avgKph,
                totalDistM,
                elapsedSec
            )
        }
    }

    fun updateLapRowingSpeed(nowMs: Long = System.currentTimeMillis()) {
        val lapStart = lapStartDistanceMeters
        val lapElapsed = _lapElapsedTimeSec.value

        // If lap hasn't really started, clear and bail
        if (lapElapsed == null || lapElapsed <= 0) {
            _lapAvgSpeedMps.value = null
            _lapAvgSpeedDisplay.value = null
            Timber.tag(TAG).v("updateLapRowingSpeed: lap not started (lapElapsed=%s)", lapElapsed?.toString() ?: "null")
            return
        }

        // 3–5s window on top of total distance → project to lap
        val maxAgeMs = 5_000L
        val minSpanMs = 2_000L
        val windowStart = nowMs - maxAgeMs

        val series = _distanceSeriesM.value
        if (series.isEmpty()) {
            _lapAvgSpeedMps.value = null
            _lapAvgSpeedDisplay.value = null
            Timber.tag(TAG).v("updateLapRowingSpeed: no distance samples")
            return
        }

        val recent = series.filter { it.tMillis >= windowStart && it.tMillis <= nowMs }
        if (recent.size < 2) {
            _lapAvgSpeedMps.value = null
            _lapAvgSpeedDisplay.value = null
            Timber.tag(TAG).v("updateLapRowingSpeed: insufficient samples (recent=%d)", recent.size)
            return
        }

        val newest = recent.last()
        val oldest = recent.firstOrNull { newest.tMillis - it.tMillis >= minSpanMs } ?: recent.first()
        val dtMs = (newest.tMillis - oldest.tMillis).coerceAtLeast(0L)
        if (dtMs < minSpanMs) {
            _lapAvgSpeedMps.value = null
            _lapAvgSpeedDisplay.value = null
            Timber.tag(TAG).v("updateLapRowingSpeed: dtMs too small (%d)", dtMs)
            return
        }

        // project to lap
        val newestLapM = (newest.v - lapStart).coerceAtLeast(0.0)
        val oldestLapM = (oldest.v - lapStart).coerceAtLeast(0.0)
        val dmLap = newestLapM - oldestLapM
        if (dmLap <= 0.0) {
            _lapAvgSpeedMps.value = null
            _lapAvgSpeedDisplay.value = null
            Timber.tag(TAG).v("updateLapRowingSpeed: non-positive dmLap=%.2f", dmLap)
            return
        }

        val dtSec = dtMs / 1000.0
        val lapMps = dmLap / dtSec

        // 3–5s “instant” lap speed
        _lapAvgSpeedMps.value = lapMps

        // display (km/h or mi/h)
        val lapKph = lapMps * 3.6
        _lapAvgSpeedDisplay.value = when (_units.value) {
            1    -> lapKph * 0.621371
            else -> lapKph
        }

        Timber.tag(TAG).v(
            "updateLapRowingSpeed: lapMps=%.2f lapKph=%.2f lapStart=%d lapElapsed=%d",
            lapMps,
            lapKph,
            lapStart,
            lapElapsed
        )

        // keep existing full-lap deriveds (distance / lapElapsed) if present;
        // but if they are null, seed them from current lap totals
        if (_lapRunPaceMinPerUnit.value == null) {
            val lapDistM = _lapDistanceMeters.value
            if (lapDistM != null && lapElapsed > 0) {
                val mpsFull = lapDistM.toDouble() / lapElapsed.toDouble()
                val kmhFull = mpsFull * 3.6
                _lapAvgSpeedMps.value = mpsFull
                _lapAvgSpeedDisplay.value = when (_units.value) {
                    1    -> kmhFull * 0.621371
                    else -> kmhFull
                }
                Timber.tag(TAG).v(
                    "updateLapRowingSpeed: seeded full-lap avg from lapDist=%d lapElapsed=%d → kph=%.2f",
                    lapDistM,
                    lapElapsed,
                    kmhFull
                )
            }
        }
    }

    fun clearAllTimeSeries() {
        Timber.tag(TAG).i("clearAllTimeSeries()")
        // 1) clear series
        _hrSeries.value = emptyList()
        _speedSeriesKph.value = emptyList()
        _cadenceSeriesSpm.value = emptyList()
        _powerSeriesW.value = emptyList()
        _inclineSeriesPercent.value = emptyList()
        _resistanceSeries.value = emptyList()
        _distanceSeriesM.value = emptyList()
        _forceOnBeltSeries.value = emptyList()
        _elevationNetSeries.value = emptyList()

        // 2) reset running/rolling accumulators that feed the public averages
        hrSum = 0.0
        hrCount = 0
        _averageHeartRate.value = null

        speedKphSum = 0.0
        speedCount = 0
        _avgSpeedKph.value = null
        _avgSpeedDisplay.value = null

        cadenceSpmSum = 0.0
        cadenceCount = 0
        _averageCadence.value = null

        powerSum = 0.0
        powerCount = 0
        _averagePower.value = null

        // 3) clear lap projections derived from series
        _lapAvgSpeedMps.value = null
        _lapAvgSpeedDisplay.value = null
    }

    /* ───────────────────────────── HrmStateSink implementation ───────────────────────────── */

    override fun setHrmSensorContact(contact: Boolean?) {
        val old = _sensorContact.value
        _sensorContact.value = contact
        Timber.tag(TAG).d("HRM sensorContact %s → %s", old?.toString() ?: "null", contact?.toString() ?: "null")
    }

    override fun setHrmRrIntervals(rrMs: List<Int>) {
        _rrIntervalsMs.value = rrMs
        Timber.tag(TAG).v("HRM rrIntervals count=%d", rrMs.size)
    }

    override fun setHrmBodySensorLocation(loc: BodyLocation) {
        val old = _bodySensorLocation.value
        _bodySensorLocation.value = loc
        Timber.tag(TAG).d("HRM bodySensorLocation %s → %s", old?.toString() ?: "null", loc)
    }

    override fun hrmRrCount(): Int = _rrIntervalsMs.value.size
}
