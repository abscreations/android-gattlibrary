package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan §6/§16.8 matcher obligations for FE-C over BLE: positive, negative, no-name-needed, and
 * automatic scan-filter derivation.
 */
class FeCMatcherTest {

    private val registry = DeviceMatcherRegistry.standard()

    private fun uuid(u: java.util.UUID) = CanonicalUuid.fromUuid(u)

    private val fecService = uuid(BluetoothIds.UUID_FEC_SERVICE)
    private val ftmsService = uuid(BluetoothIds.UUID_FTMS_SERVICE)

    private fun candidates(observation: MatchObservation) = registry.rankedCandidates(observation)
    private fun idsOf(observation: MatchObservation) = candidates(observation).map { it.candidateId }

    @Test
    fun `an advertised FEC1 service matches the FE-C candidate`() {
        val ids = idsOf(MatchObservation(advertisedServiceUuids = setOf(fecService)))
        assertEquals(listOf("vendor.fec.ble"), ids)
    }

    @Test
    fun `FEC1 alone is decisive structural evidence, no local name needed`() {
        val match = candidates(
            MatchObservation(
                localName = null,
                discoveredServiceUuids = setOf(fecService),
            ),
        ).single()
        assertEquals("vendor.fec.ble", match.candidateId)
        assertTrue(
            "no name evidence should be required or contributed",
            match.evidence.facts.none { it.kind == MatchEvidenceKind.LOCAL_NAME_PATTERN },
        )
    }

    @Test
    fun `either FEC2 or FEC3 alone is enough to fingerprint the family`() {
        for (characteristic in listOf(BluetoothIds.UUID_FEC_MEASUREMENT, BluetoothIds.UUID_FEC_CONTROL)) {
            val ids = idsOf(MatchObservation(discoveredCharacteristicUuids = setOf(uuid(characteristic))))
            assertEquals("$characteristic should identify an FE-C device", listOf("vendor.fec.ble"), ids)
        }
    }

    @Test
    fun `a name alone never matches the family`() {
        assertTrue(idsOf(MatchObservation(localName = "FitBridge FE-C Rower")).isEmpty())
    }

    @Test
    fun `an unrelated machine does not match`() {
        val ids = idsOf(
            MatchObservation(localName = "Zwift Hub", advertisedServiceUuids = setOf(ftmsService)),
        )
        assertEquals(listOf("sig.ftms"), ids)
    }

    @Test
    fun `a device advertising both FTMS and FEC1 matches both candidates`() {
        val ids = idsOf(
            MatchObservation(advertisedServiceUuids = setOf(ftmsService, fecService)),
        )
        assertEquals(2, ids.size)
        assertTrue("sig.ftms" in ids)
        assertTrue("vendor.fec.ble" in ids)
    }

    @Test
    fun `a device advertising FEC1 and the Incyclist CPS overlay still ranks as the FE-C machine`() {
        // Regression test (2026-08-20): FeCBlePeripheralAdvertiser additionally advertises
        // the Cycling Power Service (0x1818) for the Trainer and Stationary Bike personas
        // (FeCEquipmentType.includesCpsOverlay), as an Incyclist compatibility overlay. Both
        // `vendor.fec.ble` and `sig.cps` produce a single ADVERTISED_SERVICE fact of equal
        // weight from that observation, and `sig.cps` used to win the tie on `preference`
        // alone (500 vs 120) - so `startScanImpl`'s `candidates.firstOrNull()` catalogued
        // these two personas as sensors, not machines, and they never appeared in a
        // machine-only device list. Treadmill and Rower carry no CPS overlay and were
        // unaffected, which is exactly the split that was reported.
        val cpsService = uuid(BluetoothIds.UUID_POWER_SERVICE)
        val ranked = candidates(
            MatchObservation(advertisedServiceUuids = setOf(fecService, cpsService)),
        )

        assertEquals(
            "the machine candidate must not be displaced by an equally-weighted sensor match",
            "vendor.fec.ble",
            ranked.first().candidateId,
        )
        assertTrue("the FE-C candidate is a machine", ranked.first().machineRole)
        assertTrue("sig.cps" in ranked.map { it.candidateId })
    }

    @Test
    fun `the FEC1 service is registered with no known SIG service`() {
        assertTrue(fecService in registry.registeredServices())
        assertNull(
            "FE-C over BLE has no KnownBleService; that enum is the SIG set",
            registry.knownService(fecService),
        )
    }

    @Test
    fun `the registered FEC1 UUID is the one the Tacx spec gives`() {
        assertEquals("6e40fec1-b5a3-f393-e0a9-e50e24dcca9e", fecService.value)
    }

    @Test
    fun `registering FEC1 automatically widens the derived hardware scan filter`() {
        // Plan §6.3: nothing besides registration should be required for the scanner to pick up
        // an FE-C-only advertisement - a Kinomap-style capture that carries only FEC1.
        assertTrue(
            "FEC1 must be derivable from the registry without a separate scan-filter list",
            fecService in registry.scanFilterServiceUuids(),
        )
    }
}
