package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Plan §11.4 and §11.5 for the Concept2 PM: multi-characteristic subscription through one
 * per-connection queue, handler ownership, fresh per-link state, and cumulative continuity
 * across a reconnect.
 */
class Concept2ProductionPlumbingTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { (v[it] and 0xFF).toByte() }

    // ── Handler registration and ownership ───────────────────────────────────────

    @Test
    fun `the factory builds a fresh Concept2 handler each time`() {
        val factory = HandlerFactory.standard(enablePhase05HardwareTest = false)
        val first = factory.create(ProtocolKey.CONCEPT2)
        val second = factory.create(ProtocolKey.CONCEPT2)

        assertTrue(first is Concept2ProtocolHandler)
        assertTrue(second is Concept2ProtocolHandler)
        assertNotSame("per-link state must not be shared between connections", first, second)
    }

    @Test
    fun `Concept2 is a machine protocol, not a secondary sensor`() {
        // Like FTMS, it is admitted through the machine path. There is no BtServiceType for it
        // and `sensorProtocolFor` deliberately has no branch that could return it.
        for (type in BtEngineState.BtServiceType.values()) {
            assertTrue(
                "$type must not route to the Concept2 handler as a sensor",
                sensorProtocolFor(type) != ProtocolKey.CONCEPT2,
            )
        }
    }

    @Test
    fun `the handler owns all five characteristics on one link`() {
        val concept2 = Concept2ProtocolHandler()
        val ftms = FtmsProtocolHandler()

        val active = activateMatchingProtocolHandlers(
            listOf(ftms, concept2),
            setOf(BluetoothIds.UUID_FTMS_SERVICE, BluetoothIds.UUID_C2_ERG_SERVICE),
            offer = { true },
        )
        assertEquals("a PM5 may serve both services on one physical link", 2, active.size)

        // Every Concept2 characteristic routes to the Concept2 handler and to nothing else,
        // including the multiplexed one, which carries other handlers' data on no other device.
        for (characteristic in listOf(
            BluetoothIds.UUID_C2_MULTIPLEXED_INFORMATION,
            BluetoothIds.UUID_C2_GENERAL_STATUS,
            BluetoothIds.UUID_C2_ADDITIONAL_STATUS_1,
            BluetoothIds.UUID_C2_ADDITIONAL_STATUS_2,
            BluetoothIds.UUID_C2_ADDITIONAL_STROKE_DATA,
        )) {
            val route = resolveProtocolHandlerRoute(
                active,
                characteristic,
                BluetoothIds.UUID_C2_ERG_SERVICE,
            )
            assertEquals("$characteristic must route to Concept2 alone", listOf(concept2), route.owners)
            assertSame(concept2, route.owners.single())
            assertEquals(HandlerOwnership.EXPLICIT_CHARACTERISTIC, route.ownership)
        }

        // And an FTMS characteristic on the same link still routes to FTMS.
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

    // ── §11.4: every planned CCCD, through one queue ─────────────────────────────

    @Test
    fun `the individual plan arms one CCCD per consumed characteristic and no more`() {
        val plan = planConcept2Discovery(
            setOf(
                BluetoothIds.UUID_C2_GENERAL_STATUS,
                BluetoothIds.UUID_C2_ADDITIONAL_STATUS_1,
                BluetoothIds.UUID_C2_ADDITIONAL_STATUS_2,
                BluetoothIds.UUID_C2_ADDITIONAL_STROKE_DATA,
                // Present but never subscribed: it is a read/write configuration value.
                BluetoothIds.UUID_C2_STATUS_SAMPLE_RATE,
            ),
        )

        assertEquals(Concept2DeliveryPath.INDIVIDUAL, plan.path)
        assertEquals(4, plan.operations.size)
        assertTrue(
            "the sample-rate characteristic is configuration, not a notification source",
            plan.operations.none {
                it.characteristicUuid == BluetoothIds.UUID_C2_STATUS_SAMPLE_RATE
            },
        )
        assertTrue(
            plan.operations.all { it.kind == Concept2GattOperationKind.ENABLE_NOTIFICATIONS },
        )
    }

    @Test
    fun `all four CCCDs complete through one connection's queue before it is ready`() {
        val cccd = CccdState()
        val planned = cccd.mergeEnablePlan(
            CONCEPT2_CONSUMED_CHARACTERISTICS.map {
                CccdTarget(BluetoothIds.UUID_C2_ERG_SERVICE, it)
            },
        )
        assertEquals("four distinct targets, one per consumed characteristic", 4, planned.size)
        assertEquals(4, cccd.plannedEnableCount)

        // All four are queued on the same per-connection queue, then complete one at a time.
        repeat(4) { cccd.incrementPending() }

        // Three of four: a partially subscribed PM would deliver some metrics and silently omit
        // others, which is worse than not activating at all.
        repeat(3) { assertFalse("not ready until the last one lands", cccd.completePending()) }
        assertFalse(cccd.ready.value)

        assertTrue("the fourth completion is what makes the link ready", cccd.completePending())
        assertTrue(cccd.ready.value)
    }

    @Test
    fun `the multiplexed path arms exactly one CCCD`() {
        // D41's practical benefit: one subscription instead of four on a link that may already
        // carry other handlers.
        val cccd = CccdState()
        cccd.mergeEnablePlan(
            listOf(
                CccdTarget(
                    BluetoothIds.UUID_C2_ERG_SERVICE,
                    BluetoothIds.UUID_C2_MULTIPLEXED_INFORMATION,
                ),
            ),
        )
        assertEquals(1, cccd.plannedEnableCount)

        cccd.incrementPending()
        assertTrue(cccd.completePending())
        assertTrue(cccd.ready.value)
    }

    @Test
    fun `a failed Concept2 CCCD prevents readiness`() {
        val cccd = CccdState()
        cccd.mergeEnablePlan(
            listOf(
                CccdTarget(
                    BluetoothIds.UUID_C2_ERG_SERVICE,
                    BluetoothIds.UUID_C2_MULTIPLEXED_INFORMATION,
                ),
            ),
        )
        cccd.incrementPending()
        cccd.markEnablePlanFailed()

        assertFalse(cccd.completePending())
        assertFalse("a failed subscription must not read as ready", cccd.ready.value)
    }

    // ── §11.5: cumulative continuity across a reconnect ──────────────────────────

    @Test
    fun `distance does not jump when a PM reconnects on a new link generation`() {
        // The PM's own counters restart from zero on a new session, and its elapsed-time prefix
        // restarts with them. A reconnect must therefore not be read as the machine suddenly
        // travelling backwards, nor as a fresh 2500 m appearing from nowhere.
        val state = BtEngineState()
        val fusion = MetricFusion(state).also { it.setPersona(FusionPersona.ROW_PADDLE) }

        fun emit(generation: Long, distanceMetres: Double, elapsedCs: Int, arrivalMs: Long) {
            val connection = ConnectionId("AA:BB:CC:DD:EE:C2", generation)
            val emitter = MetricEmitter(
                fusion = fusion,
                sourceId = MetricSourceId(
                    connection,
                    MetricProtocol.VENDOR,
                    BluetoothIds.UUID_C2_GENERAL_STATUS,
                ),
                sourceClass = MetricSourceClass.VENDOR_NATIVE,
                arrivalMs = arrivalMs,
            )
            val reading = Concept2Measurement.Reading(
                id = Concept2Measurement.Id.GENERAL_STATUS,
                elapsedCentiseconds = elapsedCs,
                distanceMetres = distanceMetres,
            )
            concept2Samples(reading).forEach {
                emitter.emit(it.metric, it.value, eventMs = it.eventMs, nativeCumulative = it.nativeCumulative)
            }
        }

        emit(generation = 1L, distanceMetres = 2_500.0, elapsedCs = 12_171, arrivalMs = 1_000L)
        assertEquals(2_500.0, fusion.latestSample(MetricKind.DISTANCE)!!.value, 1e-9)

        // Reconnect: new generation, counters back near zero.
        emit(generation = 2L, distanceMetres = 5.0, elapsedCs = 50, arrivalMs = 60_000L)

        val afterReconnect = fusion.latestSample(MetricKind.DISTANCE)!!
        assertEquals(
            "the parser reports what the machine sent; it must not fabricate continuity",
            5.0,
            afterReconnect.nativeCumulative!!,
            1e-9,
        )
        assertEquals(ConnectionId("AA:BB:CC:DD:EE:C2", 2L), afterReconnect.connectionId)
    }

    @Test
    fun `the elapsed prefix restarting does not disturb arrival-time freshness`() {
        // Event time is protocol time and restarts with the machine's session. Freshness runs
        // on arrival time, which does not — plan §0.6's rule, and the reason a reconnect cannot
        // make a fresh sample look stale.
        val fusion = MetricFusion(BtEngineState()).also { it.setPersona(FusionPersona.ROW_PADDLE) }
        val connection = ConnectionId("AA:BB:CC:DD:EE:C2", 2L)
        val emitter = MetricEmitter(
            fusion = fusion,
            sourceId = MetricSourceId(
                connection,
                MetricProtocol.VENDOR,
                BluetoothIds.UUID_C2_ADDITIONAL_STROKE_DATA,
            ),
            sourceClass = MetricSourceClass.VENDOR_NATIVE,
            arrivalMs = 90_000L,
        )

        // A frame whose protocol time is tiny, arriving late in the session.
        val reading = Concept2Measurement.additionalStrokeData(
            bytes(0x32, 0x00, 0x00, 0xCB, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        )!!
        concept2Samples(reading).forEach {
            emitter.emit(it.metric, it.value, eventMs = it.eventMs, nativeCumulative = it.nativeCumulative)
        }

        val sample = fusion.latestSample(MetricKind.POWER)!!
        assertEquals(203.0, sample.value, 1e-9)
        assertEquals("event time is the machine's clock", 500L, sample.eventMs)
        assertEquals("freshness uses arrival time", 90_000L, sample.arrivalMs)
    }
}
