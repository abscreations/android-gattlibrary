package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.peripheral.TargetCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SchwinnX70Uuids

/**
 * x70 production plumbing — plan §7, §11 obligation 0.
 *
 * The §11 audit's question is not "does the parser work" but "can a user find this machine and get
 * data from it". Every defect the Phase 0 and Phase 5b audits found had the same shape: a family
 * known to some layers and not others. This file walks the layers.
 */
class SchwinnX70PlumbingTest {

    private val factory = HandlerFactory.standard(enablePhase05HardwareTest = false)

    // ── The initialization command (V17) ─────────────────────────────────────────

    @Test
    fun `the initialisation command is QZ's literal and satisfies both framing rules`() {
        // Byte 0 is the total length and every octet sums to 0x100. Asserted rather than computed:
        // a command FitBridge generated would be a third value for a field where TMIW and QZ
        // already disagree and nothing can choose between them — the V13 mistake.
        assertEquals(
            listOf(0x07, 0x01, 0xD3, 0x00, 0x1F, 0x05, 0x01),
            SCHWINN_X70_INITIALISATION.map { it.toInt() and 0xFF },
        )
        assertTrue(SchwinnX70Measurement.isWellFormedCommand(SCHWINN_X70_INITIALISATION))
        assertEquals(0x100, SCHWINN_X70_INITIALISATION.sumOf { it.toInt() and 0xFF })
        assertEquals(SCHWINN_X70_INITIALISATION.size, SCHWINN_X70_INITIALISATION[0].toInt() and 0xFF)
    }

    @Test
    fun `TMIW's command is also well formed, which is why V17 stays open`() {
        // Both are structurally valid, so the framing rule cannot arbitrate between them. Only QZ's
        // was observed being accepted by a physical console, which is the whole basis for the
        // choice — and it is a weak one, recorded as such.
        val tmiw = byteArrayOf(0x05, 0x03, 0xD9.toByte(), 0x00, 0x1F)
        assertTrue(SchwinnX70Measurement.isWellFormedCommand(tmiw))
        assertFalse(
            "the two commands genuinely differ",
            tmiw.contentEquals(SCHWINN_X70_INITIALISATION),
        )
    }

    // ── Handler registration and ownership ───────────────────────────────────────

    @Test
    fun `the handler is constructed on every machine connection`() {
        // D44's regression guard. A handler registered in the factory but absent from
        // MACHINE_PROTOCOLS is buildable and never built — which is exactly what was true of
        // Concept2 until 2026-08-12.
        assertTrue(ProtocolKey.SCHWINN_X70 in MACHINE_PROTOCOLS)

        val handlers = MACHINE_PROTOCOLS.map(factory::create)
        assertEquals(
            "exactly one x70 handler per machine connection",
            1,
            handlers.count { it is SchwinnX70ProtocolHandler },
        )
    }

    @Test
    fun `the handler activates on its service and owns every characteristic it exposes`() {
        val active = activateMatchingProtocolHandlers(
            MACHINE_PROTOCOLS.map(factory::create),
            setOf(SchwinnX70Uuids.UUID_X70_SERVICE),
            offer = { true },
        )
        assertEquals(1, active.size)
        assertTrue(active.single() is SchwinnX70ProtocolHandler)

        val handler = active.single()
        assertEquals(setOf(SchwinnX70Uuids.UUID_X70_SERVICE), handler.primaryServices)
        assertEquals(
            setOf(
                SchwinnX70Uuids.UUID_X70_MEASUREMENT,
                SchwinnX70Uuids.UUID_X70_SUPPLEMENTAL,
                SchwinnX70Uuids.UUID_X70_SIBLING,
                SchwinnX70Uuids.UUID_X70_CONTROL,
            ),
            handler.extraCharacteristics,
        )

        // **This set is load-bearing for control safety, which is not obvious from here.** The
        // §6c/D26 assertion below derives the handler's writable surface by subtracting the notify
        // streams from it, so anything added here that is not a notify stream reads as writable. A
        // 2026-08-18 change widened this to all eight characteristics the service exposes — four of
        // which nobody decodes — and that test caught it. Keeping the size pinned makes the coupling
        // explicit rather than something the next reader has to rediscover by breaking it.
        assertEquals(
            "adding to this set changes what `never offered as a target` asserts — read that test first",
            4,
            handler.extraCharacteristics.size,
        )
    }

    @Test
    fun `an x70 is not offered as a sensor`() {
        // A machine protocol, like FTMS, Concept2 and Precor. Appearing in the secondary-sensor
        // path would let a user assign a whole exercise bike to a heart-rate slot.
        assertTrue(
            "no service type may map to the x70 handler",
            BtEngineState.BtServiceType.entries.none {
                sensorProtocolFor(it) == ProtocolKey.SCHWINN_X70
            },
        )
    }

    // ── Control: none, and none reachable (D26, §9.2) ────────────────────────────

    @Test
    fun `no FTMS control point is reachable through the x70 handler`() {
        // The `1717b3c0-…` write is setup — it starts a notification stream — and is deliberately
        // not a control seam. If the FTMS control point ever appeared in this handler's owned
        // characteristics, a downstream target request could route to a machine that has no way to
        // honour it and no capture showing what would happen if it tried.
        val handler = factory.create(ProtocolKey.SCHWINN_X70)
        assertFalse(
            FtmsUuids.UUID_FITNESS_MACHINE_CONTROL_POINT in handler.extraCharacteristics,
        )
        assertFalse(
            FtmsUuids.UUID_FTMS_SERVICE in handler.primaryServices,
        )
    }

    // ── §11 obligation 4: connection behaviour ──────────────────────────────────

    private fun mainFrames(): List<ByteArray> {
        val stream = javaClass.classLoader
            ?.getResourceAsStream("fixtures/hardware/schwinn_x70_270_main.json")
            ?: error("270 corpus missing")
        val fixture = FixtureJson.obj(FixtureJson.parse(stream.bufferedReader().use { it.readText() }))
        return FixtureJson.array(fixture["cases"]).map { case ->
            FixtureJson.array(FixtureJson.obj(case)["packet"])
                .map { FixtureJson.int(it).toByte() }
                .toByteArray()
        }
    }

    @Test
    fun `a reconnect resets every piece of link state`() {
        // The specific hazard for this family: link state includes a crank counter, a damping
        // baseline and an energy baseline, and all three are *differences*. A counter surviving a
        // reconnect would compute a cadence across two sessions and an unknown gap — a
        // plausible-looking number from two unrelated readings, which is worse than no number.
        val handler = SchwinnX70ProtocolHandler()
        val frames = mainFrames()

        // **Indices 4 and 5, not 0 and 1, and the reason is the 2026-08-18 cadence fix.** The crank
        // counter advances once per revolution while the stream notifies about ten times a second,
        // so most adjacent frames carry an unchanged counter and now correctly yield no cadence at
        // all. In this corpus the counter first moves between index 4 and index 5. Using two frames
        // that happen to be adjacent rather than two across which the machine turned was always
        // testing the wrong thing; it only passed because the old code invented a number.
        handler.samplesForNotification(SchwinnX70Uuids.UUID_X70_MEASUREMENT, frames[4], arrivalMs = 1_000L)
        val second = handler.samplesForNotification(SchwinnX70Uuids.UUID_X70_MEASUREMENT, frames[5], arrivalMs = 1_800L)
        assertTrue("a baseline existed and the crank turned, so metrics flow", second.any { it.metric == MetricKind.CADENCE })

        handler.resetLinkState()

        // After a reconnect the same frame must behave like the first frame of a link again.
        val afterReset = handler.samplesForNotification(SchwinnX70Uuids.UUID_X70_MEASUREMENT, frames[5], arrivalMs = 9_000L)
        // One revolution across 0.8 s is 75 rpm — a real pedalling rate, which is the point of
        // choosing a realistic interval rather than one that happens to satisfy the assertion.
        assertTrue(
            "no metric may be derived from a counter belonging to a previous link",
            afterReset.none { it.metric == MetricKind.CADENCE || it.metric == MetricKind.POWER },
        )
    }

    @Test
    fun `a malformed frame is counted and cleared by a reconnect`() {
        val handler = SchwinnX70ProtocolHandler()

        handler.samplesForNotification(
            SchwinnX70Uuids.UUID_X70_MEASUREMENT,
            byteArrayOf(0x11, 0x20, 0x00),
            arrivalMs = 0L,
        )
        assertEquals("a truncated frame is a rejection", 1, handler.rejectedFrameCount())

        handler.resetLinkState()
        assertEquals("a reconnect clears the count", 0, handler.rejectedFrameCount())
    }

    @Test
    fun `a notification on an unowned characteristic produces nothing and is not a rejection`() {
        // Stale-callback isolation at the handler: a frame for a characteristic this handler does
        // not own must not be decoded *and* must not inflate the rejection count, which exists to
        // measure this family's own malformed traffic.
        val handler = SchwinnX70ProtocolHandler()

        assertTrue(
            handler.samplesForNotification(UUID.randomUUID(), mainFrames()[0], arrivalMs = 0L).isEmpty(),
        )
        assertEquals(0, handler.rejectedFrameCount())
    }

    @Test
    fun `a 170's held supplemental frame does not leak across a reconnect`() {
        // The supplemental frame is held so that one tick emits one coherent set. Holding it across
        // a link would publish a heart rate from the previous session against the new one's speed.
        val handler = SchwinnX70ProtocolHandler()
        val supplemental = ByteArray(20).also { frame ->
            SchwinnX70Measurement.SUPPLEMENTAL_TAGS.forEach { (offset, tag) -> frame[offset] = tag.toByte() }
            frame[16] = 141.toByte()
        }
        val sibling = ByteArray(14).also { frame ->
            SchwinnX70Measurement.SIBLING_TAGS.forEach { (offset, tag) -> frame[offset] = tag.toByte() }
        }

        handler.samplesForNotification(SchwinnX70Uuids.UUID_X70_SUPPLEMENTAL, supplemental, arrivalMs = 0L)
        val withHeld = handler.samplesForNotification(SchwinnX70Uuids.UUID_X70_SIBLING, sibling, arrivalMs = 100L)
        assertEquals(141.0, withHeld.single { it.metric == MetricKind.HEART_RATE }.value, 1e-9)

        handler.resetLinkState()

        val afterReset = handler.samplesForNotification(SchwinnX70Uuids.UUID_X70_SIBLING, sibling, arrivalMs = 200L)
        assertTrue(
            "a heart rate from a dead link must not attach to a live one",
            afterReset.none { it.metric == MetricKind.HEART_RATE },
        )
    }

    @Test
    fun `a fresh handler carries no state from a previous link`() {
        // The factory builds one per connection, so this is really a guard that nobody makes the
        // handler a singleton later. Damping and cadence state are per-link by construction and
        // the construction is what is asserted.
        val first = factory.create(ProtocolKey.SCHWINN_X70) as SchwinnX70ProtocolHandler
        val second = factory.create(ProtocolKey.SCHWINN_X70) as SchwinnX70ProtocolHandler

        assertFalse("each connection gets its own handler", first === second)
        assertEquals(0, second.rejectedFrameCount())
    }

    @Test
    fun `x70 and FTMS handlers coexist on one link and route independently`() {
        // Not hypothetical for this family in the way it is for Precor: nothing stops a user
        // pairing an x70 while an FTMS machine is also connected, and both handlers are offered on
        // every machine connection. Each characteristic must reach exactly one owner.
        val ftms = FtmsProtocolHandler()
        val x70 = SchwinnX70ProtocolHandler()

        val active = activateMatchingProtocolHandlers(
            listOf(ftms, x70),
            setOf(FtmsUuids.UUID_FTMS_SERVICE, SchwinnX70Uuids.UUID_X70_SERVICE),
            offer = { true },
        )
        assertEquals(listOf(ftms, x70), active)

        for (characteristic in listOf(
            SchwinnX70Uuids.UUID_X70_MEASUREMENT,
            SchwinnX70Uuids.UUID_X70_SUPPLEMENTAL,
            SchwinnX70Uuids.UUID_X70_SIBLING,
        )) {
            val route = resolveProtocolHandlerRoute(active, characteristic, SchwinnX70Uuids.UUID_X70_SERVICE)
            assertEquals("$characteristic must route to the x70 alone", listOf(x70), route.owners)
            assertEquals(HandlerOwnership.EXPLICIT_CHARACTERISTIC, route.ownership)
        }

        assertEquals(
            listOf(ftms),
            resolveProtocolHandlerRoute(
                active,
                FtmsUuids.UUID_BIKING_DATA,
                FtmsUuids.UUID_FTMS_SERVICE,
            ).owners,
        )
    }

    @Test
    fun `a characteristic this handler does not own is not routed to it`() {
        // Stale-callback isolation at the routing layer: an unknown UUID must produce no owner at
        // all rather than defaulting to whichever handler is active.
        val active = listOf(SchwinnX70ProtocolHandler())
        assertNull(resolveProtocolHandlerRoute(active, UUID.randomUUID(), null).ownership)
    }

    @Test
    fun `only the x70 handler activates when only its service is present`() {
        val active = activateMatchingProtocolHandlers(
            MACHINE_PROTOCOLS.map(factory::create),
            setOf(SchwinnX70Uuids.UUID_X70_SERVICE),
            offer = { true },
        )
        assertEquals(1, active.size)
        assertTrue(active.single() is SchwinnX70ProtocolHandler)
    }

    // ── §11 obligation 8: the downstream capability row ─────────────────────────

    @Test
    fun `an x70-driven session advertises no target-setting capability`() {
        // §9.2 and D26. Neither console exposes a control point: the only writable characteristic
        // in the captured GATT is `1717b3c0-…`, and writing it starts a notification stream rather
        // than setting a target. The downstream row is empty by construction; this asserts the
        // construction, because "empty by construction" is exactly the claim that stops being true
        // when someone later adds a capability without re-reading §9.2.
        val capabilities = TargetCapabilities.fromEngineCapabilities(
            BtEngineState.FtmsCapabilities(featuresRead = true),
        )

        assertEquals(
            "not one target bit may be set for a machine FitBridge cannot control",
            0,
            capabilities.toTargetFeatureBits(),
        )
        assertFalse("no control surface may be offered", capabilities.any)
        assertFalse(capabilities.supportsResistance)
        assertFalse(capabilities.supportsPower)
        assertFalse(capabilities.indoorBikeSimulation)
    }

    @Test
    fun `resistance is read as an input to the model and never offered as a target`() {
        // The specific trap for this family. A 270 reports a resistance *level*, so the machine
        // plainly has a resistance mechanism — which makes "we can therefore set it" a short and
        // wrong step. Nothing in either capture shows a write that changes resistance, and QZ's
        // own `requestResistance` branch is commented out. Reading a setting is not controlling it.
        //
        // **This test earned its place on 2026-08-18.** A change widened `extraCharacteristics` to
        // every characteristic the service exposes, on the reasoning that ownership arms no CCCD
        // and so costs nothing. It cost this: four characteristics nobody understands became
        // indistinguishable, here, from a writable surface. The change was reverted.
        val handler = factory.create(ProtocolKey.SCHWINN_X70)
        assertEquals(
            "the setup write is the only characteristic this handler may write",
            setOf(SchwinnX70Uuids.UUID_X70_CONTROL),
            handler.extraCharacteristics - setOf(
                SchwinnX70Uuids.UUID_X70_MEASUREMENT,
                SchwinnX70Uuids.UUID_X70_SUPPLEMENTAL,
                SchwinnX70Uuids.UUID_X70_SIBLING,
            ),
        )
    }

    @Test
    fun `the persona is a bike for both consoles and does not depend on the name`() {
        // All three consoles report the identity `SCHWINN 170/270`, so a name-derived persona would
        // be the same for every one of them by accident rather than by evidence. This is a product
        // fact: every x70 is an upright bike.
        assertEquals(FusionPersona.RIDE, SchwinnX70ProtocolHandler().persona())
    }

    // ── Adapter policy ───────────────────────────────────────────────────────────

    @Test
    fun `the x70 adapter is a peer of the other proprietary adapters and needs no setup gate`() {
        assertEquals(
            "an x70 never competes with FTMS, so it ranks with the other vendor adapters",
            AdapterLivenessCoordinator.RANK_PRECOR,
            AdapterLivenessCoordinator.RANK_SCHWINN_X70,
        )
    }
}
