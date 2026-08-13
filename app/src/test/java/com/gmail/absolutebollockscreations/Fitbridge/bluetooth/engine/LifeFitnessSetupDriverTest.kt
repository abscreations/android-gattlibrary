package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import com.gmail.absolutebollockscreations.Fitbridge.LifeFitnessProfile
import com.gmail.absolutebollockscreations.Fitbridge.LifeFitnessProfileField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Phase 6 work unit 6.5b part 2 — the transcript running against a link.
 *
 * The write is injected, so the loop that would otherwise need a machine is exercised here: a
 * queue refusing an operation, a machine rejecting one, a settle bound elapsing, and the
 * difference D61 draws between a control step and a profile step under each.
 */
class LifeFitnessSetupDriverTest {

    private val profile = LifeFitnessProfile("Given", "Family", "someone@example.test")

    /** Records what was issued and answers with a scripted accept/refuse. */
    private class Recorder(private val accept: (LifeFitnessSetup.Op) -> Boolean = { true }) :
        LifeFitnessSetupDriver.Issue {
        val issued = mutableListOf<String>()
        override fun issue(op: LifeFitnessSetup.Op): Boolean {
            issued += op.label
            return accept(op)
        }
    }

    private fun ch(uuid: UUID, writable: Boolean = true, notify: Boolean = false, cccd: Boolean = false) =
        LifeFitnessFingerprint.CharacteristicInventory(uuid, writable, notify, cccd)

    private fun inventory() = listOf(
        LifeFitnessFingerprint.ServiceInventory(
            BluetoothIds.UUID_LF_CONTROL_SERVICE,
            listOf(
                ch(BluetoothIds.UUID_LF_CONTROL_1),
                ch(BluetoothIds.UUID_LF_CONTROL_2),
                ch(BluetoothIds.UUID_LF_STATUS, writable = false, notify = true, cccd = true),
            ),
        ),
        LifeFitnessFingerprint.ServiceInventory(
            BluetoothIds.UUID_LF_USER_SERVICE,
            listOf(
                ch(BluetoothIds.UUID_LF_VENDOR_TOKEN_1),
                ch(BluetoothIds.UUID_LF_VENDOR_TOKEN_2),
            ),
        ),
    )

    private fun ftmsOnly() = listOf(
        LifeFitnessFingerprint.ServiceInventory(
            BluetoothIds.UUID_FTMS_SERVICE,
            listOf(ch(BluetoothIds.UUID_TREADMILL_DATA, writable = false, notify = true, cccd = true)),
        ),
    )

    private fun driverStarted(
        recorder: Recorder = Recorder(),
        p: LifeFitnessProfile = profile,
    ): Pair<LifeFitnessSetupDriver, LifeFitnessSetupDriver.Progress> {
        val driver = LifeFitnessSetupDriver()
        return driver to driver.start(inventory(), p, ageYears = 41, issue = recorder)
    }

    // ── Classification ───────────────────────────────────────────────────────────

    @Test
    fun `a non-Life-Fitness machine issues nothing`() {
        val recorder = Recorder()
        val driver = LifeFitnessSetupDriver()

        val progress = driver.start(ftmsOnly(), LifeFitnessProfile.EMPTY, 41, recorder)

        assertTrue(progress is LifeFitnessSetupDriver.Progress.NotApplicable)
        assertTrue("no write may be attempted", recorder.issued.isEmpty())
        assertNull(driver.inFlight)
    }

    @Test
    fun `an incomplete profile issues nothing and names the missing fields`() {
        val recorder = Recorder()
        val driver = LifeFitnessSetupDriver()

        val progress = driver.start(inventory(), LifeFitnessProfile("Given", "", ""), 41, recorder)

        assertEquals(
            LifeFitnessSetupDriver.Progress.NeedsProfile(
                listOf(LifeFitnessProfileField.LAST_NAME, LifeFitnessProfileField.EMAIL),
            ),
            progress,
        )
        assertTrue(recorder.issued.isEmpty())
    }

    @Test
    fun `a profile completed later starts a fresh transcript rather than resuming`() {
        // NeedsProfile must not mark the run started: nothing was written, so the next attempt
        // is a first attempt. Resuming would replay a partial handshake.
        val driver = LifeFitnessSetupDriver()
        driver.start(inventory(), LifeFitnessProfile.EMPTY, 41, Recorder())

        val recorder = Recorder()
        val progress = driver.start(inventory(), profile, 41, recorder)

        assertTrue(progress is LifeFitnessSetupDriver.Progress.Running)
        assertEquals(listOf("status subscribe"), recorder.issued)
    }

    // ── The happy path ───────────────────────────────────────────────────────────

    @Test
    fun `a clean run issues every operation in order and succeeds`() {
        val recorder = Recorder()
        val (driver, first) = driverStarted(recorder)

        assertTrue(first is LifeFitnessSetupDriver.Progress.Running)
        var progress: LifeFitnessSetupDriver.Progress = first
        while (progress is LifeFitnessSetupDriver.Progress.Running) {
            progress = driver.onCompleted(success = true, issue = recorder)
        }

        assertEquals(LifeFitnessSetupDriver.Progress.Succeeded, progress)
        assertEquals(
            listOf(
                "status subscribe", "control 1 open",
                "first name", "last name", "email", "age", "gender", "language",
                "vendor token 1", "vendor token 2", "weight", "height",
                "control 2 magic", "control 1 close",
            ),
            recorder.issued,
        )
        assertNull("nothing is in flight once the transcript completes", driver.inFlight)
    }

    @Test
    fun `only one operation is in flight at a time`() {
        val recorder = Recorder()
        val (driver, _) = driverStarted(recorder)

        assertEquals("status subscribe", driver.inFlight?.label)
        driver.onCompleted(success = true, issue = recorder)
        assertEquals("control 1 open", driver.inFlight?.label)
        assertEquals(2, recorder.issued.size)
    }

    // ── D61 under each failure mode ──────────────────────────────────────────────

    @Test
    fun `a rejected profile write is retried, and the same step is reissued`() {
        val recorder = Recorder()
        val (driver, _) = driverStarted(recorder)
        driver.onCompleted(true, recorder) // status
        driver.onCompleted(true, recorder) // control 1 open

        val progress = driver.onCompleted(success = false, issue = recorder)

        assertTrue(progress is LifeFitnessSetupDriver.Progress.Running)
        progress as LifeFitnessSetupDriver.Progress.Running
        assertEquals("first name", progress.op.label)
        assertEquals(2, progress.attempt)
        assertEquals(
            listOf("status subscribe", "control 1 open", "first name", "first name"),
            recorder.issued,
        )
    }

    @Test
    fun `a rejected control write ends the run immediately`() {
        val recorder = Recorder()
        val (driver, _) = driverStarted(recorder)
        driver.onCompleted(true, recorder) // status

        val progress = driver.onCompleted(success = false, issue = recorder)

        assertTrue(progress is LifeFitnessSetupDriver.Progress.Failed)
        assertTrue((progress as LifeFitnessSetupDriver.Progress.Failed).detail.contains("control 1 open"))
        assertEquals(
            "the control write must not be reissued",
            listOf("status subscribe", "control 1 open"),
            recorder.issued,
        )
        assertNull(driver.inFlight)
    }

    @Test
    fun `a control write that times out also ends the run`() {
        val recorder = Recorder()
        val (driver, _) = driverStarted(recorder)
        driver.onCompleted(true, recorder)

        val progress = driver.onTimeout(recorder)

        assertTrue(progress is LifeFitnessSetupDriver.Progress.Failed)
        assertTrue((progress as LifeFitnessSetupDriver.Progress.Failed).detail.contains("timed_out"))
    }

    // ── A write that never left the queue ────────────────────────────────────────

    @Test
    fun `an operation the queue refuses is treated as a failed attempt`() {
        // A refused issue is a different fact from a rejection — it never reached the machine —
        // but the consequence is identical: the step did not happen. Feeding it back into the
        // sequence keeps D61 in one place instead of two.
        val refuseFirstName = Recorder { it.label != "first name" }
        val driver = LifeFitnessSetupDriver()
        driver.start(inventory(), profile, 41, refuseFirstName)
        driver.onCompleted(true, refuseFirstName)
        val progress = driver.onCompleted(true, refuseFirstName)

        // Three attempts at the refused step, then the run fails rather than skipping it.
        assertTrue(progress is LifeFitnessSetupDriver.Progress.Failed)
        assertEquals(3, refuseFirstName.issued.count { it == "first name" })
        assertTrue(
            (progress as LifeFitnessSetupDriver.Progress.Failed).detail.contains("retries_exhausted"),
        )
    }

    @Test
    fun `a refused control write ends the run without reissuing it`() {
        val refuseOpen = Recorder { it.label != "control 1 open" }
        val driver = LifeFitnessSetupDriver()
        driver.start(inventory(), profile, 41, refuseOpen)

        val progress = driver.onCompleted(true, refuseOpen)

        assertTrue(progress is LifeFitnessSetupDriver.Progress.Failed)
        assertEquals(1, refuseOpen.issued.count { it == "control 1 open" })
    }

    // ── Link lifetime ────────────────────────────────────────────────────────────

    @Test
    fun `a repeated start does not restart a running transcript`() {
        // Service discovery fires again on some stacks. Restarting a half-completed handshake
        // would replay control writes D61 refuses to repeat.
        val recorder = Recorder()
        val (driver, _) = driverStarted(recorder)
        driver.onCompleted(true, recorder)

        val again = driver.start(inventory(), profile, 41, recorder)

        assertTrue(again is LifeFitnessSetupDriver.Progress.Running)
        assertEquals("no operation is reissued", 2, recorder.issued.size)
    }

    @Test
    fun `reset clears the run so a new link starts clean`() {
        val recorder = Recorder()
        val (driver, _) = driverStarted(recorder)
        driver.onCompleted(true, recorder)
        driver.reset()

        assertNull(driver.inFlight)

        val fresh = Recorder()
        val progress = driver.start(inventory(), profile, 41, fresh)

        assertTrue(progress is LifeFitnessSetupDriver.Progress.Running)
        assertEquals(listOf("status subscribe"), fresh.issued)
    }
}
