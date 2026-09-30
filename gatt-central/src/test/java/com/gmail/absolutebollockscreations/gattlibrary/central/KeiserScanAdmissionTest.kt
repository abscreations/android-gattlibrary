package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids

/**
 * Scan admission for the first advertisement-only family (plan §6.4, phase 11.4).
 *
 * The question this file exists to answer is not "does a Keiser match" but "does *only* a Keiser
 * match, and does registering it leave every other family exactly where it was". A false
 * advertisement-only admission has no later correction: nothing connects, so service discovery
 * never gets to disagree, and the device would simply sit in the machine list producing decoded
 * nonsense from another vendor's payload.
 */
class KeiserScanAdmissionTest {

    private val registry = DeviceMatcherRegistry.standard()
    private val keiserCandidateId = "vendor.keiser.m"

    /** The vendor's documented 6.30 parse example, as it appears on the air. */
    private val canonicalField = byteArrayOf(
        0x02, 0x01, 0x06, 0x30, 0x00, 0x38, 0x38, 0x03, 0x46, 0x05,
        0x73, 0x00, 0x0D, 0x00, 0x04, 0x27, 0x01, 0x00, 0x0A,
    )

    /** What Android's ScanRecord actually hands over: the same field minus the company ID. */
    private val androidValue = canonicalField.copyOfRange(2, canonicalField.size)

    private fun observation(
        localName: String? = "M3 1234",
        companyId: Int = KeiserMSeriesMeasurement.COMPANY_ID,
        payload: ByteArray? = canonicalField,
    ): MatchObservation {
        val payloads = payload?.let { mapOf(companyId to ManufacturerPayload(it)) }.orEmpty()
        return MatchObservation(
            localName = localName,
            manufacturerCompanyIds = payloads.keys,
            manufacturerPayloads = payloads,
        )
    }

    private fun keiser(observation: MatchObservation): RankedMatchCandidate? =
        registry.rankedCandidates(observation).firstOrNull { it.candidateId == keiserCandidateId }

    // ── the conjunction ──────────────────────────────────────────────────────────────────

    @Test
    fun `name, company, and payload together admit a Keiser as a machine with no service UUID`() {
        val candidate = requireNotNull(keiser(observation()))

        assertNull("phase 11 forbids inventing a Keiser service UUID", candidate.serviceUuid)
        assertNull(candidate.knownService)
        assertTrue(candidate.machineRole)
        assertEquals(MatchTransport.ADVERTISEMENT_ONLY, candidate.transport)
        assertEquals(ActivityType.BIKING, candidate.advertisedActivityType)
        assertEquals(
            setOf(
                MatchEvidenceKind.LOCAL_NAME_PATTERN,
                MatchEvidenceKind.MANUFACTURER_COMPANY_ID,
                MatchEvidenceKind.MANUFACTURER_PAYLOAD,
            ),
            candidate.evidence.facts.mapTo(mutableSetOf()) { it.kind },
        )
    }

    @Test
    fun `no two of the three facts are enough on their own`() {
        // Name plus company, payload missing: a Keiser-branded anything.
        assertNull(keiser(observation(payload = null)))
        // Company plus payload, wrong name: the name is the cheapest fact and still required,
        // because company plus a nineteen-byte buffer is a shape another Keiser product could share.
        assertNull(keiser(observation(localName = "Rower 7")))
        // Name plus payload under the wrong company: neither the company nor the payload fact is
        // produced, since the rule is keyed on the company the definition declares.
        assertNull(keiser(observation(companyId = 0x0103)))
    }

    @Test
    fun `a malformed payload is rejected even with the right name and company`() {
        assertNull("truncated", keiser(observation(payload = canonicalField.copyOf(18))))
        assertNull("trailing byte", keiser(observation(payload = canonicalField + 0xAA.toByte())))
        assertNull(
            "bad magic",
            keiser(observation(payload = canonicalField.copyOf().also { it[0] = 0x03 })),
        )
        assertNull("empty", keiser(observation(payload = ByteArray(0))))
    }

    @Test
    fun `the payload rule is the decoder's own predicate, not a second copy of it`() {
        // A structural rule that could drift from the parser would admit devices the parser then
        // rejects, which a user sees as a machine that appears in the list and never shows a number.
        for (length in 0..canonicalField.size) {
            val truncated = canonicalField.copyOf(length)
            assertEquals(
                "length $length",
                KeiserMSeriesMeasurement.isKeiserField(truncated),
                keiser(observation(payload = truncated)) != null,
            )
        }
    }

    // ── Android normalization ────────────────────────────────────────────────────────────

    @Test
    fun `Android's split manufacturer data is canonicalized before it reaches the matcher`() {
        assertEquals(17, androidValue.size)

        val canonicalized = ManufacturerPayload.fromAndroidScanRecord(
            KeiserMSeriesMeasurement.COMPANY_ID,
            androidValue,
        )

        assertEquals(19, canonicalized.size)
        assertTrue(canonicalField.contentEquals(canonicalized.copyBytes()))
        assertTrue(KeiserMSeriesMeasurement.isKeiserField(canonicalized.copyBytes()))
        assertNotNull(keiser(observation(payload = canonicalized.copyBytes())))

        // And the un-normalized value must not match, which is the whole reason the step exists.
        assertFalse(KeiserMSeriesMeasurement.isKeiserField(androidValue))
        assertNull(keiser(observation(payload = androidValue)))
    }

    @Test
    fun `a canonicalized payload never renders its own bytes`() {
        // A manufacturer payload can carry a serial number or live telemetry, so it must not be
        // able to reach a log line by being interpolated into a string.
        val rendered = ManufacturerPayload(canonicalField).toString()

        assertEquals("ManufacturerPayload(19 bytes)", rendered)
        for (byte in canonicalField) {
            assertFalse(rendered.contains("%02x".format(byte)))
        }
    }

    @Test
    fun `payload bytes are copied in and out so no caller can mutate a match`() {
        val mutable = canonicalField.copyOf()
        val payload = ManufacturerPayload(mutable)
        mutable[0] = 0x00

        assertTrue(canonicalField.contentEquals(payload.copyBytes()))
        payload.copyBytes()[0] = 0x00
        assertTrue(canonicalField.contentEquals(payload.copyBytes()))
    }

    // ── hardware filters ─────────────────────────────────────────────────────────────────

    @Test
    fun `the hardware filter carries a manufacturer spec for Keiser and cannot drift`() {
        val specs = hardwareScanFilterSpecs()

        assertTrue(
            "a Keiser puts no service UUID on the air, so only a manufacturer filter reaches it",
            HardwareScanFilterSpec.ManufacturerId(KeiserMSeriesMeasurement.COMPANY_ID) in specs,
        )
        assertEquals(
            "the spec list is derived from the registry and must not drift from it",
            registry.hardwareScanFilterSpecs(),
            specs,
        )
        assertEquals(
            "service specs must remain exactly the registry's advertised UUIDs",
            registry.scanFilterServiceUuids().toSet(),
            specs.filterIsInstance<HardwareScanFilterSpec.ServiceUuid>().mapTo(mutableSetOf()) {
                it.uuid
            },
        )
    }

    @Test
    fun `only advertisement-only families contribute a manufacturer filter`() {
        // A GATT family is already reachable through its service UUID. Adding its company
        // identifier would widen the radio filter to every product that company ships.
        assertEquals(
            listOf(HardwareScanFilterSpec.ManufacturerId(KeiserMSeriesMeasurement.COMPANY_ID)),
            hardwareScanFilterSpecs().filterIsInstance<HardwareScanFilterSpec.ManufacturerId>(),
        )
    }

    // ── coexistence ──────────────────────────────────────────────────────────────────────

    @Test
    fun `an FTMS machine and a Keiser bike are both admitted in the same scan`() {
        // The case the plan calls out: with another supported machine present, the wide-scan
        // fallback that an empty filter list would provide is gone, so Keiser must be reachable
        // on its own filter and must not displace or be displaced by the FTMS device.
        val ftms = MatchObservation(
            localName = "Some Trainer",
            advertisedServiceUuids = setOf(
                CanonicalUuid.fromUuid(FtmsUuids.UUID_FTMS_SERVICE),
            ),
        )

        val ftmsPrimary = registry.rankedCandidates(ftms).first()
        val keiserPrimary = registry.rankedCandidates(observation()).first()

        assertEquals("sig.ftms", ftmsPrimary.candidateId)
        assertEquals(MatchTransport.GATT, ftmsPrimary.transport)
        assertNull(keiser(ftms))

        assertEquals(keiserCandidateId, keiserPrimary.candidateId)
        assertEquals(MatchTransport.ADVERTISEMENT_ONLY, keiserPrimary.transport)

        val specs = hardwareScanFilterSpecs()
        assertTrue(
            HardwareScanFilterSpec.ServiceUuid(
                CanonicalUuid.fromUuid(FtmsUuids.UUID_FTMS_SERVICE),
            ) in specs,
        )
        assertTrue(
            HardwareScanFilterSpec.ManufacturerId(KeiserMSeriesMeasurement.COMPANY_ID) in specs,
        )
    }

    @Test
    fun `registering an advertisement-only family leaves every other ranking where it was`() {
        val cases = mapOf(
            "sig.ftms" to FtmsUuids.UUID_FTMS_SERVICE,
            "sig.cps" to BluetoothIds.UUID_CYCLING_POWER_SERVICE,
            "sig.hrm" to BluetoothIds.UUID_HRM_SERVICE,
            "sig.csc" to BluetoothIds.UUID_CSC_SERVICE,
            "sig.rsc" to BluetoothIds.UUID_RSC_SERVICE,
        )

        for ((expected, service) in cases) {
            val ranked = registry.rankedCandidates(
                MatchObservation(
                    advertisedServiceUuids = setOf(CanonicalUuid.fromUuid(service)),
                ),
            )
            assertEquals(service.toString(), expected, ranked.first().candidateId)
            assertTrue(ranked.none { it.candidateId == keiserCandidateId })
        }
    }

    @Test
    fun `every registered family still declares a transport and only Keiser is advertisement-only`() {
        val advertisementOnly = registry.hardwareScanFilterSpecs()
            .filterIsInstance<HardwareScanFilterSpec.ManufacturerId>()

        assertEquals(1, advertisementOnly.size)
        assertEquals(MatchTransport.GATT, registry.rankedCandidates(
            MatchObservation(
                advertisedServiceUuids = setOf(
                    CanonicalUuid.fromUuid(FtmsUuids.UUID_FTMS_SERVICE),
                ),
            ),
        ).first().transport)
    }

    // ── definition invariants ────────────────────────────────────────────────────────────

    @Test
    fun `an advertisement-only definition cannot smuggle in a service UUID or omit its payload rule`() {
        assertThrows(IllegalArgumentException::class.java) {
            MatchDefinition(
                candidateId = "test.no.rule",
                serviceUuid = null,
                transport = MatchTransport.ADVERTISEMENT_ONLY,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MatchDefinition(
                candidateId = "test.gatt.without.service",
                serviceUuid = null,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MatchDefinition(
                candidateId = "test.advertised.uuid.back.door",
                serviceUuid = null,
                transport = MatchTransport.ADVERTISEMENT_ONLY,
                manufacturerPayloadRule = { _, _ -> true },
                advertisedServiceUuids = setOf(
                    CanonicalUuid.fromUuid(FtmsUuids.UUID_FTMS_SERVICE),
                ),
            )
        }
    }

    @Test
    fun `a family that contributes no hardware filter cannot be registered at all`() {
        // The 2026-08-12 defect, promoted from a test to a construction-time invariant: a family
        // with no filter never reaches the scan callback, and the failure is silent.
        val unreachable = MatchDefinition(
            candidateId = "test.unreachable",
            serviceUuid = null,
            transport = MatchTransport.ADVERTISEMENT_ONLY,
            manufacturerPayloadRule = { _, _ -> true },
        )

        assertTrue(unreachable.hardwareScanFilterSpecs.isEmpty())
        val failure = assertThrows(IllegalStateException::class.java) {
            DeviceMatcherRegistry.of(listOf(unreachable))
        }
        assertTrue(failure.message.orEmpty().contains("test.unreachable"))
    }

    @Test
    fun `a canonicalizing constructor rejects a company identifier that cannot be on the wire`() {
        assertThrows(IllegalArgumentException::class.java) {
            ManufacturerPayload.fromAndroidScanRecord(0x1_0000, androidValue)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HardwareScanFilterSpec.ManufacturerId(-1)
        }
    }
}
