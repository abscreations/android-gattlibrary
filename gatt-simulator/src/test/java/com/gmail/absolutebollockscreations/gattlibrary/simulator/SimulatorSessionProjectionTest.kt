package com.gmail.absolutebollockscreations.gattlibrary.simulator

import android.bluetooth.BluetoothProfile
import com.gmail.absolutebollockscreations.gattlibrary.central.BtEngineState
import com.gmail.absolutebollockscreations.gattlibrary.central.LegacyConnectionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulatorSessionProjectionTest {
    @Test
    fun `local simulator session never becomes a connected physical device`() {
        val state = BtEngineState()
        val projection = SimulatorSessionProjection(state)

        projection.begin(haveControl = true)

        assertTrue(state.activeSession.value)
        assertEquals(true, state.isRunning.value)
        assertTrue(state.haveControlFlow.value)
        assertTrue(state.connectedDevices.value.isEmpty())

        state.setSpeedKph(24.0)
        state.setInstantPower(185)
        projection.end()

        assertFalse(state.activeSession.value)
        assertEquals(false, state.isRunning.value)
        assertFalse(state.haveControlFlow.value)
        assertNull(state.speedKph.value)
        assertNull(state.instantPower.value)
        assertTrue(state.connectedDevices.value.isEmpty())
    }

    @Test
    fun `ending simulator session preserves an unrelated physical connection`() {
        val state = BtEngineState()
        val physical = state.openConnectionContext(
            role = LegacyConnectionRole.HRM,
            address = "AA:BB:CC:DD:EE:FF",
            handlers = emptyList(),
        )
        state.setConnectionState(physical, BluetoothProfile.STATE_CONNECTED)
        val physicalKey = state.connectedDevices.value.single().connectionKey
        val projection = SimulatorSessionProjection(state)

        projection.begin(haveControl = false)
        projection.end()

        assertEquals(physicalKey, state.connectedDevices.value.single().connectionKey)
        assertEquals(BluetoothProfile.STATE_CONNECTED, state.hrmConnectionState.value)
    }
}
