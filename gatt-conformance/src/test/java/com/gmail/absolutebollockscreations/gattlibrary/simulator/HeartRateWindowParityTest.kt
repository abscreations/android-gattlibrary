package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.central.HEART_RATE_FRESHNESS_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Split out of SimulatorHeartRateOverrideTest (LIB-20f): needs central, so it lives here (L25). */
class HeartRateWindowParityTest {

    @Test
    fun `the window matches the one fusion uses for heart rate`() {
        // Not a second constant that could drift. A strap notifies about once a second, so this
        // rides out three missed notifications and no more.
        assertEquals(HEART_RATE_FRESHNESS_MS, SimulatorHeartRateOverride.FRESHNESS_MS)
        assertTrue("the window must tolerate a few dropped notifications", SimulatorHeartRateOverride.FRESHNESS_MS >= 3_000L)
    }
}
