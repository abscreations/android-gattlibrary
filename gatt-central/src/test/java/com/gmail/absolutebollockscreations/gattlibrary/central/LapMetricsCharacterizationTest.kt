package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/**
 * Pins what fusion writes into the lap fields today (LIB-39 step 1, before the display-unit lap fields are
 * removed). Canonical values (`lapAvgSpeedMps`, `lapRowPaceMinPer500m`, lap distance and elapsed) must
 * survive that change unchanged; the display-unit ones (`lapDistanceDisplay`, `lapAvgSpeedDisplay`,
 * `lapRunPaceMinPerUnit`) are what the change deletes, so their cases are rewritten against the canonical
 * `lapPaceMinPerKm` afterwards.
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

    /** 1609 m in 600 s of lap time: 2.68167 m/s. */
    private fun lapState(units: Int): BtEngineState {
        val state = BtEngineState().apply { setUnits(units) }
        val fusion = MetricFusion(state)
        fusion.submit(sample(MetricKind.DISTANCE, 1609.0, arrivalMs = 100))
        fusion.updateSessionElapsed(600, arrivalMs = 101)
        return state
    }

    @Test fun `canonical lap values do not depend on units`() {
        for (units in listOf(0, 1)) {
            val s = lapState(units)
            assertEquals(1609, s.lapDistanceMeters.value)
            assertEquals(600, s.lapElapsedTimeSec.value)
            assertEquals(2.6816666666, s.lapAvgSpeedMps.value!!, 1e-9)
            assertEquals(3.1075201988, s.lapRowPaceMinPer500m.value!!, 1e-9)
        }
    }

    @Test fun `metric lap display values`() {
        val s = lapState(0)
        assertEquals(1.609, s.lapDistanceDisplay.value!!, 1e-9)
        assertEquals(9.654, s.lapAvgSpeedDisplay.value!!, 1e-9)
        assertEquals(6.2150403977, s.lapRunPaceMinPerUnit.value!!, 1e-9) // min/km
    }

    @Test fun `imperial lap display values`() {
        val s = lapState(1)
        assertEquals(0.999785939, s.lapDistanceDisplay.value!!, 1e-9)
        assertEquals(5.9987274333, s.lapAvgSpeedDisplay.value!!, 1e-9)
        assertEquals(10.0021379738, s.lapRunPaceMinPerUnit.value!!, 1e-9) // min/mi
    }

    @Test fun `no lap pace or speed before lap elapsed time is known`() {
        val state = BtEngineState()
        MetricFusion(state).submit(sample(MetricKind.DISTANCE, 1609.0, arrivalMs = 100))
        assertEquals(1609, state.lapDistanceMeters.value)
        assertNull(state.lapAvgSpeedMps.value)
        assertNull(state.lapRunPaceMinPerUnit.value)
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
        assertEquals(14.4, state.lapAvgSpeedDisplay.value!!, 1e-9)
    }

    @Test fun `rower instant lap speed in imperial units`() {
        var seriesNowMs = 10_000L
        val state = BtEngineState().apply { setUnits(1) }
        val fusion = MetricFusion(state, seriesClock = MetricSeriesClock { seriesNowMs })
        fusion.updateSessionElapsed(100, arrivalMs = 99)
        fusion.submit(sample(MetricKind.DISTANCE, 100.0, 100, FtmsUuids.UUID_ROWING_MACHINE_DATA))
        seriesNowMs = 13_000L
        fusion.submit(sample(MetricKind.DISTANCE, 112.0, 101, FtmsUuids.UUID_ROWING_MACHINE_DATA))

        assertEquals(4.0, state.lapAvgSpeedMps.value!!, 1e-9)
        assertEquals(8.9477424, state.lapAvgSpeedDisplay.value!!, 1e-9)
    }
}
