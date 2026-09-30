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
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.FecBleUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids

/** Phase 9 §11.2, §11.4 and §11.5 evidence for the FE-C-specific GATT lifecycle. */
class FeCConnectionAndGattTest {

    private val fecCccd = CccdTarget(
        FecBleUuids.UUID_FEC_SERVICE,
        FecBleUuids.UUID_FEC_MEASUREMENT,
    )

    @Test
    fun `discovery requires FEC1 and FEC2 while FEC3 remains optional`() {
        val both = setOf(FecBleUuids.UUID_FEC_MEASUREMENT, FecBleUuids.UUID_FEC_CONTROL)

        assertFalse(planFeCDiscovery(false, both).activate)
        assertFalse(planFeCDiscovery(true, setOf(FecBleUuids.UUID_FEC_CONTROL)).activate)

        val measurementOnly = planFeCDiscovery(true, setOf(FecBleUuids.UUID_FEC_MEASUREMENT))
        assertTrue(measurementOnly.activate)
        assertFalse(measurementOnly.controlAvailable)
        assertEquals(
            listOf(FeCGattOperation(FeCGattOperationKind.ENABLE_NOTIFICATIONS, FecBleUuids.UUID_FEC_MEASUREMENT)),
            measurementOnly.operations,
        )

        val controlled = planFeCDiscovery(true, both)
        assertTrue(controlled.activate)
        assertTrue(controlled.controlAvailable)
        assertEquals(measurementOnly.operations, controlled.operations)
    }

    @Test
    fun `FE-C readiness waits for its one FEC2 CCCD and failure stays not ready`() {
        val success = CccdState()
        assertEquals(listOf(fecCccd), success.mergeEnablePlan(listOf(fecCccd)))
        assertTrue(success.mergeEnablePlan(listOf(fecCccd)).isEmpty())
        success.incrementPending()
        assertFalse(success.ready.value)
        assertTrue(success.completePending())
        assertTrue(success.ready.value)

        val failed = CccdState()
        failed.mergeEnablePlan(listOf(fecCccd))
        failed.incrementPending()
        failed.markEnablePlanFailed()
        assertFalse(failed.completePending())
        assertFalse(failed.ready.value)
    }

    @Test
    fun `stale FEC2 CCCD callback cannot complete a reconnected generation`() {
        val staleGatt = Any()
        val currentGatt = Any()
        val descriptor = Any()
        val queue = GattQueue()
        val entry = StackEntry(
            op = StackEntry.Op.DESC_WRITE,
            charUuid = FecBleUuids.UUID_FEC_MEASUREMENT,
            descriptorUuid = SigUuids.UUID_CCCD,
            descriptorIdentity = descriptor,
        )
        queue.enqueue(entry)
        val head = queue.beginDispatch()

        assertFalse(
            queueCompletionMatches(
                currentGatt,
                staleGatt,
                head,
                StackEntry.Op.DESC_WRITE,
                FecBleUuids.UUID_FEC_MEASUREMENT,
                SigUuids.UUID_CCCD,
                descriptor,
            ),
        )
        assertSame(entry, queue.head())
        assertTrue(queue.isBusy)
    }

    @Test
    fun `late FEC3 callback cannot consume the operation after local no-response completion`() {
        val gatt = Any()
        val queue = GattQueue()
        val fecWrite = StackEntry(
            op = StackEntry.Op.WRITE,
            charUuid = FecBleUuids.UUID_FEC_CONTROL,
            data = byteArrayOf(0x31),
            writeMode = StackEntry.WriteMode.WITHOUT_RESPONSE,
        )
        val followingRead = StackEntry(
            op = StackEntry.Op.READ,
            charUuid = SigUuids.UUID_DEVICE_INFORMATION_SERVICE,
        )
        queue.enqueue(fecWrite)
        queue.enqueue(followingRead)
        assertSame(fecWrite, queue.beginDispatch())
        assertSame(fecWrite, queue.finishHead())
        assertSame(followingRead, queue.beginDispatch())

        assertFalse(
            queueCompletionMatches(
                gatt,
                gatt,
                queue.head(),
                StackEntry.Op.WRITE,
                FecBleUuids.UUID_FEC_CONTROL,
            ),
        )
        assertSame(followingRead, queue.head())
    }

    @Test
    fun `reconnecting FE-C gets a fresh handler queue CCCD state and generation`() {
        val registry = ConnectionRegistry()
        val first = registry.open(
            LegacyConnectionRole.FTMS,
            "FE:C0:00:00:00:01",
            null,
            BluetoothProfile.STATE_CONNECTED,
        )
        val firstHandler = FeCOverBleProtocolHandler()
        first.handlers += firstHandler
        first.queue.enqueue(StackEntry(StackEntry.Op.WRITE, charUuid = FecBleUuids.UUID_FEC_CONTROL))
        first.queue.beginDispatch()
        first.cccd.mergeEnablePlan(listOf(fecCccd))
        first.cccd.incrementPending()

        val second = registry.open(
            LegacyConnectionRole.FTMS,
            "FE:C0:00:00:00:01",
            null,
            BluetoothProfile.STATE_CONNECTING,
        )
        val secondHandler = FeCOverBleProtocolHandler()
        second.handlers += secondHandler

        assertNotEquals(first.connectionId, second.connectionId)
        assertFalse(first.isActive)
        assertEquals(0, first.queue.size)
        assertEquals(0, first.cccd.pendingWrites)
        assertEquals(0, second.queue.size)
        assertEquals(0, second.cccd.plannedEnableCount)
        assertNotSame(firstHandler, secondHandler)
    }

    @Test
    fun `FE-C machine and HRM CSC CPS sensors own independent queues and CCCD plans`() {
        val registry = ConnectionRegistry()
        val fec = registry.open(LegacyConnectionRole.FTMS, "FE:C0:00:00:00:01", null, BluetoothProfile.STATE_CONNECTED)
        val hrm = registry.openAdditional("FE:C0:00:00:00:02", null, BluetoothProfile.STATE_CONNECTED)
        val csc = registry.openAdditional("FE:C0:00:00:00:03", null, BluetoothProfile.STATE_CONNECTED)
        val cps = registry.openAdditional("FE:C0:00:00:00:04", null, BluetoothProfile.STATE_CONNECTED)
        val contexts = listOf(fec, hrm, csc, cps)
        fec.handlers += FeCOverBleProtocolHandler()
        hrm.handlers += HrmProtocolHandler()
        csc.handlers += CscProtocolHandler()
        cps.handlers += CpsProtocolHandler()
        val characteristics = listOf(
            FecBleUuids.UUID_FEC_MEASUREMENT,
            SigUuids.UUID_HEART_RATE_MEASUREMENT,
            SigUuids.UUID_CSC_MEASUREMENT,
            SigUuids.UUID_CYCLING_POWER_MEASUREMENT,
        )

        contexts.zip(characteristics).forEach { (context, characteristic) ->
            context.queue.enqueue(StackEntry(StackEntry.Op.DESC_WRITE, charUuid = characteristic))
            context.queue.beginDispatch()
            context.cccd.mergeEnablePlan(listOf(CccdTarget(UUID.randomUUID(), characteristic)))
            context.cccd.incrementPending()
        }

        assertEquals(4, contexts.map { it.queue }.distinctBy { System.identityHashCode(it) }.size)
        assertEquals(4, contexts.map { it.cccd }.distinctBy { System.identityHashCode(it) }.size)
        assertTrue(contexts.all { it.queue.isBusy && it.cccd.pendingWrites == 1 })
        assertNotSame(fec.queue, cps.queue)
    }

    @Test
    fun `same-link FE-C CPS CSC HRM handlers keep explicit ownership and one merged CCCD plan`() {
        val handlers = listOf(
            FeCOverBleProtocolHandler(),
            CpsProtocolHandler(),
            CscProtocolHandler(),
            HrmProtocolHandler(),
        )
        val expectedOwners = mapOf(
            FecBleUuids.UUID_FEC_MEASUREMENT to handlers[0],
            SigUuids.UUID_CYCLING_POWER_MEASUREMENT to handlers[1],
            SigUuids.UUID_CSC_MEASUREMENT to handlers[2],
            SigUuids.UUID_HEART_RATE_MEASUREMENT to handlers[3],
        )
        expectedOwners.forEach { (characteristic, owner) ->
            assertEquals(
                listOf(owner),
                resolveProtocolHandlerRoute(handlers, characteristic, null).owners,
            )
        }

        val cccd = CccdState()
        val plan = expectedOwners.keys.map { CccdTarget(UUID.randomUUID(), it) }
        assertEquals(plan, cccd.mergeEnablePlan(plan))
        assertEquals(4, cccd.plannedEnableCount)
        assertFalse(cccd.ready.value)
        repeat(4) { cccd.incrementPending() }
        repeat(3) { assertFalse(cccd.completePending()) }
        assertTrue(cccd.completePending())
    }

    @Test
    fun `capability timeout is bounded and cannot overwrite a resolved response`() {
        assertFalse(feCCapabilityResponseTimedOut(null, Long.MAX_VALUE, false))
        assertFalse(feCCapabilityResponseTimedOut(3_000, 2_999, false))
        assertTrue(feCCapabilityResponseTimedOut(3_000, 3_000, false))
        assertFalse(feCCapabilityResponseTimedOut(3_000, 4_000, true))
    }
}
