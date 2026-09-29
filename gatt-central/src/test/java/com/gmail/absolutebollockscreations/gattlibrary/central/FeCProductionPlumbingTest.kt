package com.gmail.absolutebollockscreations.gattlibrary.central

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Plan §16.1's "obligation zero" for FE-C over BLE: the mandatory production-plumbing chain
 * from a discovered FEC1 service through to a subscribed, session-bound handler on a link
 * `AdapterLivenessCoordinator` actually supervises.
 *
 * This does not exercise `FeCOverBleProtocolHandler.onServicesDiscovered` directly - like
 * every other handler test in this suite, that would need a real or mocked `BluetoothGatt`/
 * `BluetoothGattService`, and this codebase does not carry a mocking framework for Android
 * BLE types. What it does check is every step the plan calls out that does not require one:
 * the handler is reachable from [MACHINE_PROTOCOLS] and [HandlerFactory], activation is
 * service-gated the same way every other machine handler's is, FEC2 routes to it explicitly,
 * and a discovered FEC1 service produces a ranked, liveness-supervised adapter candidate below
 * FTMS. Prior phases of this project shipped handlers that were correct but unreachable through
 * exactly this chain; this test exists so FE-C cannot join them silently.
 */
class FeCProductionPlumbingTest {

    private fun context(
        generation: Long = 1L,
        services: Set<UUID> = setOf(BluetoothIds.UUID_FEC_SERVICE),
    ): ConnectionContext = ConnectionContext(
        connectionId = ConnectionId(address = "AA:BB:CC:DD:EE:FE", linkGeneration = generation),
        address = "AA:BB:CC:DD:EE:FE",
        machineConnection = true,
        gatt = null,
        connectionState = MutableStateFlow(LinkState.CONNECTED),
        discoveredServiceUuids = linkedSetOf<UUID>().apply { addAll(services) },
    )

    // ── Reachable from the standard registration surfaces ────────────────────────

    @Test
    fun `FEC is offered on every machine connection`() {
        assertTrue(ProtocolKey.FEC in MACHINE_PROTOCOLS)
    }

    @Test
    fun `FEC is a machine protocol, not a secondary sensor`() {
        for (type in BtEngineState.BtServiceType.values()) {
            assertTrue(
                "$type must not route to the FE-C handler as a sensor",
                sensorProtocolFor(type) != ProtocolKey.FEC,
            )
        }
    }

    @Test
    fun `the factory builds a fresh FE-C handler each time`() {
        val factory = HandlerFactory.standard(enablePhase05HardwareTest = false)
        val first = factory.create(ProtocolKey.FEC)
        val second = factory.create(ProtocolKey.FEC)

        assertTrue(first is FeCOverBleProtocolHandler)
        assertTrue(second is FeCOverBleProtocolHandler)
        assertNotSame("per-link state must not be shared between connections", first, second)
    }

    // ── Service-gated activation, same discipline as every other machine handler ─

    @Test
    fun `the FE-C handler activates only when FEC1 is discovered`() {
        val fec = FeCOverBleProtocolHandler()
        val ftms = FtmsProtocolHandler()

        val activeWithFec = activateMatchingProtocolHandlers(
            listOf(ftms, fec),
            setOf(BluetoothIds.UUID_FTMS_SERVICE, BluetoothIds.UUID_FEC_SERVICE),
            offer = { true },
        )
        assertEquals("a device may serve both FTMS and FEC1", 2, activeWithFec.size)

        val activeWithoutFec = activateMatchingProtocolHandlers(
            listOf(ftms, fec),
            setOf(BluetoothIds.UUID_FTMS_SERVICE),
            offer = { true },
        )
        assertEquals(listOf(ftms), activeWithoutFec)
    }

    @Test
    fun `FEC2 routes to the FE-C handler explicitly and to nothing else`() {
        val fec = FeCOverBleProtocolHandler()
        val ftms = FtmsProtocolHandler()
        val active = listOf(ftms, fec)

        val route = resolveProtocolHandlerRoute(
            active,
            BluetoothIds.UUID_FEC_MEASUREMENT,
            BluetoothIds.UUID_FEC_SERVICE,
        )
        assertEquals(listOf(fec), route.owners)
        assertSame(fec, route.owners.single())
        assertEquals(HandlerOwnership.EXPLICIT_CHARACTERISTIC, route.ownership)

        // An FTMS characteristic on the same link still routes to FTMS.
        assertEquals(
            listOf(ftms),
            resolveProtocolHandlerRoute(
                active,
                BluetoothIds.UUID_ROWING_MACHINE_DATA,
                BluetoothIds.UUID_FTMS_SERVICE,
            ).owners,
        )
        assertNull(resolveProtocolHandlerRoute(active, UUID.randomUUID(), null).ownership)
    }

    // ── Liveness-supervised adapter candidate ─────────────────────────────────────

    @Test
    fun `a discovered FEC1 service produces a liveness-supervised FE-C candidate`() {
        val coordinator = AdapterLivenessCoordinator(livenessTimeoutMs = 1_000L)
        val ctx = context()

        coordinator.onSubscriptionsComplete(ctx, nowMs = 0L)
        assertEquals(AdapterPolicyState.AWAITING_DATA, coordinator.stateOf(ctx.connectionId))
        assertEquals(
            AdapterLivenessCoordinator.FEC_ADAPTER_ID,
            coordinator.selectedAdapterId(ctx.connectionId),
        )

        coordinator.tick(1_000L)
        assertEquals(
            "a silent FE-C link is demoted the same as any other machine adapter",
            AdapterPolicyState.CONNECTED_NO_DATA,
            coordinator.stateOf(ctx.connectionId),
        )
    }

    @Test
    fun `standard FTMS outranks FE-C on a device advertising both`() {
        // Plan §6.2: preference between two certain matches is expressed via rank, not weight.
        val coordinator = AdapterLivenessCoordinator(livenessTimeoutMs = 1_000L)
        val ctx = context(
            services = setOf(BluetoothIds.UUID_FTMS_SERVICE, BluetoothIds.UUID_FEC_SERVICE),
        )

        coordinator.onSubscriptionsComplete(ctx, nowMs = 0L)

        assertEquals(
            "FTMS is the first choice on a device that serves it",
            AdapterLivenessCoordinator.FTMS_ADAPTER_ID,
            coordinator.selectedAdapterId(ctx.connectionId),
        )
    }

    @Test
    fun `a silent FTMS advertisement falls back to FE-C instead of dying`() {
        val coordinator = AdapterLivenessCoordinator(livenessTimeoutMs = 1_000L)
        val ctx = context(
            services = setOf(BluetoothIds.UUID_FTMS_SERVICE, BluetoothIds.UUID_FEC_SERVICE),
        )

        coordinator.onSubscriptionsComplete(ctx, nowMs = 0L)
        coordinator.tick(1_000L)

        assertEquals(
            AdapterLivenessCoordinator.FEC_ADAPTER_ID,
            coordinator.selectedAdapterId(ctx.connectionId),
        )
    }

    // ── The handler never claims sample/metric surfaces it does not yet own ──────

    @Test
    fun `a fresh handler reports no control channel and no session until activation`() {
        val handler = FeCOverBleProtocolHandler()
        assertFalse(handler.controlChannelAvailable())
        assertNull(handler.currentSession())
    }
}
