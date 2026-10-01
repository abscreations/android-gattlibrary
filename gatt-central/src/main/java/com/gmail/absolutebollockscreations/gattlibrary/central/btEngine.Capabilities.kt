// =================================================================================================
// File: btEngine.Capabilities.kt
// Package: com.gmail.absolutebollockscreations.gattlibrary.central
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

package com.gmail.absolutebollockscreations.gattlibrary.central

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import timber.log.Timber
import kotlin.math.max
import kotlin.math.min
import com.gmail.absolutebollockscreations.gattlibrary.core.bytes.LittleEndian.u16
import com.gmail.absolutebollockscreations.gattlibrary.core.bytes.LittleEndian.s16
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsSpec

internal object BtEngineCapabilities {

    // Feature bit flags from FTMS Fitness Machine Feature (0x2ACC), control bits @ byte 4 (LSB first).
    private const val CTRL_SPEED_BIT      = 0x01
    private const val CTRL_INCLINE_BIT    = 0x02
    private const val CTRL_RESISTANCE_BIT = 0x04
    private const val CTRL_POWER_BIT      = 0x08

    /**
     * Measurement features occupy bytes 0-3 of `0x2ACC`; only the target-setting half at bytes
     * 4-7 was parsed until 2026-08-10. Pace Supported is bit 5 of the measurement word, so it
     * sits in byte 0.
     *
     * FitBridge relays the upstream treadmill's pace octet downstream verbatim rather than
     * synthesising it, so the downstream Feature must claim pace exactly when the machine being
     * bridged claims it. Mirroring the bit keeps that claim honest by construction instead of
     * by policy — see [TreadmillNativePaceStore] for why the field is never derived.
     */
    private const val MEAS_PACE_BIT = 0x20

    /**
     * Target Setting Features bit 13, Indoor Bike Simulation Parameters Supported.
     *
     * The target-setting word occupies bytes 4–7 of `0x2ACC`, but this parser read only byte 4
     * until 2026-08-10, which put every bit above 7 out of reach. §9.5.6 needs bit 13 to decide
     * whether an upstream `0x11` write is honest, so the read widens to the full 32 bits.
     *
     * Bit 15 (Spin Down Control) is decoded alongside it and is currently recorded but unused —
     * spin-down is a calibration procedure with its own status sequence rather than a target,
     * and its state machine is not built. Recording the bit now means the capability is
     * available when that work happens, without claiming support in the meantime.
     */
    private const val TARGET_SIMULATION_BIT = 1 shl FtmsSpec.TargetFeature.INDOOR_BIKE_SIMULATION
    private const val TARGET_SPIN_DOWN_BIT = 1 shl FtmsSpec.TargetFeature.SPIN_DOWN_CONTROL

    /** Which control targets 0x2ACC claimed; used to decide which range reads to issue. */
    class FeatureFlags(
        val supportsSpeed: Boolean,
        val supportsInclination: Boolean,
        val supportsResistance: Boolean,
        val supportsPower: Boolean,
    )

    /** State-only half of [parseFitnessMachineFeatures]. Returns null when the payload is too short. */
    fun applyFitnessMachineFeatures(state: BtEngineState, value: ByteArray): FeatureFlags? {
        if (value.size < 5) {
            Timber.tag(BtEngineState.TAG).w("Features (0x2ACC) too short len=%d", value.size)
            return null
        }

        val controlFlags = value[4].toInt() and 0xFF
        val measurementFlags = value[0].toInt() and 0xFF

        // Full 32-bit target-setting word. Bytes 5..7 are optional in practice — some machines
        // return a short characteristic — so each is folded in only when present rather than
        // indexed blindly.
        var targetFlags = controlFlags
        if (value.size >= 6) targetFlags = targetFlags or ((value[5].toInt() and 0xFF) shl 8)
        if (value.size >= 7) targetFlags = targetFlags or ((value[6].toInt() and 0xFF) shl 16)
        if (value.size >= 8) targetFlags = targetFlags or ((value[7].toInt() and 0xFF) shl 24)

        val supportsSpeed       = (controlFlags and CTRL_SPEED_BIT) != 0
        val supportsInclination = (controlFlags and CTRL_INCLINE_BIT) != 0
        val supportsResistance  = (controlFlags and CTRL_RESISTANCE_BIT) != 0
        val supportsPower       = (controlFlags and CTRL_POWER_BIT) != 0
        val supportsPaceMeasurement = (measurementFlags and MEAS_PACE_BIT) != 0
        val supportsSimulation = (targetFlags and TARGET_SIMULATION_BIT) != 0
        val supportsSpinDown = (targetFlags and TARGET_SPIN_DOWN_BIT) != 0

        // Legacy flags on state (engine-side logic / UI)
        state.supportsSpeed       = supportsSpeed
        state.supportsInclination = supportsInclination
        state.supportsResistance  = supportsResistance
        state.supportsPower       = supportsPower
        state.supportsPaceMeasurement = supportsPaceMeasurement
        state.supportsIndoorBikeSimulation = supportsSimulation
        state.supportsSpinDown    = supportsSpinDown
        state.featuresRead        = true

        // New canonical capabilities snapshot for provider-side btService
        state.mergeFtmsCapabilities(
            BtEngineState.FtmsCapabilities(
                featuresRead        = true,
                supportsSpeed       = supportsSpeed,
                supportsInclination = supportsInclination,
                supportsResistance  = supportsResistance,
                supportsPower       = supportsPower,
                supportsIndoorBikeSimulation = supportsSimulation,
            )
        )

        Timber.tag(BtEngineState.TAG).d(
            "FTMS features: speed=%s incl=%s res=%s power=%s",
            supportsSpeed,
            supportsInclination,
            supportsResistance,
            supportsPower
        )
        return FeatureFlags(supportsSpeed, supportsInclination, supportsResistance, supportsPower)
    }

    @SuppressLint("MissingPermission")
    fun parseFitnessMachineFeatures(engine: BtEngine, value: ByteArray) {
        val flags = applyFitnessMachineFeatures(engine.state, value) ?: return

        // Opportunistically fetch ranges for any supported targets.
        // Use a standard permission check so Lint can verify it.
        if (flags.supportsResistance)  tryReadRange(engine) { engine.readSupportedResistanceRange() }
        if (flags.supportsInclination) tryReadRange(engine) { engine.readSupportedInclinationRange() }
        if (flags.supportsSpeed)       tryReadRange(engine) { engine.readSupportedSpeedRange() }
        if (flags.supportsPower)       tryReadRange(engine) { engine.readSupportedPowerRange() }
    }

    /** State-only half of [parseSupportedSpeedRange], separable so it can be tested without an engine. */
    fun applySupportedSpeedRange(state: BtEngineState, value: ByteArray) {
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

        // Legacy human-readable units on state (for existing UI/math).
        // Historically this code treated /100f as "km/h"; we preserve that behavior.
        val minVal = rawMin / 100f
        val maxVal = rawMax / 100f
        val incRaw = rawInc / 100f

        state.speedMin = minVal
        state.speedMax = maxVal
        state.speedIncr = normalizeStep(incRaw)
        state.speedRangeKnown = true

        // Register spec-unit snapshot for btService adapter.
        state.mergeFtmsCapabilities(
            BtEngineState.FtmsCapabilities(
                featuresRead    = true,
                supportsSpeed   = state.supportsSpeed,
                speedMin        = rawMin.toFloat(),                      // spec: 0.01 m/s units
                speedMax        = rawMax.toFloat(),
                speedIncr       = normalizeStep(rawInc.toFloat()),
                speedRangeKnown = true
            )
        )

        Timber.tag(BtEngineState.TAG).d(
            "Speed range: min=%.2f (legacy units) max=%.2f inc=%.2f " +
                    "[spec raw min=%d max=%d inc=%d]",
            state.speedMin,
            state.speedMax,
            state.speedIncr,
            rawMin,
            rawMax,
            rawInc
        )
    }

    fun parseSupportedSpeedRange(engine: BtEngine, value: ByteArray) =
        applySupportedSpeedRange(engine.state, value)

    /** State-only half of [parseSupportedInclinationRange], separable so it can be tested without an engine. */
    fun applySupportedInclinationRange(state: BtEngineState, value: ByteArray) {
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

        // Percent grade on state (for existing UI/math)
        val minVal = rawMin / 10f
        val maxVal = rawMax / 10f
        val incRaw = rawInc / 10f

        state.inclineMin = minVal
        state.inclineMax = maxVal
        state.inclineIncr = normalizeStep(incRaw)
        state.inclineRangeKnown = true

        // Register spec-unit snapshot for btService adapter.
        state.mergeFtmsCapabilities(
            BtEngineState.FtmsCapabilities(
                featuresRead       = true,
                supportsInclination = state.supportsInclination,
                inclineMin         = rawMin.toFloat(),                   // spec: 0.1 percent units
                inclineMax         = rawMax.toFloat(),
                inclineIncr        = normalizeStep(rawInc.toFloat()),
                inclineRangeKnown  = true
            )
        )

        Timber.tag(BtEngineState.TAG).d(
            "Incline range: min=%.1f%% max=%.1f%% inc=%.1f%% " +
                    "[spec raw min=%d max=%d inc=%d]",
            state.inclineMin,
            state.inclineMax,
            state.inclineIncr,
            rawMin,
            rawMax,
            rawInc
        )
    }

    fun parseSupportedInclinationRange(engine: BtEngine, value: ByteArray) =
        applySupportedInclinationRange(engine.state, value)

    /** State-only half of [parseSupportedResistanceRange], separable so it can be tested without an engine. */
    fun applySupportedResistanceRange(state: BtEngineState, value: ByteArray) {
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

        // Legacy on state: float “levels” with 0.1 resolution
        val minVal = rawMin / 10f
        val maxVal = rawMax / 10f
        val incRaw = rawInc / 10f

        state.resistanceMin = minVal
        state.resistanceMax = maxVal
        state.resistanceIncr = normalizeStep(incRaw)
        state.resistanceRangeKnown = true

        // Register spec-unit snapshot for btService adapter.
        state.mergeFtmsCapabilities(
            BtEngineState.FtmsCapabilities(
                featuresRead           = true,
                supportsResistance     = state.supportsResistance,
                resistanceMin          = rawMin.toFloat(),               // spec: 0.1 “level” units
                resistanceMax          = rawMax.toFloat(),
                resistanceIncr         = normalizeStep(rawInc.toFloat()),
                resistanceRangeKnown   = true
            )
        )

        Timber.tag(BtEngineState.TAG).d(
            "Resistance range: min=%.1f max=%.1f inc=%.1f " +
                    "[spec raw min=%d max=%d inc=%d]",
            state.resistanceMin,
            state.resistanceMax,
            state.resistanceIncr,
            rawMin,
            rawMax,
            rawInc
        )
    }

    fun parseSupportedResistanceRange(engine: BtEngine, value: ByteArray) =
        applySupportedResistanceRange(engine.state, value)

    /** State-only half of [parseSupportedPowerRange], separable so it can be tested without an engine. */
    fun applySupportedPowerRange(state: BtEngineState, value: ByteArray) {
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

        state.powerMin = minVal
        state.powerMax = maxVal
        state.powerIncr = normalizeStep(incRaw)
        state.powerRangeKnown = true

        // Register spec-unit snapshot for btService adapter.
        state.mergeFtmsCapabilities(
            BtEngineState.FtmsCapabilities(
                featuresRead      = true,
                supportsPower     = state.supportsPower,
                powerMin          = rawMin.toFloat(),                    // spec: watts
                powerMax          = rawMax.toFloat(),
                powerIncr         = normalizeStep(rawInc.toFloat()),
                powerRangeKnown   = true
            )
        )

        Timber.tag(BtEngineState.TAG).d(
            "Power range: min=%.0f W max=%.0f W inc=%.0f W " +
                    "[spec raw min=%d max=%d inc=%d]",
            state.powerMin,
            state.powerMax,
            state.powerIncr,
            rawMin,
            rawMax,
            rawInc
        )
    }

    fun parseSupportedPowerRange(engine: BtEngine, value: ByteArray) =
        applySupportedPowerRange(engine.state, value)

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
    /** Clamp and sanitize step increments. */
    private fun normalizeStep(step: Float): Float {
        if (step.isNaN() || step.isInfinite()) return 0f
        return max(0f, min(step, 1_000_000f))
    }
}
