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
    val serviceUuid: CanonicalUuid,
    val knownService: KnownBleService? = null,
    val preference: Int = 0,
    val localNamePatterns: List<Regex> = emptyList(),
    val manufacturerCompanyIds: Set<Int> = emptySet(),
    val personaCharacteristicUuids: Set<CanonicalUuid> = emptySet(),
    val packetShape: PacketShapeRule? = null,
)

internal data class RankedMatchCandidate(
    val candidateId: String,
    val serviceUuid: CanonicalUuid,
    val knownService: KnownBleService?,
    val evidence: MatchEvidence,
    val preference: Int,
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
            )
        }.sortedWith(
            compareByDescending<RankedMatchCandidate> { it.rank }
                .thenByDescending { it.preference }
                .thenBy { it.candidateId },
        )

    fun registeredServices(): List<CanonicalUuid> = definitions.map { it.serviceUuid }.distinct()

    fun knownService(uuid: CanonicalUuid): KnownBleService? =
        definitions.firstOrNull { it.serviceUuid == uuid }?.knownService

    private fun evidenceFor(
        definition: MatchDefinition,
        observation: MatchObservation,
    ): List<MatchEvidenceFact> = buildList {
        val service = definition.serviceUuid
        if (service in observation.advertisedServiceUuids) add(
            fact(MatchEvidenceKind.ADVERTISED_SERVICE, service.value, 60, CollisionRisk.LOW,
                "Full advertised service UUID match"),
        )
        if (service in observation.serviceDataUuids) add(
            fact(MatchEvidenceKind.SERVICE_DATA, service.value, 70, CollisionRisk.LOW,
                "Full service-data UUID match"),
        )
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
                sigDefinition("sig.ftms", 0x1826, 0x2acd..0x2ad2, KnownBleService.FTMS, 600),
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

        private fun sigDefinition(
            id: String,
            serviceAssignedNumber: Int,
            characteristicAssignedNumbers: IntRange,
            knownService: KnownBleService,
            preference: Int,
            names: List<Regex> = emptyList(),
        ) = MatchDefinition(
            candidateId = id,
            serviceUuid = registeredSig16(serviceAssignedNumber),
            knownService = knownService,
            preference = preference,
            localNamePatterns = names,
            personaCharacteristicUuids = characteristicAssignedNumbers
                .mapTo(linkedSetOf(), ::registeredSig16),
        )
    }
}
