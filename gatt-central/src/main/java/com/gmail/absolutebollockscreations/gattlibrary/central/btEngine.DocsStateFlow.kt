package com.gmail.absolutebollockscreations.gattlibrary.central

/**
 * ───────────────────────────────────────────────────────────────────────────────
 * MODULE DOC: BtEngineState — StateFlow inventory & data-plumbing overview
 * FILE: btEngine.DocsStateFlow.kt (documentation only)
 * ───────────────────────────────────────────────────────────────────────────────
 *
 * ROLE IN THE SYSTEM
 *  - `BtEngineState` is the **central source of truth** for the Bluetooth layer:
 *      • It is the in-memory repository for all live metrics coming from FTMS,
 *        HRM and related profiles.
 *      • It backs all **interactive UI** surfaces (tiles, graphs, lap stats).
 *      • It feeds the **local provider service (btService)**, which echoes a
 *        virtual FTMS/CSC/CPS device to downstream clients using this data.
 *
 *  - No UI, workout logic, or btService code should talk directly to GATT or
 *    parse bytes; they observe / bind to the StateFlow surfaces on
 *    `BtEngineState` and let the collectors fill them.
 *
 *  - Mutation is done only through the helpers on `BtEngineState`:
 *      • Simple setters (setXxx) for scalar values and targets.
 *      • Append helpers (append*Sample) for time-series and rolling averages.
 *      • Lap helpers and reset helpers for session/lap boundaries.
 *
 * DATA COLLECTORS (WRITERS / SOURCES)
 *  - **Scanning / discovery**
 *      • `btEngineScan.startScanImpl()` updates:
 *          - `devices`      (FTMS / CSC / CPS / RSC / Glucose candidates)
 *          - `hrmDevices`   (HRM candidates)
 *          - `isScanning`
 *      • Each discovered device is represented as a `DiscoveredDevice` with:
 *          name, address, RSSI, lastSeenMs, raw `ScanResult`, service type set,
 *          and (after connect) an optional `machineFeature` hint.
 *
 *  - **Connection lifecycle & control**
 *      • `BtEngine.connectFtms()/disconnectFtms()` and the shared `GattCore`
 *        set:
 *          - `gatt`, `connectionState`, `lastGattError`
 *          - `controlPointChar`, `haveControlFlow`, `cccdReady`
 *      • `BtEngine.connectHrm()/disconnectHrm()` similarly manage:
 *          - `gattHrm`, `hrmConnectionState`, `lastHrmError`
 *
 *  - **FTMS capabilities & supported ranges**
 *      • `BtEngineCapabilities.parseFitnessMachineFeatures(...)` writes:
 *          - `featuresRead`
 *          - `supportsSpeed / Inclination / Resistance / Power`
 *      • `parseSupported*Range(...)` populates:
 *          - `*Min / *Max / *Incr` and `*RangeKnown` for speed, incline,
 *            resistance, and power.
 *      • These fields drive **Control Point snapping** and **btService range
 *        synthesis** (via `BtServiceSupportedRangesAdapter`).
 *
 *  - **Fitness Machine Status & targets (0x2ADA)**
 *      • `FtmsProtocolHandler` decodes Fitness Machine Status indications and
 *        Control Point responses and updates:
 *          - `lastFtmsStatusCode`   (last raw status opcode)
 *          - `safetyKeyEngaged`     (emergency-stop / safety key state)
 *          - target set-points:
 *              · `targetSpeedKmh`
 *              · `targetInclinePercent`
 *              · `targetResistanceLevel`
 *              · `targetPowerWatts`
 *              · `targetHeartRateBpm`
 *      • These are the authoritative “what we’ve asked the machine to do”
 *        values, and are mirrored into btService when acting as a local FTMS.
 *
 *  - **Session / units / authoritative elapsed time**
 *      • The engine and parsers keep session context in:
 *          - `activeSession`
 *          - `isRunning`
 *          - `elapsedTimeSec`
 *      • Display units are not engine state; the UI converts canonical values.
 *      • `BtEngine.markSessionStarted()/markSessionStopped()` and
 *        `BtEngineState.setActiveSession(...)`:
 *          - Clear averages and time-series.
 *          - Reset FTMS control/status/targets for a clean session.
 *      • **Elapsed time is now driven by FTMS**: FTMS parsers call
 *        `setElapsedSeconds(...)` with the FTMS clock, and lap math uses this
 *        authoritative timer rather than a local Workout ticker.
 *
 *  - **FTMS data parsers → metrics & series**
 *      • `BtEngineFtmsParsers` implementations (treadmill, bike, rower, etc.)
 *        decode FTMS characteristics and feed:
 *          - Core metrics:
 *              · Distance: `setTotalDistanceMeters(...)`
 *              · Speed:    `appendSpeedKphSample(...)`, peak/avg from series
 *              · Pace:     via `MetricFusion` (canonical min/km)
 *              · Power:    `appendPowerSample(...)` and smoothed averages
 *              · Cadence:  `appendCadenceSpmSample(...)`
 *              · Steps/strides/floors/strokes: set* totals and stroke metrics
 *              · Incline/ramp/elevation/force: `appendInclinePercentSample(...)`,
 *                `setRampAngle`, `setElevationGainPos/Neg`, `setForceOnBelt`
 *          - Energy:
 *              · Machine calories are passed into `MetricFusion`, which
 *                decides whether FTMS, HRM or Wear energy becomes
 *                `expEnergyTotal`.
 *          - Heart rate:
 *              · FTMS-native HR (when present) goes into `MetricFusion` as
 *                an additional HR source.
 *          - Elapsed:
 *              · FTMS elapsed seconds → `setElapsedSeconds(sec)`.
 *          - Distance series:
 *              · Distance updates call `appendDistanceMetersSample(...)` to
 *                maintain `distanceSeriesM` for **rower-speed** and distance-
 *                derived pace.
 *
 *  - **HRM data collector (external chest/arm straps)**
 *      • `HrmProtocolHandler` handles the Heart Rate Service (0x180D):
 *          - Decodes BPM, sensor contact, energy expended, RR intervals, and
 *            body sensor location.
 *          - BPM is emitted to `MetricFusion` as an external-HRM sample.
 *          - Metadata is applied via `HrmStateSink`, implemented by
 *            `BtEngineState`:
 *              · `sensorContact`
 *              · `energyExpended`
 *              · `rrIntervalsMs`
 *              · `bodySensorLocation`
 *      • `BtEngineState.appendHeartRateSample(...)` is used by arbitration to
 *        keep a time-series and running average for `averageHeartRate`.
 *
 *  - **Arbitration modules (HR / pace / energy)**
 *      • Heart-rate fusion:
 *          - Inputs:
 *              · FTMS HR
 *              · External HRM (`externalHeartRate` + `lastExternalAt`)
 *              · Wear HR (provided via engine bindings)
 *              · App-provided zone config (`hrMaxFromApp`, `restingHrFromApp`,
 *                `hrZoneModeFromApp`)
 *          - Outputs into `BtEngineState`:
 *              · Canonical `heartRate`
 *              · `heartRateSource`
 *              · `heartRateZone`
 *              · Smoothed `externalHeartRate` and updated timestamps
 *      • Pace fusion:
 *          - Inputs:
 *              · Native FTMS pace (sec/km)
 *              · Speed-derived pace (from `appendSpeedKphSample`)
 *              · Distance-derived pace (from `distanceSeriesM`)
 *          - Output:
 *              · `setInstantPace(minPerKm)` and `setAvgPace(minPerKm)`,
 *                which `BtEngineState` converts to min/km or min/mi based on
 *                `units`.
 *      • Energy fusion:
 *          - Inputs:
 *              · HRM-derived energy
 *              · Wear calories
 *              · FTMS energy
 *          - Output:
 *              · Canonical calories → `setExpEnergyTotal(...)`.
 *
 *  - **Rowing-specific derived speed**
 *      • When a rower doesn’t provide native speed:
 *          - FTMS parsers still push distance samples into `distanceSeriesM`.
 *          - `updateRowingSpeed()` uses a 3–5s sliding window over
 *            `distanceSeriesM` to derive:
 *              · Global instant speed (`speedKph`, `speedDisplay`) when no
 *                FTMS speed is present.
 *              · Global average speed from total distance + elapsed time.
 *          - `updateLapRowingSpeed()` projects that same distance window into
 *            the current lap using lap anchors to produce:
 *              · `lapAvgSpeedMps`
 *              · Seed values for lap-pace when full lap totals exist.
 *
 *  - **Lap state & helpers**
 *      • Lap anchors:
 *          - Raw fields:
 *              · `lapStartDistanceMeters`
 *              · `lapStartElapsedSec`
 *              · `lapStartStrokeCount`
 *              · `lapStartSteps`
 *          - Primed via `BtEngineState.primeLapFromCurrentTotals()` in
 *            `btEngine.StateExtensions.kt` when a new lap starts.
 *      • Derived lap metrics:
 *          - Updated by Utils and FTMS parsers using anchor+total patterns:
 *              · `lapElapsedTimeSec`
 *              · `lapDistanceMeters`
 *              · `lapAvgSpeedMps`
 *              · `lapPaceMinPerKm / lapRowPaceMinPer500m`
 *              · `lapStrokeCount / lapSteps / lapCadenceSpm`
 *          - `lapCount` increments on each `BtEngine.doLap()`.
 *          - `updateLapRowingSpeed()` provides a rowing-specific lap view when
 *            only distance is available.
 *
 *  - **Time-series & rolling averages**
 *      • `BtEngineState` maintains a bounded window (min 5 minutes) of
 *        `TimedSample(tMillis, v)` for:
 *          - `hrSeries`
 *          - `speedSeriesKph`
 *          - `cadenceSeriesSpm`
 *          - `powerSeriesW`
 *          - `inclineSeriesPercent`
 *          - `resistanceSeries`
 *          - `distanceSeriesM` (rower helper)
 *      • The `append*Sample(...)` helpers:
 *          - Append a new sample.
 *          - Trim older samples to the current `seriesWindowMinutes`.
 *          - Maintain running means:
 *              · `averageHeartRate`
 *              · `avgSpeedKph`
 *              · `averageCadence`
 *              · `averagePower`
 *      • `setSeriesWindowMinutes(...)` lets callers adjust the window size
 *        (with a minimum enforced), and `clearAllTimeSeries()` wipes both data
 *        and accumulators (used on session reset).
 *
 *  - **Error routing**
 *      • All GATT-level failures call `BtEngineState.setGattErrorFor(gatt, msg)`:
 *          - Routes to `lastGattError` for FTMS.
 *          - Routes to `lastHrmError` for HRM.
 *      • This keeps a single error surface per pipe for UI and logs.
 *
 * STATEFLOW CONSUMERS (READERS / SINKS)
 *  - **UI & ViewModels**
 *      • Subscribe to StateFlows for:
 *          - Scan lists (`devices`, `hrmDevices`, `isScanning`)
 *          - Connection & control (`connectionState`, `hrmConnectionState`,
 *            `haveControlFlow`, `cccdReady`, `lastGattError`, `lastHrmError`,
 *            `lastFtmsStatusCode`, `safetyKeyEngaged`)
 *          - Session/units (`units`, `activeSession`, `isRunning`,
 *            `elapsedTimeSec`, `lap*`)
 *          - Live metrics (speed/distance/pace/power/cadence/steps/strokes/
 *            incline/elevation/force/energy/METs)
 *          - Heart-rate & zones (`heartRate`, `heartRateSource`,
 *            `heartRateZone`, `averageHeartRate`, `sensorContact`,
 *            `rrIntervalsMs`, `bodySensorLocation`)
 *          - Graphs (`hrSeries`, `speedSeriesKph`, `cadenceSeriesSpm`,
 *            `powerSeriesW`, `inclineSeriesPercent`, `resistanceSeries`)
 *      • UI code does **not** re-interpret bytes; it just renders.
 *
 *  - **Workout / session orchestration**
 *      • Uses:
 *          - `activeSession`, `isRunning`, `elapsedTimeSec` for session state.
 *          - distance/steps/strokes/speed/HR/energy for payloads and export.
 *      • Binds app-level preferences into `BtEngineState`:
 *          - `hrMaxFromApp`, `restingHrFromApp`, `hrZoneModeFromApp`.
 *
 *  - **btService (local provider)**
 *      • Reads the same StateFlows to emulate a FTMS/CSC/CPS device:
 *          - Uses capabilities and ranges to build Supported-* Range values.
 *          - Uses targets, status, and metrics to back Fitness Machine Service
 *            characteristics that downstream apps see.
 *
 * LIFECYCLE HELPERS (StateExtensions)
 *  - `resetFtmsControl()`:
 *      • Clears `controlPointChar` and resets `haveControlFlow` to `false`.
 *      • Called on disconnect, RESET, or control-loss events to ensure callers
 *        don’t fire Control Point commands against a dead pipe.
 *
 *  - `primeLapFromCurrentTotals()`:
 *      • Copies current totals into lap anchors:
 *          - `lapStartDistanceMeters`
 *          - `lapStartElapsedSec`
 *          - `lapStartStrokeCount`
 *          - `lapStartSteps`
 *      • Called when starting a session or taking a new lap, so later incoming
 *        FTMS frames can compute lap deltas correctly.
 *
 * IMPLEMENTATION NOTES
 *  - `BtEngineState` deliberately holds only lightweight Android types
 *    (`BluetoothGatt`, `BluetoothGattCharacteristic`) and never calls platform
 *    APIs itself.
 *  - All observable fields are backed by `MutableStateFlow` and exposed as
 *    `StateFlow` to consumers; callers **must** use `BtEngineState`’s helpers
 *    rather than touching backing flows directly.
 *  - This file is documentation only; it should compile without contributing
 *    any runtime behavior.
 */
