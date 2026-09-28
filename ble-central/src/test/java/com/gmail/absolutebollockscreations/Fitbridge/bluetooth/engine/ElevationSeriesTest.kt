package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ElevationSeriesTest {

    @Test
    fun `net elevation subtracts the unsigned negative gain magnitude`() {
        assertEquals(25.0, netElevationChangeMeters(30.0, 5.0)!!, 0.0)
        assertEquals(30.0, netElevationChangeMeters(30.0, null)!!, 0.0)
        assertEquals(-5.0, netElevationChangeMeters(null, 5.0)!!, 0.0)
        assertNull(netElevationChangeMeters(null, null))
    }

    @Test
    fun `elevation updates retain component totals and populate net series`() {
        val state = BtEngineState()

        state.setElevationGains(20.0, 3.0, tsMillis = 1_000L)
        state.setElevationGains(24.0, 5.0, tsMillis = 2_000L)

        assertEquals(24.0, state.elevationGainPos.value!!, 0.0)
        assertEquals(5.0, state.elevationGainNeg.value!!, 0.0)
        assertEquals(2, state.elevationNetSeries.value.size)
        assertEquals(17.0, state.elevationNetSeries.value.first().v, 0.0)
        assertEquals(19.0, state.elevationNetSeries.value.last().v, 0.0)
    }
}
