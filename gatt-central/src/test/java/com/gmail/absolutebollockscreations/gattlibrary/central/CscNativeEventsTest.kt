package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CscNativeEventsTest {

    @Test
    fun `split wheel and crank links compose exact high-bit native fields`() {
        val store = CscNativeEventStore()
        store.publish(
            ConnectionId("WHEEL", 7),
            CscMeasurement.Reading(
                wheel = CscMeasurement.WheelEvent(0xFEDC_BA98L, 0xFFFF),
            ),
            setOf(BtEngineState.CscRole.SPEED),
        )
        store.publish(
            ConnectionId("CRANK", 8),
            CscMeasurement.Reading(
                crank = CscMeasurement.CrankEvent(0xFFFE, 0),
            ),
            setOf(BtEngineState.CscRole.CADENCE),
        )

        assertEquals(
            CscNativeEventSnapshot(
                cumulativeWheelRevolutions = 0xFEDC_BA98L,
                lastWheelEventTimeTicks = 0xFFFF,
                cumulativeCrankRevolutions = 0xFFFE,
                lastCrankEventTimeTicks = 0,
            ),
            store.snapshot(),
        )
    }

    @Test
    fun `partial combined notifications retain the last assigned peer field`() {
        val store = CscNativeEventStore()
        val connection = ConnectionId("COMBINED", 2)
        val both = BtEngineState.CscRole.entries.toSet()
        store.publish(
            connection,
            CscMeasurement.Reading(
                wheel = CscMeasurement.WheelEvent(10, 20),
                crank = CscMeasurement.CrankEvent(30, 40),
            ),
            both,
        )
        store.publish(
            connection,
            CscMeasurement.Reading(
                wheel = CscMeasurement.WheelEvent(11, 21),
            ),
            both,
        )

        assertEquals(
            CscNativeEventSnapshot(11, 21, 30, 40),
            store.snapshot(),
        )
    }

    @Test
    fun `unassigned fields are ignored and link removal clears only that link`() {
        val store = CscNativeEventStore()
        val wheel = ConnectionId("WHEEL", 1)
        val crank = ConnectionId("CRANK", 1)
        store.publish(
            wheel,
            CscMeasurement.Reading(
                wheel = CscMeasurement.WheelEvent(1, 2),
                crank = CscMeasurement.CrankEvent(3, 4),
            ),
            setOf(BtEngineState.CscRole.SPEED),
        )
        store.publish(
            crank,
            CscMeasurement.Reading(crank = CscMeasurement.CrankEvent(5, 6)),
            setOf(BtEngineState.CscRole.CADENCE),
        )

        assertEquals(CscNativeEventSnapshot(1, 2, 5, 6), store.snapshot())
        store.remove(wheel)
        assertEquals(CscNativeEventSnapshot(null, null, 5, 6), store.snapshot())
        store.clear()
        assertNull(store.snapshot())
    }
}
