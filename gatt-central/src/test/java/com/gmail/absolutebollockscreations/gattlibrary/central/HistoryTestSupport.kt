package com.gmail.absolutebollockscreations.gattlibrary.central

/** Test-only readers over `MeasurementHistory`, so assertions read like the old per-series lists. */
internal fun BtEngineState.series(kind: MetricSeriesKind): HistorySeries = history.value.series(kind)

internal fun HistorySeries.pairs(): List<Pair<Long, Double>> = List(size) { timeAt(it) to valueAt(it) }

internal fun HistorySeries.values(): List<Double> = List(size) { valueAt(it) }

internal fun HistorySeries.single(): TimedValue {
    check(size == 1) { "expected exactly one point, found $size" }
    return get(0)
}

internal fun HistorySeries.firstOrNull(predicate: (TimedValue) -> Boolean): TimedValue? =
    toList().firstOrNull(predicate)
