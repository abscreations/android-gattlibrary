package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.capture.BleCapture
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.capture.CaptureTrigger
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
        services: Set<UUID> = setOf(BluetoothIds.UUID_FTMS_SERVICE),
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
            characteristicUuid = BluetoothIds.UUID_BIKING_DATA,
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

    // ── D6: a PM5 advertising both standards produces two candidates ─────────────

    @Test
    fun `a PM5 advertising FTMS and the Erg service registers both candidates`() {
        // TMIW hard-skips its proprietary Concept2 descriptor whenever FTMS is advertised. D6
        // rejects that: both are registered, FTMS ranked higher, and the proprietary path stays
        // available rather than being discarded at discovery.
        val coordinator = AdapterLivenessCoordinator(livenessTimeoutMs = 1_000L)
        val ctx = context(
            services = setOf(BluetoothIds.UUID_FTMS_SERVICE, BluetoothIds.UUID_C2_ERG_SERVICE),
        )

        coordinator.onSubscriptionsComplete(ctx, nowMs = 0L)

        assertEquals(AdapterPolicyState.AWAITING_DATA, coordinator.stateOf(ctx.connectionId))
        assertEquals(
            "FTMS is the first choice on a PM5 that serves it",
            AdapterLivenessCoordinator.FTMS_ADAPTER_ID,
            coordinator.selectedAdapterId(ctx.connectionId),
        )
    }

    @Test
    fun `a silent FTMS advertisement falls back to the Concept2 path instead of dying`() {
        // The case D6 exists for: a firmware revision that advertises 0x1826 and then sends
        // nothing. Under a hard skip this is a dead session with no recovery.
        val coordinator = AdapterLivenessCoordinator(livenessTimeoutMs = 1_000L)
        val ctx = context(
            services = setOf(BluetoothIds.UUID_FTMS_SERVICE, BluetoothIds.UUID_C2_ERG_SERVICE),
        )

        coordinator.onSubscriptionsComplete(ctx, nowMs = 0L)
        coordinator.tick(1_000L)

        assertEquals(
            "the proprietary path must take over rather than the session going dead",
            AdapterLivenessCoordinator.CONCEPT2_ADAPTER_ID,
            coordinator.selectedAdapterId(ctx.connectionId),
        )
        assertTrue("the demotion is still the evidence that matters", BleCapture.isArmed())
    }

    @Test
    fun `a Concept2-only PM is driven by the proprietary path from the start`() {
        val coordinator = AdapterLivenessCoordinator(livenessTimeoutMs = 1_000L)
        val ctx = context(services = setOf(BluetoothIds.UUID_C2_ERG_SERVICE))

        coordinator.onSubscriptionsComplete(ctx, nowMs = 0L)

        assertEquals(
            AdapterLivenessCoordinator.CONCEPT2_ADAPTER_ID,
            coordinator.selectedAdapterId(ctx.connectionId),
        )
    }

    @Test
    fun `a working FTMS PM5 never demotes to the proprietary path`() {
        val coordinator = AdapterLivenessCoordinator(livenessTimeoutMs = 1_000L)
        val ctx = context(
            services = setOf(BluetoothIds.UUID_FTMS_SERVICE, BluetoothIds.UUID_C2_ERG_SERVICE),
        )

        coordinator.onSubscriptionsComplete(ctx, nowMs = 0L)
        coordinator.onSample(sample(ctx, atMs = 400L))
        coordinator.tick(10_000L)

        assertEquals(AdapterPolicyState.READY, coordinator.stateOf(ctx.connectionId))
        assertEquals(
            AdapterLivenessCoordinator.FTMS_ADAPTER_ID,
            coordinator.selectedAdapterId(ctx.connectionId),
        )
        assertFalse(BleCapture.isArmed())
    }

    @Test
    fun `FTMS outranks Concept2, and evidence weight must not override that`() {
        assertTrue(
            AdapterLivenessCoordinator.RANK_FTMS > AdapterLivenessCoordinator.RANK_CONCEPT2,
        )

        // The rank constants alone do not settle the ordering. `MachineAdapterPolicy` sorts by
        // evidence weight *first* and only then by rank, so a heavier Concept2 fact would win
        // outright and D6 would be silently inverted while these constants still read correctly.
        //
        // This is the assertion that actually holds the ordering, and it is why the Concept2
        // fact carries the same weight as the FTMS one rather than the larger weight a 128-bit
        // vendor UUID might seem to deserve.
        val coordinator = AdapterLivenessCoordinator(livenessTimeoutMs = 1_000L)
        val ctx = context(
            services = setOf(BluetoothIds.UUID_C2_ERG_SERVICE, BluetoothIds.UUID_FTMS_SERVICE),
        )
        coordinator.onSubscriptionsComplete(ctx, nowMs = 0L)

        assertEquals(
            "evidence weight must not promote the proprietary path ahead of FTMS",
            AdapterLivenessCoordinator.FTMS_ADAPTER_ID,
            coordinator.selectedAdapterId(ctx.connectionId),
        )
    }

    @Test
    fun `a machine with neither service registers no candidate`() {
        val coordinator = AdapterLivenessCoordinator(livenessTimeoutMs = 1_000L)
        val ctx = context(services = setOf(BluetoothIds.UUID_HRM_SERVICE))

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
        val ctx = context(services = setOf(BluetoothIds.UUID_HRM_SERVICE))

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
}
