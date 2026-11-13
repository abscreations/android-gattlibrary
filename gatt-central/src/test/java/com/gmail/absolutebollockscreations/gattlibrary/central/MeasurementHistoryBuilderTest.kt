package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** LIB-76: the public `MeasurementHistory.Builder` has the recorder's snapshot and sharing contract. */
class MeasurementHistoryBuilderTest {
    private val power = MetricSeriesKind.POWER_WATTS
    private val hr = MetricSeriesKind.HEART_RATE

    @Test
    fun `a new builder builds an empty history`() {
        val built = MeasurementHistory.Builder().build()
        for (kind in MetricSeriesKind.entries) assertTrue(built.series(kind).isEmpty())
        assertEquals(MeasurementHistory.EMPTY.series(power), built.series(power))
    }

    @Test
    fun `appended points keep order, timestamps and values per series`() {
        val built = MeasurementHistory.Builder()
            .append(power, 1_000, 180.0)
            .append(hr, 1_500, 140.0)
            .append(power, 2_000, 200.0)
            .build()
        assertEquals(listOf(TimedValue(1_000, 180.0), TimedValue(2_000, 200.0)), built.series(power).toList())
        assertEquals(listOf(TimedValue(1_500, 140.0)), built.series(hr).toList())
    }

    @Test
    fun `a build is a snapshot that later appends do not change`() {
        val builder = MeasurementHistory.Builder().append(power, 1_000, 180.0)
        val first = builder.build()
        builder.append(power, 2_000, 200.0)
        assertEquals(1, first.series(power).size)
        assertEquals(2, builder.build().series(power).size)
    }

    @Test
    fun `a series that did not change is shared between builds and one that grew is not`() {
        val builder = MeasurementHistory.Builder().append(power, 1_000, 180.0)
        val before = builder.build()
        builder.append(hr, 2_000, 140.0)
        val after = builder.build()
        assertEquals("unchanged power series compares equal in O(1)", before.series(power), after.series(power))
        assertNotEquals(before.series(hr), after.series(hr))
    }

    @Test
    fun `equal timestamps are allowed and an earlier one is rejected`() {
        val builder = MeasurementHistory.Builder().append(power, 1_000, 180.0).append(power, 1_000, 181.0)
        assertThrows(IllegalArgumentException::class.java) { builder.append(power, 999, 1.0) }
        // The rejected point was not recorded, and other series are unaffected.
        assertEquals(2, builder.build().series(power).size)
        builder.append(hr, 0, 60.0)
        assertSame(builder, builder.append(hr, 0, 61.0))
    }
}
