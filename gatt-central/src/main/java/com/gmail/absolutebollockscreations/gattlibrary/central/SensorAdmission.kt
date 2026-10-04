package com.gmail.absolutebollockscreations.gattlibrary.central

import android.bluetooth.BluetoothProfile
import java.util.Locale

/** Which cycling roles a speed/cadence sensor can fill, and how that was learned. */
public data class SensorRoleCapability(
    val roles: Set<SensorRole>,
    val evidence: SensorRoleEvidence,
)

/** One sensor's claim on cycling roles, as the admission policy needs it. */
public data class SensorRoleAssignment(
    val address: String,
    val capability: SensorRoleCapability?,
    val assignedRoles: Set<SensorRole> = emptySet(),
)

/** A live link as the sensor admission policy sees it. */
public data class SensorLink(
    val address: String,
    val status: ConnectionStatus,
    val activeServices: Set<DeviceService>,
    val capability: SensorRoleCapability? = null,
    val assignedRoles: Set<SensorRole> = emptySet(),
)

/**
 * Sensor slot and cycling-role admission, in the client's public vocabulary. This is the same
 * policy the engine enforces when it connects sensors; the app asks it first so the UI and the
 * engine cannot disagree. Every function delegates to the engine's own implementation.
 */
public object SensorAdmission {
    /** Concurrent connections allowed for [service]; zero means it is not a sensor slot. */
    public fun connectionLimit(service: DeviceService): Int =
        sensorConnectionLimit(service.toEngine())

    /** Role capability implied by a GAP appearance value, or null when it says nothing. */
    public fun roleCapabilityFromAppearance(appearance: Int?): SensorRoleCapability? =
        cscRoleCapabilityFromAppearance(appearance)?.toPublic()

    /** Roles a sensor occupies; an unclassified sensor occupies both. */
    public fun occupiedRoles(capability: SensorRoleCapability?): Set<SensorRole> =
        occupiedCscRoles(capability?.toEngine()).mapTo(linkedSetOf()) { it.toPublic() }

    public fun canAssignRole(
        address: String,
        candidate: SensorRoleCapability?,
        existing: Iterable<SensorRoleAssignment>,
    ): Boolean = canAssignCscRole(
        address,
        candidate?.toEngine(),
        existing.map { CscRoleAssignment(it.address, it.capability?.toEngine(), it.assignedRoles.toEngineRoles()) },
    )

    public fun canAssignService(
        address: String,
        service: DeviceService,
        links: Iterable<SensorLink>,
        candidateCapability: SensorRoleCapability? = null,
    ): Boolean = canAssignSensorService(address, service.toEngine(), links.map { it.toEngine() }, candidateCapability?.toEngine())

    /** Distinct devices with an active link carrying [service]. */
    public fun activeConnectionCount(links: Iterable<SensorLink>, service: DeviceService): Int =
        activeSensorConnectionCount(links.map { it.toEngine() }, service.toEngine())
}

private fun DeviceService.toEngine(): BtEngineState.BtServiceType = BtEngineState.BtServiceType.valueOf(name)

private fun SensorRole.toEngine(): BtEngineState.CscRole = BtEngineState.CscRole.valueOf(name)

private fun BtEngineState.CscRole.toPublic(): SensorRole = SensorRole.valueOf(name)

private fun Set<SensorRole>.toEngineRoles(): Set<BtEngineState.CscRole> = mapTo(linkedSetOf()) { it.toEngine() }

private fun SensorRoleCapability.toEngine() = BtEngineState.CscRoleCapability(
    roles = roles.toEngineRoles(),
    source = BtEngineState.CscRoleSource.valueOf(evidence.name),
)

private fun BtEngineState.CscRoleCapability.toPublic() = SensorRoleCapability(
    roles = roles.mapTo(linkedSetOf()) { it.toPublic() },
    evidence = SensorRoleEvidence.valueOf(source.name),
)

private fun ConnectionStatus.toBluetoothState(): Int = when (this) {
    ConnectionStatus.DISCONNECTED -> BluetoothProfile.STATE_DISCONNECTED
    ConnectionStatus.CONNECTING -> BluetoothProfile.STATE_CONNECTING
    ConnectionStatus.CONNECTED -> BluetoothProfile.STATE_CONNECTED
    ConnectionStatus.DISCONNECTING -> BluetoothProfile.STATE_DISCONNECTING
}

private fun SensorLink.toEngine() = BtEngineState.ConnectedDeviceLink(
    connectionKey = address.trim().uppercase(Locale.ROOT),
    address = address,
    connectionState = status.toBluetoothState(),
    activeServices = activeServices.mapTo(linkedSetOf()) { it.toEngine() },
    machineConnection = false,
    cscRoleCapability = capability?.toEngine(),
    cscAssignedRoles = assignedRoles.toEngineRoles(),
)
