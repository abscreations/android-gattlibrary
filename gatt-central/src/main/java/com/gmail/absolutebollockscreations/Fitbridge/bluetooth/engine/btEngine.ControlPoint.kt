// =================================================================================================
// File: btEngine.ControlPoint.kt
// Package: com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine
// -------------------------------------------------------------------------------------------------
// ROLE
//   Helpers for driving the FTMS Fitness Machine Control Point characteristic (0x2AD9).
//
//   This module is a stateless, internal façade around the FTMS CP opcodes. It operates on a
//   passed-in [BtEngine] instance, uses BtEngineState for capability/range info, and enqueues
//   GATT writes through BtEngine’s GATT queue (pushWrite).
//
// -------------------------------------------------------------------------------------------------
// SUPPORTED CONTROL POINT OPERATIONS (high-level API)
//   All public functions here are called by BtEngine methods with the same names:
//
//   • requestControl(engine)
//       - Sends OPCODE_REQUEST_CONTROL.
//       - Does not require existing control; used to acquire it.
//       - Always enqueues a write; result is reported asynchronously via CP indication.
//
//   • startOrResume(engine)
//       - Sends OPCODE_START_RESUME (start/resume workout).
//       - Requires that BtEngineState.haveControlFlow == true.
//       - Uses cpWriteOrIgnore() to avoid writes when control isn’t granted.
//
//   • stopOrPause(engine)
//       - Sends OPCODE_STOP_PAUSE (stop or pause workout).
//       - Requires control (same gate as startOrResume).
//
//   • reset(engine)
//       - Sends OPCODE_RESET (0x01) to reset machine state/targets.
//       - Uses precheck() to ensure control and that the device actually advertises support.
//
// -------------------------------------------------------------------------------------------------
// TARGET SETTERS + RANGE SNAPPING
//
//   These methods convert human-readable targets (km/h, degrees, resistance level, watts) into
//   FTMS-scaled values, respecting the device’s advertised supported ranges when available:
//
//   • setTargetSpeedKmh(engine, kmh)
//       - Feature gate: state.supportsSpeed
//       - Applies [speedMin, speedMax, speedIncr] if speedRangeKnown.
//       - Converts to 0.01 km/h units (FTMS_SPEED_SCALE) → UInt16 LE.
//
//   • setTargetInclinationPercent(engine, percent)
//       - Feature gate: state.supportsInclination
//       - Applies [inclineMin, inclineMax, inclineIncr] if inclineRangeKnown.
//       - Converts to 0.1 % SInt16 LE (FTMS_INCLINE_SCALE).
//
//   • setTargetResistance(engine, level)
//       - Feature gate: state.supportsResistance
//       - Applies [resistanceMin, resistanceMax, resistanceIncr] if resistanceRangeKnown.
//       - Converts to 0.1 “level” units → UInt16 LE (FTMS_RESISTANCE_SCALE).
//
//   • setTargetPowerWatts(engine, watts)
//       - Feature gate: state.supportsPower
//       - Applies [powerMin, powerMax, powerIncr] if powerRangeKnown.
//       - Converts to scaled UInt16 LE using FTMS_POWER_SCALE.
//
//   Range application is handled via snapToStep(value, min, max, step):
//     - Clamps to [min, max].
//     - Snaps to the nearest multiple of `step` when step > 0, otherwise just clamps.
//     - Designed to mirror the machine’s discrete control granularity and avoid invalid targets.
//
// -------------------------------------------------------------------------------------------------
// PERMISSION + SAFETY
//
//   • All public methods require BLUETOOTH_CONNECT (annotated @RequiresPermission).
//   • cpWriteOrIgnore() enforces:
//       - Control must be granted (state.haveControlFlow == true), otherwise the write is skipped.
//       - Logging is emitted when writes are ignored.
//   • precheck() additionally enforces that, once features have been read, we don’t send a target
//     for a capability the device does not advertise.
//
// -------------------------------------------------------------------------------------------------
// IMPLEMENTATION NOTES
//
//   • This module doesn’t touch UI or provider-side services directly.
//   • It writes only to UUID_FITNESS_MACHINE_CONTROL_POINT; responses come back via the FTMS
//     protocol handler (btEngine.GattFtms.kt) as CP indications.
//   • Byte-packing helpers (u16le / s16le) ensure correct little-endian encoding.
//   • Logging is centralized under BtEngineState.TAG (“BtEngine”) for easy tracing.
// =================================================================================================

package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.Manifest
import androidx.annotation.RequiresPermission
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.core.FitBridgeBleInternalApi
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.FTMS_INCLINE_SCALE
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.FTMS_POWER_SCALE
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.FTMS_RESISTANCE_SCALE
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.FTMS_SPEED_SCALE
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.OPCODE_REQUEST_CONTROL
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.OPCODE_RESET
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.OPCODE_SET_INDOOR_BIKE_SIMULATION
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.OPCODE_SET_TARGET_INCLINATION
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.OPCODE_SET_TARGET_POWER
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.OPCODE_SET_TARGET_RESISTANCE
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.OPCODE_SET_TARGET_SPEED
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.OPCODE_START_RESUME
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.OPCODE_STOP_PAUSE
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds.UUID_FITNESS_MACHINE_CONTROL_POINT
import timber.log.Timber
import kotlin.math.round
import kotlin.math.roundToInt

internal object BtEngineControlPoint {

    // Gate CP traffic on CCCD readiness to avoid writes before indications are armable.
    // Optional settle knob in case a vendor needs a tiny grace window after link-up.
    private const val CP_SETTLE_MS: Long = 0 // set >0 if you observe early-CCCD flakiness

    // ─────────────────────────────────────────────────────────────
    // High-level CP ops
    // ─────────────────────────────────────────────────────────────

    /** Try to acquire machine control (no precondition). */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun requestControl(engine: BtEngine): Boolean {
        val context = engine.state.connectionRegistry.current(LegacyConnectionRole.FTMS)
        val setupPending = context?.lifeFitnessSetupState == LifeFitnessLinkSetupState.PENDING
        if (!canRequestFtmsControl(
                cccdReady = engine.state.cccdReady.value,
                setupPending = setupPending,
                controlPointPresent = engine.state.controlPointChar != null,
            )
        ) {
            Timber.tag(BtEngineState.TAG).w(
                "CP: requestControl ignored (setupPending=%s controlPointPresent=%s cccdReady=%s)",
                setupPending,
                engine.state.controlPointChar != null,
                engine.state.cccdReady.value,
            )
            return false
        }
        // For requestControl we do NOT require haveControl, but we still ensure CCCDs are ready.
        if (!ensureCccdReady(engine, opName = "requestControl", requireControl = false)) return false
        Timber.tag(BtEngineState.TAG).i("CP: requestControl() → enqueue")
        return enqueueControlPointWrite(engine, byteArrayOf(OPCODE_REQUEST_CONTROL))
    }

    /** Start or resume the workout on the machine (requires control). */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun startOrResume(engine: BtEngine): Boolean {
        if (!ensureCccdReady(engine, opName = "startOrResume", requireControl = true)) return false
        Timber.tag(BtEngineState.TAG).i("CP: startOrResume() → enqueue")
        return cpWriteOrIgnore(engine, byteArrayOf(OPCODE_START_RESUME))
    }

    /** Stop or pause on the machine (requires control). */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun stopOrPause(engine: BtEngine): Boolean {
        if (!ensureCccdReady(engine, opName = "stopOrPause", requireControl = true)) return false
        Timber.tag(BtEngineState.TAG).i("CP: stopOrPause() → enqueue")
        return cpWriteOrIgnore(engine, byteArrayOf(OPCODE_STOP_PAUSE))
    }

    /** Reset the fitness machine’s current state/targets (FTMS CP opcode 0x01). */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun reset(engine: BtEngine): Boolean {
        if (!ensureCccdReady(engine, opName = "reset", requireControl = true)) return false
        if (!precheck(engine, "reset", supported = true)) return false
        val payload = byteArrayOf(OPCODE_RESET)
        return cpWriteOrIgnore(engine, payload)
    }

    // ─────────────────────────────────────────────────────────────
    // Target setters (+ range snapping)
    // ─────────────────────────────────────────────────────────────

    /** Set target speed in km/h. Device step/min/max applied when known. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun setTargetSpeedKph(engine: BtEngine, kph: Float): Boolean {
        if (!ensureCccdReady(engine, opName = "setTargetSpeedKph", requireControl = true)) return false
        if (!precheck(engine, feature = "speed", supported = engine.state.supportsSpeed)) return false

        val value = if (engine.state.speedRangeKnown)
            snapToStep(kph, engine.state.speedMin, engine.state.speedMax, engine.state.speedIncr)
        else kph

        val scaled = (value * FTMS_SPEED_SCALE).toInt().coerceIn(0, 0xFFFF)
        val payload = byteArrayOf(OPCODE_SET_TARGET_SPEED) + u16le(scaled)

        Timber.tag(BtEngineState.TAG).i("CP: set speed → %.2f km/h (scaled=%d)", value, scaled)
        return enqueueControlPointWrite(engine, payload)
    }

    /**
     * FTMS `0x11` Set Indoor Bike Simulation Parameters (plan §9.5.6).
     *
     * Simulation mode is how a riding app expresses a route: it sends road gradient plus
     * coefficients and lets the trainer compute resistance, rather than sending a target watts
     * figure. FitBridge's downstream side has decoded this since Phase 0; the upstream side
     * dropped it, so a gradient arrived, parsed correctly, and went nowhere.
     *
     * Payload is seven octets including the opcode:
     *
     * | Offset | Field | Type | Scale |
     * |---|---|---|---|
     * | 0 | Opcode `0x11` | uint8 | |
     * | 1–2 | Wind speed | sint16 LE | m/s × 1000 |
     * | 3–4 | Grade | sint16 LE | percent × 100 |
     * | 5 | Rolling resistance coefficient | uint8 | × 10000 |
     * | 6 | Wind resistance coefficient | uint8 | × 100 |
     *
     * Gated on Target Setting Features bit 13. Unlike a target write there is no range
     * characteristic to snap against, so values are clamped to their field widths and nothing
     * is invented: an out-of-range grade is pinned to the representable extreme rather than
     * silently wrapped, which is the difference between a steep hill and a cliff in the
     * opposite direction.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun setIndoorBikeSimulation(
        engine: BtEngine,
        windSpeedMps: Float,
        gradePercent: Float,
        rollingResistanceCoefficient: Float,
        windResistanceCoefficient: Float,
    ): Boolean {
        if (!ensureCccdReady(engine, opName = "setIndoorBikeSimulation", requireControl = true)) {
            return false
        }
        if (!precheck(
                engine,
                feature = "indoor bike simulation",
                supported = engine.state.supportsIndoorBikeSimulation,
            )
        ) {
            return false
        }

        val wind = (windSpeedMps * 1000f).roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        val grade = (gradePercent * 100f).roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        val crr = (rollingResistanceCoefficient * 10_000f).roundToInt().coerceIn(0, 0xFF)
        val cw = (windResistanceCoefficient * 100f).roundToInt().coerceIn(0, 0xFF)

        val payload = byteArrayOf(OPCODE_SET_INDOOR_BIKE_SIMULATION) +
            s16le(wind) + s16le(grade) + byteArrayOf(crr.toByte(), cw.toByte())

        Timber.tag(BtEngineState.TAG).i(
            "CP: set simulation → wind %.3f m/s, grade %.2f %%, crr %.4f, cw %.2f",
            windSpeedMps, gradePercent, rollingResistanceCoefficient, windResistanceCoefficient,
        )
        return enqueueControlPointWrite(engine, payload)
    }

    /** Set target incline (percent grade). Device step/min/max applied when known. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun setTargetInclinationPercent(engine: BtEngine, percent: Float): Boolean {
        if (!ensureCccdReady(engine, opName = "setTargetInclinationPercent", requireControl = true)) return false
        if (!precheck(engine, feature = "inclination", supported = engine.state.supportsInclination)) return false

        val value = if (engine.state.inclineRangeKnown)
            snapToStep(percent, engine.state.inclineMin, engine.state.inclineMax, engine.state.inclineIncr)
        else percent

        val scaled = (value * FTMS_INCLINE_SCALE).toInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        val payload = byteArrayOf(OPCODE_SET_TARGET_INCLINATION) + s16le(scaled)

        Timber.tag(BtEngineState.TAG).i("CP: set incline → %.1f %% (scaled=%d)", value, scaled)
        return enqueueControlPointWrite(engine, payload)
    }

    /** Set target resistance level. Device step/min/max applied when known. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun setTargetResistance(engine: BtEngine, level: Float): Boolean {
        if (!ensureCccdReady(engine, opName = "setTargetResistance", requireControl = true)) return false
        if (!precheck(engine, feature = "resistance", supported = engine.state.supportsResistance)) return false

        val value = if (engine.state.resistanceRangeKnown)
            snapToStep(level, engine.state.resistanceMin, engine.state.resistanceMax, engine.state.resistanceIncr)
        else level

        // FTMS 0x04 Set Target Resistance Level carries a single uint8 in 0.1 units,
        // so the write is 2 bytes total. Sending a uint16 here made conformant
        // machines reject the command with an invalid-parameter response.
        val scaled = (value * FTMS_RESISTANCE_SCALE).toInt().coerceIn(0, 0xFF)
        val payload = byteArrayOf(OPCODE_SET_TARGET_RESISTANCE, scaled.toByte())

        Timber.tag(BtEngineState.TAG).i("CP: set resistance → %.1f (scaled=%d, uint8)", value, scaled)
        return enqueueControlPointWrite(engine, payload)
    }

    /** Set target power in watts. Device step/min/max applied when known. */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun setTargetPowerWatts(engine: BtEngine, watts: Int): Boolean {
        if (!ensureCccdReady(engine, opName = "setTargetPowerWatts", requireControl = true)) return false
        if (!precheck(engine, feature = "power", supported = engine.state.supportsPower)) return false

        val snapped = if (engine.state.powerRangeKnown)
            snapToStep(watts.toFloat(), engine.state.powerMin, engine.state.powerMax, engine.state.powerIncr).toInt()
        else watts

        val scaled = (snapped * FTMS_POWER_SCALE).toInt().coerceIn(0, 0xFFFF)
        val payload = byteArrayOf(OPCODE_SET_TARGET_POWER) + u16le(scaled)

        Timber.tag(BtEngineState.TAG).i("CP: set power → %d W (scaled=%d)", snapped, scaled)
        return enqueueControlPointWrite(engine, payload)
    }

    // ─────────────────────────────────────────────────────────────
    // Internals
    // ─────────────────────────────────────────────────────────────

    /**
     * Unified readiness gate:
     *  - For most ops we require both control and CCCD readiness so CP indications can be received.
     *  - For requestControl we only require CCCD readiness (no control yet).
     *  - Optional "settle" window can be enabled via CP_SETTLE_MS if some devices need grace time.
     *
     * We do NOT implement retries here; the queue will enforce backoff on 201/CONN_BUSY.
     */
    private fun ensureCccdReady(
        engine: BtEngine,
        opName: String,
        requireControl: Boolean
    ): Boolean {
        if (requireControl && !engine.state.haveControlFlow.value) {
            Timber.tag(BtEngineState.TAG).w("CP: %s: ignored (no control)", opName)
            return false
        }

        // If CCCDs are not ready, optionally allow a tiny grace window after linkage.
        val ready = engine.state.cccdReady.value
        if (!ready) {
            if (CP_SETTLE_MS > 0) {
                val since = System.currentTimeMillis() - (engine.state.linkedAtMs ?: 0L)
                if (since in 0..CP_SETTLE_MS) {
                    Timber.tag(BtEngineState.TAG).d("CP: %s: proceeding during settle window (%d ms)", opName, since)
                    return true
                }
            }
            Timber.tag(BtEngineState.TAG).w("CP: %s: ignored (CCCDs not ready)", opName)
            return false
        }
        return true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun cpWriteOrIgnore(engine: BtEngine, payload: ByteArray): Boolean {
        if (!engine.state.haveControlFlow.value) {
            Timber.tag(BtEngineState.TAG).w("CP write ignored: control not granted yet")
            return false
        }
        if (!engine.state.cccdReady.value) {
            Timber.tag(BtEngineState.TAG).w("CP write ignored: CCCDs not ready")
            return false
        }
        return enqueueControlPointWrite(engine, payload)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun enqueueControlPointWrite(engine: BtEngine, payload: ByteArray): Boolean {
        val context = engine.machineContext("FTMS control-point write") ?: return false
        engine.pushWrite(context, payload, UUID_FITNESS_MACHINE_CONTROL_POINT)
        return true
    }

    private fun precheck(engine: BtEngine, feature: String, supported: Boolean): Boolean {
        if (!engine.state.haveControlFlow.value) {
            Timber.tag(BtEngineState.TAG).w("CP: set %s: no control", feature)
            return false
        }
        if (engine.state.featuresRead && !supported) {
            Timber.tag(BtEngineState.TAG).w("CP: set %s: device does not advertise target support", feature)
            return false
        }
        if (!engine.state.cccdReady.value) {
            Timber.tag(BtEngineState.TAG).w("CP: set %s: CCCDs not ready", feature)
            return false
        }
        return true
    }

    /** Round to nearest step inside [min,max]. If step<=0, just clamp. */
    private fun snapToStep(value: Float, min: Float, max: Float, step: Float): Float {
        if (step <= 0f) return value.coerceIn(min, max)
        val steps = round((value - min) / step)
        return (min + steps * step).coerceIn(min, max)
    }

    private fun u16le(v: Int): ByteArray =
        byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())

    private fun s16le(v: Int): ByteArray = u16le(v and 0xFFFF)
}

@FitBridgeBleInternalApi
fun canRequestFtmsControl(
    cccdReady: Boolean,
    setupPending: Boolean,
    controlPointPresent: Boolean,
): Boolean = cccdReady && !setupPending && controlPointPresent
