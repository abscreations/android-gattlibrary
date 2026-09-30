package com.gmail.absolutebollockscreations.gattlibrary.conformance

import com.gmail.absolutebollockscreations.gattlibrary.server.AdapterNameCoordinator
import com.gmail.absolutebollockscreations.gattlibrary.simulator.simulatorPersonaOptions
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Split out of the server's AdapterNameCoordinatorTest (it needs the simulator's persona list): no
 * selector name can be mistaken for the user's own adapter name. A persona added with a bare name
 * and no entry in the coordinator's matcher would silently reopen the stranding bug.
 */
class PersonaNamesNotStrandedTest {

    private class Gateway(var name: String?) : AdapterNameCoordinator.Gateway {
        override val currentName: String? get() = name
        override fun requestName(name: String): Boolean = true
    }

    private class Store : AdapterNameCoordinator.OriginalNameStore {
        var value: String? = null
        override fun read(): String? = value
        override fun write(name: String): Boolean {
            value = name
            return true
        }

        override fun clear(): Boolean {
            value = null
            return true
        }
    }

    /** Nothing fires; the test only needs the original-name decision made in apply(). */
    private class IdleScheduler : AdapterNameCoordinator.Scheduler {
        override fun postDelayed(token: Any, delayMs: Long, action: () -> Unit) = Unit
        override fun cancel(token: Any) = Unit
    }

    @Test
    fun `no persona in the selector can be mistaken for the user's own name`() {
        simulatorPersonaOptions(includeKickr = true)
            .map { it.advertisedName }
            .distinct()
            .forEach { stranded ->
                val store = Store()
                AdapterNameCoordinator(Gateway(stranded), store, IdleScheduler(), timeoutMs = 3_000, pollIntervalMs = 100)
                    .apply("FBRG-Bike") {}
                assertNull("a crash leaving the adapter named '$stranded' would strand the phone", store.value)
            }
    }
}
