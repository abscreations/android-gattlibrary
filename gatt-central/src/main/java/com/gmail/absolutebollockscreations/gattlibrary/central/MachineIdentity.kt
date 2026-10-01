package com.gmail.absolutebollockscreations.gattlibrary.central

import java.util.UUID

/**
 * What a downstream bridge needs to describe the machine it is bridging: a name for the advertised
 * device, the machine's address, and the services it exposes. Read live with
 * [FitnessGattClient.machineIdentity]; each read is a fresh snapshot.
 *
 * The name comes in the pieces the engine has, not as one chosen string, because choosing and
 * pinning a stable name is the bridge's policy: a name that flipped between the scan name and the
 * platform name would restart the bridge each time.
 */
public class MachineIdentity internal constructor(
    /** The connected machine link's address as the platform reports it, or null with no machine link (an advertisement-only machine). */
    public val gattAddress: String?,
    /** "<model> #<system ID>" for an advertisement-only machine, else null. */
    public val advertisementLabel: String?,
    /** The machine's name from the discovery catalog, if the scanner saw one. */
    public val scanName: String?,
    /** The platform's name for the device; null when unnamed or when the platform refused the read. */
    public val platformName: String?,
    /** GATT services the machine link exposes, including vendor services; empty without a machine link. */
    public val serviceUuids: Set<UUID>,
    /** The advertisement-only machine family's id, else null. */
    public val advertisementCandidateId: String?,
)
