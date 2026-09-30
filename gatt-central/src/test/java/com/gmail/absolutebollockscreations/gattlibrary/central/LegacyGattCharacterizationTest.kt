package com.gmail.absolutebollockscreations.gattlibrary.central

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.Concept2Uuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids

class LegacyGattCharacterizationTest {

    @Test
    fun `Concept2 discovery excludes FTMS activation but preserves native and sensor handlers`() {
        val ftms = RecordingHandler(setOf(FtmsUuids.UUID_FTMS_SERVICE))
        val concept2 = RecordingHandler(setOf(Concept2Uuids.UUID_C2_ERG_SERVICE))
        val hrm = RecordingHandler(setOf(SigUuids.UUID_HRM_SERVICE))
        val services = setOf(FtmsUuids.UUID_FTMS_SERVICE, Concept2Uuids.UUID_C2_ERG_SERVICE,
            SigUuids.UUID_HRM_SERVICE)
        val offered = mutableListOf<BtEngineGattCore.ProtocolHandler>()

        assertEquals(listOf(concept2, hrm), matchingProtocolHandlers(listOf(ftms, concept2, hrm), services))
        assertEquals(listOf(concept2, hrm), activateMatchingProtocolHandlers(
            listOf(ftms, concept2, hrm), services,
        ) { offered += it; true })
        assertEquals(listOf(concept2, hrm), offered)
        assertTrue("diagnostic inventory must retain FTMS", FtmsUuids.UUID_FTMS_SERVICE in services)
    }

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

        val ftmsHandler = RecordingHandler(setOf(FtmsUuids.UUID_FTMS_SERVICE))
        val hrmHandler = RecordingHandler(setOf(SigUuids.UUID_HRM_SERVICE))
        val handlers = listOf(ftmsHandler, hrmHandler)

        assertEquals(
            listOf(ftmsHandler),
            matchingProtocolHandlers(handlers, setOf(FtmsUuids.UUID_FTMS_SERVICE)),
        )
        assertEquals(
            listOf(hrmHandler),
            matchingProtocolHandlers(handlers, setOf(SigUuids.UUID_HRM_SERVICE)),
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
    fun `synthetic context reports state and discovery without touching legacy projections`() {
        val syntheticService = UUID.randomUUID()
        val syntheticHandler = RecordingHandler(setOf(syntheticService))
        val state = BtEngineState()
        val context = ConnectionContext(
            connectionId = ConnectionId("SYNTHETIC", 1L),
            address = "SYNTHETIC",
            gatt = null,
            connectionState = MutableStateFlow(LinkState.CONNECTING),
            handlers = mutableListOf(syntheticHandler),
        )
        val lifecycle = ContextCallbackLifecycle(state, context)

        lifecycle.publishConnectionState(BluetoothProfile.STATE_CONNECTED)
        val matched = lifecycle.matchingHandlers(setOf(syntheticService))

        assertEquals(LinkState.CONNECTED, context.connectionState.value)
        assertEquals(listOf(syntheticHandler), matched)
        assertEquals(BluetoothProfile.STATE_DISCONNECTED, state.connectionState.value)
        assertEquals(BluetoothProfile.STATE_DISCONNECTED, state.hrmConnectionState.value)
    }

    @Test
    fun `Life Fitness readiness requires both setup and descriptor completion in either order`() {
        fun opened(): Pair<BtEngineState, ConnectionContext> {
            val state = BtEngineState()
            val context = state.connectionRegistry.open(
                LegacyConnectionRole.FTMS,
                "AA:BB:CC:DD:EE:10",
                gatt = null,
                initialState = BluetoothProfile.STATE_CONNECTED,
            )
            context.lifeFitnessSetupState = LifeFitnessLinkSetupState.PENDING
            context.cccd.incrementPending()
            return state to context
        }

        opened().let { (state, context) ->
            assertTrue(state.connectionRegistry.completeCccdWrite(context))
            assertTrue("raw subscription plan completed", context.cccd.ready.value)
            assertFalse("setup still gates public readiness", state.cccdReady.value)

            context.lifeFitnessSetupState = LifeFitnessLinkSetupState.SUCCEEDED
            assertTrue(state.connectionRegistry.refreshCccdReady(context))
            assertTrue(state.cccdReady.value)
        }

        opened().let { (state, context) ->
            context.lifeFitnessSetupState = LifeFitnessLinkSetupState.SUCCEEDED
            assertFalse("descriptors are still pending", state.connectionRegistry.refreshCccdReady(context))
            assertFalse(state.cccdReady.value)

            assertTrue(state.connectionRegistry.completeCccdWrite(context))
            assertTrue(state.cccdReady.value)
        }

        opened().let { (state, context) ->
            context.lifeFitnessSetupState = LifeFitnessLinkSetupState.FAILED
            state.connectionRegistry.completeCccdWrite(context)
            assertFalse("failed setup cannot publish a ready link", state.cccdReady.value)
        }
    }
}
