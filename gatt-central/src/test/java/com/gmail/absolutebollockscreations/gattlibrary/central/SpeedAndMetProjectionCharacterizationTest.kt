package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.core.capture.BleCapture
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.FecBleUuids
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Characterization for unit 4a (L24): what central's fusion leaves in engine state for speed and
 * Metabolic Equivalent. The public measurement snapshot is built from this, so it must not change
 * by accident.
 *
 * Speed: fusion is the only writer of the mph field and always derives it from km/h, so km/h is
 * present whenever mph is. That makes the bridge pump's "fall back to mph" branch unreachable for
 * fused data, and is why the public API can be km/h only (decision 1, 2026-09-30).
 */
class SpeedAndMetProjectionCharacterizationTest {

    private val state = BtEngineState()
    private val fusion = MetricFusion(state).also { it.setPersona(FusionPersona.RIDE, 0) }

    private fun emitter(): MetricEmitter {
        val source = MetricSourceId(
            ConnectionId("AA:BB:CC:00:00:01", 1L),
            MetricProtocol.VENDOR,
            FecBleUuids.UUID_FEC_MEASUREMENT,
        )
        return MetricEmitter(fusion, source, MetricSourceClass.VENDOR_NATIVE, 1_000L)
    }

    @Before
    fun resetBefore() = BleCapture.resetSession()

    @After
    fun resetAfter() = BleCapture.resetSession()

    @Test
    fun `nothing fused means neither speed unit is present`() {
        assertNull(state.speedKph.value)
        assertNull(state.speedMph.value)
    }

    @Test
    fun `a fused speed sets km per hour and derives mph from it`() {
        emitter().emit(MetricKind.SPEED, 30.0)
        assertEquals(30.0, state.speedKph.value!!, 1e-9)
        assertEquals(30.0 / 1.609344, state.speedMph.value!!, 1e-9)
    }

    @Test
    fun `Metabolic Equivalent is decoded into engine state as a plain METs value`() {
        // Central already collects it (FTMS Indoor Bike/Treadmill/Rower data, FE-C metabolic page).
        // The bridge pump just does not forward it yet; that wiring is deferred (decision 3).
        emitter().emit(MetricKind.METABOLIC_EQUIVALENT, 8.5)
        assertEquals(8.5, state.metabolicEquivalent.value!!, 1e-9)
    }
}
