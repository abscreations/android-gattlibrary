package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.server.DEFAULT_EMISSION_PERIOD_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bridge-side half of the pacing guard; the notifier's own pacing tests live in gatt-server. */
class EngineMetricPumpPeriodTest {

    @Test
    fun `the upstream sample period stays faster than the emission floor`() {
        // The regression this guards: the notifier's tick was lowered from 700 ms
        // to a 250 ms floor while the pump stayed at a literal 350 ms, which made
        // the pump slower than the notifier. Consecutive emissions then carried an
        // identical snapshot, and every emission carried a sample up to 350 ms old.
        assertTrue(
            "sampling (${EngineMetricPump.DEFAULT_SAMPLE_PERIOD_MS}ms) must outpace " +
                "emission (${DEFAULT_EMISSION_PERIOD_MS}ms)",
            EngineMetricPump.DEFAULT_SAMPLE_PERIOD_MS < DEFAULT_EMISSION_PERIOD_MS,
        )
        assertEquals(
            "the pump samples twice per emission floor",
            DEFAULT_EMISSION_PERIOD_MS / 2,
            EngineMetricPump.DEFAULT_SAMPLE_PERIOD_MS,
        )
    }
}
