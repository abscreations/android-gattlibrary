// =================================================================================================
// File: btEngine.GattArbitration.kt
// Package: com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine
// -------------------------------------------------------------------------------------------------
// ROLE
//   Central arbitration for metrics that can be produced by multiple Bluetooth / GATT sources
//   (FTMS-native, external HRM, derived values from distance/speed, etc).
//
//   This module does **not** talk to UI directly. Instead, it:
//     • Listens to raw inputs from various producers (engine/parsers call into it).
//     • Applies freshness rules, smoothing, and priority decisions.
//     • Publishes a single canonical value into [BtEngineState] (heart rate, pace, energy).
//
// -------------------------------------------------------------------------------------------------
// HEART RATE ARBITRATION (HrmArbitration)
//   Consolidates heart rate from:
//     • FTMS-native HR (from FTMS data characteristics).
//     • External HRM (Bluetooth Heart Rate Service 0x180D).
//
//   Features:
//     • Freshness window: both sources are considered stale after 4 seconds.
//     • Smoothing for external HRM:
//         - Exponential moving average (EMA) with dynamic alpha.
//         - Hard clamp on per-second jump (MAX_JUMP_PER_SEC) to avoid spikes.
//     • Source selection:
//         - External HRM wins if fresh.
//         - Else FTMS-native if fresh.
//         - Else HR becomes null (no current value).
//     • State integration:
//         - Writes canonical HR via BtEngineState.appendHeartRateSample(...) which updates:
//               heartRate, averageHeartRate, and hrSeries time-series.
//         - Tracks source via BtEngineState.HrSource (EXTERNAL_HRM vs UNKNOWN/FTMS).
//     • Zone computation (HrZoneMode-driven), with separate flows for:
//         - Instantaneous HR zone:     BtEngineState.heartRateZone (0..5 or null)
//         - Average HR-based zone:     BtEngineState.averageHeartRateZone (0..5 or null)
//       Both use app-provided prefs already bound into BtEngineState:
//         hrZoneModeFromApp, hrMaxFromApp, restingHrFromApp.
//
//   Public entry points:
//     • setExternalHeartRate(engine, bpm)
//     • setFtmsHeartRate(engine, bpm)
//     • reselect(engine)        → recompute winner from cached samples.
//     • recalcZone(engine)      → recompute zones from current HR + averages + prefs.
// -------------------------------------------------------------------------------------------------
// PACE ARBITRATION (PaceArbitration)
//   Chooses the canonical running/rowing pace, expressed in **metric pace** (min/km),
//   from three possible sources:
//
//     1) Native FTMS pace (already expressed as seconds per km).
//     2) Pace derived from instantaneous speed in m/s.
//     3) Pace derived from distance/time snapshots over ~10 seconds.
//
//   It now arbitrates **both**:
//     • Instant pace    → BtEngineState.setInstantPace(min/km or min/mi via units).
//     • Average pace    → BtEngineState.setAvgPace(min/km or min/mi via units).
//
//   Features (instant pace):
//     • Freshness:
//         - Any pace source older than 3 seconds is considered stale.
//         - Distance-derived pace additionally requires >= 5 seconds lookback.
//     • Priority order (instant):
//         1. Native FTMS pace (if fresh).
//         2. Pace derived from speed (if fresh).
//         3. Pace derived from distance (if fresh & enough history).
//     • Distance-based derivation:
//         - Maintains a sliding buffer (~30s) of (timestamp, distanceMeters).
//         - Looks back around TARGET_LOOKBACK_MS (~10s) with a minimum span.
//         - Computes deltaDistance / deltaTime → speed → pace.
//     • State integration (instant):
//         - Converts selected pace (sec/km) to minutes per km.
//         - Writes to BtEngineState.setInstantPace(minutesPerKm).
//         - Unit-specific formatting (min/km vs min/mi) is handled inside BtEngineState.
//
//   Features (average pace):
//     • Sources:
//         1. Native FTMS average pace (sec/km) when available.
//         2. Pace derived from session average speed (BtEngineState.avgSpeedKph).
//         3. Pace derived from total distance and elapsed time.
//     • Freshness:
//         - Each average source is considered stale after 3 seconds from its last update.
//     • Priority order (average):
//         1. Native FTMS average pace (if fresh).
//         2. Pace derived from average speed (if fresh and > 0).
//         3. Pace derived from total distance + elapsed time (if both > 0).
//     • State integration (average):
//         - Converts selected avg pace (sec/km) to minutes per km.
//         - Writes to BtEngineState.setAvgPace(minutesPerKm).
//
//   Public entry points:
//     • setNativePace(engine, paceSecPerKm)          → instant pace (native).
//     • setNativeAvgPace(engine, paceSecPerKm)       → average pace (native).
//     • setSpeedMetersPerSecond(engine, speedMps)    → instant+average pace from speed.
//     • setDistanceSnapshot(engine, distanceMeters, atMs)
//         - instant pace via 10s lookback buffer.
//         - average pace via total distance + elapsed time from BtEngineState.
// -------------------------------------------------------------------------------------------------
// ENERGY ARBITRATION (EnergyArbitration)
//   Consolidates total energy / calories from potentially conflicting sources:
//
//     • HRM-derived energy (highest priority).
//     • Wear OS / Health Services producer (middle priority).
//     • FTMS-native total energy (lowest priority).
//
//   Design choices:
//     • No time-based staleness: once a non-null source is present, it remains the winner
//       until that source becomes null (to avoid mixing different semantics/scales).
//     • Simple priority chain: HRM → Wear → FTMS.
//     • State integration:
//         - Writes the chosen energy (kcal) to BtEngineState.setExpEnergyTotal(Int?),
//           rounding from Double when present.
//
//   Public entry points:
//     • setHrmEnergy(engine, energyKcal)
//     • setWearEnergy(engine, energyKcal)
//     • setFtmsEnergy(engine, energyKcal)
// -------------------------------------------------------------------------------------------------
// IMPLEMENTATION NOTES
//   • All arbiters are pure singletons (`object`) with internal caches; they do not own
//     coroutines or threads.
//   • Time is taken from System.currentTimeMillis() for freshness and smoothing logic.
//   • Callers are responsible for feeding samples; arbiters only read/write BtEngineState.
//   • This file is intentionally app-level: no direct references to Android Bluetooth APIs.
// =================================================================================================

package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import com.gmail.absolutebollockscreations.Fitbridge.HrZoneMode
import kotlin.math.roundToInt


// ───────────────────────────────────────────────────────────────────────────────
// HEART RATE ARBITRATION (Updated to track instant & average HR zones separately)
// ───────────────────────────────────────────────────────────────────────────────

internal object HrmArbitration {

    private const val FRESHNESS_MS: Long = 4_000L
    private const val EMA_ALPHA: Double = 0.35
    private const val MAX_JUMP_PER_SEC: Double = 18.0
    private const val MIN_ALPHA: Double = 0.20

    // External HRM smoothing state
    private var lastExternalSmoothed: Double? = null
    private var lastExternalSmoothedAtMs: Long = 0L

    // FTMS-native HR (raw)
    private var lastFtmsBpm: Int? = null
    private var lastFtmsAtMs: Long = 0L

    // External HRM (post-smoothing, as Int)
    private var lastExternalBpm: Int? = null
    private var lastExternalAtMs: Long = 0L

    /** Feed external HRM bpm samples (Bluetooth Heart Rate service). */
    fun setExternalHeartRate(engine: BtEngine, bpm: Int?) {
        val now = System.currentTimeMillis()
        val smoothed = smoothExternal(bpm, now)
        lastExternalBpm = smoothed
        lastExternalAtMs = now

        // Keep the dedicated external HR flow/timestamp for diagnostics & UI tags.
        engine.state.setExternalHeartRate(smoothed)
        engine.state.updateLastExternalAt(now)

        expireStaleSourcesIfNeeded(now)
        choosePublishAndZones(engine, now)
    }

    /** Feed FTMS-native HR bpm samples (from machine FTMS measurement frames). */
    fun setFtmsHeartRate(engine: BtEngine, bpm: Int?) {
        val now = System.currentTimeMillis()
        lastFtmsBpm = bpm
        lastFtmsAtMs = now

        expireStaleSourcesIfNeeded(now)
        choosePublishAndZones(engine, now)
    }

    private fun expireStaleSourcesIfNeeded(now: Long = System.currentTimeMillis()) {
        if (lastExternalAtMs != 0L && now - lastExternalAtMs > FRESHNESS_MS) {
            lastExternalBpm = null
            lastExternalSmoothed = null
            lastExternalSmoothedAtMs = 0L
        }
        if (lastFtmsAtMs != 0L && now - lastFtmsAtMs > FRESHNESS_MS) {
            lastFtmsBpm = null
        }
    }

    private fun smoothExternal(sample: Int?, nowMs: Long): Int? {
        sample ?: return null
        val prev = lastExternalSmoothed
        if (prev == null) {
            lastExternalSmoothed = sample.toDouble()
            lastExternalSmoothedAtMs = nowMs
            return sample
        }

        val dtSec = ((nowMs - lastExternalSmoothedAtMs).coerceAtLeast(1L)).toDouble() / 1000.0
        val maxJump = MAX_JUMP_PER_SEC * dtSec

        val clampedRaw = when {
            sample > prev + maxJump -> prev + maxJump
            sample < prev - maxJump -> prev - maxJump
            else -> sample.toDouble()
        }

        val alpha = (EMA_ALPHA * dtSec).coerceAtLeast(MIN_ALPHA).coerceAtMost(0.85)
        val smoothed = prev * (1.0 - alpha) + clampedRaw * alpha

        lastExternalSmoothed = smoothed
        lastExternalSmoothedAtMs = nowMs
        return kotlin.math.round(smoothed).toInt()
    }

    /** Winner selection: external wins if fresh; else FTMS if fresh; else null. */
    private fun pickWinner(
        ftms: Int?,  ftmsAt: Long,
        ext:  Int?,  extAt: Long,
        now:  Long
    ): Pair<Int?, BtEngineState.HrSource> {
        val ftmsFresh = ftms != null && (now - ftmsAt) <= FRESHNESS_MS
        val extFresh  = ext  != null && (now - extAt)  <= FRESHNESS_MS

        val chosen = when {
            extFresh  -> ext
            ftmsFresh -> ftms
            else      -> null
        }

        val src = when {
            chosen == null -> BtEngineState.HrSource.UNKNOWN
            chosen == ext  -> BtEngineState.HrSource.EXTERNAL_HRM
            else           -> BtEngineState.HrSource.UNKNOWN // FTMS-native (no dedicated enum)
        }
        return chosen to src
    }

    /** Full pipeline: choose source → publish (incl. series/averages) → zones (instant & average). */
    private fun choosePublishAndZones(engine: BtEngine, now: Long = System.currentTimeMillis()) {
        val s = engine.state
        val ftms = lastFtmsBpm
        val ext  = lastExternalBpm

        val (chosen, src) = pickWinner(
            ftms = ftms, ftmsAt = lastFtmsAtMs,
            ext  = ext,  extAt  = lastExternalAtMs,
            now  = now
        )

        if (chosen != null) {
            // appendHeartRateSample updates heartRate + averageHeartRate + hrSeries
            s.appendHeartRateSample(chosen, now)
        } else {
            s.setHeartRate(null)
        }
        s.setHeartRateSource(src)

        computeAndPublishZones(engine)
    }

    /**
     * Re-compute zones using **saved user prefs** already injected into BtEngineState.
     *
     * This computes both:
     *   • Instantaneous HR zone      → state.heartRateZone
     *   • Average HR-based zone     → state.averageHeartRateZone
     */
    private fun computeAndPublishZones(engine: BtEngine) {
        val s = engine.state

        val mode = s.hrZoneModeFromApp?.value
        val max  = s.hrMaxFromApp?.value
        val rest = s.restingHrFromApp?.value

        // Instantaneous HR zone
        val instantBpm = s.heartRate.value
        val instantZone = instantBpm?.let {
            computeZoneFromPrefs(
                bpm   = it,
                mode  = mode,
                maxHr = max,
                restHr = rest
            )
        }

        // Average HR-based zone (may be smoother / more stable than instant zone)
        val avgBpm = s.averageHeartRate.value
        val avgZone = avgBpm?.let {
            computeZoneFromPrefs(
                bpm   = it,
                mode  = mode,
                maxHr = max,
                restHr = rest
            )
        }

        s.setHeartRateZone(instantZone)
        s.setAverageHeartRateZone(avgZone)
    }

    /**
     * Standalone helper so we can reuse it from reselect()/recalcZone().
     *
     * @return Int? zone (0..5) or null if zone mode is OFF / not configured / insufficient data.
     */
    private fun computeZoneFromPrefs(
        bpm: Int,
        mode: HrZoneMode?,
        maxHr: Int?,
        restHr: Int?
    ): Int? {
        // 1) Mode missing or OFF → no zone
        if (mode == null || mode == HrZoneMode.Off) {
            return null
        }

        // 2) Per-mode ratio
        val ratio: Double = when (mode) {
            HrZoneMode.MaxPercent -> {
                if (maxHr == null || maxHr <= 0) return null
                bpm.toDouble() / maxHr.toDouble()
            }
            HrZoneMode.HeartRateReserve -> {
                // HRR needs both Max and Rest, and Max > Rest
                if (maxHr == null || restHr == null) return null
                if (maxHr <= restHr) return null
                (bpm - restHr).toDouble() / (maxHr - restHr).toDouble()
            }
            HrZoneMode.Off -> return null  // handled above, but keep for completeness
        }

        val clamped = ratio.coerceIn(0.0, 1.0)

        return when {
            clamped < 0.50 -> 0
            clamped < 0.60 -> 1
            clamped < 0.70 -> 2
            clamped < 0.80 -> 3
            clamped < 0.90 -> 4
            else           -> 5
        }
    }

    /** Force re-selection using current cached source values. */
    fun reselect(engine: BtEngine) {
        val now = System.currentTimeMillis()
        expireStaleSourcesIfNeeded(now)
        choosePublishAndZones(engine, now)
    }

    /**
     * Recalculate zones from the already-published canonical HR + averages.
     *
     * This is intended for "prefs changed" cases (hrMax/rest/mode), not for
     * re-selecting a winner between FTMS vs external HRM.
     */
    fun recalcZone(engine: BtEngine) {
        computeAndPublishZones(engine)
    }
}

// ────────────────────────────────────────────────────────────────────────────────
// PACE ARBITRATION (instant + average)
// ────────────────────────────────────────────────────────────────────────────────

internal object PaceArbitration {

    private const val STALE_MS: Long = 3_000L
    private const val MIN_DIST_LOOKBACK_SEC: Long = 5L
    private const val TARGET_LOOKBACK_MS: Long = 10_000L
    private const val MAX_BUFFER_MS: Long = 30_000L   // keep ~30s of distance snapshots

    // ── source 1: native FTMS instant pace (sec/km) ──────────────────────────────
    private var lastNativePaceSecPerKm: Double? = null
    private var lastNativeAtMs: Long = 0L

    // ── source 2: instant pace from speed (sec/km) ───────────────────────────────
    private var lastSpeedDerivedPaceSecPerKm: Double? = null
    private var lastSpeedAtMs: Long = 0L

    // ── source 3: instant pace from distance over time (sec/km) ─────────────────
    private val distanceBuffer = ArrayDeque<Pair<Long, Double>>()  // (timestampMs, distanceM)
    private var lastDistanceDerivedPaceSecPerKm: Double? = null
    private var lastDistanceDerivedAtMs: Long = 0L
    private var lastDistanceLookbackSec: Long = 0L

    // ── AVERAGE pace caches (sec/km) ─────────────────────────────────────────────

    // 1) Native FTMS average pace (sec/km)
    private var lastNativeAvgPaceSecPerKm: Double? = null
    private var lastNativeAvgAtMs: Long = 0L

    // 2) Average pace derived from session average speed (sec/km)
    private var lastSpeedDerivedAvgPaceSecPerKm: Double? = null
    private var lastSpeedAvgAtMs: Long = 0L

    // 3) Average pace derived from total distance + elapsed time (sec/km)
    private var lastDistanceDerivedAvgPaceSecPerKm: Double? = null
    private var lastDistanceDerivedAvgAtMs: Long = 0L

    // ── Public entrypoints for INSTANT pace ──────────────────────────────────────

    fun setNativePace(engine: BtEngine, paceSecPerKm: Double?) {
        val now = System.currentTimeMillis()
        lastNativePaceSecPerKm = paceSecPerKm
        lastNativeAtMs = now
        arbitrateAndPublish(engine, now)
    }

    fun setSpeedMetersPerSecond(engine: BtEngine, speedMps: Double?) {
        val now = System.currentTimeMillis()
        val paceSecPerKm = speedMps?.let { mps ->
            if (mps <= 0.0) null else 1000.0 / mps
        }
        lastSpeedDerivedPaceSecPerKm = paceSecPerKm
        lastSpeedAtMs = now

        // Also derive an average pace from current session average speed (if available)
        val avgSpeedKph = engine.state.avgSpeedKph.value
        val avgPaceSecPerKm = avgSpeedKph?.let { kph ->
            if (kph <= 0.0) null else 3600.0 / kph
        }
        lastSpeedDerivedAvgPaceSecPerKm = avgPaceSecPerKm
        if (avgPaceSecPerKm != null) {
            lastSpeedAvgAtMs = now
        }

        arbitrateAndPublish(engine, now)
    }

    fun setDistanceSnapshot(
        engine: BtEngine,
        distanceMeters: Double,
        atMs: Long = System.currentTimeMillis()
    ) {
        // Maintain rolling distance buffer for instant pace derivation
        distanceBuffer.addLast(atMs to distanceMeters)

        val cutoff = atMs - MAX_BUFFER_MS
        while (distanceBuffer.isNotEmpty() && distanceBuffer.first().first < cutoff) {
            distanceBuffer.removeFirst()
        }

        // Instant pace over ~10s lookback
        val derivedInstant = computeDistanceDerivedPace(atMs)
        if (derivedInstant != null) {
            lastDistanceDerivedPaceSecPerKm = derivedInstant.first
            lastDistanceLookbackSec = derivedInstant.second
            lastDistanceDerivedAtMs = atMs
        }

        // Average pace from total distance + elapsed time (if available)
        val state = engine.state
        val totalDistM = state.totalDistanceMeters.value
        val elapsedSec = state.elapsedTimeSec.value
        val avgPaceSecPerKm = if (
            totalDistM != null && totalDistM > 0 &&
            elapsedSec != null && elapsedSec > 0
        ) {
            val speedMps = totalDistM.toDouble() / elapsedSec.toDouble()
            if (speedMps > 0.0) 1000.0 / speedMps else null
        } else {
            null
        }

        if (avgPaceSecPerKm != null) {
            lastDistanceDerivedAvgPaceSecPerKm = avgPaceSecPerKm
            lastDistanceDerivedAvgAtMs = atMs
        }

        arbitrateAndPublish(engine, atMs)
    }

    // ── Public entrypoint for NATIVE AVERAGE pace ────────────────────────────────

    /**
     * Feed FTMS-native **average** pace (sec/km).
     * Typically called from the same FTMS parser path that delivers native instant pace.
     */
    fun setNativeAvgPace(engine: BtEngine, avgPaceSecPerKm: Double?) {
        val now = System.currentTimeMillis()
        lastNativeAvgPaceSecPerKm = avgPaceSecPerKm
        lastNativeAvgAtMs = now
        arbitrateAndPublish(engine, now)
    }

    // ── Core arbitration (instant + average) ─────────────────────────────────────

    private fun arbitrateAndPublish(engine: BtEngine, now: Long) {
        expireStaleSources(now)

        // Instant freshness
        val nativeFresh = lastNativePaceSecPerKm != null && (now - lastNativeAtMs) <= STALE_MS
        val speedFresh  = lastSpeedDerivedPaceSecPerKm != null && (now - lastSpeedAtMs) <= STALE_MS
        val distFresh   = lastDistanceDerivedPaceSecPerKm != null &&
                (now - lastDistanceDerivedAtMs) <= STALE_MS &&
                lastDistanceLookbackSec >= MIN_DIST_LOOKBACK_SEC

        val chosenInstantSecPerKm: Double? = when {
            nativeFresh -> lastNativePaceSecPerKm
            speedFresh  -> lastSpeedDerivedPaceSecPerKm
            distFresh   -> lastDistanceDerivedPaceSecPerKm
            else        -> null
        }

        publishInstantToState(engine, chosenInstantSecPerKm)

        // Average freshness
        val nativeAvgFresh = lastNativeAvgPaceSecPerKm != null &&
                (now - lastNativeAvgAtMs) <= STALE_MS
        val speedAvgFresh = lastSpeedDerivedAvgPaceSecPerKm != null &&
                (now - lastSpeedAvgAtMs) <= STALE_MS
        val distAvgFresh = lastDistanceDerivedAvgPaceSecPerKm != null &&
                (now - lastDistanceDerivedAvgAtMs) <= STALE_MS

        val chosenAvgSecPerKm: Double? = when {
            nativeAvgFresh -> lastNativeAvgPaceSecPerKm
            speedAvgFresh  -> lastSpeedDerivedAvgPaceSecPerKm
            distAvgFresh   -> lastDistanceDerivedAvgPaceSecPerKm
            else           -> null
        }

        publishAverageToState(engine, chosenAvgSecPerKm)
    }

    private fun expireStaleSources(now: Long) {
        // Instant sources
        if (lastNativeAtMs != 0L && now - lastNativeAtMs > STALE_MS) {
            lastNativePaceSecPerKm = null
        }
        if (lastSpeedAtMs != 0L && now - lastSpeedAtMs > STALE_MS) {
            lastSpeedDerivedPaceSecPerKm = null
        }
        if (lastDistanceDerivedAtMs != 0L && now - lastDistanceDerivedAtMs > STALE_MS) {
            lastDistanceDerivedPaceSecPerKm = null
            lastDistanceLookbackSec = 0L
        }

        // Average sources
        if (lastNativeAvgAtMs != 0L && now - lastNativeAvgAtMs > STALE_MS) {
            lastNativeAvgPaceSecPerKm = null
        }
        if (lastSpeedAvgAtMs != 0L && now - lastSpeedAvgAtMs > STALE_MS) {
            lastSpeedDerivedAvgPaceSecPerKm = null
        }
        if (lastDistanceDerivedAvgAtMs != 0L && now - lastDistanceDerivedAvgAtMs > STALE_MS) {
            lastDistanceDerivedAvgPaceSecPerKm = null
        }
    }

    // ── Helpers for distance-based instant pace ──────────────────────────────────

    private fun computeDistanceDerivedPace(nowMs: Long): Pair<Double, Long>? {
        if (distanceBuffer.isEmpty()) return null

        val minLookbackMs = MIN_DIST_LOOKBACK_SEC * 1000
        val targetTime = nowMs - TARGET_LOOKBACK_MS

        var chosen: Pair<Long, Double>? = null
        for (i in distanceBuffer.size - 1 downTo 0) {
            val (ts, dist) = distanceBuffer.elementAt(i)
            if (ts <= targetTime) {
                chosen = ts to dist
                break
            }
        }

        if (chosen == null) {
            val earliest = distanceBuffer.first()
            val dt = nowMs - earliest.first
            if (dt >= minLookbackMs) {
                chosen = earliest
            } else {
                return null
            }
        }

        val (thenTs, thenDistM) = chosen
        val nowDistM = distanceBuffer.last().second
        val dtMs = nowMs - thenTs
        if (dtMs < minLookbackMs) return null

        val deltaM = nowDistM - thenDistM
        if (deltaM <= 0.0) return null

        val dtSec = dtMs / 1000.0
        val speedMps = deltaM / dtSec
        if (speedMps <= 0.0) return null

        val paceSecPerKm = 1000.0 / speedMps
        return paceSecPerKm to (dtMs / 1000L)
    }

    // ── State publishing helpers ────────────────────────────────────────────────

    private fun publishInstantToState(engine: BtEngine, paceSecPerKm: Double?) {
        val s = engine.state
        if (paceSecPerKm == null) {
            runCatching { s.setInstantPace(null) }
            return
        }
        val minutesPerKm: Double = paceSecPerKm / 60.0
        s.setInstantPace(minutesPerKm)
    }

    private fun publishAverageToState(engine: BtEngine, paceSecPerKm: Double?) {
        val s = engine.state
        if (paceSecPerKm == null) {
            runCatching { s.setAvgPace(null) }
            return
        }
        val minutesPerKm: Double = paceSecPerKm / 60.0
        s.setAvgPace(minutesPerKm)
    }
}

// ────────────────────────────────────────────────────────────────────────────────
// ENERGY ARBITRATION (unchanged)
// ────────────────────────────────────────────────────────────────────────────────

internal object EnergyArbitration {

    private var lastHrmEnergyKcal: Double? = null
    private var lastWearEnergyKcal: Double? = null
    private var lastFtmsEnergyKcal: Double? = null

    fun setHrmEnergy(engine: BtEngine, energyKcal: Double?) {
        lastHrmEnergyKcal = energyKcal
        arbitrateAndPublish(engine)
    }

    fun setWearEnergy(engine: BtEngine, energyKcal: Double?) {
        lastWearEnergyKcal = energyKcal
        arbitrateAndPublish(engine)
    }

    fun setFtmsEnergy(engine: BtEngine, energyKcal: Double?) {
        lastFtmsEnergyKcal = energyKcal
        arbitrateAndPublish(engine)
    }

    private fun arbitrateAndPublish(engine: BtEngine) {
        val chosen = lastHrmEnergyKcal
            ?: lastWearEnergyKcal
            ?: lastFtmsEnergyKcal

        publishToState(engine, chosen)
    }

    private fun publishToState(engine: BtEngine, energyKcal: Double?) {
        val s = engine.state
        runCatching {
            s.setExpEnergyTotal(energyKcal?.roundToInt())
        }
    }
}
