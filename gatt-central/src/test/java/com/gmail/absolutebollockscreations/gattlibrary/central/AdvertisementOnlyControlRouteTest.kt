package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Control routing for a machine that broadcasts and cannot receive (phase 11.5).
 *
 * The capture that prompted this showed a Keiser ride logging
 * `CP: requestControl ignored (controlPointPresent=false)`, `startOrResume(): CCCDs not ready`
 * and `did not gain control within timeout` — three warnings, sixteen seconds of waiting, and not
 * one of them describing a fault. A bike has no control point; that is the design, not a symptom.
 */
class AdvertisementOnlyControlRouteTest {

    @Test
    fun `Kayak First has no downstream FTMS control route`() {
        assertEquals(
            MachineControlRoute.UNAVAILABLE,
            machineControlRoute(
                AdapterLivenessCoordinator.KAYAK_FIRST_ADAPTER_ID,
                hasFeCHandler = false,
            ),
        )
    }

    @Test
    fun `a selected advertisement machine wins the route outright`() {
        assertEquals(
            MachineControlRoute.ADVERTISEMENT_ONLY,
            machineControlRoute(
                selectedAdapterId = null,
                hasFeCHandler = false,
                hasAdvertisementMachine = true,
            ),
        )
    }

    @Test
    fun `it outranks every live control surface, because selecting it tore theirs down`() {
        // §6.6 disconnects the GATT machine when a bike is selected. A connection context that has
        // not finished going away must not route a control write at a bike.
        for (adapterId in listOf(
            AdapterLivenessCoordinator.FTMS_ADAPTER_ID,
            AdapterLivenessCoordinator.FEC_ADAPTER_ID,
            AdapterLivenessCoordinator.WAHOO_KICKR_ADAPTER_ID,
            AdapterLivenessCoordinator.RIDEON_ADAPTER_ID,
        )) {
            assertEquals(
                adapterId,
                MachineControlRoute.ADVERTISEMENT_ONLY,
                machineControlRoute(
                    selectedAdapterId = adapterId,
                    hasFeCHandler = true,
                    hasWahooControl = true,
                    hasRideOnControl = true,
                    hasAdvertisementMachine = true,
                ),
            )
        }
    }

    @Test
    fun `with no bike selected every existing route is unchanged`() {
        assertEquals(
            MachineControlRoute.FTMS,
            machineControlRoute(selectedAdapterId = null, hasFeCHandler = false),
        )
        assertEquals(
            MachineControlRoute.FEC,
            machineControlRoute(
                selectedAdapterId = AdapterLivenessCoordinator.FEC_ADAPTER_ID,
                hasFeCHandler = true,
            ),
        )
    }

    @Test
    fun `the endpoint declines every command and says so by returning false`() {
        val endpoint = AdvertisementOnlyMachineControlEndpoint
        assertFalse(endpoint.requestControl())
        assertFalse(endpoint.startOrResume())
        assertFalse(endpoint.stopOrPause())
        assertFalse(endpoint.setTargetSpeedKph(10f))
        assertFalse(endpoint.setTargetInclinationPercent(1f))
        assertFalse(endpoint.setTargetResistance(5f))
        assertFalse(endpoint.setTargetPowerWatts(200))
        assertFalse(endpoint.setWheelCircumferenceMm(2105f))
        assertFalse(endpoint.setIndoorBikeSimulation(0f, 0f, 0f, 0f))
    }
}
