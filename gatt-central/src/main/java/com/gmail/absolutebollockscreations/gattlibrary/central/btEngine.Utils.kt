/**
 * btEngine.utils.kt — shared utility helpers for BtEngine / BtEngineState
 *
 * PURPOSE
 *  - Centralize small, engine-specific helpers that are used across the Bluetooth
 *    engine implementation without bloating BtEngine / BtEngineState themselves.
 *  - Keep unit / lap math consistent by routing all derived calculations through
 *    a single set of helpers.
 *
 * FEATURES
 *  - Permissions / environment:
 *      • hasConnectPermission() → checks BLUETOOTH_CONNECT via ContextCompat.
 *      • isBluetoothReady()     → true when the adapter exists and is enabled.
 *
 *  - Misc utilities:
 *      • nowMs()                → System.currentTimeMillis() wrapper.
 *      • ByteArray.toHex()      → human-readable hex dump for logging.
 *
 *  - Unit system + display conversions:
 *      • UNIT_METRIC / UNIT_IMPERIAL constants shared by engine/state.
 *      • toDisplayDistanceFromMeters() → meters → km/mi based on state.units.
 *      • recomputeAllDisplayUnits() → recomputes the unit-dependent lap display
 *        fields (lap distance, lap speed, lap paces) after a unit change. Total and
 *        instant speed, distance and pace are canonical here; the UI converts them.
 *
 *  - Lap math & derived metrics:
 *      • recomputeLapDerived() → recomputes lap-avg speed (m/s + display),
 *        running pace (min/km or min/mi), and rowing pace (min/500m).
 *      • updateLapDistanceFromTotal() / updateLapElapsedFromTotal() /
 *        updateLapStepsFromTotal() / updateLapStrokesFromTotal() →
 *        delta-from-baseline helpers that keep lap values in sync with
 *        authoritative totals.
 *      • updateUnifiedLapCadenceFromInstant() →
 *        running-average cadence for laps from instantaneous cadence samples.
 *
 * NOTES
 *  - All helpers are internal and operate on an existing BtEngine / BtEngineState
 *    instance; they do not own any state themselves.
 *  - Intended to be the single place to adjust unit/pace/lap math so that
 *    FTMS/HRM parsers and UI stay consistent.
 */

package com.gmail.absolutebollockscreations.gattlibrary.central

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlin.math.max

/* ───────────────────────────── permissions ───────────────────────────── */

// BLUETOOTH_CONNECT is API 31+. Before that, BLUETOOTH is an install-time
// permission declared in the manifest and therefore always held; checking the
// API 31 string on an older device returns DENIED and disables the caller.
internal fun BtEngine.hasConnectPermission(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

/* ───────────────────────────── misc helpers ───────────────────────────── */

internal fun nowMs(): Long = System.currentTimeMillis()

internal fun ByteArray.toHex(): String =
    joinToString(separator = " ") { b -> "%02X".format(b) }

/* ───────────────────────────── unit system ───────────────────────────── */

internal const val UNIT_METRIC = 0
internal const val UNIT_IMPERIAL = 1

private const val MPS_TO_KPH: Double = 3.6
private const val MPS_TO_MPH: Double = 2.23694
private const val M_TO_MI:    Double = 0.000621371
private const val M_TO_KM:    Double = 0.001

private const val M_PER_KM   = 1000.0
private const val M_PER_MILE = 1609.344
private const val ROWING_PACE_DISTANCE_M = 500.0

/** Convert raw meters to unit-aware display distance (km or mi). */
internal fun toDisplayDistanceFromMeters(engine: BtEngine, meters: Int?): Double? {
    if (meters == null) return null
    return when (engine.state.units.value) {
        UNIT_METRIC   -> meters * M_TO_KM
        UNIT_IMPERIAL -> meters * M_TO_MI
        else          -> null
    }
}

/** Recompute all unit-aware display fields. Call after units change. */
internal fun recomputeAllDisplayUnits(engine: BtEngine) {
    val s = engine.state
    // Lap distance
    s.setLapDistanceDisplay(toDisplayDistanceFromMeters(engine, s.lapDistanceMeters.value))
    // Lap-derived (avg speed display, run pace, row pace)
    if (s.lapDistanceMeters.value != null && s.lapElapsedTimeSec.value != null) {
        recomputeLapDerived(engine)
    }
}

/* ───────────────────────────── lap math & derived ───────────────────────────── */

/** Recompute lap-derived metrics: avg speed (m/s + display), run pace, row pace. */
internal fun recomputeLapDerived(engine: BtEngine) {
    val s = engine.state
    val lapDistM = s.lapDistanceMeters.value ?: return
    val lapTimeS = s.lapElapsedTimeSec.value ?: return
    if (lapTimeS <= 0) {
        s.setLapAvgSpeedMps(null)
        s.setLapAvgSpeedDisplay(null)
        s.setLapRunPaceMinPerUnit(null)
        s.setLapRowPaceMinPer500m(null)
        return
    }

    val mps = lapDistM.toDouble() / lapTimeS.toDouble()
    s.setLapAvgSpeedMps(mps)

    // Display speed per current units
    val u = s.units.value
    val disp = when (u) {
        UNIT_METRIC   -> mps * MPS_TO_KPH
        UNIT_IMPERIAL -> mps * MPS_TO_MPH
        else          -> null
    }
    s.setLapAvgSpeedDisplay(disp)

    // Running pace: min per km or min per mile
    val perMeters = when (u) {
        UNIT_METRIC   -> M_PER_KM
        UNIT_IMPERIAL -> M_PER_MILE
        else          -> null
    }
    val runPace = perMeters?.let { meters ->
        if (mps > 0.0) (meters / mps) / 60.0 else null
    }
    s.setLapRunPaceMinPerUnit(runPace)

    // Rowing pace: min per 500m
    val rowPace = if (mps > 0.0) (ROWING_PACE_DISTANCE_M / mps) / 60.0 else null
    s.setLapRowPaceMinPer500m(rowPace)
}

/** Called when total distance changes to update lap distance + derived. */
internal fun updateLapDistanceFromTotal(engine: BtEngine, totalMeters: Int) {
    val s = engine.state
    val lapMeters = max(0, totalMeters - s.lapStartDistanceMeters)
    s.setLapDistanceMeters(lapMeters)
    s.setLapDistanceDisplay(toDisplayDistanceFromMeters(engine, lapMeters))
    recomputeLapDerived(engine)
}

/** Called when authoritative total elapsed changes to update lap elapsed + derived. */
internal fun updateLapElapsedFromTotal(engine: BtEngine, totalElapsedSec: Int) {
    val s = engine.state
    val lapSec = max(0, totalElapsedSec - s.lapStartElapsedSec)
    s.setLapElapsedTimeSec(lapSec)
    recomputeLapDerived(engine)
}

/** Called when total steps change to update lap steps. */
internal fun updateLapStepsFromTotal(engine: BtEngine, totalStepsNow: Int) {
    val s = engine.state
    s.setLapSteps(max(0, totalStepsNow - s.lapStartSteps))
}

/** Called when total strokes change to update lap strokes + lap cadence. */
internal fun updateLapStrokesFromTotal(engine: BtEngine, totalStrokesNow: Int) {
    val s = engine.state
    val lapStrokes = max(0, totalStrokesNow - s.lapStartStrokeCount)
    s.setLapStrokeCount(lapStrokes)

    val lapSec = s.lapElapsedTimeSec.value
    if (lapSec != null && lapSec > 0) {
        val spm = lapStrokes.toDouble() / (lapSec / 60.0)
        s.setLapCadenceSpm(spm)
    }
}

/** Feed unified lap cadence average from an instant cadence sample. */
internal fun updateUnifiedLapCadenceFromInstant(engine: BtEngine, inst: Double?) {
    if (inst == null) return
    val s = engine.state
    s.lapAvgCadenceTotal += inst
    s.lapAvgCadenceCount++
    s.setLapCadenceSpm(s.lapAvgCadenceTotal / s.lapAvgCadenceCount.toDouble())
}

internal fun BtEngine.isBluetoothReady(): Boolean = (adapter?.isEnabled == true)
