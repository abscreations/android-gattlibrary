# Sole simulator capture assets

Each file contains the GATT manifest and redacted ATT replay for the named
`SoleSimulatorProfile`. The header gives the original file, connection ID in
the discovery inventory and SHA-256. Sources are in FitBridge's
`CaptureLogs/Sole`, with the service and handle mapping in
`Sole_Simulator_Personas.json` and `Sole_BTSnoop_GATT_Inventory.json`.
F65 uses the central NDJSON in its FitBridge capture archive.

- `A` lists advertised service UUIDs and `D` carries captured FTMS Service Data.
- `S` starts a primary service. `G` lists the characteristic UUID, property bits
  in hex, CCCD presence and an observed read response, if available.
- `T` holds Treadmill Data, `V` holds the proprietary UART exchange and `C`
  holds FTMS Control Point, Training Status and Fitness Machine Status traffic.
  Each record contains the relative delay in milliseconds, operation, full
  service/characteristic identity and payload. Operations are notification
  (`N`), indication (`I`), write request (`W`) and write command (`U`).
  Short SIG UUIDs expand with the Bluetooth base UUID. A dash denotes a missing
  read value or an empty replay payload, depending on record type.

At runtime the workout engine generates fresh treadmill or bike samples on
the standard simulator tick. ST90 and modern F80 use the standard Treadmill
Data encoder and its MTU handling. F65 retains the observed four-byte Feature
read and short speed/zero-HR pair, with speed supplied by the workout engine.
F65 also runs a synthetic proprietary UART session, since its fixture has no
vendor transcript. The responder follows the separately observed F65 HCI
samples and the QDomyos-Zwift F65 order. After the client's final `40`
acknowledgement, it streams 18-byte workout frames with generated HR, speed
and incline. This is F65's only incline channel.
The raw `T` records stay available for capture regression tests.

F63, legacy F80, both legacy F85 variants, LCB and R92 advertise FTMS using
their captured advertising data and publish both FTMS and the proprietary
UART service in GATT. The same generated sample goes to FTMS subscribers
as Treadmill Data or Indoor Bike Data and to proprietary subscribers after
the captured startup reply. The simulator supplies a Feature value for R92,
whose captured database lacks that characteristic.
Treadmills send 18-byte workout packets. Bikes send the resistance record and
a 22-byte workout packet. R92 sends the workout packet in its captured 20+2
shape. LCB also uses that split when a full frame exceeds the negotiated MTU.
The encoders populate the mapped fields and zero the unmodeled workout bytes.

Vendor setup and status still follow the captured transcript. ST90, modern
F80 and the 59-characteristic F85 can optionally run the standard FTMS Control
Point state machine against the synthetic workout. The F85 control behavior is
a test configuration based on a separate cached session. Target incline spans
-10% to 30% in 0.5-point steps and target speed spans 0 to 30 km/h in 0.1 km/h
steps. The host's treadmill speed setting gates the speed Feature bit, range,
and writes. With simulator controls off, the Control Point and target ranges
are omitted. Captured control frames remain in the fixture for analysis.

Android owns GAP and GATT services and assigns handles. Advertising reproduces
the captured name, UUID list and known FTMS service data through Android's API.
Packet placement, R92 appearance and low-level advertising timing can differ.
Read values without evidence return an ATT error. Oversize setup or control
frames are reported and skipped without changing their bytes.

The F85 cached run, the separate F65 HCI sessions and the unidentified
elliptical lack the independently discovered surface needed for a selectable
persona. Sole+ extended program commands remain unsupported.
