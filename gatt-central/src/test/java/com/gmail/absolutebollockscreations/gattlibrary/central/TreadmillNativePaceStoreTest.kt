package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Plan §11.4 lifecycle coverage for the native treadmill pace store. */
class TreadmillNativePaceStoreTest {
    private fun conn(generation: Long) = ConnectionId("AA:BB:CC:DD:EE:F0", generation)

    @Test
    fun `the store keeps raw octets and merges partial updates`() {
        val store = TreadmillNativePaceStore()
        val c = conn(1L)

        store.publish(c, instantaneousRaw = 2, averageRaw = null)
        assertEquals(2, store.snapshot()?.instantaneousRaw)
        assertNull(store.snapshot()?.averageRaw)

        // A later frame carrying only the average must not erase the instantaneous value:
        // the two share a characteristic but not a flag, so either can arrive alone.
        store.publish(c, instantaneousRaw = null, averageRaw = 3)
        assertEquals(2, store.snapshot()?.instantaneousRaw)
        assertEquals(3, store.snapshot()?.averageRaw)

        store.publish(c, instantaneousRaw = 4, averageRaw = null)
        assertEquals(4, store.snapshot()?.instantaneousRaw)
        assertEquals(3, store.snapshot()?.averageRaw)
    }

    @Test
    fun `a publish carrying nothing is ignored`() {
        val store = TreadmillNativePaceStore()
        store.publish(conn(1L), instantaneousRaw = null, averageRaw = null)
        assertNull(store.snapshot())
    }

    @Test
    fun `the newest link generation wins`() {
        val store = TreadmillNativePaceStore()
        store.publish(conn(1L), 2, null)
        store.publish(conn(7L), 5, null)

        assertEquals(
            "a reconnect opens a new generation and its readings supersede the old link's",
            5,
            store.snapshot()?.instantaneousRaw,
        )
    }

    @Test
    fun `removing a connection drops its pace, and clear empties the store`() {
        val store = TreadmillNativePaceStore()
        store.publish(conn(1L), 2, 3)
        store.remove(conn(1L))
        assertNull("a disconnected machine must not keep supplying pace", store.snapshot())

        store.publish(conn(2L), 4, null)
        store.clear()
        assertNull(store.snapshot())
    }
}
