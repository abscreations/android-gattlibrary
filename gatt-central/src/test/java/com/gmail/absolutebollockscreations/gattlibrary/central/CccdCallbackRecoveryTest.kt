package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids

/** Callback-to-queue and bounded-state coverage for asynchronous CCCD failure recovery. */
class CccdCallbackRecoveryTest {

    private val characteristic = UUID.randomUUID()
    private val cccd = SigUuids.UUID_CCCD

    private fun failedQueue(
        descriptorIdentity: Any = Any(),
    ): Triple<GattQueue, StackEntry, Any> {
        val queue = GattQueue()
        val failed = StackEntry(
            op = StackEntry.Op.DESC_WRITE,
            charUuid = characteristic,
            descriptorUuid = cccd,
            descriptorIdentity = descriptorIdentity,
        )
        queue.enqueue(failed)
        queue.enqueue(StackEntry(StackEntry.Op.READ, UUID.randomUUID()))
        queue.beginDispatch()
        return Triple(queue, failed, descriptorIdentity)
    }

    @Test
    fun `matched callback failure consumes the descriptor head once and pauses later work`() {
        val gatt = Any()
        val (queue, failed, descriptorIdentity) = failedQueue()

        val consumed = consumeMatchedDescriptorFailure(
            contextGatt = gatt,
            callbackGatt = gatt,
            queue = queue,
            callbackCharUuid = characteristic,
            callbackDescriptorUuid = cccd,
            callbackDescriptorIdentity = descriptorIdentity,
        )

        assertSame(failed, consumed)
        assertEquals(1, queue.size)
        assertFalse(queue.isBusy)
        assertNull(
            consumeMatchedDescriptorFailure(
                gatt,
                gatt,
                queue,
                characteristic,
                cccd,
                descriptorIdentity,
            ),
        )
        assertEquals("the following operation must not drain before recovery", 1, queue.size)
    }

    @Test
    fun `wrong gatt characteristic descriptor or identity cannot claim the failed head`() {
        val gatt = Any()
        val wrongGatt = Any()
        val (queue, _, descriptorIdentity) = failedQueue()

        assertNull(consumeMatchedDescriptorFailure(gatt, wrongGatt, queue, characteristic, cccd, descriptorIdentity))
        assertNull(consumeMatchedDescriptorFailure(gatt, gatt, queue, UUID.randomUUID(), cccd, descriptorIdentity))
        assertNull(consumeMatchedDescriptorFailure(gatt, gatt, queue, characteristic, UUID.randomUUID(), descriptorIdentity))
        assertNull(consumeMatchedDescriptorFailure(gatt, gatt, queue, characteristic, cccd, Any()))
        assertEquals(2, queue.size)
        assertTrue(queue.isBusy)
    }

    @Test
    fun `recovery ownership ends on disconnect or a replacement gatt generation`() {
        val firstGatt = Any()

        assertTrue(cccdRecoveryStillOwned(firstGatt, firstGatt, contextActive = true))
        assertFalse(cccdRecoveryStillOwned(null, firstGatt, contextActive = false))
        assertFalse(cccdRecoveryStillOwned(Any(), firstGatt, contextActive = true))
        assertFalse(cccdRecoveryStillOwned(firstGatt, firstGatt, contextActive = false))
    }

    @Test
    fun `recovery is bounded and retry success resets only after the whole merged plan is ready`() {
        val state = CccdState()
        val service = UUID.randomUUID()
        state.mergeEnablePlan(
            listOf(
                CccdTarget(service, UUID.randomUUID()),
                CccdTarget(service, UUID.randomUUID()),
            ),
        )
        state.incrementPending()
        state.incrementPending()

        assertEquals(CccdRecoveryDecision.Retry(1), state.nextRecoveryDecision(MAX_CCCD_RECOVERY_ATTEMPTS))
        assertFalse(state.completePending())
        assertEquals("one success must not forgive a failed merged plan", 1, state.recoveryAttempts)
        assertTrue(state.completePending())
        assertFalse("readiness transitions only once", state.completePending())

        state.resetRecoveryAttempts()
        assertEquals(0, state.recoveryAttempts)
        assertEquals(CccdRecoveryDecision.Retry(1), state.nextRecoveryDecision(MAX_CCCD_RECOVERY_ATTEMPTS))
        assertEquals(CccdRecoveryDecision.Retry(2), state.nextRecoveryDecision(MAX_CCCD_RECOVERY_ATTEMPTS))
        assertEquals(CccdRecoveryDecision.Terminate, state.nextRecoveryDecision(MAX_CCCD_RECOVERY_ATTEMPTS))
    }

    @Test
    fun `dispatch and callback failure causes retain their authoritative origin`() {
        assertEquals("dispatch-api33-status=5", CccdWriteFailureCause.Api33Dispatch(5).detail)
        assertEquals("dispatch-legacy-rejected", CccdWriteFailureCause.LegacyDispatchRejected.detail)
        assertEquals("callback-status=133", CccdWriteFailureCause.Callback(133).detail)
    }
}
