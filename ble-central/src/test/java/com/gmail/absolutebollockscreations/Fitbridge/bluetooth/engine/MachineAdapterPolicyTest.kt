package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MachineAdapterPolicyTest {
    private val connection = ConnectionId("AA:BB:CC:00:00:01", 7)
    private val otherGeneration = ConnectionId("AA:BB:CC:00:00:01", 8)

    private fun evidence(weight: Int, value: String): MatchEvidence = MatchEvidence(
        listOf(
            MatchEvidenceFact(
                kind = MatchEvidenceKind.ADVERTISED_SERVICE,
                structuralValue = value,
                weight = weight,
                collisionRisk = CollisionRisk.LOW,
                note = "test evidence",
            ),
        ),
    )

    private fun candidate(
        id: String,
        evidenceWeight: Int,
        rank: Int,
        setup: Boolean = false,
        timeoutMs: Long = 1_000,
    ) = MachineAdapterCandidate(
        adapterId = id,
        evidence = evidence(evidenceWeight, id),
        rank = rank,
        requiresSetup = setup,
        livenessTimeoutMs = timeoutMs,
    )

    @Test
    fun `evidence strength ranks before configured rank and ties are deterministic`() {
        val policy = MachineAdapterPolicy(
            connection,
            listOf(
                candidate("bare-ftms", evidenceWeight = 60, rank = 100),
                candidate("vendor", evidenceWeight = 200, rank = 1),
                candidate("vendor-z", evidenceWeight = 200, rank = 1),
            ),
        )

        assertEquals("vendor", policy.start().selected?.adapterId)
        assertEquals(AdapterPolicyState.SUBSCRIPTION_PENDING, policy.snapshot().state)

        val rankTieBreaker = MachineAdapterPolicy(
            connection,
            listOf(
                candidate("lower-rank", evidenceWeight = 100, rank = 1),
                candidate("higher-rank", evidenceWeight = 100, rank = 2),
            ),
        )
        assertEquals("higher-rank", rankTieBreaker.start().selected?.adapterId)
    }

    @Test
    fun `setup-required candidate is not ready or timed until setup and subscriptions succeed`() {
        val policy = MachineAdapterPolicy(
            connection,
            listOf(candidate("life-fitness", 200, 10, setup = true, timeoutMs = 2_000)),
        )
        assertEquals(AdapterPolicyState.SETUP_REQUIRED, policy.start().state)
        assertFalse(policy.subscriptionsCompleted(connection, 100))
        assertFalse(policy.metricSampleArrived(connection, "life-fitness", 101))
        assertTrue(policy.setupSucceeded(connection))
        assertEquals(AdapterPolicyState.SUBSCRIPTION_PENDING, policy.snapshot().state)
        assertTrue(policy.subscriptionsCompleted(connection, 1_000))
        assertEquals(3_000L, policy.snapshot().livenessDeadlineMs)
        assertTrue(policy.metricSampleArrived(connection, "life-fitness", 1_001))
        assertEquals(AdapterPolicyState.READY, policy.snapshot().state)
    }

    @Test
    fun `setup failure is a distinct terminal state and never waits for liveness`() {
        val policy = MachineAdapterPolicy(
            connection,
            listOf(candidate("vendor", 100, 10, setup = true)),
        )
        policy.start()
        assertTrue(policy.setupFailed(connection, "handshake rejected"))
        assertEquals(AdapterPolicyState.SETUP_FAILED, policy.snapshot().state)
        assertEquals("handshake rejected", policy.snapshot().detail)
        assertEquals(AdapterPolicyState.SETUP_FAILED, policy.checkLiveness(99_000).state)
    }

    @Test
    fun `liveness timeout demotes once and records full diagnostic evidence`() {
        val demotions = mutableListOf<AdapterDemotion>()
        val first = candidate("vendor", 200, 10, timeoutMs = 500)
        val policy = MachineAdapterPolicy(
            connection,
            listOf(first, candidate("ftms", 60, 100, timeoutMs = 1_000)),
            AdapterDemotionRecorder(demotions::add),
        )
        policy.start()
        policy.subscriptionsCompleted(connection, 1_000)
        assertEquals("vendor", policy.checkLiveness(1_499).selected?.adapterId)
        val promoted = policy.checkLiveness(1_500)

        assertEquals("ftms", promoted.selected?.adapterId)
        assertEquals(AdapterPolicyState.SUBSCRIPTION_PENDING, promoted.state)
        assertTrue(promoted.demotionUsed)
        assertEquals(1, demotions.size)
        assertEquals(connection, demotions.single().connectionId)
        assertEquals("vendor", demotions.single().fromAdapterId)
        assertEquals("ftms", demotions.single().toAdapterId)
        assertEquals(500L, demotions.single().timeoutMs)
        assertEquals(first.evidence, demotions.single().evidence)
    }

    @Test
    fun `fallback timeout is bounded and settles connected no-data without oscillation`() {
        val demotions = mutableListOf<AdapterDemotion>()
        val policy = MachineAdapterPolicy(
            connection,
            listOf(
                candidate("vendor", 200, 1, timeoutMs = 100),
                candidate("ftms", 60, 1, timeoutMs = 200),
                candidate("third", 40, 1, timeoutMs = 300),
            ),
            AdapterDemotionRecorder(demotions::add),
        )
        policy.start()
        policy.subscriptionsCompleted(connection, 0)
        assertEquals("ftms", policy.checkLiveness(100).selected?.adapterId)
        policy.subscriptionsCompleted(connection, 101)
        val terminal = policy.checkLiveness(301)

        assertEquals(AdapterPolicyState.CONNECTED_NO_DATA, terminal.state)
        assertEquals("ftms", terminal.selected?.adapterId)
        assertEquals(1, demotions.size)
        assertEquals(AdapterPolicyState.CONNECTED_NO_DATA, policy.checkLiveness(10_000).state)
        assertEquals(1, demotions.size)
    }

    @Test
    fun `no matching candidate starts in connected no-data`() {
        val policy = MachineAdapterPolicy(connection, emptyList())
        val snapshot = policy.start()
        assertEquals(AdapterPolicyState.CONNECTED_NO_DATA, snapshot.state)
        assertNull(snapshot.selected)
    }

    @Test
    fun `single unsuccessful candidate records one terminal demotion`() {
        val demotions = mutableListOf<AdapterDemotion>()
        val policy = MachineAdapterPolicy(
            connection,
            listOf(candidate("ftms", 60, 1, timeoutMs = 100)),
            AdapterDemotionRecorder(demotions::add),
        )
        policy.start()
        policy.subscriptionsCompleted(connection, 10)
        assertEquals(AdapterPolicyState.CONNECTED_NO_DATA, policy.checkLiveness(110).state)
        assertNull(demotions.single().toAdapterId)
    }

    @Test
    fun `zero timeout expects no fallback and remains eligible for a later sample`() {
        val policy = MachineAdapterPolicy(
            connection,
            listOf(candidate("ftms", 60, 1, timeoutMs = 0)),
        )
        policy.start()
        policy.subscriptionsCompleted(connection, 100)
        assertNull(policy.snapshot().livenessDeadlineMs)
        assertEquals(AdapterPolicyState.AWAITING_DATA, policy.checkLiveness(Long.MAX_VALUE).state)
        assertTrue(policy.metricSampleArrived(connection, "ftms", Long.MAX_VALUE))
        assertEquals(AdapterPolicyState.READY, policy.snapshot().state)
    }

    @Test
    fun `wrong adapter and stale link generation cannot satisfy readiness`() {
        val policy = MachineAdapterPolicy(
            connection,
            listOf(candidate("ftms", 60, 1)),
        )
        policy.start()
        assertFalse(policy.subscriptionsCompleted(otherGeneration, 0))
        assertTrue(policy.subscriptionsCompleted(connection, 0))
        assertFalse(policy.metricSampleArrived(otherGeneration, "ftms", 1))
        assertFalse(policy.metricSampleArrived(connection, "vendor", 1))
        assertEquals(AdapterPolicyState.AWAITING_DATA, policy.snapshot().state)
    }

    @Test
    fun `sample at or after deadline cannot retroactively satisfy liveness`() {
        val policy = MachineAdapterPolicy(
            connection,
            listOf(candidate("ftms", 60, 1, timeoutMs = 100)),
        )
        policy.start()
        policy.subscriptionsCompleted(connection, 1_000)
        assertFalse(policy.metricSampleArrived(connection, "ftms", 1_100))
        assertEquals(AdapterPolicyState.CONNECTED_NO_DATA, policy.checkLiveness(1_100).state)
    }

    @Test
    fun `adapter-owned polling miss becomes terminal and records evidence once`() {
        val demotions = mutableListOf<AdapterDemotion>()
        val policy = MachineAdapterPolicy(
            connection,
            listOf(candidate("kayak.first", 40, 80, setup = true)),
            AdapterDemotionRecorder(demotions::add),
        )
        policy.start()
        policy.setupSucceeded(connection)
        policy.subscriptionsCompleted(connection, 0)
        policy.metricSampleArrived(connection, "kayak.first", 1)

        assertTrue(policy.connectedNoData(connection, "three missed polls"))
        assertEquals(AdapterPolicyState.CONNECTED_NO_DATA, policy.snapshot().state)
        assertEquals("three missed polls", policy.snapshot().detail)
        assertFalse(policy.connectedNoData(connection, "four missed polls"))
        assertEquals(1, demotions.size)
        assertNull(demotions.single().toAdapterId)
    }

    @Test
    fun `repeated lifecycle callbacks are idempotent and do not reset deadline`() {
        val policy = MachineAdapterPolicy(
            connection,
            listOf(candidate("ftms", 60, 1, timeoutMs = 500)),
        )
        val firstStart = policy.start()
        assertEquals(firstStart, policy.start())
        assertTrue(policy.subscriptionsCompleted(connection, 100))
        assertFalse(policy.subscriptionsCompleted(connection, 200))
        assertEquals(600L, policy.snapshot().livenessDeadlineMs)
    }
}
