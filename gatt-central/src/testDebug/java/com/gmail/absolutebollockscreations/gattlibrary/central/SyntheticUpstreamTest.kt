package com.gmail.absolutebollockscreations.gattlibrary.central

import android.bluetooth.BluetoothProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticUpstreamTest {
    @Test
    fun `local simulator session never becomes a connected physical device`() {
        val state = BtEngineState()
        val projection = SyntheticUpstream(state)

        projection.beginSession(haveControl = true)

        assertTrue(state.activeSession.value)
        assertEquals(true, state.isRunning.value)
        assertTrue(state.haveControlFlow.value)
        assertTrue(state.connectedDevices.value.isEmpty())

        state.setSpeedKph(24.0)
        state.setInstantPower(185)
        projection.endSession()

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
        val projection = SyntheticUpstream(state)

        projection.beginSession(haveControl = false)
        projection.endSession()

        assertEquals(physicalKey, state.connectedDevices.value.single().connectionKey)
        assertEquals(BluetoothProfile.STATE_CONNECTED, state.hrmConnectionState.value)
    }

    @Test
    fun `published samples land in engine state and untouched metrics stay untouched`() {
        val state = BtEngineState()
        val host = SyntheticUpstream(state)
        host.beginSession(haveControl = false)

        host.publish(
            SyntheticSample(
                elapsedSeconds = 5,
                heartRate = 120,
                caloriesTotal = 3,
                caloriesPerHour = 400,
                speedKph = 24.0,
                powerWatts = 185,
            ),
        )

        assertEquals(24.0, state.speedKph.value!!, 0.0001)
        assertEquals(185, state.instantPower.value)
        assertNull(state.inclination.value)
    }
}
