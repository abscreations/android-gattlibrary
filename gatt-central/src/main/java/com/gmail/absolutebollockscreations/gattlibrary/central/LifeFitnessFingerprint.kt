package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi
import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.LifeFitnessUuids

/**
 * Post-discovery identification of a Life Fitness setup surface (plan §6, D52, D54).
 *
 * ## Why this is a pure function over an inventory
 *
 * Everything here is a decision, and Phase 4 §11.7 established what happens to decisions sealed
 * inside a GATT handler: they need a live `BluetoothGatt` to reach, so they go untested, and a
 * defect ships through the gap. The handler that uses this owns callbacks and queue targeting;
 * the rule for *what counts as a Life Fitness machine* lives here, where a test can drive every
 * decline path without a connection.
 *
 * ## What identifies the family, and what deliberately does not
 *
 * **Not the parent service UUIDs, even though they are known.** D54 records
 * `fb694b90-…` and `fb694b91-…` from two independent physical captures, so FitBridge could
 * simply require them. D52's rule is kept instead: a parent qualifies by *containing the right
 * characteristics under one service with the right properties*. The captured UUIDs are reported
 * as [Resolved.canonicalParents] when they match, which is useful confidence and useful
 * logging, and they are never a requirement — a firmware variant that moves its characteristics
 * to different parents still completes setup rather than silently failing.
 *
 * **Not the name.** `LF…` is supporting post-connect evidence, never authority to write. This
 * matters more here than for a measurement family: the payoff for a wrong match is writing a
 * vendor handshake into an unrelated machine.
 *
 * **Not a four-character alias.** TMIW matches these characteristics by comparing characters
 * 5–8 of a UUID, which §8.4 documents as the mistake that produces a wrong identity for one
 * characteristic in four across this very family — `ce78e85e-9cb6-…` shares no base with the
 * `5da5____-9cb2-…` group. Every comparison here is on a full 128-bit value.
 *
 * ## Status is resolved, and still not a gate
 *
 * `5da551de-…` is a real characteristic (D54) whose values `07` and `04 01` were observed at two
 * points of the sequence. Two samples are not a vocabulary. It resolves only inside the
 * fingerprinted control parent, with notify or indicate and a CCCD, and its **absence declines
 * the status path without failing the fingerprint** — a machine that completes setup while
 * telling us nothing is still a machine that completes setup.
 */
@GattLibraryInternalApi
internal object LifeFitnessFingerprint {

    /**
     * Whether a discovered GATT database is a Life Fitness setup surface.
     *
     * The convenience the adapter layer wants: `candidatesFor` needs a boolean to set
     * `requiresSetup`, not a resolved pair, and asking for the pair there would invite callers
     * to keep it and let it drift from the link it came from.
     */
    fun isSetupSurface(services: List<ServiceInventory>): Boolean =
        identify(services) is Result.Match

    /** One discovered service and the characteristics under it, as the handler will see them. */
    data class ServiceInventory(
        val serviceUuid: UUID,
        val characteristics: List<CharacteristicInventory>,
    )

    /**
     * One discovered characteristic.
     *
     * Properties are carried as booleans rather than the Android bitmask so this file has no
     * platform dependency and a test can state intent directly.
     */
    data class CharacteristicInventory(
        val uuid: UUID,
        val writable: Boolean = false,
        val notifyOrIndicate: Boolean = false,
        val hasCccd: Boolean = false,
    )

    /** Why a fingerprint declined. Distinct cases, because they call for different diagnostics. */
    enum class Decline {
        /** Neither required pair appeared anywhere in the inventory. */
        NOT_LIFE_FITNESS,

        /** One characteristic of a pair is present and its partner is not. */
        INCOMPLETE_PAIR,

        /** Both characteristics of a pair exist, but under different parent services. */
        SPLIT_PARENT,

        /** The pair is complete and co-located, but a characteristic cannot be written. */
        PROPERTY_INCOMPATIBLE,
    }

    /** A resolved setup surface. Parents are whatever was discovered, not what was expected. */
    data class Resolved(
        val controlService: UUID,
        val userService: UUID,
        /** Present only when the control parent exposes a usable status characteristic. */
        val statusCharacteristic: UUID?,
        /** True when both parents are the UUIDs recorded in D54. Confidence, not a condition. */
        val canonicalParents: Boolean,
    )

    sealed interface Result {
        data class Match(val resolved: Resolved) : Result
        data class NoMatch(val reason: Decline) : Result
    }

    private val CONTROL_PAIR = setOf(LifeFitnessUuids.UUID_LF_CONTROL_1, LifeFitnessUuids.UUID_LF_CONTROL_2)
    private val USER_PAIR =
        setOf(LifeFitnessUuids.UUID_LF_VENDOR_TOKEN_1, LifeFitnessUuids.UUID_LF_VENDOR_TOKEN_2)

    /**
     * Identify the control and user services in a discovered GATT database.
     *
     * Both pairs must resolve. A machine exposing a control surface with no user surface cannot
     * complete the transcript, so reporting a partial match would only defer the failure to the
     * point where profile writes begin.
     */
    fun identify(services: List<ServiceInventory>): Result {
        val control = locate(services, CONTROL_PAIR)
        val user = locate(services, USER_PAIR)

        // NOT_LIFE_FITNESS only when *neither* pair left any trace. If one pair resolved and the
        // other did not, the device is a Life Fitness machine with an inventory we cannot use,
        // and saying "not Life Fitness" would send a reader looking for the wrong problem.
        if (control is Located.Absent && user is Located.Absent) {
            return Result.NoMatch(Decline.NOT_LIFE_FITNESS)
        }
        (control as? Located.Failed)?.let { return Result.NoMatch(it.reason) }
        (user as? Located.Failed)?.let { return Result.NoMatch(it.reason) }
        if (control is Located.Absent || user is Located.Absent) {
            return Result.NoMatch(Decline.INCOMPLETE_PAIR)
        }

        val controlService = (control as Located.Found).serviceUuid
        val userService = (user as Located.Found).serviceUuid

        return Result.Match(
            Resolved(
                controlService = controlService,
                userService = userService,
                statusCharacteristic = resolveStatus(services, controlService),
                canonicalParents = controlService == LifeFitnessUuids.UUID_LF_CONTROL_SERVICE &&
                    userService == LifeFitnessUuids.UUID_LF_USER_SERVICE,
            ),
        )
    }

    /**
     * The status characteristic, when the control parent offers a usable one.
     *
     * Scoped to the fingerprinted control service rather than searched globally: a notify
     * characteristic with this UUID under some unrelated parent is not this machine's status
     * source, and accepting it would be the same class of error as alias matching.
     */
    private fun resolveStatus(services: List<ServiceInventory>, controlService: UUID): UUID? =
        services.firstOrNull { it.serviceUuid == controlService }
            ?.characteristics
            ?.firstOrNull {
                it.uuid == LifeFitnessUuids.UUID_LF_STATUS && it.notifyOrIndicate && it.hasCccd
            }
            ?.uuid

    private sealed interface Located {
        data class Found(val serviceUuid: UUID) : Located
        data class Failed(val reason: Decline) : Located
        data object Absent : Located
    }

    /**
     * Find the single service containing every UUID in [pair] with write properties.
     *
     * The ordering of checks is the substance: co-location is tested before properties, so a
     * split inventory reports `SPLIT_PARENT` rather than the `PROPERTY_INCOMPATIBLE` it would
     * also technically satisfy. A decline reason that names the first real problem is worth
     * more than one that names a true but incidental one.
     */
    private fun locate(services: List<ServiceInventory>, pair: Set<UUID>): Located {
        val seen = services.flatMap { service -> service.characteristics.map { it.uuid } }
            .filterTo(mutableSetOf()) { it in pair }
        if (seen.isEmpty()) return Located.Absent
        if (seen.size < pair.size) return Located.Failed(Decline.INCOMPLETE_PAIR)

        val whole = services.filter { service ->
            pair.all { required -> service.characteristics.any { it.uuid == required } }
        }
        if (whole.isEmpty()) return Located.Failed(Decline.SPLIT_PARENT)

        // A duplicated inventory is not ambiguity worth failing over: any parent carrying the
        // complete pair with correct properties can serve the transcript, and the first is as
        // good as the second. What must never happen is composing one from two parents.
        val usable = whole.firstOrNull { service ->
            pair.all { required ->
                service.characteristics.any { it.uuid == required && it.writable }
            }
        } ?: return Located.Failed(Decline.PROPERTY_INCOMPATIBLE)

        return Located.Found(usable.serviceUuid)
    }
}
