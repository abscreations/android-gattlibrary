package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.ActivityType
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.TargetCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

import org.junit.Test
import java.util.UUID

/**
 * Precor Chrono registration, matching, ownership and control honesty — plan §5b, §11.1,
 * §11.4, §11.5, §11.8.
 */
class PrecorProductionPlumbingTest {

    // ── §11.1 matcher and evidence ───────────────────────────────────────────────

    private val registry = DeviceMatcherRegistry.standard()

    private fun uuid(u: UUID) = CanonicalUuid.fromUuid(u)

    private fun idsOf(observation: MatchObservation) =
        registry.rankedCandidates(observation).map { it.candidateId }

    @Test
    fun `an advertised Precor service matches the Chrono candidate and nothing else`() {
        val ids = idsOf(
            MatchObservation(
                advertisedServiceUuids = setOf(uuid(BluetoothIds.UUID_PRECOR_SERVICE)),
            ),
        )

        assertEquals(listOf("vendor.precor.chrono"), ids)
    }

    @Test
    fun `the measurement characteristic fingerprints the family and the name contributes nothing`() {
        val match = registry.rankedCandidates(
            MatchObservation(
                localName = "CHRONO",
                discoveredServiceUuids = setOf(uuid(BluetoothIds.UUID_PRECOR_SERVICE)),
                discoveredCharacteristicUuids = setOf(
                    uuid(BluetoothIds.UUID_PRECOR_MEASUREMENT),
                ),
            ),
        ).single()

        assertEquals("vendor.precor.chrono", match.candidateId)
        assertTrue(
            match.evidence.facts.any { it.kind == MatchEvidenceKind.GATT_SERVICE },
        )
        assertTrue(
            "no name evidence contributes to this match",
            match.evidence.facts.none { it.kind == MatchEvidenceKind.LOCAL_NAME_PATTERN },
        )
    }

    @Test
    fun `the name alone matches nothing`() {
        // §5b: "Name alone is far too weak", and §8.9 forbids a name-restricted generic
        // profile. `evidenceFor` produces a candidate from any non-empty evidence, so this
        // passes only because `precorDefinition` carries no name pattern at all — a
        // `^CHRONO` pattern would make each of these a weight-10 Precor candidate.
        assertTrue(idsOf(MatchObservation(localName = "CHRONO")).isEmpty())
        assertTrue(idsOf(MatchObservation(localName = "Chrono Watch Pro")).isEmpty())
        assertTrue(idsOf(MatchObservation(localName = "CHRONO 1234")).isEmpty())
    }

    @Test
    fun `a True treadmill sharing the service produces no decoded data`() {
        // QZ's `truetreadmill` uses this exact service and characteristic pair for a different
        // machine, so the matcher legitimately claims it — the UUIDs are genuinely present.
        val ids = idsOf(
            MatchObservation(
                localName = "ASSAULT TREADMILL 1",
                discoveredServiceUuids = setOf(uuid(BluetoothIds.UUID_PRECOR_SERVICE)),
            ),
        )
        assertEquals(listOf("vendor.precor.chrono"), ids)

        // The prefix gate is what stops the collision becoming wrong data. These are QZ's
        // documented True treadmill frame lengths; none carries `53 59 16`, and the 19-octet
        // case is the one that matters because length alone would admit it.
        for (length in listOf(4, 16, 18, 19)) {
            val trueFrame = ByteArray(length) { (it * 7 + 1).toByte() }
            assertNull(
                "a $length-octet True treadmill frame must not decode as a Chrono frame",
                PrecorMeasurement.parse(trueFrame),
            )
        }
    }

    @Test
    fun `a Concept2 device does not match the Precor candidate`() {
        val ids = idsOf(
            MatchObservation(
                localName = "PM5 430343693",
                advertisedServiceUuids = setOf(uuid(BluetoothIds.UUID_C2_ERG_SERVICE)),
            ),
        )

        assertFalse("vendor.precor.chrono" in ids)
    }

    @Test
    fun `FTMS and Precor coexist as matcher candidates with standard FTMS first`() {
        val matches = registry.rankedCandidates(
            MatchObservation(
                advertisedServiceUuids = setOf(
                    uuid(BluetoothIds.UUID_FTMS_SERVICE),
                    uuid(BluetoothIds.UUID_PRECOR_SERVICE),
                ),
                discoveredServiceUuids = setOf(
                    uuid(BluetoothIds.UUID_FTMS_SERVICE),
                    uuid(BluetoothIds.UUID_PRECOR_SERVICE),
                ),
                discoveredCharacteristicUuids = setOf(
                    uuid(BluetoothIds.UUID_BIKING_DATA),
                    uuid(BluetoothIds.UUID_PRECOR_MEASUREMENT),
                ),
            ),
        )

        assertEquals(listOf("sig.ftms", "vendor.precor.chrono"), matches.map { it.candidateId })
        assertTrue(matches.all { it.machineRole })
        assertTrue(
            "the standard service remains D6's first choice while the proprietary path stays live",
            matches.first().preference > matches.last().preference,
        )
    }

    @Test
    fun `the Precor UUIDs are the ones recorded in plan section 10`() {
        // A UUID typo compiles, passes every parser test, and produces a device that never
        // connects. Pinning both strings is the cheapest possible guard against it.
        assertEquals(
            "a026ee07-0a7d-4ab3-97fa-f1500f9feb8b",
            BluetoothIds.UUID_PRECOR_SERVICE.toString(),
        )
        assertEquals(
            "a026e01d-0a7d-4ab3-97fa-f1500f9feb8b",
            BluetoothIds.UUID_PRECOR_MEASUREMENT.toString(),
        )
    }

    // ── §11.4 / §11.5 handler registration and ownership ─────────────────────────

    @Test
    fun `the factory builds a fresh Precor handler each time`() {
        val factory = HandlerFactory.standard(enablePhase05HardwareTest = false)
        val first = factory.create(ProtocolKey.PRECOR)
        val second = factory.create(ProtocolKey.PRECOR)

        assertTrue(first is PrecorProtocolHandler)
        assertNotSame("per-link state must not be shared between connections", first, second)
    }

    @Test
    fun `Precor is a machine protocol, not a secondary sensor`() {
        for (type in BtEngineState.BtServiceType.values()) {
            assertTrue(
                "$type must not route to the Precor handler as a sensor",
                sensorProtocolFor(type) != ProtocolKey.PRECOR,
            )
        }
    }

    @Test
    fun `every machine protocol is constructible from the shared list`() {
        // The regression guard for the gap this phase found: MACHINE_PROTOCOLS is what a
        // machine connection builds from, so a key listed there without a registered factory
        // would throw at connect time on real hardware and nowhere else.
        val factory = HandlerFactory.standard(enablePhase05HardwareTest = false)

        // `create` throws `IllegalArgumentException` for an unregistered key, so simply
        // calling it for every entry is the assertion. An `is ProtocolHandler` check here
        // would compile, read as a test, and verify only the return type the signature
        // already guarantees — the compiler flags it as always true, and it is.
        val handlers = MACHINE_PROTOCOLS.map(factory::create)

        assertEquals(
            "each machine protocol must build a distinct handler implementation",
            MACHINE_PROTOCOLS.size,
            handlers.map { it::class.java.name }.distinct().size,
        )
        assertTrue(ProtocolKey.FTMS in MACHINE_PROTOCOLS)
        assertTrue(ProtocolKey.CONCEPT2 in MACHINE_PROTOCOLS)
        assertTrue(ProtocolKey.PRECOR in MACHINE_PROTOCOLS)
    }

    @Test
    fun `only the handler whose service is present activates`() {
        val factory = HandlerFactory.standard(enablePhase05HardwareTest = false)
        val candidates = MACHINE_PROTOCOLS.map(factory::create)

        val onChrono = activateMatchingProtocolHandlers(
            candidates,
            setOf(BluetoothIds.UUID_PRECOR_SERVICE),
            offer = { true },
        )
        assertEquals(1, onChrono.size)
        assertTrue(onChrono.single() is PrecorProtocolHandler)

        // And the converse: offering Precor on every machine link must not disturb FTMS.
        val onTreadmill = activateMatchingProtocolHandlers(
            candidates,
            setOf(BluetoothIds.UUID_FTMS_SERVICE),
            offer = { true },
        )
        assertEquals(1, onTreadmill.size)
        assertTrue(onTreadmill.single() is FtmsProtocolHandler)
    }

    @Test
    fun `FTMS and Precor handlers can activate on the same physical link`() {
        val ftms = FtmsProtocolHandler()
        val precor = PrecorProtocolHandler()

        val active = activateMatchingProtocolHandlers(
            listOf(ftms, precor),
            setOf(BluetoothIds.UUID_FTMS_SERVICE, BluetoothIds.UUID_PRECOR_SERVICE),
            offer = { true },
        )

        assertEquals(listOf(ftms, precor), active)
        assertEquals(
            listOf(precor),
            resolveProtocolHandlerRoute(
                active,
                BluetoothIds.UUID_PRECOR_MEASUREMENT,
                BluetoothIds.UUID_PRECOR_SERVICE,
            ).owners,
        )
        assertEquals(
            listOf(ftms),
            resolveProtocolHandlerRoute(
                active,
                BluetoothIds.UUID_BIKING_DATA,
                BluetoothIds.UUID_FTMS_SERVICE,
            ).owners,
        )
    }

    @Test
    fun `the measurement characteristic routes to the Precor handler alone`() {
        val precor = PrecorProtocolHandler()
        val ftms = FtmsProtocolHandler()
        val active = listOf(ftms, precor)

        val route = resolveProtocolHandlerRoute(
            active,
            BluetoothIds.UUID_PRECOR_MEASUREMENT,
            BluetoothIds.UUID_PRECOR_SERVICE,
        )
        assertEquals(listOf(precor), route.owners)
        assertEquals(HandlerOwnership.EXPLICIT_CHARACTERISTIC, route.ownership)

        assertNull(resolveProtocolHandlerRoute(active, UUID.randomUUID(), null).ownership)
    }

    // ── §11.8 control honesty ────────────────────────────────────────────────────

    @Test
    fun `the Precor handler owns no writable characteristic`() {
        val precor = PrecorProtocolHandler()

        assertEquals(setOf(BluetoothIds.UUID_PRECOR_MEASUREMENT), precor.extraCharacteristics)
        assertFalse(
            "no FTMS control point may be reachable through a proprietary measurement handler",
            BluetoothIds.UUID_FITNESS_MACHINE_CONTROL_POINT in precor.extraCharacteristics,
        )
    }

    @Test
    fun `a Precor-driven session advertises no target-setting capability`() {
        // §9.2 and D26. The Chrono exposes no control point in either reference project, and
        // even if it did, D26 keeps control hardware-gated for a family FitBridge cannot test.
        // The downstream row is empty by construction; this asserts the construction.
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
    fun `the persona is a bike and does not depend on the advertised name`() {
        assertEquals(FusionPersona.RIDE, PrecorProtocolHandler().persona())
    }

    @Test
    fun `a validated Chrono becomes visibly a biking device`() {
        val address = "C0:00:00:00:00:01"
        val state = BtEngineState()
        state.putDiscoveredDevices(
            mapOf(
                address to BtEngineState.DiscoveredDevice(
                    name = "CHRONO",
                    address = address,
                    rssi = -50,
                    lastSeenMs = 1,
                    scanResult = null,
                ),
            ),
        )

        PrecorProtocolHandler().publishDeviceClassification(state, address)

        assertEquals(
            ActivityType.BIKING.ordinal,
            state.discoveredDevices.value.getValue(address).machineFeature,
        )
    }

    @Test
    fun `a fresh handler has counted no rejected frames`() {
        assertEquals(0, PrecorProtocolHandler().rejectedFrameCount())
    }

    @Test
    fun `a reconnect resets all rejected-frame state`() {
        val handler = PrecorProtocolHandler()
        handler.recordRejectedFrame()
        handler.recordRejectedFrame()
        assertEquals(2, handler.rejectedFrameCount())

        handler.resetLinkState()

        assertEquals(0, handler.rejectedFrameCount())
    }

    @Test
    fun `captured auxiliary notification is ignored without becoming a rejection`() {
        val handler = PrecorProtocolHandler()

        assertNull(handler.decodeNotification(byteArrayOf(0x24, 0x08, 0x00)))
        assertEquals(0, handler.rejectedFrameCount())

        assertNull(handler.decodeNotification(byteArrayOf(0x24, 0x08, 0x01)))
        assertEquals(1, handler.rejectedFrameCount())
    }

    // ── Adapter candidacy ────────────────────────────────────────────────────────

    @Test
    fun `Precor and Concept2 rank equally and neither outranks FTMS`() {
        assertEquals(AdapterLivenessCoordinator.RANK_CONCEPT2, AdapterLivenessCoordinator.RANK_PRECOR)
        assertTrue(AdapterLivenessCoordinator.RANK_FTMS > AdapterLivenessCoordinator.RANK_PRECOR)
    }
}
