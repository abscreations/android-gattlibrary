package com.gmail.absolutebollockscreations.gattlibrary.central

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ConnectionContextTest {

    @Test
    fun `handle activation cannot attach a service to a replacement at the same address`() {
        val registry = ConnectionRegistry()
        val old = registry.openAdditional("AA:01", null, BluetoothProfile.STATE_CONNECTED)
        registry.clear(old)
        val replacement = registry.openAdditional("AA:01", null, BluetoothProfile.STATE_CONNECTED)
        replacement.discoveredServiceUuids += BluetoothIds.UUID_RSC_SERVICE
        var calls = 0
        replacement.handlerActivator = { calls++; true }
        assertFalse(registry.activateHandlerOn(old, TestHandler(BluetoothIds.UUID_RSC_SERVICE)))
        assertEquals(0, calls)
        assertTrue(registry.isCurrent(replacement))
    }

    @Test
    fun `handle activation adds multiple services once on its original physical link`() {
        val registry = ConnectionRegistry()
        val context = registry.openAdditional("AA:01", null, BluetoothProfile.STATE_CONNECTED)
        context.handlers += TestHandler(BluetoothIds.UUID_HRM_SERVICE)
        context.discoveredServiceUuids += BluetoothIds.UUID_RSC_SERVICE
        var calls = 0
        context.handlerActivator = { handler -> calls++; context.handlers += handler; true }
        assertTrue(registry.activateHandlerOn(context, TestHandler(BluetoothIds.UUID_RSC_SERVICE)))
        assertTrue(registry.activateHandlerOn(context, TestHandler(BluetoothIds.UUID_RSC_SERVICE)))
        assertEquals(1, calls)
        assertEquals(setOf(BtEngineState.BtServiceType.HeartRate, BtEngineState.BtServiceType.RSC),
            registry.connectedDevices.value.single().activeServices)
        assertTrue(registry.clear(context))
        assertTrue(registry.connectedDevices.value.isEmpty())
    }

    @Test
    fun `registry projects definitive machine family ahead of generic FTMS`() {
        val registry = ConnectionRegistry()
        val context = registry.open(
            role = LegacyConnectionRole.FTMS,
            address = "AA:BB:CC:DD:EE:10",
            gatt = null,
            initialState = BluetoothProfile.STATE_CONNECTED,
        )
        context.discoveredServiceUuids += setOf(
            BluetoothIds.UUID_FTMS_SERVICE,
            BluetoothIds.UUID_C2_ERG_SERVICE,
        )

        registry.setMachineDeviceType(context, BtEngineState.MachineDeviceType.SKI_ERG)

        val link = registry.connectedDevices.value.single()
        assertEquals(
            BtEngineState.MachineIdentityFamily.CONCEPT2_PM5,
            link.machineIdentityFamily,
        )
        assertEquals(BtEngineState.MachineDeviceType.SKI_ERG, link.machineDeviceType)
    }

    @Test
    fun `registry projects Life Fitness and Precor identities from link evidence`() {
        val lifeFitnessRegistry = ConnectionRegistry()
        val lifeFitness = lifeFitnessRegistry.open(
            role = LegacyConnectionRole.FTMS,
            address = "AA:BB:CC:DD:EE:11",
            gatt = null,
            initialState = BluetoothProfile.STATE_CONNECTED,
        )
        lifeFitness.discoveredServiceUuids += BluetoothIds.UUID_FTMS_SERVICE
        lifeFitness.lifeFitnessSetupState = LifeFitnessLinkSetupState.PENDING
        lifeFitnessRegistry.replaceHandlers(lifeFitness, emptyList())
        assertEquals(
            BtEngineState.MachineIdentityFamily.LIFE_FITNESS,
            lifeFitnessRegistry.connectedDevices.value.single().machineIdentityFamily,
        )

        val precorRegistry = ConnectionRegistry()
        val precor = precorRegistry.open(
            role = LegacyConnectionRole.FTMS,
            address = "AA:BB:CC:DD:EE:12",
            gatt = null,
            initialState = BluetoothProfile.STATE_CONNECTED,
        )
        precor.discoveredServiceUuids += BluetoothIds.UUID_PRECOR_SERVICE
        precorRegistry.replaceHandlers(precor, emptyList())
        val precorLink = precorRegistry.connectedDevices.value.single()
        assertEquals(
            BtEngineState.MachineIdentityFamily.PRECOR_SPINNER_CHRONO_POWER,
            precorLink.machineIdentityFamily,
        )
        assertEquals(BtEngineState.MachineDeviceType.BIKE, precorLink.machineDeviceType)
    }

    @Test
    fun `registry projects Flywheel as a bike from its exact service`() {
        val registry = ConnectionRegistry()
        val context = registry.open(
            role = LegacyConnectionRole.FTMS,
            address = "AA:BB:CC:DD:EE:13",
            gatt = null,
            initialState = BluetoothProfile.STATE_CONNECTED,
        )
        context.discoveredServiceUuids += BluetoothIds.UUID_FLYWHEEL_SERVICE
        registry.replaceHandlers(context, emptyList())

        val link = registry.connectedDevices.value.single()
        assertEquals(BtEngineState.MachineIdentityFamily.FLYWHEEL_SPORTS, link.machineIdentityFamily)
        assertEquals(BtEngineState.MachineDeviceType.BIKE, link.machineDeviceType)
    }

    @Test
    fun `registry publishes live link snapshots independently of discovery maps`() {
        val registry = ConnectionRegistry()
        val machine = registry.open(
            role = LegacyConnectionRole.FTMS,
            address = "aa:bb",
            gatt = null,
            initialState = BluetoothProfile.STATE_CONNECTING,
        )

        assertEquals(1, registry.connectedDevices.value.size)
        assertEquals(
            setOf(BtEngineState.BtServiceType.FTMS),
            registry.connectedDevices.value.single().activeServices,
        )

        registry.updateState(machine, BluetoothProfile.STATE_CONNECTED)
        val sensor = registry.open(
            role = LegacyConnectionRole.HRM,
            address = "AA:BB",
            gatt = null,
            initialState = BluetoothProfile.STATE_CONNECTED,
        )

        assertEquals(2, registry.connectedDevices.value.size)
        assertTrue(registry.connectedDevices.value.any { it.machineConnection })
        assertTrue(registry.connectedDevices.value.any {
            BtEngineState.BtServiceType.HeartRate in it.activeServices
        })

        assertTrue(registry.clear(sensor))
        assertEquals(1, registry.connectedDevices.value.size)
        assertTrue(registry.clear(machine))
        assertTrue(registry.connectedDevices.value.isEmpty())
    }

    @Test
    fun `additional service activates on the same link generation exactly once`() {
        val registry = ConnectionRegistry()
        val context = registry.open(
            role = LegacyConnectionRole.FTMS,
            address = "aa:bb",
            gatt = null,
            initialState = BluetoothProfile.STATE_CONNECTED,
        )
        context.handlers += TestHandler(BluetoothIds.UUID_FTMS_SERVICE)
        context.discoveredServiceUuids += BluetoothIds.UUID_HRM_SERVICE
        var activationCalls = 0
        context.handlerActivator = { handler ->
            activationCalls += 1
            context.handlers += handler
            true
        }
        val connectionKey = registry.connectedDevices.value.single().connectionKey

        assertTrue(
            registry.activateHandlerOn(
                "AA:BB",
                TestHandler(BluetoothIds.UUID_HRM_SERVICE),
            )
        )
        assertTrue(
            registry.activateHandlerOn(
                "aa:bb",
                TestHandler(BluetoothIds.UUID_HRM_SERVICE),
            )
        )

        assertEquals(1, activationCalls)
        assertEquals(1, registry.connectedDevices.value.size)
        assertEquals(connectionKey, registry.connectedDevices.value.single().connectionKey)
        assertEquals(
            setOf(
                BtEngineState.BtServiceType.FTMS,
                BtEngineState.BtServiceType.HeartRate,
            ),
            registry.connectedDevices.value.single().activeServices,
        )
    }

    @Test
    fun `HRM selection followed by RSC activation remains one physical registry entry`() {
        val registry = ConnectionRegistry()
        val context = registry.openAdditional(
            address = "AA:BB",
            gatt = null,
            initialState = BluetoothProfile.STATE_CONNECTED,
        )
        context.handlers += TestHandler(BluetoothIds.UUID_HRM_SERVICE)
        context.discoveredServiceUuids += BluetoothIds.UUID_RSC_SERVICE
        context.handlerActivator = { handler ->
            context.handlers += handler
            true
        }

        assertTrue(
            registry.activateHandlerOn(
                address = "aa:bb",
                handler = TestHandler(BluetoothIds.UUID_RSC_SERVICE),
            )
        )

        val links = registry.connectedDevices.value
        assertEquals(1, links.size)
        assertEquals(
            setOf(BtEngineState.BtServiceType.HeartRate, BtEngineState.BtServiceType.RSC),
            links.single().activeServices,
        )
    }

    @Test
    fun `one machine and three sensors coexist and disconnect independently by key`() {
        val registry = ConnectionRegistry()
        val machine = registry.open(
            LegacyConnectionRole.FTMS,
            "AA:00",
            gatt = null,
            initialState = BluetoothProfile.STATE_CONNECTED,
        )
        val firstSensor = registry.openAdditional(
            "AA:01",
            gatt = null,
            initialState = BluetoothProfile.STATE_CONNECTED,
        )
        val secondSensor = registry.openAdditional(
            "AA:02",
            gatt = null,
            initialState = BluetoothProfile.STATE_CONNECTED,
        )
        val thirdSensor = registry.openAdditional(
            "AA:03",
            gatt = null,
            initialState = BluetoothProfile.STATE_CONNECTED,
        )

        assertEquals(4, registry.connectedDevices.value.size)
        assertSame(machine, registry.current(LegacyConnectionRole.FTMS))
        assertSame(firstSensor, registry.current(LegacyConnectionRole.HRM))

        val contexts = listOf(machine, firstSensor, secondSensor, thirdSensor)
        val keysByAddress = registry.connectedDevices.value.associate {
            it.address to it.connectionKey
        }
        contexts.forEach { target ->
            val key = keysByAddress.getValue(target.address)
            assertSame(target, registry.contextForKey(key))
            val remainingBefore = registry.connectedDevices.value
                .mapTo(linkedSetOf()) { it.address }
                .minus(target.address)

            assertTrue(registry.clear(registry.contextForKey(key)!!))

            assertNull(registry.contextForKey(key))
            assertFalse(registry.isCurrent(target))
            assertEquals(
                remainingBefore,
                registry.connectedDevices.value.mapTo(linkedSetOf()) { it.address },
            )
        }
        assertTrue(registry.connectedDevices.value.isEmpty())
    }

    private class TestHandler(
        serviceUuid: UUID,
    ) : BtEngineGattCore.ProtocolHandler {
        override val primaryServices: Set<UUID> = setOf(serviceUuid)

        override fun onServicesDiscovered(
            engine: BtEngine,
            gatt: BluetoothGatt,
            services: List<BluetoothGattService>,
        ): Boolean = true
    }

    @Test
    fun `active handlers are handed to connection termination exactly once`() {
        val lifecycle = ActiveHandlerLifecycle<String>()
        lifecycle.addAll(listOf("hrm", "echo"))

        assertEquals(listOf("hrm", "echo"), lifecycle.snapshot())
        assertEquals(listOf("hrm", "echo"), lifecycle.terminateOnce())
        assertNull(lifecycle.terminateOnce())

        lifecycle.addAll(listOf("late"))
        assertTrue(lifecycle.snapshot().isEmpty())

        val reconnectLifecycle = ActiveHandlerLifecycle<String>()
        reconnectLifecycle.addAll(listOf("hrm", "echo"))
        assertEquals(listOf("hrm", "echo"), reconnectLifecycle.terminateOnce())
    }

    @Test
    fun `FTMS and HRM contexts occupy independent compatibility slots`() {
        val registry = ConnectionRegistry()

        val ftms = registry.open(
            LegacyConnectionRole.FTMS,
            "aa:bb:cc:dd:ee:01",
            gatt = null,
            initialState = BluetoothProfile.STATE_CONNECTING,
        )
        val hrm = registry.open(
            LegacyConnectionRole.HRM,
            "AA:BB:CC:DD:EE:02",
            gatt = null,
            initialState = BluetoothProfile.STATE_CONNECTED,
        )

        assertSame(ftms, registry.current(LegacyConnectionRole.FTMS))
        assertSame(hrm, registry.current(LegacyConnectionRole.HRM))
        assertEquals("AA:BB:CC:DD:EE:01", ftms.address)
        assertEquals(LinkState.CONNECTING, ftms.connectionState.value)
        assertEquals(LinkState.CONNECTED, hrm.connectionState.value)
        assertNotSame(ftms.queue, hrm.queue)
        assertNotSame(ftms.cccd, hrm.cccd)
    }

    @Test
    fun `reopening an address creates a new link generation`() {
        val registry = ConnectionRegistry()
        val first = registry.open(
            LegacyConnectionRole.FTMS,
            "aa:bb:cc:dd:ee:01",
            gatt = null,
            initialState = BluetoothProfile.STATE_CONNECTING,
        )
        first.queue.enqueue(StackEntry(StackEntry.Op.READ, charUuid = java.util.UUID.randomUUID()))
        first.queue.beginDispatch()
        registry.setCccdReady(first, true)
        assertTrue(registry.machineCccdReady.value)

        val second = registry.open(
            LegacyConnectionRole.FTMS,
            "AA:BB:CC:DD:EE:01",
            gatt = null,
            initialState = BluetoothProfile.STATE_CONNECTING,
        )

        assertEquals(first.connectionId.address, second.connectionId.address)
        assertNotEquals(first.connectionId, second.connectionId)
        assertEquals(first.connectionId.linkGeneration + 1, second.connectionId.linkGeneration)
        assertEquals(LinkState.DISCONNECTED, first.connectionState.value)
        assertEquals(0, first.queue.size)
        assertFalse(first.queue.isBusy)
        assertFalse(first.cccd.ready.value)
        assertFalse(registry.machineCccdReady.value)
    }

    @Test
    fun `clearing one slot leaves the other context intact`() {
        val registry = ConnectionRegistry()
        val ftms = registry.open(
            LegacyConnectionRole.FTMS,
            "AA:BB:CC:DD:EE:01",
            gatt = null,
            initialState = BluetoothProfile.STATE_CONNECTED,
        )
        val hrm = registry.open(
            LegacyConnectionRole.HRM,
            "AA:BB:CC:DD:EE:02",
            gatt = null,
            initialState = BluetoothProfile.STATE_CONNECTED,
        )
        ftms.queue.enqueue(StackEntry(StackEntry.Op.READ, charUuid = java.util.UUID.randomUUID()))
        hrm.queue.enqueue(StackEntry(StackEntry.Op.READ, charUuid = java.util.UUID.randomUUID()))
        ftms.queue.beginDispatch()
        hrm.queue.beginDispatch()
        assertEquals(
            CccdRecoveryDecision.Retry(1),
            ftms.cccd.nextRecoveryDecision(MAX_CCCD_RECOVERY_ATTEMPTS),
        )
        assertEquals(
            CccdRecoveryDecision.Retry(1),
            hrm.cccd.nextRecoveryDecision(MAX_CCCD_RECOVERY_ATTEMPTS),
        )

        registry.clear(LegacyConnectionRole.FTMS)

        assertNull(registry.current(LegacyConnectionRole.FTMS))
        assertSame(hrm, registry.current(LegacyConnectionRole.HRM))
        assertEquals(0, ftms.queue.size)
        assertFalse(ftms.queue.isBusy)
        assertEquals(0, ftms.cccd.recoveryAttempts)
        assertEquals(1, hrm.queue.size)
        assertTrue(hrm.queue.isBusy)
        assertEquals(1, hrm.cccd.recoveryAttempts)
    }

    @Test
    fun `clearing a context releases its explicit termination hook`() {
        val registry = ConnectionRegistry()
        val context = registry.open(
            LegacyConnectionRole.HRM,
            "AA:BB:CC:DD:EE:02",
            gatt = null,
            initialState = BluetoothProfile.STATE_CONNECTED,
        )
        context.terminationHook = {}

        assertTrue(registry.clear(context))

        assertNull(context.terminationHook)
    }

    @Test
    fun `legacy connection-state flows project their current owning contexts`() {
        val state = BtEngineState()
        val ftms = state.connectionRegistry.open(
            LegacyConnectionRole.FTMS,
            "AA:BB:CC:DD:EE:01",
            gatt = null,
            initialState = BluetoothProfile.STATE_CONNECTING,
        )
        val hrm = state.connectionRegistry.open(
            LegacyConnectionRole.HRM,
            "AA:BB:CC:DD:EE:02",
            gatt = null,
            initialState = BluetoothProfile.STATE_CONNECTING,
        )

        state.setConnectionState(ftms, BluetoothProfile.STATE_CONNECTED)
        state.setConnectionState(hrm, BluetoothProfile.STATE_DISCONNECTED)

        assertEquals(LinkState.CONNECTED, ftms.connectionState.value)
        assertEquals(LinkState.DISCONNECTED, hrm.connectionState.value)
        assertEquals(BluetoothProfile.STATE_CONNECTED, state.connectionState.value)
        assertEquals(BluetoothProfile.STATE_DISCONNECTED, state.hrmConnectionState.value)
    }

    @Test
    fun `stale context state cannot replace current compatibility projection`() {
        val state = BtEngineState()
        val stale = state.connectionRegistry.open(
            LegacyConnectionRole.FTMS,
            "AA:BB:CC:DD:EE:01",
            gatt = null,
            initialState = BluetoothProfile.STATE_CONNECTING,
        )
        val current = state.connectionRegistry.open(
            LegacyConnectionRole.FTMS,
            "AA:BB:CC:DD:EE:02",
            gatt = null,
            initialState = BluetoothProfile.STATE_CONNECTING,
        )

        state.setConnectionState(current, BluetoothProfile.STATE_CONNECTED)
        state.setConnectionState(stale, BluetoothProfile.STATE_DISCONNECTED)

        assertFalse(stale.isActive)
        assertFalse(state.connectionRegistry.isCurrent(stale))
        assertTrue(state.connectionRegistry.isCurrent(current))
        assertEquals(LinkState.DISCONNECTED, stale.connectionState.value)
        assertEquals(LinkState.CONNECTED, current.connectionState.value)
        assertEquals(BluetoothProfile.STATE_CONNECTED, state.connectionState.value)
        assertSame(current, state.connectionRegistry.current(LegacyConnectionRole.FTMS))
    }

    @Test
    fun `CSC supported and assigned roles are projected by physical connection identity`() {
        val registry = ConnectionRegistry()
        val context = registry.openAdditional(
            "AA:BB:CC:DD:EE:03",
            gatt = null,
            initialState = BluetoothProfile.STATE_CONNECTED,
        )
        val capability = BtEngineState.CscRoleCapability(
            roles = setOf(BtEngineState.CscRole.SPEED),
            source = BtEngineState.CscRoleSource.APPEARANCE,
        )

        registry.setCscRoleCapability(context, capability)
        registry.setCscAssignedRoles(context, setOf(BtEngineState.CscRole.SPEED))

        assertEquals(capability, registry.connectedDevices.value.single().cscRoleCapability)
        assertEquals(
            setOf(BtEngineState.CscRole.SPEED),
            registry.connectedDevices.value.single().cscAssignedRoles,
        )
    }
}
