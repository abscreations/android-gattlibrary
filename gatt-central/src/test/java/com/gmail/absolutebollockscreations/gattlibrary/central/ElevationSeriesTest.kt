package com.gmail.absolutebollockscreations.gattlibrary.central

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
        assertEquals(2, state.series(MetricSeriesKind.ELEVATION_NET_METERS).size)
        assertEquals(17.0, state.series(MetricSeriesKind.ELEVATION_NET_METERS)[0].value, 0.0)
        assertEquals(19.0, state.series(MetricSeriesKind.ELEVATION_NET_METERS).last()!!.value, 0.0)
    }

    @Test
    fun `net elevation is ascent minus descent over time`() {
        val state = BtEngineState()
        state.setElevationGains(10.0, 0.0, tsMillis = 1_000L)
        state.setElevationGains(10.0, 4.0, tsMillis = 2_000L) // descending: net falls
        state.setElevationGains(10.0, 14.0, tsMillis = 3_000L) // below the start

        assertEquals(
            listOf(1_000L to 10.0, 2_000L to 6.0, 3_000L to -4.0),
            state.series(MetricSeriesKind.ELEVATION_NET_METERS).pairs(),
        )
    }

    @Test
    fun `an unchanged net is not appended again`() {
        val state = BtEngineState()
        state.setElevationGains(10.0, 2.0, tsMillis = 1_000L)
        state.setElevationGains(10.0, 2.0, tsMillis = 2_000L)
        state.setElevationGains(12.0, 4.0, tsMillis = 3_000L) // both moved, net still 8

        assertEquals(listOf(1_000L to 8.0), state.series(MetricSeriesKind.ELEVATION_NET_METERS).pairs())
    }

    @Test
    fun `no gains reported appends nothing`() {
        val state = BtEngineState()
        state.setElevationGains(null, null, tsMillis = 1_000L)
        assertEquals(true, state.series(MetricSeriesKind.ELEVATION_NET_METERS).isEmpty())
    }

    @Test
    fun `fusion feeds the net series from either gain and each gain keeps its own total`() {
        val connection = ConnectionId("AA:BB:CC:DD:EE:02", 9L)
        val characteristic = java.util.UUID.randomUUID()
        var clock = 1_785_000_000_000L
        var arrival = 10L
        val state = BtEngineState()
        val fusion = MetricFusion(state, seriesClock = MetricSeriesClock { clock })

        fun submit(metric: MetricKind, value: Double) {
            fusion.submit(
                MetricSample(
                    connectionId = connection,
                    sourceId = MetricSourceId(connection, MetricProtocol.FTMS, characteristic),
                    protocol = MetricProtocol.FTMS,
                    sourceClass = MetricSourceClass.FTMS,
                    metric = metric,
                    arrivalMs = arrival++,
                    value = value,
                    nativeCumulative = if (metric.cumulative) value else null,
                ),
            )
            clock += 1_000L
        }

        val t0 = clock
        submit(MetricKind.ELEVATION_GAIN_POSITIVE, 12.0)
        submit(MetricKind.ELEVATION_GAIN_NEGATIVE, 5.0)
        submit(MetricKind.ELEVATION_GAIN_POSITIVE, 20.0)

        assertEquals(20.0, state.elevationGainPos.value!!, 0.0)
        assertEquals(5.0, state.elevationGainNeg.value!!, 0.0)
        assertEquals(
            listOf(t0 to 12.0, t0 + 1_000 to 7.0, t0 + 2_000 to 15.0),
            state.series(MetricSeriesKind.ELEVATION_NET_METERS).pairs(),
        )
    }

    @Test
    fun `clearing session data empties the net series but keeps the gain totals`() {
        val state = BtEngineState()
        state.setElevationGains(10.0, 2.0, tsMillis = 1_000L)

        state.clearSessionData()

        assertEquals(true, state.series(MetricSeriesKind.ELEVATION_NET_METERS).isEmpty())
        assertEquals(10.0, state.elevationGainPos.value!!, 0.0)
        assertEquals(2.0, state.elevationGainNeg.value!!, 0.0)
    }
}

