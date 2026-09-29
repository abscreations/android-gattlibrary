package com.gmail.absolutebollockscreations.gattlibrary.central

import java.util.Collections

public enum class DeviceService { FTMS, HeartRate, CSC, RSC, Power, Glucose }
public enum class SensorRole { SPEED, CADENCE }
public enum class SensorRoleEvidence { APPEARANCE, FEATURE }
public enum class DeviceTransport { GATT, ADVERTISEMENT_ONLY }
public enum class AdvertisementReception { RECEIVING, STALE, OUT_OF_RANGE }
public enum class MachineFamily {
    FTMS, LIFE_FITNESS, PRECOR_SPINNER_CHRONO_POWER, CONCEPT2_PM5,
    FE_C_OVER_BLE, FLYWHEEL_SPORTS, KAYAK_FIRST,
}
public enum class MachineKind { BIKE, BIKE_ERG, ELLIPTICAL, ROWER, SKI_ERG, STAIR_CLIMBER, STAIR_STEPPER, TREADMILL }
public enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }

/** A selection token from this client's current discovery catalog; never persist it. */
public class DeviceHandle internal constructor(
    internal val owner: Any,
    /** Engine catalog key. An unresolved (blank-address) row has a temporary key and no address. */
    internal val key: String,
    internal val address: String,
    internal val transport: DeviceTransport,
    internal val systemId: Int?,
)

/** Identifies one physical-link generation, not a service or a reusable address. */
public class LinkHandle internal constructor(internal val owner: Any, internal val key: String)

public enum class OperationFailure {
    CLOSED, STALE_HANDLE, PERMISSION_REQUIRED, BLUETOOTH_UNAVAILABLE, UNSUPPORTED, REJECTED,
}

/** Accepted means submitted locally. Connection/setup completion is observed separately. */
public class OperationResult internal constructor(public val failure: OperationFailure?) {
    public val accepted: Boolean get() = failure == null
}

public class ScanOptions(
    public val durationMillis: Long = 300_000L,
    public val useHardwareFilters: Boolean = true,
) {
    init { require(durationMillis in 1..300_000L) { "Discovery duration must be between 1 and 300000 ms" } }
}

public class SensorAssignment(
    public val service: DeviceService,
    roles: Set<SensorRole>? = null,
) {
    public val roles: Set<SensorRole>? = roles?.let(::immutableSet)
    init {
        require(service in setOf(DeviceService.HeartRate, DeviceService.CSC, DeviceService.RSC, DeviceService.Power))
        require(roles == null || (service == DeviceService.CSC && roles.isNotEmpty()))
    }
}

public class DiscoveredDevice internal constructor(
    public val handle: DeviceHandle,
    public val name: String,
    public val address: String,
    public val rssi: Int,
    public val lastSeenMs: Long,
    services: Set<DeviceService>,
    public val machineCandidate: Boolean,
    public val transport: DeviceTransport,
    public val matchCandidateId: String?,
    public val advertisementSystemId: Int?,
    public val advertisementFirmware: String?,
    public val machineFeature: Int?,
    public val advertisedMachineFeature: Int?,
    public val appearance: Int?,
) {
    public val services: Set<DeviceService> = immutableSet(services)
}

public class ConnectedLink internal constructor(
    public val handle: LinkHandle,
    public val address: String,
    public val status: ConnectionStatus,
    activeServices: Set<DeviceService>,
    public val machineConnection: Boolean,
    public val machineFamily: MachineFamily?,
    public val machineKind: MachineKind?,
    supportedRoles: Set<SensorRole>?,
    public val roleEvidence: SensorRoleEvidence?,
    assignedRoles: Set<SensorRole>,
) {
    public val activeServices: Set<DeviceService> = immutableSet(activeServices)
    public val supportedRoles: Set<SensorRole>? = supportedRoles?.let(::immutableSet)
    public val assignedRoles: Set<SensorRole> = immutableSet(assignedRoles)
}

/** Advertisement selection has no GATT link or disconnect handle. */
public class AdvertisementMachine internal constructor(
    public val address: String,
    public val candidateId: String,
    public val systemId: Int,
    public val firmware: String?,
    public val generation: Long,
    public val reception: AdvertisementReception,
    public val model: String,
)

public class DiscoverySnapshot internal constructor(
    public val discoveryRequested: Boolean,
    public val radioScanning: Boolean,
    devices: List<DiscoveredDevice>,
    /** Android scanner failure code, absent until a scanner failure is reported. */
    public val scanFailureCode: Int?,
) {
    public val devices: List<DiscoveredDevice> = immutableList(devices)
}

public class ConnectionSnapshot internal constructor(
    links: List<ConnectedLink>,
    public val advertisementMachine: AdvertisementMachine?,
    public val machineStatus: ConnectionStatus,
    public val machineProbe: MachineProbeState,
    /** Subscription readiness only; this does not grant target capability or control. */
    public val machineSubscriptionsReady: Boolean,
) {
    public val links: List<ConnectedLink> = immutableList(links)
}

internal fun <T> immutableSet(values: Collection<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))
internal fun <T> immutableList(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))

internal fun connectionStatus(value: Int): ConnectionStatus = when (value) {
    android.bluetooth.BluetoothProfile.STATE_CONNECTING -> ConnectionStatus.CONNECTING
    android.bluetooth.BluetoothProfile.STATE_CONNECTED -> ConnectionStatus.CONNECTED
    android.bluetooth.BluetoothProfile.STATE_DISCONNECTING -> ConnectionStatus.DISCONNECTING
    else -> ConnectionStatus.DISCONNECTED
}

internal class DiscoveryProjection(private val owner: Any) {
    private val devices = mutableMapOf<String, DeviceHandle>()
    private val links = mutableMapOf<String, LinkHandle>()

    /**
     * Keyed by the engine catalog key, not the address: rows whose address could not be read share
     * a blank address but keep distinct temporary keys, so they must not collapse into one handle.
     */
    fun deviceRows(rows: Map<String, BtEngineState.DiscoveredDevice>): List<DiscoveredDevice> {
        devices.keys.retainAll(rows.keys)
        return rows.map { (key, row) ->
            val address = canonicalScanAddress(row.address)
            val transport = DeviceTransport.valueOf(row.transport.name)
            val handle = devices[key]?.takeIf {
                it.address == address && it.transport == transport && it.systemId == row.advertisementSystemId
            } ?: DeviceHandle(owner, key, address, transport, row.advertisementSystemId).also { devices[key] = it }
            DiscoveredDevice(
                handle, row.name, row.address, row.rssi, row.lastSeenMs,
                row.services.mapTo(linkedSetOf()) { DeviceService.valueOf(it.name) },
                row.machineCandidate, transport, row.matchCandidateId,
                row.advertisementSystemId, row.advertisementFirmware,
                row.machineFeature, row.advertisedMachineFeature, row.appearance,
            )
        }
    }

    fun linkRows(rows: List<BtEngineState.ConnectedDeviceLink>): List<ConnectedLink> {
        links.keys.retainAll(rows.map { it.connectionKey }.toSet())
        return rows.map { row ->
            ConnectedLink(
                links.getOrPut(row.connectionKey) { LinkHandle(owner, row.connectionKey) },
                row.address, connectionStatus(row.connectionState),
                row.activeServices.mapTo(linkedSetOf()) { DeviceService.valueOf(it.name) },
                row.machineConnection, row.machineIdentityFamily?.let { MachineFamily.valueOf(it.name) },
                row.machineDeviceType?.let { MachineKind.valueOf(it.name) },
                row.cscRoleCapability?.roles?.mapTo(linkedSetOf()) { SensorRole.valueOf(it.name) },
                row.cscRoleCapability?.source?.let { SensorRoleEvidence.valueOf(it.name) },
                row.cscAssignedRoles.mapTo(linkedSetOf()) { SensorRole.valueOf(it.name) },
            )
        }
    }

    fun accepts(handle: DeviceHandle): Boolean = handle.owner === owner && devices[handle.key] === handle
    fun accepts(handle: LinkHandle): Boolean = handle.owner === owner && links[handle.key] === handle
}

internal fun BtEngineState.ActiveAdvertisementMachine.toConsumer(): AdvertisementMachine = AdvertisementMachine(
    address, candidateId, systemId, firmware, generation, AdvertisementReception.valueOf(sourceState.name), model,
)
