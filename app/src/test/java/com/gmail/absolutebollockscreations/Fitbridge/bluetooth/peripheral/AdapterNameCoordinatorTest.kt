package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the advertised-name race.
 *
 * The bug: Android splices the global adapter name into the advertisement when
 * advertising starts, `setName()` is asynchronous, and the old code advertised
 * immediately after requesting the rename. When the stack lost that race the
 * machine went on the air under the phone's previous name.
 *
 * Every test below is a scenario that used to be able to produce a wrong name.
 */
class AdapterNameCoordinatorTest {

    /** Adapter whose name only changes when the test says so. */
    private class FakeGateway(var name: String? = "Pixel 9") : AdapterNameCoordinator.Gateway {
        var requested: String? = null
        var requests = 0
        var accept = true

        /** Apply the rename the stack was asked for, as the real stack would. */
        fun settle() {
            name = requested
        }

        override val currentName: String? get() = name

        override fun requestName(name: String): Boolean {
            requests++
            requested = name
            return accept
        }
    }

    private class FakeStore(var value: String? = null) : AdapterNameCoordinator.OriginalNameStore {
        var writable = true
        override fun read(): String? = value
        override fun write(name: String): Boolean {
            if (!writable) return false
            value = name
            return true
        }

        override fun clear(): Boolean {
            value = null
            return true
        }
    }

    /** Manual clock: nothing fires until the test advances it. */
    private class FakeScheduler : AdapterNameCoordinator.Scheduler {
        private data class Task(val token: Any, var remaining: Long, val action: () -> Unit)

        private val tasks = mutableListOf<Task>()

        override fun postDelayed(token: Any, delayMs: Long, action: () -> Unit) {
            cancel(token)
            tasks += Task(token, delayMs, action)
        }

        override fun cancel(token: Any) {
            tasks.removeAll { it.token === token }
        }

        fun advance(ms: Long) {
            var remaining = ms
            while (remaining > 0) {
                val due = tasks.filter { it.remaining <= remaining }.minByOrNull { it.remaining }
                    ?: break
                val step = due.remaining
                tasks.forEach { it.remaining -= step }
                remaining -= step
                tasks.remove(due)
                due.action()
            }
            tasks.forEach { it.remaining -= remaining }
        }
    }

    private fun coordinator(
        gateway: FakeGateway,
        store: FakeStore = FakeStore(),
        scheduler: FakeScheduler = FakeScheduler(),
    ) = AdapterNameCoordinator(gateway, store, scheduler, timeoutMs = 3_000, pollIntervalMs = 100)

    // ── the core fix ──────────────────────────────────────────────────────────

    @Test
    fun `apply does not report success until the adapter confirms`() {
        val gateway = FakeGateway("Pixel 9")
        val scheduler = FakeScheduler()
        val subject = coordinator(gateway, scheduler = scheduler)

        var result: Result<Unit>? = null
        subject.apply("FBRG-Treadmill") { result = it }

        // The stack accepted the request but has not applied it. This is exactly
        // the window in which the old code started advertising.
        assertNull("must not complete before the adapter agrees", result)
        assertEquals("FBRG-Treadmill", gateway.requested)
        assertEquals("Pixel 9", gateway.currentName)

        gateway.settle()
        subject.onNameObserved("FBRG-Treadmill")

        assertTrue(result!!.isSuccess)
    }

    @Test
    fun `a broadcast for a different name does not satisfy the wait`() {
        // Another app renaming the adapter mid-flight must not be mistaken for
        // our confirmation.
        val gateway = FakeGateway("Pixel 9")
        val subject = coordinator(gateway)

        var result: Result<Unit>? = null
        subject.apply("FBRG-Bike") { result = it }

        subject.onNameObserved("Someone Else's Phone")
        assertNull(result)

        gateway.settle()
        subject.onNameObserved("FBRG-Bike")
        assertTrue(result!!.isSuccess)
    }

    @Test
    fun `timeout fails the operation so startup can abort`() {
        val gateway = FakeGateway("Pixel 9")
        val scheduler = FakeScheduler()
        val subject = coordinator(gateway, scheduler = scheduler)

        var result: Result<Unit>? = null
        subject.apply("FBRG-Rower") { result = it }
        assertNull(result)

        scheduler.advance(3_000)

        assertTrue("must fail rather than advertise under the wrong name", result!!.isFailure)
        assertTrue(
            result!!.exceptionOrNull()!!.message!!.contains("FBRG-Rower"),
        )
    }

    @Test
    fun `the poll fallback confirms when no broadcast arrives`() {
        // Some stacks do not reliably emit ACTION_LOCAL_NAME_CHANGED.
        val gateway = FakeGateway("Pixel 9")
        val scheduler = FakeScheduler()
        val subject = coordinator(gateway, scheduler = scheduler)

        var result: Result<Unit>? = null
        subject.apply("FBRG-Bike") { result = it }

        gateway.settle()
        assertNull("not yet observed", result)

        scheduler.advance(100)
        assertTrue("poll should have noticed", result!!.isSuccess)
    }

    @Test
    fun `an already-correct name completes immediately without touching the stack`() {
        val gateway = FakeGateway("FBRG-Bike")
        val subject = coordinator(gateway)

        var result: Result<Unit>? = null
        subject.apply("FBRG-Bike") { result = it }

        assertTrue(result!!.isSuccess)
        assertEquals("no rename should have been requested", 0, gateway.requests)
    }

    @Test
    fun `a rejected rename fails fast`() {
        val gateway = FakeGateway("Pixel 9").apply { accept = false }
        val subject = coordinator(gateway)

        var result: Result<Unit>? = null
        subject.apply("FBRG-Bike") { result = it }

        assertTrue(result!!.isFailure)
    }

    // ── ordering: the second half of the bug ──────────────────────────────────

    @Test
    fun `a stranded restore cannot overwrite a later session rename`() {
        // The original failure mode: startup restoration and a new session's
        // rename were both in flight, and whichever the stack applied last won.
        val gateway = FakeGateway("FBRG-Treadmill")   // stranded from a crash
        val store = FakeStore("Pixel 9")              // user's real name
        val scheduler = FakeScheduler()
        val subject = coordinator(gateway, store, scheduler)

        val order = mutableListOf<String>()
        subject.restore { order += "restore" }
        subject.apply("FBRG-Bike") { order += "apply" }

        // Only the restore is in flight; the apply is queued behind it.
        assertEquals("Pixel 9", gateway.requested)
        assertEquals(1, gateway.requests)

        gateway.settle()
        subject.onNameObserved("Pixel 9")

        // Now, and only now, the session rename goes out.
        assertEquals("FBRG-Bike", gateway.requested)
        gateway.settle()
        subject.onNameObserved("FBRG-Bike")

        assertEquals(listOf("restore", "apply"), order)
        assertEquals("FBRG-Bike", gateway.currentName)
    }

    @Test
    fun `operations run one at a time in order`() {
        val gateway = FakeGateway("Pixel 9")
        val subject = coordinator(gateway)

        val done = mutableListOf<String>()
        subject.apply("FBRG-Bike") { done += "bike" }
        subject.apply("FBRG-Rower") { done += "rower" }

        assertEquals("only the first may be in flight", 1, gateway.requests)
        assertTrue(subject.isBusy)

        gateway.settle(); subject.onNameObserved("FBRG-Bike")
        assertEquals(listOf("bike"), done)

        gateway.settle(); subject.onNameObserved("FBRG-Rower")
        assertEquals(listOf("bike", "rower"), done)
        assertFalse(subject.isBusy)
    }

    // ── the user's original name ──────────────────────────────────────────────

    @Test
    fun `the user's name is saved once and restored`() {
        val gateway = FakeGateway("Jeff's Pixel")
        val store = FakeStore()
        val subject = coordinator(gateway, store)

        subject.apply("FBRG-Bike") {}
        assertEquals("Jeff's Pixel", store.value)
        gateway.settle(); subject.onNameObserved("FBRG-Bike")

        // A second session must not overwrite the saved original with our own
        // persona name.
        subject.apply("FBRG-Rower") {}
        assertEquals("Jeff's Pixel", store.value)
        gateway.settle(); subject.onNameObserved("FBRG-Rower")

        subject.restore {}
        gateway.settle(); subject.onNameObserved("Jeff's Pixel")
        assertEquals("Jeff's Pixel", gateway.currentName)
        assertNull("saved original should be forgotten once restored", store.value)
    }

    @Test
    fun `one of our own names is never recorded as the user's original`() {
        // Recovering from a crash: the adapter is still called FBRG-Treadmill.
        // Saving that would strand the phone under a persona name forever.
        val gateway = FakeGateway("FBRG-Treadmill")
        val store = FakeStore()
        val subject = coordinator(gateway, store)

        subject.apply("FBRG-Bike") {}

        assertNull("must not save a session name as the original", store.value)
    }

    @Test
    fun `a failed restore keeps the saved name for the next launch`() {
        val gateway = FakeGateway("FBRG-Bike")
        val store = FakeStore("Pixel 9")
        val scheduler = FakeScheduler()
        val subject = coordinator(gateway, store, scheduler)

        var result: Result<Unit>? = null
        subject.restore { result = it }
        scheduler.advance(3_000)

        assertTrue(result!!.isFailure)
        assertEquals("must be retried next launch", "Pixel 9", store.value)
    }

    @Test
    fun `restore with nothing stranded is a no-op success`() {
        val gateway = FakeGateway("Pixel 9")
        val subject = coordinator(gateway, FakeStore(null))

        var result: Result<Unit>? = null
        subject.restore { result = it }

        assertTrue(result!!.isSuccess)
        assertEquals(0, gateway.requests)
    }

    @Test
    fun `an unwritable store fails the apply rather than losing the user's name`() {
        val gateway = FakeGateway("Pixel 9")
        val store = FakeStore().apply { writable = false }
        val subject = coordinator(gateway, store)

        var result: Result<Unit>? = null
        subject.apply("FBRG-Bike") { result = it }

        assertTrue(result!!.isFailure)
        assertEquals("must not rename if we cannot remember the original", 0, gateway.requests)
    }

    @Test
    fun `cancelAll fails everything pending`() {
        val gateway = FakeGateway("Pixel 9")
        val subject = coordinator(gateway)

        var first: Result<Unit>? = null
        var second: Result<Unit>? = null
        subject.apply("FBRG-Bike") { first = it }
        subject.apply("FBRG-Rower") { second = it }

        subject.cancelAll("Bluetooth adapter is off")

        assertTrue(first!!.isFailure)
        assertTrue(second!!.isFailure)
        assertFalse(subject.isBusy)
    }

    @Test
    fun `every advertised name is recognised as one of ours`() {
        // The coordinator must never mistake a persona name for the user's own,
        // or a crash mid-session would strand their phone under it permanently.
        val names = com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine
            .BtServiceProfiles.FtmsMachineProfile.entries.map { it.advertisedName }
        assertTrue(
            names.all { it.startsWith(AdapterNameCoordinator.SESSION_NAME_PREFIX) },
        )
    }
}
