package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

/**
 * Pins what the engine's display-unit flows (`speedDisplay`, `avgSpeedDisplay`, `totalDistanceDisplay`,
 * `instPace`, `avgPace`) produced before the UI began converting canonical values itself. The app's
 * `MeasurementDisplayTest` and `MeasurementBindingsTest` assert the same literals through the UI-side
 * helpers, so the two sides cannot drift apart. These flows and this test are deleted together.
 */
class DisplayUnitCharacterizationTest {
    private val connection = ConnectionId("AA:BB:CC:DD:EE:01", 7L)
    private val characteristic = UUID.randomUUID()
    private var arrival = 100L

    private fun sample(metric: MetricKind, value: Double) = MetricSample(
        connectionId = connection,
        sourceId = MetricSourceId(connection, MetricProtocol.FTMS, characteristic),
        protocol = MetricProtocol.FTMS,
        sourceClass = MetricSourceClass.FTMS,
        metric = metric,
        arrivalMs = arrival++,
        value = value,
        nativeCumulative = if (metric.cumulative) value else null,
    )

    private fun ingest(units: Int): BtEngineState {
        val state = BtEngineState().apply { setUnits(units) }
        val fusion = MetricFusion(state)
        fusion.submit(sample(MetricKind.SPEED, 16.09344))
        fusion.submit(sample(MetricKind.AVERAGE_SPEED, 8.04672))
        fusion.submit(sample(MetricKind.DISTANCE, 1609.0))
        fusion.submit(sample(MetricKind.INSTANT_PACE, 4.25))
        fusion.submit(sample(MetricKind.AVERAGE_PACE, 5.0))
        return state
    }

    @Test fun `metric display values equal the canonical ones`() {
        val s = ingest(0)
        assertEquals(16.09344, s.speedDisplay.value!!, 1e-9)
        assertEquals(8.04672, s.avgSpeedDisplay.value!!, 1e-9)
        assertEquals(1.609, s.totalDistanceDisplay.value!!, 1e-9)
        assertEquals(4.25, s.instPace.value!!, 1e-9)
        assertEquals(5.0, s.avgPace.value!!, 1e-9)
    }

    @Test fun `imperial display values are converted at ingest`() {
        val s = ingest(1)
        assertEquals(10.0, s.speedDisplay.value!!, 1e-6)
        assertEquals(5.0, s.avgSpeedDisplay.value!!, 1e-6)
        assertEquals(0.999785939, s.totalDistanceDisplay.value!!, 1e-9)
        assertEquals(6.839712, s.instPace.value!!, 1e-9)
        assertEquals(8.04672, s.avgPace.value!!, 1e-9)
    }

    @Test fun `changing units on state alone leaves speed and distance display stale but re-emits pace`() {
        // Only BtEngine.setUnits also recomputes display fields; BtEngineState.setUnits re-emits pace alone.
        // Converting in the UI from canonical values removes this trap.
        val s = ingest(0)
        s.setUnits(1)
        assertEquals(16.09344, s.speedDisplay.value!!, 1e-9)
        assertEquals(1.609, s.totalDistanceDisplay.value!!, 1e-9)
        assertEquals(6.839712, s.instPace.value!!, 1e-9)
    }
}
