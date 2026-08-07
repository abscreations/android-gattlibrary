package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_HRM_SERVICE
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_RSC_FEATURE
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_RSC_MEASUREMENT
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_RSC_SERVICE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The RSC handler is reachable from production, not only from tests.
 *
 * The Phase 0 reachability audit found five components that were built, tested
 * and never constructed by anything shipping. This test exists so Phase 1's
 * handler cannot become the sixth: it asserts the factory can build it and that
 * service routing selects it, which is the part a unit test can reach. The GATT
 * callbacks themselves need hardware and are covered by the Phase 1 hardware
 * criteria.
 */
class RscHandlerRegistrationTest {

    @Test
    fun `the standard factory can build an RSC handler`() {
        val handler = HandlerFactory.standard().create(ProtocolKey.RSC)
        assertTrue(handler is RscProtocolHandler)
    }

    @Test
    fun `the handler claims the RSC service and its two characteristics`() {
        val handler = RscProtocolHandler()

        assertEquals(setOf(UUID_RSC_SERVICE), handler.primaryServices)
        assertTrue("must claim Measurement", UUID_RSC_MEASUREMENT in handler.extraCharacteristics)
        assertTrue("must claim Feature", UUID_RSC_FEATURE in handler.extraCharacteristics)
    }

    @Test
    fun `a footpod advertising only RSC activates the RSC handler`() {
        val activated = activateMatchingProtocolHandlers(
            candidates = listOf(RscProtocolHandler(), HrmProtocolHandler()),
            discoveredServiceUuids = setOf(UUID_RSC_SERVICE),
            offer = { true },
        )

        assertEquals(1, activated.size)
        assertTrue(activated.single() is RscProtocolHandler)
    }

    @Test
    fun `a running strap activates RSC and HRM on the same connection`() {
        // The Garmin HRM-Pro shape, and the reason Phase 0.5 exists. Both
        // handlers must activate on one link; neither may exclude the other.
        val activated = activateMatchingProtocolHandlers(
            candidates = listOf(RscProtocolHandler(), HrmProtocolHandler()),
            discoveredServiceUuids = setOf(UUID_RSC_SERVICE, UUID_HRM_SERVICE),
            offer = { true },
        )

        assertEquals(2, activated.size)
        assertTrue(activated.any { it is RscProtocolHandler })
        assertTrue(activated.any { it is HrmProtocolHandler })
    }

    @Test
    fun `a handler that declines discovery is not retained`() {
        // Declining matters for RSC specifically: a device advertising 0x1814
        // without 0x2A53 has nothing to stream, and holding the link open for it
        // would block whichever handler could have used the connection.
        val activated = activateMatchingProtocolHandlers(
            candidates = listOf(RscProtocolHandler()),
            discoveredServiceUuids = setOf(UUID_RSC_SERVICE),
            offer = { false },
        )

        assertTrue(activated.isEmpty())
    }

    @Test
    fun `RSC is not offered to a device that does not advertise it`() {
        val activated = activateMatchingProtocolHandlers(
            candidates = listOf(RscProtocolHandler()),
            discoveredServiceUuids = setOf(UUID_HRM_SERVICE),
            offer = { true },
        )

        assertTrue(activated.isEmpty())
    }
}
