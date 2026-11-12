package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.EngineMetricPump
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsMetricStore
import com.gmail.absolutebollockscreations.gattlibrary.simulator.Concept2CsafeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LIB-72 canary for Stage 3 step 6 item 3. Cross-module tests move into this module's tests so
 * they keep central internals once those become `internal`; this pins that the test compilation
 * sees both central's `internal` declarations and the server, peripheral and simulator modules.
 * If it stops compiling, the moved tests cannot live here.
 */
class CrossModuleTestAccessTest {
    @Test
    fun `central tests see central internals and the server, peripheral and simulator modules`() {
        val state = BtEngineState()
        // `internal` in central: visible only from this module's own compilations.
        state.putDiagnosticDiscoveredDevices(emptyMap())
        assertEquals(100f, CapabilityUnits.SPEED_RAW_PER_KPH)

        state.setInstantPower(150)
        val store = FtmsMetricStore()
        EngineMetricPump({ state.toMeasurementSnapshot() }, store) { FtmsMachineProfile.INDOOR_BIKE }.sampleOnce()
        assertEquals(150, store.snapshot.powerWatts)

        assertNotNull(Concept2CsafeCodec)
        assertTrue(state.discoveredDevices.value.isEmpty())
    }
}
