package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile

/**
 * ───────────────────────────────────────────────────────────────────────────────
 * FITBRIDGE BLUETOOTH ENGINE – ARCHITECTURE NOTES (btEngine.Docs.kt)
 * ───────────────────────────────────────────────────────────────────────────────
 *
 * This file is a **living architecture overview** for the FitBridge Bluetooth stack on
 * the **client / engine** side. It mirrors the current codebase (BtEngine, BtEngineState
 * and friends) and is intended to be kept in sync with the implementation.
 *
 * Files covered here live under:
 *
 *   com.gmail.absolutebollockscreations.gattlibrary.central
 *
 * and, in the shared IDs file:
 *
 *   com.gmail.absolutebollockscreations.gattlibrary.central.bluetoothIds.kt
 *
 * The same ID file is shared by the **local FTMS/CSC/CPS provider service** (“btService”)
 * so changes must remain wire-compatible for both sides.
 *
 * Major responsibilities:
 *  - Own and orchestrate BLE connections to external FTMS machines and HRM straps.
 *  - Maintain a rich, observable state model for UI, workout logic, and graphs.
 *  - Perform protocol-level work for FTMS (0x1826) and HRM (0x180D).
 *  - Run arbitration logic for **heart rate**, **pace**, and **energy** across sources.
 *  - Integrate (optionally) with a local FitBridge btService via a lightweight sink.
 *
 * The sections below are organized roughly by file.
 *
 *
 * ───────────────────────────────────────────────────────────────────────────────
 * MODULE: BtEngine (btEngine.kt) — Facade & public API
 * ───────────────────────────────────────────────────────────────────────────────
 *
 * PURPOSE
 *  - Thin owner/facade of the Bluetooth stack for this app.
 *  - Holds engine state, Android Bluetooth primitives, and wires a shared GATT router.
 *  - Exposes a clean API for UI/Workout layers:
 *      • Scan / stop scan
 *      • Connect / disconnect FTMS & HRM
 *      • High-level “session” toggles (activeSession / isRunning)
 *      • Unit changes, elapsed time, laps
 *      • Heart-rate & calorie bindings
 *  - Publishes the machine's classification, capabilities and control events so a
 *    **local provider service** (the peripheral bridge) can both mirror them and
 *    advertise a matching virtual device. The engine itself holds no provider reference.
 *
 * OWNED OBJECTS
 *  - state        : [BtEngineState]
 *      Central mutable snapshot. All modules read/write through this.
 *
 *  - btManager    : BluetoothManager?
 *  - adapter      : BluetoothAdapter?
 *      Android Bluetooth primitives. Accessors are guard-wrapped to be API-safe.
 *
 *  - scope        : CoroutineScope
 *      IO + SupervisorJob scope used for internal orchestration (scan watchdogs,
 *      deferred start/stop, CCCD readiness waits, etc.).
 *
 *  - gattRouter   : BtEngineGattCore
 *      Factory for per-connection BluetoothGattCallbacks that fan out to protocol
 *      handlers (FTMS, HRM).
 *
 * INTERNAL GUARDS & INTENT
 *  - startLock / isStarted (volatile):
 *      Single-flight guard for start(address, activity). Makes sure concurrent
 *      callers don’t trigger multiple connect flows.
 *
 *  - desiredAddress: String?
 *  - desiredActivity: ActivityType?
 *      Remember “where we were trying to go” while a connect/scan is in flight.
 *
 * PERMISSION HELPERS
 *  - hasConnectPermission(): Boolean
 *  - hasScanPermission(): Boolean
 *  - guardConnectPerm(op, default, block)
 *  - guardScanPerm(op, block)
 *      Small wrappers that swallow SecurityException and surface user-visible
 *      warnings instead of crashing. All BLE entry points use these.
 *
 * PUBLIC FLOWS (forwarded from [BtEngineState])
 *  - connectionState: StateFlow<Int>
 *  - haveControlFlow: StateFlow<Boolean>
 *  - activeSession : StateFlow<Boolean>
 *  - devices     : StateFlow<Map<String, DiscoveredDevice>>
 *  - hrmDevices  : StateFlow<Map<String, DiscoveredDevice>>
 *
 * DISCOVERY API (delegates to btEngineScan.kt)
 *  - startScan(useFtmsHardwareFilter = true, onDevice: (name, addr, result) -> Unit)
 *      • Permission-guarded (BLUETOOTH_SCAN).
 *      • Supports a hardware-filtered and a software-filtered path.
 *
 *  - stopScan()
 *
 * SESSION / CONNECTION LIFECYCLE
 *  - suspend fun start(
 *        address: String,
 *        activity: ActivityType,
 *        allowScanFallback: Boolean = true
 *    ): Boolean
 *      • Single-flight via startLock/isStarted.
 *      • Attempts a direct FTMS connect.
 *      • Optionally falls back to a short scan-then-connect when direct connect
 *        fails and BLUETOOTH_SCAN is granted.
 *
 *  - fun stop()
 *      • Clears isStarted.
 *      • Sets isRunning=false and activeSession=false.
 *      • Best-effort “stop or pause” on Control Point when we have control.
 *      • Calls release() to disconnect FTMS + HRM and stop scanning.
 *
 *  - fun markSessionStarted(activity, address?)
 *      • Persists intent + address and flips activeSession=true.
 *
 *  - fun markSessionStopped()
 *      • Sets activeSession=false.
 *
 *  - fun setRunning(running: Boolean)
 *      • Hooks for higher-level workout/session orchestration.
 *
 * FTMS / HRM CONNECT & DISCONNECT
 *  - connectFtms(address, activity)
 *  - disconnectFtms()
 *  - connectHrm(address)
 *  - disconnectHrm()
 *
 *    Highlights:
 *      • Stop scan before connecting.
 *      • Reset FTMS control & queue state on each new FTMS link.
 *      • Use BtEngineGattCore.build(...) to install the right protocol handler.
 *      • Permission-aware disconnect: if BLUETOOTH_CONNECT disappears mid-session,
 *        the engine cleans up local state and surfaces an error without calling
 *        platform methods that would now throw.
 *
 *  - release()
 *      • Convenience: stopScan() + disconnectFtms() + disconnectHrm().
 *
 * FTMS CONTROL POINT (delegates to BtEngineControlPoint)
 *  - requestControl(), startOrResume(), stopOrPause(), reset()
 *  - setTargetSpeedKmh(kmh), setTargetInclinationPercent(percent),
 *    setTargetResistance(level), setTargetPowerWatts(watts)
 *
 *    All methods:
 *      • Are @RequiresPermission(BLUETOOTH_CONNECT).
 *      • Check device features/targets when the feature set is known.
 *      • Snap to device min/max/step when Supported-* Range characteristics
 *        have been read.
 *      • Enqueue writes through the shared GATT queue.
 *
 * CAPABILITY READS
 *  - readSupportedSpeedRange()
 *  - readSupportedInclinationRange()
 *  - readSupportedResistanceRange()
 *  - readSupportedPowerRange()
 *
 *    All are permission-guarded and simply push a READ for the appropriate UUID.
 *    Parsing is delegated to BtEngineCapabilities.
 *
 * UNITS / ELAPSED / LAPS
 *  - setElapsedFromWorkout(...)
 *  - doLap()
 *
 *    These update [BtEngineState] and recompute derived display fields and laps
 *    via helpers in btEngine.Utils.kt. (Elapsed is now primarily driven by FTMS;
 *    see BtEngineFtmsParsers + BtEngineState.setElapsedSeconds().)
 *
 * HEART RATE & CALORIE BINDINGS
 *  - bindCalorieMode(...)
 *  - bindWearCalories(...)
 *  - bindHrMax(...), bindRestingHr(...), bindHrZoneMode(...)
 *      Wire up app-level flows to MetricFusion for heart-rate zones and calories.
 *
 *  - setPreferExternalHrm(Boolean)
 *  - setWearHeartRate(bpm: Int?)
 *      Feed MetricFusion and update
 *      timestamps used for freshness.
 *
 * ERROR SURFACES & ROUTING
 *  - fun BtEngine.setGattErrorFor(gatt, msg)
 *      Extension that routes an error string to the correct sink in state:
 *      lastGattError (FTMS) vs lastHrmError (HRM), and performs a best-effort
 *      disconnect/cleanup of the offending GATT.
 *
 * BT → SERVICE BRIDGE
 *  - The engine no longer calls a provider. The peripheral bridge reads what the engine
 *    publishes (inferred activity, capabilities, measurements, control events) from the
 *    public client and drives the local virtual FTMS device from that.
 *
 *
 * ───────────────────────────────────────────────────────────────────────────────
 * MODULE: BtEngineState (btEngineState.kt) — central state & data model
 * ───────────────────────────────────────────────────────────────────────────────
 *
 * PURPOSE
 *  - Single mutable snapshot for all Bluetooth engine state.
 *  - Owned by BtEngine; all modules read/write through this instance.
 *  - Exposes StateFlow-based outputs that UI/ViewModel layers can observe.
 *  - Implements HR-related sinks for HRM and arbitration modules.
 *
 * NESTED TYPES
 *  - DiscoveredDevice
 *      • name: String
 *      • address: String
 *      • rssi: Int
 *      • lastSeenMs: Long
 *      • services: Set<ServiceKind> (FTMS / HRM / CSC / CPS / RSC / GLUCOSE)
 *      • machineFeature: Int? (FTMS Fitness Machine Feature bitmap when known)
 *
 *  - HrSource
 *      Canonical HR source (FTMS, external HRM, Wear, UNKNOWN).
 *
 *  - BodyLocation
 *      Sensor placement (CHEST, WRIST, etc.).
 *
 * DISCOVERY / SCANNING STATE
 *  - devices: StateFlow<Map<String, DiscoveredDevice>>   // FTMS-capable
 *  - hrmDevices: StateFlow<Map<String, DiscoveredDevice>>// HRM-capable
 *  - isScanning: StateFlow<Boolean>
 *
 * GATT REFERENCES
 *  - gatt: BluetoothGatt?                    // FTMS link
 *  - gattHrm: BluetoothGatt?                // HRM link
 *  - connectionState: StateFlow<Int>        // BluetoothProfile.STATE_*
 *  - hrmConnectionState: StateFlow<Int>
 *  - lastGattError / lastHrmError: StateFlow<String?>
 *
 *  - FTMS characteristics:
 *      • controlPointChar: BluetoothGattCharacteristic?
 *      • treadmill/elliptical/stepper/climber/rower/bike data chars.
 *
 *  - HRM characteristics:
 *      • hrmMeasurementChar / hrmCtrlPointChar / hrmBodyLocChar
 *
 * FTMS CONTROL & STATUS
 *  - haveControlFlow: StateFlow<Boolean>
 *  - cccdReady: StateFlow<Boolean>           // current machine-context projection
 *  - fitnessMachineStatus: StateFlow<Byte>   // last STATUS_* code from 0x2ADA
 *
 *  - Target snapshot (what we last requested via CP):
 *      • targetSpeedKph, targetInclinePercent, targetResistance, targetPowerWatts
 *
 *  - Mirroring hooks for btService:
 *      • Methods like setTargetsFromService(...) allow the local provider to
 *        push state into the engine when it is the **source** of FTMS data.
 *
 * GATT OP QUEUE (see btEngine.GattQueue.kt)
 *  - Each ConnectionContext owns its GattQueue and CccdState.
 *  - No engine-global queue or untargeted GATT fallback remains.
 *
 * CAPABILITIES / SUPPORTED RANGES
 *  - featuresRead: Boolean
 *  - supportsSpeed / supportsInclination / supportsResistance / supportsPower
 *  - speedMin/Max/Incr (+ speedRangeKnown)
 *  - inclineMin/Max/Incr (+ inclineRangeKnown)
 *  - resistanceMin/Max/Incr (+ resistanceRangeKnown)
 *  - powerMin/Max/Incr (+ powerRangeKnown)
 *
 *  (Ranges are populated by BtEngineCapabilities.)
 *
 * UNITS & SESSION
 *  - units: StateFlow<Int?>                  // metric / imperial
 *  - activeSession: StateFlow<Boolean>
 *  - isRunning: StateFlow<Boolean?>
 *
 *  - elapsedTimeSec: StateFlow<Int?>
 *      • FTMS elapsed is now treated as the **authoritative** session elapsed
 *        and is set via setElapsedSeconds() from FtmsParsers.
 *
 * METRIC STREAMS (observable)
 *  - Speed: speedKph, speedMph, avgSpeedKph, peakSpeedKph, peakSpeedMph
 *  - Pace:  instantPaceMinPerKm, averagePaceMinPerKm,
 *           lapPaceMinPerKm, lapRowPaceMinPer500m
 *  - Distance: totalDistanceMeters
 *  - Power: instantPower, averagePower, peakPower
 *  - Cadence / strokes / steps: strokeRate, peakStrokeRate, strokeCount,
 *           instantCadence, averageCadence, totalStrides, totalFloors,
 *           totalSteps, lapCadenceSpm, lapStrokeCount, lapSteps
 *  - Grade / elevation: inclination, rampAngle, elevationGainPos, elevationGainNeg
 *  - Resistance: resistance
 *  - Energy / MET: expEnergyTotal, metabolicEquivalent
 *  - Belt: forceOnBelt
 *
 * LAP STATE / DERIVED
 *  - Anchors:
 *      • lapStartDistanceMeters
 *      • lapStartElapsedSec
 *      • lapStartStrokeCount
 *      • lapStartSteps
 *
 *  - Public lap flows:
 *      • lapCount
 *      • lapElapsedTimeSec
 *      • lapDistanceMeters
 *      • lapAvgSpeedMps
 *      • lapPaceMinPerKm, lapRowPaceMinPer500m
 *      • lapStrokeCount, lapSteps, lapCadenceSpm
 *
 *  - Internal accumulators:
 *      • lapAvgCadenceTotal, lapAvgCadenceCount
 *
 * HEART RATE & ZONES
 *  - heartRate: StateFlow<Int?>
 *  - heartRateSource: StateFlow<HrSource>
 *  - heartRateZone: StateFlow<Int?> (0..5)
 *  - sensorContact: StateFlow<Boolean?>
 *  - energyExpended: StateFlow<Int?>
 *  - rrIntervalsMs: StateFlow<List<Int>>
 *  - bodySensorLocation: StateFlow<BodyLocation?>
 *
 *  - Inputs for arbitration:
 *      • externalHeartRate / ftmsHeartRate / wearHeartRate (optional)
 *      • lastExternalAt / lastFtmsAt / lastWearAt
 *      • preferExternal: Boolean
 *
 *  - App bindings:
 *      • hrMaxFromApp: StateFlow<Int?>?
 *      • restingHrFromApp: StateFlow<Int?>?
 *      • hrZoneModeFromApp: StateFlow<HrZoneMode>?
 *
 *  - Calorie bindings:
 *      • calorieModeFromApp: StateFlow<CalorieMode>?
 *      • wearActiveCalories / wearTotalCalories: StateFlow<Int?>?
 *
 * TIME-SERIES / GRAPH SUPPORT
 *  - Sliding-window series for graphs and averages:
 *      • hrSeries
 *      • speedSeries
 *      • cadenceSeries
 *      • powerSeries
 *      • inclineSeries
 *      • resistanceSeries
 *      • distanceSeries (used for rowing speed and distance-derived pace)
 *
 *  - Each append*Sample(...) call:
 *      • Maintains a fixed-duration window (seriesWindowMinutes).
 *      • Updates rolling averages (avgSpeedKph, etc.).
 *      • Emits source-tagged samples to MetricFusion where relevant.
 *
 * ROWING-SPECIFIC DERIVED SPEED
 *  - appendDistanceMetersSample(...) + updateRowingSpeed()
 *      • When rowers don’t expose explicit speed, we derive it from distance/time
 *        using a small sliding window. A lap-scoped view is maintained via
 *        updateLapRowingSpeed() as well.
 *
 * HRM STATE SINK
 *  - Implements HrmStateSink so handlers can write:
 *      • setHrmSensorContact(contact: Boolean?)
 *      • setHrmEnergyExpended(kcal: Int)
 *      • setHrmRrIntervals(rrMs: List<Int>)
 *      • setHrmBodySensorLocation(loc: BodyLocation)
 *
 * THREADING
 *  - Exposes StateFlow for thread-safe observation.
 *  - GATT queue guarded by `lock`; other fields are simple atomic/setter-based.
 *
 *
 * ───────────────────────────────────────────────────────────────────────────────
 * MODULE: BluetoothIds (bluetoothIds.kt); server-owned BtServiceProfiles lives in peripheral (LIB-19h); FTMS vocabulary lives in gatt-core core.ftms
 * ───────────────────────────────────────────────────────────────────────────────
 *
 * PURPOSE
 *  - Single source of truth for:
 *      • All Bluetooth SIG Assigned Number UUIDs used by FitBridge.
 *      • CSC / CPS / RSC / HRM / Glucose services and key characteristics.
 *      • Shared service-side profiles and advertised device names.
 *      • (Byte helpers moved to gatt-core `core.bytes` in LIB-18f.)
 *
 *  - This file is **shared** by:
 *      • BtEngine (client/consumer of FTMS/HRM/CSC/CPS).
 *      • btService (local provider that emulates FTMS/CSC/CPS/HRM).
 *
 * CONTENTS
 *  - object BluetoothIds
 *      • FTMS identifiers and vocabulary are NOT here any more (LIB-19a): see gatt-core
 *        `core.ftms` (FtmsUuids, FtmsSpec, FtmsDataFlags, FtmsMachineProfile, FtmsMachineType).
 *        FTMS_SERVICE_PARCEL (Android ParcelUuid for scan filters) stays here.
 *
 *      • HRM (0x180D), CSC (0x1816), RSC (0x1814), CPS (0x1818), Glucose (0x1808)
 *        service UUIDs and core characteristics (measurement, feature, control point).
 *
 *      • UUID_CCCD (0x2902) – Client Characteristic Configuration Descriptor.
 *
 *  - core.ftms.FtmsDataFlags (was `Flags`)
 *      • Flag bitmasks for FTMS data characteristics:
 *          TREAD_* (Treadmill), ELL_* (Elliptical), ROW_* (Rower),
 *          BIKE_* (Indoor Bike), STEP_* (Stepper), CLIMB_* (Climber).
 *
 *  - object BtServiceProfiles: moved to `peripheral.BtServiceProfiles` (LIB-19h): ServiceMode, CscCpsProfile,
 *    advertised device names, FTMS Service Data builder, MAX_CLIENTS. Dead FMF/TSF presets and the
 *    activity/UUID glue helpers were deleted.
 *
 *  - Byte helpers:
 *      • Byte helpers: see gatt-core `core.bytes` (LittleEndian, BigEndian, ByteCursor).
 *        used by both engine and btService for payload framing.
 *
 * NOTES
 *  - No literals for UUIDs/opcodes/result codes should be re-declared elsewhere.
 *  - Adding support for a new Bluetooth profile should start here.
 *
 *
 * ───────────────────────────────────────────────────────────────────────────────
 * MODULE: BtEngineCapabilities (btEngineCapabilities.kt) — FTMS features & ranges
 * ───────────────────────────────────────────────────────────────────────────────
 *
 * PURPOSE
 *  - Parse:
 *      • Fitness Machine Feature (0x2ACC).
 *      • Supported-Speed/Inclination/Resistance/Power Range characteristics.
 *  - Populate capability booleans and min/max/step ranges in [BtEngineState].
 *  - Trigger range reads opportunistically when support bits are present.
 *
 * ENTRY POINTS
 *  - parseFitnessMachineFeatures(engine, value: ByteArray)
 *      • Reads control/target bits from the FMF bitmap.
 *      • Sets supportsSpeed / supportsInclination / supportsResistance / supportsPower.
 *      • Marks featuresRead = true.
 *      • When BLUETOOTH_CONNECT is granted, pushes READs for Supported-* Range
 *        characteristics corresponding to supported targets.
 *
 *  - parseSupportedSpeedRange(engine, value)
 *  - parseSupportedInclinationRange(engine, value)
 *  - parseSupportedResistanceRange(engine, value)
 *  - parseSupportedPowerRange(engine, value)
 *      • Each expects a 6-byte [min,max,step] triple with units as per BluetoothIds.
 *      • Updates corresponding min/max/incr fields and sets *RangeKnown=true.
 *
 * PERMISSIONS
 *  - Range reads are gated by a runtime BLUETOOTH_CONNECT check; if missing,
 *    the module logs and skips but never crashes.
 *
 *
 * ───────────────────────────────────────────────────────────────────────────────
 * MODULE: State Extensions (btEngine.StateExtensions.kt)
 * ───────────────────────────────────────────────────────────────────────────────
 *
 * PURPOSE
 *  - Keep common state transitions for [BtEngineState] in one place:
 *      • FTMS control reset.
 *      • Lap anchor priming.
 *
 * PUBLIC EXTENSIONS
 *  - fun BtEngineState.resetFtmsControl()
 *      • Clears controlPointChar, haveControl/haveControlFlow, CCCD flags and
 *        any pending write counters.
 *
 *  - fun BtEngineState.primeLapFromCurrentTotals()
 *      • Copies current totals into lap anchor fields using null-safe defaults
 *        (distance, elapsed, strokeCount, steps).
 *
 *
 * ───────────────────────────────────────────────────────────────────────────────
 * MODULE: BtEngineControlPoint (btEngine.ControlPoint.kt)
 * ───────────────────────────────────────────────────────────────────────────────
 *
 * PURPOSE
 *  - Stateless façade for building and enqueueing FTMS Control Point payloads.
 *  - Ensures we only send commands when:
 *      • We hold control.
 *      • The device advertises support for that target (when featuresRead=true).
 *  - Snaps incoming values to device min/max/step when Supported-* Ranges are
 *    known.
 *
 * PUBLIC HELPERS (all @RequiresPermission(BLUETOOTH_CONNECT))
 *  - requestControl(engine)
 *  - startOrResume(engine)
 *  - stopOrPause(engine)
 *  - reset(engine)
 *  - setTargetSpeedKmh(engine, kmh: Float)
 *  - setTargetInclinationPercent(engine, percent: Float)
 *  - setTargetResistance(engine, level: Float)
 *  - setTargetPowerWatts(engine, watts: Int)
 *
 * INTERNALS
 *  - cpWriteOrIgnore(engine, payload)
 *      • Ensures haveControlFlow.value==true before enqueuing.
 *
 *  - precheck(engine, feature, supported)
 *      • Logs and denies when control is missing or feature is not supported.
 *
 *  - snapToStep(value, min, max, step)
 *      • Step-aware clamp with rounding.
 *
 *
 * ───────────────────────────────────────────────────────────────────────────────
 * MODULE: BtEngineGattCore (btEngine.GattCore.kt) — shared BluetoothGattCallback
 * ───────────────────────────────────────────────────────────────────────────────
 *
 * PURPOSE
 *  - Provide **one** BluetoothGattCallback per connection that:
 *      • Manages connection priority and MTU.
 *      • Discovers services.
 *      • Activates protocol handlers based on present services.
 *      • Routes characteristic/descriptor events to active handlers.
 *      • Advances the serialized GATT queue after each operation.
 *      • Surfaces errors to BtEngineState.
 *
 * KEY TYPE: ProtocolHandler
 *  - primaryServices: Set<UUID>
 *  - extraCharacteristics: Set<UUID>
 *  - onConnected(engine, gatt)
 *  - onDisconnected(engine)
 *  - onServicesDiscovered(engine, gatt, services): Boolean
 *  - onCharacteristicChanged(engine, gatt, ch, value)
 *  - onCharacteristicRead(engine, gatt, ch, value, status)
 *  - onCharacteristicWrite(engine, gatt, ch, status)
 *  - onDescriptorWrite(engine, gatt, desc, status)
 *
 * CALLBACK LIFECYCLE
 *  - onConnectionStateChange(CONNECTED):
 *      • requestConnectionPriority(HIGH).
 *      • requestMtu(512).
 *      • Start discoverServices() after onMtuChanged, or after a timeout fallback.
 *
 *  - onServicesDiscovered:
 *      • Activates handlers whose primaryServices intersect the discovered set.
 *      • Each handler may enable notifications and push initial reads.
 *
 *  - onCharacteristic / onDescriptorWrite:
*      • Route event to owner handler(s).
*      • Call engine.handleStack() to advance the queue.
*      • On error → state.setGattErrorFor(gatt, "...") + handleStack().
*
*  - onConnectionStateChange(DISCONNECTED):
*      • Notifies handlers, clears active set.
*      • Resets FTMS control + queue under state.lock.
*
*
* ───────────────────────────────────────────────────────────────────────────────
* MODULE: FtmsProtocolHandler (btEngine.GattFtms.kt) — FTMS profile plug-in
* ───────────────────────────────────────────────────────────────────────────────
*
* PURPOSE
*  - Implement the Bluetooth SIG Fitness Machine Service protocol on top of
*    BtEngineGattCore and BtEngine’s GATT queue.
*
* ROUTING
*  - primaryServices = { UUID_FTMS_SERVICE }
*  - extraCharacteristics includes:
*      • UUID_FITNESS_MACHINE_CONTROL_POINT
*      • UUID_FITNESS_MACHINE_FEATURE
*      • UUID_SUPPORTED_*_RANGE
*      • All FTMS data characteristics (treadmill/elliptical/stepper/climber/
*        rower/bike).
*      • UUID_FITNESS_MACHINE_STATUS
*
* CONNECTION SETUP
*  - onServicesDiscovered:
*      • Verifies FTMS service presence.
*      • Enables notifications/indications for:
*          - All relevant data characteristics for the current ActivityType.
*          - Fitness Machine Control Point (CP).
*          - Fitness Machine Status (when present).
*      • Pushes a READ of Fitness Machine Feature.
*
* NOTIFICATIONS
*  - Data characteristics:
*      • Delegated directly to BtEngineFtmsParsers.do* (treadmill, bike, rower, …).
*
*  - Control Point:
*      • Parses 0x80 Response Code frames.
*      • Applies results:
*          - On REQUEST_CONTROL success → haveControlFlow=true.
*          - On RESET success         → clear local control flags.
*          - On CONTROL_NOT_PERMITTED → clear control locally + surface error.
*          - Other failures           → state.setGattErrorFor(gatt, "...").
*
*  - Fitness Machine Status:
*      • Decodes STATUS_* values and writes them into state.fitnessMachineStatus.
*      • Triggers local reactions for events such as:
*          - MACHINE_RESET
*          - CONTROL_PERMISSION_LOST
*          - USER_STARTED/STOPPED
*      • Each status is also published as a ControlEvent for the peripheral bridge.
*
* READS
*  - onCharacteristicRead:
*      • UUID_FITNESS_MACHINE_FEATURE       → BtEngineCapabilities.parseFitnessMachineFeatures
*      • UUID_SUPPORTED_*_RANGE             → corresponding parseSupported*Range
*
* ERROR SURFACING
*  - Any protocol-level inconsistency (bad frame, unknown opcode, etc.) is logged
*    and, when appropriate, written into lastGattError via state.setGattErrorFor.
*
*
* ───────────────────────────────────────────────────────────────────────────────
* MODULE: BtEngineFtmsParsers (btEngine.GattFtmsParsers.kt) — FTMS data parsing
* ───────────────────────────────────────────────────────────────────────────────
*
* PURPOSE
*  - Decode FTMS data characteristics into app metrics and update [BtEngineState].
*  - Feed **time-series**, **lap math**, and **arbitration** modules.
*
* SUPPORTED CHARACTERISTICS
*  - 0x2ACD Treadmill Data
*  - 0x2ACE Elliptical Data
*  - 0x2ACF Stair Stepper Data
*  - 0x2AD0 Stair Climber Data
*  - 0x2AD1 Rowing Machine Data
*  - 0x2AD2 Indoor Bike Data
*
* KEY BEHAVIOR
*  - Flags:
*      • Each frame begins with a flags bitmap (u16 or 24-bit) that controls which
*        fields are present. Parsers advance an offset as they consume fields.
*
*  - Speed:
*      • Instant & average speed arrive as 0.01 km/h (u16).
*      • appendSpeedKphSample(kph) updates speed series and running averages.
*      • Derived mph, display speed, and peak speed are updated.
*      • MetricFusion derives lower-quality pace candidates from speed for
*        pace-from-speed fallback.
*
*  - Distance:
*      • Total distance encoded as u24 meters.
*      • Updates totalDistanceMeters / totalDistanceDisplay.
*      • appendDistanceMetersSample(...) keeps a distance series for:
*          - rowing speed derivation, and
*          - MetricFusion distance-derived pace.
*      • Lap metrics updated via updateLapDistance(...).
*
*  - Pace:
*      • Some FTMS char variants encode pace as seconds per km (optionally ×10).
*      • Native pace is **not** written directly to state; instead:
*          MetricFusion receives a native instant-pace sample.
*        is called and state receives the canonical pace through arbitration.
*
*  - Power:
*      • Instant & average power arrive in watts (s16).
*      • Internal smoothing is applied for instantPower.
*      • Peaks tracked via peakPower.
*
*  - Cadence / strokes / steps:
*      • Multiple encodings supported across bike/elliptical/stepper/climber/
*        rower. Lap cadence averages are updated via feedLapCadenceAvg(...).
*
*  - Elevation / incline:
*      • Handles legacy and v2 encodings for incline & ramp.
*
*  - Energy:
*      • FTMS energy fields are not written directly to expEnergyTotal.
*        Instead, a source-tagged energy sample is emitted so that MetricFusion
*        calories from HRM/Wear can override when appropriate.
*
*  - Elapsed time:
*      • FTMS elapsed seconds are now treated as **authoritative**:
*          - engine.state.setElapsedSeconds(ftmsElapsedSec)
*          - Lap elapsed is derived from this authoritative clock.
*          - Lap strokes are kept in sync with elapsed.
*
* ARBITRATION INTEGRATION
*  - Heart-rate:
*      • FTMS HR fields are routed through MetricFusion.
*
*  - Pace:
*      • Native pace and speed are emitted as source-tagged samples.
*      • Distance   → BtEngineState.appendDistanceMetersSample(...), which
*        MetricFusion reads for distance-derived pace.
*
*  - Energy:
*      • Machine kcal → MetricFusion cumulative energy sample.
*
* IMPLEMENTATION NOTES
*  - Uses small byte helpers (has/u8/u16/s16/skip) and logSet(...) to reduce
*    boilerplate and only log on actual changes.
*  - Any value that should appear in graphs or averages flows through the
*    append*Sample(...) family on BtEngineState.
*
*
* ───────────────────────────────────────────────────────────────────────────────
* MODULE: HrmProtocolHandler (btEngine.GattHrm.kt) — Heart Rate Service
* ───────────────────────────────────────────────────────────────────────────────
*
* PURPOSE
*  - Handle the Bluetooth SIG Heart Rate Service (0x180D) on top of the shared
*    GATT callback.
*  - Parse HRM frames, emit BPM to MetricFusion as an **external HRM**
*    source, and optionally enrich BtEngineState with HRV and sensor metadata.
*
* CONNECTION SETUP
*  - primaryServices = { UUID_HRM_SERVICE }
*  - extraCharacteristics = { HEART_RATE_MEASUREMENT, BODY_SENSOR_LOCATION,
    *                             HEART_RATE_CONTROL_POINT }
*  - onServicesDiscovered:
*      • Enable notifications on HEART_RATE_MEASUREMENT.
*      • Best-effort read BODY_SENSOR_LOCATION.
*
* FRAME PARSING
*  - Reads flags to determine:
*      • Value size (u8 or u16).
*      • Sensor contact info.
*      • Presence of Energy Expended.
*      • Presence of RR-intervals.
*
*  - BPM:
*      • Extracted as Int and emitted as a source-tagged heart-rate sample.
*
*  - Optional fields (when BtEngineState implements HrmStateSink):
*      • Sensor contact → setHrmSensorContact(...)
*      • Energy expended → setHrmEnergyExpended(...)
*      • RR-intervals (converted to ms) → setHrmRrIntervals(...)
*      • BodySensorLocation → setHrmBodySensorLocation(...)
*
*
* ───────────────────────────────────────────────────────────────────────────────
* MODULE: GATT Queue & CCCD Helpers (btEngine.GattQueue.kt)
* ───────────────────────────────────────────────────────────────────────────────
*
* PURPOSE
*  - Serialize GATT operations per connection so each link may have one in flight.
*  - Hide API-level differences (API 33+ vs pre-33) for characteristic/descriptor
*    writes.
*  - Provide a reusable helper for enabling notifications/indications (CCCD).
*
* PUBLIC API ON BtEngine (all @RequiresPermission(BLUETOOTH_CONNECT))
*  - pushRead(context, uuid)
*  - pushWrite(context, bytes, uuid)
*  - pushDescriptorWrite(context, descriptor, payload)
*  - enableNotifications(context, characteristic)
*
* INTERNAL DRIVER
*  - handleStack(context)
*      • Dispatches that context's head operation if none is in flight.
*      • Uses only the BluetoothGatt owned by the supplied context.
*      • On immediate failure or missing service/char, calls failAndContinue(...).
*      • Callback completion must match GATT identity, operation, and UUID.
*
*  - queueSucceed(context, callback identity...) / queueFail(...)
*      • Update lastGattError/lastHrmError appropriately.
*      • Pop only a matching queue head and schedule that context's next operation.
*
*
* ───────────────────────────────────────────────────────────────────────────────
* MODULE: HR / PACE / ENERGY ARBITRATION (btEngine.GattArbitration.kt)
* ───────────────────────────────────────────────────────────────────────────────
*
* ROLE
*  - Central arbitration for metrics that have **multiple producers**:
*      • Heart rate  → FTMS vs external HRM vs Wear.
*      • Pace        → native FTMS pace vs speed-derived vs distance-derived.
*      • Energy      → HRM-derived vs Wear vs FTMS native.
*  - Runs as pure singleton objects (`object`) with small internal caches.
*  - Only reads/writes BtEngineState; no direct Bluetooth API access.
*
* HEART-RATE SELECTION (MetricFusion)
*  - Sources:
*      • FTMS-native HR (from FtmsParsers).
*      • External HRM (from HrmProtocolHandler).
*      • Wear OS HR stream (optional).
*
*  - Steps:
*      1) Smooth external samples with an EMA + jump clamp.
*      2) Maintain freshness timestamps for each source (stale after a few sec).
*      3) Choose winner honoring preferExternal + freshness.
*      4) Publish:
*          - heartRate
*          - heartRateSource (HrSource.FTMS / EXTERNAL_HRM / WEAR_OS / UNKNOWN)
*      5) Compute heart-rate zone using:
*          - HRR (Heart Rate Reserve) when mode indicates HRR and both max &
*            resting HR are known.
*          - Otherwise, %HRmax.
*
*  - Public entry points:
*      • setExternalHeartRate(engine, bpm)
*      • setFtmsHeartRate(engine, bpm)
*      • setWearHeartRate(engine, bpm)      // indirectly via BtEngine bindings
*      • reselect(engine)
*      • recalcZone(engine)
*
* PACE SELECTION (MetricFusion)
*  - Sources (all expressed as seconds per km internally):
*      1) Native FTMS pace (treadmill/rower).
*      2) Pace derived from instantaneous speed (m/s).
*      3) Pace derived from distance/time over ~10s windows.
*
*  - Features:
*      • Freshness:
*          - Any source older than ~3s is considered stale.
*          - Distance-derived pace also requires a minimum lookback span.
*      • Priority:
*          1. Native FTMS pace.
*          2. Speed-derived pace.
*          3. Distance-derived pace.
*      • State integration:
*          - Writes canonical pace to BtEngineState.setInstantPace(minutesPerKm).
*          - Display formatting (min/km vs min/mi) happens inside BtEngineState.
*
*  - Public entry points:
*      • setNativePace(engine, paceSecPerKm)
*      • setSpeedMetersPerSecond(engine, speedMps)
*      • setDistanceSnapshot(engine, distanceMeters, atMs)
*
* ENERGY SELECTION (MetricFusion)
*  - Sources:
*      • HRM-derived energy (highest priority).
*      • Wear OS / Health Services energy (medium).
*      • FTMS total energy (lowest).
*
*  - Design:
*      • No time staleness; winner remains until its source becomes null.
*      • Simple priority chain: HRM → Wear → FTMS.
*      • Writes chosen kcal to BtEngineState.setExpEnergyTotal(Int?).
*
*  - Public entry points:
*      • setHrmEnergy(engine, energyKcal: Double?)
*      • setWearEnergy(engine, energyKcal: Double?)
*      • setFtmsEnergy(engine, energyKcal: Double?)
*
*
* ───────────────────────────────────────────────────────────────────────────────
* MODULE: Scanning & Discovery (btEngineScan.kt)
* ───────────────────────────────────────────────────────────────────────────────
*
* PURPOSE
*  - Manage BLE scans for FTMS machines and HRM straps, including:
*      • HW-filtered scans by service UUID (FTMS/HRM/CSC/CPS/RSC/GLUCOSE).
*      • Fallback software filtering when advertisements omit service UUIDs.
*      • Discovery map maintenance with heuristics for names, RSSI, and
*        temporary keys.
*
* API
*  - BtEngine.startScanImpl(useFtmsHardwareFilter = true, onDevice: (name, addr, result) -> Unit)
*      • Starts a low-latency scan with hardware filters when possible.
*      • Schedules a 5s watchdog; if no devices are found, falls back to
*        software filtering.
*      • For each matching result:
*          - Derives a stable key (address or temp).
*          - Updates devices/hrmDevices with DiscoveredDevice entries, including
*            classified service kinds and any known FTMS FMF bits.
*
*  - BtEngine.stopScanImpl()
*      • Stops scan and clears the per-engine ScanCallback.
*
*
* ───────────────────────────────────────────────────────────────────────────────
* MODULE: Utilities, Units, Lap Math (btEngine.Utils.kt)
* ───────────────────────────────────────────────────────────────────────────────
*
* PURPOSE
*  - Small, stateless helpers used across the engine:
*      • Permission checks (BLUETOOTH_CONNECT).
*      • Hex formatting for debug.
*      • Unit conversion (metric/imperial).
*      • Lap math derived metrics.
*      • Convenience helpers like isBluetoothReady().
*
* KEY FUNCTIONS
*  - hasConnectPermission(): Boolean
*  - nowMs(): Long
*  - ByteArray.toHex(): String
*
*  (Unit conversion and lap math left this file: central is canonical, the UI converts,
*   and MetricFusion derives lap values.)
*
*
* ───────────────────────────────────────────────────────────────────────────────
* MAINTENANCE NOTES
* ───────────────────────────────────────────────────────────────────────────────
*
*  - When adding a new Bluetooth profile or FTMS field:
*      • Declare UUIDs/flags/opcodes/scales in bluetoothIds.kt.
*      • Wire protocol handling in the appropriate handler (FTMS/HRM/CSC/CPS).
*      • Expose new state in BtEngineState as flows or properties.
*      • Update BtEngine.Docs.kt (this file) with:
*          - A short “MODULE:” section, or
*          - An update to an existing section.
*
*  - This file is documentation only; it should compile cleanly (no declarations
*    required) and must not participate in runtime behavior.
*/
