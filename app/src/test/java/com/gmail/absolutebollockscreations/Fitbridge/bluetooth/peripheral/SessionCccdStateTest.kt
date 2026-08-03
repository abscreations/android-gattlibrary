package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtServiceProfiles.FtmsMachineProfile

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionCccdStateTest {
    private val measurement = UUID.fromString("00002ad2-0000-1000-8000-00805f9b34fb")
    private val controlPoint = UUID.fromString("00002ad9-0000-1000-8000-00805f9b34fb")
    private val cscMeasurement = UUID.fromString("00002a5b-0000-1000-8000-00805f9b34fb")
    private val cyclingPowerMeasurement = UUID.fromString("00002a63-0000-1000-8000-00805f9b34fb")
    private val scControlPoint = UUID.fromString("00002a55-0000-1000-8000-00805f9b34fb")

    @Test
    fun `a new connection starts with every CCCD disabled`() {
        val state = SessionCccdState()

        assertEquals(PeripheralConnection.CccdMode.DISABLED, state.mode(measurement))
        assertEquals(PeripheralConnection.CccdMode.DISABLED, state.mode(controlPoint))
    }

    @Test
    fun `disconnect reset clears notify and indicate subscriptions`() {
        val state = SessionCccdState()
        state.write(measurement, PeripheralConnection.CccdMode.NOTIFY)
        state.write(controlPoint, PeripheralConnection.CccdMode.INDICATE)

        state.clear()

        assertEquals(PeripheralConnection.CccdMode.DISABLED, state.mode(measurement))
        assertEquals(PeripheralConnection.CccdMode.DISABLED, state.mode(controlPoint))
    }

    @Test
    fun `disabled write removes only the selected subscription`() {
        val state = SessionCccdState()
        state.write(measurement, PeripheralConnection.CccdMode.NOTIFY)
        state.write(controlPoint, PeripheralConnection.CccdMode.INDICATE)

        state.write(measurement, PeripheralConnection.CccdMode.DISABLED)

        assertEquals(PeripheralConnection.CccdMode.DISABLED, state.mode(measurement))
        assertEquals(PeripheralConnection.CccdMode.INDICATE, state.mode(controlPoint))
    }

    @Test
    fun `native cycling cccds remain independent`() {
        val state = SessionCccdState()
        state.write(cscMeasurement, PeripheralConnection.CccdMode.NOTIFY)
        state.write(cyclingPowerMeasurement, PeripheralConnection.CccdMode.NOTIFY)
        state.write(scControlPoint, PeripheralConnection.CccdMode.INDICATE)

        state.write(cscMeasurement, PeripheralConnection.CccdMode.DISABLED)

        assertEquals(PeripheralConnection.CccdMode.DISABLED, state.mode(cscMeasurement))
        assertEquals(PeripheralConnection.CccdMode.NOTIFY, state.mode(cyclingPowerMeasurement))
        assertEquals(PeripheralConnection.CccdMode.INDICATE, state.mode(scControlPoint))
        assertEquals(PeripheralConnection.CccdMode.DISABLED, state.mode(measurement))
    }
}
