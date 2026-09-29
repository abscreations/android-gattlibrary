package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class InternalControlRoutingTest {
    @Test
    fun `selected adapter routes control to FE-C only when its handler is active`() {
        assertEquals(MachineControlRoute.FTMS, machineControlRoute(null, false))
        assertEquals(
            MachineControlRoute.FTMS,
            machineControlRoute(AdapterLivenessCoordinator.FTMS_ADAPTER_ID, true),
        )
        assertEquals(
            MachineControlRoute.FTMS,
            machineControlRoute(AdapterLivenessCoordinator.FEC_ADAPTER_ID, false),
        )
        assertEquals(
            MachineControlRoute.FEC,
            machineControlRoute(AdapterLivenessCoordinator.FEC_ADAPTER_ID, true),
        )
        assertEquals(
            "a selected Wahoo adapter remains closed until its endpoint unit lands",
            MachineControlRoute.UNAVAILABLE,
            machineControlRoute(AdapterLivenessCoordinator.WAHOO_KICKR_ADAPTER_ID, false),
        )
    }

    @Test
    fun `Flywheel remains unavailable for downstream control`() {
        assertEquals(
            MachineControlRoute.UNAVAILABLE,
            machineControlRoute(AdapterLivenessCoordinator.FLYWHEEL_ADAPTER_ID, false),
        )
    }

    @Test
    fun `Flywheel CCCD queue completion cannot be consumed by another GATT`() {
        val queue = GattQueue()
        val descriptor = Any()
        val expectedGatt = Any()
        val otherGatt = Any()
        val entry = StackEntry(
            op = StackEntry.Op.DESC_WRITE,
            charUuid = BluetoothIds.UUID_FLYWHEEL_TX,
            descriptorUuid = BluetoothIds.UUID_CCCD,
            descriptorIdentity = descriptor,
        )
        queue.enqueue(entry)
        val head = queue.beginDispatch()

        assertFalse(
            queueCompletionMatches(
                contextGatt = expectedGatt,
                callbackGatt = otherGatt,
                head = head,
                callbackOp = StackEntry.Op.DESC_WRITE,
                callbackCharUuid = BluetoothIds.UUID_FLYWHEEL_TX,
                callbackDescriptorUuid = BluetoothIds.UUID_CCCD,
                callbackDescriptorIdentity = descriptor,
            ),
        )
        assertSame(entry, queue.head())
        assertTrue(queue.isBusy)
    }

    @Test
    fun `request control requires readiness completed setup and a real control point`() {
        assertFalse(canRequestFtmsControl(cccdReady = false, setupPending = false, controlPointPresent = true))
        assertFalse(canRequestFtmsControl(cccdReady = true, setupPending = true, controlPointPresent = true))
        assertFalse(canRequestFtmsControl(cccdReady = true, setupPending = false, controlPointPresent = false))
        assertTrue(canRequestFtmsControl(cccdReady = true, setupPending = false, controlPointPresent = true))
    }
}
