package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.BleDefaults
import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi
import com.gmail.absolutebollockscreations.gattlibrary.core.SigUserDataUnits

/**
 * The user-entered profile a Life Fitness machine's handshake consumes (plan §6, D56).
 *
 * ## Why FitBridge asks for this at all
 *
 * It did not, until 2026-08-12. Q6 was closed by D24/D25 with "send placeholders for every
 * field; collect no personal data for an unverified handshake", on the reasoning that nobody
 * can bisect which fields are mandatory without a machine to bisect against. D56 supersedes
 * that by explicit user decision: the values are treated as necessary and collected.
 *
 * ## What is and is not known
 *
 * TMIW is the entire precedent, and it is weaker than it first appears. It exposes exactly
 * these three settings in a section labelled "Life Fitness", described as *"Used for Life
 * Fitness handshake protocol"* — **and defaults them to `" "`, `" "` and `"@"`.** It ships
 * placeholders and makes them overridable, which moves the question to the user rather than
 * answering it. The four official-app captures in TMIW issue #462 redact precisely the values
 * that would settle it.
 *
 * So FitBridge collects the fields **without claiming the protocol demands real ones**, and
 * that is why [isPopulated] checks only for non-blank. No email format check, no name character
 * policy, no minimum length. A user who types `a` and `a@a` has satisfied FitBridge, and if
 * that also satisfies the machine then the requirement was never real and D56 should be
 * revisited.
 *
 * ## Handling rules that are not optional
 *
 * - Values are written **only** to characteristics under a fingerprinted Life Fitness user
 *   service. A profile write that cannot name its target service does not happen.
 * - Nothing is pre-filled from an account, contact list or system source.
 * - Every value is redaction-registered by UUID in `SensitiveCharacteristics`, so no capture
 *   can carry it at any level.
 */
data class LifeFitnessProfile(
    val firstName: String,
    val lastName: String,
    val email: String,
    /**
     * The rest of what the transcript writes.
     *
     * **Deliberately not part of [missingFields].** A name or an email has no honest default —
     * any value FitBridge invents is either a placeholder that may not satisfy the machine or a
     * stranger's data — so a blank one has to be asked about. Gender, weight and height have
     * neutral defaults that are truthful *as* defaults, because the machine uses them for
     * calorie arithmetic rather than to identify anyone. Blocking a connection on them would
     * make the prompt fire for users who have nothing to fix.
     *
     * They are still personal data and are redacted identically; the difference is only about
     * what blocks a connection.
     */
    val gender: Int = BleDefaults.LF_DEFAULT_GENDER,
    val weightKg: Int = BleDefaults.LF_DEFAULT_WEIGHT_KG,
    val heightCm: Int = BleDefaults.LF_DEFAULT_HEIGHT_CM,
) {
    /** Weight in the SIG `0x2A98` unit: `uint16` of 0.005 kg. */
    val weightWireUnits: Int get() = SigUserDataUnits.weightToWireUnits(weightKg)

    /** Height in the SIG `0x2A8E` unit: `uint16` of 0.01 m. */
    val heightWireUnits: Int get() = SigUserDataUnits.heightToWireUnits(heightCm)

    /**
     * Whether every required field carries something.
     *
     * Blank-or-whitespace is the only rejection. TMIW's own default first name is a single
     * space, so even "is it whitespace" is a stricter test than the reference implementation
     * applies — deliberately, because a space is indistinguishable from an empty field to the
     * person filling the form, and a gate the user cannot see the reason for is worse than no
     * gate.
     */
    val isPopulated: Boolean
        get() = missingFields.isEmpty()

    /** Which fields are blank, in the order the settings screen presents them. */
    val missingFields: List<LifeFitnessProfileField>
        get() = buildList {
            if (firstName.isBlank()) add(LifeFitnessProfileField.FIRST_NAME)
            if (lastName.isBlank()) add(LifeFitnessProfileField.LAST_NAME)
            if (email.isBlank()) add(LifeFitnessProfileField.EMAIL)
        }

    companion object {
        val EMPTY = LifeFitnessProfile(firstName = "", lastName = "", email = "")
    }
}

enum class LifeFitnessProfileField(val settingsLabel: String) {
    FIRST_NAME("First name"),
    LAST_NAME("Last name"),
    EMAIL("Email address"),
}

/**
 * Whether a connection attempt may proceed into the Life Fitness setup transcript.
 *
 * Separated from [LifeFitnessProfile] because it answers a different question: the profile
 * knows whether it is complete, and this knows what to *do* about an incomplete one on a
 * specific connection. Keeping them apart is what lets the gate be exercised for a machine that
 * is not Life Fitness at all, which is the case most likely to regress.
 */
@GattLibraryInternalApi
sealed interface LifeFitnessSetupGate {

    /** Not a Life Fitness machine, or not one whose setup surface was fingerprinted. */
    data object NotApplicable : LifeFitnessSetupGate

    /** Fingerprinted and the profile is complete: the transcript may run. */
    data object Ready : LifeFitnessSetupGate

    /**
     * Fingerprinted, but the user has not supplied everything the handshake writes.
     *
     * The active attempt cannot be resumed after Settings changes because the transcript is
     * connection-scoped and never started. The UI therefore releases the machine link before
     * showing this prompt; the next attempt begins with fresh discovery and a complete transcript.
     */
    data class NeedsProfile(val missing: List<LifeFitnessProfileField>) : LifeFitnessSetupGate
}

/**
 * Decide whether the Life Fitness setup transcript may begin.
 *
 * @param fingerprinted whether the discovered GATT database produced a Life Fitness setup
 * surface. **Not a device name and not an advertisement** — §6 admits the link on standard FTMS
 * advertising and only claims the family after `LifeFitnessFingerprint` resolves both
 * characteristic pairs, so a machine merely called `LF Something` never triggers this prompt.
 */
@GattLibraryInternalApi
fun lifeFitnessSetupGate(
    fingerprinted: Boolean,
    profile: LifeFitnessProfile,
): LifeFitnessSetupGate = when {
    !fingerprinted -> LifeFitnessSetupGate.NotApplicable
    profile.isPopulated -> LifeFitnessSetupGate.Ready
    else -> LifeFitnessSetupGate.NeedsProfile(profile.missingFields)
}
