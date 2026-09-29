package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds
import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.central.RideOnServiceForm

/** Field-level provenance carried into diagnostics and evidence exports. */
internal enum class KickrEvidenceLabel { HCI, LOG, P, M, X, R, SYN }

enum class KickrSimulatorPersona(
    val displayLabel: String,
    val provisional: Boolean,
) {
    V5_STANDARD_LEGACY("KICKR v5 — standard/legacy only", false),
    CORE_ORIGINAL_ZWIFT("KICKR Core — original Zwift service", false),
    CORE2_FC82_PROVISIONAL("KICKR CORE 2 — CPS/FTMS + FC82", true),
    ZWIFT_HUB_CAPTURED("Zwift Hub — captured GATT", true),
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
    val scanResponseServices: List<UUID> = emptyList(),
    val serviceData: Map<UUID, ByteArray> = emptyMap(),
    val manufacturerId: Int? = null,
    val manufacturerData: ByteArray? = null,
    val evidence: Set<KickrEvidenceLabel>,
    val exactLegacyPayload: ByteArray? = null,
) {
    val semanticLegacyBytes: Int = FLAGS_STRUCTURE_BYTES +
        COMPLETE_NAME_HEADER_BYTES + localName.toByteArray(Charsets.UTF_8).size +
        groupedServiceStructureBytes(advertisedServices) +
        serviceData.entries.sumOf { (uuid, value) ->
            AD_HEADER_BYTES + encodedUuidBytes(uuid) + value.size
        } + (manufacturerData?.let { MANUFACTURER_HEADER_BYTES + it.size } ?: 0)

    val semanticScanResponseBytes: Int = groupedServiceStructureBytes(scanResponseServices)

    init {
        require((manufacturerId == null) == (manufacturerData == null)) {
            "KICKR manufacturer ID and data must be supplied together"
        }
        require(semanticLegacyBytes <= MAX_LEGACY_BYTES) {
            "KICKR advertising payload requires $semanticLegacyBytes of $MAX_LEGACY_BYTES bytes"
        }
        require(semanticScanResponseBytes <= MAX_LEGACY_BYTES) {
            "KICKR scan response requires $semanticScanResponseBytes of $MAX_LEGACY_BYTES bytes"
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
            scanResponseServices == other.scanResponseServices &&
            serviceData.keys == other.serviceData.keys &&
            serviceData.all { (key, value) -> value.contentEquals(other.serviceData[key]) } &&
            manufacturerId == other.manufacturerId &&
            when {
                manufacturerData == null -> other.manufacturerData == null
                other.manufacturerData == null -> false
                else -> manufacturerData.contentEquals(other.manufacturerData)
            } &&
            evidence == other.evidence &&
            when {
                exactLegacyPayload == null -> other.exactLegacyPayload == null
                other.exactLegacyPayload == null -> false
                else -> exactLegacyPayload.contentEquals(other.exactLegacyPayload)
            }

    override fun hashCode(): Int {
        var result = localName.hashCode()
        result = 31 * result + advertisedServices.hashCode()
        result = 31 * result + scanResponseServices.hashCode()
        result = 31 * result + serviceData.entries.fold(0) { acc, entry ->
            31 * acc + entry.key.hashCode() + entry.value.contentHashCode()
        }
        result = 31 * result + (manufacturerId ?: 0)
        result = 31 * result + (manufacturerData?.contentHashCode() ?: 0)
        result = 31 * result + evidence.hashCode()
        result = 31 * result + (exactLegacyPayload?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        const val MAX_LEGACY_BYTES = 31
        private const val FLAGS_STRUCTURE_BYTES = 3
        private const val COMPLETE_NAME_HEADER_BYTES = 2
        private const val AD_HEADER_BYTES = 2
        private const val MANUFACTURER_HEADER_BYTES = 4

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
    val zwiftServiceForm: RideOnServiceForm?,
) {
    fun service(uuid: UUID): KickrServiceManifest? = services.singleOrNull { it.uuid == uuid }

    companion object {
        fun forPersona(persona: KickrSimulatorPersona): KickrPersonaManifest = when (persona) {
            KickrSimulatorPersona.V5_STANDARD_LEGACY -> v5()
            KickrSimulatorPersona.CORE_ORIGINAL_ZWIFT -> originalCore()
            KickrSimulatorPersona.CORE2_FC82_PROVISIONAL -> core2Probe()
            KickrSimulatorPersona.ZWIFT_HUB_CAPTURED -> zwiftHub()
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
            zwiftServiceForm = null,
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
            zwiftServiceForm = RideOnServiceForm.ORIGINAL,
        )

        private fun core2Probe(): KickrPersonaManifest = KickrPersonaManifest(
            persona = KickrSimulatorPersona.CORE2_FC82_PROVISIONAL,
            advertising = KickrAdvertisingPlan(
                // The capture says "KICKR CORE 1A2B". Retain its suffix and service list while
                // inserting the user's requested CORE 2 model marker into the simulator name.
                localName = "KICKR CORE 2 1A2B",
                advertisedServices = listOf(
                    BluetoothIds.UUID_CYCLING_POWER_SERVICE,
                    BluetoothIds.UUID_FTMS_SERVICE,
                ),
                evidence = setOf(KickrEvidenceLabel.HCI, KickrEvidenceLabel.SYN),
                exactLegacyPayload = CORE2_SIM_ADVERTISING.copyOf(),
            ),
            identity = KickrDeviceIdentity(null, null, null, setOf(KickrEvidenceLabel.SYN)),
            services = capturedCore2Services(),
            legacyAckMode = null,
            zwiftServiceForm = RideOnServiceForm.CORE2,
        )

        private fun zwiftHub(): KickrPersonaManifest = KickrPersonaManifest(
            persona = KickrSimulatorPersona.ZWIFT_HUB_CAPTURED,
            advertising = KickrAdvertisingPlan(
                localName = "Zwift Hub",
                // QZ reports this aggregate set but not its raw ADV/scan-response allocation. This
                // split stays within both legacy packet budgets and is therefore explicitly SYN.
                advertisedServices = listOf(
                    BluetoothIds.UUID_CYCLING_POWER_SERVICE,
                    BluetoothIds.UUID_FTMS_SERVICE,
                    BluetoothIds.UUID_DEVICE_INFORMATION_SERVICE,
                ),
                scanResponseServices = listOf(BluetoothIds.UUID_WAHOO_RIDEON_SERVICE),
                manufacturerId = 0x094A,
                manufacturerData = byteArrayOf(0x06, 0x2E, 0xBE.toByte()),
                evidence = setOf(KickrEvidenceLabel.LOG, KickrEvidenceLabel.SYN),
            ),
            identity = KickrDeviceIdentity(null, null, null, setOf(KickrEvidenceLabel.SYN)),
            services = capturedZwiftHubServices(),
            legacyAckMode = null,
            zwiftServiceForm = RideOnServiceForm.ORIGINAL,
        )

        /** Repeated physical Zwift Hub discovery; GAP/GATT are supplied by the Android stack. */
        private fun capturedZwiftHubServices(): List<KickrServiceManifest> {
            val evidence = setOf(KickrEvidenceLabel.LOG)
            return listOf(
                service(
                    BluetoothIds.UUID_CYCLING_POWER_SERVICE,
                    evidence,
                    characteristic(BluetoothIds.UUID_CYCLING_POWER_MEASUREMENT, NOTIFY, KickrEvidenceLabel.LOG),
                    characteristic(BluetoothIds.UUID_CYCLING_POWER_FEATURE, READ, KickrEvidenceLabel.LOG),
                    characteristic(BluetoothIds.UUID_SENSOR_LOCATION, READ, KickrEvidenceLabel.LOG),
                    characteristic(BluetoothIds.UUID_CYCLING_POWER_CONTROL_POINT, WRITE_INDICATE, KickrEvidenceLabel.LOG),
                ),
                service(
                    BluetoothIds.UUID_FTMS_SERVICE,
                    evidence,
                    characteristic(BluetoothIds.UUID_BIKING_DATA, NOTIFY, KickrEvidenceLabel.LOG),
                    characteristic(BluetoothIds.UUID_FITNESS_MACHINE_FEATURE, READ, KickrEvidenceLabel.LOG),
                    characteristic(BluetoothIds.UUID_FITNESS_MACHINE_STATUS, NOTIFY, KickrEvidenceLabel.LOG),
                    characteristic(BluetoothIds.UUID_SUPPORTED_RESISTANCE_RANGE, READ, KickrEvidenceLabel.LOG),
                    characteristic(BluetoothIds.UUID_SUPPORTED_POWER_RANGE, READ, KickrEvidenceLabel.LOG),
                    characteristic(BluetoothIds.UUID_FITNESS_MACHINE_CONTROL_POINT, WRITE_INDICATE, KickrEvidenceLabel.LOG),
                ),
                service(
                    BluetoothIds.UUID_ZWIFT_HUB_SUPPORT_SERVICE,
                    evidence,
                    characteristic(BluetoothIds.UUID_ZWIFT_HUB_SUPPORT_READ, READ, KickrEvidenceLabel.LOG),
                    characteristic(BluetoothIds.UUID_ZWIFT_HUB_SUPPORT_NOTIFY, READ_NOTIFY, KickrEvidenceLabel.LOG),
                    characteristic(BluetoothIds.UUID_ZWIFT_HUB_SUPPORT_CONTROL, WRITE_INDICATE, KickrEvidenceLabel.LOG),
                ),
                service(
                    BluetoothIds.UUID_HRM_SERVICE,
                    evidence,
                    characteristic(BluetoothIds.UUID_HEART_RATE_MEASUREMENT, NOTIFY, KickrEvidenceLabel.LOG),
                    characteristic(BluetoothIds.UUID_BODY_SENSOR_LOCATION, READ, KickrEvidenceLabel.LOG),
                ),
                service(
                    BluetoothIds.UUID_NORDIC_UART_SERVICE,
                    evidence,
                    characteristic(BluetoothIds.UUID_NORDIC_UART_RX, WRITE_WRITE_NR, KickrEvidenceLabel.LOG),
                    characteristic(BluetoothIds.UUID_NORDIC_UART_TX, NOTIFY, KickrEvidenceLabel.LOG),
                ),
                service(
                    BluetoothIds.UUID_SECURE_DFU_SERVICE,
                    evidence,
                    characteristic(BluetoothIds.UUID_SECURE_DFU_CONTROL, WRITE_INDICATE, KickrEvidenceLabel.LOG),
                ),
                service(
                    BluetoothIds.UUID_DEVICE_INFORMATION_SERVICE,
                    evidence,
                    characteristic(BluetoothIds.UUID_MANUFACTURER_NAME, READ, KickrEvidenceLabel.LOG),
                    characteristic(BluetoothIds.UUID_MODEL_NUMBER, READ, KickrEvidenceLabel.LOG),
                    characteristic(BluetoothIds.UUID_SERIAL_NUMBER, READ, KickrEvidenceLabel.LOG),
                    characteristic(BluetoothIds.UUID_HARDWARE_REVISION, READ, KickrEvidenceLabel.LOG),
                    characteristic(BluetoothIds.UUID_FIRMWARE_REVISION, READ, KickrEvidenceLabel.LOG),
                ),
                service(
                    BluetoothIds.UUID_WAHOO_RIDEON_SERVICE,
                    evidence,
                    characteristic(BluetoothIds.UUID_WAHOO_RIDEON_ASYNC_TX, NOTIFY, KickrEvidenceLabel.LOG),
                    characteristic(BluetoothIds.UUID_WAHOO_RIDEON_SYNC_RX, WRITE_NR, KickrEvidenceLabel.LOG),
                    characteristic(BluetoothIds.UUID_WAHOO_RIDEON_SYNC_TX, READ_INDICATE, KickrEvidenceLabel.LOG),
                ),
            )
        }

        /**
         * Connected GATT inventory logged from physical `KICKR CORE 724F` on 2025-12-04.
         * Service order follows the captured ATT handle order; values not read by QZ remain SYN.
         */
        private fun capturedCore2Services(): List<KickrServiceManifest> {
            val evidence = setOf(KickrEvidenceLabel.LOG)
            val standard = standardKickrServices(evidence).associateBy { it.uuid }
            val ee06 = requireNotNull(standard[BluetoothIds.UUID_WAHOO_KICKR_MARKER_EE06]).let { service ->
                service.copy(
                    characteristics = service.characteristics + characteristic(
                        BluetoothIds.UUID_WAHOO_KICKR_E018,
                        WRITE_NR_NOTIFY,
                        KickrEvidenceLabel.LOG,
                    ),
                )
            }
            return listOf(
                service(
                    BluetoothIds.UUID_HRM_SERVICE,
                    evidence,
                    characteristic(
                        BluetoothIds.UUID_HEART_RATE_MEASUREMENT,
                        NOTIFY,
                        KickrEvidenceLabel.LOG,
                    ),
                    characteristic(
                        BluetoothIds.UUID_BODY_SENSOR_LOCATION,
                        READ,
                        KickrEvidenceLabel.LOG,
                    ),
                ),
                requireNotNull(standard[BluetoothIds.UUID_DEVICE_INFORMATION_SERVICE]),
                requireNotNull(standard[BluetoothIds.UUID_USER_DATA_SERVICE]),
                requireNotNull(standard[BluetoothIds.UUID_CYCLING_POWER_SERVICE]),
                service(
                    BluetoothIds.UUID_SECURE_DFU_SERVICE,
                    evidence,
                    characteristic(
                        BluetoothIds.UUID_SECURE_DFU_CONTROL,
                        WRITE_INDICATE,
                        KickrEvidenceLabel.LOG,
                    ),
                ),
                zwiftService(BluetoothIds.UUID_WAHOO_RIDEON_CORE2_SERVICE, KickrEvidenceLabel.LOG),
                requireNotNull(standard[BluetoothIds.UUID_WAHOO_KICKR_MARKER_EE01]),
                ee06,
                requireNotNull(standard[BluetoothIds.UUID_FTMS_SERVICE]),
                requireNotNull(standard[BluetoothIds.UUID_WAHOO_KICKR_MARKER_EE0B]),
            )
        }

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
        private val READ_INDICATE = setOf(KickrGattProperty.READ, KickrGattProperty.INDICATE)
        private val WRITE_INDICATE = setOf(KickrGattProperty.WRITE, KickrGattProperty.INDICATE)
        private val WRITE_WRITE_NR = setOf(
            KickrGattProperty.WRITE,
            KickrGattProperty.WRITE_WITHOUT_RESPONSE,
        )
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

        /** User-supplied physical CORE 2 legacy advertisement, pinned without interpretation. */
        val CAPTURED_CORE2_ADVERTISING: ByteArray = byteArrayOf(
            0x02, 0x01, 0x06,
            0x05, 0x03, 0x18, 0x18, 0x26, 0x18,
            0x10, 0x09, 0x4B, 0x49, 0x43, 0x4B, 0x52, 0x20,
            0x43, 0x4F, 0x52, 0x45, 0x20, 0x31, 0x41, 0x32, 0x42,
        )

        /** Captured packet adapted only by inserting "2 " before the four-character suffix. */
        val CORE2_SIM_ADVERTISING: ByteArray = byteArrayOf(
            0x02, 0x01, 0x06,
            0x05, 0x03, 0x18, 0x18, 0x26, 0x18,
            0x12, 0x09, 0x4B, 0x49, 0x43, 0x4B, 0x52, 0x20,
            0x43, 0x4F, 0x52, 0x45, 0x20, 0x32, 0x20, 0x31, 0x41, 0x32, 0x42,
        )
    }
}
