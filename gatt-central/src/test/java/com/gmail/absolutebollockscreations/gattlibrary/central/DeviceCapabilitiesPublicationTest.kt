package com.gmail.absolutebollockscreations.gattlibrary.central

import android.bluetooth.BluetoothProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Unit 4b step 2b: the published `deviceCapabilities` must equal a fresh computation after every
 * event that can change an input.
 *
 * The flow is not a plain field: it depends on the merged snapshot, the machine link, the adapter
 * selected for it and, for the Wahoo KICKR, whether control was granted. Only the snapshot is
 * observable, so each other input has an explicit republish point. This test is the guard against
 * a missed one: it asserts equality with the fresh answer after each event rather than asserting
 * what the answer should be, and separately pins a few concrete values.
 *
 * Not covered: Flywheel, Kayak First and RideOn selection (their handlers need structural
 * matches that are costly to build here; they share the listener code exercised for the Wahoo),
 * and the engine's own wiring of `onControlStateChanged`, which the test reproduces by hand.
 */
class DeviceCapabilitiesPublicationTest {

    private val state = BtEngineState()

    private fun fresh(): DeviceCapabilities =
        state.providerMachineCapabilities().toDeviceCapabilities(state.supportsPaceMeasurement)

    private fun assertPublishedIsFresh(event: String) =
        assertEquals("after: $event", fresh(), state.deviceCapabilities.value)

    private fun openMachine(services: Set<UUID> = emptySet()): ConnectionContext {
        val ctx = state.connectionRegistry.open(
            LegacyConnectionRole.FTMS, "AA:BB:CC:DD:EE:10", null, BluetoothProfile.STATE_CONNECTED,
        )
        ctx.discoveredServiceUuids.addAll(services)
        return ctx
    }

    private fun u16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    @Test
    fun `nothing is published before any input`() {
        assertEquals(DeviceCapabilities.NONE, state.deviceCapabilities.value)
        assertPublishedIsFresh("construction")
    }

    @Test
    fun `a snapshot with no machine link publishes nothing`() {
        BtEngineCapabilities.applyFitnessMachineFeatures(state, byteArrayOf(0, 0, 0, 0, 0x0F, 0, 0, 0))
        assertPublishedIsFresh("features without a machine")
        assertEquals(DeviceCapabilities.NONE, state.deviceCapabilities.value)
    }

    @Test
    fun `every snapshot event on a connected FTMS machine republishes`() {
        val ctx = openMachine()
        assertPublishedIsFresh("machine opened")

        BtEngineCapabilities.applyFitnessMachineFeatures(
            state, byteArrayOf(0x20, 0, 0, 0, 0x0F, 0x20, 0, 0), // pace; four targets; bit 13
        )
        assertPublishedIsFresh("features")
        val afterFeatures = state.deviceCapabilities.value
        assertTrue(afterFeatures.featuresRead)
        assertTrue(afterFeatures.supportsSpeedTarget && afterFeatures.supportsInclineTarget)
        assertTrue(afterFeatures.supportsResistanceTarget && afterFeatures.supportsPowerTarget)
        assertTrue(afterFeatures.supportsIndoorBikeSimulation && afterFeatures.supportsPaceMeasurement)
        assertNull(afterFeatures.speedRangeKph)

        BtEngineCapabilities.applySupportedSpeedRange(state, u16(100) + u16(2_000) + u16(10))
        assertPublishedIsFresh("speed range")
        BtEngineCapabilities.applySupportedInclinationRange(state, u16(0xFFE2) + u16(150) + u16(5))
        assertPublishedIsFresh("incline range")
        BtEngineCapabilities.applySupportedResistanceRange(state, u16(10) + u16(1_000) + u16(10))
        assertPublishedIsFresh("resistance range")
        BtEngineCapabilities.applySupportedPowerRange(state, u16(0) + u16(2_000) + u16(1))
        assertPublishedIsFresh("power range")

        val caps = state.deviceCapabilities.value
        assertEquals(ControlRange(1.0f, 20.0f, 0.1f), caps.speedRangeKph)
        assertEquals(ControlRange(-3.0f, 15.0f, 0.5f), caps.inclineRangePercent)
        assertEquals(ControlRange(1.0f, 100.0f, 1.0f), caps.resistanceRange)
        assertEquals(ControlRange(0f, 2_000f, 1f), caps.powerRangeWatts)

        state.resetFtmsCapabilities()
        assertPublishedIsFresh("reset")
        assertEquals(DeviceCapabilities.NONE, state.deviceCapabilities.value)

        state.connectionRegistry.clear(ctx)
        assertPublishedIsFresh("machine cleared")
    }

    @Test
    fun `clearing the machine link empties the published capabilities`() {
        val ctx = openMachine()
        BtEngineCapabilities.applyFitnessMachineFeatures(state, byteArrayOf(0, 0, 0, 0, 0x08, 0, 0, 0))
        assertTrue(state.deviceCapabilities.value.supportsPowerTarget)

        state.connectionRegistry.clear(ctx)
        assertPublishedIsFresh("link cleared")
        assertEquals(DeviceCapabilities.NONE, state.deviceCapabilities.value)

        openMachine()
        assertPublishedIsFresh("link reopened")
        assertTrue("the merged snapshot survives a link until reset", state.deviceCapabilities.value.supportsPowerTarget)
    }

    @Test
    fun `a Wahoo KICKR reports nothing until control is granted and again after it closes`() = runBlocking {
        val ctx = openMachine(setOf(WahooKickrUuids.CYCLING_POWER_SERVICE))
        val handler = WahooKickrProtocolHandler().apply {
            recordStructuralMatch(
                requireNotNull(
                    matchWahooKickrGatt(
                        WahooKickrGattInventory(
                            mapOf(
                                WahooKickrUuids.CYCLING_POWER_SERVICE to mapOf(
                                    WahooKickrUuids.CONTROL to (
                                        android.bluetooth.BluetoothGattCharacteristic.PROPERTY_WRITE or
                                            android.bluetooth.BluetoothGattCharacteristic.PROPERTY_INDICATE
                                        ),
                                ),
                                WahooKickrUuids.MARKER_EE0B to emptyMap(),
                            ),
                        ),
                    ),
                ),
            )
        }
        state.connectionRegistry.replaceHandlers(ctx, listOf(handler))
        assertPublishedIsFresh("handlers replaced")

        state.adapterLiveness.onSubscriptionsComplete(ctx, nowMs = 0L)
        assertEquals(
            AdapterLivenessCoordinator.WAHOO_KICKR_ADAPTER_ID,
            state.adapterLiveness.selectedAdapterId(ctx.connectionId),
        )
        assertPublishedIsFresh("adapter selected")
        assertEquals("not unlocked yet", DeviceCapabilities.NONE, state.deviceCapabilities.value)

        val transport = object : GattTransport {
            override val connectionId = ctx.connectionId
            override fun enableNotifications(uuid: UUID) = true
            override suspend fun write(
                uuid: UUID, payload: ByteArray, mode: GattWriteMode, timeoutMs: Long, operationName: String,
            ): Boolean {
                handler.handleControlIndication(
                    ctx.connectionId, WahooKickrUuids.CONTROL,
                    byteArrayOf(1, (payload[0].toInt() and 0xFF).toByte(), 1, 0),
                )
                return true
            }
        }
        val session = WahooKickrControlSession(
            transport,
            WahooKickrSessionTiming(
                unlockOverallTimeoutMs = 500, unlockAttemptIntervalMs = 10, attWriteTimeoutMs = 50,
                indicationTimeoutMs = 50, ergMinIntervalMs = 20,
            ),
            onControlStateChanged = { state.refreshDeviceCapabilities() },
        )
        handler.bindControlSession(session)
        assertTrue(handler.handleCccdReady(ctx.connectionId))
        repeat(100) { if (!session.controlGranted) delay(5) }
        assertTrue(session.controlGranted)
        assertPublishedIsFresh("control granted")
        val granted = state.deviceCapabilities.value
        assertTrue(granted.featuresRead)
        assertTrue(granted.supportsPowerTarget && granted.supportsResistanceTarget)
        assertTrue(granted.supportsIndoorBikeSimulation && granted.supportsWheelCircumference)
        assertFalse(granted.supportsSpeedTarget)
        assertEquals(ControlRange(0f, 2_000f, 1f), granted.powerRangeWatts)
        assertEquals(ControlRange(0f, 100f, 0.1f), granted.resistanceRange)

        session.close()
        assertPublishedIsFresh("session closed")
        assertEquals(DeviceCapabilities.NONE, state.deviceCapabilities.value)
    }

    @Test
    fun `a stale computation can never overwrite a newer one`() {
        openMachine()
        BtEngineCapabilities.applyFitnessMachineFeatures(state, byteArrayOf(0, 0, 0, 0, 0x08, 0, 0, 0))
        val threads = List(8) { n ->
            Thread {
                repeat(200) {
                    if (n % 2 == 0) state.refreshDeviceCapabilities()
                    else BtEngineCapabilities.applySupportedPowerRange(state, u16(0) + u16(2_000) + u16(1))
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertPublishedIsFresh("concurrent refreshes")
    }
}
