package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.capture.BleCapture
import com.gmail.absolutebollockscreations.gattlibrary.core.capture.CaptureTrigger
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.Concept2Uuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids

/**
 * The §0.7a integration that Phase 0.7a never shipped.
 *
 * `MachineAdapterPolicy` was built and tested, but nothing constructed it, so a
 * liveness demotion could not occur in the running app and the §0.9.5 capture
 * trigger that depends on it was unreachable. These tests cover the coordinator
 * that closes that gap.
 */
class AdapterLivenessCoordinatorTest {

    private fun context(
        generation: Long = 1L,
        machine: Boolean = true,
        services: Set<UUID> = setOf(FtmsUuids.UUID_FTMS_SERVICE),
    ): ConnectionContext = ConnectionContext(
        connectionId = ConnectionId(address = "AA:BB:CC:DD:EE:FF", linkGeneration = generation),
        address = "AA:BB:CC:DD:EE:FF",
        machineConnection = machine,
        gatt = null,
        connectionState = MutableStateFlow(LinkState.CONNECTED),
        discoveredServiceUuids = linkedSetOf<UUID>().apply { addAll(services) },
    )

    private fun sample(context: ConnectionContext, atMs: Long): MetricSample = MetricSample(
        connectionId = context.connectionId,
        sourceId = MetricSourceId(
            connectionId = context.connectionId,
            protocol = MetricProtocol.FTMS,
            characteristicUuid = FtmsUuids.UUID_BIKING_DATA,
        ),
        protocol = MetricProtocol.FTMS,
        sourceClass = MetricSourceClass.FTMS,
        metric = MetricKind.SPEED,
        arrivalMs = atMs,
        value = 9.0,
    )

    @Before
    fun setUp() {
        BleCapture.resetSession()
    }

    @After
    fun tearDown() {
        BleCapture.resetSession()
    }

    @Test
    fun `a silent machine is reported once the timeout expires`() {
        val coordinator = AdapterLivenessCoordinator(livenessTimeoutMs = 1_000L)
        val ctx = context()

        coordinator.onSubscriptionsComplete(ctx, nowMs = 0L)
        assertEquals(AdapterPolicyState.AWAITING_DATA, coordinator.stateOf(ctx.connectionId))

        coordinator.tick(999L)
        assertEquals("not yet due", AdapterPolicyState.AWAITING_DATA, coordinator.stateOf(ctx.connectionId))

        coordinator.tick(1_000L)
        assertEquals(
            AdapterPolicyState.CONNECTED_NO_DATA,
            coordinator.stateOf(ctx.connectionId),
        )
    }

    @Test
    fun `an advertisement sample cannot satisfy a GATT adapter liveness policy`() {
        val coordinator = AdapterLivenessCoordinator(livenessTimeoutMs = 1_000L)
        val ctx = context()
        coordinator.onSubscriptionsComplete(ctx, nowMs = 0L)
        val source = MetricSourceId(
            originId = MetricOriginId.Advertisement("keiser", 1L),
            protocol = MetricProtocol.VENDOR,
            endpointId = MetricEndpointId.ManufacturerData(0x0102),
        )

        coordinator.onSample(
            MetricSample(
                originId = source.originId,
                sourceId = source,
                protocol = MetricProtocol.VENDOR,
                sourceClass = MetricSourceClass.VENDOR_NATIVE,
                metric = MetricKind.POWER,
                arrivalMs = 500L,
                freshnessMs = 1_000L,
                value = 200.0,
            ),
        )
        coordinator.tick(1_000L)

        assertEquals(AdapterPolicyState.CONNECTED_NO_DATA, coordinator.stateOf(ctx.connectionId))
    }

    @Test
    fun `the timeout expiring arms a capture`() {
        // This is the whole point: the moment FitBridge knows it was wrong is the
        // moment the evidence needs to reach disk.
        val coordinator = AdapterLivenessCoordinator(livenessTimeoutMs = 500L)
        val ctx = context()

        assertFalse(BleCapture.isArmed())
        coordinator.onSubscriptionsComplete(ctx, nowMs = 0L)
        coordinator.tick(500L)

        assertTrue("a demotion must arm a capture", BleCapture.isArmed())
        assertEquals(CaptureTrigger.LIVENESS_DEMOTION, BleCapture.armedBy())
    }

    @Test
    fun `a sample before the deadline keeps the machine alive`() {
        val coordinator = AdapterLivenessCoordinator(livenessTimeoutMs = 1_000L)
        val ctx = context()

        coordinator.onSubscriptionsComplete(ctx, nowMs = 0L)
        coordinator.onSample(sample(ctx, atMs = 400L))
        coordinator.tick(5_000L)

        assertEquals(AdapterPolicyState.READY, coordinator.stateOf(ctx.connectionId))
        assertFalse("a working machine must not arm anything", BleCapture.isArmed())
    }

    @Test
    fun `an idle machine gets the full generous timeout`() {
        // A machine with nobody on it legitimately sends nothing. The default has
        // to cover connecting, mounting the machine and starting to move.
        assertTrue(
            "default timeout must tolerate a normal pre-workout pause",
            AdapterLivenessCoordinator.DEFAULT_LIVENESS_TIMEOUT_MS >= 30_000L,
        )
    }

    // Concept2 native-only exception to D6, user-authorized 2026-09-12.

    @Test
    fun `a PM5 serving FTMS and the Erg service selects only Concept2`() {
        val coordinator = AdapterLivenessCoordinator(livenessTimeoutMs = 1_000L)
        val ctx = context(
            services = setOf(FtmsUuids.UUID_FTMS_SERVICE, Concept2Uuids.UUID_C2_ERG_SERVICE),
        )

        coordinator.onSubscriptionsComplete(ctx, nowMs = 0L)

        assertEquals(AdapterPolicyState.AWAITING_DATA, coordinator.stateOf(ctx.connectionId))
        assertEquals(
            "the user-selected native-only policy must suppress FTMS",
            AdapterLivenessCoordinator.CONCEPT2_ADAPTER_ID,
            coordinator.selectedAdapterId(ctx.connectionId),
        )
    }

    @Test
    fun `a silent Concept2 stream never falls back to FTMS`() {
        val coordinator = AdapterLivenessCoordinator(livenessTimeoutMs = 1_000L)
        val ctx = context(
            services = setOf(FtmsUuids.UUID_FTMS_SERVICE, Concept2Uuids.UUID_C2_ERG_SERVICE),
        )

        coordinator.onSubscriptionsComplete(ctx, nowMs = 0L)
        coordinator.tick(1_000L)

        assertEquals(AdapterPolicyState.CONNECTED_NO_DATA, coordinator.stateOf(ctx.connectionId))
        assertFalse(AdapterLivenessCoordinator.FTMS_ADAPTER_ID == coordinator.selectedAdapterId(ctx.connectionId))
        assertTrue(BleCapture.isArmed())
    }

    @Test
    fun `an FTMS sample cannot satisfy native-only PM5 liveness`() {
        val coordinator = AdapterLivenessCoordinator(livenessTimeoutMs = 1_000L)
        val ctx = context(services = setOf(FtmsUuids.UUID_FTMS_SERVICE, Concept2Uuids.UUID_C2_ERG_SERVICE))
        coordinator.onSubscriptionsComplete(ctx, nowMs = 0L)
        coordinator.onSample(sample(ctx, atMs = 400L))
        assertEquals(AdapterPolicyState.AWAITING_DATA, coordinator.stateOf(ctx.connectionId))
        coordinator.tick(1_000L)
        assertEquals(AdapterPolicyState.CONNECTED_NO_DATA, coordinator.stateOf(ctx.connectionId))
    }

    @Test
    fun `a Concept2-only PM is driven by the proprietary path from the start`() {
        val coordinator = AdapterLivenessCoordinator(livenessTimeoutMs = 1_000L)
        val ctx = context(services = setOf(Concept2Uuids.UUID_C2_ERG_SERVICE))

        coordinator.onSubscriptionsComplete(ctx, nowMs = 0L)

        assertEquals(
            AdapterLivenessCoordinator.CONCEPT2_ADAPTER_ID,
            coordinator.selectedAdapterId(ctx.connectionId),
        )
    }

    @Test
    fun `native Concept2 measurements satisfy PM5 liveness`() {
        val coordinator = AdapterLivenessCoordinator(livenessTimeoutMs = 1_000L)
        val ctx = context(
            services = setOf(FtmsUuids.UUID_FTMS_SERVICE, Concept2Uuids.UUID_C2_ERG_SERVICE),
        )

        coordinator.onSubscriptionsComplete(ctx, nowMs = 0L)
        coordinator.onSample(sample(ctx, atMs = 400L).copy(
            sourceId = MetricSourceId(ctx.connectionId, MetricProtocol.VENDOR,
                Concept2Uuids.UUID_C2_GENERAL_STATUS),
            protocol = MetricProtocol.VENDOR,
            sourceClass = MetricSourceClass.VENDOR_NATIVE,
        ))
        coordinator.tick(10_000L)

        assertEquals(AdapterPolicyState.READY, coordinator.stateOf(ctx.connectionId))
        assertEquals(
            AdapterLivenessCoordinator.CONCEPT2_ADAPTER_ID,
            coordinator.selectedAdapterId(ctx.connectionId),
        )
        assertFalse(BleCapture.isArmed())
    }

    @Test
    fun `FTMS ranking cannot override the Concept2 exclusion policy`() {
        assertTrue(
            AdapterLivenessCoordinator.RANK_FTMS > AdapterLivenessCoordinator.RANK_CONCEPT2,
        )

        // The generic ranks remain unchanged, but FTMS is excluded before the policy ranks them.
        val coordinator = AdapterLivenessCoordinator(livenessTimeoutMs = 1_000L)
        val ctx = context(
            services = setOf(Concept2Uuids.UUID_C2_ERG_SERVICE, FtmsUuids.UUID_FTMS_SERVICE),
        )
        coordinator.onSubscriptionsComplete(ctx, nowMs = 0L)

        assertEquals(
            "FTMS must not be a candidate regardless of its rank",
            AdapterLivenessCoordinator.CONCEPT2_ADAPTER_ID,
            coordinator.selectedAdapterId(ctx.connectionId),
        )
    }

    @Test
    fun `a machine with neither service registers no candidate`() {
        val coordinator = AdapterLivenessCoordinator(livenessTimeoutMs = 1_000L)
        val ctx = context(services = setOf(SigUuids.UUID_HRM_SERVICE))

        coordinator.onSubscriptionsComplete(ctx, nowMs = 0L)
        coordinator.tick(10_000L)

        assertNull(
            "a strap is not a machine adapter and must not be demoted as one",
            coordinator.selectedAdapterId(ctx.connectionId),
        )
    }

    @Test
    fun `a sample from another connection does not clear this one`() {
        val coordinator = AdapterLivenessCoordinator(livenessTimeoutMs = 1_000L)
        val ctx = context(generation = 1L)
        val other = context(generation = 2L)

        coordinator.onSubscriptionsComplete(ctx, nowMs = 0L)
        coordinator.onSample(sample(other, atMs = 100L))
        coordinator.tick(1_000L)

        assertEquals(
            AdapterPolicyState.CONNECTED_NO_DATA,
            coordinator.stateOf(ctx.connectionId),
        )
    }

    @Test
    fun `a sensor-only connection is not supervised`() {
        // Liveness is a machine-adapter concept. An HRM strap is not a machine
        // and has no adapter to demote.
        val coordinator = AdapterLivenessCoordinator(livenessTimeoutMs = 1_000L)
        val ctx = context(machine = false)

        coordinator.onSubscriptionsComplete(ctx, nowMs = 0L)

        assertNull(coordinator.stateOf(ctx.connectionId))
    }

    @Test
    fun `a machine without the FTMS service produces no candidate`() {
        val coordinator = AdapterLivenessCoordinator(livenessTimeoutMs = 1_000L)
        val ctx = context(services = setOf(SigUuids.UUID_HRM_SERVICE))

        coordinator.onSubscriptionsComplete(ctx, nowMs = 0L)

        assertNull(coordinator.stateOf(ctx.connectionId))
    }

    @Test
    fun `forgetting a link drops its policy so a stale deadline cannot fire`() {
        val coordinator = AdapterLivenessCoordinator(livenessTimeoutMs = 1_000L)
        val ctx = context()

        coordinator.onSubscriptionsComplete(ctx, nowMs = 0L)
        coordinator.forget(ctx.connectionId)

        assertNull(coordinator.stateOf(ctx.connectionId))
        coordinator.tick(10_000L)
        assertFalse("a forgotten link must not arm anything", BleCapture.isArmed())
    }

    @Test
    fun `subscriptions completing twice does not restart the clock`() {
        val coordinator = AdapterLivenessCoordinator(livenessTimeoutMs = 1_000L)
        val ctx = context()

        coordinator.onSubscriptionsComplete(ctx, nowMs = 0L)
        coordinator.onSubscriptionsComplete(ctx, nowMs = 900L)
        coordinator.tick(1_000L)

        assertEquals(
            "the deadline is set once, from the first completion",
            AdapterPolicyState.CONNECTED_NO_DATA,
            coordinator.stateOf(ctx.connectionId),
        )
    }

    @Test
    fun `Life Fitness setup and subscriptions converge in either callback order`() {
        fun lifeFitnessContext(generation: Long): ConnectionContext =
            context(generation = generation).also {
                it.lifeFitnessSetupState = LifeFitnessLinkSetupState.PENDING
            }

        val subscriptionsFirst = lifeFitnessContext(31L)
        val firstCoordinator = AdapterLivenessCoordinator(livenessTimeoutMs = 1_000L)
        subscriptionsFirst.cccd.setReady(true)
        firstCoordinator.onSubscriptionsComplete(subscriptionsFirst, nowMs = 100L)
        assertEquals(
            AdapterPolicyState.SETUP_REQUIRED,
            firstCoordinator.stateOf(subscriptionsFirst.connectionId),
        )
        assertTrue(firstCoordinator.setupSucceeded(subscriptionsFirst, nowMs = 200L))
        assertEquals(
            AdapterPolicyState.AWAITING_DATA,
            firstCoordinator.stateOf(subscriptionsFirst.connectionId),
        )

        val setupFirst = lifeFitnessContext(32L)
        val secondCoordinator = AdapterLivenessCoordinator(livenessTimeoutMs = 1_000L)
        assertTrue(secondCoordinator.setupSucceeded(setupFirst, nowMs = 100L))
        assertEquals(
            AdapterPolicyState.SUBSCRIPTION_PENDING,
            secondCoordinator.stateOf(setupFirst.connectionId),
        )
        setupFirst.cccd.setReady(true)
        secondCoordinator.onSubscriptionsComplete(setupFirst, nowMs = 200L)
        assertEquals(
            AdapterPolicyState.AWAITING_DATA,
            secondCoordinator.stateOf(setupFirst.connectionId),
        )
    }

    @Test
    fun `Life Fitness setup failure is retained before descriptor completion`() {
        val ctx = context(generation = 33L).also {
            it.lifeFitnessSetupState = LifeFitnessLinkSetupState.PENDING
        }
        val coordinator = AdapterLivenessCoordinator(livenessTimeoutMs = 1_000L)

        assertTrue(coordinator.setupFailed(ctx, "control 1 open rejected"))
        ctx.cccd.setReady(true)
        coordinator.onSubscriptionsComplete(ctx, nowMs = 100L)

        assertEquals(AdapterPolicyState.SETUP_FAILED, coordinator.stateOf(ctx.connectionId))
    }
}
