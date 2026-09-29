# gatt-core spec extraction inventory (LIB-18b)

Date: 2026-09-29. Inventory only: nothing has moved. Scope is L23 (core holds spec facts
only) applied to the current tree at `9787704` plus the uncommitted LIB-18a plan update.
Counts come from `core_spec_inventory_scan.py` in this folder (static text scan, not compiler
resolution; same-named symbols in other packages are counted too). The `.fuse_hidden*` files
the scan sees under some source folders are mount artifacts, not sources, and are excluded.

## 1. Classification rule applied

| Category | Goes to core? | Why |
|---|---|---|
| Identifiers: SIG and vendor service/characteristic/descriptor UUIDs, company IDs, advertisement markers | Yes | Needed to find or serve a device on both sides; pure facts. |
| Protocol vocabulary: opcodes, result/status codes, page numbers, feature/flag bit positions, machine-type codes, wire enums, fixed init/magic byte sequences, frame lengths, parameter ranges stated by the protocol | Yes | Both sides must agree on meaning; no behavior. |
| Field layouts and scales: per-field offsets, divisors, multipliers, "observed constants" | **Proposed no (Q1)** | Kept private on each side as deliberate double entry; conformance tests (L25) compare both against golden captures. Sharing them would let one wrong offset fool central and simulator together. |
| Byte primitives: LE/BE integer read/write, varint/zigzag | Yes, as one tested set with explicit semantics | Duplicated in many files with differing semantics (section 3). |
| Parsers, encoders, codecs, command models, physics/power models, state machines | No | Behavior. Each side keeps or writes its own (L23). |
| Timing, retry, freshness, fusion policy | No | Owner policy, not protocol. |
| Android runtime types (`ParcelUuid`, scan filters) | No | Core carries no Android BLE runtime types. Stays in central. |
| FitBridge persona policy: `FBRG-` names, service modes, advertised feature presets, client limits | No | Product policy of the server/peripheral side. |

## 2. `BluetoothIds.kt` (central, 1,257 lines; about 330 public declarations)

The file holds four things: `object BluetoothIds`, `internal object Flags`, `object BtServiceProfiles`,
and top-level byte helpers. Proposed disposition:

### 2.1 Moves to core (identifiers and vocabulary)

| Group | Members | Current consumers (main source sets) |
|---|---|---|
| FTMS service/characteristics | `UUID_FTMS_SERVICE`, feature, control point, status, training status, the six machine data characteristics, the five supported-range characteristics | central, peripheral, simulator |
| FTMS control point vocabulary | `OPCODE_*` (13), `OPCODE_RESPONSE_CODE`, `RESULT_*` (5) | central (peripheral has a duplicate `FtmsSpec`, 2.4) |
| FTMS status vocabulary | `STATUS_*` (11) | central (duplicate in peripheral `FtmsSpec.Status`) |
| FTMS unit scales | `FTMS_SPEED_SCALE`, `FTMS_INCLINE_SCALE`, `FTMS_RESISTANCE_SCALE`, `FTMS_POWER_SCALE` | central. These are spec-stated units (0.01 km/h, 0.1 %, 0.1, 1 W), so vocabulary, not private layout. |
| FTMS data flags | `Flags.TREAD_*`, `ELL_*`, `ROW_*`, `BIKE_*`, `STEP_*`, `CLIMB_*` (73, currently `internal`) | central only today; peripheral encoders build the same flags inline. Becomes public in core. |
| FTMS machine type | `BtServiceProfiles.FtmsMachineProfile`, `FM_TYPE_*`, `FM_FLAG_MACHINE_AVAILABLE`, `dataUuidForProfile` (profile → data characteristic) | central, peripheral, simulator, app |
| SIG sensor services | HRM, CSC, RSC, CPS, battery, glucose, device information, bond management, user data (incl. `USER_PROFILE_CHARACTERISTICS`), sensor location, CCCD | central, peripheral, simulator |
| Concept2 PM5 | `concept2Uuid` builder and all `UUID_C2_*` (29) | central, simulator |
| Precor, Schwinn x70, Flywheel, Kayak First, FE-C over BLE, Wahoo KICKR, Wahoo RideOn, Zwift hub support, Nordic UART, Secure DFU, Life Fitness (incl. B90/FD0x) | all `UUID_*` in those groups, `X70_OBSERVED_CHARACTERISTICS` | central and/or simulator |
| FE-C ANT vocabulary | `FEC_ANT_*`, `FEC_DEFAULT_CHANNEL`, `FEC_PAGE_*` (19), `FEC_EQUIPMENT_*` (9) | central, simulator |
| Diagnostics list | `DIAGNOSTICALLY_RETAINED_CHARACTERISTICS` | central, app tests. **Q2** decides whether this travels with the capture contract instead. |

About 75 of these UUIDs are used only by the simulator (device information, DFU, Zwift hub,
Nordic UART, KICKR markers, most Concept2 characteristics, Life Fitness B90/FD0x, Schwinn
unused characteristics). They are still facts about supported devices, so the proposal keeps
one registry in core (**Q4**).

### 2.2 Stays in central

- `FTMS_SERVICE_PARCEL` and the other `*_PARCEL` values (Android `ParcelUuid`). Only
  `FTMS_SERVICE_PARCEL` is referenced; the other nine are unused and are deletion candidates in
  the move unit (no behavior change).
- `BtServiceProfiles.activityTypeFromFtmsServiceData` (FTMS service-data decoder).
- `ftmsProfileForActivity`, `dataUuidForActivity` (central's activity mapping; they can call
  core's `dataUuidForProfile`).

### 2.3 Goes to `gatt-server` (peripheral-side policy and encoders), not core

`ServiceMode`, `CscCpsProfile`, `DEVICE_NAME_PREFIX` and the `DEVICE_NAME_*` names,
`deviceNameFor`, `MAX_CLIENTS` (read by the app), `DEFAULT_NOTIFY_INTERVAL_MS` (unused),
`BASE_FMF`/`FMF_*` presets and `fmfFor`, `TSF_NONE`, `fitnessMachineTypeFor` and
`ftmsServiceDataFor` (service-data encoder). These are FitBridge's advertised persona, not
protocol facts. `TSF_NONE` has one central reader, which becomes a local zero.

### 2.4 Duplicate FTMS definitions to merge into the core set

| Fact | Central | Peripheral | Simulator/other |
|---|---|---|---|
| Opcodes, result codes | `BluetoothIds.OPCODE_*`, `RESULT_*` | `FtmsSpec.Opcode`, `FtmsSpec.Result` | — |
| Status codes | `STATUS_*` (no 0x12/0x13) | `FtmsSpec.Status` (has 0x12, 0x13) | — |
| Training status | — | `FtmsSpec.TrainingStatus` | — |
| Feature and target-setting bit positions | `btEngine.Capabilities` private `TARGET_SIMULATION_BIT` (13), `TARGET_SPIN_DOWN_BIT` (15) | `FtmsSpec.Feature`, `FtmsSpec.TargetFeature` | — |
| "Not available" sentinel | inline | `FtmsSpec.SINT16_NOT_AVAILABLE` | `FeCPageParsers` has FE-C sentinels (private layout, stays) |
| Control-point parameter lengths | inline in encoders | `FtmsControlPointSchema.expectedLength` | — |
| Data-characteristic flags | `Flags.*` | built inline by `FtmsDataEncoders` | `KickrSyntheticModel.FTMS_FLAGS_CAPTURE_SHAPE`, `TacxFlux2StandardSupport.INDOOR_BIKE_DATA_FLAGS` (persona values, stay) |

All overlapping values were compared by hand and agree; the only difference is coverage
(peripheral defines more status codes). The merged core object carries the union.

### 2.5 CSC/CPS/RSC vocabulary scattered outside `BluetoothIds`

| Fact | Where now | Proposal |
|---|---|---|
| CSC measurement flags (wheel, crank) | `CscMeasurement.FLAG_*` (public) | core |
| CSC/CPS feature bits, CPS flag bits, sensor-location code, SC control point opcodes/results | `peripheral/CyclingSensors.kt` (`CSC_FEATURE_WHEEL_AND_CRANK`, `CPS_FEATURE_CRANK_REVOLUTION_DATA`, `CPS_FLAG_*`, `SENSOR_LOCATION_REAR_HUB`, `SET_CUMULATIVE_VALUE`, response codes) | core |
| RSC flag bits | `RscMeasurement` private `FLAG_*`; simulator `StrydFootpodEncoder.RSC_FLAG_*` | core, **after Q5** |
| CSC/CPS event-time base (1/1024 s) and rollover modulus | `CyclingSensors` private | core (spec-stated units) |

## 3. Byte helpers

80 production definitions in central, peripheral and simulator, plus 63 in test sources
(app 4, central 22, peripheral 22, simulator 15). They are not interchangeable: five different
behaviors exist today, and several call sites depend on theirs.

| Semantics | Where (production) | Notes |
|---|---|---|
| **R1** Read, unchecked index (caller checks length first; throws if it didn't) | central: `CscMeasurement` u8/u16/u32, `KeiserMSeriesMeasurement` u8/u16, `PrecorMeasurement` u8/u16/u24, `RscMeasurement` u16/u32, `SchwinnX70Measurement` u8/u16/u48, `btEngine.Capabilities` u16/s16, `btEngine.GattFtmsParsers` u8/u16/s16, `FeCPageParsers` u8/u16le/i16le, `FlywheelMeasurement` Byte.u8; peripheral: `FtmsControlPointHandler` u16/s16; simulator: `FeCBleControlDecoder` u16le, `KickrLegacyControlServer` u16, `Concept2CsafeCodec` Byte.u8 | Most common. The two s16 styles (mask-then-`toShort()` versus sign-carrying high byte) give identical results. |
| **R2** Read, sequential cursor, null when short | central: `Concept2Measurement.Cursor`, `CpsMeasurement.Cursor` (u8/u16/s16/u24/u32) | Proper bounds-checked form; the model for the core reader. |
| **R3** Read, short input returns 0 | central: `btEngine.GattFtms` status `u16LE` | Different from its neighbor `s16LE`, which returns null. Must be preserved at that call site or changed in its own reviewed unit, not silently. |
| **R4** Read, big-endian | simulator: `Concept2CsafeCodec` u16be, u32be (null above `Int.MAX_VALUE`) | CSAFE is big-endian. |
| **W1** Write, mask/wrap (out-of-range values wrap) | central: `bluetoothIds` u16le/s16le/u24le/byteOf (public), `btEngine.ControlPoint` u16le/s16le, `LifeFitnessSetup` uint16Le, `WahooKickrCodec` u16; peripheral: `FtmsSpec` u8/u16le/s16le/u24le/u32le (public, imported by simulator); simulator: `Concept2Pm5GattProfile` u16le, `StrydFootpodEncoder` u16le/s16le/u32le, `FeCBleTelemetryEncoder` putU16, `SchwinnX70FrameEncoder` putU16 | Two public copies (central and peripheral) with the same name in different packages. |
| **W2** Write, saturating (out-of-range values clamp) | simulator: `Concept2Pm5Encoder.Writer` u8/u16/u24, `LifeFitnessPacketEncoder.Writer` u8/u16/s16/u24, `KeiserMSeriesSimulatorEncoder` putU16, `PrecorChronoEncoder` putU16/putU24; peripheral: `CyclingSensors` u32leLong; central: `btServiceSupportedRangeAdapter` clampU8/U16/S16 | Saturating versus wrapping changes emitted bytes for out-of-range input. |
| **P1** Protobuf varint/zigzag | central: `RideOnProtoCodec` (varint, zigZagEncode32/Decode32, field helpers); simulator calls `RideOnProtoCodec.zigZagDecode32` | Generic wire-format primitive; proposed for core. The RideOn message layer stays with each side. |
| Hex formatting (`toHex`, `toHexString`, `appendHexString`) | central ×4 (two formats), peripheral ×1 | Logging/diagnostics, not spec. Stays local unless the capture contract moves (Q2). |

Proposed core primitive set: bounds-checked readers (`u8/u16/s16/u24/u32` LE, `u16/u32` BE,
nullable and cursor forms), writers in **both** explicit forms (`…Wrapping` and `…Saturating`),
and varint/zigzag. Every call site keeps its current semantics; any site that should change
(R3 is the obvious candidate) is a separate, test-first change. Test helpers stay in tests; a
shared test helper in `gatt-fixtures` is optional and out of scope for core.

## 4. Proprietary constants consumed across modules today

These are the only non-UUID central symbols that peripheral or simulator import. Each needs a
home before the simulator can drop its central dependency (Stage 3.5.3).

| Symbol (central) | Used by | Proposed home |
|---|---|---|
| `KeiserMSeriesMeasurement.COMPANY_ID`, `CURRENT_FIELD_LENGTH` (`LEGACY_FIELD_LENGTH`) | simulator | core (identifier, framing) |
| `KEISER_M_SERIES_CANDIDATE_ID` and the other candidate IDs | peripheral | core family catalog (Q3) |
| `PrecorMeasurement.MAGIC_PREFIX`, `FRAME_LENGTH`, `auxiliaryFrame()` bytes | simulator | core (framing, fixed captured frame, cited) |
| `SCHWINN_X70_INITIALISATION` | simulator | core (fixed init sequence, cited) |
| `SchwinnX70Measurement.KM_PER_MILE` | simulator | local constant on each side (unit conversion, not protocol) |
| `SchwinnX70Measurement.TMIW_CALORIE_DIVISOR`, `SIBLING_SPEED_DIVISOR` | simulator | stays private per side (layout/scale, Q1) |
| `SchwinnX70Measurement.isWellFormedCommand`, `parseSibling` | simulator | behavior: simulator writes its own |
| `SchwinnX70PowerModel` (`RESISTANCE_POWER_COEFFICIENTS`, `powerWatts`) | simulator | coefficient table to core (cited TMIW source); each side implements the formula |
| `RideOnUuids` opcodes, data IDs, `HUB_INFO_MARKER`, `MAX_PACKET_BYTES` | simulator | core |
| `RideOnUuids.RIDING_DATA_FIELD5_SPEED_MULTIPLIER`, `FIELD6_OBSERVED_CONSTANT` | via `RideOnProtoCodec.ridingData` | private per side (observed scales, Q1) |
| `RideOnServiceForm`, `RIDEON_GEAR_RATIOS` | simulator | core (wire enum; cited gear table) |
| `RideOnProtoCodec`, `RideOnProtoField`, `RideOnDecodedPacket`, `RideOnDecodeResult` | simulator | behavior: simulator writes its own message layer on core varint |
| `WahooKickrUuids` opcodes and protocol ranges (`MIN/MAX_ERG_WATTS`, standard level, CRR/CW maxima, `MAX_TOTAL_MASS_KG`) | simulator | core |
| `WahooKickrUuids` timings (unlock timeouts/attempts, ATT/indication timeouts, `ERG_MIN_INTERVAL_MS`) | central | stays central (policy) |
| `WahooKickrCommand` model and codec | simulator | behavior: simulator keeps its own decoded command model |
| `KayakFirstOrangeCommand`, `KayakFirstDisplayMetric` wire codes, `KayakFirstHandshakeVariant` | simulator, app | core (wire vocabulary) |
| `KayakFirstDisplayConfiguration` (`DEFAULT`, `fromWireCodes`) | simulator, app | stays central as consumer config; simulator decodes wire codes itself |
| `KayakFirstCommandCodec.DEFAULT_BOAT_WEIGHT_KG`, `KayakFirstDerivedMetrics.DEFAULT_PULL_LENGTH_M` | simulator | core only if the Kayak First wire spec (`docs/kayak-first-wire-protocol-spec.md`) states them; otherwise each side keeps its own default |
| `KayakFirstOrangePhysics`, `KayakFirstOrangeTimingTuple`, `…PhysicsResult` | simulator | behavior: simulator implements the inverse independently; shared physical constants (for example flywheel inertia) to core only if sourced |
| `CpsNativeEventSnapshot` | simulator (`TacxFlux2StandardSupport`) | simulator-owned value type |
| `HEART_RATE_FRESHNESS_MS`, `BtEngineState` reads | simulator | not core: simulator host port (L25) |
| `CscNativeEventSnapshot`, `TreadmillNativePaceSnapshot`, `FtmsResistanceScale`, `BtEngineState.FtmsCapabilities`, `BtServiceEngineFacade` | peripheral | not core: public client projections (L24) |
| `central.capture.*` event types and `BleCapture` | peripheral | **Q2** |

## 5. Divergence found during the inventory

**RSC flag bit 2 is interpreted in opposite directions.** Central's `RscMeasurement`
documents bit 2 as "walking (1) / running (0)" and sets `walking = true` when it is set. The
simulator's Stryd encoder sets bit 2 for *running*. The Bluetooth SIG RSC Measurement
characteristic definition (as mirrored from the SIG GATT XML) defines bit 2 as
"Walking or Running Status": 0 = walking, 1 = running, which matches the simulator. Today
central only uses the value in a log line, so no metric is wrong, but core must not publish a
constant name that encodes either reading until the central side is corrected (**Q5**).

## 6. Family identifiers: three vocabularies

A core family catalog (L23) needs one key. Today there are three:

- Matcher candidate IDs (central `DeviceMatching`): `sig.ftms`, `sig.hrm`, `sig.csc`, `sig.rsc`,
  `sig.cps`, `sig.glucose`, `vendor.concept2.pm`, `vendor.fec.ble`, `vendor.precor.chrono`,
  `vendor.schwinn.x70`, `vendor.wahoo.kickr.legacy`, `vendor.wahoo.rideon.original`,
  `vendor.wahoo.rideon.core2`, `vendor.keiser.m`, `vendor.flywheel.sports`, `kayak.first`.
- Adapter liveness IDs (central `AdapterLiveness`): `ftms.generic`, `concept2.pm`,
  `precor.chrono`, `wahoo.kickr.legacy`, `wahoo.kickr.rideon`, `schwinn.x70`, `fec.ble`,
  `flywheel.sports`, `kayak.first`.
- The consumer `MachineFamily` enum (LIB-17b), which also names Life Fitness (identified from
  FTMS plus vendor services, with no candidate ID of its own).

Proposal (Q3): the core catalog keys families by the matcher candidate IDs (they are the
evidence-bearing identity and already cover sensors), adds an explicit Life Fitness entry, and
maps adapter IDs to it inside central. The consumer enum is unchanged.

## 7. Decisions

User decisions, 2026-09-29: **Q1** field layouts and scales stay private per side; **Q2**
capture contract moves to `core.capture` as a named exception to L23; **Q5** correct central's
RSC bit 2 reading first, in its own test-first unit; **Q6** split the registry into per-domain
objects with citations in the same move. **Q3** (catalog keyed by matcher candidate IDs) and
**Q4** (simulator-only UUIDs stay in the single core registry) follow the recommendations below
unless the user changes them before step 4. The original options are kept for the record.


- **Q1 Field layouts:** keep per-field offsets and scales private on each side (recommended)
  instead of putting them in core. This narrows the L23 example list, which mentioned Keiser
  layout offsets.
- **Q2 Capture contract:** peripheral (and later `gatt-server`) writes capture events typed in
  `central.capture`. Under L22 that type needs a home that is not central. Options: a
  `core.capture` data contract (event schema, roles, redaction rules; recommended, because
  redaction must be identical everywhere) as a named exception to "spec facts only", or a
  server-local copy.
- **Q3 Family catalog key:** matcher candidate IDs (recommended), as in section 6.
- **Q4 Simulator-only UUIDs already in `BluetoothIds`:** keep them in the single core registry
  (recommended) rather than moving them to the simulator. UUIDs the simulator defines locally
  (Tacx FE-C extras, Stryd proprietary) stay in the simulator.
- **Q5 RSC bit 2:** correct central's interpretation in its own test-first unit before core
  names the bit (recommended), or name it neutrally in core and fix later.
- **Q6 Shape of the core registry:** split `BluetoothIds` into per-domain objects with a source
  citation per object (for example SIG services, FTMS, CSC/CPS/RSC, Concept2, Wahoo, Life
  Fitness), or move it unchanged first and split later. Every import site changes either way;
  splitting in the same unit costs a larger diff but gives L23's per-constant citations a home.

## 8. Proposed move sequence (after the decisions)

1. Add the core byte primitives with their own exhaustive tests (wrap, saturate, sign, short
   input, big-endian, varint/zigzag). No call site changes.
2. Move FTMS vocabulary into core and merge peripheral `FtmsSpec` and central duplicates;
   switch call sites. Peripheral's `FtmsSpec` public helpers become thin aliases or are removed
   with their imports.
3. Move the remaining SIG and vendor identifiers (Q4, Q6), leaving `ParcelUuid` values in central.
4. Move the proprietary vocabulary in section 4, with citations; add the family catalog (Q3).
5. Replace duplicate byte helpers call site by call site with the matching core semantics.

Each step is its own work unit with `releaseApiDump` review for core and central (core gains
public symbols, central loses the moved ones) and the affected module tests. The persona
advertisement/GATT snapshot tests must stay byte-identical throughout (L25).
