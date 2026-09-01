package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The advertisement-only source's state machine, on a fake clock (plan §6.5, phase 11.5).
 *
 * Every timing assertion here is a plain number. The behavior under test *is* timing — a
 * one-second statistics window, a seven-second one, a sixty-second disappearance — and a test that
 * waited real seconds to prove them would be slow, flaky, and no more truthful. The source reads no
 * clock of its own for exactly this reason.
 */
class KeiserAdvertisementSourceTest {

    private val leases = ScanLeaseRegistry()
    private val state = BtEngineState()
    private val fusion = MetricFusion(state)
    private var leaseReleases = 0
    private var leaseAcquisitions = 0
    private val source = KeiserAdvertisementSource(
        fusion = fusion,
        leases = leases,
        onScanLeaseAcquired = { leaseAcquisitions++ },
        onScanLeaseReleased = { leaseReleases++ },
    )

    private val address = "AA:BB:CC:DD:EE:FF"
    private val otherAddress = "11:22:33:44:55:66"
    private val selection = KeiserSelection(address, systemId = 0x38)

    /** The vendor's documented 6.30 vector: system ID 0x38, fast firmware, 115 W, 82.4 rpm. */
    private val fastFirmware = byteArrayOf(
        0x02, 0x01, 0x06, 0x30, 0x00, 0x38, 0x38, 0x03, 0x46, 0x05,
        0x73, 0x00, 0x0D, 0x00, 0x04, 0x27, 0x01, 0x00, 0x0A,
    )

    private fun packet(
        systemId: Int = 0x38,
        dataType: Int = 0x00,
        firmwareMinor: Int = 0x30,
        powerWatts: Int = 115,
    ): ByteArray = fastFirmware.copyOf().also {
        it[3] = firmwareMinor.toByte()
        it[4] = dataType.toByte()
        it[5] = systemId.toByte()
        it[10] = (powerWatts and 0xFF).toByte()
        it[11] = ((powerWatts shr 8) and 0xFF).toByte()
    }

    private fun advertisement(
        from: String = address,
        bytes: ByteArray = packet(),
    ) = KeiserAdvertisement(from, ManufacturerPayload(bytes))

    // ── selection and generations ────────────────────────────────────────────────────────

    @Test
    fun `selection mints a generation, pins the machine origin, and takes the scan lease`() {
        assertEquals(KeiserSourceState.IDLE, source.state())
        assertFalse(leases.isHeld())

        val origin = source.select(selection, nowMs = 1_000L)

        assertEquals(selection.sourceKey, origin.sourceKey)
        assertEquals(1L, origin.generation)
        assertEquals(KeiserSourceState.LIVE, source.state())
        assertEquals(origin, fusion.selectedMachineOrigin())
        assertTrue(leases.holds(ScanLeaseOwner.ADVERTISEMENT_MACHINE))
        assertEquals(1, leaseAcquisitions)
    }

    @Test
    fun `source publishes live stale and removal presentation states`() {
        val snapshots = mutableListOf<KeiserSourceSnapshot?>()
        val observed = KeiserAdvertisementSource(
            fusion = MetricFusion(BtEngineState()),
            leases = ScanLeaseRegistry(),
            onSourceStateChanged = snapshots::add,
        )

        observed.select(selection, nowMs = 0L)
        observed.onAdvertisement(advertisement(), arrivalMs = 0L)
        observed.tick(1_001L)
        observed.tick(KeiserMSeriesMeasurement.BIKE_GONE_TIMEOUT_MS)

        assertEquals(KeiserSourceState.LIVE, snapshots[0]?.state)
        assertEquals("6.30", snapshots[1]?.firmware)
        assertEquals(KeiserSourceState.STALE, snapshots[2]?.state)
        assertEquals(KeiserSourceState.GONE, snapshots[3]?.state)
        assertNull(snapshots.last())
    }

    @Test
    fun `reselecting the same bike is a new generation, not a continuation`() {
        val first = source.select(selection, nowMs = 0L)
        source.onAdvertisement(advertisement(), arrivalMs = 10L)

        val second = source.select(selection, nowMs = 100L)

        assertEquals(first.sourceKey, second.sourceKey)
        assertNotEquals(first.generation, second.generation)
        assertEquals(2L, second.generation)
        // One lease throughout. Releasing and re-acquiring would stop and restart the radio
        // between two selections, for no reason a user could observe except a gap in telemetry.
        assertEquals(1, leaseAcquisitions)
        assertEquals(0, leaseReleases)
        assertTrue(leases.holds(ScanLeaseOwner.ADVERTISEMENT_MACHINE))
    }

    @Test
    fun `the source key carries the system ID so two bikes never share a fusion origin`() {
        val bikeOne = KeiserSelection(address, systemId = 0)
        val bikeTwo = KeiserSelection(address, systemId = 1)

        assertNotEquals(bikeOne.sourceKey, bikeTwo.sourceKey)
        assertThrows(IllegalArgumentException::class.java) { KeiserSelection("", 0) }
        assertThrows(IllegalArgumentException::class.java) { KeiserSelection(address, 0x100) }
    }

    // ── admission ────────────────────────────────────────────────────────────────────────

    @Test
    fun `an accepted advertisement publishes metrics under the advertisement origin`() {
        val origin = source.select(selection, nowMs = 0L)

        assertEquals(
            KeiserAdvertisementOutcome.ACCEPTED,
            source.onAdvertisement(advertisement(), arrivalMs = 10L),
        )

        val powerSource = requireNotNull(fusion.selectedSource(MetricKind.POWER))
        assertEquals(origin, powerSource.originId)
        assertEquals(MetricProtocol.VENDOR, powerSource.protocol)
        assertEquals(
            MetricEndpointId.ManufacturerData(KeiserMSeriesMeasurement.COMPANY_ID),
            powerSource.endpointId,
        )
        assertNull("the protocol has no speed field", fusion.selectedSource(MetricKind.SPEED))
        assertEquals("6.30", source.firmware())
    }

    @Test
    fun `repeated identical advertisements are all delivered`() {
        // Telemetry delivery must not deduplicate by address or payload: a Keiser holding a steady
        // effort sends the same nineteen bytes over and over, and each one is a fresh liveness
        // proof even when its content has not changed.
        source.select(selection, nowMs = 0L)

        for (arrival in listOf(10L, 320L, 640L, 960L)) {
            assertEquals(
                "arrival $arrival",
                KeiserAdvertisementOutcome.ACCEPTED,
                source.onAdvertisement(advertisement(), arrivalMs = arrival),
            )
        }
        source.tick(1_500L)

        assertEquals(KeiserSourceState.LIVE, source.state())
    }

    @Test
    fun `another broadcaster, another system ID, review, and malformed are all ignored`() {
        source.select(selection, nowMs = 0L)

        assertEquals(
            KeiserAdvertisementOutcome.IGNORED_OTHER_ADDRESS,
            source.onAdvertisement(advertisement(from = otherAddress), arrivalMs = 10L),
        )
        assertEquals(
            KeiserAdvertisementOutcome.IGNORED_OTHER_SYSTEM_ID,
            source.onAdvertisement(advertisement(bytes = packet(systemId = 0x39)), arrivalMs = 11L),
        )
        assertEquals(
            KeiserAdvertisementOutcome.IGNORED_REVIEW,
            source.onAdvertisement(advertisement(bytes = packet(dataType = 0xFF)), arrivalMs = 12L),
        )
        assertEquals(
            KeiserAdvertisementOutcome.IGNORED_MALFORMED,
            source.onAdvertisement(
                advertisement(bytes = packet().copyOf(18)),
                arrivalMs = 13L,
            ),
        )
    }

    @Test
    fun `nothing but an accepted packet refreshes the statistics clock`() {
        // The corpus shows a bike sending seventy consecutive review frames while displaying a ride
        // summary. If those refreshed liveness the source would stay alive forever, publishing
        // nothing.
        source.select(selection, nowMs = 0L)
        source.onAdvertisement(advertisement(), arrivalMs = 0L)

        source.onAdvertisement(advertisement(bytes = packet(dataType = 0xFF)), arrivalMs = 900L)
        source.onAdvertisement(advertisement(from = otherAddress), arrivalMs = 950L)
        source.onAdvertisement(advertisement(bytes = packet(systemId = 0x39)), arrivalMs = 980L)
        source.tick(1_001L)

        assertEquals(KeiserSourceState.STALE, source.state())
    }

    // ── statistics window ────────────────────────────────────────────────────────────────

    @Test
    fun `fast firmware expires after one second and its boundary is inclusive`() {
        source.select(selection, nowMs = 0L)
        source.onAdvertisement(advertisement(bytes = packet(firmwareMinor = 0x30)), arrivalMs = 0L)

        source.tick(1_000L)
        assertEquals(KeiserSourceState.LIVE, source.state())

        source.tick(1_001L)
        assertEquals(KeiserSourceState.STALE, source.state())
        assertNull(fusion.selectedSource(MetricKind.POWER))
    }

    @Test
    fun `old firmware keeps its values for seven seconds`() {
        source.select(selection, nowMs = 0L)
        source.onAdvertisement(advertisement(bytes = packet(firmwareMinor = 0x22)), arrivalMs = 0L)

        source.tick(7_000L)
        assertEquals(KeiserSourceState.LIVE, source.state())

        source.tick(7_001L)
        assertEquals(KeiserSourceState.STALE, source.state())
    }

    @Test
    fun `a stale source recovers on the next accepted advertisement`() {
        source.select(selection, nowMs = 0L)
        source.onAdvertisement(advertisement(), arrivalMs = 0L)
        source.tick(2_000L)
        assertEquals(KeiserSourceState.STALE, source.state())

        assertEquals(
            KeiserAdvertisementOutcome.ACCEPTED,
            source.onAdvertisement(advertisement(), arrivalMs = 2_100L),
        )

        assertEquals(KeiserSourceState.LIVE, source.state())
        assertEquals(1L, requireNotNull(source.originId()).generation)
    }

    @Test
    fun `an out-of-order arrival cannot drag the statistics clock backwards`() {
        source.select(selection, nowMs = 0L)
        source.onAdvertisement(advertisement(), arrivalMs = 5_000L)
        source.onAdvertisement(advertisement(), arrivalMs = 4_000L)

        source.tick(5_500L)

        assertEquals(KeiserSourceState.LIVE, source.state())
    }

    // ── disappearance ────────────────────────────────────────────────────────────────────

    @Test
    fun `sixty seconds of silence ends the origin, clears fusion, and releases the lease`() {
        val origin = source.select(selection, nowMs = 0L)
        source.onAdvertisement(advertisement(), arrivalMs = 0L)
        assertEquals(origin, fusion.selectedMachineOrigin())

        source.tick(59_999L)
        assertEquals(KeiserSourceState.STALE, source.state())
        assertTrue(leases.holds(ScanLeaseOwner.ADVERTISEMENT_MACHINE))

        source.tick(60_000L)

        assertEquals(KeiserSourceState.GONE, source.state())
        assertNull(source.selection())
        assertNull(fusion.selectedMachineOrigin())
        assertNull(fusion.selectedSource(MetricKind.POWER))
        assertFalse(leases.holds(ScanLeaseOwner.ADVERTISEMENT_MACHINE))
        assertEquals(1, leaseReleases)
    }

    @Test
    fun `a late callback from an ended generation is rejected`() {
        source.select(selection, nowMs = 0L)
        source.onAdvertisement(advertisement(), arrivalMs = 0L)
        source.tick(60_000L)
        assertEquals(KeiserSourceState.GONE, source.state())

        assertEquals(
            KeiserAdvertisementOutcome.IGNORED_NOT_ACTIVE,
            source.onAdvertisement(advertisement(), arrivalMs = 60_001L),
        )
        assertNull(fusion.selectedSource(MetricKind.POWER))
    }

    @Test
    fun `an advertisement before any selection is rejected`() {
        assertEquals(
            KeiserAdvertisementOutcome.IGNORED_NOT_ACTIVE,
            source.onAdvertisement(advertisement(), arrivalMs = 10L),
        )
    }

    @Test
    fun `ending is idempotent and ticking an ended source does nothing`() {
        source.select(selection, nowMs = 0L)
        source.onAdvertisement(advertisement(), arrivalMs = 0L)

        source.end(KeiserEndReason.DESELECTED, nowMs = 100L)
        source.end(KeiserEndReason.DESELECTED, nowMs = 200L)
        source.tick(100_000L)

        assertEquals(KeiserSourceState.IDLE, source.state())
        assertEquals(1, leaseReleases)
        assertFalse(leases.isHeld())
    }

    @Test
    fun `a bike that comes back is a new generation whose cumulative counters rebase`() {
        // The same rebasing a reconnected GATT link gets, and for the same reason: the bike's own
        // distance counter is continuous, the session's is not obliged to be.
        source.select(selection, nowMs = 0L)
        source.onAdvertisement(advertisement(), arrivalMs = 0L)
        val firstDistance = state.totalDistanceMeters.value

        source.tick(60_000L)
        val second = source.select(selection, nowMs = 61_000L)
        source.onAdvertisement(advertisement(), arrivalMs = 61_000L)

        assertEquals(2L, second.generation)
        assertEquals(KeiserSourceState.LIVE, source.state())
        // One bike, one advertisement each side of the gap: the total must not have doubled.
        assertEquals(firstDistance, state.totalDistanceMeters.value)
    }

    // ── two bikes in one room ────────────────────────────────────────────────────────────

    @Test
    fun `selecting the other bike ends the first generation before starting the second`() {
        val first = source.select(KeiserSelection(address, systemId = 0x38), nowMs = 0L)
        source.onAdvertisement(advertisement(), arrivalMs = 0L)
        assertEquals(first, fusion.selectedMachineOrigin())

        val second = source.select(KeiserSelection(otherAddress, systemId = 0x39), nowMs = 100L)

        assertNotEquals(first.sourceKey, second.sourceKey)
        assertEquals(second, fusion.selectedMachineOrigin())
        assertNull("the first bike's samples left with its origin", fusion.selectedSource(MetricKind.POWER))
        // The lease was never released, so the scan did not stop between the two selections.
        assertEquals(0, leaseReleases)

        assertEquals(
            KeiserAdvertisementOutcome.IGNORED_OTHER_ADDRESS,
            source.onAdvertisement(advertisement(from = address), arrivalMs = 110L),
        )
        assertEquals(
            KeiserAdvertisementOutcome.ACCEPTED,
            source.onAdvertisement(
                advertisement(from = otherAddress, bytes = packet(systemId = 0x39)),
                arrivalMs = 120L,
            ),
        )
    }

    // ── nothing that looks like a connection ─────────────────────────────────────────────

    @Test
    fun `selecting a Keiser opens no link and registers no connected device`() {
        // The §11.5 gate, asserted through the state a connection would have to create.
        source.select(selection, nowMs = 0L)
        source.onAdvertisement(advertisement(), arrivalMs = 0L)

        assertTrue(state.connectedDevices.value.isEmpty())
        // selectedMachine() is the GATT-only accessor: a Keiser must be invisible to it, because
        // anything reading it is asking about a link.
        assertNull(fusion.selectedMachine())
        assertTrue(
            requireNotNull(fusion.selectedSource(MetricKind.POWER)).originId
                is MetricOriginId.Advertisement,
        )
    }
}
