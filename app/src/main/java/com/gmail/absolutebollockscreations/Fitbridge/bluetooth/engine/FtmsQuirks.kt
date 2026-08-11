package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import java.util.UUID

/**
 * Declarative overrides for machines whose FTMS data characteristic does not match the
 * specification (plan Phase 4c).
 *
 * ## Why a registry rather than a subclass per machine
 *
 * TMIW expresses each of these as a descriptor subclass overriding `processFlag`. That works,
 * but it puts device identity, field layout and firmware notes in three different places and
 * makes it impossible to ask "which machines are we second-guessing, and on what evidence".
 * Here a quirk is data: a fingerprint, a layout, the evidence behind it, and what to do with a
 * packet from that machine that the fingerprint does not match.
 *
 * ## The distinction that matters
 *
 * **Present-but-ignored** means the octets are in the packet and must be stepped over.
 * **Absent-despite-flag** means the flag claims the field but no octets exist, so the cursor
 * must *not* advance. Conflating them misaligns everything downstream of the error — which is
 * exactly the failure D38 was written to stop, and a quirk is a licence to reintroduce it by
 * hand. [FtmsLayoutStep.Skip] and the plain absence of a step are therefore different things,
 * and both are spelled out explicitly in every layout below.
 *
 * ## Fingerprints are required, not optional
 *
 * A quirk fires only on an exact flag word and packet length. A machine of the same model whose
 * firmware sends anything else falls back to the standard parser under
 * [UnknownPacketPolicy.FALL_BACK_TO_STANDARD], because a layout verified against one firmware
 * revision is evidence about that revision and nothing more. TMIW's CardioStrong correction is
 * the counter-example this rule exists to avoid: it applies a ten-times distance scale to any
 * device whose name starts `FS-IB50`, with no flag or length gate, so a firmware that ever
 * reported metres correctly would silently give every user ten times their real distance.
 */

/** One field in an overridden layout, in wire order. */
internal sealed interface FtmsLayoutStep {
    /** Decode [width] octets as [field]. */
    data class Read(val field: FtmsQuirkField, val width: Int) : FtmsLayoutStep

    /**
     * Step over [width] octets that are present but carry nothing FitBridge will publish.
     * Distinct from omitting the step entirely, which means the field is absent from the packet.
     */
    data class Skip(val width: Int, val why: String) : FtmsLayoutStep
}

/** Fields an override can produce. Deliberately small: a quirk decodes, it does not derive. */
internal enum class FtmsQuirkField {
    STROKE_RATE_HALF_SPM,
    STROKE_COUNT,
    TOTAL_DISTANCE_M,
    PACE_SECONDS_PER_500M,
    INSTANT_POWER_W,
    TOTAL_ENERGY_KCAL,
    ENERGY_PER_HOUR_KCAL,
    ENERGY_PER_MINUTE_KCAL,
    HEART_RATE_BPM,
}

internal enum class UnknownPacketPolicy {
    /** Packet does not match the fingerprint: parse it as an ordinary FTMS frame. */
    FALL_BACK_TO_STANDARD,
}

internal data class FtmsQuirk(
    val id: String,
    /** Lowercased device-name prefix. The only identity these machines expose. */
    val namePrefix: String,
    val characteristic: UUID,
    /** Exact flag words this layout is evidenced for. */
    val flagWords: Set<Int>,
    val packetLength: Int,
    val layout: List<FtmsLayoutStep>,
    val evidence: String,
    val firmwareNotes: String,
    val unknownPacketPolicy: UnknownPacketPolicy = UnknownPacketPolicy.FALL_BACK_TO_STANDARD,
) {
    /** Total octets the layout accounts for, including the flag word. */
    val declaredWidth: Int
        get() = 2 + layout.sumOf {
            when (it) {
                is FtmsLayoutStep.Read -> it.width
                is FtmsLayoutStep.Skip -> it.width
            }
        }

    fun matches(deviceName: String?, characteristic: UUID, flags: Int, length: Int): Boolean =
        deviceName != null &&
            deviceName.lowercase().startsWith(namePrefix) &&
            characteristic == this.characteristic &&
            flags in flagWords &&
            length == packetLength
}

internal object FtmsQuirkRegistry {

    /**
     * Mr Captain rower (`XG` name prefix), Rower Data `0x2AD1`, 20 octets.
     *
     * Layout derived numerically from the pinned TMIW corpus rather than by reading TMIW's
     * descriptor: every field below is confirmed against `ftms_mr_captain` case 2, which
     * consumes exactly 20 of 20 octets and reproduces all nine of TMIW's decoded values.
     *
     * Three departures from FTMS Rower Data:
     *
     *  - **Average Pace is absent despite flag bit 4 being set.** The cursor must not advance.
     *  - **The expended-energy block is reordered and resized.** Standard order is total
     *    (uint16), per hour (uint16), per minute (uint8) — five octets. This machine sends per
     *    minute first, as *two* octets, then total, then per hour: six octets.
     *  - **Elapsed time is a single octet and is known-broken.** TMIW records that it "goes
     *    up/down". It is stepped over, never published: the session clock remains the fallback
     *    for this family, and that exception does not weaken D29 for valid machine counters.
     *
     * `0x0B42` carries a different flag set but TMIW applies this same fixed layout to both, so
     * both are registered. Only `0x0B3C` has vectors; `0x0B42` is corroborated from TMIW alone
     * and is recorded as such rather than claimed as verified.
     */
    private val MR_CAPTAIN_ROWER = FtmsQuirk(
        id = "mr-captain-rower-20",
        namePrefix = "xg",
        characteristic = BluetoothIds.UUID_ROWING_MACHINE_DATA,
        flagWords = setOf(0x0B3C, 0x0B42),
        packetLength = 20,
        layout = listOf(
            FtmsLayoutStep.Read(FtmsQuirkField.STROKE_RATE_HALF_SPM, 1),
            FtmsLayoutStep.Read(FtmsQuirkField.STROKE_COUNT, 2),
            FtmsLayoutStep.Read(FtmsQuirkField.TOTAL_DISTANCE_M, 3),
            FtmsLayoutStep.Read(FtmsQuirkField.PACE_SECONDS_PER_500M, 2),
            // Average Pace (bit 4) is flagged but absent: no step, no advance.
            FtmsLayoutStep.Read(FtmsQuirkField.INSTANT_POWER_W, 2),
            FtmsLayoutStep.Read(FtmsQuirkField.ENERGY_PER_MINUTE_KCAL, 2),
            FtmsLayoutStep.Read(FtmsQuirkField.TOTAL_ENERGY_KCAL, 2),
            FtmsLayoutStep.Read(FtmsQuirkField.ENERGY_PER_HOUR_KCAL, 2),
            FtmsLayoutStep.Skip(1, "elapsed time, one octet and known-broken; drop, do not publish"),
            FtmsLayoutStep.Read(FtmsQuirkField.HEART_RATE_BPM, 1),
        ),
        evidence = "TMIW@85f2ee7 test/mr_captain_test.dart, 7 vectors; layout verified " +
            "numerically against case 2 (20/20 octets, 9/9 fields).",
        firmwareNotes = "Name prefix XG. Average pace absent despite flag; energy block " +
            "reordered with a two-octet per-minute field; elapsed octet unusable.",
    )

    /**
     * Registered quirks. Two families named in plan Phase 4c are deliberately absent:
     *
     *  - **CardioStrong IB50** reports total distance in decametres. The rule is simple, but
     *    plan §4c requires a fingerprint and there is no capture — no flag word, no packet
     *    length, no firmware revision. It also needs the user-visible correction toggle the
     *    plan specifies. Shipping it on a bare name prefix would reproduce exactly the ungated
     *    behaviour this registry's documentation criticises.
     *  - **Life Fitness bike cadence**, present despite its flag bit being clear, has no
     *    captured flag word at all — plan §4c records it as "unverified, capture needed". A
     *    fingerprint cannot be invented, so the family cannot ship a quirk.
     *
     * Both are open items, not omissions. See the implementation log.
     */
    val all: List<FtmsQuirk> = listOf(MR_CAPTAIN_ROWER)

    fun find(deviceName: String?, characteristic: UUID, flags: Int, length: Int): FtmsQuirk? =
        all.firstOrNull { it.matches(deviceName, characteristic, flags, length) }
}
