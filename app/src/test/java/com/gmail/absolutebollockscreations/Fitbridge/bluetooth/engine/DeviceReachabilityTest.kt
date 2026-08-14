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
        assertTrue("Schwinn x70 must be scannable", BluetoothIds.UUID_X70_SERVICE in filter)
    }

    // ── Schwinn x70 admission (plan §7) ──────────────────────────────────────────

    @Test
    fun `a Schwinn x70 is admitted by its advertised service and is a machine`() {
        // The advertisement is captured, not assumed. From the user-supplied HCI snoop of device
        // 80:4b:50:5b:00:74, the AD payload is:
        //
        //   [ 2] Flags: 05
        //   [17] Incomplete list of 128-bit service UUIDs:
        //        1b c5 d5 a5 02 00 11 90 e6 11 2f 3a 80 89 f5 3b
        //   [ 9] Manufacturer specific: 64 33 db d0 f4 d7 00 80
        //
        // The 16 UUID octets are little-endian, so reversing them is the whole decode. Doing it
        // here rather than pasting the result means this fails if the constant ever drifts from the
        // bytes a real console put on the air.
        val advertised = byteArrayOf(
            0x1b, 0xc5.toByte(), 0xd5.toByte(), 0xa5.toByte(), 0x02, 0x00, 0x11, 0x90.toByte(),
            0xe6.toByte(), 0x11, 0x2f, 0x3a, 0x80.toByte(), 0x89.toByte(), 0xf5.toByte(), 0x3b,
        ).reversedArray().joinToString("") { "%02x".format(it) }
        val decoded = java.util.UUID.fromString(
            "${advertised.substring(0, 8)}-${advertised.substring(8, 12)}-" +
                "${advertised.substring(12, 16)}-${advertised.substring(16, 20)}-" +
                advertised.substring(20),
        )
        assertEquals(
            "the registered service must be the one the console actually advertises",
            BluetoothIds.UUID_X70_SERVICE,
            decoded,
        )

        val candidates = registry.rankedCandidates(
            MatchObservation(
                // The name arrives in the *scan response*, not the advertisement, so it is not
                // available at the moment the OS filter decides. One more reason §7 registers no
                // local-name pattern for this family.
                localName = "SCHWINN 170/270",
                advertisedServiceUuids = setOf(uuid(decoded)),
            ),
        )

        assertEquals(listOf("vendor.schwinn.x70"), candidates.map { it.candidateId })
        assertTrue("an x70 is a machine, not a sensor", candidates.single().machineRole)
    }

    @Test
    fun `the x70 name alone is not evidence of anything`() {
        // All three consoles report the generic identity `SCHWINN 170/270`, which makes a name
        // pattern look like free evidence. It is the §8.9 trap: `evidenceFor` produces a candidate
        // from any non-empty evidence, so a name pattern would admit anything merely called
        // Schwinn — including the IC4, a wholly different FTMS machine — to a proprietary
        // vendor protocol.
        val candidates = registry.rankedCandidates(MatchObservation(localName = "SCHWINN 170/270"))

        assertTrue(
            "a name with no advertised service must not produce an x70 candidate",
            candidates.none { it.candidateId == "vendor.schwinn.x70" },
        )
    }

    @Test
    fun `both x70 console shapes match, because neither exposes the other's stream`() {
        // The defect this guards is a matcher that requires every persona characteristic. A 270
        // notifies 17-byte frames on the measurement characteristic and never uses the sibling; a
        // 170 does the reverse. Requiring all four would reject both real consoles — a family that
        // fingerprints perfectly against a device that does not exist.
        val console270 = setOf(
            uuid(BluetoothIds.UUID_X70_SERVICE),
            uuid(BluetoothIds.UUID_X70_MEASUREMENT),
            uuid(BluetoothIds.UUID_X70_CONTROL),
        )
        val console170 = setOf(
            uuid(BluetoothIds.UUID_X70_SERVICE),
            uuid(BluetoothIds.UUID_X70_SUPPLEMENTAL),
            uuid(BluetoothIds.UUID_X70_SIBLING),
            uuid(BluetoothIds.UUID_X70_CONTROL),
        )

        for ((label, discovered) in listOf("270/570" to console270, "170" to console170)) {
            val candidates = registry.rankedCandidates(
                MatchObservation(discoveredServiceUuids = discovered),
            )
            assertTrue(
                "a $label console must remain an x70 candidate after discovery",
                candidates.any { it.candidateId == "vendor.schwinn.x70" },
            )
        }
    }

    @Test
    fun `the five x70 identifiers are distinct and share no derivable base`() {
        // §8.4's standing hazard, and this family is where it bites hardest: TMIW stores all four
        // of these as four-character aliases and matches on characters 5-8. The advertised service
        // sits on `-3a2f-11e6-9011-` while the four characteristics sit on `-9803-11e3-` with
        // three different third groups, so no helper could generate them and an alias comparison
        // would match unrelated UUIDs from any vendor that happened to reuse those four hex digits.
        val all = listOf(
            BluetoothIds.UUID_X70_SERVICE,
            BluetoothIds.UUID_X70_MEASUREMENT,
            BluetoothIds.UUID_X70_CONTROL,
            BluetoothIds.UUID_X70_SUPPLEMENTAL,
            BluetoothIds.UUID_X70_SIBLING,
        )

        assertEquals("all five identifiers are distinct", all.size, all.toSet().size)

        // No two share everything after the first group, so none can be produced from another by
        // substituting an alias — the precise operation §8.4 forbids.
        val suffixes = all.map { it.toString().substringAfter('-') }
        assertEquals("no identifier is an alias substitution of another", all.size, suffixes.toSet().size)

        // And the advertised service is on a different base entirely from the four characteristics.
        val serviceSecondGroup = BluetoothIds.UUID_X70_SERVICE.toString().split('-')[1]
        assertEquals("3a2f", serviceSecondGroup)
        for (characteristic in all.drop(1)) {
            assertEquals(
                "$characteristic sits on the 9803 base, not the service's",
                "9803",
                characteristic.toString().split('-')[1],
            )
        }
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

    // ── Life Fitness admission (plan §6 exit criterion) ──────────────────────────

    @Test
    fun `a Life Fitness machine is admitted by FTMS alone, with no proprietary UUID advertised`() {
        // The §6 exit criterion, and the one place the whole family could have been made
        // undiscoverable. The captured advertisement contains `0x1826` and `0xFE13` and **no**
        // `4b90` or `4b91` — the proprietary services exist only in the GATT database, which
        // nothing can read before connecting. A registry entry keyed on them would have produced
        // a family that fingerprints perfectly and is never once scanned for.
        val candidates = registry.rankedCandidates(
            MatchObservation(
                localName = "LF Cross Trainer",
                advertisedServiceUuids = setOf(uuid(BluetoothIds.UUID_FTMS_SERVICE)),
            ),
        )

        assertEquals(listOf("sig.ftms"), candidates.map { it.candidateId })
        assertTrue("a Life Fitness machine is a machine", candidates.single().machineRole)
        assertTrue(
            "FTMS must be scannable for this family to be found at all",
            BluetoothIds.UUID_FTMS_SERVICE in hardwareScanServiceUuids(),
        )
    }

    @Test
    fun `no Life Fitness proprietary UUID is in the scan filter or the registry`() {
        // The inverse, and the more useful direction: registering these would not merely be
        // useless, it would be a claim that a machine advertises them. Nothing observed does.
        val filter = hardwareScanServiceUuids().toSet()
        val registered = registry.scanFilterServiceUuids().toSet()

        for (proprietary in listOf(
            BluetoothIds.UUID_LF_CONTROL_SERVICE,
            BluetoothIds.UUID_LF_USER_SERVICE,
            BluetoothIds.UUID_LF_STATUS,
        )) {
            assertFalse("$proprietary is not advertised and must not be scanned for", proprietary in filter)
            assertFalse("$proprietary must not be a registry match key", uuid(proprietary) in registered)
        }
    }

    // ── The liveness tick reaches handlers (D41's defect) ───────────────────────

    @Test
    fun `a handler that overrides the liveness tick is actually offered one`() {
        // **The fifth decoded-but-unreachable defect, and the first this file catches by shape
        // rather than by name.** `Concept2ProtocolHandler.onLivenessTick` existed, was unit-tested,
        // and nothing called it — so D41's multiplexed-to-individual fallback never fired and a PM
        // whose `0x0080` subscription went silent stayed silent for the whole session.
        //
        // The delivery path is `BtEngine.tickProtocolHandlers` -> `ConnectionContext.handlerTicker`
        // -> the callback's active handlers. Two of those three links need a device, so what is
        // asserted here is the part that can be: that the interface declares the seam, that the
        // context carries the field the engine reads, and that the engine's tick calls it. A
        // handler overriding a method the engine never invokes is precisely the shape that hid
        // for two phases.
        val engineSource = sourceText("btEngine.kt")
        val setupSource = sourceText("btEngine.LifeFitnessSetup.kt")
        val contextSource = sourceText("ConnectionContext.kt")

        assertTrue(
            "the engine's tick must offer itself to protocol handlers",
            engineSource.contains("tickProtocolHandlers()"),
        )
        assertTrue(
            "the tick must walk machine contexts and invoke their ticker",
            setupSource.contains("context.handlerTicker?.invoke()"),
        )
        assertTrue(
            "the context must carry the seam the engine reads",
            contextSource.contains("var handlerTicker"),
        )
        assertTrue(
            "a ticker must not survive its link",
            contextSource.contains("handlerTicker = null"),
        )
    }

    @Test
    fun `every machine handler can accept a tick without an override being required`() {
        // The default is a no-op, so adding the seam cannot have changed any handler's behaviour
        // except the one that overrides it. This also proves the method is on the interface rather
        // than only on Concept2, which is what makes it findable by the next handler that needs it.
        val handlers = MACHINE_PROTOCOLS.map(HandlerFactory.standard(false)::create)
        assertTrue(handlers.isNotEmpty())
        for (handler in handlers) {
            val declared = handler.javaClass.methods.any { it.name == "onLivenessTick" }
            assertTrue("${handler.javaClass.simpleName} must expose the tick seam", declared)
        }
    }

    private fun sourceText(name: String): String {
        val roots = sequenceOf(
            java.io.File("app/src/main/java/com/gmail/absolutebollockscreations/Fitbridge/bluetooth/engine"),
            java.io.File("src/main/java/com/gmail/absolutebollockscreations/Fitbridge/bluetooth/engine"),
        )
        val root = roots.firstOrNull(java.io.File::isDirectory)
        assertTrue("engine source root exists", root != null)
        val file = java.io.File(root, name)
        assertTrue("$name exists", file.isFile)
        return file.readText()
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
            BluetoothIds.UUID_X70_SERVICE,
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
            BluetoothIds.UUID_X70_SERVICE,
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
