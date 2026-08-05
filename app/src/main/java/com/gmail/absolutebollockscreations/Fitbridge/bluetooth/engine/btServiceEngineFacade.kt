/**
 * =================================================================================================
 * BtServiceEngineFacade – upstream FTMS bridge for btService
 *
 * =================================================================================================
 *
 * PURPOSE
 *  - Expose a narrow, Android-type-free view of the upstream FTMS pipe so the
 *    provider-side btService module can behave like a proper FTMS / CSC+CPS
 *    server without depending directly on BtEngine internals.
 *  - Let btService:
 *      • Check whether an FTMS device is currently connected.
 *      • Read the shared [BtEngineState] for capabilities and live metrics.
 *      • Build FTMS Supported-* Range payloads (0x2AD4–0x2AD8) from the current
 *        machine ranges exposed by the engine.
 *      • Fire FTMS Fitness Machine Control Point commands (start/stop/targets)
 *        upstream to the real fitness machine via BtEngine.
 *
 * FEATURES
 *  - Read-only upstream view:
 *      • [isFtmsConnected] – simple Boolean for “do we have a live FTMS link?”
 *      • [engineState]      – shared state object with metrics/capabilities.
 *
 *  - Supported-* range builders:
 *      • [buildSupportedSpeedRange]        – Supported Speed Range (0x2AD4).
 *      • [buildSupportedInclineRange]      – Supported Inclination Range (0x2AD5).
 *      • [buildSupportedResistanceRange]   – Supported Resistance Level Range (0x2AD6).
 *      • [buildSupportedHeartRateRange]    – Supported Heart Rate Range (0x2AD7).
 *      • [buildSupportedPowerRange]        – Supported Power Range (0x2AD8).
 *    These helpers encapsulate FTMS encoding rules so btService can respond to
 *    characteristic READs without re-implementing range math.
 *
 *  - Control Point helpers:
 *      • [startOrResume] / [stopOrPause]          – map START/RESUME and STOP/PAUSE
 *        FTMS CP opcodes onto BtEngine’s session control.
 *      • [setTargetSpeedKph] / [setTargetInclinationPercent] /
 *        [setTargetResistance] / [setTargetPowerWatts] – map Set Target * FTMS
 *        CP opcodes into upstream target changes.
 *    All helpers are guarded by BLUETOOTH_CONNECT since concrete implementations
 *    will talk to Android’s BLE stack.
 *
 * DESIGN NOTES
 *  - This is an engine-side interface; the concrete implementation is BtEngine.
 *  - btService only sees this façade and the shared [BtEngineState], keeping it
 *    isolated from Android BLE primitives and the full engine API surface.
 *  - Control-point logic on the provider side (e.g. [btServiceControlPoint]) is
 *    expected to:
 *      • Check [isFtmsConnected] and [engineState] capability flags before
 *        calling target setters.
 *      • Translate Boolean return values into FTMS CP result codes
 *        (SUCCESS vs OPERATION_FAILED / NOT_SUPPORTED, etc.).
 */


package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.Manifest
import androidx.annotation.RequiresPermission

/**
 * BtServiceEngineFacade.kt
 *
 * PURPOSE
 *  - Narrow facade that the provider-side btService module uses to:
 *      • Check upstream FTMS connection state.
 *      • Read BtEngineState for metrics / capabilities.
 *      • Build Supported-* Range payloads from current machine ranges.
 *      • Fire FTMS Control Point commands (Start/Stop/Targets).
 *
 * DESIGN NOTES
 *  - This interface intentionally hides all Android BLE types from btService.
 *  - The concrete implementation is BtEngine.
 */
interface BtServiceEngineFacade {

    /** True when the upstream FTMS pipe is currently connected. */
    val isFtmsConnected: Boolean

    /** Read-only exposure of engine state for metrics / capabilities. */
    val engineState: BtEngineState

    // ───────────────────── Supported * Range payload builders ─────────────────────

    /** Build FTMS Supported Speed Range (0x2AD4) payload from current BtEngineState. */
    fun buildSupportedSpeedRange(): ByteArray

    /** Build FTMS Supported Inclination Range (0x2AD5) payload from current BtEngineState. */
    fun buildSupportedInclineRange(): ByteArray

    /** Build FTMS Supported Resistance Level Range (0x2AD6) payload from current BtEngineState. */
    fun buildSupportedResistanceRange(): ByteArray

    /** Build FTMS Supported Heart Rate Range (0x2AD7) payload. */
    fun buildSupportedHeartRateRange(): ByteArray

    /** Build FTMS Supported Power Range (0x2AD8) payload from current BtEngineState. */
    fun buildSupportedPowerRange(): ByteArray

    // ───────────────────── FTMS Control Point helpers ─────────────────────

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun startOrResume(): Boolean

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun stopOrPause(): Boolean

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun setTargetSpeedKph(kph: Float): Boolean

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun setTargetInclinationPercent(percent: Float): Boolean

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun setTargetResistance(level: Float): Boolean

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun setTargetPowerWatts(watts: Int): Boolean
}
