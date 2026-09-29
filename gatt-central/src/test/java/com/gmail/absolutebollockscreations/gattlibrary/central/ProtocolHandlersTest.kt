package com.gmail.absolutebollockscreations.gattlibrary.central

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
        val factory = HandlerFactory.standard(enablePhase05HardwareTest = false)

        val firstFtms = factory.create(ProtocolKey.FTMS)
        val secondFtms = factory.create(ProtocolKey.FTMS)
        val firstHrm = factory.create(ProtocolKey.HRM)
        val secondHrm = factory.create(ProtocolKey.HRM)
        val firstCsc = factory.create(ProtocolKey.CSC)
        val secondCsc = factory.create(ProtocolKey.CSC)

        assertTrue(firstFtms is FtmsProtocolHandler)
        assertTrue(firstHrm is HrmProtocolHandler)
        assertTrue(firstCsc is CscProtocolHandler)
        assertNotSame(firstFtms, secondFtms)
        assertNotSame(firstHrm, secondHrm)
        assertNotSame(firstCsc, secondCsc)
    }

    @Test
    fun `selected handler set registers only requested protocols`() {
        val handlers = HandlerSet.of(
            HandlerSet.Protocol.FTMS,
            HandlerSet.Protocol.HRM,
            HandlerSet.Protocol.CSC,
        )
        val factory = HandlerFactory.standard(handlerSet = handlers)

        assertTrue(factory.supports(ProtocolKey.FTMS))
        assertTrue(factory.supports(ProtocolKey.HRM))
        assertTrue(factory.supports(ProtocolKey.CSC))
        assertFalse(factory.supports(ProtocolKey.RSC))
        assertFalse(factory.supports(ProtocolKey.CONCEPT2))
        assertFalse(handlers.includesAdvertisementMachine())
        assertTrue(handlers.includesSensor(BtEngineState.BtServiceType.CSC))
        assertFalse(handlers.includesSensor(BtEngineState.BtServiceType.RSC))
        assertEquals(HandlerSet.ftmsAndHrm(), HandlerSet.of(
            HandlerSet.Protocol.FTMS,
            HandlerSet.Protocol.HRM,
        ))
    }

    @Test
    fun `all-handler preset preserves production and phase 05 companion registration`() {
        val all = HandlerSet.all()
        val factory = HandlerFactory.standard(
            enablePhase05HardwareTest = true,
            handlerSet = all,
        )

        assertTrue(MACHINE_PROTOCOLS.all(factory::supports))
        assertTrue(listOf(ProtocolKey.HRM, ProtocolKey.RSC, ProtocolKey.CSC, ProtocolKey.CPS)
            .all(factory::supports))
        assertTrue(all.includesAdvertisementMachine())
        assertTrue(factory.createForConnection(ProtocolKey.HRM).last() is TestEchoHandler)
    }

    @Test
    fun `phase 05 companion follows HRM in a restricted handler set`() {
        val factory = HandlerFactory.standard(
            enablePhase05HardwareTest = true,
            handlerSet = HandlerSet.ftmsAndHrm(),
        )

        assertTrue(factory.supports(ProtocolKey.TEST_ECHO))
        assertEquals(2, factory.createForConnection(ProtocolKey.HRM).size)
        assertFalse(factory.supports(ProtocolKey.RSC))
    }

    @Test
    fun `release-safe factory keeps HRM connection single handler`() {
        val handlers = HandlerFactory.standard(enablePhase05HardwareTest = false)
            .createForConnection(ProtocolKey.HRM)

        assertEquals(1, handlers.size)
        assertTrue(handlers.single() is HrmProtocolHandler)
    }

    @Test
    fun `phase 05 factory adds fresh inert RSC echo companion to HRM connection`() {
        val factory = HandlerFactory.standard(enablePhase05HardwareTest = true)

        val first = factory.createForConnection(ProtocolKey.HRM)
        val second = factory.createForConnection(ProtocolKey.HRM)

        assertEquals(2, first.size)
        assertTrue(first[0] is HrmProtocolHandler)
        assertTrue(first[1] is TestEchoHandler)
        assertEquals(setOf(BluetoothIds.UUID_RSC_SERVICE), first[1].primaryServices)
        assertEquals(setOf(BluetoothIds.UUID_RSC_MEASUREMENT), first[1].extraCharacteristics)
        assertNotSame(first[0], second[0])
        assertNotSame(first[1], second[1])
    }

    @Test
    fun `phase 05 companion is not added to FTMS connections`() {
        val handlers = HandlerFactory.standard(enablePhase05HardwareTest = true)
            .createForConnection(ProtocolKey.FTMS)

        assertEquals(1, handlers.size)
        assertTrue(handlers.single() is FtmsProtocolHandler)
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
    fun `FTMS handler explicitly owns every Life Fitness setup callback`() {
        val ftms = FtmsProtocolHandler()

        LifeFitnessSetup.CALLBACK_CHARACTERISTICS.forEach { characteristic ->
            val route = resolveProtocolHandlerRoute(
                listOf(ftms),
                characteristicUuid = characteristic,
                serviceUuid = UUID.randomUUID(),
            )
            assertEquals(characteristic.toString(), listOf(ftms), route.owners)
            assertEquals(HandlerOwnership.EXPLICIT_CHARACTERISTIC, route.ownership)
        }

        assertTrue(
            resolveProtocolHandlerRoute(
                listOf(ftms),
                characteristicUuid = BluetoothIds.UUID_LF_B90_WRITE_F4E8,
                serviceUuid = BluetoothIds.UUID_LF_CONTROL_SERVICE,
            ).owners.isEmpty(),
        )
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
