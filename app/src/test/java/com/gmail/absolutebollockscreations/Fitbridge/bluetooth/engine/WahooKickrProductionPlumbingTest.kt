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

/** Phase 10.1 reachability, adversarial fingerprint, ranking, and reconnect obligations. */
class WahooKickrProductionPlumbingTest {

    private val validProperties = BluetoothGattCharacteristic.PROPERTY_WRITE or
        BluetoothGattCharacteristic.PROPERTY_INDICATE

    private fun inventory(
        markerServices: Set<UUID> = setOf(WahooKickrUuids.MARKER_EE0B),
        controlParent: UUID = WahooKickrUuids.CYCLING_POWER_SERVICE,
        controlProperties: Int = validProperties,
        includeControl: Boolean = true,
        extraServices: Set<UUID> = emptySet(),
    ): WahooKickrGattInventory {
        val serviceIds = linkedSetOf(WahooKickrUuids.CYCLING_POWER_SERVICE).apply {
            addAll(markerServices)
            addAll(extraServices)
            add(controlParent)
        }
        return WahooKickrGattInventory(
            serviceIds.associateWith { service ->
                if (includeControl && service == controlParent) {
                    mapOf(WahooKickrUuids.CONTROL to controlProperties)
                } else {
                    emptyMap()
                }
            },
        )
    }

    private fun validMatch(
        markerServices: Set<UUID> = setOf(WahooKickrUuids.MARKER_EE0B),
    ): WahooKickrGattMatch = requireNotNull(matchWahooKickrGatt(inventory(markerServices)))

    private fun context(
        generation: Long = 1L,
        services: Set<UUID> = setOf(WahooKickrUuids.CYCLING_POWER_SERVICE),
        includeWahooHandler: Boolean = true,
    ): ConnectionContext {
        val handlers = mutableListOf<BtEngineGattCore.ProtocolHandler>()
        if (includeWahooHandler) {
            handlers += WahooKickrProtocolHandler().apply { recordStructuralMatch(validMatch()) }
        }
        return ConnectionContext(
            connectionId = ConnectionId("AA:BB:CC:DD:EE:10", generation),
            address = "AA:BB:CC:DD:EE:10",
            machineConnection = true,
            gatt = null,
            connectionState = MutableStateFlow(LinkState.CONNECTED),
            discoveredServiceUuids = services.toCollection(linkedSetOf()),
            handlers = handlers,
        )
    }

    @Test
    fun `each independently corroborated marker activates the legacy fingerprint`() {
        for (marker in WahooKickrUuids.MARKER_SERVICES) {
            val match = matchWahooKickrGatt(inventory(markerServices = setOf(marker)))
            assertEquals(setOf(marker), match?.markerServices)
        }
    }

    @Test
    fun `both markers and RideOn are retained as evidence without changing the control parent`() {
        val match = matchWahooKickrGatt(
            inventory(
                markerServices = WahooKickrUuids.MARKER_SERVICES,
                extraServices = WahooKickrUuids.RIDEON_SERVICES,
            ),
        )

        assertEquals(WahooKickrUuids.MARKER_SERVICES, match?.markerServices)
        assertEquals(WahooKickrUuids.RIDEON_SERVICES, match?.rideOnServices)
    }

    @Test
    fun `bare CPS and missing markers are rejected`() {
        assertNull(matchWahooKickrGatt(inventory(markerServices = emptySet(), includeControl = false)))
        assertNull(matchWahooKickrGatt(inventory(markerServices = emptySet())))
    }

    @Test
    fun `control under the wrong parent is rejected even when CPS and a marker exist`() {
        assertNull(
            matchWahooKickrGatt(
                inventory(controlParent = WahooKickrUuids.MARKER_EE0B),
            ),
        )
    }

    @Test
    fun `control must support both write and indicate`() {
        assertNull(
            matchWahooKickrGatt(
                inventory(controlProperties = BluetoothGattCharacteristic.PROPERTY_WRITE),
            ),
        )
        assertNull(
            matchWahooKickrGatt(
                inventory(controlProperties = BluetoothGattCharacteristic.PROPERTY_INDICATE),
            ),
        )
        assertNull(matchWahooKickrGatt(inventory(controlProperties = 0)))
    }

    @Test
    fun `KICKR is offered on machine links and factory instances are per link`() {
        assertTrue(ProtocolKey.WAHOO_KICKR in MACHINE_PROTOCOLS)
        val factory = HandlerFactory.standard(enablePhase05HardwareTest = false)
        val first = factory.create(ProtocolKey.WAHOO_KICKR)
        val second = factory.create(ProtocolKey.WAHOO_KICKR)
        assertTrue(first is WahooKickrProtocolHandler)
        assertTrue(second is WahooKickrProtocolHandler)
        assertNotSame(first, second)
    }

    @Test
    fun `handler activation is CPS gated and control routes explicitly`() {
        val wahoo = WahooKickrProtocolHandler()
        val ftms = FtmsProtocolHandler()
        assertEquals(
            listOf(wahoo),
            activateMatchingProtocolHandlers(
                listOf(ftms, wahoo),
                setOf(WahooKickrUuids.CYCLING_POWER_SERVICE),
                offer = { true },
            ),
        )

        val route = resolveProtocolHandlerRoute(
            listOf(ftms, wahoo),
            WahooKickrUuids.CONTROL,
            WahooKickrUuids.CYCLING_POWER_SERVICE,
        )
        assertEquals(HandlerOwnership.EXPLICIT_CHARACTERISTIC, route.ownership)
        assertSame(wahoo, route.owners.single())
    }

    @Test
    fun `link-local fingerprint is cleared for reconnect`() {
        val handler = WahooKickrProtocolHandler()
        handler.recordStructuralMatch(validMatch())
        assertTrue(handler.structuralMatch != null)

        handler.resetLinkState()

        assertNull(handler.structuralMatch)
        assertNull(WahooKickrProtocolHandler().structuralMatch)
    }

    @Test
    fun `CPS plus KICKR name is provisional machine admission but neither alone is`() {
        val registry = DeviceMatcherRegistry.standard()
        val cps = CanonicalUuid.fromUuid(WahooKickrUuids.CYCLING_POWER_SERVICE)

        val admitted = registry.rankedCandidates(
            MatchObservation(localName = "KICKR CORE C3FB", advertisedServiceUuids = setOf(cps)),
        )
        assertEquals("vendor.wahoo.kickr.legacy", admitted.first().candidateId)
        assertTrue(admitted.first().machineRole)

        val cpsOnly = registry.rankedCandidates(MatchObservation(advertisedServiceUuids = setOf(cps)))
        assertEquals(listOf("sig.cps"), cpsOnly.map { it.candidateId })

        val nameOnly = registry.rankedCandidates(MatchObservation(localName = "KICKR v5"))
        assertFalse(nameOnly.any { it.candidateId == "vendor.wahoo.kickr.legacy" })
    }

    @Test
    fun `post-connect CPS plus proprietary control identifies provisional KICKR`() {
        val registry = DeviceMatcherRegistry.standard()
        val candidates = registry.rankedCandidates(
            MatchObservation(
                discoveredServiceUuids = setOf(
                    CanonicalUuid.fromUuid(WahooKickrUuids.CYCLING_POWER_SERVICE),
                ),
                discoveredCharacteristicUuids = setOf(
                    CanonicalUuid.fromUuid(WahooKickrUuids.CONTROL),
                ),
            ),
        )
        assertEquals("vendor.wahoo.kickr.legacy", candidates.first().candidateId)
    }

    @Test
    fun `standalone legacy KICKR stays setup gated and bare CPS has no machine candidate`() {
        val coordinator = AdapterLivenessCoordinator(livenessTimeoutMs = 1_000L)
        val kickr = context()
        coordinator.onSubscriptionsComplete(kickr, nowMs = 0L)
        assertEquals(AdapterPolicyState.SETUP_REQUIRED, coordinator.stateOf(kickr.connectionId))
        assertEquals(
            AdapterLivenessCoordinator.WAHOO_KICKR_ADAPTER_ID,
            coordinator.selectedAdapterId(kickr.connectionId),
        )

        val bareCps = context(generation = 2L, includeWahooHandler = false)
        coordinator.onSubscriptionsComplete(bareCps, nowMs = 0L)
        assertNull(coordinator.selectedAdapterId(bareCps.connectionId))
    }

    @Test
    fun `native FTMS wins then a silence demotes once to setup-gated legacy`() {
        val coordinator = AdapterLivenessCoordinator(livenessTimeoutMs = 1_000L)
        val kickr = context(
            services = setOf(
                BluetoothIds.UUID_FTMS_SERVICE,
                WahooKickrUuids.CYCLING_POWER_SERVICE,
            ),
        )

        coordinator.onSubscriptionsComplete(kickr, nowMs = 0L)
        assertEquals(
            AdapterLivenessCoordinator.FTMS_ADAPTER_ID,
            coordinator.selectedAdapterId(kickr.connectionId),
        )
        assertTrue(
            AdapterLivenessCoordinator.RANK_FTMS >
                AdapterLivenessCoordinator.RANK_WAHOO_KICKR,
        )

        coordinator.tick(1_000L)

        assertEquals(
            AdapterLivenessCoordinator.WAHOO_KICKR_ADAPTER_ID,
            coordinator.selectedAdapterId(kickr.connectionId),
        )
        assertEquals(AdapterPolicyState.SETUP_REQUIRED, coordinator.stateOf(kickr.connectionId))
    }
}
