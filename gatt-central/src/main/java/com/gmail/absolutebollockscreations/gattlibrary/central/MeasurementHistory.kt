package com.gmail.absolutebollockscreations.gattlibrary.central

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The chart series the client records for a session. Every series is canonical (central never records display
 * units); the UI converts for display.
 */
public enum class MetricSeriesKind {
    /**
     * Beats per minute. A value of `0.0` is a deliberate dropout marker: the sensor reported no reading at that
     * moment. It is kept so a chart can show where data dropped out; it is not a heart rate.
     */
    HEART_RATE,

    /** Speed in kilometres per hour. */
    SPEED_KPH,

    /** Cadence in revolutions or strides per minute; for rowers, stroke rate per minute. */
    CADENCE_PER_MIN,

    /** Power in watts. */
    POWER_WATTS,

    /** Incline in percent. */
    INCLINE_PERCENT,

    /** Device resistance level, in the device's own units. */
    RESISTANCE,

    /** Force on the belt in newtons. */
    FORCE_ON_BELT_NEWTONS,

    /** Net elevation change in metres: positive gain minus negative gain, i.e. the height relative to the start. */
    ELEVATION_NET_METERS,

    /** Distance in metres. */
    DISTANCE_METERS,
}

/** One recorded point: wall-clock milliseconds and the canonical value. */
public data class TimedValue(val tMillis: Long, val value: Double)

/**
 * A read-only view over a recorded series. Taking a view is O(1) and copies nothing; later appends to the
 * history never change an existing view. Timestamps are assumed to be non-decreasing (they come from one
 * clock), which [since] relies on.
 *
 * Equality is O(1) and conservative: two views are equal when they cover the same range of the same recording,
 * or when both are empty. Equal views always have equal contents; unequal views usually differ, though two
 * views of identical content taken across a storage growth may compare unequal. That is what makes
 * `distinctUntilChanged` cheap on a series that grows for the whole session.
 */
public class HistorySeries internal constructor(
    private val times: LongArray,
    private val values: DoubleArray,
    private val from: Int,
    private val to: Int,
) {
    public val size: Int get() = to - from

    public fun isEmpty(): Boolean = to == from

    public fun timeAt(index: Int): Long = times[checked(index)]

    public fun valueAt(index: Int): Double = values[checked(index)]

    public operator fun get(index: Int): TimedValue {
        val i = checked(index)
        return TimedValue(times[i], values[i])
    }

    /** The latest point, or null when the series is empty. */
    public fun last(): TimedValue? = if (isEmpty()) null else get(size - 1)

    /** The view of points whose timestamp is at or after [tMillis]. O(log n); copies nothing. */
    public fun since(tMillis: Long): HistorySeries {
        var lo = from
        var hi = to
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] < tMillis) lo = mid + 1 else hi = mid
        }
        return HistorySeries(times, values, lo, to)
    }

    /** Copies the view into a list. O(n); prefer indexed access for large series. */
    public fun toList(): List<TimedValue> = List(size) { get(it) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HistorySeries) return false
        if (isEmpty() && other.isEmpty()) return true
        return times === other.times && from == other.from && to == other.to
    }

    override fun hashCode(): Int =
        if (isEmpty()) 0 else 31 * (31 * System.identityHashCode(times) + from) + to

    override fun toString(): String = "HistorySeries(size=$size)"

    private fun checked(index: Int): Int {
        if (index < 0 || index >= size) throw IndexOutOfBoundsException("index $index, size $size")
        return from + index
    }
}

/** A snapshot of every recorded series at one moment. Immutable; later recording never changes it. */
public class MeasurementHistory internal constructor(private val slices: Array<Slice>) {
    public fun series(kind: MetricSeriesKind): HistorySeries {
        val s = slices[kind.ordinal]
        return HistorySeries(s.times, s.values, 0, s.size)
    }

    internal class Slice(val times: LongArray, val values: DoubleArray, val size: Int)

    public companion object {
        public val EMPTY: MeasurementHistory = MeasurementHistory(
            Array(MetricSeriesKind.entries.size) { Slice(LongArray(0), DoubleArray(0), 0) },
        )
    }
}

/**
 * Records every series into append-only primitive arrays and publishes an immutable [MeasurementHistory] after
 * each change. Appends are O(1) amortised (capacity doubles). Readers hold array references captured at
 * publication, and a recorded point is never rewritten, so a published view is safe to read from any thread.
 * A clear replaces the arrays, so views taken earlier keep their old contents.
 */
internal class HistoryRecorder {
    private val lock = Any()
    private val kinds = MetricSeriesKind.entries.size
    private val times = Array(kinds) { LongArray(INITIAL_CAPACITY) }
    private val values = Array(kinds) { DoubleArray(INITIAL_CAPACITY) }
    private val sizes = IntArray(kinds)
    private var published = Array(kinds) { MeasurementHistory.Slice(times[it], values[it], 0) }
    private val _history = MutableStateFlow(MeasurementHistory(published))
    val history: StateFlow<MeasurementHistory> = _history

    fun append(kind: MetricSeriesKind, tMillis: Long, value: Double) {
        val k = kind.ordinal
        synchronized(lock) {
            val n = sizes[k]
            if (n == times[k].size) {
                times[k] = times[k].copyOf(n * 2)
                values[k] = values[k].copyOf(n * 2)
            }
            times[k][n] = tMillis
            values[k][n] = value
            sizes[k] = n + 1
            publishLocked(k)
        }
    }

    /** Drops every recorded point. Views taken earlier keep their contents. */
    fun clear() {
        synchronized(lock) {
            for (k in 0 until kinds) {
                times[k] = LongArray(INITIAL_CAPACITY)
                values[k] = DoubleArray(INITIAL_CAPACITY)
                sizes[k] = 0
            }
            published = Array(kinds) { MeasurementHistory.Slice(times[it], values[it], 0) }
            _history.value = MeasurementHistory(published)
        }
    }

    private fun publishLocked(k: Int) {
        val next = published.copyOf()
        next[k] = MeasurementHistory.Slice(times[k], values[k], sizes[k])
        published = next
        _history.value = MeasurementHistory(next)
    }

    private companion object {
        const val INITIAL_CAPACITY = 256
    }
}
