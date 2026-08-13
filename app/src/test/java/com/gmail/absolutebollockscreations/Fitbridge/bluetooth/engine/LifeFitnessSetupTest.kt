package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import com.gmail.absolutebollockscreations.Fitbridge.LifeFitnessProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Phase 6 work unit 6.4 — the setup transcript and its advance rules (plan §6, D61).
 *
 * The transcript is the part FitBridge can never test against hardware, so the tests here are
 * about the two things that can be established without a machine: that the operations match the
 * corroborated order and encodings, and that every failure branch does what D61 says.
 */
class LifeFitnessSetupTest {

    private val control: UUID = BluetoothIds.UUID_LF_CONTROL_SERVICE
    private val user: UUID = BluetoothIds.UUID_LF_USER_SERVICE
    private val status: UUID = BluetoothIds.UUID_LF_STATUS

    private val profile = LifeFitnessProfile(
        firstName = "Given",
        lastName = "Family",
        email = "someone@example.test",
        gender = 1,
        weightKg = 75,
        heightCm = 170,
    )

    private fun transcript(withStatus: Boolean = true) = LifeFitnessSetup.transcript(
        control = control,
        user = user,
        status = if (withStatus) status else null,
        profile = profile,
        ageYears = 41,
    )

    private fun op(label: String) = transcript().single { it.label == label }

    // ── Order ────────────────────────────────────────────────────────────────────

    @Test
    fun `the transcript follows the corroborated order`() {
        // TMIW, QZ, issue #1018's physical trace and issue #462's four official-app sessions all
        // agree on this order. That two implementations and two capture sets agree is worth more
        // than any one of them agreeing with itself.
        assertEquals(
            listOf(
                "status subscribe",
                "control 1 open",
                "first name", "last name", "email", "age", "gender", "language",
                "vendor token 1", "vendor token 2", "weight", "height",
                "control 2 magic",
                "control 1 close",
            ),
            transcript().map { it.label },
        )
    }

    @Test
    fun `a machine with no status characteristic loses one step and nothing else`() {
        // §6: status is observable but never a gate. Its absence must not change the sequence.
        val withoutStatus = transcript(withStatus = false)

        assertEquals(transcript().size - 1, withoutStatus.size)
        assertEquals(transcript().drop(1).map { it.label }, withoutStatus.map { it.label })
        assertFalse(withoutStatus.any { it.kind == LifeFitnessSetup.OpKind.ENABLE_STATUS })
    }

    @Test
    fun `control writes target the control parent and profile writes the user parent`() {
        // The §6 targeting rule: a profile write that cannot name its service does not happen.
        for (op in transcript()) {
            val expected = when (op.kind) {
                LifeFitnessSetup.OpKind.PROFILE_WRITE -> user
                else -> control
            }
            assertEquals("${op.label} parent", expected, op.serviceUuid)
        }
    }

    // ── Encodings ────────────────────────────────────────────────────────────────

    @Test
    fun `names and email are NUL-terminated UTF-8`() {
        val firstName = op("first name").payload!!

        assertEquals("Given", String(firstName.dropLast(1).toByteArray(), Charsets.UTF_8))
        assertEquals(0, firstName.last().toInt())
        assertEquals(0, op("email").payload!!.last().toInt())
    }

    @Test
    fun `weight and height carry their SIG scale, not their display value`() {
        // 0x2A98 is 0.005 kg per unit, so 75 kg is 15000 little-endian, not 75. This is the
        // encoding that cost Phase 4 a treadmill pace field when it was got wrong elsewhere.
        assertEquals(listOf(0x98, 0x3A), op("weight").payload!!.map { it.toInt() and 0xFF })
        assertEquals(15_000, 0x3A98)

        // 0x2A8E is 0.01 m per unit, which makes centimetres an identity: 170 -> 0x00AA.
        assertEquals(listOf(0xAA, 0x00), op("height").payload!!.map { it.toInt() and 0xFF })
    }

    @Test
    fun `age is a single octet and is clamped rather than truncated`() {
        assertEquals(listOf(41), op("age").payload!!.map { it.toInt() and 0xFF })

        val absurd = LifeFitnessSetup.transcript(control, user, status, profile, ageYears = 400)
            .single { it.label == "age" }
        assertEquals(
            "a nonsense age must saturate, not wrap to a plausible one",
            255,
            absurd.payload!!.single().toInt() and 0xFF,
        )
    }

    @Test
    fun `the control payloads are the corroborated constants`() {
        assertEquals(listOf(0x01), op("control 1 open").payload!!.map { it.toInt() and 0xFF })
        assertEquals(listOf(0x02), op("control 1 close").payload!!.map { it.toInt() and 0xFF })
        assertEquals(
            listOf(0x00, 0x00, 0x00, 0x01, 0xB8, 0x5B, 0x5D),
            op("control 2 magic").payload!!.map { it.toInt() and 0xFF },
        )
    }

    @Test
    fun `the vendor token is the 32-character ASCII hex string both references write`() {
        val token = op("vendor token 1").payload!!

        assertEquals(32, token.size)
        assertEquals("f77ab82b7a2440078f33c5aa6ee7a31b", String(token, Charsets.US_ASCII))
        assertEquals(listOf(0x00), op("vendor token 2").payload!!.map { it.toInt() and 0xFF })
    }

    @Test
    fun `an operation never prints its payload`() {
        // These carry a name and an email. `SensitiveCharacteristics` makes a capture
        // structurally incapable of holding them, but an ordinary log line has no such
        // protection, and a data class `toString` would print the user.
        val rendered = op("email").toString()

        assertFalse(rendered.contains("example.test"))
        assertFalse(rendered.contains("someone"))
        assertTrue(rendered.contains("email"))
    }

    // ── D61: retry policy ────────────────────────────────────────────────────────

    @Test
    fun `a clean run issues every operation once and completes`() {
        val ops = transcript()
        val sequence = LifeFitnessSetupSequence(ops)
        val issued = mutableListOf<String>()

        var outcome = sequence.start()
        while (outcome is LifeFitnessSetupSequence.Outcome.Issue) {
            issued += outcome.op.label
            assertEquals("no retries on a clean run", 1, outcome.attempt)
            outcome = sequence.onCompleted(success = true)
        }

        assertEquals(LifeFitnessSetupSequence.Outcome.Complete, outcome)
        assertEquals(ops.map { it.label }, issued)
        assertTrue(sequence.isComplete)
    }

    @Test
    fun `a failed profile write retries within its bound`() {
        val sequence = LifeFitnessSetupSequence(transcript())
        repeat(2) { sequence.onCompleted(success = true) } // status, control 1 open

        val first = sequence.onCompleted(success = false)
        assertTrue(first is LifeFitnessSetupSequence.Outcome.Issue)
        first as LifeFitnessSetupSequence.Outcome.Issue
        assertEquals("first name", first.op.label)
        assertEquals("the same step, second attempt", 2, first.attempt)
    }

    @Test
    fun `a profile write that exhausts its attempts fails the sequence`() {
        val sequence = LifeFitnessSetupSequence(transcript())
        repeat(2) { sequence.onCompleted(success = true) }

        sequence.onCompleted(success = false)
        sequence.onCompleted(success = false)
        val outcome = sequence.onCompleted(success = false)

        assertEquals(
            LifeFitnessSetupSequence.Outcome.Failed(
                "first name",
                LifeFitnessSetupSequence.Reason.RETRIES_EXHAUSTED,
            ),
            outcome,
        )
    }

    @Test
    fun `a failed control write is never retried`() {
        // D61, and the assertion this whole unit turns on. Repeating a command at an unknown
        // point in a state machine nobody can test is the failure mode being refused; a visible
        // dead session is the better outcome.
        val sequence = LifeFitnessSetupSequence(transcript())
        sequence.onCompleted(success = true) // status subscribe

        val outcome = sequence.onCompleted(success = false)

        assertEquals(
            LifeFitnessSetupSequence.Outcome.Failed(
                "control 1 open",
                LifeFitnessSetupSequence.Reason.WRITE_REJECTED,
            ),
            outcome,
        )
    }

    @Test
    fun `a control write that times out is not retried either`() {
        // A timeout is less obviously a refusal than a rejection, which makes it the more
        // tempting one to retry. It is the same command to the same unexamined state machine.
        val sequence = LifeFitnessSetupSequence(transcript())
        sequence.onCompleted(success = true)

        val outcome = sequence.onTimeout()

        assertEquals(
            LifeFitnessSetupSequence.Outcome.Failed(
                "control 1 open",
                LifeFitnessSetupSequence.Reason.TIMED_OUT,
            ),
            outcome,
        )
    }

    @Test
    fun `every control write in the transcript carries a single attempt`() {
        for (op in transcript()) {
            val expected = if (op.kind == LifeFitnessSetup.OpKind.PROFILE_WRITE) {
                LifeFitnessSetup.PROFILE_MAX_ATTEMPTS
            } else {
                1
            }
            assertEquals("${op.label} attempts", expected, op.maxAttempts)
        }
    }

    @Test
    fun `the closing control write is the last thing that happens`() {
        // If anything is ever appended after it, the machine is told setup is finished before it
        // is. Pinned because the transcript is a list and lists get appended to.
        val ops = transcript()

        assertEquals("control 1 close", ops.last().label)
        assertEquals(LifeFitnessSetup.OpKind.CONTROL_WRITE, ops.last().kind)
        assertNull(ops.drop(ops.indexOfFirst { it.label == "control 1 close" } + 1).firstOrNull())
    }
}
