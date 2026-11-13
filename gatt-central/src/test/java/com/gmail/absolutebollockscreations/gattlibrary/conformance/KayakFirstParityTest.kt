package com.gmail.absolutebollockscreations.gattlibrary.conformance

import com.gmail.absolutebollockscreations.gattlibrary.central.KayakFirstDisplayConfiguration
import com.gmail.absolutebollockscreations.gattlibrary.central.KayakFirstOrangePhysics as CentralPhysics
import com.gmail.absolutebollockscreations.gattlibrary.central.KayakFirstOrangeTimingTuple as CentralTiming
import com.gmail.absolutebollockscreations.gattlibrary.simulator.KayakFirstOrangePhysics as SimulatorPhysics
import com.gmail.absolutebollockscreations.gattlibrary.simulator.KayakFirstOrangeTimingTuple as SimulatorTiming
import com.gmail.absolutebollockscreations.gattlibrary.simulator.KayakFirstPanelConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * L25 parity for the Kayak First simulator copies (LIB-19l): the Orange flywheel oracle against
 * central's physics, and the panel slot model against central's display configuration. Both are
 * deliberate double entry (L23); this is the check that the two copies still agree.
 */
class KayakFirstParityTest {

    private val timings = listOf(
        doubleArrayOf(0.30, 0.10, 0.45, 0.80),
        doubleArrayOf(0.25, 0.08, 0.60, 1.10),
        doubleArrayOf(0.50, 0.20, 0.50, 0.90),
        doubleArrayOf(0.10, 0.05, 0.12, 0.30),
        doubleArrayOf(0.45, 0.10, 0.30, 0.80),
        doubleArrayOf(0.0, 0.10, 0.45, 0.80),
        doubleArrayOf(-0.3, 0.10, 0.45, 0.80),
        doubleArrayOf(Double.NaN, 0.10, 0.45, 0.80),
        doubleArrayOf(0.30, Double.POSITIVE_INFINITY, 0.45, 0.80),
        doubleArrayOf(0.30, 0.10, 0.45, 0.0),
    )
    private val weights = listOf(null, 0.0, -5.0, Double.NaN, 45.0, 70.0, 95.5, 140.0)

    @Test
    fun orangePhysicsAgreesAcrossTheGrid() {
        var producedResults = 0
        for (t in timings) for (w in weights) {
            val central = CentralPhysics.calculate(CentralTiming(t[0], t[1], t[2], t[3]), w)
            val sim = SimulatorPhysics.calculate(SimulatorTiming(t[0], t[1], t[2], t[3]), w)
            val label = "timing=${t.toList()} weight=$w"
            if (central == null || sim == null) {
                assertEquals("null-ness differs for $label", central == null, sim == null)
                continue
            }
            producedResults++
            assertEquals(label, central.powerWatts, sim.powerWatts, 1e-9)
            assertEquals(label, central.forceNewtons, sim.forceNewtons, 1e-9)
            assertEquals(label, central.speedMetresPerSecond, sim.speedMetresPerSecond, 1e-9)
            assertEquals(label, central.strokeRateSpm, sim.strokeRateSpm, 1e-9)
            val cp = central.pace500Seconds
            val sp = sim.pace500Seconds
            if (cp == null || sp == null) assertEquals("pace null-ness differs for $label", cp == null, sp == null)
            else assertEquals(label, cp, sp, 1e-9)
        }
        assertNotNull("grid produced no comparable results", producedResults.takeIf { it > 0 })
    }

    @Test
    fun panelSlotWireCodesAgree() {
        assertEquals(KayakFirstDisplayConfiguration.SLOT_COUNT, KayakFirstPanelConfiguration.SLOT_COUNT)
        assertEquals(KayakFirstDisplayConfiguration.DEFAULT.slots, KayakFirstPanelConfiguration.DEFAULT.slots)
        for (code in -2..300) {
            val codes = listOf(code, 0, 1, 2, 3)
            val central = KayakFirstDisplayConfiguration.fromWireCodes(codes)
            val sim = KayakFirstPanelConfiguration.fromWireCodes(codes)
            assertEquals("acceptance differs for code $code", central == null, sim == null)
            if (central != null && sim != null) assertEquals("code $code", central.slots, sim.slots)
        }
        (0..8).forEach { size ->
            val codes = List(size) { 0 }
            assertEquals("size $size", KayakFirstDisplayConfiguration.fromWireCodes(codes) == null, KayakFirstPanelConfiguration.fromWireCodes(codes) == null)
        }
        assertNull(KayakFirstPanelConfiguration.fromWireCodes(emptyList()))
    }

    @Test
    fun withSlotAgrees() {
        val metric = KayakFirstDisplayConfiguration.DEFAULT.slot5
        (0 until KayakFirstDisplayConfiguration.SLOT_COUNT).forEach { i ->
            val c = KayakFirstDisplayConfiguration.DEFAULT.withSlot(i, KayakFirstDisplayConfiguration.DEFAULT.slot1)
            val s = KayakFirstPanelConfiguration.DEFAULT.withSlot(i, KayakFirstPanelConfiguration.DEFAULT.slot1)
            assertEquals(c.slots, s.slots)
        }
        assertNotNull(metric)
    }
}
