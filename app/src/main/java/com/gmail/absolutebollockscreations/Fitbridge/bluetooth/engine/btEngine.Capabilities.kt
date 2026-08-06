// =================================================================================================
// File: btEngine.Capabilities.kt
// Package: com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine
// -------------------------------------------------------------------------------------------------
// ROLE
//   Parse FTMS (Fitness Machine Service) capability and range characteristics into BtEngineState.
//
//   This module is responsible for:
//     • Decoding the FTMS Fitness Machine Feature characteristic (0x2ACC)
//         - Determines whether the upstream machine supports speed / incline / resistance / power
//           control.
//         - Updates the corresponding flags on BtEngineState (supportsSpeed, supportsInclination,
//           supportsResistance, supportsPower, featuresRead).
//         - Opportunistically triggers range reads for any supported control dimensions.
//         - Updates BtEngineState.ftmsCapabilities (featuresRead + control flags).
//     • Decoding the four “Supported * Range” characteristics:
//         - Supported Speed Range (0x2AD4)       → speedMin / speedMax / speedIncr
//         - Supported Inclination Range (0x2AD5) → inclineMin / inclineMax / inclineIncr
//         - Supported Resistance Range (0x2AD6)  → resistanceMin / resistanceMax / resistanceIncr
//         - Supported Power Range (0x2AD8)       → powerMin / powerMax / powerIncr
//       Each parser:
//         - Safely validates payload length.
//         - Converts raw UInt16/SInt16 values into human units for engine.state
//             • speed:      km/h (legacy engine fields)
//             • incline:    degrees
//             • resistance: 0.1 “levels”
//             • power:      watts
//         - Also updates BtEngineState.ftmsCapabilities with *spec-unit* values suitable for
//           provider-side “Supported * Range” characteristics (0x2AD4–0x2AD8).
//         - Normalizes step increments with a defensive clamp.
//         - Marks the corresponding “*RangeKnown” flags on BtEngineState.
//   The engine (FtmsProtocolHandler / BtEngine) calls into these helpers whenever a relevant
//   characteristic is read. All parsed values are stored on BtEngineState for consumption by
//   UI and the provider-side btService layer.
// -------------------------------------------------------------------------------------------------
// PERMISSION HANDLING
//   • Range reads are wrapped in tryReadRange(), which:
//       - Verifies BLUETOOTH_CONNECT at runtime using ContextCompat.
//       - Catches SecurityException to avoid crashing on permission revocation.
//   • This design keeps permission checks centralized and Lint-friendly.
// -------------------------------------------------------------------------------------------------
// IMPLEMENTATION NOTES
//   • All parsing is side-effect-free except for updates to engine.state and logging.
//   • Byte decoding helpers (u16 / s16) perform little-endian conversions as per the FTMS spec.
//   • normalizeStep() keeps increments finite and within a large but sane bound to avoid
//     propagating garbage values from misbehaving devices.
//   • Legacy engine.state fields remain in human units for existing math/UI, while the new
//     BtEngineState.FtmsCapabilities snapshot is kept in raw spec units for btService packing.
// =================================================================================================

package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import timber.log.Timber
import kotlin.math.max
import kotlin.math.min

internal object BtEngineCapabilities {

    // Feature bit flags from FTMS Fitness Machine Feature (0x2ACC), control bits @ byte 4 (LSB first).
    private const val CTRL_SPEED_BIT      = 0x01
    private const val CTRL_INCLINE_BIT    = 0x02
    private const val CTRL_RESISTANCE_BIT = 0x04
    private const val CTRL_POWER_BIT      = 0x08

    @SuppressLint("MissingPermission")
    fun parseFitnessMachineFeatures(engine: BtEngine, value: ByteArray) {
        if (value.size < 5) {
            Timber.tag(BtEngineState.TAG).w("Features (0x2ACC) too short len=%d", value.size)
            return
        }

        val controlFlags = value[4].toInt() and 0xFF

        val supportsSpeed       = (controlFlags and CTRL_SPEED_BIT) != 0
        val supportsInclination = (controlFlags and CTRL_INCLINE_BIT) != 0
        val supportsResistance  = (controlFlags and CTRL_RESISTANCE_BIT) != 0
        val supportsPower       = (controlFlags and CTRL_POWER_BIT) != 0

        // Legacy flags on state (engine-side logic / UI)
        engine.state.supportsSpeed       = supportsSpeed
        engine.state.supportsInclination = supportsInclination
        engine.state.supportsResistance  = supportsResistance
        engine.state.supportsPower       = supportsPower
        engine.state.featuresRead        = true

        // New canonical capabilities snapshot for provider-side btService
        engine.state.mergeFtmsCapabilities(
            BtEngineState.FtmsCapabilities(
                featuresRead        = true,
                supportsSpeed       = supportsSpeed,
                supportsInclination = supportsInclination,
                supportsResistance  = supportsResistance,
                supportsPower       = supportsPower
            )
        )

        Timber.tag(BtEngineState.TAG).d(
            "FTMS features: speed=%s incl=%s res=%s power=%s",
            supportsSpeed,
            supportsInclination,
            supportsResistance,
            supportsPower
        )

        // Opportunistically fetch ranges for any supported targets.
        // Use a standard permission check so Lint can verify it.
        if (supportsResistance)  tryReadRange(engine) { engine.readSupportedResistanceRange() }
        if (supportsInclination) tryReadRange(engine) { engine.readSupportedInclinationRange() }
        if (supportsSpeed)       tryReadRange(engine) { engine.readSupportedSpeedRange() }
        if (supportsPower)       tryReadRange(engine) { engine.readSupportedPowerRange() }
    }

    fun parseSupportedSpeedRange(engine: BtEngine, value: ByteArray) {
        if (value.size < 6) {
            Timber.tag(BtEngineState.TAG).w("SpeedRange (0x2AD4) too short len=%d", value.size)
            return
        }

        // Raw spec units from FTMS: 0.01 m/s (we keep them as-is for FtmsCapabilities)
        var rawMin = u16(value, 0)
        var rawMax = u16(value, 2)
        var rawInc = u16(value, 4)

        if (rawMin > rawMax) {
            val t = rawMin
            rawMin = rawMax
            rawMax = t
        }

        // Legacy human-readable units on engine.state (for existing UI/math).
        // Historically this code treated /100f as "km/h"; we preserve that behavior.
        val minVal = rawMin / 100f
        val maxVal = rawMax / 100f
        val incRaw = rawInc / 100f

        engine.state.speedMin = minVal
        engine.state.speedMax = maxVal
        engine.state.speedIncr = normalizeStep(incRaw)
        engine.state.speedRangeKnown = true

        // Register spec-unit snapshot for btService adapter.
        engine.state.mergeFtmsCapabilities(
            BtEngineState.FtmsCapabilities(
                featuresRead    = true,
                supportsSpeed   = engine.state.supportsSpeed,
                speedMin        = rawMin.toFloat(),                      // spec: 0.01 m/s units
                speedMax        = rawMax.toFloat(),
                speedIncr       = normalizeStep(rawInc.toFloat()),
                speedRangeKnown = true
            )
        )

        Timber.tag(BtEngineState.TAG).d(
            "Speed range: min=%.2f (legacy units) max=%.2f inc=%.2f " +
                    "[spec raw min=%d max=%d inc=%d]",
            engine.state.speedMin,
            engine.state.speedMax,
            engine.state.speedIncr,
            rawMin,
            rawMax,
            rawInc
        )
    }

    fun parseSupportedInclinationRange(engine: BtEngine, value: ByteArray) {
        if (value.size < 6) {
            Timber.tag(BtEngineState.TAG).w("InclineRange (0x2AD5) too short len=%d", value.size)
            return
        }

        // Raw spec units from FTMS: 0.1 degree (or 0.1 % grade, device dependent)
        var rawMin = s16(value, 0)
        var rawMax = s16(value, 2)
        var rawInc = u16(value, 4)

        if (rawMin > rawMax) {
            val t = rawMin
            rawMin = rawMax
            rawMax = t
        }

        // Percent grade on engine.state (for existing UI/math)
        val minVal = rawMin / 10f
        val maxVal = rawMax / 10f
        val incRaw = rawInc / 10f

        engine.state.inclineMin = minVal
        engine.state.inclineMax = maxVal
        engine.state.inclineIncr = normalizeStep(incRaw)
        engine.state.inclineRangeKnown = true

        // Register spec-unit snapshot for btService adapter.
        engine.state.mergeFtmsCapabilities(
            BtEngineState.FtmsCapabilities(
                featuresRead       = true,
                supportsInclination = engine.state.supportsInclination,
                inclineMin         = rawMin.toFloat(),                   // spec: 0.1 percent units
                inclineMax         = rawMax.toFloat(),
                inclineIncr        = normalizeStep(rawInc.toFloat()),
                inclineRangeKnown  = true
            )
        )

        Timber.tag(BtEngineState.TAG).d(
            "Incline range: min=%.1f%% max=%.1f%% inc=%.1f%% " +
                    "[spec raw min=%d max=%d inc=%d]",
            engine.state.inclineMin,
            engine.state.inclineMax,
            engine.state.inclineIncr,
            rawMin,
            rawMax,
            rawInc
        )
    }

    fun parseSupportedResistanceRange(engine: BtEngine, value: ByteArray) {
        if (value.size < 6) {
            Timber.tag(BtEngineState.TAG).w("ResistanceRange (0x2AD6) too short len=%d", value.size)
            return
        }

        // Raw spec units from FTMS: 0.1 “level”
        var rawMin = s16(value, 0)
        var rawMax = s16(value, 2)
        var rawInc = u16(value, 4)

        if (rawMin > rawMax) {
            val t = rawMin
            rawMin = rawMax
            rawMax = t
        }

        // Legacy on engine.state: float “levels” with 0.1 resolution
        val minVal = rawMin / 10f
        val maxVal = rawMax / 10f
        val incRaw = rawInc / 10f

        engine.state.resistanceMin = minVal
        engine.state.resistanceMax = maxVal
        engine.state.resistanceIncr = normalizeStep(incRaw)
        engine.state.resistanceRangeKnown = true

        // Register spec-unit snapshot for btService adapter.
        engine.state.mergeFtmsCapabilities(
            BtEngineState.FtmsCapabilities(
                featuresRead           = true,
                supportsResistance     = engine.state.supportsResistance,
                resistanceMin          = rawMin.toFloat(),               // spec: 0.1 “level” units
                resistanceMax          = rawMax.toFloat(),
                resistanceIncr         = normalizeStep(rawInc.toFloat()),
                resistanceRangeKnown   = true
            )
        )

        Timber.tag(BtEngineState.TAG).d(
            "Resistance range: min=%.1f max=%.1f inc=%.1f " +
                    "[spec raw min=%d max=%d inc=%d]",
            engine.state.resistanceMin,
            engine.state.resistanceMax,
            engine.state.resistanceIncr,
            rawMin,
            rawMax,
            rawInc
        )
    }

    fun parseSupportedPowerRange(engine: BtEngine, value: ByteArray) {
        if (value.size < 6) {
            Timber.tag(BtEngineState.TAG).w("PowerRange (0x2AD8) too short len=%d", value.size)
            return
        }

        // Raw spec units from FTMS: watts as SInt16 / UInt16
        var rawMin = s16(value, 0)
        var rawMax = s16(value, 2)
        var rawInc = u16(value, 4)

        if (rawMin > rawMax) {
            val t = rawMin
            rawMin = rawMax
            rawMax = t
        }

        val minVal = rawMin.toFloat()
        val maxVal = rawMax.toFloat()
        val incRaw = rawInc.toFloat()

        engine.state.powerMin = minVal
        engine.state.powerMax = maxVal
        engine.state.powerIncr = normalizeStep(incRaw)
        engine.state.powerRangeKnown = true

        // Register spec-unit snapshot for btService adapter.
        engine.state.mergeFtmsCapabilities(
            BtEngineState.FtmsCapabilities(
                featuresRead      = true,
                supportsPower     = engine.state.supportsPower,
                powerMin          = rawMin.toFloat(),                    // spec: watts
                powerMax          = rawMax.toFloat(),
                powerIncr         = normalizeStep(rawInc.toFloat()),
                powerRangeKnown   = true
            )
        )

        Timber.tag(BtEngineState.TAG).d(
            "Power range: min=%.0f W max=%.0f W inc=%.0f W " +
                    "[spec raw min=%d max=%d inc=%d]",
            engine.state.powerMin,
            engine.state.powerMax,
            engine.state.powerIncr,
            rawMin,
            rawMax,
            rawInc
        )
    }

    // ─────────────────────────────────────────────────────────────
    // Permission-safe wrapper for range reads (Lint-friendly)
    // ─────────────────────────────────────────────────────────────
    private inline fun tryReadRange(engine: BtEngine, crossinline action: () -> Unit) {
        // API 31+ only; pre-31 BLUETOOTH is install-time and always held.
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                engine.context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (!granted) {
            Timber.tag(BtEngineState.TAG).w("Missing BLUETOOTH_CONNECT; skipping range read.")
            return
        }
        try {
            action()
        } catch (se: SecurityException) {
            Timber.tag(BtEngineState.TAG).w(se, "SecurityException during range read; skipping.")
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun s16(b: ByteArray, off: Int): Int {
        val u = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
        return (u shl 16) shr 16
    }

    /** Clamp and sanitize step increments. */
    private fun normalizeStep(step: Float): Float {
        if (step.isNaN() || step.isInfinite()) return 0f
        return max(0f, min(step, 1_000_000f))
    }
}
