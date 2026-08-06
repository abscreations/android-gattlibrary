/**
 * btEngine.StateExtensions.kt — small convenience extensions for BtEngineState
 *
 * PURPOSE
 *  - Provide focused, engine-aware helpers that mutate BtEngineState in a
 *    consistent, high-level way without duplicating logic at call sites.
 *
 *  FUNCTIONS
 *  - resetFtmsControl():
 *      • Clears the cached FTMS Control Point characteristic reference.
 *      • Resets the “have control” flag via BtEngineState’s setter
 *        (setHaveControl(false)) instead of touching the underlying StateFlow
 *        directly.
 *      • Intended to be called on disconnect, FTMS reset, or control loss.
 *
 *  - primeLapFromCurrentTotals():
 *      • Captures the current cumulative workout totals as the starting
 *        baselines for a new “lap”:
 *          - lapStartDistanceMeters
 *          - lapStartElapsedSec
 *          - lapStartStrokeCount
 *          - lapStartSteps
 *      • Subsequent FTMS parsers can compute lap deltas (distance, time,
 *        strokes, steps) relative to these primed values.
 */

package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

internal fun BtEngineState.resetFtmsControl() {
    controlPointChar = null
    // Don't write to StateFlow directly; use the setter provided by BtEngineState.
    setHaveControl(false)
}

internal fun BtEngineState.primeLapFromCurrentTotals() {
    lapStartDistanceMeters = totalDistanceMeters.value ?: 0
    lapStartElapsedSec     = elapsedTimeSec.value ?: 0
    lapStartStrokeCount    = strokeCount.value ?: 0
    lapStartSteps          = totalSteps.value ?: 0
}
