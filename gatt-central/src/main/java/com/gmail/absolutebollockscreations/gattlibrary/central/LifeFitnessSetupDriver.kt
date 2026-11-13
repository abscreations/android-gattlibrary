package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi


/**
 * Runs a Life Fitness setup transcript against a live link (plan §6, unit 6.5b part 2).
 *
 * ## Why the write is injected
 *
 * The driver never touches a `BluetoothGatt`. It asks its caller to issue an operation and is
 * told whether that succeeded, which makes every branch — a queue refusing a write, a machine
 * rejecting one, a settle bound elapsing, a control failure ending the run — reachable from a
 * unit test. The handler that supplies [Issue] does nothing but call `pushWrite` and
 * `pushDescriptorWrite`, and holds no policy.
 *
 * That split is what stops this being the fourth component in Phase 6 that compiles and cannot
 * be exercised.
 *
 * ## What it is not responsible for
 *
 * It does not own the settle timer, because a timer must be bound to the link generation that
 * started it and only the engine knows about generations. It exposes [inFlight] so the caller
 * can arm one, and [onTimeout] so the caller can report that it fired.
 */
@GattLibraryInternalApi
internal class LifeFitnessSetupDriver(
    private val coordinator: LifeFitnessSetupCoordinator = LifeFitnessSetupCoordinator(),
) {

    /**
     * Issue one operation. Returns whether it was accepted for transmission.
     *
     * **False means the write never reached the machine** — a missing characteristic, a queue
     * that refused it — which is a different fact from the machine rejecting it, and is treated
     * the same way only because the consequence is identical: this step did not happen.
     */
    fun interface Issue {
        fun issue(op: LifeFitnessSetup.Op): Boolean
    }

    sealed interface Progress {
        /** Not a Life Fitness setup surface. The caller must not change adapter state. */
        data class NotApplicable(val reason: LifeFitnessFingerprint.Decline) : Progress

        /** Fingerprinted, but the user has not supplied what the handshake writes. */
        data class NeedsProfile(val missing: List<LifeFitnessProfileField>) : Progress

        /** An operation is in flight; arm a settle bound and wait for its completion. */
        data class Running(val op: LifeFitnessSetup.Op, val attempt: Int) : Progress

        /** The transcript finished. The caller reports `setupSucceeded`. */
        data object Succeeded : Progress

        /** The transcript stopped. [detail] is payload-free and suits `setupFailed(reason)`. */
        data class Failed(val detail: String) : Progress
    }

    /** The operation awaiting completion, or null when nothing is in flight. */
    var inFlight: LifeFitnessSetup.Op? = null
        private set

    private var started = false

    /**
     * Classify the link and, when it is ready, issue the first operation.
     *
     * Safe to call more than once for the same link: a second call while a transcript is running
     * is ignored rather than restarting it. Service discovery can fire again on some stacks, and
     * restarting a half-completed handshake would replay control writes D61 refuses to repeat.
     */
    fun start(
        services: List<LifeFitnessFingerprint.ServiceInventory>,
        profile: LifeFitnessProfile,
        ageYears: Int,
        issue: Issue,
    ): Progress {
        if (started) {
            return inFlight?.let { Progress.Running(it, attempt = 1) } ?: Progress.Succeeded
        }

        return when (val decision = coordinator.decide(services, profile, ageYears)) {
            is LifeFitnessSetupCoordinator.Decision.NotApplicable ->
                Progress.NotApplicable(decision.reason)

            is LifeFitnessSetupCoordinator.Decision.NeedsProfile ->
                // Deliberately does not mark the run started. The user can fill the fields and
                // reconnect, and the next attempt should be a fresh transcript rather than a
                // resumption of one that never issued a write.
                Progress.NeedsProfile(decision.missing)

            is LifeFitnessSetupCoordinator.Decision.Ready -> {
                started = true
                advance(coordinator.begin(decision), issue)
            }
        }
    }

    /** Report the in-flight operation's completion. */
    fun onCompleted(success: Boolean, issue: Issue): Progress =
        advance(coordinator.onCompleted(success), issue)

    /** Report that the settle bound elapsed with no completion. */
    fun onTimeout(issue: Issue): Progress = advance(coordinator.onTimeout(), issue)

    /** Drop per-link state. A transcript must never outlive the connection that began it. */
    fun reset() {
        coordinator.reset()
        inFlight = null
        started = false
    }

    /**
     * Turn a sequence outcome into progress, issuing the next operation when there is one.
     *
     * The loop is here rather than in the caller because an issue failure has to be fed straight
     * back into the sequence: a write the queue refused is a failed attempt, and for a profile
     * step that means the retry policy applies exactly as it would to a rejection. Leaving that
     * to the handler would put D61 in two places.
     */
    private fun advance(
        outcome: LifeFitnessSetupSequence.Outcome,
        issue: Issue,
    ): Progress {
        var current = outcome
        while (true) {
            when (current) {
                is LifeFitnessSetupSequence.Outcome.Complete -> {
                    inFlight = null
                    return Progress.Succeeded
                }

                is LifeFitnessSetupSequence.Outcome.Failed -> {
                    inFlight = null
                    return Progress.Failed(LifeFitnessSetupCoordinator.failureDetail(current))
                }

                is LifeFitnessSetupSequence.Outcome.Issue -> {
                    val op = current.op
                    if (issue.issue(op)) {
                        inFlight = op
                        return Progress.Running(op, current.attempt)
                    }
                    // Never transmitted. Same consequence as a rejection, so the same policy:
                    // a profile step retries within its bound, a control step ends the run.
                    current = coordinator.onCompleted(success = false)
                }
            }
        }
    }
}
