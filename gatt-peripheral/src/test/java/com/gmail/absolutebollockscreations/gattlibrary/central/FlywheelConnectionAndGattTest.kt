package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.peripheral.providerMeasurementCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.requiresSpeed
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.Concept2Uuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.FecBleUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.FlywheelUuids

class FlywheelConnectionAndGattTest {
    @Test
    fun `Flywheel handler is registered per machine link and routes only exact endpoints`() {
        assertTrue(ProtocolKey.FLYWHEEL in MACHINE_PROTOCOLS)
        val factory = HandlerFactory.standard(enablePhase05HardwareTest = false)
        val first = factory.create(ProtocolKey.FLYWHEEL)
        val second = factory.create(ProtocolKey.FLYWHEEL)
        assertTrue(first is FlywheelProtocolHandler)
        assertTrue(second is FlywheelProtocolHandler)
        assertNotSame(first, second)

        val route = resolveProtocolHandlerRoute(
            listOf(first),
            FlywheelUuids.UUID_FLYWHEEL_TX,
            FlywheelUuids.UUID_FLYWHEEL_SERVICE,
        )
        assertEquals(HandlerOwnership.EXPLICIT_CHARACTERISTIC, route.ownership)
        assertSame(first, route.owners.single())
        assertTrue(
            resolveProtocolHandlerRoute(
                listOf(first),
                FecBleUuids.UUID_FEC_MEASUREMENT,
                FecBleUuids.UUID_FEC_SERVICE,
            ).owners.isEmpty(),
        )
    }

    @Test
    fun `structurally confirmed Flywheel remains setup-gated until TX subscription succeeds`() {
        val handler = FlywheelProtocolHandler().also { it.recordStructuralMatch() }
        val context = ConnectionContext(
            connectionId = connection(9),
            address = "AA:BB:CC:DD:EE:12",
            machineConnection = true,
            gatt = null,
            connectionState = MutableStateFlow(LinkState.CONNECTED),
            discoveredServiceUuids = linkedSetOf(FlywheelUuids.UUID_FLYWHEEL_SERVICE),
            handlers = mutableListOf(handler),
        )
        val coordinator = AdapterLivenessCoordinator(livenessTimeoutMs = 1_000)

        coordinator.onSubscriptionsComplete(context, nowMs = 0)
        assertEquals(FlywheelSetupState.IDLE, handler.setupState())
        assertEquals(AdapterPolicyState.SETUP_REQUIRED, coordinator.stateOf(context.connectionId))
        assertEquals(
            AdapterLivenessCoordinator.FLYWHEEL_ADAPTER_ID,
            coordinator.selectedAdapterId(context.connectionId),
        )
        assertTrue(coordinator.setupFailed(context, "Flywheel TX subscription timed out"))
        assertEquals(AdapterPolicyState.SETUP_FAILED, coordinator.stateOf(context.connectionId))
    }

    @Test
    fun `one hertz Flywheel telemetry keeps selection live with complete measurement capabilities`() {
        val handler = FlywheelProtocolHandler().also { it.recordStructuralMatch() }
        val context = ConnectionContext(
            connectionId = connection(10),
            address = "AA:BB:CC:DD:EE:13",
            machineConnection = true,
            gatt = null,
            connectionState = MutableStateFlow(LinkState.CONNECTED),
            discoveredServiceUuids = linkedSetOf(FlywheelUuids.UUID_FLYWHEEL_SERVICE),
            handlers = mutableListOf(handler),
        )
        val coordinator = AdapterLivenessCoordinator()
        context.cccd.setReady(true)
        coordinator.onSubscriptionsComplete(context, nowMs = 0L)
        assertTrue(coordinator.setupSucceeded(context, nowMs = 100L))
        coordinator.onSample(
            MetricSample(
                connectionId = context.connectionId,
                sourceId = MetricSourceId(
                    connectionId = context.connectionId,
                    protocol = MetricProtocol.VENDOR,
                    characteristicUuid = FlywheelUuids.UUID_FLYWHEEL_TX,
                ),
                protocol = MetricProtocol.VENDOR,
                sourceClass = MetricSourceClass.VENDOR_NATIVE,
                metric = MetricKind.POWER,
                arrivalMs = 1_100L,
                value = 177.0,
            ),
        )
        coordinator.tick(AdapterLivenessCoordinator.DEFAULT_LIVENESS_TIMEOUT_MS + 10_000L)

        assertEquals(AdapterPolicyState.READY, coordinator.stateOf(context.connectionId))
        assertEquals(
            AdapterLivenessCoordinator.FLYWHEEL_ADAPTER_ID,
            coordinator.selectedAdapterId(context.connectionId),
        )
        val capabilities = providerMeasurementCapabilities(
            FtmsMachineProfile.INDOOR_BIKE,
            context.discoveredServiceUuids,
        )
        assertTrue(
            "indoor bike supplies mandatory instantaneous speed",
            requiresSpeed(FtmsMachineProfile.INDOOR_BIKE),
        )
        assertTrue(capabilities.averageSpeed)
        assertTrue(capabilities.cadence)
        assertTrue(capabilities.totalDistance)
        assertTrue(capabilities.power)
    }

    @Test
    fun `exact Flywheel surface outranks stale Concept2 services and becomes ready`() {
        assertTrue(AdapterLivenessCoordinator.RANK_FTMS > AdapterLivenessCoordinator.RANK_FLYWHEEL)
        assertTrue(
            AdapterLivenessCoordinator.RANK_FLYWHEEL > AdapterLivenessCoordinator.RANK_CONCEPT2,
        )
        val handler = FlywheelProtocolHandler().also { it.recordStructuralMatch() }
        val context = ConnectionContext(
            connectionId = connection(11),
            address = "AA:BB:CC:DD:EE:14",
            machineConnection = true,
            gatt = null,
            connectionState = MutableStateFlow(LinkState.CONNECTED),
            discoveredServiceUuids = linkedSetOf(
                FlywheelUuids.UUID_FLYWHEEL_SERVICE,
                Concept2Uuids.UUID_C2_ERG_SERVICE,
            ),
            handlers = mutableListOf(handler),
        )
        val coordinator = AdapterLivenessCoordinator()

        coordinator.onSubscriptionsComplete(context, nowMs = 0L)
        assertEquals(
            AdapterLivenessCoordinator.FLYWHEEL_ADAPTER_ID,
            coordinator.selectedAdapterId(context.connectionId),
        )
        assertEquals(AdapterPolicyState.SETUP_REQUIRED, coordinator.stateOf(context.connectionId))
        context.cccd.setReady(true)
        assertTrue(coordinator.setupSucceeded(context, nowMs = 100L))
        coordinator.onSample(
            MetricSample(
                connectionId = context.connectionId,
                sourceId = MetricSourceId(
                    connectionId = context.connectionId,
                    protocol = MetricProtocol.VENDOR,
                    characteristicUuid = FlywheelUuids.UUID_FLYWHEEL_TX,
                ),
                protocol = MetricProtocol.VENDOR,
                sourceClass = MetricSourceClass.VENDOR_NATIVE,
                metric = MetricKind.POWER,
                arrivalMs = 1_100L,
                value = 177.0,
            ),
        )
        coordinator.tick(AdapterLivenessCoordinator.DEFAULT_LIVENESS_TIMEOUT_MS + 10_000L)

        assertEquals(AdapterPolicyState.READY, coordinator.stateOf(context.connectionId))
        assertEquals(
            AdapterLivenessCoordinator.FLYWHEEL_ADAPTER_ID,
            coordinator.selectedAdapterId(context.connectionId),
        )
    }

    private fun connection(generation: Long) =
        ConnectionId("AA:BB:CC:DD:EE:12", generation)
}
