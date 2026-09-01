package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Selection routing and scanner-row metadata (plan §6.6, phase 11.5b).
 *
 * These are the two decisions in 11.5b that can be asserted without a radio. The rest of that unit
 * is Android-coupled plumbing — the scan callback, the lease-aware start/stop path, the liveness
 * tick — and its evidence is a compiling app plus the 11.5a state machine it feeds, not a test in
 * this file. Recorded plainly rather than dressed up.
 */
class MachineSelectionRouteTest {

    private fun row(
        transport: BtEngineState.DeviceTransport = BtEngineState.DeviceTransport.GATT,
        services: Set<BtEngineState.BtServiceType> = emptySet(),
    ) = BtEngineState.DiscoveredDevice(
        name = "M3 1234",
        address = "AA:BB:CC:DD:EE:FF",
        rssi = -55,
        lastSeenMs = 1L,
        scanResult = null,
        services = services,
        machineCandidate = true,
        transport = transport,
    )

    /** The vendor's documented 6.30 vector: system ID 0x38, firmware 6.30. */
    private val canonicalField = byteArrayOf(
        0x02, 0x01, 0x06, 0x30, 0x00, 0x38, 0x38, 0x03, 0x46, 0x05,
        0x73, 0x00, 0x0D, 0x00, 0x04, 0x27, 0x01, 0x00, 0x0A,
    )

    @Test
    fun `an advertisement-only row never routes to a GATT connect`() {
        // Calling connectFtms on a Keiser would open a ConnectionContext for a device that never
        // answers, stop the scan its telemetry depends on, and sit connecting until it timed out.
        assertEquals(
            MachineSelectionRoute.ADVERTISEMENT_SELECT,
            machineSelectionRoute(row(BtEngineState.DeviceTransport.ADVERTISEMENT_ONLY)),
        )
    }

    @Test
    fun `every GATT row keeps the path it had before phase 11`() {
        assertEquals(
            MachineSelectionRoute.GATT_CONNECT,
            machineSelectionRoute(row(BtEngineState.DeviceTransport.GATT)),
        )
        assertEquals(
            MachineSelectionRoute.GATT_CONNECT,
            machineSelectionRoute(
                row(services = setOf(BtEngineState.BtServiceType.FTMS)),
            ),
        )
    }

    @Test
    fun `an address the catalog has never seen still tries to connect`() {
        // Preserved deliberately: connectMachine on an unknown address has always simply tried, and
        // an unknown device is not evidence of an advertisement-only one. Guessing otherwise would
        // silently break the existing "connect to an address I already know" paths.
        assertEquals(MachineSelectionRoute.GATT_CONNECT, machineSelectionRoute(null))
    }

    // ── scanner row metadata ─────────────────────────────────────────────────────────────

    @Test
    fun `row metadata reads the system ID and firmware a rider can check against the console`() {
        val metadata = requireNotNull(
            keiserRowMetadata(
                mapOf(
                    KeiserMSeriesMeasurement.COMPANY_ID to ManufacturerPayload(canonicalField),
                ),
            ),
        )

        assertEquals(0x38, metadata.systemId)
        assertEquals("6.30", metadata.firmware)
    }

    @Test
    fun `row metadata comes from a review frame too, because identity is not telemetry`() {
        // A bike parked on its summary screen is still bike number seven running firmware 6.30. A
        // row that went blank while the rider caught their breath would be worse than useless.
        val review = canonicalField.copyOf().also { it[4] = 0xFF.toByte() }
        val metadata = requireNotNull(
            keiserRowMetadata(
                mapOf(KeiserMSeriesMeasurement.COMPANY_ID to ManufacturerPayload(review)),
            ),
        )

        assertEquals(0x38, metadata.systemId)
        assertEquals("6.30", metadata.firmware)
    }

    @Test
    fun `row metadata is absent for a wrong company, a malformed field, or no payload at all`() {
        assertNull(keiserRowMetadata(emptyMap()))
        assertNull(
            keiserRowMetadata(mapOf(0x0103 to ManufacturerPayload(canonicalField))),
        )
        assertNull(
            keiserRowMetadata(
                mapOf(
                    KeiserMSeriesMeasurement.COMPANY_ID to
                        ManufacturerPayload(canonicalField.copyOf(18)),
                ),
            ),
        )
    }
}
