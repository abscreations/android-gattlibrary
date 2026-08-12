package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every registered family must be reachable end to end. Plan §11, new obligation.
 *
 * ## Why this file exists, and why it is not a device test
 *
 * Three separate defects in two days shared one shape: code that decoded a device correctly and
 * could never be reached in production.
 *
 * 1. **D44** — `handlersForServices` named `ProtocolKey.FTMS` inline, so no machine connection
 *    ever constructed a Concept2 handler.
 * 2. **Classification** — the `0x0016` read decoded, stored and logged an erg family that no
 *    production caller consumed.
 * 3. **Discovery** — the OS scan filter was built from five hardcoded `KnownBleService` values,
 *    so a proprietary family could not be scanned for at all; and the matcher held the
 *    post-connection service UUID where the advertised one was needed; and `onScanResult`
 *    discarded any candidate without a `KnownBleService`; and `machineCandidate` was inferred
 *    from `knownService == FTMS`.
 *
 * Every one passed its own unit tests. The §11 obligations ask about parsers, subscriptions,
 * ownership, fusion and downstream encoding — each a question about a component in isolation.
 * None asks *can a user find this device and get data from it*, which is the only question a
 * user actually has.
 *
 * So this file tests the seams **between** components, driven by the registry rather than by a
 * list of families, so that adding a family without wiring it fails here rather than in the
 * field. It deliberately contains no byte-level decoding: that is covered per family elsewhere,
 * and duplicating it here would obscure what this file is for.
 */
class DeviceReachabilityTest {

    private val registry = DeviceMatcherRegistry.standard()

    private fun uuid(u: java.util.UUID) = CanonicalUuid.fromUuid(u)

    // ── Discovery ────────────────────────────────────────────────────────────────

    @Test
    fun `every registered family is present in the OS scan filter`() {
        // The defect this file was written for. A family absent here is invisible to the app
        // no matter how complete its adapter is.
        val filter = hardwareScanServiceUuids().map(CanonicalUuid::fromUuid).toSet()

        for (service in registry.scanFilterServiceUuids()) {
            assertTrue(
                "$service is registered but absent from the scan filter, so no device " +
                    "advertising it can ever be discovered",
                service in filter,
            )
        }
        assertEquals(
            "the filter must contain exactly the registry's advertised UUIDs",
            registry.scanFilterServiceUuids().toSet(),
            filter,
        )
    }

    @Test
    fun `the scan filter covers proprietary families, not only SIG profiles`() {
        val filter = hardwareScanServiceUuids().toSet()

        assertTrue("FTMS", BluetoothIds.UUID_FTMS_SERVICE in filter)
        assertTrue("Concept2 must be scannable", BluetoothIds.UUID_C2_DISCOVERY_SERVICE in filter)
        assertTrue("Precor must be scannable", BluetoothIds.UUID_PRECOR_SERVICE in filter)
    }

    @Test
    fun `both Concept2 advertised UUIDs are accepted, per V11`() {
        // The specification names the base UUID; TMIW matches the Erg service. Both are
        // accepted because both are exclusively Concept2's and nothing establishes that no
        // firmware advertises the Erg service. Narrowing to one would be a guess.
        for (advertised in listOf(
            BluetoothIds.UUID_C2_DISCOVERY_SERVICE,
            BluetoothIds.UUID_C2_ERG_SERVICE,
        )) {
            val ids = registry.rankedCandidates(
                MatchObservation(advertisedServiceUuids = setOf(uuid(advertised))),
            ).map { it.candidateId }
            assertEquals("advertised $advertised", listOf("vendor.concept2.pm"), ids)
            assertTrue("$advertised must be in the scan filter", advertised in hardwareScanServiceUuids())
        }
    }

    @Test
    fun `a PM5 advertises the C2 base UUID and is discovered by it`() {
        // The specification: "the mobile device application should use the proprietary C2 base
        // UUID, advertised in a Scan Response packet". FitBridge's own PM5 simulator advertises
        // this UUID, so before the fix FitBridge could not discover a device it was itself
        // advertising — the corroboration that should have caught this years earlier.
        val candidates = registry.rankedCandidates(
            MatchObservation(
                localName = "PM5 430343693",
                advertisedServiceUuids = setOf(uuid(BluetoothIds.UUID_C2_DISCOVERY_SERVICE)),
            ),
        )

        assertEquals(listOf("vendor.concept2.pm"), candidates.map { it.candidateId })
        assertTrue("a PM is a machine, not a sensor", candidates.single().machineRole)
    }

    @Test
    fun `the Erg service still matches after connecting`() {
        // The advertised UUID is additional to the served one, not a replacement. Post-discovery
        // matching and handler activation both key on the Erg service and must be unaffected.
        val candidates = registry.rankedCandidates(
            MatchObservation(
                discoveredServiceUuids = setOf(uuid(BluetoothIds.UUID_C2_ERG_SERVICE)),
            ),
        )

        assertEquals(listOf("vendor.concept2.pm"), candidates.map { it.candidateId })
    }

    // ── Machine role ─────────────────────────────────────────────────────────────

    @Test
    fun `machine role is declared, not inferred from a SIG enum`() {
        val expected = mapOf(
            BluetoothIds.UUID_FTMS_SERVICE to true,
            BluetoothIds.UUID_C2_DISCOVERY_SERVICE to true,
            BluetoothIds.UUID_PRECOR_SERVICE to true,
            BluetoothIds.UUID_HRM_SERVICE to false,
            BluetoothIds.UUID_CSC_SERVICE to false,
            BluetoothIds.UUID_RSC_SERVICE to false,
            BluetoothIds.UUID_CYCLING_POWER_SERVICE to false,
        )

        for ((service, isMachine) in expected) {
            val candidate = registry.rankedCandidates(
                MatchObservation(advertisedServiceUuids = setOf(uuid(service))),
            ).single()
            assertEquals("$service machine role", isMachine, candidate.machineRole)
        }
    }

    @Test
    fun `a sensor advertisement never becomes a machine candidate`() {
        val hrm = registry.rankedCandidates(
            MatchObservation(
                localName = "Garmin HRM-Pro",
                advertisedServiceUuids = setOf(uuid(BluetoothIds.UUID_HRM_SERVICE)),
            ),
        ).single()

        assertEquals("sig.hrm", hrm.candidateId)
        assertFalse(hrm.machineRole)
    }

    // ── Handler construction ─────────────────────────────────────────────────────

    @Test
    fun `every machine family has a constructible handler that activates on its service`() {
        // D44's regression guard, generalised: a family that is discovered, matched and
        // admitted must also have a handler that activates on the service it serves.
        val factory = HandlerFactory.standard(enablePhase05HardwareTest = false)
        val handlers = MACHINE_PROTOCOLS.map(factory::create)

        val machineServices = listOf(
            BluetoothIds.UUID_FTMS_SERVICE,
            BluetoothIds.UUID_C2_ERG_SERVICE,
            BluetoothIds.UUID_PRECOR_SERVICE,
        )

        for (service in machineServices) {
            val active = activateMatchingProtocolHandlers(
                handlers,
                setOf(service),
                offer = { true },
            )
            assertEquals(
                "$service must activate exactly one machine handler",
                1,
                active.size,
            )
        }
    }

    @Test
    fun `every registry service a machine serves has an adapter candidate`() {
        // Closes the loop: matcher, handler and AdapterLivenessCoordinator must agree about
        // which services identify a machine. A service known to two of the three is the shape
        // every defect above took.
        val adapterServices = setOf(
            BluetoothIds.UUID_FTMS_SERVICE,
            BluetoothIds.UUID_C2_ERG_SERVICE,
            BluetoothIds.UUID_PRECOR_SERVICE,
        )
        val handlerServices = HandlerFactory.standard(enablePhase05HardwareTest = false)
            .let { factory -> MACHINE_PROTOCOLS.map(factory::create) }
            .flatMap { it.primaryServices }
            .toSet()

        assertEquals(
            "the adapter candidates and the machine handlers must cover the same services",
            adapterServices,
            handlerServices,
        )
    }
}
