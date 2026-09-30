package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.LifeFitnessUuids

/**
 * Phase 6 work unit 6.5a — the composition of fingerprint, gate and transcript.
 *
 * Each of the three was already tested in isolation. What was untested until now is the thing
 * that decides between them, which is where the damaging mistakes live: prompting a machine that
 * is not Life Fitness, writing before the profile is complete, or targeting the canonical parents
 * instead of the discovered ones.
 */
class LifeFitnessSetupCoordinatorTest {

    private val profile = LifeFitnessProfile("Given", "Family", "someone@example.test")

    private fun ch(
        uuid: UUID,
        writable: Boolean = true,
        notify: Boolean = false,
        cccd: Boolean = false,
    ) = LifeFitnessFingerprint.CharacteristicInventory(uuid, writable, notify, cccd)

    private fun lifeFitnessInventory(
        control: UUID = LifeFitnessUuids.UUID_LF_CONTROL_SERVICE,
        user: UUID = LifeFitnessUuids.UUID_LF_USER_SERVICE,
        withStatus: Boolean = true,
    ) = listOf(
        LifeFitnessFingerprint.ServiceInventory(
            control,
            buildList {
                add(ch(LifeFitnessUuids.UUID_LF_CONTROL_1))
                add(ch(LifeFitnessUuids.UUID_LF_CONTROL_2))
                if (withStatus) {
                    add(ch(LifeFitnessUuids.UUID_LF_STATUS, writable = false, notify = true, cccd = true))
                }
            },
        ),
        LifeFitnessFingerprint.ServiceInventory(
            user,
            listOf(
                ch(LifeFitnessUuids.UUID_LF_VENDOR_TOKEN_1),
                ch(LifeFitnessUuids.UUID_LF_VENDOR_TOKEN_2),
            ),
        ),
    )

    private fun decide(
        services: List<LifeFitnessFingerprint.ServiceInventory>,
        p: LifeFitnessProfile = profile,
    ) = LifeFitnessSetupCoordinator().decide(services, p, ageYears = 41)

    // ── The three decisions ──────────────────────────────────────────────────────

    @Test
    fun `an ordinary FTMS machine is not applicable and produces no prompt`() {
        // The most damaging failure this ordering prevents: a dialog demanding an email address
        // because an unrelated treadmill connected. The fingerprint runs before the profile is
        // consulted, so a non-Life-Fitness machine cannot reach the gate at all.
        val decision = decide(
            listOf(
                LifeFitnessFingerprint.ServiceInventory(
                    FtmsUuids.UUID_FTMS_SERVICE,
                    listOf(ch(FtmsUuids.UUID_TREADMILL_DATA, writable = false, notify = true)),
                ),
            ),
            p = LifeFitnessProfile.EMPTY,
        )

        assertEquals(
            LifeFitnessSetupCoordinator.Decision.NotApplicable(
                LifeFitnessFingerprint.Decline.NOT_LIFE_FITNESS,
            ),
            decision,
        )
    }

    @Test
    fun `a fingerprinted machine with an incomplete profile asks and writes nothing`() {
        val decision = decide(
            lifeFitnessInventory(),
            p = LifeFitnessProfile("Given", "", ""),
        )

        assertEquals(
            LifeFitnessSetupCoordinator.Decision.NeedsProfile(
                listOf(LifeFitnessProfileField.LAST_NAME, LifeFitnessProfileField.EMAIL),
            ),
            decision,
        )
        assertFalse(
            "a NeedsProfile decision must carry no transcript to run",
            decision is LifeFitnessSetupCoordinator.Decision.Ready,
        )
    }

    @Test
    fun `a fingerprinted machine with a complete profile yields a runnable transcript`() {
        val decision = decide(lifeFitnessInventory())

        assertTrue(decision is LifeFitnessSetupCoordinator.Decision.Ready)
        decision as LifeFitnessSetupCoordinator.Decision.Ready
        assertEquals(14, decision.transcript.size)
        assertEquals("control 1 close", decision.transcript.last().label)
    }

    // ── Parents come from the device ─────────────────────────────────────────────

    @Test
    fun `the transcript targets the discovered parents, not the canonical constants`() {
        // D52's whole purpose. Building against LifeFitnessUuids.UUID_LF_CONTROL_SERVICE would look
        // correct on every machine that matches the capture and silently fail on the variant the
        // runtime rule exists to support.
        val variantControl = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val variantUser = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa")

        val decision = decide(
            lifeFitnessInventory(control = variantControl, user = variantUser),
        ) as LifeFitnessSetupCoordinator.Decision.Ready

        assertFalse("the variant must not be reported as canonical", decision.resolved.canonicalParents)
        for (op in decision.transcript) {
            val expected = when (op.kind) {
                LifeFitnessSetup.OpKind.PROFILE_WRITE -> variantUser
                else -> variantControl
            }
            assertEquals("${op.label} parent", expected, op.serviceUuid)
        }
    }

    @Test
    fun `a machine with no status characteristic still runs setup`() {
        val decision = decide(lifeFitnessInventory(withStatus = false))
            as LifeFitnessSetupCoordinator.Decision.Ready

        assertEquals(13, decision.transcript.size)
        assertFalse(decision.transcript.any { it.kind == LifeFitnessSetup.OpKind.ENABLE_STATUS })
    }

    // ── Driving the sequence ─────────────────────────────────────────────────────

    @Test
    fun `a clean run reaches Complete through the coordinator`() {
        val coordinator = LifeFitnessSetupCoordinator()
        val ready = coordinator.decide(lifeFitnessInventory(), profile, 41)
            as LifeFitnessSetupCoordinator.Decision.Ready

        var outcome = coordinator.begin(ready)
        var issued = 0
        while (outcome is LifeFitnessSetupSequence.Outcome.Issue) {
            issued++
            outcome = coordinator.onCompleted(success = true)
        }

        assertEquals(LifeFitnessSetupSequence.Outcome.Complete, outcome)
        assertEquals(ready.transcript.size, issued)
    }

    @Test
    fun `completion after reset is inert rather than resuming a dead link`() {
        // A sequence must never survive the connection that started it. Reporting Complete for a
        // link with no sequence is the safe answer: it advances nothing and writes nothing.
        val coordinator = LifeFitnessSetupCoordinator()
        val ready = coordinator.decide(lifeFitnessInventory(), profile, 41)
            as LifeFitnessSetupCoordinator.Decision.Ready
        coordinator.begin(ready)
        coordinator.reset()

        assertEquals(LifeFitnessSetupSequence.Outcome.Complete, coordinator.onCompleted(true))
        assertEquals(LifeFitnessSetupSequence.Outcome.Complete, coordinator.onTimeout())
    }

    // ── Failure details ──────────────────────────────────────────────────────────

    @Test
    fun `a failure detail names the step and never the payload`() {
        val detail = LifeFitnessSetupCoordinator.failureDetail(
            LifeFitnessSetupSequence.Outcome.Failed(
                "email",
                LifeFitnessSetupSequence.Reason.RETRIES_EXHAUSTED,
            ),
        )

        assertTrue(detail.contains("email"))
        assertTrue(detail.contains("retries_exhausted"))
        assertFalse("the value must never reach a policy detail", detail.contains("example.test"))
    }

    @Test
    fun `each decline reason produces a distinguishable diagnostic`() {
        // Collapsing these to "not supported" would make a split-parent machine and a
        // property-incompatible one identical in a log, and they need different investigation.
        val details = LifeFitnessFingerprint.Decline.values()
            .map(LifeFitnessSetupCoordinator::declineDetail)

        assertEquals(
            "every decline reason needs its own wording",
            LifeFitnessFingerprint.Decline.values().size,
            details.distinct().size,
        )
        assertTrue(details.none { it.isBlank() })
    }
}
