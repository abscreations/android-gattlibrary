package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeCRolloverTest {

    @Test
    fun `first sample seeds the baseline without adding a delta`() {
        val acc = UnsignedModuloAccumulator(modulus = 256)
        assertFalse(acc.hasSample)
        val total = acc.accumulate(40)
        assertEquals(40L, total)
        assertTrue(acc.hasSample)
    }

    @Test
    fun `accumulates forward deltas without wrapping`() {
        val acc = UnsignedModuloAccumulator(modulus = 256)
        acc.accumulate(10)
        acc.accumulate(15)
        assertEquals(15L, acc.total)
        acc.accumulate(200)
        assertEquals(200L, acc.total)
    }

    @Test
    fun `handles rollover across the modulus boundary`() {
        val acc = UnsignedModuloAccumulator(modulus = 256)
        acc.accumulate(250)
        val afterWrap = acc.accumulate(3) // delta = (256-250)+3 = 9
        assertEquals(259L, afterWrap)
    }

    @Test
    fun `repeated identical reading does not double-count`() {
        val acc = UnsignedModuloAccumulator(modulus = 65536)
        acc.accumulate(1200)
        val unchanged = acc.accumulate(1200)
        assertEquals(1200L, unchanged)
    }

    @Test
    fun `reset discards accumulated state`() {
        val acc = UnsignedModuloAccumulator(modulus = 256)
        acc.accumulate(200)
        acc.accumulate(3)
        acc.reset()
        assertFalse(acc.hasSample)
        assertEquals(0L, acc.total)
        // Next sample re-seeds rather than computing a rollover delta against stale state.
        assertEquals(5L, acc.accumulate(5))
    }

    @Test
    fun `65536 modulus accumulates across several wraps`() {
        val acc = UnsignedModuloAccumulator(modulus = 65536)
        acc.accumulate(65000)
        acc.accumulate(500) // delta = (65536-65000)+500 = 1036
        assertEquals(66036L, acc.total)
    }
}
