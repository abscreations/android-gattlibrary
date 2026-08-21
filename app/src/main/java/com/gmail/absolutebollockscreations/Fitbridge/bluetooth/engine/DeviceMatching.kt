package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import java.util.Locale
import java.util.UUID

/** Matcher-layer UUID storage. Values are always lowercase canonical 128-bit UUID strings. */
@JvmInline
internal value class CanonicalUuid private constructor(val value: String) {
    fun toUuid(): UUID = UUID.fromString(value)
    override fun toString(): String = value

    companion object {
        fun fromUuid(uuid: UUID): CanonicalUuid = CanonicalUuid(uuid.toString().lowercase(Locale.US))

        fun parse128(value: String): CanonicalUuid {
            val trimmed = value.trim()
            require(FULL_UUID.matches(trimmed)) { "A full 128-bit UUID is required" }
            return fromUuid(UUID.fromString(trimmed))
        }

        private val FULL_UUID = Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        )
    }
}

/** The only short-ID expansion boundary; matcher definitions immediately store the full UUID. */
private fun registeredSig16(assignedNumber: Int): CanonicalUuid {
    require(assignedNumber in 0x0000..0xffff) { "SIG assigned number must be 16-bit" }
    return CanonicalUuid.parse128(
        "0000%04x-0000-1000-8000-00805f9b34fb".format(Locale.US, assignedNumber),
    )
}

internal enum class MatchEvidenceKind {
    LOCAL_NAME_PATTERN,
    MANUFACTURER_COMPANY_ID,
    SERVICE_DATA,
    ADVERTISED_SERVICE,
    GATT_SERVICE,
    GATT_CHARACTERISTIC,
    PACKET_SHAPE,
}

internal enum class CollisionRisk { LOW, MEDIUM, HIGH }

internal data class MatchEvidenceFact(
    val kind: MatchEvidenceKind,
    val structuralValue: String,
    val weight: Int,
    val collisionRisk: CollisionRisk,
    val note: String,
) {
    init {
        require(weight > 0)
        require(structuralValue.isNotBlank())
        require(note.isNotBlank())
    }
}

internal data class MatchEvidence(val facts: List<MatchEvidenceFact>) {
    val totalWeight: Int = facts.sumOf { it.weight }
    val rankingWeight: Int = facts
        .filterNot { it.kind == MatchEvidenceKind.LOCAL_NAME_PATTERN }
        .sumOf { it.weight }
        .takeIf { it > 0 }
        ?: totalWeight
    val confidence: Double = (totalWeight / 100.0).coerceIn(0.0, 1.0)

    init {
        require(facts.isNotEmpty())
    }
}

internal enum class KnownBleService { FTMS, HRM, CSC, RSC, CPS, GLUCOSE }

internal data class PacketShapeRule(
    val lengths: IntRange? = null,
    val flagsMask: Long? = null,
    val flagsValue: Long? = null,
) {
    init {
        require((flagsMask == null) == (flagsValue == null)) {
            "Packet flags mask and value must be supplied together"
        }
    }

    fun matches(length: Int?, flags: Long?): Boolean {
        if (lengths != null && (length == null || length !in lengths)) return false
        if (flagsMask != null && (flags == null || flags and flagsMask != flagsValue)) return false
        return lengths != null || flagsMask != null
    }
}

internal data class MatchObservation(
    val localName: String? = null,
    val manufacturerCompanyIds: Set<Int> = emptySet(),
    val serviceDataUuids: Set<CanonicalUuid> = emptySet(),
    val advertisedServiceUuids: Set<CanonicalUuid> = emptySet(),
    val discoveredServiceUuids: Set<CanonicalUuid> = emptySet(),
    val discoveredCharacteristicUuids: Set<CanonicalUuid> = emptySet(),
    val packetLength: Int? = null,
    val packetFlags: Long? = null,
)

internal data class MatchDefinition(
    val candidateId: String,
    /** The service UUID present in the GATT database **after connecting**. */
    val serviceUuid: CanonicalUuid,
    val knownService: KnownBleService? = null,
    val preference: Int = 0,
    val localNamePatterns: List<Regex> = emptyList(),
    val manufacturerCompanyIds: Set<Int> = emptySet(),
    val personaCharacteristicUuids: Set<CanonicalUuid> = emptySet(),
    val packetShape: PacketShapeRule? = null,
    /**
     * The UUIDs this family puts in its **advertisement or scan response**, when they are not
     * the same as [serviceUuid].
     *
     * For every SIG profile the two coincide — an HRM advertises `0x180D` and serves `0x180D` —
     * which is why this distinction did not exist until a vendor family needed it. Concept2 is
     * the counterexample: a PM advertises the C2 **base** UUID `CE060000-…` in its scan
     * response and serves the Erg service `CE060030-…` after connecting. Matching an
     * advertisement against the post-connection UUID finds nothing, and a scan filter built
     * from it discards every PM5 before the app sees it.
     *
     * Empty means "advertises what it serves", which keeps every SIG definition unchanged.
     */
    val advertisedServiceUuids: Set<CanonicalUuid> = emptySet(),
    /**
     * Whether a match makes this device a **machine** candidate rather than a sensor.
     *
     * Previously inferred as `knownService == FTMS`, which silently excluded every proprietary
     * machine: a vendor family carries no [KnownBleService], so a PM5 could not be a machine
     * candidate even once discovered. Stated per definition so a new family declares its role
     * instead of inheriting one from an enum it does not belong to.
     */
    val machineRole: Boolean = false,
) {
    /** The UUIDs to match an advertisement against, and to build a scan filter from. */
    val effectiveAdvertisedUuids: Set<CanonicalUuid> =
        advertisedServiceUuids.ifEmpty { setOf(serviceUuid) }
}

internal data class RankedMatchCandidate(
    val candidateId: String,
    val serviceUuid: CanonicalUuid,
    val knownService: KnownBleService?,
    val evidence: MatchEvidence,
    val preference: Int,
    /** True for FTMS and for every proprietary machine family; false for sensors. */
    val machineRole: Boolean = false,
) {
    val rank: Int = evidence.rankingWeight
}

internal class DeviceMatcherRegistry private constructor(
    private val definitions: List<MatchDefinition>,
) {
    fun rankedCandidates(observation: MatchObservation): List<RankedMatchCandidate> =
        definitions.mapNotNull { definition ->
            val facts = evidenceFor(definition, observation)
            if (facts.isEmpty()) null else RankedMatchCandidate(
                candidateId = definition.candidateId,
                serviceUuid = definition.serviceUuid,
                knownService = definition.knownService,
                evidence = MatchEvidence(facts),
                preference = definition.preference,
                machineRole = definition.machineRole,
            )
        }.sortedWith(
            // `machineRole` is placed ahead of `preference` deliberately (bug fix,
            // 2026-08-20): a device that legitimately matches both a machine family and a
            // SIG sensor family with tied evidence weight must not have the machine
            // silently displaced by the sensor. This is exactly what happened to the FE-C
            // simulator's Trainer and Stationary Bike personas, which additionally
            // advertise the Incyclist Cycling Power Service compatibility overlay
            // (`FeCEquipmentType.includesCpsOverlay`, `FeCBlePeripheralAdvertiser`): both
            // `vendor.fec.ble` and `sig.cps` matched on a single 60-weight
            // ADVERTISED_SERVICE fact, and `sig.cps`'s higher `preference` (500 vs 120) won
            // the tie, so `startScanImpl`'s `candidates.firstOrNull()` picked the sensor
            // candidate and the device was catalogued as `machineCandidate = false` -
            // invisible to any machine-only device list, even though it is an FE-C machine
            // that merely also exposes a read-only power-sensor view of the same data for a
            // third-party client. The Treadmill and Rower personas carry no CPS overlay, so
            // they only ever produced the `vendor.fec.ble` candidate and were unaffected -
            // which is exactly the split reported ("only seeing treadmill and rower").
            // Machine role does not change which candidate has more or better evidence, so
            // this cannot invert an already-decided rank difference (e.g. a real standalone
            // CPS power meter with only CPS evidence still ranks CPS, since no machine
            // candidate exists for it to tie against); it only changes the tie-break among
            // candidates already judged equally strong.
            compareByDescending<RankedMatchCandidate> { it.rank }
                .thenByDescending { it.machineRole }
                .thenByDescending { it.preference }
                .thenBy { it.candidateId },
        )

    fun registeredServices(): List<CanonicalUuid> = definitions.map { it.serviceUuid }.distinct()

    /**
     * Every UUID any registered family puts on the air, for building the OS scan filter.
     *
     * **This must be derived, never hand-listed.** Until 2026-08-12 the filter came from five
     * hardcoded [KnownBleService] values in `btEngine.scan.kt`, so adding a vendor family to
     * this registry did nothing at all: Android discarded its advertisements before FitBridge's
     * scan callback ran, and no test noticed because every test worked on advertisements it had
     * constructed itself. Deriving it means registering a family is sufficient to find one.
     */
    fun scanFilterServiceUuids(): List<CanonicalUuid> =
        definitions.flatMap { it.effectiveAdvertisedUuids }.distinct()

    fun knownService(uuid: CanonicalUuid): KnownBleService? =
        definitions.firstOrNull { it.serviceUuid == uuid }?.knownService

    private fun evidenceFor(
        definition: MatchDefinition,
        observation: MatchObservation,
    ): List<MatchEvidenceFact> = buildList {
        val service = definition.serviceUuid

        // Advertisement and service-data evidence are matched against what the family puts on
        // the air, which for a vendor family is not what it serves. See
        // `MatchDefinition.advertisedServiceUuids`.
        definition.effectiveAdvertisedUuids
            .filter { it in observation.advertisedServiceUuids }
            .forEach { advertised ->
                add(
                    fact(MatchEvidenceKind.ADVERTISED_SERVICE, advertised.value, 60,
                        CollisionRisk.LOW, "Full advertised service UUID match"),
                )
            }
        definition.effectiveAdvertisedUuids
            .filter { it in observation.serviceDataUuids }
            .forEach { advertised ->
                add(
                    fact(MatchEvidenceKind.SERVICE_DATA, advertised.value, 70,
                        CollisionRisk.LOW, "Full service-data UUID match"),
                )
            }
        if (service in observation.discoveredServiceUuids) add(
            fact(MatchEvidenceKind.GATT_SERVICE, service.value, 75, CollisionRisk.LOW,
                "Post-discovery full service UUID match"),
        )
        definition.personaCharacteristicUuids
            .filter { it in observation.discoveredCharacteristicUuids }
            .forEach { characteristic ->
                add(
                    fact(MatchEvidenceKind.GATT_CHARACTERISTIC, characteristic.value, 90,
                        CollisionRisk.LOW, "Persona characteristic fingerprint match"),
                )
            }
        definition.manufacturerCompanyIds
            .intersect(observation.manufacturerCompanyIds)
            .forEach { companyId ->
                add(
                    fact(MatchEvidenceKind.MANUFACTURER_COMPANY_ID, "company:$companyId", 35,
                        CollisionRisk.MEDIUM, "Company identifier may cover several product families"),
                )
            }
        val localName = observation.localName.orEmpty()
        definition.localNamePatterns.firstOrNull { it.containsMatchIn(localName) }?.let { pattern ->
            add(
                fact(MatchEvidenceKind.LOCAL_NAME_PATTERN, "pattern:${pattern.pattern}", 10,
                    CollisionRisk.HIGH, "Local names are mutable and may collide"),
            )
        }
        definition.packetShape?.takeIf {
            it.matches(observation.packetLength, observation.packetFlags)
        }?.let {
            add(
                fact(
                    MatchEvidenceKind.PACKET_SHAPE,
                    "length:${observation.packetLength};flags:${observation.packetFlags?.toString(16)}",
                    45,
                    CollisionRisk.MEDIUM,
                    "Packet shape is supporting evidence, not unique identity",
                ),
            )
        }
    }

    private fun fact(
        kind: MatchEvidenceKind,
        value: String,
        weight: Int,
        risk: CollisionRisk,
        note: String,
    ) = MatchEvidenceFact(kind, value, weight, risk, note)

    companion object {
        fun of(definitions: List<MatchDefinition>): DeviceMatcherRegistry {
            require(definitions.isNotEmpty())
            require(definitions.map { it.candidateId }.distinct().size == definitions.size) {
                "Matcher candidate IDs must be unique"
            }
            return DeviceMatcherRegistry(definitions)
        }

        fun standard(): DeviceMatcherRegistry = of(
            listOf(
                sigDefinition(
                    "sig.ftms", 0x1826, 0x2acd..0x2ad2, KnownBleService.FTMS, 600,
                    machineRole = true,
                ),
                sigDefinition("sig.cps", 0x1818, 0x2a63..0x2a63, KnownBleService.CPS, 500),
                sigDefinition(
                    "sig.hrm",
                    0x180d,
                    0x2a37..0x2a37,
                    KnownBleService.HRM,
                    400,
                    listOf(Regex("polar|wahoo|garmin|coospo|tickr|hrm|heart", RegexOption.IGNORE_CASE)),
                ),
                sigDefinition("sig.rsc", 0x1814, 0x2a53..0x2a53, KnownBleService.RSC, 300),
                sigDefinition("sig.csc", 0x1816, 0x2a5b..0x2a5b, KnownBleService.CSC, 200),
                concept2Definition(),
                precorDefinition(),
                schwinnX70Definition(),
                fecDefinition(),
                sigDefinition("sig.glucose", 0x1808, 0x2a18..0x2a18, KnownBleService.GLUCOSE, 100),
            ),
        )

        /**
         * Concept2 PM, the first non-SIG definition in the registry (plan §5a).
         *
         * `MatchDefinition` already carries an arbitrary [CanonicalUuid], so a vendor family
         * needs no new machinery — only a constructor that does not assume a 16-bit assigned
         * number. Every UUID here is expanded from the base quoted in Concept2's published
         * specification; see `docs/evidence/phase-5/2026-08-10-concept2-pm5-uuid-resolution.md`.
         *
         * **Preference sits below `sig.csc`, not above `sig.ftms`.** A PM5 advertising both
         * services must still be *matched* as a Concept2 device — that is what makes the
         * proprietary path available at all — but which adapter drives the link is D6's
         * question, answered by `AdapterLivenessCoordinator` ranking. Preference here orders
         * matcher candidates; it is not a second, competing statement of that decision.
         *
         * The persona characteristics are the four FitBridge consumes plus the multiplexed
         * characteristic. Any of them is a decisive fingerprint: no other vendor uses this base.
         *
         * **No local-name pattern, deliberately.** A `^pm[0-9]` pattern was tried and removed.
         * It contributes nothing when structural evidence is present — every PM exposes the Erg
         * service — so the only case it changes is a device advertising *nothing* Concept2 while
         * calling itself `PM5`, which is precisely the case where a match must not be claimed.
         * §0.7 says to prefer structural evidence over a name; for a family whose fingerprint is
         * this unmistakable, the name is not weak evidence but no evidence.
         *
         * The name still selects the *persona* once a match exists — RowErg, SkiErg, BikeErg —
         * which is `concept2PersonaFor`'s job and a different question from identity.
         */
        private fun concept2Definition() = MatchDefinition(
            candidateId = "vendor.concept2.pm",
            serviceUuid = CanonicalUuid.fromUuid(BluetoothIds.UUID_C2_ERG_SERVICE),
            // **Both, deliberately — this is V1's resolution applied to a second family.**
            //
            // Concept2's specification says the base UUID `CE060000-…` is what a PM puts in its
            // scan response and what an application should filter on, and FitBridge's own PM5
            // peripheral simulator advertises exactly that (`Concept2Pm5AdvertisingPlan`). That
            // is the authority and the corroboration, and it is why the base UUID is here at
            // all: without it FitBridge could not discover the device FitBridge was itself
            // advertising.
            //
            // The Erg service stays in the advertised set too, because TMIW matches a PM on
            // `0030` from its advertisement digest and there is no evidence that *no* PM
            // firmware ever advertises it. §12.1 V11 resolves the disagreement in the
            // specification's favour for the question "what must FitBridge filter on", not for
            // the stronger claim "the Erg service never appears on the air", which nothing
            // establishes. Accepting both costs one extra scan-filter entry and cannot produce
            // a false match: both UUIDs are exclusively Concept2's.
            //
            // Narrowing this to the base UUID alone would have been a guess dressed as rigour —
            // and three of this repository's own tests said so immediately.
            advertisedServiceUuids = setOf(
                CanonicalUuid.fromUuid(BluetoothIds.UUID_C2_DISCOVERY_SERVICE),
                CanonicalUuid.fromUuid(BluetoothIds.UUID_C2_ERG_SERVICE),
            ),
            machineRole = true,
            knownService = null,
            preference = 150,
            personaCharacteristicUuids = linkedSetOf(
                CanonicalUuid.fromUuid(BluetoothIds.UUID_C2_GENERAL_STATUS),
                CanonicalUuid.fromUuid(BluetoothIds.UUID_C2_ADDITIONAL_STATUS_1),
                CanonicalUuid.fromUuid(BluetoothIds.UUID_C2_ADDITIONAL_STATUS_2),
                CanonicalUuid.fromUuid(BluetoothIds.UUID_C2_ADDITIONAL_STROKE_DATA),
                CanonicalUuid.fromUuid(BluetoothIds.UUID_C2_MULTIPLEXED_INFORMATION),
            ),
        )

        /**
         * Precor Spinner Chrono Power (plan §5b).
         *
         * **No local-name pattern, for the same reason [concept2Definition] has none — but
         * reached by a different route, and it is worth writing down which.**
         *
         * The obvious argument for a `^CHRONO` pattern here is that this service UUID is not
         * exclusively Precor's: QZ's `truetreadmill` reaches for the identical
         * `a026ee07`/`a026e01d` pair for a True brand treadmill. That is a real collision — two
         * unrelated machines behind one service — and a name looks like the natural
         * tie-breaker.
         *
         * It is not, for two reasons. First, `evidenceFor` produces a candidate from *any*
         * non-empty evidence, so adding the pattern would make a device named `Chrono Watch`
         * advertising nothing at all a Precor Chrono candidate at weight 10. §8.9 names
         * exactly that — a name-restricted generic profile — as a behaviour not to port, and
         * TMIW's name-prefix matching for this family is what §5b calls "far too weak".
         * Second, the name cannot discriminate the case that actually matters: a True treadmill
         * advertises neither `CHRONO` nor anything else FitBridge could enumerate, so the
         * pattern would decide nothing about the collision it was added for.
         *
         * **What does discriminate is the frame prefix `53 59 16`**, checked per notification
         * in [PrecorMeasurement.isChronoFrame]. A True treadmill on this service sends 4-, 16-,
         * 18- or 19-octet frames with no such prefix, so every one is rejected and the adapter
         * produces nothing rather than something wrong. That is the correct outcome: §0.7a's
         * liveness path then demotes a machine adapter that never delivers, which is a
         * mechanism that already exists and needs no help from a name.
         *
         * The prefix cannot live in this registry because a matcher runs on advertisement and
         * discovery data, before any notification exists. So identity is claimed on the service
         * and validated per frame, with nothing in between pretending to add certainty.
         */
        private fun precorDefinition() = MatchDefinition(
            candidateId = "vendor.precor.chrono",
            serviceUuid = CanonicalUuid.fromUuid(BluetoothIds.UUID_PRECOR_SERVICE),
            // User-approved tier-S evidence substitution (2026-08-12): the official Precor
            // Spinning Connect client discovers and consumes FitBridge's Chrono simulator when
            // it advertises this served service UUID. That establishes a valid discovery route
            // for Phase 5b and closes Q32, without claiming that every physical firmware emits
            // an identical advertising payload or promoting the central adapter to tier H.
            machineRole = true,
            knownService = null,
            preference = 140,
            personaCharacteristicUuids = linkedSetOf(
                CanonicalUuid.fromUuid(BluetoothIds.UUID_PRECOR_MEASUREMENT),
            ),
        )

        /**
         * Schwinn x70 — 170 / 270 / 570 (plan §7).
         *
         * **The service is both advertised and served, so no `advertisedServiceUuids` split is
         * needed.** QZ issue #1365's advertisement screenshot shows
         * `3bf58980-3a2f-11e6-9011-0002a5d5c51b` in the incomplete 128-bit service list, and the
         * connected GATT places every characteristic under that same service. This is the ordinary
         * case; Concept2 needed the split because its specification advertises a base UUID it does
         * not serve, and that is the exception rather than the pattern.
         *
         * **No local-name pattern, and here the temptation is stronger than usual.** All three
         * consoles report the same generic identity, `SCHWINN 170/270`, so a name looks like free
         * evidence. It is not: `evidenceFor` produces a candidate from any non-empty evidence, so
         * the pattern would make anything merely *named* Schwinn a candidate for a proprietary
         * vendor protocol — §8.9's name-restricted generic profile. The service UUID is
         * exclusively this family's and settles identity on its own.
         *
         * **The name also cannot do the job it would be added for.** The interesting question is
         * not "is this a Schwinn" but "is this a 170 or a 270", and the console answers
         * `SCHWINN 170/270` to both. That distinction is only observable from which characteristics
         * notify — 17 bytes on [BluetoothIds.UUID_X70_MEASUREMENT] for a 270, paired 20- and
         * 14-byte streams for a 170 — which is a post-subscription fact, not a matcher input.
         *
         * All four notify/write characteristics are listed as persona characteristics so that a
         * device exposing only the 270 set and one exposing only the 170 set both match. Requiring
         * all four would reject both real consoles.
         */
        private fun schwinnX70Definition() = MatchDefinition(
            candidateId = "vendor.schwinn.x70",
            serviceUuid = CanonicalUuid.fromUuid(BluetoothIds.UUID_X70_SERVICE),
            machineRole = true,
            knownService = null,
            preference = 130,
            personaCharacteristicUuids = linkedSetOf(
                CanonicalUuid.fromUuid(BluetoothIds.UUID_X70_MEASUREMENT),
                CanonicalUuid.fromUuid(BluetoothIds.UUID_X70_SUPPLEMENTAL),
                CanonicalUuid.fromUuid(BluetoothIds.UUID_X70_SIBLING),
                CanonicalUuid.fromUuid(BluetoothIds.UUID_X70_CONTROL),
            ),
        )

        /**
         * FE-C over BLE, generic (plan §6 of `docs/fe-c-ble_central_implementation.md`).
         *
         * No local-name pattern: unlike Concept2/Precor/Schwinn x70, FE-C over BLE has no
         * fixed device-name convention across vendors, and none is needed — the FEC1 service
         * UUID is proprietary enough on its own to be structural evidence (plan §6.1). Serves
         * what it advertises (Kinomap's own BLE capture of the FitBridge FE-C simulator, and
         * the Tacx spec's example advertisement, both show only FEC1 in the ad payload), so
         * [MatchDefinition.advertisedServiceUuids] is left empty like every SIG definition.
         */
        private fun fecDefinition() = MatchDefinition(
            candidateId = "vendor.fec.ble",
            serviceUuid = CanonicalUuid.fromUuid(BluetoothIds.UUID_FEC_SERVICE),
            machineRole = true,
            knownService = null,
            preference = 120,
            personaCharacteristicUuids = linkedSetOf(
                CanonicalUuid.fromUuid(BluetoothIds.UUID_FEC_MEASUREMENT),
                CanonicalUuid.fromUuid(BluetoothIds.UUID_FEC_CONTROL),
            ),
        )

        private fun sigDefinition(
            id: String,
            serviceAssignedNumber: Int,
            characteristicAssignedNumbers: IntRange,
            knownService: KnownBleService,
            preference: Int,
            names: List<Regex> = emptyList(),
            machineRole: Boolean = false,
        ) = MatchDefinition(
            candidateId = id,
            serviceUuid = registeredSig16(serviceAssignedNumber),
            knownService = knownService,
            preference = preference,
            localNamePatterns = names,
            // A SIG profile advertises the service it serves, so `advertisedServiceUuids` stays
            // empty and defaults to `serviceUuid`. Stated once here rather than repeated per
            // definition.
            machineRole = machineRole,
            personaCharacteristicUuids = characteristicAssignedNumbers
                .mapTo(linkedSetOf(), ::registeredSig16),
        )
    }
}
