package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class QueueTargetingCharacterizationTest {

    @Test
    fun `two contexts can each hold an in-flight operation without cross-completion`() {
        val ftmsGatt = Any()
        val hrmGatt = Any()
        val ftmsUuid = UUID.randomUUID()
        val hrmUuid = UUID.randomUUID()
        val ftmsQueue = GattQueue()
        val hrmQueue = GattQueue()
        ftmsQueue.enqueue(StackEntry(StackEntry.Op.WRITE, charUuid = ftmsUuid))
        hrmQueue.enqueue(StackEntry(StackEntry.Op.READ, charUuid = hrmUuid))

        val ftmsHead = ftmsQueue.beginDispatch()
        val hrmHead = hrmQueue.beginDispatch()

        assertTrue(ftmsQueue.isBusy)
        assertTrue(hrmQueue.isBusy)
        assertFalse(
            queueCompletionMatches(
                ftmsGatt,
                hrmGatt,
                ftmsHead,
                StackEntry.Op.READ,
                hrmUuid,
            ),
        )
        assertFalse(
            queueCompletionMatches(
                ftmsGatt,
                ftmsGatt,
                ftmsHead,
                StackEntry.Op.WRITE,
                hrmUuid,
            ),
        )
        assertTrue(
            queueCompletionMatches(
                hrmGatt,
                hrmGatt,
                hrmHead,
                StackEntry.Op.READ,
                hrmUuid,
            ),
        )

        hrmQueue.finishIfHead(hrmHead!!)

        assertEquals(1, ftmsQueue.size)
        assertTrue(ftmsQueue.isBusy)
        assertEquals(0, hrmQueue.size)
        assertFalse(hrmQueue.isBusy)
    }

    @Test
    fun `body sensor location read retains its HRM context`() {
        val hrmContext = Any()

        assertSame(hrmContext, bodySensorLocationReadContext(hrmContext))
    }

    @Test
    fun `completion operation must match the in-flight queue head`() {
        val gatt = Any()
        val uuid = UUID.randomUUID()
        val head = StackEntry(StackEntry.Op.WRITE, charUuid = uuid)

        assertFalse(
            queueCompletionMatches(
                gatt,
                gatt,
                head,
                StackEntry.Op.READ,
                uuid,
            ),
        )
    }
}
