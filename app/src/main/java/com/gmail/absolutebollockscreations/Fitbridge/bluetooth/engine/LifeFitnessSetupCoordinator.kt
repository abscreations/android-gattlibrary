package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import com.gmail.absolutebollockscreations.Fitbridge.LifeFitnessProfile
import com.gmail.absolutebollockscreations.Fitbridge.LifeFitnessProfileField
import com.gmail.absolutebollockscreations.Fitbridge.LifeFitnessSetupGate
import com.gmail.absolutebollockscreations.Fitbridge.lifeFitnessSetupGate

/**
 * Decides what a connected machine's setup path should do, and drives it (plan §6, unit 6.5a).
 *
 * ## The question nothing else answers
 *
 * `LifeFitnessFingerprint` says whether this GATT database is a Life Fitness setup surface.
 * `lifeFitnessSetupGate` says whether the user has supplied what the handshake writes.
 * `LifeFitnessSetup` says what to send. Each is complete and none of them decides *what this
 * connection should do next* — that composition is this class, and until it existed the three
 * were correct and inert.
 *
 * ## Why it takes an inventory rather than a GATT
 *
 * `AdapterLivenessCoordinator.candidatesFor` builds its candidates from
 * `discoveredServiceUuids` alone, so `requiresSetup` cannot be decided where every other
 * candidate property is: the fingerprint needs characteristics. Taking the inventory directly
 * keeps that decision testable and keeps this class free of Android types, so every branch —
 * including the ones a machine would have to misbehave to reach — is reachable from a test.
 *
 * ## Parents come from the device, not from the constants
 *
 * [Decision.Ready] carries a transcript targeted at whatever parents the fingerprint resolved.
 * D54 records canonical values and D52 keeps the runtime rule precisely so a firmware variant
 * that moves its characteristics still completes setup; building the transcript against
 * `BluetoothIds.UUID_LF_CONTROL_SERVICE` would quietly undo that.
 */
internal class LifeFitnessSetupCoordinator {

    sealed interface Decision {
        /** Not a Life Fitness setup surface. No prompt, no writes, no state change. */
        data class NotApplicable(val reason: LifeFitnessFingerprint.Decline) : Decision

        /**
         * A Life Fitness machine whose handshake cannot be attempted yet.
         *
         * **No writes are issued.** The connection stands, the machine stays silent because its
         * setup has not run, and the user is asked for the fields D56 requires.
         */
        data class NeedsProfile(val missing: List<LifeFitnessProfileField>) : Decision

        /** Fingerprinted and complete: run [transcript] against the resolved parents. */
        data class Ready(
            val resolved: LifeFitnessFingerprint.Resolved,
            val transcript: List<LifeFitnessSetup.Op>,
        ) : Decision
    }

    private var sequence: LifeFitnessSetupSequence? = null

    /**
     * Classify a discovered GATT database.
     *
     * Ordering matters: the fingerprint runs **before** the profile is consulted, so a machine
     * that is not Life Fitness can never produce a profile prompt. That is the failure this
     * ordering exists to prevent — a dialog demanding an email address because an unrelated
     * treadmill connected would be worse than the missing feature.
     */
    fun decide(
        services: List<LifeFitnessFingerprint.ServiceInventory>,
        profile: LifeFitnessProfile,
        ageYears: Int,
    ): Decision {
        val match = LifeFitnessFingerprint.identify(services)
        if (match is LifeFitnessFingerprint.Result.NoMatch) {
            return Decision.NotApplicable(match.reason)
        }
        val resolved = (match as LifeFitnessFingerprint.Result.Match).resolved

        return when (val gate = lifeFitnessSetupGate(fingerprinted = true, profile = profile)) {
            is LifeFitnessSetupGate.NeedsProfile -> Decision.NeedsProfile(gate.missing)
            LifeFitnessSetupGate.NotApplicable ->
                // Unreachable: the gate returns this only when `fingerprinted` is false, and it
                // was passed true. Mapped rather than thrown so a future change to the gate's
                // contract degrades into "do nothing" instead of crashing a connection.
                Decision.NotApplicable(LifeFitnessFingerprint.Decline.NOT_LIFE_FITNESS)
            LifeFitnessSetupGate.Ready -> Decision.Ready(
                resolved = resolved,
                transcript = LifeFitnessSetup.transcript(
                    control = resolved.controlService,
                    user = resolved.userService,
                    status = resolved.statusCharacteristic,
                    profile = profile,
                    ageYears = ageYears,
                ),
            )
        }
    }

    /** Begin a decided transcript. Returns the first operation to issue. */
    fun begin(decision: Decision.Ready): LifeFitnessSetupSequence.Outcome =
        LifeFitnessSetupSequence(decision.transcript).also { sequence = it }.start()

    /** Record the in-flight operation's completion. */
    fun onCompleted(success: Boolean): LifeFitnessSetupSequence.Outcome =
        sequence?.onCompleted(success) ?: LifeFitnessSetupSequence.Outcome.Complete

    /** Record that the settle bound elapsed. */
    fun onTimeout(): LifeFitnessSetupSequence.Outcome =
        sequence?.onTimeout() ?: LifeFitnessSetupSequence.Outcome.Complete

    /** Drop per-link state. A sequence must never survive the connection that started it. */
    fun reset() {
        sequence = null
    }

    companion object {
        /**
         * A payload-free failure detail for `MachineAdapterPolicy.setupFailed(reason)`.
         *
         * The policy's detail string reaches logs and the connection UI, and the operation label
         * it names is deliberately free of values — see `LifeFitnessSetup.Op`. A failure says
         * *which step* and *why*, never *what was being written*.
         */
        fun failureDetail(failed: LifeFitnessSetupSequence.Outcome.Failed): String =
            "Life Fitness setup failed at ${failed.label}: ${failed.reason.name.lowercase()}"

        /**
         * Why a fingerprint declined, for a diagnostic.
         *
         * Kept distinct per reason rather than collapsed to "not supported": a split-parent
         * machine and a property-incompatible one need different investigation, and the
         * difference is invisible once it reaches a log as one string.
         */
        fun declineDetail(reason: LifeFitnessFingerprint.Decline): String = when (reason) {
            LifeFitnessFingerprint.Decline.NOT_LIFE_FITNESS ->
                "no Life Fitness setup characteristics present"
            LifeFitnessFingerprint.Decline.INCOMPLETE_PAIR ->
                "a Life Fitness setup pair is incomplete"
            LifeFitnessFingerprint.Decline.SPLIT_PARENT ->
                "Life Fitness setup characteristics span more than one service"
            LifeFitnessFingerprint.Decline.PROPERTY_INCOMPATIBLE ->
                "a Life Fitness setup characteristic is not writable"
        }
    }
}
