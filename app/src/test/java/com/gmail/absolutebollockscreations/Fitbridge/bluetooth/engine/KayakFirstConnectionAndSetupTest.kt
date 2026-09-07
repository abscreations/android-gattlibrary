package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.bluetooth.BluetoothGattCharacteristic
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class KayakFirstConnectionAndSetupTest {
    private val notify = BluetoothGattCharacteristic.PROPERTY_NOTIFY
    private val writeNoResponse = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE

    @Test
    fun `exact same-parent FFE1 surface plans one notification subscription`() {
        val plan = planKayakFirstDiscovery(KAYAK_FIRST_CANDIDATE_ID, inventory())

        assertTrue(plan.activate)
        assertNull(plan.failure)
        assertEquals(
            CccdTarget(BluetoothIds.UUID_KAYAK_FIRST_SERVICE, BluetoothIds.UUID_KAYAK_FIRST_IO),
            plan.notificationTarget,
        )
    }

    @Test
    fun `candidate identity service endpoint properties and CCCD are independently required`() {
        val wrongParent = UUID.fromString("12345678-1234-5678-9abc-def012345678")
        val cases = listOf(
            planKayakFirstDiscovery(null, inventory()) to
                KayakFirstDiscoveryFailure.ADVERTISEMENT_CANDIDATE_MISSING,
            planKayakFirstDiscovery(
                KAYAK_FIRST_CANDIDATE_ID,
                KayakFirstGattInventory(emptyMap()),
            ) to KayakFirstDiscoveryFailure.MISSING_SERVICE,
            planKayakFirstDiscovery(KAYAK_FIRST_CANDIDATE_ID, inventory(includeIo = false)) to
                KayakFirstDiscoveryFailure.MISSING_IO,
            planKayakFirstDiscovery(
                KAYAK_FIRST_CANDIDATE_ID,
                inventory(ioProperties = writeNoResponse),
            ) to KayakFirstDiscoveryFailure.IO_NOT_NOTIFY,
            planKayakFirstDiscovery(
                KAYAK_FIRST_CANDIDATE_ID,
                inventory(ioProperties = notify),
            ) to KayakFirstDiscoveryFailure.IO_NOT_WRITE_WITHOUT_RESPONSE,
            planKayakFirstDiscovery(
                KAYAK_FIRST_CANDIDATE_ID,
                inventory(hasCccd = false),
            ) to KayakFirstDiscoveryFailure.IO_MISSING_CCCD,
            planKayakFirstDiscovery(
                KAYAK_FIRST_CANDIDATE_ID,
                KayakFirstGattInventory(
                    mapOf(
                        wrongParent to mapOf(
                            BluetoothIds.UUID_KAYAK_FIRST_IO to characteristic(),
                        ),
                    ),
                ),
            ) to KayakFirstDiscoveryFailure.MISSING_SERVICE,
        )

        cases.forEach { (plan, expected) ->
            assertFalse(plan.activate)
            assertEquals(expected, plan.failure)
            assertNull(plan.notificationTarget)
        }
    }

    @Test
    fun `additional properties and unrelated services do not weaken the exact match`() {
        val extras = mapOf(
            UUID.fromString("12345678-1234-5678-9abc-def012345678") to mapOf(
                UUID.fromString("87654321-4321-8765-cba9-876543210fed") to
                    KayakFirstCharacteristicInventory(
                        BluetoothGattCharacteristic.PROPERTY_READ,
                        false,
                    ),
            ),
        )
        val plan = planKayakFirstDiscovery(
            KAYAK_FIRST_CANDIDATE_ID,
            inventory(
                ioProperties = notify or writeNoResponse or
                    BluetoothGattCharacteristic.PROPERTY_READ,
                extras = extras,
            ),
        )
        assertTrue(plan.activate)
    }

    @Test
    fun `happy setup timeline captures display snapshot and requires exact display success`() {
        val id = connection(1)
        val display = KayakFirstDisplayConfiguration.DEFAULT.copy(
            slot5 = KayakFirstDisplayMetric.INSTANTANEOUS_POWER,
        )
        val setup = KayakFirstSetupCoordinator(id, display)
        assertTrue(setup.beginSubscription(0))

        var send = setup.onSubscribed(id, 1_800_000_000L, -240, 1).singleSend()
        assertEquals(KayakFirstSetupState.RESET_A, setup.state)
        assertEquals(KayakFirstCommand.Reset, send.command)
        assertTrue(setup.onWriteCompleted(id, send.kind, send.attempt, true, 10).isEmpty())
        assertTrue(setup.onTextResponse(id, response("2;1"), 11).isEmpty())
        assertEquals(KayakFirstSetupState.RESET_A, setup.state)
        setup.onBinaryResetAcknowledgement(id, 20)

        send = setup.tick(1_020).singleSend()
        assertEquals(KayakFirstSetupState.RESET_B, setup.state)
        setup.onWriteCompleted(id, send.kind, send.attempt, true, 1_030)
        setup.onTextResponse(id, response("1;1;1800000000"), 1_040)

        send = setup.tick(6_040).singleSend()
        assertEquals(KayakFirstSetupState.HANDSHAKE, setup.state)
        assertEquals(
            KayakFirstCommand.LegacyHandshake(1_800_000_000L, -240),
            send.command,
        )
        assertTrue(send.chunks.all { it.size <= 20 })
        setup.onWriteCompleted(id, send.kind, send.attempt, true, 6_050)
        setup.onTextResponse(id, response("2;1"), 6_060)

        send = setup.tick(11_060).singleSend()
        assertEquals(KayakFirstSetupState.DISPLAY, setup.state)
        assertEquals(KayakFirstCommand.DisplayConfiguration(display), send.command)
        setup.onWriteCompleted(id, send.kind, send.attempt, true, 11_070)
        setup.onTextResponse(id, response("9;1"), 11_071)
        assertEquals(KayakFirstSetupState.DISPLAY, setup.state)
        setup.onTextResponse(id, response("5;1"), 11_080)

        assertEquals(listOf(KayakFirstSetupAction.Ready), setup.tick(16_080))
        assertEquals(KayakFirstSetupState.READY, setup.state)
        assertEquals(display, setup.displayConfiguration)
        setup.cancel(disconnected = true)
        assertEquals(KayakFirstSetupState.CANCELLED, setup.state)
    }

    @Test
    fun `selected sport is retained by the setup generation`() {
        val id = connection(31)
        val setup = KayakFirstSetupCoordinator(
            id,
            KayakFirstDisplayConfiguration.DEFAULT,
            KayakFirstSportMode.KAYAKING,
        )

        assertTrue(setup.beginSubscription(0))
        setup.onSubscribed(id, 1_788_737_313L, -240, 1)
        assertEquals(KayakFirstSportMode.KAYAKING, setup.sportMode)
    }

    @Test
    fun `bull generation sends command 21 and only a command 21 line completes it`() {
        val id = connection(32)
        val setup = KayakFirstSetupCoordinator(
            id,
            KayakFirstDisplayConfiguration.DEFAULT,
            handshakeVariant = KayakFirstHandshakeVariant.BULL,
            athleteWeightKg = 93,
            boatWeightKg = 12,
        )
        var now = reachHandshake(setup, id)

        val transaction = requireNotNull(setup.activeTransaction)
        val bull = transaction.command as KayakFirstCommand.BullHandshake
        assertEquals(93, bull.athleteWeightKg)
        assertEquals(12, bull.boatWeightKg)
        // reachHandshake subscribes with epoch 1 / offset 0.
        assertEquals(
            "21;1;0;93;12\r\n",
            KayakFirstCommandCodec.encode(bull).toString(Charsets.UTF_8),
        )

        setup.onWriteCompleted(id, transaction.kind, 1, true, now)
        now += 10
        // A legacy command-2 acknowledgement cannot complete a command-21 transaction.
        setup.onTextResponse(
            id,
            KayakFirstTextResponse(KayakFirstResponseKind.HANDSHAKE, "2", listOf("1")),
            now,
        )
        assertEquals(1, requireNotNull(setup.activeTransaction).attempt)

        setup.onTextResponse(
            id,
            KayakFirstTextResponse(KayakFirstResponseKind.HANDSHAKE, "21", listOf("1")),
            now,
        )
        assertEquals(null, setup.activeTransaction)
        assertTrue(
            setup.completedTransactions.any {
                it.kind == KayakFirstTransactionKind.HANDSHAKE &&
                    it.terminalResult == KayakFirstTransactionResult.SUCCESS
            },
        )
    }

    @Test
    fun `handshake alone may exhaust and continue unconfirmed after bounded retries`() {
        val id = connection(2)
        val setup = KayakFirstSetupCoordinator(
            id,
            KayakFirstDisplayConfiguration.DEFAULT,
            KayakFirstSportMode.KAYAKING,
        )
        var now = reachHandshake(setup, id)

        for (attempt in 1..3) {
            val transaction = requireNotNull(setup.activeTransaction)
            assertEquals(attempt, transaction.attempt)
            assertEquals(
                KayakFirstSportMode.KAYAKING,
                (transaction.command as KayakFirstCommand.LegacyHandshake).sport,
            )
            setup.onWriteCompleted(id, transaction.kind, attempt, true, now)
            now += KayakFirstSetupCoordinator.ACK_DEADLINE_MS
            assertTrue(setup.tick(now).isEmpty())
            if (attempt < 3) {
                now += KayakFirstSetupCoordinator.RETRY_DELAY_MS
                val retry = setup.tick(now).singleSend()
                assertEquals(attempt + 1, retry.attempt)
            }
        }

        assertEquals(KayakFirstSetupState.HANDSHAKE_UNCONFIRMED, setup.state)
        assertEquals(3, setup.completedTransactions.count {
            it.kind == KayakFirstTransactionKind.HANDSHAKE &&
                it.terminalResult == KayakFirstTransactionResult.TIMEOUT
        })
        val display = setup.tick(now + KayakFirstSetupCoordinator.HANDSHAKE_SETTLE_MS).singleSend()
        assertEquals(KayakFirstTransactionKind.DISPLAY, display.kind)
    }

    @Test
    fun `reset and display exhaustion fail while write failures consume the same retry budget`() {
        val id = connection(3)
        val setup = KayakFirstSetupCoordinator(id, KayakFirstDisplayConfiguration.DEFAULT)
        setup.beginSubscription(0)
        var send = setup.onSubscribed(id, 1, 0, 1).singleSend()
        var now = 1L
        for (attempt in 1..3) {
            assertEquals(attempt, send.attempt)
            val actions = setup.onWriteCompleted(id, send.kind, send.attempt, false, now)
            if (attempt < 3) {
                assertTrue(actions.isEmpty())
                now += KayakFirstSetupCoordinator.RETRY_DELAY_MS
                send = setup.tick(now).singleSend()
            } else {
                assertEquals(
                    listOf(KayakFirstSetupAction.Failed(KayakFirstSetupFailure.RESET_A_EXHAUSTED)),
                    actions,
                )
            }
        }
        assertEquals(KayakFirstSetupState.FAILED, setup.state)
    }

    @Test
    fun `matching malformed response terminates only its current attempt`() {
        val id = connection(30)
        val setup = KayakFirstSetupCoordinator(id, KayakFirstDisplayConfiguration.DEFAULT)
        setup.beginSubscription(0)
        val reset = setup.onSubscribed(id, 1, 0, 1).singleSend()
        setup.onWriteCompleted(id, reset.kind, reset.attempt, true, 1)

        assertTrue(setup.onTextResponse(id, response("1"), 2).isEmpty())
        assertEquals(KayakFirstTransactionResult.MALFORMED, setup.completedTransactions.single().terminalResult)
        val retry = setup.tick(2 + KayakFirstSetupCoordinator.RETRY_DELAY_MS).singleSend()
        assertEquals(2, retry.attempt)
        assertEquals(KayakFirstSetupState.RESET_A, setup.state)
    }

    @Test
    fun `negative display acknowledgement is rejected and retried`() {
        val id = connection(31)
        val setup = coordinatorAt(KayakFirstSetupState.DISPLAY, id)
        val display = requireNotNull(setup.activeTransaction)
        setup.onWriteCompleted(id, display.kind, display.attempt, true, 20_000)

        assertTrue(setup.onTextResponse(id, response("5;0"), 20_001).isEmpty())
        assertEquals(KayakFirstTransactionResult.REJECTED, setup.completedTransactions.last().terminalResult)
        val retry = setup.tick(21_001).singleSend()
        assertEquals(KayakFirstTransactionKind.DISPLAY, retry.kind)
        assertEquals(2, retry.attempt)
    }

    @Test
    fun `deadlines begin on final write completion and stale generations cannot advance setup`() {
        val id = connection(4)
        val stale = connection(3)
        val setup = KayakFirstSetupCoordinator(id, KayakFirstDisplayConfiguration.DEFAULT)
        setup.beginSubscription(100)
        assertTrue(setup.onSubscribed(stale, 1, 0, 101).isEmpty())
        val send = setup.onSubscribed(id, 1, 0, 102).singleSend()
        assertTrue(setup.tick(Long.MAX_VALUE).isEmpty())
        assertEquals(KayakFirstSetupState.RESET_A, setup.state)
        assertTrue(setup.onWriteCompleted(stale, send.kind, 1, true, 200).isEmpty())
        assertTrue(setup.onBinaryResetAcknowledgement(id, 201).isEmpty())
        assertEquals(0, setup.completedTransactions.size)

        setup.onWriteCompleted(id, send.kind, 1, true, 300)
        assertTrue(setup.onBinaryResetAcknowledgement(stale, 301).isEmpty())
        assertEquals(KayakFirstSetupState.RESET_A, setup.state)
        setup.onBinaryResetAcknowledgement(id, 302)
        assertEquals(KayakFirstTransactionResult.SUCCESS, setup.completedTransactions.single().terminalResult)
    }

    @Test
    fun `subscription is bounded and every live setup state cancels permanently`() {
        val timeout = KayakFirstSetupCoordinator(connection(5), KayakFirstDisplayConfiguration.DEFAULT)
        timeout.beginSubscription(10)
        assertEquals(
            listOf(KayakFirstSetupAction.Failed(KayakFirstSetupFailure.SUBSCRIPTION_FAILED)),
            timeout.tick(10 + KayakFirstSetupCoordinator.SUBSCRIPTION_DEADLINE_MS),
        )

        val states = listOf(
            KayakFirstSetupState.SUBSCRIBING,
            KayakFirstSetupState.RESET_A,
            KayakFirstSetupState.RESET_B,
            KayakFirstSetupState.HANDSHAKE,
            KayakFirstSetupState.HANDSHAKE_UNCONFIRMED,
            KayakFirstSetupState.DISPLAY,
        )
        states.forEachIndexed { index, target ->
            val id = connection(10L + index)
            val setup = coordinatorAt(target, id)
            setup.cancel(disconnected = true)
            assertEquals(KayakFirstSetupState.CANCELLED, setup.state)
            assertTrue(setup.tick(Long.MAX_VALUE).isEmpty())
            assertTrue(setup.onTextResponse(id, response("5;1"), 0).isEmpty())
        }
    }

    @Test
    fun `handler registration is per-link and routing owns only FFE1`() {
        assertTrue(ProtocolKey.KAYAK_FIRST in MACHINE_PROTOCOLS)
        val factory = HandlerFactory.standard(enablePhase05HardwareTest = false)
        val first = factory.create(ProtocolKey.KAYAK_FIRST)
        val second = factory.create(ProtocolKey.KAYAK_FIRST)
        assertTrue(first is KayakFirstProtocolHandler)
        assertNotSame(first, second)

        val route = resolveProtocolHandlerRoute(
            listOf(first),
            BluetoothIds.UUID_KAYAK_FIRST_IO,
            BluetoothIds.UUID_KAYAK_FIRST_SERVICE,
        )
        assertEquals(HandlerOwnership.EXPLICIT_CHARACTERISTIC, route.ownership)
        assertSame(first, route.owners.single())
        assertTrue(
            resolveProtocolHandlerRoute(
                listOf(first),
                BluetoothIds.UUID_FLYWHEEL_TX,
                BluetoothIds.UUID_FLYWHEEL_SERVICE,
            ).owners.isEmpty(),
        )
    }

    @Test
    fun `factory captures an immutable display configuration for each new link`() {
        var next = KayakFirstDisplayConfiguration.DEFAULT
        val factory = HandlerFactory.standard(false) { next }
        val first = factory.create(ProtocolKey.KAYAK_FIRST) as KayakFirstProtocolHandler
        val firstId = connection(40)
        first.recordStructuralMatch(firstId)

        next = next.copy(slot1 = KayakFirstDisplayMetric.INSTANTANEOUS_POWER)
        val second = factory.create(ProtocolKey.KAYAK_FIRST) as KayakFirstProtocolHandler
        val secondId = connection(41)
        second.recordStructuralMatch(secondId)

        assertEquals(
            KayakFirstDisplayMetric.ELAPSED_TIME,
            first.setupCoordinator()?.displayConfiguration?.slot1,
        )
        assertEquals(
            KayakFirstDisplayMetric.INSTANTANEOUS_POWER,
            second.setupCoordinator()?.displayConfiguration?.slot1,
        )
    }

    @Test
    fun `same-link handlers retain explicit endpoint ownership without fan-out`() {
        val kayak = KayakFirstProtocolHandler()
        val flywheel = FlywheelProtocolHandler()
        val handlers = listOf(kayak, flywheel)

        assertEquals(
            listOf(kayak),
            resolveProtocolHandlerRoute(
                handlers,
                BluetoothIds.UUID_KAYAK_FIRST_IO,
                BluetoothIds.UUID_KAYAK_FIRST_SERVICE,
            ).owners,
        )
        assertEquals(
            listOf(flywheel),
            resolveProtocolHandlerRoute(
                handlers,
                BluetoothIds.UUID_FLYWHEEL_TX,
                BluetoothIds.UUID_FLYWHEEL_SERVICE,
            ).owners,
        )
    }

    @Test
    fun `structural match stays setup-gated and ranks below FTMS`() {
        val id = connection(20)
        val handler = KayakFirstProtocolHandler().also { it.recordStructuralMatch(id) }
        val context = ConnectionContext(
            connectionId = id,
            address = id.address,
            machineConnection = true,
            gatt = null,
            connectionState = MutableStateFlow(LinkState.CONNECTED),
            discoveredServiceUuids = linkedSetOf(
                BluetoothIds.UUID_FTMS_SERVICE,
                BluetoothIds.UUID_KAYAK_FIRST_SERVICE,
            ),
            handlers = mutableListOf(handler),
        )
        val coordinator = AdapterLivenessCoordinator()
        coordinator.onSubscriptionsComplete(context, nowMs = 0)

        assertTrue(AdapterLivenessCoordinator.RANK_FTMS > AdapterLivenessCoordinator.RANK_KAYAK_FIRST)
        assertEquals(AdapterLivenessCoordinator.FTMS_ADAPTER_ID, coordinator.selectedAdapterId(id))
        assertFalse(handler.isProtocolConfirmed())
    }

    private fun inventory(
        includeIo: Boolean = true,
        ioProperties: Int = notify or writeNoResponse,
        hasCccd: Boolean = true,
        extras: Map<UUID, Map<UUID, KayakFirstCharacteristicInventory>> = emptyMap(),
    ): KayakFirstGattInventory {
        val characteristics = if (includeIo) {
            mapOf(BluetoothIds.UUID_KAYAK_FIRST_IO to characteristic(ioProperties, hasCccd))
        } else {
            emptyMap()
        }
        return KayakFirstGattInventory(
            extras + (BluetoothIds.UUID_KAYAK_FIRST_SERVICE to characteristics),
        )
    }

    private fun characteristic(
        properties: Int = notify or writeNoResponse,
        hasCccd: Boolean = true,
    ) = KayakFirstCharacteristicInventory(properties, hasCccd)

    private fun response(line: String): KayakFirstTextResponse =
        (KayakFirstResponseDecoder.decode(line) as KayakFirstLineDecodeResult.Decoded).response

    private fun List<KayakFirstSetupAction>.singleSend(): KayakFirstTransaction =
        (single() as KayakFirstSetupAction.Send).transaction

    private fun connection(generation: Long) = ConnectionId("AA:BB:CC:DD:EE:40", generation)

    private fun reachHandshake(setup: KayakFirstSetupCoordinator, id: ConnectionId): Long {
        setup.beginSubscription(0)
        var send = setup.onSubscribed(id, 1, 0, 1).singleSend()
        setup.onWriteCompleted(id, send.kind, send.attempt, true, 1)
        setup.onBinaryResetAcknowledgement(id, 2)
        send = setup.tick(1_002).singleSend()
        setup.onWriteCompleted(id, send.kind, send.attempt, true, 1_003)
        setup.onTextResponse(id, response("1;1"), 1_004)
        setup.tick(6_004).singleSend()
        return 6_005
    }

    private fun coordinatorAt(
        target: KayakFirstSetupState,
        id: ConnectionId,
    ): KayakFirstSetupCoordinator {
        val setup = KayakFirstSetupCoordinator(id, KayakFirstDisplayConfiguration.DEFAULT)
        setup.beginSubscription(0)
        if (target == KayakFirstSetupState.SUBSCRIBING) return setup

        var send = setup.onSubscribed(id, 1, 0, 1).singleSend()
        if (target == KayakFirstSetupState.RESET_A) return setup
        setup.onWriteCompleted(id, send.kind, send.attempt, true, 1)
        setup.onBinaryResetAcknowledgement(id, 2)
        send = setup.tick(1_002).singleSend()
        if (target == KayakFirstSetupState.RESET_B) return setup
        setup.onWriteCompleted(id, send.kind, send.attempt, true, 1_003)
        setup.onTextResponse(id, response("1;1"), 1_004)
        send = setup.tick(6_004).singleSend()
        if (target == KayakFirstSetupState.HANDSHAKE) return setup

        setup.onWriteCompleted(id, send.kind, send.attempt, true, 6_005)
        var now = 7_005L
        setup.tick(now)
        now += 1_000
        send = setup.tick(now).singleSend()
        setup.onWriteCompleted(id, send.kind, send.attempt, true, now + 1)
        now += 1_001
        setup.tick(now)
        now += 1_000
        send = setup.tick(now).singleSend()
        setup.onWriteCompleted(id, send.kind, send.attempt, true, now + 1)
        now += 1_001
        setup.tick(now)
        if (target == KayakFirstSetupState.HANDSHAKE_UNCONFIRMED) return setup
        send = setup.tick(now + KayakFirstSetupCoordinator.HANDSHAKE_SETTLE_MS).singleSend()
        assertEquals(KayakFirstTransactionKind.DISPLAY, send.kind)
        return setup
    }
}
