package com.gmail.absolutebollockscreations.gattlibrary.central

import android.bluetooth.BluetoothGattCharacteristic
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.providerMeasurementCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.requiresSpeed
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class FlywheelConnectionAndGattTest {
    private val notify = BluetoothGattCharacteristic.PROPERTY_NOTIFY
    private val write = BluetoothGattCharacteristic.PROPERTY_WRITE
    private val writeNoResponse = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE

    @Test
    fun `exact inventory plans one TX subscription and no RX operation`() {
        for (rxProperties in listOf(write, writeNoResponse, write or writeNoResponse)) {
            val plan = planFlywheelDiscovery(FLYWHEEL_CANDIDATE_ID, inventory(rxProperties = rxProperties))
            assertTrue(plan.activate)
            assertNull(plan.failure)
            assertEquals(
                listOf(
                    FlywheelGattOperation(
                        FlywheelGattOperationKind.ENABLE_NOTIFICATIONS,
                        BluetoothIds.UUID_FLYWHEEL_TX,
                    ),
                ),
                plan.operations,
            )
            assertTrue(plan.operations.none { it.characteristicUuid == BluetoothIds.UUID_FLYWHEEL_RX })
        }
    }

    @Test
    fun `advertisement identity is required even for a complete generic Nordic UART surface`() {
        val complete = inventory()
        for (candidate in listOf(null, "generic.nordic.uart", "vendor.fec.ble")) {
            val plan = planFlywheelDiscovery(candidate, complete)
            assertFalse(plan.activate)
            assertEquals(FlywheelDiscoveryFailure.ADVERTISEMENT_IDENTITY_NOT_CONFIRMED, plan.failure)
            assertTrue(plan.operations.isEmpty())
        }
    }

    @Test
    fun `missing service and endpoint failures are typed and plan no operation`() {
        val wrongBase = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dccaaa")
        val cases = listOf(
            FlywheelGattInventory(emptyMap()) to FlywheelDiscoveryFailure.MISSING_SERVICE,
            FlywheelGattInventory(
                mapOf(
                    wrongBase to mapOf(
                        BluetoothIds.UUID_FLYWHEEL_TX to characteristic(notify, true),
                        BluetoothIds.UUID_FLYWHEEL_RX to characteristic(writeNoResponse, false),
                    ),
                ),
            ) to FlywheelDiscoveryFailure.MISSING_SERVICE,
            inventory(includeTx = false) to FlywheelDiscoveryFailure.MISSING_TX,
            inventory(includeRx = false) to FlywheelDiscoveryFailure.MISSING_RX,
        )
        for ((inventory, expected) in cases) {
            val plan = planFlywheelDiscovery(FLYWHEEL_CANDIDATE_ID, inventory)
            assertFalse(plan.activate)
            assertEquals(expected, plan.failure)
            assertTrue(plan.operations.isEmpty())
        }
    }

    @Test
    fun `wrong properties and missing CCCD reject activation`() {
        val cases = listOf(
            inventory(txProperties = BluetoothGattCharacteristic.PROPERTY_INDICATE) to
                FlywheelDiscoveryFailure.TX_NOT_NOTIFY,
            inventory(txHasCccd = false) to FlywheelDiscoveryFailure.TX_MISSING_CCCD,
            inventory(rxProperties = BluetoothGattCharacteristic.PROPERTY_READ) to
                FlywheelDiscoveryFailure.RX_NOT_WRITABLE,
        )
        for ((inventory, expected) in cases) {
            val plan = planFlywheelDiscovery(FLYWHEEL_CANDIDATE_ID, inventory)
            assertFalse(plan.activate)
            assertEquals(expected, plan.failure)
            assertTrue(plan.operations.isEmpty())
        }
    }

    @Test
    fun `unrelated services and properties do not weaken the exact inventory match`() {
        val extraService = UUID.fromString("12345678-1234-5678-9abc-def012345678")
        val extraCharacteristic = UUID.fromString("87654321-4321-8765-cba9-876543210fed")
        val inventory = inventory(
            txProperties = notify or BluetoothGattCharacteristic.PROPERTY_READ,
            rxProperties = writeNoResponse or BluetoothGattCharacteristic.PROPERTY_READ,
            extras = mapOf(extraService to mapOf(extraCharacteristic to characteristic(notify, true))),
        )

        assertTrue(planFlywheelDiscovery(FLYWHEEL_CANDIDATE_ID, inventory).activate)
    }

    @Test
    fun `setup readiness accepts only the owning generation before its deadline`() {
        val current = connection(generation = 8)
        val stale = connection(generation = 7)
        val gate = FlywheelSubscriptionGate(current, timeoutMs = 1_000)

        assertTrue(gate.begin(nowMs = 100))
        assertFalse(gate.markReady(stale, nowMs = 200))
        assertEquals(FlywheelSetupState.SUBSCRIBING, gate.state)
        assertTrue(gate.markReady(current, nowMs = 999))
        assertEquals(FlywheelSetupState.READY, gate.state)
        assertFalse(gate.markReady(current, nowMs = 1_000))
    }

    @Test
    fun `setup timeout is bounded and cannot become ready afterward`() {
        val id = connection(generation = 1)
        val gate = FlywheelSubscriptionGate(id, timeoutMs = 500)
        gate.begin(nowMs = 1_000)

        assertFalse(gate.tick(nowMs = 1_499))
        assertTrue(gate.tick(nowMs = 1_500))
        assertEquals(FlywheelSetupState.FAILED, gate.state)
        assertFalse(gate.markReady(id, nowMs = 1_501))
        assertFalse(gate.tick(nowMs = Long.MAX_VALUE))
    }

    @Test
    fun `teardown cancels setup and reconnect uses fresh generation state`() {
        val first = FlywheelSubscriptionGate(connection(1), timeoutMs = 1_000)
        first.begin(0)
        first.cancel()
        assertEquals(FlywheelSetupState.CANCELLED, first.state)
        assertFalse(first.markReady(connection(1), 1))

        val second = FlywheelSubscriptionGate(connection(2), timeoutMs = 1_000)
        assertNotSame(first, second)
        assertEquals(FlywheelSetupState.IDLE, second.state)
        assertTrue(second.begin(10))
        assertTrue(second.markReady(connection(2), 11))
        assertEquals(FlywheelSetupState.READY, second.state)
    }

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
            BluetoothIds.UUID_FLYWHEEL_TX,
            BluetoothIds.UUID_FLYWHEEL_SERVICE,
        )
        assertEquals(HandlerOwnership.EXPLICIT_CHARACTERISTIC, route.ownership)
        assertSame(first, route.owners.single())
        assertTrue(
            resolveProtocolHandlerRoute(
                listOf(first),
                BluetoothIds.UUID_FEC_MEASUREMENT,
                BluetoothIds.UUID_FEC_SERVICE,
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
            discoveredServiceUuids = linkedSetOf(BluetoothIds.UUID_FLYWHEEL_SERVICE),
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
            discoveredServiceUuids = linkedSetOf(BluetoothIds.UUID_FLYWHEEL_SERVICE),
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
                    characteristicUuid = BluetoothIds.UUID_FLYWHEEL_TX,
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
            BtServiceProfiles.FtmsMachineProfile.INDOOR_BIKE,
            context.discoveredServiceUuids,
        )
        assertTrue(
            "indoor bike supplies mandatory instantaneous speed",
            requiresSpeed(BtServiceProfiles.FtmsMachineProfile.INDOOR_BIKE),
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
                BluetoothIds.UUID_FLYWHEEL_SERVICE,
                BluetoothIds.UUID_C2_ERG_SERVICE,
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
                    characteristicUuid = BluetoothIds.UUID_FLYWHEEL_TX,
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

    private fun inventory(
        includeTx: Boolean = true,
        includeRx: Boolean = true,
        txProperties: Int = notify,
        txHasCccd: Boolean = true,
        rxProperties: Int = writeNoResponse,
        extras: Map<UUID, Map<UUID, FlywheelCharacteristicInventory>> = emptyMap(),
    ): FlywheelGattInventory {
        val characteristics = linkedMapOf<UUID, FlywheelCharacteristicInventory>()
        if (includeTx) characteristics[BluetoothIds.UUID_FLYWHEEL_TX] =
            characteristic(txProperties, txHasCccd)
        if (includeRx) characteristics[BluetoothIds.UUID_FLYWHEEL_RX] =
            characteristic(rxProperties, false)
        return FlywheelGattInventory(
            extras + (BluetoothIds.UUID_FLYWHEEL_SERVICE to characteristics),
        )
    }

    private fun characteristic(properties: Int, hasCccd: Boolean) =
        FlywheelCharacteristicInventory(properties, hasCccd)

    private fun connection(generation: Long) =
        ConnectionId("AA:BB:CC:DD:EE:12", generation)
}
