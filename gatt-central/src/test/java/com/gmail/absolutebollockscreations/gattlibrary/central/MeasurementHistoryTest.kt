package com.gmail.absolutebollockscreations.gattlibrary.central

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/** Pins the storage and view contract of `MeasurementHistory` (LIB-43, unit 4d step 2). */
class MeasurementHistoryTest {
    private val recorder = HistoryRecorder()
    private val hr = MetricSeriesKind.HEART_RATE
    private fun series(kind: MetricSeriesKind = hr) = recorder.history.value.series(kind)

    @Test
    fun `empty history has empty series for every kind`() {
        for (kind in MetricSeriesKind.entries) {
            val s = MeasurementHistory.EMPTY.series(kind)
            assertTrue(s.isEmpty())
            assertEquals(0, s.size)
            assertNull(s.last())
            assertTrue(s.toList().isEmpty())
        }
        assertEquals(MeasurementHistory.EMPTY.series(hr), series())
    }

    @Test
    fun `append keeps order and values`() {
        recorder.append(hr, 100, 60.0)
        recorder.append(hr, 200, 0.0)
        recorder.append(hr, 300, 72.5)
        val s = series()
        assertEquals(3, s.size)
        assertEquals(100L, s.timeAt(0))
        assertEquals(0.0, s.valueAt(1), 0.0)
        assertEquals(TimedValue(300, 72.5), s[2])
        assertEquals(TimedValue(300, 72.5), s.last())
        assertEquals(listOf(TimedValue(100, 60.0), TimedValue(200, 0.0), TimedValue(300, 72.5)), s.toList())
    }

    @Test
    fun `out of range index throws`() {
        recorder.append(hr, 1, 1.0)
        try {
            series().valueAt(1)
            fail("expected IndexOutOfBoundsException")
        } catch (_: IndexOutOfBoundsException) {
        }
        try {
            series().timeAt(-1)
            fail("expected IndexOutOfBoundsException")
        } catch (_: IndexOutOfBoundsException) {
        }
    }

    @Test
    fun `a view taken earlier does not change as more points arrive`() {
        recorder.append(hr, 1, 1.0)
        recorder.append(hr, 2, 2.0)
        val before = series()
        val snapshotList = before.toList()
        for (i in 3L..1_500L) recorder.append(hr, i, i.toDouble())
        assertEquals(2, before.size)
        assertEquals(snapshotList, before.toList())
        assertEquals(1_500, series().size)
    }

    @Test
    fun `growth across capacity boundaries keeps every point`() {
        val n = 10_000
        for (i in 0 until n) recorder.append(hr, i.toLong(), i * 2.0)
        val s = series()
        assertEquals(n, s.size)
        for (i in 0 until n) {
            assertEquals(i.toLong(), s.timeAt(i))
            assertEquals(i * 2.0, s.valueAt(i), 0.0)
        }
    }

    @Test
    fun `kinds are independent`() {
        recorder.append(MetricSeriesKind.SPEED_KPH, 10, 8.0)
        recorder.append(MetricSeriesKind.POWER_WATTS, 20, 150.0)
        recorder.append(MetricSeriesKind.ELEVATION_NET_METERS, 30, -2.5)
        assertEquals(1, series(MetricSeriesKind.SPEED_KPH).size)
        assertEquals(1, series(MetricSeriesKind.POWER_WATTS).size)
        assertEquals(-2.5, series(MetricSeriesKind.ELEVATION_NET_METERS).valueAt(0), 0.0)
        assertTrue(series(hr).isEmpty())
        assertTrue(series(MetricSeriesKind.DISTANCE_METERS).isEmpty())
    }

    @Test
    fun `since is a lower bound on the timestamp`() {
        for (t in listOf(10L, 20L, 20L, 30L, 40L)) recorder.append(hr, t, t.toDouble())
        val s = series()
        assertEquals(5, s.since(0).size)
        assertEquals(5, s.since(10).size)
        assertEquals(4, s.since(11).size)
        assertEquals(4, s.since(20).size)
        assertEquals(20L, s.since(20).timeAt(0))
        assertEquals(1, s.since(31).size)
        assertEquals(1, s.since(40).size)
        assertTrue(s.since(41).isEmpty())
        assertEquals(2, s.since(20).since(30).size)
        assertEquals(40L, s.since(15).last()!!.tMillis)
    }

    @Test
    fun `since on an empty series is empty`() {
        assertTrue(series().since(0).isEmpty())
    }

    @Test
    fun `equality tracks changes and is cheap`() {
        recorder.append(hr, 1, 1.0)
        val a = series()
        val b = series()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        recorder.append(hr, 2, 2.0)
        assertNotEquals(a, series())
        assertEquals(a.since(0), a)
        assertNotEquals(a, a.since(2))
    }

    @Test
    fun `clear starts a new generation and leaves old views readable`() {
        recorder.append(hr, 1, 1.0)
        recorder.append(MetricSeriesKind.SPEED_KPH, 1, 9.0)
        val old = series()
        recorder.clear()
        assertTrue(series().isEmpty())
        assertTrue(series(MetricSeriesKind.SPEED_KPH).isEmpty())
        assertNotEquals(old, series())
        assertEquals(1, old.size)
        assertEquals(1.0, old.valueAt(0), 0.0)
        recorder.append(hr, 5, 5.0)
        assertEquals(1, series().size)
        assertEquals(5L, series().timeAt(0))
        assertEquals(1L, old.timeAt(0))
    }

    @Test
    fun `each append publishes a new history and untouched kinds stay equal`() {
        recorder.append(MetricSeriesKind.POWER_WATTS, 1, 100.0)
        val h1 = recorder.history.value
        recorder.append(hr, 2, 60.0)
        val h2 = recorder.history.value
        assertNotSame(h1, h2)
        assertEquals(h1.series(MetricSeriesKind.POWER_WATTS), h2.series(MetricSeriesKind.POWER_WATTS))
        assertNotEquals(h1.series(hr), h2.series(hr))
    }

    @Test
    fun `a concurrent reader only ever sees a consistent prefix`() {
        val total = 100_000
        val failure = AtomicReference<String?>(null)
        val writer = Thread {
            for (i in 0 until total) recorder.append(hr, i.toLong(), i.toDouble())
        }
        val reader = Thread {
            var lastSize = 0
            while (lastSize < total && failure.get() == null) {
                val s = recorder.history.value.series(hr)
                val n = s.size
                if (n < lastSize) failure.set("size went backwards: $lastSize -> $n")
                for (i in listOf(0, n / 2, n - 1)) {
                    if (i in 0 until n && (s.timeAt(i) != i.toLong() || s.valueAt(i) != i.toDouble())) {
                        failure.set("point $i is ${s[i]}")
                    }
                }
                lastSize = n
            }
        }
        writer.start()
        reader.start()
        writer.join()
        reader.join()
        assertNull(failure.get(), failure.get())
        assertEquals(total, series().size)
    }
}
