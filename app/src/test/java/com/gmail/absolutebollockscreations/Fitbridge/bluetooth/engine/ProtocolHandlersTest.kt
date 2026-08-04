package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ProtocolHandlersTest {

    private class StatefulHandler(
        override val primaryServices: Set<UUID>,
        override val extraCharacteristics: Set<UUID> = emptySet(),
        private val activates: Boolean = true,
    ) : BtEngineGattCore.ProtocolHandler {
        var mutableLinkValue: Int = 0
        fun discoveryAccepted(): Boolean = activates

        override fun onServicesDiscovered(
            engine: BtEngine,
            gatt: BluetoothGatt,
            services: List<BluetoothGattService>,
        ): Boolean = activates
    }

    @Test
    fun `standard factory returns fresh production handlers`() {
        val factory = HandlerFactory.standard()

        val firstFtms = factory.create(ProtocolKey.FTMS)
        val secondFtms = factory.create(ProtocolKey.FTMS)
        val firstHrm = factory.create(ProtocolKey.HRM)
        val secondHrm = factory.create(ProtocolKey.HRM)

        assertTrue(firstFtms is FtmsProtocolHandler)
        assertTrue(firstHrm is HrmProtocolHandler)
        assertNotSame(firstFtms, secondFtms)
        assertNotSame(firstHrm, secondHrm)
    }

    @Test
    fun `factory creates fresh mutable handler state for each context`() {
        val protocol = ProtocolKey("test.stateful")
        val service = UUID.randomUUID()
        val factory = HandlerFactory(mapOf(protocol to { StatefulHandler(setOf(service)) }))

        val first = factory.create(protocol) as StatefulHandler
        val second = factory.create(protocol) as StatefulHandler
        first.mutableLinkValue = 42

        assertNotSame(first, second)
        assertEquals(42, first.mutableLinkValue)
        assertEquals(0, second.mutableLinkValue)
    }

    @Test
    fun `connection factory can add a same-link companion handler for hardware validation`() {
        val primary = ProtocolKey("test.primary")
        val echo = ProtocolKey("test.echo")
        val primaryService = UUID.randomUUID()
        val echoService = UUID.randomUUID()
        val factory = HandlerFactory(
            builders = mapOf(
                primary to { StatefulHandler(setOf(primaryService)) },
                echo to { StatefulHandler(setOf(echoService)) },
            ),
            companionProtocols = mapOf(primary to listOf(echo)),
        )

        val firstContextHandlers = factory.createForConnection(primary)
        val secondContextHandlers = factory.createForConnection(primary)

        assertEquals(
            listOf(setOf(primaryService), setOf(echoService)),
            firstContextHandlers.map { it.primaryServices },
        )
        assertNotSame(firstContextHandlers[0], secondContextHandlers[0])
        assertNotSame(firstContextHandlers[1], secondContextHandlers[1])
    }

    @Test
    fun `every matching handler is offered discovery and several can activate`() {
        val firstService = UUID.randomUUID()
        val secondService = UUID.randomUUID()
        val absentService = UUID.randomUUID()
        val first = StatefulHandler(setOf(firstService))
        val second = StatefulHandler(setOf(secondService))
        val absent = StatefulHandler(setOf(absentService))
        val offered = mutableListOf<BtEngineGattCore.ProtocolHandler>()

        val active = activateMatchingProtocolHandlers(
            listOf(first, second, absent),
            setOf(firstService, secondService),
        ) { handler ->
            offered += handler
            true
        }

        assertEquals(listOf(first, second), offered)
        assertEquals(listOf(first, second), active)
    }

    @Test
    fun `matching handler activates only when discovery offer succeeds`() {
        val service = UUID.randomUUID()
        val accepted = StatefulHandler(setOf(service))
        val declined = StatefulHandler(setOf(service), activates = false)

        val active = activateMatchingProtocolHandlers(
            listOf(accepted, declined),
            setOf(service),
        ) { (it as StatefulHandler).discoveryAccepted() }

        assertEquals(listOf(accepted), active)
    }

    @Test
    fun `explicit characteristic ownership takes precedence over service ownership`() {
        val service = UUID.randomUUID()
        val characteristic = UUID.randomUUID()
        val serviceOwner = StatefulHandler(setOf(service))
        val explicitOwner = StatefulHandler(
            primaryServices = setOf(UUID.randomUUID()),
            extraCharacteristics = setOf(characteristic),
        )

        val route = resolveProtocolHandlerRoute(
            listOf(serviceOwner, explicitOwner),
            characteristic,
            service,
        )

        assertEquals(HandlerOwnership.EXPLICIT_CHARACTERISTIC, route.ownership)
        assertEquals(listOf(explicitOwner), route.owners)
    }

    @Test
    fun `service ownership routes only to the owning handler`() {
        val firstService = UUID.randomUUID()
        val secondService = UUID.randomUUID()
        val characteristic = UUID.randomUUID()
        val first = StatefulHandler(setOf(firstService))
        val second = StatefulHandler(setOf(secondService))

        val route = resolveProtocolHandlerRoute(
            listOf(first, second),
            characteristic,
            secondService,
        )

        assertEquals(HandlerOwnership.PRIMARY_SERVICE, route.ownership)
        assertEquals(listOf(second), route.owners)
    }

    @Test
    fun `unowned characteristic has no fallback recipients`() {
        val handler = StatefulHandler(setOf(UUID.randomUUID()))

        val route = resolveProtocolHandlerRoute(
            listOf(handler),
            characteristicUuid = UUID.randomUUID(),
            serviceUuid = UUID.randomUUID(),
        )

        assertTrue(route.owners.isEmpty())
        assertNull(route.ownership)
    }

    @Test
    fun `CCCD plans merge in order and deduplicate across handlers`() {
        val state = CccdState()
        val first = CccdTarget(UUID.randomUUID(), UUID.randomUUID())
        val shared = CccdTarget(UUID.randomUUID(), UUID.randomUUID())
        val second = CccdTarget(UUID.randomUUID(), UUID.randomUUID())

        assertEquals(listOf(first, shared), state.mergeEnablePlan(listOf(first, shared)))
        assertEquals(listOf(second), state.mergeEnablePlan(listOf(shared, second)))
        assertEquals(listOf(first, shared, second), state.enablePlanSnapshot())
        assertEquals(3, state.plannedEnableCount)

        state.resetOrchestration(clearPlan = false)
        assertEquals(listOf(first, shared, second), state.enablePlanSnapshot())

        state.incrementPending()
        state.markEnablePlanFailed()
        assertFalse(state.completePending())
        assertFalse(state.ready.value)

        state.reset()
        assertTrue(state.enablePlanSnapshot().isEmpty())
    }
}
