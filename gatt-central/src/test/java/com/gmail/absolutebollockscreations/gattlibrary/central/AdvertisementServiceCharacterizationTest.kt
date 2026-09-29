package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Test

class AdvertisementServiceCharacterizationTest {

    @Test
    fun `HRM plus RSC advertisement retains both services on discovered device`() {
        val services = advertisedServiceTypes(
            advertisedUuids = setOf(
                BluetoothIds.UUID_HRM_SERVICE,
                BluetoothIds.UUID_RSC_SERVICE,
            ),
        )
        val device = BtEngineState.DiscoveredDevice(
            name = "Garmin HRM-Pro",
            address = "AA:BB:CC:DD:EE:FF",
            rssi = -55,
            lastSeenMs = 1L,
            scanResult = null,
            services = services,
        )

        assertEquals(
            linkedSetOf(
                BtEngineState.BtServiceType.HeartRate,
                BtEngineState.BtServiceType.RSC,
            ),
            device.services,
        )
    }

    @Test
    fun `service data contributes a service tag when UUID list omits it`() {
        assertEquals(
            setOf(BtEngineState.BtServiceType.RSC),
            advertisedServiceTypes(
                advertisedUuids = emptySet(),
                serviceDataUuids = setOf(BluetoothIds.UUID_RSC_SERVICE),
            ),
        )
    }

    @Test
    fun `RSC-only blank-name observation remains visible to software classification`() {
        val candidates = rankedServiceCandidates(
            MatchObservation(
                localName = "",
                advertisedServiceUuids = setOf(
                    CanonicalUuid.fromUuid(BluetoothIds.UUID_RSC_SERVICE),
                ),
            ),
        )

        assertEquals(KnownBleService.RSC, candidates.first().knownService)
    }
}
