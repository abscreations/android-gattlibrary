package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Test

class ForceOnBeltSeriesTest {

    @Test
    fun `belt force samples update the instantaneous value and graph series`() {
        val state = BtEngineState()

        state.appendForceOnBeltSample(42, tsMillis = 1_000L)
        state.appendForceOnBeltSample(57, tsMillis = 2_000L)

        assertEquals(57, state.forceOnBelt.value)
        assertEquals(2, state.series(MetricSeriesKind.FORCE_ON_BELT_NEWTONS).size)
        assertEquals(42.0, state.series(MetricSeriesKind.FORCE_ON_BELT_NEWTONS)[0].value, 0.0)
        assertEquals(57.0, state.series(MetricSeriesKind.FORCE_ON_BELT_NEWTONS).last()!!.value, 0.0)
    }
}
