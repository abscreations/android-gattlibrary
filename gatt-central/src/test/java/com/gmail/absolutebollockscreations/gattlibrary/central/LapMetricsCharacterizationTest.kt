package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/**
 * Pins what fusion writes into the lap fields. Written against the pre-LIB-39 code first (the canonical
 * values and the display values), then reduced to the canonical ones when the display-unit lap fields were
 * removed; `lapPaceMinPerKm` is the old metric `lapRunPaceMinPerUnit`, which is exactly what the pre-change
 * run pinned (6.2150403977 min/km).
 */
class LapMetricsCharacterizationTest {
    private val connection = ConnectionId("AA:BB:CC:DD:EE:01", 7L)

    private fun sample(metric: MetricKind, value: Double, arrivalMs: Long, characteristic: UUID = UUID.randomUUID()) =
        MetricSample(
            connectionId = connection,
            sourceId = MetricSourceId(connection, MetricProtocol.FTMS, characteristic),
            protocol = MetricProtocol.FTMS,
            sourceClass = MetricSourceClass.FTMS,
            metric = metric,
            arrivalMs = arrivalMs,
            value = value,
            nativeCumulative = if (metric.cumulative) value else null,
        )

    @Test fun `canonical lap values for 1609 m in 600 s`() {
        val s = BtEngineState()
        val fusion = MetricFusion(s)
        fusion.submit(sample(MetricKind.DISTANCE, 1609.0, arrivalMs = 100))
        fusion.updateSessionElapsed(600, arrivalMs = 101)

        assertEquals(1609, s.lapDistanceMeters.value)
        assertEquals(600, s.lapElapsedTimeSec.value)
        assertEquals(2.6816666666, s.lapAvgSpeedMps.value!!, 1e-9)
        assertEquals(6.2150403977, s.lapPaceMinPerKm.value!!, 1e-9)
        assertEquals(3.1075201988, s.lapRowPaceMinPer500m.value!!, 1e-9)
    }

    @Test fun `no lap pace or speed before lap elapsed time is known`() {
        val state = BtEngineState()
        MetricFusion(state).submit(sample(MetricKind.DISTANCE, 1609.0, arrivalMs = 100))
        assertEquals(1609, state.lapDistanceMeters.value)
        assertNull(state.lapAvgSpeedMps.value)
        assertNull(state.lapPaceMinPerKm.value)
    }

    @Test fun `rower instant lap speed replaces the full lap average after two spaced distance samples`() {
        var seriesNowMs = 10_000L
        val state = BtEngineState()
        val fusion = MetricFusion(state, seriesClock = MetricSeriesClock { seriesNowMs })
        fusion.updateSessionElapsed(100, arrivalMs = 99)

        fusion.submit(sample(MetricKind.DISTANCE, 100.0, 100, FtmsUuids.UUID_ROWING_MACHINE_DATA))
        // One distance sample is too few for a windowed speed, so the speed is cleared.
        assertNull(state.lapAvgSpeedMps.value)

        seriesNowMs = 13_000L
        fusion.submit(sample(MetricKind.DISTANCE, 112.0, 101, FtmsUuids.UUID_ROWING_MACHINE_DATA))

        assertEquals(112, state.lapDistanceMeters.value)
        assertEquals(4.0, state.lapAvgSpeedMps.value!!, 1e-9) // 12 m over 3 s
    }
}
