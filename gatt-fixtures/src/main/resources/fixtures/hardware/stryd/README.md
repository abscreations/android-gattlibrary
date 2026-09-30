# Stryd footpod BLE capture corpus (sanitized)

Extracted from qdomyos-zwift application debug logs by `the extraction script (not checked in)`.
Four distinct Stryd units, December 2023 – December 2025, iOS/macOS Qt central.
Redundant exports of the same session were collapsed (prefix-match), so each row
below is one physical session.

| Session | Adv name | Duration | Frames | Speed m/s | Cadence spm | Power W | Distance km | Running frames | Battery |
|---|---|---|---|---|---|---|---|---|---|
| session-A | `Stryd` | 355s | 1440 | 0–0.89 | 0–85 | 0–81 | 0.068 | 9 of 355 | 45–47% |
| session-B | `StrydX` | 1229s | 6188 | 0–2.10 | 0–89 | 0–173 | 2.466 | 1171 of 1230 | 67–69% |
| session-C | `StrydX` | 131s | 526 | 0–1.07 | 0–55 | 0–89 | 0.075 | 0 of 132 | — |
| session-D | `Stryd` | 140s | 422 | 0–1.73 | 0–67 | 0–160 | 0.107 | 28 of 141 | — |

**Session B is the reference workout**: 20 minutes, 2.47 km, a clean
walk→run transition, full power and cadence range, and battery notifications.
Use it for replay unless you need a specific edge case.

Edge cases worth knowing:

- **session-D** opens with a total-distance value carried over from a previous
  session (40.1 m) that resets to 0 on the second frame — the only backward
  distance jump in the corpus. Use it to test client tolerance of odometer reset.
- **session-C** never leaves walking state (flags `0x03` throughout).
- **session-A** has the lowest battery readings (45–47%) and four walk/run flag
  transitions.

## Files

- `manifest.json` — session inventory, per-characteristic cadence percentiles,
  and the sanitization record.
- `session-X.frames.jsonl` — one JSON object per notification:
  `t_ms` (relative to first frame), `char`, `name`, `hex`, `len`, `decoded`.
- `session-X.rsc.csv`, `session-X.cps.csv` — decoded RSC / CPS frames, one row
  per notification, raw hex retained alongside the decode.

## Sanitization

- wall-clock timestamps replaced with milliseconds relative to first frame of session
- CoreBluetooth peer UUIDs and MAC address fields dropped
- per-unit manufacturer-data payload dropped; company ID and length retained
- non-Stryd devices from the scan log never extracted
- source log filenames replaced with session pseudonyms

The manufacturer-data payload was 6 bytes and unique per unit, so it is treated
as a device identifier and dropped; only the company ID (43690 / 0xAAAA) and the
length are retained. Nothing in these files identifies a unit, a person, a
location, or a wall-clock time.
