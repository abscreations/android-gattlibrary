package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import com.gmail.absolutebollockscreations.Fitbridge.LifeFitnessProfile
import java.util.UUID

/**
 * The Life Fitness setup transcript: an ordered list of GATT operations and the rules for
 * advancing through them (plan §6, work unit 6.4).
 *
 * ## Pure by construction
 *
 * Nothing here touches Android. The transcript is data, the advance rules are a small state
 * machine over that data, and the handler that eventually runs it owns queue targeting and
 * callbacks. That split is the §11.7 lesson: a sequence sealed inside a GATT handler needs a
 * live connection to reach, so it goes untested.
 *
 * ## No fixed sleeps
 *
 * TMIW waits 2000 ms, then 2000 ms, then 500 ms between each write. The official Life Fitness
 * application in issue #462 advances through the same writes in roughly **0.2–0.4 seconds**,
 * which is direct evidence that those delays are an interoperability workaround rather than a
 * demonstrated protocol requirement. Each operation therefore completes on its GATT callback,
 * and [SETTLE_TIMEOUT_MS] is an upper bound on waiting for that callback rather than a delay to
 * observe.
 *
 * ## Retries: profile yes, control no (D61)
 *
 * A profile write is a plain value write. Repeating one that failed sets the same field to the
 * same value, so it retries within its bound.
 *
 * A control write is a command at a known point in a state machine nobody can test. Nothing
 * establishes that a Life Fitness console tolerates `[0x01]` twice, or the seven-octet magic
 * twice, and the §6c instinct applies even though this family exposes no movement control:
 * **never blindly repeat a command whose acknowledgement you do not understand.** A failed
 * control write fails the whole sequence and surfaces the explicit setup-failure state, which is
 * a visible dead session rather than a machine in a state we cannot name.
 */
internal object LifeFitnessSetup {

    /**
     * The vendor application token TMIW and QZ both write to `5da54cf2-…`.
     *
     * **This is Life Fitness's own application identifier, and FitBridge presenting it is
     * impersonation of a sort.** The value is the 32-character ASCII hex string
     * `f77ab82b7a2440078f33c5aa6ee7a31b` — the byte encoding of a UUID that almost certainly
     * identifies the official app to the console. TMIW hardcodes it (`life_fitness.dart`) and
     * QZ writes the same bytes; both are public and permissively licensed, so copying the
     * constant is a licensing non-issue. Whether FitBridge *should* is a different question,
     * recorded as Q33 rather than settled here.
     *
     * The practical position: no evidence exists that any other value unlocks the stream, and
     * the field is not optional in either reference implementation. Shipping without it means
     * shipping a handshake that is known not to complete.
     */
    val VENDOR_TOKEN: ByteArray = "f77ab82b7a2440078f33c5aa6ee7a31b".toByteArray(Charsets.US_ASCII)

    /** Written to `5da569e2-…` immediately before the closing control write. */
    val CONTROL_2_MAGIC = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0xB8.toByte(), 0x5B, 0x5D)

    const val CONTROL_1_OPEN: Byte = 0x01
    const val CONTROL_1_CLOSE: Byte = 0x02

    /** `[0x00]`, per both reference implementations. Purpose unknown. */
    val VENDOR_TOKEN_2 = byteArrayOf(0x00)

    /** ISO 639-1, matching TMIW. Not localised: the machine's use of it is unknown. */
    val LANGUAGE = "en".toByteArray(Charsets.US_ASCII)

    /**
     * Upper bound on one operation's completion callback.
     *
     * Derived from TMIW's longest delay rather than its shortest, because it bounds a failure
     * rather than pacing a success: the official app completes each step in a fraction of this,
     * so any wait approaching it already means something is wrong.
     */
    const val SETTLE_TIMEOUT_MS = 2_000L

    /** Attempts allowed for a profile write, including the first. */
    const val PROFILE_MAX_ATTEMPTS = 3

    enum class OpKind {
        /** Subscribe to the optional status characteristic. Never gates completion. */
        ENABLE_STATUS,

        /** A command to the machine. Never retried — see D61. */
        CONTROL_WRITE,

        /** A user-data value write. Retried within [PROFILE_MAX_ATTEMPTS]. */
        PROFILE_WRITE,
    }

    /**
     * One step of the transcript.
     *
     * @param label a payload-free description for logs and failure states. **The payload itself
     * must never reach a log**: these carry the user's name and email, and while
     * `SensitiveCharacteristics` makes a capture structurally incapable of holding them, an
     * ordinary `Timber` line has no such protection. The label exists so a diagnostic can say
     * which step failed without saying what it contained.
     */
    data class Op(
        val kind: OpKind,
        val serviceUuid: UUID,
        val characteristicUuid: UUID,
        val payload: ByteArray?,
        val label: String,
    ) {
        val maxAttempts: Int
            get() = if (kind == OpKind.PROFILE_WRITE) PROFILE_MAX_ATTEMPTS else 1

        /** Deliberately excludes [payload]: a data class `toString` would print the user. */
        override fun toString(): String = "Op($label, $kind, attempts=$maxAttempts)"
    }

    /**
     * Build the transcript for a fingerprinted machine.
     *
     * Order is the corroborated one: status subscription, open, profile block, magic, close.
     * Both reference implementations and both capture sets agree on it, which is worth more than
     * either agreeing with itself.
     *
     * @param status the resolved status characteristic, or null when the machine exposes none.
     * Its absence removes one operation and changes nothing else — §6 is explicit that status is
     * observable but never a gate.
     */
    fun transcript(
        control: UUID,
        user: UUID,
        status: UUID?,
        profile: LifeFitnessProfile,
        ageYears: Int,
    ): List<Op> = buildList {
        if (status != null) {
            add(Op(OpKind.ENABLE_STATUS, control, status, null, "status subscribe"))
        }
        add(
            Op(
                OpKind.CONTROL_WRITE,
                control,
                BluetoothIds.UUID_LF_CONTROL_1,
                byteArrayOf(CONTROL_1_OPEN),
                "control 1 open",
            ),
        )

        // The profile block, in the order both references write it. Names and email are
        // NUL-terminated UTF-8; the rest are fixed-width SIG encodings.
        add(profileOp(user, BluetoothIds.UUID_USER_FIRST_NAME, nulTerminated(profile.firstName), "first name"))
        add(profileOp(user, BluetoothIds.UUID_USER_LAST_NAME, nulTerminated(profile.lastName), "last name"))
        add(profileOp(user, BluetoothIds.UUID_USER_EMAIL, nulTerminated(profile.email), "email"))
        add(profileOp(user, BluetoothIds.UUID_USER_AGE, byteArrayOf(ageYears.coerceIn(0, 255).toByte()), "age"))
        add(profileOp(user, BluetoothIds.UUID_USER_GENDER, byteArrayOf(profile.gender.toByte()), "gender"))
        add(profileOp(user, BluetoothIds.UUID_USER_LANGUAGE, LANGUAGE, "language"))
        add(profileOp(user, BluetoothIds.UUID_LF_VENDOR_TOKEN_1, VENDOR_TOKEN, "vendor token 1"))
        add(profileOp(user, BluetoothIds.UUID_LF_VENDOR_TOKEN_2, VENDOR_TOKEN_2, "vendor token 2"))
        add(profileOp(user, BluetoothIds.UUID_USER_WEIGHT, uint16Le(profile.weightWireUnits), "weight"))
        add(profileOp(user, BluetoothIds.UUID_USER_HEIGHT, uint16Le(profile.heightWireUnits), "height"))

        add(
            Op(
                OpKind.CONTROL_WRITE,
                control,
                BluetoothIds.UUID_LF_CONTROL_2,
                CONTROL_2_MAGIC,
                "control 2 magic",
            ),
        )
        add(
            Op(
                OpKind.CONTROL_WRITE,
                control,
                BluetoothIds.UUID_LF_CONTROL_1,
                byteArrayOf(CONTROL_1_CLOSE),
                "control 1 close",
            ),
        )
    }

    private fun profileOp(service: UUID, characteristic: UUID, payload: ByteArray, label: String) =
        Op(OpKind.PROFILE_WRITE, service, characteristic, payload, label)

    private fun nulTerminated(value: String): ByteArray =
        value.toByteArray(Charsets.UTF_8) + 0x00

    private fun uint16Le(value: Int): ByteArray =
        byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())
}

/**
 * Advances a transcript, one completion at a time.
 *
 * Separate from the transcript because they answer different questions: what to send, and what
 * to do when a send finishes or does not. Keeping the second in a class with no Android types
 * means every failure branch — a refused write, a timeout, a retry exhausting — is reachable
 * from a unit test rather than only from a machine nobody has.
 */
internal class LifeFitnessSetupSequence(private val ops: List<LifeFitnessSetup.Op>) {

    sealed interface Outcome {
        /** Issue [op]; this is attempt [attempt] of [LifeFitnessSetup.Op.maxAttempts]. */
        data class Issue(val op: LifeFitnessSetup.Op, val attempt: Int) : Outcome

        data object Complete : Outcome

        /**
         * The sequence stopped and setup did not happen.
         *
         * @param label the failing step, payload-free.
         */
        data class Failed(val label: String, val reason: Reason) : Outcome
    }

    enum class Reason {
        /** The machine refused the write, or the stack reported a non-success status. */
        WRITE_REJECTED,

        /** No completion callback inside the settle bound. */
        TIMED_OUT,

        /** A retryable step exhausted its attempts. */
        RETRIES_EXHAUSTED,
    }

    private var index = 0
    private var attempt = 0

    val isComplete: Boolean get() = index >= ops.size

    /** The next operation to issue, or [Outcome.Complete] when the transcript is finished. */
    fun start(): Outcome = current()

    /**
     * Record that the in-flight operation finished.
     *
     * @param success whether the stack reported success. A control write that fails ends the
     * sequence immediately (D61); a profile write retries until its attempts are spent.
     */
    fun onCompleted(success: Boolean): Outcome {
        val op = ops.getOrNull(index) ?: return Outcome.Complete
        if (success) {
            index++
            attempt = 0
            return current()
        }
        return failure(op, Reason.WRITE_REJECTED)
    }

    /** Record that the settle bound elapsed with no completion. Same policy as a rejection. */
    fun onTimeout(): Outcome {
        val op = ops.getOrNull(index) ?: return Outcome.Complete
        return failure(op, Reason.TIMED_OUT)
    }

    private fun failure(op: LifeFitnessSetup.Op, reason: Reason): Outcome {
        // A control write is never repeated, whatever went wrong. Retrying a command whose
        // acknowledgement is not understood is the failure mode D61 refuses.
        if (op.kind == LifeFitnessSetup.OpKind.CONTROL_WRITE) {
            return Outcome.Failed(op.label, reason)
        }
        attempt++
        if (attempt >= op.maxAttempts) {
            return Outcome.Failed(op.label, Reason.RETRIES_EXHAUSTED)
        }
        return Outcome.Issue(op, attempt + 1)
    }

    private fun current(): Outcome {
        val op = ops.getOrNull(index) ?: return Outcome.Complete
        return Outcome.Issue(op, attempt + 1)
    }
}
