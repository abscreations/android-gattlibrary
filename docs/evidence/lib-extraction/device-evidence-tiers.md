# Device-family evidence

Source: the FitBridge device-port plan at commit e9442a4. The FitBridge plan remains authoritative.

## Evidence criteria

### 1.5 Hardware access and the evidence ladder (D24, D25, D26)

The original plan gated six phases on physical access to machines. That gate is now known to
be permanent, not temporary, so treating those phases as "ready when hardware appears" was
scheduling work that would never start.

#### Available hardware

| Class | What | Confidence |
|---|---|---|
| FTMS rower | physical machine | High — direct verification |
| FTMS elliptical | physical machine | High — direct verification |
| Simulated protocols | FTMS across every persona (bike, treadmill, rower, elliptical, stair), plus a phase-specific simulator only where that phase records one | High, for the protocol behavior actually exercised. A simulator proves FitBridge consumes the specified/capture-backed stream; it proves nothing about how an unobserved physical model or firmware deviates. |
| HRM | physical sensor | High |
| CSC | physical sensor | High |
| RSC | physical sensor | High |

Everything else — Concept2, Precor, Life Fitness, Schwinn, Kayak First, Wahoo, Tacx/FE-C,
Keiser, Flywheel, Echelon, Domyos, Sole, Nautilus, iFit — will not be tested against hardware
by this project. Assume that permanently.

#### The ladder

Every family declares exactly one tier, recorded in its phase section and in its provenance
record. The tier is a claim about **evidence**, not about code quality.

| Tier | Name | Definition | May ship measurement | May ship control |
|---|---|---|---|---|
| **H** | Hardware-verified | Verified against a machine or sensor in the D24 list. | Yes | Yes |
| **S** | Simulator-verified | Verified over BLE against a spec- or capture-conformant simulator for this family's actual protocol, but no physical instance of the family is available to test. In-process packet replay alone is not sufficient. | Yes | Only for standard FTMS control, and only where the phase's safety rules permit it |
| **C** | Corroborated | Two or more independent reference projects implement the family and **agree** on the identifiers, framing, field offsets, types, scaling, and units FitBridge relies on. | Yes | No |
| **1** | Single-source | Exactly one project implements it. Nothing to cross-check against. | Yes, labelled unverified | No |
| **X** | Disputed | Two or more projects implement it and **disagree** on a detail FitBridge depends on. | Only after §1.5.1 resolution | No |
| **U** | Unresolvable | A fact FitBridge needs is in no source — most often a full 128-bit UUID that every project stores only as a four-character alias. | No — cannot be implemented at all | No |

Two consequences worth stating plainly:

- **Tier C is not proof.** Two projects can agree because one copied the other. Corroboration
  is worth more than a single source and much less than a packet capture, and the plan should
  not pretend otherwise. Where a lineage is suspected — QZ and TMIW both descending from the
  same forum post, say — record it in the provenance record and treat the family as tier 1.
- **Tier U is a real category, not a formality.** It is the one case this decision does not
  rescue. If nobody anywhere records the full UUID, no amount of best-effort makes the service
  discoverable. §10 lists which families are currently in it and what would resolve them.

#### What "unverified" means to a user

A tier-1 or resolved tier-X family shows its status in the device row and in the connection
detail: the machine is supported, the decoding is taken from one project's implementation, and
FitBridge has not seen the hardware. That is honest and it is also useful — it tells a user
reporting bad numbers, and it tells you, exactly where to look first.

Tiers H, S, and C carry no user-facing marker.

#### 1.5.1 Disagreement is a finding, not a blocker

When two projects disagree, the family goes to tier X and the disagreement is entered in the
**divergence register** (§12.1) before any code is written. Resolving an entry means one of:

1. **One reading is demonstrably right** — it matches a published specification, or the other
   is internally inconsistent (a scale that contradicts the project's own unit conversion, a
   field width that overruns its packet). Record the argument; the family drops to tier C.
2. **Both readings are defensible.** Implement neither as a silent default. Either decode the
   field both ways and pick by a runtime plausibility check with the losing branch logged, or
   **drop the field**. A metric FitBridge cannot decode confidently is better absent than
   wrong: an absent metric is visibly missing, a wrong one is quietly believed.
3. **The disagreement is about model coverage, not the wire** — one project handles a firmware
   revision the other does not. Both are right. Implement the union, gate each branch on its
   own fingerprint, and drop to tier C.

An unresolved tier-X field must not ship. An unresolved tier-X *family* may still ship with
that field omitted.

#### What replaces hardware in the exit criteria

Where a phase previously said "hardware confirmation of X", it now says:

1. **Cross-source comparison table** for the facts FitBridge depends on — identifiers, framing,
   offsets, types, scaling, units, and any state machine ordering — with one column per
   reference project and agreement or divergence marked per row.
2. **Divergence register entries** for every disagreement, each resolved per §1.5.1.
3. **Fixture parity** against whatever vectors the reference projects ship, per §5.
4. **Synthetic adversarial fixtures** built by FitBridge: truncation, malformed flags, high-bit
   values, rollover, sentinel values, and out-of-order or fragmented delivery. These are now
   more important than before, because they are the only place a decoding error can be caught
   without the machine.
5. **Plausibility bounds at runtime.** Every untested family declares physically sensible
   ranges per metric (a rower does not sustain 3000 W; a treadmill does not run at 400 km/h).
   A sample outside its bound is dropped and captured through §0.9 rather than published. This
   is the cheapest available substitute for the sanity a real machine would have provided.

Item 5 is the substantive addition. The rest is bookkeeping; bounds checking is what actually
protects a user from a mis-scaled field in a family nobody could test.

---

## Recorded phase evidence statements

### Phase 5 — Concept2 PM5 and Precor Chrono (measurement-only)

**Evidence tier: C (corroborated).** TMIW implements it, QZ's `ftmsrower` and `concept2skierg`
corroborate it, and Concept2 publishes the PM Bluetooth Smart specification.

**Evidence tier: C (corroborated).** TMIW implements the Chrono as a bike; QZ's `chronobike`
corroborates the wire format, and both QZ's `chronobike` and its `truetreadmill` record the
`a026ee07` / `a026e01d` identifiers with a full namespace.

### Phase 6 — Life Fitness initialization

**Evidence tier: C (corroborated) for identity, the setup transcript, and transition to FTMS
measurements.** TMIW's `life_fitness_mixin.dart` and QZ's `lifefitnesstreadmill.cpp` independently
describe the setup writes. QZ issue #1018 adds an nRF Connect inventory and physical Android HCI
trace that shows the complete proprietary exchange followed by sustained standard FTMS Treadmill
Data notifications. QZ issue #1554 independently records the same proprietary GATT inventory on
an `LF…` cross trainer. TMIW issue #462 adds official-app HCI captures across a cross trainer,
stair climber, indoor bike, and treadmill. Those four sessions repeat the canonical GATT and setup
sequence, demonstrate much shorter write-completion timing than TMIW's fixed sleeps, and yield
real FTMS packet-length corpora for all four personas. The redacted records are
`docs/evidence/phase-6/2022-11-11-life-fitness-qz-gatt-and-setup-evidence.md` and
`docs/evidence/phase-6/2024-05-15-life-fitness-tmiw-multi-persona-hci.md`. The user-submitted
BleDiscovery dump archived at
`docs/evidence/phase-6/2026-07-20-life-fitness-blediscovery-advertisement.md` adds real
advertisement evidence. Exact model/firmware coverage is still absent, and FitBridge has no Life
Fitness hardware, so none of this is a tier-H claim.

### Phase 7 — Schwinn x70 (170 / 270 / 570)

**Status: CLOSED 2026-08-18.** All three streams decode, emit and are reachable end to end; the
family ships labelled unverified. Closing suite: 150 classes, 1,265 tests, 0 failures, 0 errors, 0
skipped. Exit audit: `docs/evidence/phase-7/2026-08-18-phase-7-exit-audit.md`.

**Evidence tier, revised 2026-08-14 and re-scoped 2026-08-18 — the tiers belong to *streams*, not
consoles.** The wording below said "170 path" and "270 / 570 path" because the streams were believed
to be console-specific. They are not: one machine emits both. The tiers themselves are unchanged,
because they describe how well each *layout* is corroborated, and that did not move.

### Phase 8 — Kayak First

**Status: CLOSED 2026-09-08 at tier S / unverified.** The user-run forced suite passed on
2026-09-08: `gradlew testDebugUnitTest --rerun-tasks --no-daemon`, 2,057 tests, 26 of 26 tasks
executed.

**What tier S means here, stated plainly:** no physical Kayak First console has ever been
involved. Every upstream byte in every capture came from FitBridge's own simulator, so the
archived evidence tests the central and downstream paths against a reimplementation of the
protocol rather than against the device. The wire reading is corroborated by decompiled official
application versions, the KayakFit third-party client, and one physical-console log set from a QZ
issue report — corroborated is not verified, and the family ships labelled unverified. §23.4 of the
refactor plan lists what remains unestablished.

**Evidence tier: 1 (single-source), still the weakest implementation position in the plan.** TMIW
is the only project that implements it, and TMIW ships **no dedicated tests for it at all**
(confirmed: no `kayak_first*_test.dart` exists). However, issues #397/#426/#429/#430 document
development with a physical console assembly, public command/response logs, malformed-frame
examples, and successful canoe-erg workouts. The implementation is not independently
corroborated, but it is no longer accurate to say there is no hardware or captured corpus.

### Phase 9 — FE-C over BLE

**Evidence tier: C (corroborated), and unusually strong.** FE-C is a published ANT+ profile,
Incyclist implements it, and the frame format is documented independently of any project. This
is the best-evidenced non-standard family in the plan. The formal Phase-9 cross-source evidence
record is explicitly deferred by user decision (2026-08-21); closure records official-profile
evidence plus tier-S FitBridge simulator/central interoperability without promoting it to hardware
verification.

### Phase 10 — Wahoo proprietary trainer control

**Evidence tier for proprietary control: C (corroborated), with hardware validation deferred by
D89.** Incyclist and independent implementations/captures establish the control-point UUID,
opcodes, and the selected encodings below. The submitted physical sessions never exercise the
proprietary point. Physical identity/GATT and standard FTMS control evidence must not be promoted
into proprietary verification, and later simulator-to-third-party-app captures backfill rather than
precede implementation.
The 2026-08-21 raw Core firmware-1.5.36 HCI follow-up strengthens the physical identity, exact
advertising, GATT, FTMS-feature, and dual CPS/FTMS measurement evidence, but also contains zero
`a026e005` and zero `0x2AD9` value writes. Its 142 Write Commands use the distinct
`00000001-19ca-4651-86e5-fa29dcdd09d1` RideOn service and are outside this adapter's protocol scope;
they do not resolve V18–V21 by substitution.

### Phase 11 — Keiser M3 / M3i (advertisement-only source)

**Closed 2026-09-02 at tier S under D93/D95.** Keiser Corporation's
MIT-licensed companion `Keiser.M3i.BLE-HCI-Simulator`, pinned for this phase at
`03f4a78639e6c002f9b6bad3afb546adb10505ba`, was exercised as a real BLE advertiser. It exposes
the Keiser company ID `0x0102` and the 17-byte Android manufacturer payload (the full vendor frame
is the company-ID octets `02 01` followed by those 17 bytes), repeat advertisements without a GATT connection, and
cover the official vector plus the capture-backed firmware/unit/mode cases recorded in the Phase 11
evidence record. The archived FitBridge-side scan trace agrees with the pinned fixtures. This
validates the advertisement protocol path, not physical
Keiser hardware, and it does not add control.

### Phase 12 — Flywheel Sports

**Closed 2026-09-04 at tier 1/unverified, with tier-S simulator interoperability.** All six
subphases, focused and affected regressions, and the forced full unit suite passed. A real BLE run
verified Flywheel simulator → FitBridge central → `FBRG-Bike` → Garmin measurement delivery. The
simulator does not promote the physical-device claim: no Flywheel hardware was available under D24,
and the implementation remains measurement-only with no target capability advertised.

**Evidence tier: 1 (single-source).** gymnasticon is the only implementation, and its own notes
describe observed dropouts. Ships labelled unverified.

See [module provenance](stage3-module-provenance.md) and the matching fixtures for implementation lineage and capture evidence.
