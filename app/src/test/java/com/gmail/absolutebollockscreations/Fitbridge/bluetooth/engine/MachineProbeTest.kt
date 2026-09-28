package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.MachineProbe.PROBE_CAP_MS
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.ProviderAdvertisingReadiness.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/** LIB-9f: when the connected machine's row shows "Probing" and Start is held. */
class MachineProbeTest {

    private fun probe(
        discovered: Boolean,
        ftmsFeature: Boolean,
        readiness: Decision,
        elapsedMs: Long = 0,
    ) = MachineProbe.state(discovered, ftmsFeature, readiness, elapsedMs)

    @Test
    fun `probing until service discovery completes`() {
        assertEquals(MachineProbeState.PROBING, probe(false, false, Decision.WaitForFeatures))
    }

    @Test
    fun `FTMS machine probes until Feature and every claimed range are read`() {
        assertEquals(MachineProbeState.PROBING, probe(true, true, Decision.WaitForFeatures))
        assertEquals(
            MachineProbeState.PROBING,
            probe(true, true, Decision.WaitForRanges(listOf("speed", "inclination", "power"))),
        )
        assertEquals(MachineProbeState.READY, probe(true, true, Decision.Ready))
        assertEquals(
            MachineProbeState.READY,
            probe(true, true, Decision.ReadyWithoutRanges(listOf("power")), elapsedMs = 5_000),
        )
    }

    @Test
    fun `machine without an FTMS Feature characteristic is ready once discovered`() {
        // PM5, FE-C and the other families: nothing further to read before Start.
        assertEquals(MachineProbeState.READY, probe(true, false, Decision.WaitForFeatures))
    }

    @Test
    fun `probe never holds Start past the cap`() {
        assertEquals(
            MachineProbeState.PROBING,
            probe(true, true, Decision.WaitForFeatures, elapsedMs = PROBE_CAP_MS - 1),
        )
        assertEquals(
            MachineProbeState.READY,
            probe(true, true, Decision.WaitForFeatures, elapsedMs = PROBE_CAP_MS),
        )
        assertEquals(
            MachineProbeState.READY,
            probe(false, false, Decision.WaitForFeatures, elapsedMs = PROBE_CAP_MS),
        )
    }
}
