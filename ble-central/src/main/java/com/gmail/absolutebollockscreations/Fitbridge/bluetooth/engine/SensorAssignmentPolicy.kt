package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.bluetooth.BluetoothProfile
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.core.FitBridgeBleInternalApi

// CSC-role and sensor-slot policy, split from the app's SensorClass.kt (LIB-1/S1). The engine
// calls this (CSC Feature resolution, sensor admission) and it depends only on BtEngineState,
// so it belongs to the future ble-central module. Logic is unchanged; canAssignCscRole now
// calls sensorConnectionLimit(CSC) directly, which is what SensorClass.CSC.maxConnections
// returned.

fun sensorConnectionLimit(serviceType: BtEngineState.BtServiceType): Int =
    when (serviceType) {
        BtEngineState.BtServiceType.HeartRate,
        BtEngineState.BtServiceType.RSC,
        BtEngineState.BtServiceType.Power,
        -> 1

        BtEngineState.BtServiceType.CSC -> 2
        BtEngineState.BtServiceType.FTMS,
        BtEngineState.BtServiceType.Glucose,
        -> 0
    }

@FitBridgeBleInternalApi
const val APPEARANCE_CYCLING_SPEED_SENSOR = 0x0482
@FitBridgeBleInternalApi
const val APPEARANCE_CYCLING_CADENCE_SENSOR = 0x0483
@FitBridgeBleInternalApi
const val APPEARANCE_CYCLING_SPEED_CADENCE_SENSOR = 0x0485

private val ALL_CSC_ROLES = BtEngineState.CscRole.entries.toSet()

fun cscRoleCapabilityFromAppearance(
    appearance: Int?,
): BtEngineState.CscRoleCapability? = when (appearance) {
    APPEARANCE_CYCLING_SPEED_SENSOR -> BtEngineState.CscRoleCapability(
        roles = setOf(BtEngineState.CscRole.SPEED),
        source = BtEngineState.CscRoleSource.APPEARANCE,
    )

    APPEARANCE_CYCLING_CADENCE_SENSOR -> BtEngineState.CscRoleCapability(
        roles = setOf(BtEngineState.CscRole.CADENCE),
        source = BtEngineState.CscRoleSource.APPEARANCE,
    )

    APPEARANCE_CYCLING_SPEED_CADENCE_SENSOR -> BtEngineState.CscRoleCapability(
        roles = ALL_CSC_ROLES,
        source = BtEngineState.CscRoleSource.APPEARANCE,
    )

    else -> null
}

/** Feature bit 0 is wheel/speed and bit 1 is crank/cadence. Zero is not a usable CSC role. */
@FitBridgeBleInternalApi
fun cscRoleCapabilityFromFeature(
    featureBits: Int,
): BtEngineState.CscRoleCapability? {
    val roles = buildSet {
        if (featureBits and 0x0001 != 0) add(BtEngineState.CscRole.SPEED)
        if (featureBits and 0x0002 != 0) add(BtEngineState.CscRole.CADENCE)
    }
    return roles.takeIf { it.isNotEmpty() }?.let {
        BtEngineState.CscRoleCapability(it, BtEngineState.CscRoleSource.FEATURE)
    }
}

/** Missing classification occupies both roles until the mandatory Feature read resolves it. */
fun occupiedCscRoles(
    capability: BtEngineState.CscRoleCapability?,
): Set<BtEngineState.CscRole> = capability?.roles?.takeIf { it.isNotEmpty() } ?: ALL_CSC_ROLES

data class CscRoleAssignment(
    val address: String,
    val capability: BtEngineState.CscRoleCapability?,
    val assignedRoles: Set<BtEngineState.CscRole> = emptySet(),
)

@FitBridgeBleInternalApi
data class ActiveCscHandler(
    val connectionKey: String,
    val linkGeneration: Long,
    val address: String,
    val capability: BtEngineState.CscRoleCapability?,
    val assignedRoles: Set<BtEngineState.CscRole> = emptySet(),
    val requestedRoles: Set<BtEngineState.CscRole>? = null,
)

@FitBridgeBleInternalApi
data class CscFeatureResolution(
    val capability: BtEngineState.CscRoleCapability,
    val assignedRoles: Set<BtEngineState.CscRole>,
    val disconnectConnectionKey: String? = null,
    val disconnectReason: String? = null,
)

/** Defensive post-read resolution for two provisional links admitted by a connection race. */
@FitBridgeBleInternalApi
fun resolveCscFeatureRoles(
    resolvedConnectionKey: String,
    featureBits: Int,
    activeHandlers: Iterable<ActiveCscHandler>,
): CscFeatureResolution? {
    val capability = cscRoleCapabilityFromFeature(featureBits) ?: return null
    val handlers = activeHandlers.toList()
    val resolved = handlers.firstOrNull { it.connectionKey == resolvedConnectionKey } ?: return null
    val olderOccupied = handlers
        .filter { it.connectionKey != resolved.connectionKey && it.linkGeneration < resolved.linkGeneration }
        .flatMapTo(linkedSetOf()) { it.effectiveAssignedRoles() }
    val available = capability.roles - olderOccupied
    val assigned = resolved.requestedRoles
        ?.takeIf { it.isNotEmpty() }
        ?.intersect(available)
        ?: available
    if (assigned.isEmpty()) {
        val requested = resolved.requestedRoles.orEmpty()
        val unsupportedRequest = requested.isNotEmpty() &&
            requested.intersect(capability.roles).isEmpty()
        val conflictRoles = capability.roles.intersect(olderOccupied)
            .joinToString(" and ") { it.name.lowercase() }
        return CscFeatureResolution(
            capability = capability,
            assignedRoles = emptySet(),
            disconnectConnectionKey = resolved.connectionKey,
            disconnectReason = if (unsupportedRequest) {
                "CSC requested role unsupported by Feature"
            } else {
                "CSC role conflict: $conflictRoles already occupied"
            },
        )
    }

    val conflictingNewer = handlers.filter { other ->
        other.connectionKey != resolved.connectionKey &&
            other.linkGeneration > resolved.linkGeneration &&
            other.effectiveAssignedRoles().intersect(assigned).isNotEmpty()
    }
    if (conflictingNewer.isEmpty()) return CscFeatureResolution(capability, assigned)

    val loser = conflictingNewer.maxBy { it.linkGeneration }
    val conflictRoles = loser.effectiveAssignedRoles().intersect(assigned)
        .joinToString(" and ") { it.name.lowercase() }
    return CscFeatureResolution(
        capability = capability,
        assignedRoles = assigned,
        disconnectConnectionKey = loser.connectionKey,
        disconnectReason = "CSC role conflict: $conflictRoles already occupied",
    )
}

private fun ActiveCscHandler.effectiveAssignedRoles(): Set<BtEngineState.CscRole> =
    assignedRoles.ifEmpty { occupiedCscRoles(capability) }

fun canAssignCscRole(
    address: String,
    candidateCapability: BtEngineState.CscRoleCapability?,
    existingAssignments: Iterable<CscRoleAssignment>,
): Boolean {
    val targetAddress = canonicalConnectionAddress(address)
    val active = existingAssignments
        .groupBy { canonicalConnectionAddress(it.address) }
        .map { (canonicalAddress, assignments) ->
            CscRoleAssignment(
                canonicalAddress,
                assignments.firstNotNullOfOrNull { it.capability },
                assignments.flatMapTo(linkedSetOf()) { it.assignedRoles },
            )
        }
    if (active.any { it.address == targetAddress }) return true
    if (active.size >= sensorConnectionLimit(BtEngineState.BtServiceType.CSC)) return false
    val occupied = active.flatMapTo(linkedSetOf()) { assignment ->
        assignment.assignedRoles.ifEmpty { occupiedCscRoles(assignment.capability) }
    }
    val candidateRoles = candidateCapability?.roles ?: return active.isEmpty()
    return candidateRoles.any { it !in occupied }
}

@FitBridgeBleInternalApi
fun activeSensorConnectionCount(
    links: Iterable<BtEngineState.ConnectedDeviceLink>,
    serviceType: BtEngineState.BtServiceType,
): Int = links
    .filter { link ->
        link.connectionState != BluetoothProfile.STATE_DISCONNECTED &&
            serviceType in link.activeServices
    }
    .map { canonicalConnectionAddress(it.address) }
    .distinct()
    .size

/** Existing same-device activation is idempotent and does not consume another slot. */
fun canAssignSensorService(
    address: String,
    serviceType: BtEngineState.BtServiceType,
    links: Iterable<BtEngineState.ConnectedDeviceLink>,
    candidateCscRoleCapability: BtEngineState.CscRoleCapability? = null,
): Boolean {
    if (address.isBlank()) return false
    val limit = sensorConnectionLimit(serviceType)
    if (limit <= 0) return false
    val activeAddresses = links
        .filter { link ->
            link.connectionState != BluetoothProfile.STATE_DISCONNECTED &&
                serviceType in link.activeServices
        }
        .mapTo(linkedSetOf()) { canonicalConnectionAddress(it.address) }
    val targetAddress = canonicalConnectionAddress(address)
    if (targetAddress in activeAddresses) return true
    if (serviceType == BtEngineState.BtServiceType.CSC) {
        val assignments = links
            .filter { link ->
                link.connectionState != BluetoothProfile.STATE_DISCONNECTED &&
                    serviceType in link.activeServices
            }
            .map { CscRoleAssignment(it.address, it.cscRoleCapability, it.cscAssignedRoles) }
        return canAssignCscRole(address, candidateCscRoleCapability, assignments)
    }
    return activeAddresses.size < limit
}
