package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi
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
@GattLibraryInternalApi
sealed interface FtmsLayoutStep {
    /** Decode [width] octets as [field]. */
    data class Read(val field: FtmsQuirkField, val width: Int) : FtmsLayoutStep

    /**
     * Step over [width] octets that are present but carry nothing FitBridge will publish.
     * Distinct from omitting the step entirely, which means the field is absent from the packet.
     */
    data class Skip(val width: Int, val why: String) : FtmsLayoutStep
}

/** Fields an override can produce. Deliberately small: a quirk decodes, it does not derive. */
@GattLibraryInternalApi
enum class FtmsQuirkField {
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

@GattLibraryInternalApi
enum class UnknownPacketPolicy {
    /** Packet does not match the fingerprint: parse it as an ordinary FTMS frame. */
    FALL_BACK_TO_STANDARD,
}

@GattLibraryInternalApi
data class FtmsQuirk(
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
    /**
     * Flag bits the machine fails to set for fields it nonetheless sends.
     *
     * **A second, weaker kind of quirk, and the distinction is worth keeping sharp.** A [layout]
     * says "ignore the specification for this packet and read these octets instead" — maximum
     * power, maximum risk, and it is why every layout is verified octet-by-octet against a
     * pinned vector. A flag correction says only "this machine under-reports its own flag word;
     * the fields are where the specification puts them". The standard walk then does all the
     * decoding, so a flag correction cannot misalign a field the way a hand-written layout can.
     *
     * It is only admissible when packet-length arithmetic proves it. If the corrected flag word
     * accounts for exactly the observed length while the as-sent word does not, the missing
     * field is present at its standard offset and nothing has been guessed. That check is not
     * optional decoration — it is the entire evidential basis, and
     * `FtmsQuirkRegistryTest` performs it for every registered correction.
     *
     * Mutually exclusive with [layout]: a quirk either replaces the walk or repairs its input.
     */
    val forceFlagBits: Int = 0,
) {
    init {
        require(layout.isNotEmpty() || forceFlagBits != 0) {
            "$id declares neither a layout nor a flag correction"
        }
        require(layout.isEmpty() || forceFlagBits == 0) {
            "$id must either replace the walk or repair its input, not both"
        }
        require(flagWords.none { it and forceFlagBits != 0 }) {
            "$id corrects a bit that its own fingerprint already has set"
        }
    }

    /** True when this quirk repairs the flag word rather than replacing the field walk. */
    val isFlagCorrection: Boolean get() = forceFlagBits != 0

    /** The flag word the standard walk should see for a matching packet. */
    fun correctedFlags(flags: Int): Int = flags or forceFlagBits

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

@GattLibraryInternalApi
object FtmsQuirkRegistry {

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
     * Life Fitness indoor bike (`LF` name prefix), Indoor Bike Data `0x2AD2`, 26 and 27 octets.
     *
     * The machine sends Instantaneous Cadence but leaves its presence bit clear. Registered as a
     * flag correction rather than a layout because **packet-length arithmetic proves the field
     * is at its standard offset**, so the standard walk needs its input repaired and nothing
     * else:
     *
     * ```
     *   0x09FA as sent          →  24 octets   observed 26   short by exactly the 2-octet field
     *   0x09FA | cadence bit    →  26 octets   observed 26   exact
     *   0x0BFA as sent          →  25 octets   observed 27
     *   0x0BFA | cadence bit    →  27 octets   observed 27   exact
     * ```
     *
     * **Evidence, and its limit.** Q28 blocked this quirk for two phases because §4c forbids a
     * quirk without a fingerprint and no capture existed. TMIW issue #462's official-app HCI
     * supplies one: 115 physical indoor-bike packets, every one with bit 2 clear, in exactly two
     * flag/length families. Both are registered.
     *
     * TMIW's parser tests carry a third family, `0x19FA`/28, whose arithmetic reconciles the
     * same way (26 → 28). It is **deliberately not registered**: it appears in no physical
     * packet, and a fingerprint's whole purpose is to assert that a real machine behaves this
     * way. Reference coverage is not that assertion. If a capture ever shows it, adding it is a
     * one-line change with the evidence to justify it.
     */
    private val LIFE_FITNESS_BIKE_CADENCE_26 = FtmsQuirk(
        id = "life-fitness-bike-cadence-26",
        namePrefix = "lf",
        characteristic = BluetoothIds.UUID_BIKING_DATA,
        flagWords = setOf(0x09FA),
        packetLength = 26,
        layout = emptyList(),
        forceFlagBits = Flags.BIKE_CADENCE,
        evidence = "TMIW issue #462 official-app HCI, 22 physical packets at 0x09FA/26, all " +
            "with the cadence presence bit clear; corpus " +
            "life_fitness_issue_462_indoor_bike_capture.json. Length arithmetic: 24 as sent, " +
            "26 corrected, 26 observed.",
        firmwareNotes = "Name prefix LF. Exact commercial model and firmware are not recorded " +
            "in the capture; this is upstream hardware-observed evidence under D55, not tier H.",
    )

    private val LIFE_FITNESS_BIKE_CADENCE_27 = FtmsQuirk(
        id = "life-fitness-bike-cadence-27",
        namePrefix = "lf",
        characteristic = BluetoothIds.UUID_BIKING_DATA,
        flagWords = setOf(0x0BFA),
        packetLength = 27,
        layout = emptyList(),
        forceFlagBits = Flags.BIKE_CADENCE,
        evidence = "TMIW issue #462 official-app HCI, 93 physical packets at 0x0BFA/27, all " +
            "with the cadence presence bit clear. Length arithmetic: 25 as sent, 27 corrected, " +
            "27 observed. Differs from the 26-octet family only by the heart-rate bit.",
        firmwareNotes = "Name prefix LF. Same limits as the 26-octet family.",
    )

    /**
     * Registered quirks. One family named in plan Phase 4c remains deliberately absent:
     *
     *  - **CardioStrong IB50** reports total distance in decametres. The rule is simple, but
     *    plan §4c requires a fingerprint and there is no capture — no flag word, no packet
     *    length, no firmware revision. It also needs the user-visible correction toggle the
     *    plan specifies. Shipping it on a bare name prefix would reproduce exactly the ungated
     *    behaviour this registry's documentation criticises.
     *
     * The Life Fitness bike cadence quirk was in that list until 2026-08-12 for the same reason,
     * and left it the way §4c intends: a capture arrived, so a fingerprint exists.
     */
    val all: List<FtmsQuirk> = listOf(
        MR_CAPTAIN_ROWER,
        LIFE_FITNESS_BIKE_CADENCE_26,
        LIFE_FITNESS_BIKE_CADENCE_27,
    )

    /**
     * The characteristics whose parser actually calls [find].
     *
     * **Registering a quirk is not enough to make it fire.** The walker for its characteristic
     * has to consult this registry, and four of the six do not: `doTreadmill`, `doElliptical`,
     * `doStepper` and `doClimber` decode straight from the flag word with no registry lookup.
     * That is harmless while no quirk targets them and silently wrong the moment one does — a
     * quirk registered against `0x2ACD` today would be complete, evidenced, tested at the
     * registry level, and never reached by a packet.
     *
     * This set exists so that failure is impossible rather than merely unlikely.
     * `FtmsQuirkRegistryTest` asserts every registered quirk targets a characteristic in it, so
     * registering one for an unwired walker fails the build with a message naming the walker to
     * change. Adding a characteristic here without wiring its parser fails too, because the same
     * test proves each entry actually applies a quirk end to end.
     *
     * The asymmetry is deliberate for now — wiring a lookup into a walker that has nothing to
     * look up adds a parameter to four public functions and their call sites for no behaviour.
     * When a fifth quirk needs one of them, wire that walker and add it here.
     */
    val CHARACTERISTICS_CONSULTING_REGISTRY: Set<UUID> = setOf(
        BluetoothIds.UUID_ROWING_MACHINE_DATA,
        BluetoothIds.UUID_BIKING_DATA,
    )

    fun find(deviceName: String?, characteristic: UUID, flags: Int, length: Int): FtmsQuirk? =
        all.firstOrNull { it.matches(deviceName, characteristic, flags, length) }
}
