package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CpsNativeEventStoreTest {
    @Test
    fun `store retains omitted native fields and removes exact link generation`() {
        val store = CpsNativeEventStore()
        val id = ConnectionId("sensor", 4)
        store.publish(id, CpsMeasurement.parse(hex("10 00 64 00 04 03 02 01 06 05"))!!)
        store.publish(id, CpsMeasurement.parse(hex("20 00 65 00 08 07 0A 09"))!!)
        val snapshot = store.snapshot()!!
        assertEquals(0x01020304L, snapshot.cumulativeWheelRevolutions)
        assertEquals(0x0708, snapshot.cumulativeCrankRevolutions)
        store.remove(id)
        assertNull(store.snapshot())
    }

    private fun hex(value: String): ByteArray = value.trim().split(Regex("\\s+"))
        .map { it.toInt(16).toByte() }.toByteArray()
}
