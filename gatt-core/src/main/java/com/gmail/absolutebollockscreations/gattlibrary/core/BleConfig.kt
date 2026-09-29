package com.gmail.absolutebollockscreations.gattlibrary.core

/**
 * Per-engine configuration supplied by the host app (LIB-2/S2).
 *
 * Replaces the engine's direct reads of the app's `BuildConfig`. A library has its own
 * `BuildConfig`, so an app build flag has to arrive as data.
 *
 * @property phase05HardwareTest arms the completed Phase 0.5 hardware harness (extra PHASE05
 * evidence logging and the harness handler). FitBridge passes
 * `BuildConfig.PHASE_05_HARDWARE_TEST`, which every normal build keeps `false`
 * (`Phase05HardwareHarnessTest`).
 */
public data class BleConfig(
    public val phase05HardwareTest: Boolean = false,
)
