package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class LegacyGattCharacterizationTest {

    private class RecordingHandler(
        override val primaryServices: Set<UUID>,
    ) : BtEngineGattCore.ProtocolHandler {
        override fun onServicesDiscovered(
            engine: BtEngine,
            gatt: BluetoothGatt,
            services: List<BluetoothGattService>,
        ): Boolean = true
    }

    @Test
    fun `FTMS and HRM discovery CCCD completion and disconnect remain independent`() {
        val ftmsGatt = Any()
        val hrmGatt = Any()
        val connected = LegacyConnectionSlots(ftms = ftmsGatt, hrm = hrmGatt)

        assertSame(ftmsGatt, connected.current(LegacyConnectionRole.FTMS))
        assertSame(hrmGatt, connected.current(LegacyConnectionRole.HRM))

        val ftmsHandler = RecordingHandler(setOf(BluetoothIds.UUID_FTMS_SERVICE))
        val hrmHandler = RecordingHandler(setOf(BluetoothIds.UUID_HRM_SERVICE))
        val handlers = listOf(ftmsHandler, hrmHandler)

        assertEquals(
            listOf(ftmsHandler),
            matchingProtocolHandlers(handlers, setOf(BluetoothIds.UUID_FTMS_SERVICE)),
        )
        assertEquals(
            listOf(hrmHandler),
            matchingProtocolHandlers(handlers, setOf(BluetoothIds.UUID_HRM_SERVICE)),
        )

        val state = BtEngineState().apply {
            connectionRegistry.open(
                LegacyConnectionRole.FTMS,
                "AA:BB:CC:DD:EE:01",
                gatt = null,
                initialState = android.bluetooth.BluetoothProfile.STATE_CONNECTED,
            ).cccd.apply {
                incrementPending()
                incrementPending()
            }
        }
        val context = state.connectionRegistry.current(LegacyConnectionRole.FTMS)!!
        assertFalse(state.connectionRegistry.completeCccdWrite(context))
        assertEquals(1, context.cccd.pendingWrites)
        assertFalse(state.cccdReady.value)
        assertTrue(state.connectionRegistry.completeCccdWrite(context))
        assertEquals(0, context.cccd.pendingWrites)
        assertTrue(state.cccdReady.value)

        val afterHrmDisconnect = connected.without(LegacyConnectionRole.HRM)
        assertSame(ftmsGatt, afterHrmDisconnect.ftms)
        assertNull(afterHrmDisconnect.hrm)

        val afterFtmsDisconnect = connected.without(LegacyConnectionRole.FTMS)
        assertNull(afterFtmsDisconnect.ftms)
        assertSame(hrmGatt, afterFtmsDisconnect.hrm)
    }

    @Test
    fun `legacy callbacks retain their FTMS and HRM roles`() {
        val ftms = RecordingHandler(setOf(BluetoothIds.UUID_FTMS_SERVICE))
        val hrm = RecordingHandler(setOf(BluetoothIds.UUID_HRM_SERVICE))

        assertEquals(LegacyConnectionRole.FTMS, legacyConnectionRole(listOf(ftms)))
        assertEquals(LegacyConnectionRole.HRM, legacyConnectionRole(listOf(hrm)))
    }
}
