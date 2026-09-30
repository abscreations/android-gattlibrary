package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids

class PrecorDiscoveryPlanTest {
    @Test
    fun `discovery declines when the Precor service is absent`() {
        assertTrue(
            planPrecorDiscovery(
                mapOf(FtmsUuids.UUID_FTMS_SERVICE to setOf(FtmsUuids.UUID_BIKING_DATA)),
            ).isEmpty(),
        )
    }

    @Test
    fun `discovery declines when the Precor measurement characteristic is absent`() {
        assertTrue(
            planPrecorDiscovery(
                mapOf(BluetoothIds.UUID_PRECOR_SERVICE to setOf(UUID.randomUUID())),
            ).isEmpty(),
        )
    }

    @Test
    fun `discovery subscribes only to the measurement despite unknown inventory`() {
        val extraService = UUID.randomUUID()
        val extraCharacteristic = UUID.randomUUID()

        assertEquals(
            listOf(
                CccdTarget(
                    BluetoothIds.UUID_PRECOR_SERVICE,
                    BluetoothIds.UUID_PRECOR_MEASUREMENT,
                ),
            ),
            planPrecorDiscovery(
                linkedMapOf(
                    extraService to setOf(extraCharacteristic),
                    BluetoothIds.UUID_PRECOR_SERVICE to linkedSetOf(
                        extraCharacteristic,
                        BluetoothIds.UUID_PRECOR_MEASUREMENT,
                    ),
                ),
            ),
        )
    }
}
