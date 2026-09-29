package com.gmail.absolutebollockscreations.gattlibrary.central

import android.bluetooth.BluetoothProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Phase 5b §11.4/§11.5 link-lifecycle and concurrent-context obligations. */
class PrecorConnectionTest {

    private val target = CccdTarget(
        BluetoothIds.UUID_PRECOR_SERVICE,
        BluetoothIds.UUID_PRECOR_MEASUREMENT,
    )

    @Test
    fun `Chrono discovery retry is bounded and resets for a reconnect`() {
        val gate = GattDiscoveryAttemptGate(maxAttempts = 3)

        assertEquals(1, gate.tryStartAttempt())
        assertTrue(gate.releaseForRetry())
        assertEquals(2, gate.tryStartAttempt())
        assertTrue(gate.releaseForRetry())
        assertEquals(3, gate.tryStartAttempt())
        assertFalse(gate.releaseForRetry())
        assertEquals(null, gate.tryStartAttempt())

        gate.reset()
        assertEquals(1, gate.tryStartAttempt())
    }

    @Test
    fun `Chrono becomes ready only after its one planned CCCD completes`() {
        val cccd = CccdState()

        assertEquals(listOf(target), cccd.mergeEnablePlan(listOf(target)))
        assertTrue(cccd.mergeEnablePlan(listOf(target)).isEmpty())
        assertEquals(1, cccd.plannedEnableCount)
        assertFalse(cccd.ready.value)

        cccd.incrementPending()
        assertTrue(cccd.completePending())
        assertTrue(cccd.ready.value)
    }

    @Test
    fun `a failed Chrono CCCD never reports the link ready`() {
        val cccd = CccdState()
        cccd.mergeEnablePlan(listOf(target))
        cccd.incrementPending()

        cccd.markEnablePlanFailed()

        assertFalse(cccd.completePending())
        assertFalse(cccd.ready.value)
    }

    @Test
    fun `a stale descriptor callback cannot consume a reconnected Chrono queue`() {
        val staleGatt = Any()
        val currentGatt = Any()
        val descriptor = Any()
        val queue = GattQueue()
        val entry = StackEntry(
            op = StackEntry.Op.DESC_WRITE,
            charUuid = BluetoothIds.UUID_PRECOR_MEASUREMENT,
            descriptorUuid = BluetoothIds.UUID_CCCD,
            descriptorIdentity = descriptor,
        )
        queue.enqueue(entry)
        val head = queue.beginDispatch()

        assertFalse(
            queueCompletionMatches(
                contextGatt = currentGatt,
                callbackGatt = staleGatt,
                head = head,
                callbackOp = StackEntry.Op.DESC_WRITE,
                callbackCharUuid = BluetoothIds.UUID_PRECOR_MEASUREMENT,
                callbackDescriptorUuid = BluetoothIds.UUID_CCCD,
                callbackDescriptorIdentity = descriptor,
            ),
        )
        assertSame(entry, queue.head())
        assertTrue(queue.isBusy)

        assertTrue(
            queueCompletionMatches(
                contextGatt = currentGatt,
                callbackGatt = currentGatt,
                head = head,
                callbackOp = StackEntry.Op.DESC_WRITE,
                callbackCharUuid = BluetoothIds.UUID_PRECOR_MEASUREMENT,
                callbackDescriptorUuid = BluetoothIds.UUID_CCCD,
                callbackDescriptorIdentity = descriptor,
            ),
        )
    }

    @Test
    fun `reconnecting Chrono gets a new context and empty queue and CCCD state`() {
        val registry = ConnectionRegistry()
        val first = registry.open(
            LegacyConnectionRole.FTMS,
            "C0:00:00:00:00:01",
            gatt = null,
            initialState = BluetoothProfile.STATE_CONNECTED,
        )
        first.handlers += PrecorProtocolHandler()
        first.queue.enqueue(StackEntry(StackEntry.Op.READ, charUuid = UUID.randomUUID()))
        first.queue.beginDispatch()
        first.cccd.mergeEnablePlan(listOf(target))
        first.cccd.incrementPending()

        val second = registry.open(
            LegacyConnectionRole.FTMS,
            "C0:00:00:00:00:01",
            gatt = null,
            initialState = BluetoothProfile.STATE_CONNECTING,
        )

        assertNotEquals(first.connectionId, second.connectionId)
        assertFalse(first.isActive)
        assertEquals(0, first.queue.size)
        assertEquals(0, first.cccd.pendingWrites)
        assertEquals(0, first.cccd.plannedEnableCount)
        assertEquals(0, second.queue.size)
        assertEquals(0, second.cccd.plannedEnableCount)
    }

    @Test
    fun `Chrono and three cycling sensors own independent queues and CCCD plans`() {
        val registry = ConnectionRegistry()
        val chrono = registry.open(
            LegacyConnectionRole.FTMS,
            "C0:00:00:00:00:01",
            gatt = null,
            initialState = BluetoothProfile.STATE_CONNECTED,
        )
        val hrm = registry.openAdditional("C0:00:00:00:00:02", null, BluetoothProfile.STATE_CONNECTED)
        val csc = registry.openAdditional("C0:00:00:00:00:03", null, BluetoothProfile.STATE_CONNECTED)
        val cps = registry.openAdditional("C0:00:00:00:00:04", null, BluetoothProfile.STATE_CONNECTED)
        val contexts = listOf(chrono, hrm, csc, cps)
        chrono.handlers += PrecorProtocolHandler()
        hrm.handlers += HrmProtocolHandler()
        csc.handlers += CscProtocolHandler()
        cps.handlers += CpsProtocolHandler()
        val characteristicUuids = listOf(
            BluetoothIds.UUID_PRECOR_MEASUREMENT,
            BluetoothIds.UUID_HEART_RATE_MEASUREMENT,
            BluetoothIds.UUID_CSC_MEASUREMENT,
            BluetoothIds.UUID_CYCLING_POWER_MEASUREMENT,
        )

        val descriptorIdentities = contexts.associateWith { Any() }
        val linkIdentities = contexts.associateWith { Any() }
        contexts.zip(characteristicUuids).forEach { (context, characteristicUuid) ->
            context.queue.enqueue(
                StackEntry(
                    StackEntry.Op.DESC_WRITE,
                    charUuid = characteristicUuid,
                    descriptorUuid = BluetoothIds.UUID_CCCD,
                    descriptorIdentity = descriptorIdentities.getValue(context),
                ),
            )
            context.queue.beginDispatch()
            context.cccd.mergeEnablePlan(listOf(CccdTarget(UUID.randomUUID(), characteristicUuid)))
            context.cccd.incrementPending()
        }

        assertEquals(4, contexts.map { it.queue }.distinctBy { System.identityHashCode(it) }.size)
        assertEquals(4, contexts.map { it.cccd }.distinctBy { System.identityHashCode(it) }.size)
        assertTrue(contexts.all { it.queue.size == 1 && it.queue.isBusy })
        assertTrue(contexts.all { it.cccd.pendingWrites == 1 && it.cccd.plannedEnableCount == 1 })

        val sensorHead = hrm.queue.head()
        assertFalse(
            queueCompletionMatches(
                contextGatt = linkIdentities.getValue(hrm),
                callbackGatt = linkIdentities.getValue(chrono),
                head = sensorHead,
                callbackOp = StackEntry.Op.DESC_WRITE,
                callbackCharUuid = BluetoothIds.UUID_PRECOR_MEASUREMENT,
                callbackDescriptorUuid = BluetoothIds.UUID_CCCD,
                callbackDescriptorIdentity = descriptorIdentities.getValue(chrono),
            ),
        )
        assertSame(sensorHead, hrm.queue.head())
        assertNotSame(chrono.queue, hrm.queue)
    }
}
