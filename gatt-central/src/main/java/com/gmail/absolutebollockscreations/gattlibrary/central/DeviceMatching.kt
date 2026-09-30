package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi

import com.gmail.absolutebollockscreations.gattlibrary.core.ActivityType
import java.util.Locale
import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.Concept2Uuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.FecBleUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.FlywheelUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.KayakFirstUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.PrecorUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SchwinnX70Uuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.WahooUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.catalog.MachineFamilyIds
import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.KeiserMSeriesSpec

internal const val KAYAK_FIRST_MANUFACTURER_ID = 0xA759

/** The captured value is eight bytes beginning 88 A0; canonical payloads include the company ID. */
internal fun isKayakFirstManufacturerPayload(payload: ManufacturerPayload): Boolean {
    val bytes = payload.copyBytes()
    return bytes.size == 10 &&
        (bytes[0].toInt() and 0xff) == 0x59 &&
        (bytes[1].toInt() and 0xff) == 0xA7 &&
        (bytes[2].toInt() and 0xff) == 0x88 &&
        (bytes[3].toInt() and 0xff) == 0xA0
}

internal fun isKayakFirstAddressCorrelation(
    address: String,
    payload: ManufacturerPayload,
): Boolean {
    if (!isKayakFirstManufacturerPayload(payload)) return false
    val addressBytes = address.split(':').takeIf { it.size == 6 }
        ?.map { octet -> octet.toIntOrNull(16)?.takeIf { it in 0..0xff } }
        ?.takeIf { it.none { octet -> octet == null } }
        ?.map { requireNotNull(it) }
        ?: return false
    val suffix = payload.copyBytes().copyOfRange(4, 10).map { it.toInt() and 0xff }
    return suffix == addressBytes.reversed()
}

/** Matcher-layer UUID storage. Values are always lowercase canonical 128-bit UUID strings. */
@JvmInline
@GattLibraryInternalApi
value class CanonicalUuid private constructor(val value: String) {
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

@GattLibraryInternalApi
enum class MatchEvidenceKind {
    LOCAL_NAME_PATTERN,
    MANUFACTURER_COMPANY_ID,
    SERVICE_DATA,
    ADVERTISED_SERVICE,
    GATT_SERVICE,
    GATT_CHARACTERISTIC,
    PACKET_SHAPE,

    /**
     * The manufacturer-data payload itself satisfied a family's structural rule.
     *
     * Deliberately distinct from [PACKET_SHAPE], which describes a GATT notification's length and
     * flag bits. A conjunction that named only "shape" could be satisfied by either, and an
     * advertisement-only family has no notification to shape-check — its whole identity is the
     * payload, so the requirement has to be able to say exactly that.
     */
    MANUFACTURER_PAYLOAD,
    ADDRESS_CORRELATION,
}

/**
 * How a family delivers its telemetry once selected.
 *
 * [GATT] is every family that existed before phase 11: FitBridge connects, discovers services and
 * subscribes. [ADVERTISEMENT_ONLY] never accepts a connection at all — its metrics arrive in
 * repeated advertisements — so nothing downstream may build a `ConnectionContext`, offer a
 * disconnect affordance, or present it as connected. Stated on the definition rather than inferred
 * from a missing service UUID, because "we have not identified a service yet" and "there is no
 * service" are different claims and only the second is a protocol fact.
 */
@GattLibraryInternalApi
enum class MatchTransport { GATT, ADVERTISEMENT_ONLY }

/**
 * One advertisement's manufacturer-data payload, in canonical form.
 *
 * ## The company ID belongs in the buffer
 *
 * Android's `ScanRecord` splits manufacturer data into a company ID key and a value, so its
 * callback hands over a payload whose first two documented octets are missing. Vendor documents,
 * permissive reference implementations and captured corpora all describe the buffer *including*
 * the little-endian company ID. [fromAndroidScanRecord] performs that normalization, and it is the
 * only constructor the scan layer uses, so a rule written against the documented layout cannot be
 * handed a payload two bytes short of it.
 *
 * ## Privacy
 *
 * A manufacturer payload can carry a serial number, a session identifier, or a rider's live
 * telemetry. [toString] therefore renders the length and never the bytes, so a payload cannot
 * reach a log line, a crash report or a diagnostic dump by being interpolated into a string. The
 * bytes are copied in and out so no caller can retain a reference into a scan buffer, and nothing
 * here is persisted: an observation lives for the duration of one match.
 */
@GattLibraryInternalApi
class ManufacturerPayload(bytes: ByteArray) {

    private val bytes: ByteArray = bytes.copyOf()

    val size: Int get() = bytes.size

    /** A defensive copy. The matcher never hands out the array it holds. */
    fun copyBytes(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other || (other is ManufacturerPayload && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()

    /** Length only, never content. See the privacy note on the class. */
    override fun toString(): String = "ManufacturerPayload(${bytes.size} bytes)"

    companion object {
        /** Canonicalize Android's split representation into the documented buffer. */
        fun fromAndroidScanRecord(companyId: Int, value: ByteArray): ManufacturerPayload {
            require(companyId in 0..0xffff) { "Company identifier must fit uint16" }
            val canonical = ByteArray(value.size + 2)
            canonical[0] = (companyId and 0xff).toByte()
            canonical[1] = ((companyId shr 8) and 0xff).toByte()
            value.copyInto(canonical, destinationOffset = 2)
            return ManufacturerPayload(canonical)
        }
    }
}

/**
 * A family's structural test for a canonical manufacturer payload.
 *
 * Named rather than inlined as a lambda so the rule a definition applies is a referenceable
 * function with its own tests, and so the definition reads as a claim about the protocol instead
 * of as an anonymous predicate.
 */
@GattLibraryInternalApi
fun interface ManufacturerPayloadRule {
    fun matches(companyId: Int, payload: ManufacturerPayload): Boolean
}

/** Optional, transient correlation evidence. Implementations must never expose the address. */
@GattLibraryInternalApi
fun interface ManufacturerAddressRule {
    fun matches(address: String, payload: ManufacturerPayload): Boolean
}

/**
 * A filter FitBridge asks the OS radio to apply, before any advertisement reaches the app.
 *
 * Was a bare list of service UUIDs until phase 11.4, which is a shape an advertisement-only
 * family cannot express: a Keiser M3 puts no service UUID on the air at all, so a UUID-derived
 * filter discards it in hardware and no amount of correct software matching can recover it.
 * Typed so a definition contributes the filter its own transport needs.
 */
@GattLibraryInternalApi
sealed interface HardwareScanFilterSpec {
    data class ServiceUuid(val uuid: CanonicalUuid) : HardwareScanFilterSpec

    data class ManufacturerId(val companyId: Int) : HardwareScanFilterSpec {
        init {
            require(companyId in 0..0xffff) { "Company identifier must fit uint16" }
        }
    }
}

@GattLibraryInternalApi
enum class CollisionRisk { LOW, MEDIUM, HIGH }

@GattLibraryInternalApi
data class MatchEvidenceFact(
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

@GattLibraryInternalApi
data class MatchEvidence(val facts: List<MatchEvidenceFact>) {
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

@GattLibraryInternalApi
enum class KnownBleService { FTMS, HRM, CSC, RSC, CPS, GLUCOSE }

@GattLibraryInternalApi
data class PacketShapeRule(
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

@GattLibraryInternalApi
data class MatchObservation(
    val localName: String? = null,
    val deviceAddress: String? = null,
    val manufacturerCompanyIds: Set<Int> = emptySet(),
    val serviceDataUuids: Set<CanonicalUuid> = emptySet(),
    val advertisedServiceUuids: Set<CanonicalUuid> = emptySet(),
    val discoveredServiceUuids: Set<CanonicalUuid> = emptySet(),
    val discoveredCharacteristicUuids: Set<CanonicalUuid> = emptySet(),
    val packetLength: Int? = null,
    val packetFlags: Long? = null,
    /**
     * Canonical manufacturer payloads keyed by company identifier.
     *
     * Populated only where a registered family actually inspects payloads; see the privacy note
     * on [ManufacturerPayload]. Empty for every observation predating phase 11.4, which is why
     * no existing family's ranking can move.
     */
    val manufacturerPayloads: Map<Int, ManufacturerPayload> = emptyMap(),
)

@GattLibraryInternalApi
data class MatchDefinition(
    val candidateId: String,
    /**
     * The service UUID present in the GATT database **after connecting**, or null for an
     * advertisement-only family that never exposes one.
     *
     * Null means "this protocol has no service", not "not identified yet". Phase 11 forbids
     * inventing a Keiser UUID precisely because a reserved placeholder here would propagate into
     * scan filters, the discovery catalog and diagnostic capture as if it were a real identity.
     */
    val serviceUuid: CanonicalUuid?,
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
    /**
     * Optional conjunction gates. At least one alternative must be wholly represented by the
     * observed facts. This lets a CPS-advertising KICKR be admitted on CPS + supporting name
     * without allowing either weak fact alone to create a proprietary machine candidate.
     */
    val requiredEvidenceAlternatives: List<Set<MatchEvidenceKind>> = emptyList(),
    /** How this family delivers telemetry once selected. See [MatchTransport]. */
    val transport: MatchTransport = MatchTransport.GATT,
    /**
     * Structural test for a canonical manufacturer payload, when this family has one.
     *
     * Supplying a rule is what lets a definition treat the payload as identity rather than as an
     * opaque blob behind a company identifier. A company ID names a company, not a product.
     */
    val manufacturerPayloadRule: ManufacturerPayloadRule? = null,
    val manufacturerAddressRule: ManufacturerAddressRule? = null,
    /**
     * The activity this family is, when that is knowable from the advertisement alone.
     *
     * FTMS machines carry it in service data and it is decoded per advertisement. A Keiser M3 is
     * an indoor bike by construction, so the row can say so before anything is selected. Optimistic
     * in the same sense as the FTMS service-data value: a claim from the air, superseded by
     * whatever a connection later proves — except that here nothing ever connects, so it stands.
     */
    val advertisedActivityType: ActivityType? = null,
) {
    init {
        require(serviceUuid != null || transport == MatchTransport.ADVERTISEMENT_ONLY) {
            "A GATT family must name the service UUID it serves"
        }
        require(serviceUuid != null || advertisedServiceUuids.isEmpty()) {
            "An advertisement-only family must not claim a service UUID by the back door"
        }
        require(transport == MatchTransport.GATT || manufacturerPayloadRule != null) {
            "An advertisement-only family must identify itself by payload structure"
        }
    }

    /** The UUIDs to match an advertisement against, and to build a scan filter from. */
    val effectiveAdvertisedUuids: Set<CanonicalUuid> =
        advertisedServiceUuids.ifEmpty { setOfNotNull(serviceUuid) }

    /**
     * The OS-level filters this family needs in order to be discoverable at all.
     *
     * A manufacturer-ID filter is contributed only by an advertisement-only family. A GATT family
     * is already reachable through its service UUID, and adding its company identifier would widen
     * the radio filter to every product that company ships — a hardware filter exists to stop the
     * radio discarding what FitBridge needs, not to decide identity.
     *
     * An empty list here means the family can never be discovered however correct its adapter is,
     * which is why [DeviceMatcherRegistry.of] refuses to register one.
     */
    val hardwareScanFilterSpecs: List<HardwareScanFilterSpec> = buildList {
        effectiveAdvertisedUuids.forEach { add(HardwareScanFilterSpec.ServiceUuid(it)) }
        if (transport == MatchTransport.ADVERTISEMENT_ONLY) {
            manufacturerCompanyIds.forEach { add(HardwareScanFilterSpec.ManufacturerId(it)) }
        }
    }
}

@GattLibraryInternalApi
data class RankedMatchCandidate(
    val candidateId: String,
    /** Null for an advertisement-only family, which serves no GATT service. */
    val serviceUuid: CanonicalUuid?,
    val knownService: KnownBleService?,
    val evidence: MatchEvidence,
    val preference: Int,
    /** True for FTMS and for every proprietary machine family; false for sensors. */
    val machineRole: Boolean = false,
    /** How this candidate would deliver telemetry. See [MatchTransport]. */
    val transport: MatchTransport = MatchTransport.GATT,
    /** The activity claimed by the advertisement, when the family declares one. */
    val advertisedActivityType: ActivityType? = null,
) {
    val rank: Int = evidence.rankingWeight
}

@GattLibraryInternalApi
class DeviceMatcherRegistry private constructor(
    private val definitions: List<MatchDefinition>,
) {
    fun rankedCandidates(observation: MatchObservation): List<RankedMatchCandidate> =
        definitions.mapNotNull { definition ->
            val facts = evidenceFor(definition, observation)
            val observedKinds = facts.mapTo(mutableSetOf()) { it.kind }
            val satisfiesGate = definition.requiredEvidenceAlternatives.isEmpty() ||
                definition.requiredEvidenceAlternatives.any(observedKinds::containsAll)
            if (facts.isEmpty() || !satisfiesGate) null else RankedMatchCandidate(
                candidateId = definition.candidateId,
                serviceUuid = definition.serviceUuid,
                knownService = definition.knownService,
                evidence = MatchEvidence(facts),
                preference = definition.preference,
                machineRole = definition.machineRole,
                transport = definition.transport,
                advertisedActivityType = definition.advertisedActivityType,
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

    fun registeredServices(): List<CanonicalUuid> =
        definitions.mapNotNull { it.serviceUuid }.distinct()

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

    /**
     * Every hardware filter the registered families need, typed by what the radio must match on.
     *
     * Service UUIDs come from every family that puts one on the air. Manufacturer-ID filters come
     * **only** from advertisement-only families: a GATT family is already reachable through its
     * service UUID, and adding its company identifier would widen the radio filter to every
     * product that company ships, which is the opposite of what a hardware filter is for.
     *
     * Derived, never hand-listed, for the same reason [scanFilterServiceUuids] is: a family that
     * is registered but absent here is invisible no matter how correct its adapter is.
     */
    fun hardwareScanFilterSpecs(): List<HardwareScanFilterSpec> =
        definitions.flatMap { it.hardwareScanFilterSpecs }.distinct()

    fun knownService(uuid: CanonicalUuid): KnownBleService? =
        definitions.firstOrNull { it.serviceUuid != null && it.serviceUuid == uuid }?.knownService

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
        if (service != null && service in observation.discoveredServiceUuids) add(
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
        // Weighted above a company identifier and below a full UUID. A payload that satisfies a
        // documented layout is far stronger evidence than the company that sent it, and weaker
        // than a 128-bit service UUID, which is unique by construction rather than by structure.
        // The structural value records the length only; see the privacy note on ManufacturerPayload.
        definition.manufacturerPayloadRule?.let { rule ->
            definition.manufacturerCompanyIds
                .mapNotNull { companyId ->
                    observation.manufacturerPayloads[companyId]?.let { companyId to it }
                }
                .filter { (companyId, payload) -> rule.matches(companyId, payload) }
                .forEach { (companyId, payload) ->
                    add(
                        fact(
                            MatchEvidenceKind.MANUFACTURER_PAYLOAD,
                            "company:$companyId;payloadBytes:${payload.size}",
                            55,
                            CollisionRisk.LOW,
                            "Payload satisfies this family's documented structural layout",
                        ),
                    )
                }
        }
        definition.manufacturerAddressRule?.let { rule ->
            val address = observation.deviceAddress
            if (address != null) {
                definition.manufacturerCompanyIds
                    .mapNotNull(observation.manufacturerPayloads::get)
                    .firstOrNull { payload -> rule.matches(address, payload) }
                    ?.let {
                        add(
                            fact(
                                MatchEvidenceKind.ADDRESS_CORRELATION,
                                "manufacturer-address-reversal:true",
                                15,
                                CollisionRisk.MEDIUM,
                                "Manufacturer suffix reverses the transient exposed address",
                            ),
                        )
                    }
            }
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
            // The 2026-08-12 discovery defect, now a construction-time invariant rather than a
            // test: a family absent from the OS scan filter is invisible however complete its
            // adapter is, and the failure is silent because nothing ever reaches the callback.
            definitions.firstOrNull { it.hardwareScanFilterSpecs.isEmpty() }?.let {
                error("${it.candidateId} contributes no hardware scan filter and can never be discovered")
            }
            return DeviceMatcherRegistry(definitions)
        }

        fun standard(): DeviceMatcherRegistry = of(
            listOf(
                sigDefinition(
                    MachineFamilyIds.SIG_FTMS, 0x1826, 0x2acd..0x2ad2, KnownBleService.FTMS, 600,
                    machineRole = true,
                ),
                sigDefinition(MachineFamilyIds.SIG_CPS, 0x1818, 0x2a63..0x2a63, KnownBleService.CPS, 500),
                wahooKickrDefinition(),
                rideOnDefinition(MachineFamilyIds.WAHOO_RIDEON_ORIGINAL, WahooUuids.UUID_WAHOO_RIDEON_SERVICE),
                rideOnDefinition(MachineFamilyIds.WAHOO_RIDEON_CORE2, WahooUuids.UUID_WAHOO_RIDEON_CORE2_SERVICE),
                sigDefinition(
                    MachineFamilyIds.SIG_HRM,
                    0x180d,
                    0x2a37..0x2a37,
                    KnownBleService.HRM,
                    400,
                    listOf(Regex("polar|wahoo|garmin|coospo|tickr|hrm|heart", RegexOption.IGNORE_CASE)),
                ),
                sigDefinition(MachineFamilyIds.SIG_RSC, 0x1814, 0x2a53..0x2a53, KnownBleService.RSC, 300),
                sigDefinition(MachineFamilyIds.SIG_CSC, 0x1816, 0x2a5b..0x2a5b, KnownBleService.CSC, 200),
                concept2Definition(),
                precorDefinition(),
                schwinnX70Definition(),
                flywheelDefinition(),
                kayakFirstDefinition(),
                fecDefinition(),
                keiserMSeriesDefinition(),
                sigDefinition(MachineFamilyIds.SIG_GLUCOSE, 0x1808, 0x2a18..0x2a18, KnownBleService.GLUCOSE, 100),
            ),
        )

        /**
         * The first advertisement-only family: a Keiser M-series bike, which never connects.
         *
         * ## Three facts, and none of them is sufficient alone
         *
         * The local name begins `M3`, which is short, mutable, and shared with anything else
         * whose owner typed "M3" into a name field. The company identifier is `0x0102`, which
         * names Keiser and every product Keiser ships. The manufacturer payload satisfies the
         * documented M-series layout — magic prefix, and a total length the firmware minor byte
         * itself predicts — which is structural but is still only nineteen bytes and could in
         * principle be coincidence.
         *
         * The conjunction is the match, and it is declared as a single required alternative so
         * no pair of them can admit a device. That matters more here than for a GATT family: a
         * wrong GATT match is discovered on connect, when service discovery finds nothing it
         * expects. Nothing ever connects to this one, so a false admission has no later
         * correction — it would sit in the machine list and produce decoded nonsense.
         *
         * ## No service UUID, and no placeholder for one
         *
         * There is no Keiser service, so [serviceUuid] is null and the family contributes nothing
         * to the service-UUID scan filter. Its radio reachability comes from a manufacturer-ID
         * filter instead; see [DeviceMatcherRegistry.hardwareScanFilterSpecs]. A reserved UUID
         * here would have been easier and would have made every downstream diagnostic lie about
         * the transport.
         *
         * ## Preference
         *
         * Placed below FTMS and the SIG sensor profiles. A device that genuinely advertises FTMS
         * *and* satisfies this whole conjunction is not something the captures show existing, and
         * if one appears the connectable profile is the better machine — it can be controlled and
         * verified on connect. Ranking already decides that: the required conjunction here scores
         * name plus company plus payload, so preference only ever breaks a tie.
         */
        private fun keiserMSeriesDefinition() = MatchDefinition(
            candidateId = MachineFamilyIds.KEISER_M_SERIES,
            serviceUuid = null,
            knownService = null,
            preference = 150,
            localNamePatterns = listOf(Regex("^M3", RegexOption.IGNORE_CASE)),
            manufacturerCompanyIds = setOf(KeiserMSeriesSpec.COMPANY_ID),
            machineRole = true,
            transport = MatchTransport.ADVERTISEMENT_ONLY,
            // The decoder's own predicate, not a second copy of it. A structural rule that could
            // drift from the parser would admit devices the parser then rejects, which reads to a
            // user as a machine that appears in the list and never produces a number.
            manufacturerPayloadRule = ManufacturerPayloadRule { companyId, payload ->
                companyId == KeiserMSeriesSpec.COMPANY_ID &&
                    KeiserMSeriesMeasurement.isKeiserField(payload.copyBytes())
            },
            advertisedActivityType = ActivityType.BIKING,
            requiredEvidenceAlternatives = listOf(
                setOf(
                    MatchEvidenceKind.LOCAL_NAME_PATTERN,
                    MatchEvidenceKind.MANUFACTURER_COMPANY_ID,
                    MatchEvidenceKind.MANUFACTURER_PAYLOAD,
                ),
            ),
        )

        /**
         * Provisional scan admission and post-connect identity for legacy KICKR control.
         *
         * A SNAP capture advertises CPS without either Wahoo marker, so requiring a marker in
         * the Android scan filter would make the supported trainer invisible. CPS alone is also
         * the ordinary power-meter profile and must remain a sensor. The pre-connect alternative
         * therefore requires CPS plus the supporting `KICKR` name. That name never activates
         * control: [WahooKickrProtocolHandler] separately requires the full parent/property/marker
         * fingerprint from the discovered GATT database.
         */
        private fun wahooKickrDefinition() = MatchDefinition(
            candidateId = MachineFamilyIds.WAHOO_KICKR_LEGACY,
            serviceUuid = CanonicalUuid.fromUuid(SigUuids.UUID_CYCLING_POWER_SERVICE),
            knownService = null,
            preference = 125,
            localNamePatterns = listOf(Regex("^KICKR(?:\\s|$)", RegexOption.IGNORE_CASE)),
            personaCharacteristicUuids = setOf(
                CanonicalUuid.fromUuid(WahooUuids.UUID_WAHOO_KICKR_CONTROL),
            ),
            machineRole = true,
            requiredEvidenceAlternatives = listOf(
                setOf(MatchEvidenceKind.ADVERTISED_SERVICE, MatchEvidenceKind.LOCAL_NAME_PATTERN),
                setOf(MatchEvidenceKind.GATT_SERVICE, MatchEvidenceKind.GATT_CHARACTERISTIC),
            ),
        )

        /**
         * Flywheel's service is Nordic UART, a transport used by unrelated products. The
         * advertisement-stage identity is therefore the conjunction of the exact source name and
         * full service UUID. Neither weak name evidence nor generic Nordic UART alone may claim the
         * bike; post-connect activation separately validates both endpoints and their properties.
         */
        private fun flywheelDefinition() = MatchDefinition(
            candidateId = MachineFamilyIds.FLYWHEEL_SPORTS,
            serviceUuid = CanonicalUuid.fromUuid(FlywheelUuids.UUID_FLYWHEEL_SERVICE),
            preference = 140,
            localNamePatterns = listOf(Regex("^Flywheel 1$", RegexOption.IGNORE_CASE)),
            personaCharacteristicUuids = setOf(
                CanonicalUuid.fromUuid(FlywheelUuids.UUID_FLYWHEEL_RX),
                CanonicalUuid.fromUuid(FlywheelUuids.UUID_FLYWHEEL_TX),
            ),
            machineRole = true,
            advertisedActivityType = ActivityType.BIKING,
            requiredEvidenceAlternatives = listOf(
                setOf(MatchEvidenceKind.ADVERTISED_SERVICE, MatchEvidenceKind.LOCAL_NAME_PATTERN),
                setOf(
                    MatchEvidenceKind.GATT_SERVICE,
                    MatchEvidenceKind.GATT_CHARACTERISTIC,
                    MatchEvidenceKind.LOCAL_NAME_PATTERN,
                ),
            ),
        )

        /**
         * FFE0 is a commonly reused serial bridge, so this definition admits only a provisional
         * candidate. The handler still requires the exact FFE0/FFE1 parent, properties and CCCD,
         * then a protocol response before setup can become ready. Name and manufacturer facts
         * raise confidence but are deliberately optional because Android may omit scan-response
         * fields and because the physical identifier must never become persisted identity.
         */
        private fun kayakFirstDefinition() = MatchDefinition(
            candidateId = MachineFamilyIds.KAYAK_FIRST,
            serviceUuid = CanonicalUuid.fromUuid(KayakFirstUuids.UUID_KAYAK_FIRST_SERVICE),
            preference = 135,
            localNamePatterns = listOf(Regex("^[\\x20-\\x7E]{8}$")),
            manufacturerCompanyIds = setOf(KAYAK_FIRST_MANUFACTURER_ID),
            personaCharacteristicUuids = setOf(
                CanonicalUuid.fromUuid(KayakFirstUuids.UUID_KAYAK_FIRST_IO),
            ),
            manufacturerPayloadRule = ManufacturerPayloadRule { companyId, payload ->
                companyId == KAYAK_FIRST_MANUFACTURER_ID &&
                    isKayakFirstManufacturerPayload(payload)
            },
            manufacturerAddressRule = ManufacturerAddressRule { address, payload ->
                isKayakFirstAddressCorrelation(address, payload)
            },
            machineRole = true,
            advertisedActivityType = ActivityType.ROWING,
            requiredEvidenceAlternatives = listOf(
                setOf(MatchEvidenceKind.ADVERTISED_SERVICE),
                setOf(MatchEvidenceKind.GATT_SERVICE, MatchEvidenceKind.GATT_CHARACTERISTIC),
            ),
        )

        private fun rideOnDefinition(candidateId: String, service: java.util.UUID) = MatchDefinition(
            candidateId = candidateId,
            serviceUuid = CanonicalUuid.fromUuid(service),
            preference = 130,
            localNamePatterns = listOf(Regex("^KICKR CORE", RegexOption.IGNORE_CASE)),
            personaCharacteristicUuids = setOf(
                CanonicalUuid.fromUuid(WahooUuids.UUID_WAHOO_RIDEON_ASYNC_TX),
                CanonicalUuid.fromUuid(WahooUuids.UUID_WAHOO_RIDEON_SYNC_RX),
                CanonicalUuid.fromUuid(WahooUuids.UUID_WAHOO_RIDEON_SYNC_TX),
            ),
            machineRole = true,
            requiredEvidenceAlternatives = listOf(
                setOf(MatchEvidenceKind.GATT_SERVICE, MatchEvidenceKind.GATT_CHARACTERISTIC),
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
            candidateId = MachineFamilyIds.CONCEPT2_PM,
            serviceUuid = CanonicalUuid.fromUuid(Concept2Uuids.UUID_C2_ERG_SERVICE),
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
                CanonicalUuid.fromUuid(Concept2Uuids.UUID_C2_DISCOVERY_SERVICE),
                CanonicalUuid.fromUuid(Concept2Uuids.UUID_C2_ERG_SERVICE),
            ),
            machineRole = true,
            knownService = null,
            preference = 150,
            personaCharacteristicUuids = linkedSetOf(
                CanonicalUuid.fromUuid(Concept2Uuids.UUID_C2_GENERAL_STATUS),
                CanonicalUuid.fromUuid(Concept2Uuids.UUID_C2_ADDITIONAL_STATUS_1),
                CanonicalUuid.fromUuid(Concept2Uuids.UUID_C2_ADDITIONAL_STATUS_2),
                CanonicalUuid.fromUuid(Concept2Uuids.UUID_C2_ADDITIONAL_STROKE_DATA),
                CanonicalUuid.fromUuid(Concept2Uuids.UUID_C2_MULTIPLEXED_INFORMATION),
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
            candidateId = MachineFamilyIds.PRECOR_CHRONO,
            serviceUuid = CanonicalUuid.fromUuid(PrecorUuids.UUID_PRECOR_SERVICE),
            // User-approved tier-S evidence substitution (2026-08-12): the official Precor
            // Spinning Connect client discovers and consumes FitBridge's Chrono simulator when
            // it advertises this served service UUID. That establishes a valid discovery route
            // for Phase 5b and closes Q32, without claiming that every physical firmware emits
            // an identical advertising payload or promoting the central adapter to tier H.
            machineRole = true,
            knownService = null,
            preference = 140,
            personaCharacteristicUuids = linkedSetOf(
                CanonicalUuid.fromUuid(PrecorUuids.UUID_PRECOR_MEASUREMENT),
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
         * notify — 17 bytes on [SchwinnX70Uuids.UUID_X70_MEASUREMENT] for a 270, paired 20- and
         * 14-byte streams for a 170 — which is a post-subscription fact, not a matcher input.
         *
         * All four notify/write characteristics are listed as persona characteristics so that a
         * device exposing only the 270 set and one exposing only the 170 set both match. Requiring
         * all four would reject both real consoles.
         */
        private fun schwinnX70Definition() = MatchDefinition(
            candidateId = MachineFamilyIds.SCHWINN_X70,
            serviceUuid = CanonicalUuid.fromUuid(SchwinnX70Uuids.UUID_X70_SERVICE),
            machineRole = true,
            knownService = null,
            preference = 130,
            personaCharacteristicUuids = linkedSetOf(
                CanonicalUuid.fromUuid(SchwinnX70Uuids.UUID_X70_MEASUREMENT),
                CanonicalUuid.fromUuid(SchwinnX70Uuids.UUID_X70_SUPPLEMENTAL),
                CanonicalUuid.fromUuid(SchwinnX70Uuids.UUID_X70_SIBLING),
                CanonicalUuid.fromUuid(SchwinnX70Uuids.UUID_X70_CONTROL),
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
            candidateId = MachineFamilyIds.FEC_BLE,
            serviceUuid = CanonicalUuid.fromUuid(FecBleUuids.UUID_FEC_SERVICE),
            machineRole = true,
            knownService = null,
            preference = 120,
            personaCharacteristicUuids = linkedSetOf(
                CanonicalUuid.fromUuid(FecBleUuids.UUID_FEC_MEASUREMENT),
                CanonicalUuid.fromUuid(FecBleUuids.UUID_FEC_CONTROL),
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
