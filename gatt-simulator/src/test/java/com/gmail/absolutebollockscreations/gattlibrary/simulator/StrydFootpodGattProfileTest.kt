package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids

class StrydFootpodGattProfileTest {

    @Test
    fun `device information identifies the persona as Stryd`() {
        assertEquals("Stryd", StrydFootpodIdentity.MANUFACTURER_NAME)
        assertArrayEquals(
            "Stryd".toByteArray(Charsets.UTF_8),
            requireNotNull(
                StrydFootpodGattProfile.staticReadValues()[SigUuids.UUID_MANUFACTURER_NAME],
            ),
        )
    }

    @Test
    fun `captured proprietary notification endpoints are part of the GATT plan`() {
        val endpoints = StrydFootpodGattProfile.notifyingCharacteristics.toSet()

        assertEquals(
            setOf(
                StrydFootpodGattProfile.UUID_PROPRIETARY_SERVICE_A to
                    StrydFootpodGattProfile.UUID_PROPRIETARY_MEASUREMENT_A,
                StrydFootpodGattProfile.UUID_PROPRIETARY_SERVICE_B to
                    StrydFootpodGattProfile.UUID_PROPRIETARY_MEASUREMENT_B,
            ),
            endpoints.filter { (service, _) ->
                service == StrydFootpodGattProfile.UUID_PROPRIETARY_SERVICE_A ||
                    service == StrydFootpodGattProfile.UUID_PROPRIETARY_SERVICE_B
            }.toSet(),
        )
    }
}
