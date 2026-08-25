package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import java.util.UUID

/** Field-level provenance carried into diagnostics and evidence exports. */
internal enum class KickrEvidenceLabel { HCI, LOG, P, M, X, R, SYN }

enum class KickrSimulatorPersona(
    val displayLabel: String,
    val provisional: Boolean,
) {
    V5_STANDARD_LEGACY("KICKR v5 — standard/legacy only", false),
    CORE_ORIGINAL_ZWIFT("KICKR Core — original Zwift service", false),
    CORE2_FC82_PROVISIONAL("KICKR CORE 2 — provisional FC82", true),
}

internal enum class KickrGattProperty { READ, WRITE, WRITE_WITHOUT_RESPONSE, NOTIFY, INDICATE }

internal data class KickrCharacteristicManifest(
    val uuid: UUID,
    val properties: Set<KickrGattProperty>,
    val evidence: Set<KickrEvidenceLabel>,
) {
    val hasCccd: Boolean =
        KickrGattProperty.NOTIFY in properties || KickrGattProperty.INDICATE in properties
}

internal data class KickrServiceManifest(
    val uuid: UUID,
    val characteristics: List<KickrCharacteristicManifest>,
    val evidence: Set<KickrEvidenceLabel>,
)

internal data class KickrDeviceIdentity(
    val manufacturer: String?,
    val hardwareRevision: String?,
    val firmwareRevision: String?,
    val evidence: Set<KickrEvidenceLabel>,
)

/** Pure advertising contract. [exactLegacyPayload] is present only for byte-captured advertising. */
internal data class KickrAdvertisingPlan(
    val localName: String,
    val advertisedServices: List<UUID>,
    val serviceData: Map<UUID, ByteArray> = emptyMap(),
    val evidence: Set<KickrEvidenceLabel>,
    val exactLegacyPayload: ByteArray? = null,
) {
    val semanticLegacyBytes: Int = FLAGS_STRUCTURE_BYTES +
        COMPLETE_NAME_HEADER_BYTES + localName.toByteArray(Charsets.UTF_8).size +
        groupedServiceStructureBytes(advertisedServices) +
        serviceData.entries.sumOf { (uuid, value) ->
            AD_HEADER_BYTES + encodedUuidBytes(uuid) + value.size
        }

    init {
        require(semanticLegacyBytes <= MAX_LEGACY_BYTES) {
            "KICKR advertising payload requires $semanticLegacyBytes of $MAX_LEGACY_BYTES bytes"
        }
        exactLegacyPayload?.let { exact ->
            require(exact.size <= MAX_LEGACY_BYTES) { "Exact KICKR payload exceeds legacy advertising budget" }
            require(exact.size == semanticLegacyBytes) {
                "Exact payload size ${exact.size} disagrees with semantic plan $semanticLegacyBytes"
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is KickrAdvertisingPlan &&
            localName == other.localName &&
            advertisedServices == other.advertisedServices &&
            serviceData.keys == other.serviceData.keys &&
            serviceData.all { (key, value) -> value.contentEquals(other.serviceData[key]) } &&
            evidence == other.evidence &&
            when {
                exactLegacyPayload == null -> other.exactLegacyPayload == null
                other.exactLegacyPayload == null -> false
                else -> exactLegacyPayload.contentEquals(other.exactLegacyPayload)
            }

    override fun hashCode(): Int {
        var result = localName.hashCode()
        result = 31 * result + advertisedServices.hashCode()
        result = 31 * result + serviceData.entries.fold(0) { acc, entry ->
            31 * acc + entry.key.hashCode() + entry.value.contentHashCode()
        }
        result = 31 * result + evidence.hashCode()
        result = 31 * result + (exactLegacyPayload?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        const val MAX_LEGACY_BYTES = 31
        private const val FLAGS_STRUCTURE_BYTES = 3
        private const val COMPLETE_NAME_HEADER_BYTES = 2
        private const val AD_HEADER_BYTES = 2

        private fun groupedServiceStructureBytes(uuids: List<UUID>): Int =
            uuids.groupBy(::encodedUuidBytes).entries.sumOf { (uuidBytes, group) ->
                AD_HEADER_BYTES + uuidBytes * group.size
            }

        private fun encodedUuidBytes(uuid: UUID): Int =
            if (uuid.toString().endsWith("-0000-1000-8000-00805f9b34fb") &&
                uuid.toString().startsWith("0000")) {
                2
            } else {
                16
            }
    }
}

internal data class KickrPersonaManifest(
    val persona: KickrSimulatorPersona,
    val advertising: KickrAdvertisingPlan,
    val identity: KickrDeviceIdentity,
    val services: List<KickrServiceManifest>,
    val legacyAckMode: KickrLegacyAckMode?,
) {
    fun service(uuid: UUID): KickrServiceManifest? = services.singleOrNull { it.uuid == uuid }

    companion object {
        fun forPersona(persona: KickrSimulatorPersona): KickrPersonaManifest = when (persona) {
            KickrSimulatorPersona.V5_STANDARD_LEGACY -> v5()
            KickrSimulatorPersona.CORE_ORIGINAL_ZWIFT -> originalCore()
            KickrSimulatorPersona.CORE2_FC82_PROVISIONAL -> core2Probe()
        }

        private fun v5(): KickrPersonaManifest = KickrPersonaManifest(
            persona = KickrSimulatorPersona.V5_STANDARD_LEGACY,
            advertising = KickrAdvertisingPlan(
                localName = "Wahoo KICKR AEE4",
                advertisedServices = listOf(
                    BluetoothIds.UUID_CYCLING_POWER_SERVICE,
                    BluetoothIds.UUID_FTMS_SERVICE,
                ),
                evidence = setOf(KickrEvidenceLabel.LOG),
            ),
            identity = KickrDeviceIdentity(null, null, null, setOf(KickrEvidenceLabel.SYN)),
            services = standardKickrServices(setOf(KickrEvidenceLabel.LOG)),
            legacyAckMode = KickrLegacyAckMode.ACKNOWLEDGING_SYNTHETIC,
        )

        private fun originalCore(): KickrPersonaManifest = KickrPersonaManifest(
            persona = KickrSimulatorPersona.CORE_ORIGINAL_ZWIFT,
            advertising = KickrAdvertisingPlan(
                localName = "KICKR CORE C3FB",
                advertisedServices = listOf(
                    BluetoothIds.UUID_CYCLING_POWER_SERVICE,
                    BluetoothIds.UUID_FTMS_SERVICE,
                ),
                evidence = setOf(KickrEvidenceLabel.HCI),
                exactLegacyPayload = ORIGINAL_CORE_ADVERTISING.copyOf(),
            ),
            identity = KickrDeviceIdentity(
                manufacturer = "Wahoo Fitness",
                hardwareRevision = "1",
                firmwareRevision = "1.5.36",
                evidence = setOf(KickrEvidenceLabel.HCI),
            ),
            services = standardKickrServices(setOf(KickrEvidenceLabel.HCI)) + listOf(
                service(
                    BluetoothIds.UUID_WAHOO_KICKR_MARKER_EE03,
                    setOf(KickrEvidenceLabel.LOG),
                    characteristic(BluetoothIds.UUID_WAHOO_KICKR_E00A, NOTIFY, KickrEvidenceLabel.LOG),
                ),
                zwiftService(BluetoothIds.UUID_WAHOO_RIDEON_SERVICE, KickrEvidenceLabel.HCI),
            ),
            legacyAckMode = KickrLegacyAckMode.ACKNOWLEDGING_SYNTHETIC,
        )

        private fun core2Probe(): KickrPersonaManifest = KickrPersonaManifest(
            persona = KickrSimulatorPersona.CORE2_FC82_PROVISIONAL,
            advertising = KickrAdvertisingPlan(
                localName = "KICKR CORE 2 SIM",
                advertisedServices = listOf(BluetoothIds.UUID_WAHOO_RIDEON_CORE2_SERVICE),
                evidence = setOf(KickrEvidenceLabel.M, KickrEvidenceLabel.SYN),
            ),
            identity = KickrDeviceIdentity(null, null, null, setOf(KickrEvidenceLabel.SYN)),
            services = listOf(
                zwiftService(BluetoothIds.UUID_WAHOO_RIDEON_CORE2_SERVICE, KickrEvidenceLabel.M),
            ),
            legacyAckMode = null,
        )

        private fun standardKickrServices(evidence: Set<KickrEvidenceLabel>): List<KickrServiceManifest> =
            listOf(
                service(
                    BluetoothIds.UUID_DEVICE_INFORMATION_SERVICE,
                    evidence,
                    characteristic(BluetoothIds.UUID_MANUFACTURER_NAME, READ, *evidence.toTypedArray()),
                    characteristic(BluetoothIds.UUID_SERIAL_NUMBER, READ, *evidence.toTypedArray()),
                    characteristic(BluetoothIds.UUID_HARDWARE_REVISION, READ, *evidence.toTypedArray()),
                    characteristic(BluetoothIds.UUID_FIRMWARE_REVISION, READ, *evidence.toTypedArray()),
                ),
                service(
                    BluetoothIds.UUID_CYCLING_POWER_SERVICE,
                    evidence,
                    characteristic(BluetoothIds.UUID_CYCLING_POWER_MEASUREMENT, NOTIFY, *evidence.toTypedArray()),
                    characteristic(BluetoothIds.UUID_CYCLING_POWER_FEATURE, READ, *evidence.toTypedArray()),
                    characteristic(BluetoothIds.UUID_SENSOR_LOCATION, READ, *evidence.toTypedArray()),
                    characteristic(BluetoothIds.UUID_WAHOO_KICKR_CONTROL, WRITE_INDICATE, *evidence.toTypedArray()),
                    characteristic(BluetoothIds.UUID_CYCLING_POWER_CONTROL_POINT, WRITE_INDICATE, *evidence.toTypedArray()),
                ),
                service(
                    BluetoothIds.UUID_USER_DATA_SERVICE,
                    evidence,
                    characteristic(BluetoothIds.UUID_USER_WEIGHT, READ_WRITE, *evidence.toTypedArray()),
                ),
                service(
                    BluetoothIds.UUID_FTMS_SERVICE,
                    evidence,
                    characteristic(BluetoothIds.UUID_FITNESS_MACHINE_FEATURE, READ, *evidence.toTypedArray()),
                    characteristic(BluetoothIds.UUID_TRAINING_STATUS, READ_NOTIFY, *evidence.toTypedArray()),
                    characteristic(BluetoothIds.UUID_FITNESS_MACHINE_CONTROL_POINT, WRITE_INDICATE, *evidence.toTypedArray()),
                    characteristic(BluetoothIds.UUID_FITNESS_MACHINE_STATUS, NOTIFY, *evidence.toTypedArray()),
                    characteristic(BluetoothIds.UUID_BIKING_DATA, NOTIFY, *evidence.toTypedArray()),
                    characteristic(BluetoothIds.UUID_SUPPORTED_RESISTANCE_RANGE, READ, *evidence.toTypedArray()),
                    characteristic(BluetoothIds.UUID_SUPPORTED_POWER_RANGE, READ, *evidence.toTypedArray()),
                ),
                service(
                    BluetoothIds.UUID_WAHOO_KICKR_MARKER_EE01,
                    evidence,
                    characteristic(BluetoothIds.UUID_WAHOO_KICKR_E002, WRITE_NR_NOTIFY, *evidence.toTypedArray()),
                    characteristic(BluetoothIds.UUID_WAHOO_KICKR_E004, NOTIFY, *evidence.toTypedArray()),
                ),
                service(
                    BluetoothIds.UUID_WAHOO_KICKR_MARKER_EE06,
                    evidence,
                    characteristic(BluetoothIds.UUID_WAHOO_KICKR_E023, WRITE_NR_NOTIFY, *evidence.toTypedArray()),
                ),
                service(
                    BluetoothIds.UUID_WAHOO_KICKR_MARKER_EE0B,
                    evidence,
                    characteristic(BluetoothIds.UUID_WAHOO_KICKR_E037, WRITE_NR_NOTIFY, *evidence.toTypedArray()),
                ),
            )

        private fun zwiftService(parent: UUID, evidence: KickrEvidenceLabel): KickrServiceManifest =
            service(
                parent,
                setOf(evidence),
                characteristic(BluetoothIds.UUID_WAHOO_RIDEON_ASYNC_TX, NOTIFY, evidence),
                characteristic(BluetoothIds.UUID_WAHOO_RIDEON_SYNC_RX, WRITE_NR, evidence),
                characteristic(BluetoothIds.UUID_WAHOO_RIDEON_SYNC_TX, INDICATE, evidence),
            )

        private fun service(
            uuid: UUID,
            evidence: Set<KickrEvidenceLabel>,
            vararg characteristics: KickrCharacteristicManifest,
        ) = KickrServiceManifest(uuid, characteristics.toList(), evidence)

        private fun characteristic(
            uuid: UUID,
            properties: Set<KickrGattProperty>,
            vararg evidence: KickrEvidenceLabel,
        ) = KickrCharacteristicManifest(uuid, properties, evidence.toSet())

        private val READ = setOf(KickrGattProperty.READ)
        private val NOTIFY = setOf(KickrGattProperty.NOTIFY)
        private val INDICATE = setOf(KickrGattProperty.INDICATE)
        private val WRITE_NR = setOf(KickrGattProperty.WRITE_WITHOUT_RESPONSE)
        private val READ_WRITE = setOf(KickrGattProperty.READ, KickrGattProperty.WRITE)
        private val READ_NOTIFY = setOf(KickrGattProperty.READ, KickrGattProperty.NOTIFY)
        private val WRITE_INDICATE = setOf(KickrGattProperty.WRITE, KickrGattProperty.INDICATE)
        private val WRITE_NR_NOTIFY = setOf(
            KickrGattProperty.WRITE_WITHOUT_RESPONSE,
            KickrGattProperty.NOTIFY,
        )

        val ORIGINAL_CORE_ADVERTISING: ByteArray = byteArrayOf(
            0x02, 0x01, 0x06,
            0x05, 0x03, 0x18, 0x18, 0x26, 0x18,
            0x10, 0x09, 0x4B, 0x49, 0x43, 0x4B, 0x52, 0x20,
            0x43, 0x4F, 0x52, 0x45, 0x20, 0x43, 0x33, 0x46, 0x42,
        )
    }
}
