# Stage 3 module provenance

Recorded 2026-09-29 for the in-repository `gatt-*` modules. This inventory describes
implementation lineage and the sources reviewed for protocol facts; it does not upgrade any
family's evidence tier. The device-port plan and per-phase evidence records remain authoritative
for exact fields, fixture hashes, divergences, hardware claims, and control gates.

| Module | Family or area | Implementation lineage and corroboration |
| --- | --- | --- |
| `gatt-core` | Shared configuration, opt-in marker, ports/contracts | FitBridge extraction. No TMIW, Incyclist, or gymnasticon implementation material identified in this module. |
| `gatt-central` | Standard FTMS, HRM, RSC, CSC, CPS and metric fusion | FitBridge engine lineage; the RSC/CSC/CPS port phases compare against pinned TMIW fixtures and standard characteristics. FitBridge-specific connection contexts, metric fusion, and BLECAP remain distinct work. |
| `gatt-central` | Concept2, Precor, Schwinn x70, Life Fitness, Kayak First and FTMS quirks | TMIW at `85f2ee7520705c99ce81db7355df1be7f4b72ecf` (Apache-2.0), with independent captures and recorded divergence decisions where available. A TMIW alias is not treated as a full UUID. |
| `gatt-central` | FE-C over BLE and legacy Wahoo KICKR | Incyclist Devices v3.0.29 (MIT), plus the independent vectors/captures and safety decisions in device-port Phases 9-10. |
| `gatt-central` | Keiser M Series | Published Keiser developer documentation and gymnasticon v1.4.1 at `e142f539c518d6d4ced3abdd587a825b918a9367` (MIT); gymnasticonV2 was reviewed for robustness, not ported as an implementation source. Phase 11's archived simulator evidence is tier S, not physical-device verification. |
| `gatt-central` | Flywheel Sports | gymnasticon v1.4.1 (MIT) and the recorded Phase 12 divergence on isolated-zero filtering; measurement only. |
| `gatt-peripheral` | FTMS advertising, data and control bridge | TMIW at the pinned Apache-2.0 revision for the original FTMS peripheral surface. Capability honesty, upstream/downstream bridge rules, and control safety are FitBridge work. |
| `gatt-simulator` | Synthetic FTMS and TMIW-family personas | TMIW at the pinned Apache-2.0 revision for the mock-data lineage; physical/capture-backed variants follow the individual phase evidence records rather than inheriting a hardware claim from TMIW. |
| `gatt-simulator` | FE-C and legacy Wahoo compatibility | Incyclist Devices v3.0.29 (MIT), plus independent in-process and BLE simulator checks recorded in Phases 9-10. |
| `gatt-simulator` | Keiser M Series | Published Keiser documentation, Phase 11 captures, and fixtures. Keiser Corporation's MIT-licensed M3i BLE-HCI simulator at `03f4a78639e6c002f9b6bad3afb546adb10505ba` was an external test reference; no claim of code copied into this module. |
| `gatt-simulator` | Flywheel Sports | gymnasticon v1.4.1 (MIT) protocol corroboration and the Phase 12 compatibility decisions. |
| `gatt-simulator` | Other captured personas and Stryd | Independently recorded wire facts, fixtures, and published specifications as named by the relevant phase. No QZ GPL-3.0 implementation is attributed or authorized for reuse. |

Each production module carries its applicable attribution in a uniquely named
`src/main/resources/META-INF/gattlibrary/<module>.NOTICE` resource.
The repository root `LICENSE` and `NOTICE` remain the license/attribution baseline. Module notices
must travel with the library during Stage 4 extraction and be checked in the release AARs; a
source-tree file alone does not prove packaging.
