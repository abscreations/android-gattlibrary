/**
 * btEngine.GattFtmsParsers.kt
 *
 * ROLE
 *  - Stateless FTMS (Fitness Machine Service) measurement parsers used by [FtmsProtocolHandler].
 *  - Decodes raw FTMS data characteristics for all supported machine types and pushes
 *    normalized values into [BtEngineState] (time-series, lap metrics, display fields).
 *
 * SUPPORTED FTMS MEASUREMENT FRAMES
 *  - doBike(...)       → Cycling (UUID 0x2AD2)
 *  - doRower(...)      → Rowing machine (UUID 0x2AD1)
 *  - doTreadmill(...)  → Treadmill (UUID 0x2ACD)
 *  - doElliptical(...) → Elliptical (UUID 0x2ACE)
 *  - doStepper(...)    → Stair stepper (UUID 0x2ACF)
 *  - doClimber(...)    → Stair climber (UUID 0x2AD0)
 *
 * For each frame type the parser:
 *    • Reads and interprets FTMS flag fields.
 *    • Walks the packed payload in spec order with bounds checks.
 *    • Converts FTMS units (0.01 km/h, 1/2 spm, 0.1 levels, etc.) into neutral / SI units.
 *    • Updates the appropriate fields on [BtEngine.state] using dedicated setters/appenders.
 *
 * METRICS & STATE UPDATES
 *  - Speed & distance
 *      • Instant & average speed (km/h) + mph + unit-aware display speed.
 *      • Peak speed tracking (kph / mph / display).
 *      • Total distance in meters + unit-aware display distance (km / mi).
 *      • Distance samples are also added to a bounded distance series for derived rowing speed.
 *
 *  - Cadence, strokes, strides, steps, floors
 *      • Instant & average cadence (spm) from various FTMS encodings (1/2 units, tenths, etc.).
 *      • Stroke rate + stroke count for rowing, with peak stroke rate tracking.
 *      • Strides → steps conversion (via STRIDES_TO_STEPS) for ellipticals/climbers.
 *      • Floors and total steps for steppers/climbers.
 *
 *  - Incline, ramp, elevation, belt force
 *      • Inclination samples (degrees) and ramp angle for treadmills/ellipticals.
 *      • Positive / negative elevation gain variants depending on machine type.
 *      • Belt force + belt-derived instantaneous power for treadmills.
 *
 *  - Power & energy
 *      • Instant, average and peak power (watts), including legacy smoothing logic
 *        and time-series updates via appendPowerSample(...).
 *      • Machine-reported energy (kcal) and MET values.
 *
 *  - Heart rate & elapsed time
 *      • FTMS-native heart rate samples forwarded into HR arbitration (see below).
 *      • FTMS elapsed time is treated as the authoritative session elapsed time and
 *        drives lap elapsed calculations.
 *
 * LAP & DERIVED METRICS
 *  - Maintains lap baselines in [BtEngineState] (start distance, elapsed, strokes, steps).
 *  - Continuously updates per-lap:
 *      • lapDistanceMeters + lapDistanceDisplay.
 *      • lapElapsedTimeSec.
 *      • lapAvgSpeedMps + lapAvgSpeedDisplay (km/h or mi/h).
 *      • lapRunPaceMinPerUnit (min/km or min/mi).
 *      • lapRowPaceMinPer500m (rowing-specific).
 *      • lapStrokeCount, lapSteps, lapCadenceSpm (including incremental averaging).
 *
 * ARBITRATION INTEGRATION
 *  - Heart rate:
 *      • FTMS HR frames call HrmArbitration.setFtmsHeartRate(engine, bpm) so the
 *        canonical heart rate (and zones) are chosen across FTMS vs. external HRM.
 *
 *  - Pace:
 *      • Native FTMS **instant** pace (treadmill/rower) is fed into
 *        PaceArbitration.setNativePace(engine, secPerKm).
 *      • Native FTMS **average** pace (treadmill/rower) is fed into
 *        PaceArbitration.setNativeAvgPace(engine, secPerKm).
 *      • When FTMS only reports **average speed**, this parser computes a synthetic
 *        average pace (sec/km) from km/h and feeds it into
 *        PaceArbitration.setNativeAvgPace(...) instead of relying on speed-derived
 *        paths inside the arbitrator.
 *      • Instant speed updates still call PaceArbitration.setSpeedMetersPerSecond(...)
 *        so the arbitrator can derive fallback instant pace when native pace is absent.
 *      • Distance updates feed BtEngineState.appendDistanceMetersSample(...), which
 *        PaceArbitration can use (via its own distance snapshots) to derive pace from
 *        distance/time when speed/pace are missing.
 *
 *  - Energy:
 *      • Machine energy is routed via EnergyArbitration.setFtmsEnergy(...) instead of
 *        writing directly to expEnergyTotal, so it can be consolidated with other sources.
 *
 * IMPLEMENTATION NOTES
 *  - The object itself is stateless; all mutable state lives in [BtEngineState] and the
 *    arbitration helpers (HrmArbitration, PaceArbitration, EnergyArbitration).
 *  - Uses small byte helpers (has / u8 / u16 / s16 / skip) and a logSet(...) helper to
 *    minimize boilerplate and only log state changes when logging is enabled.
 *  - Any value that participates in graphs or averages goes through append*Sample(...)
 *    so sliding-window series and running means remain consistent across the engine.
 */

package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.Manifest
import androidx.annotation.RequiresPermission
import timber.log.Timber
import kotlin.math.max
import com.gmail.absolutebollockscreations.Fitbridge.Debugging
import com.gmail.absolutebollockscreations.Fitbridge.DebugLevel

internal object BtEngineFtmsParsers {

    private const val TAG = "BtEngine" // or BtEngineState.TAG

    // Logging knobs:
    //  - Metric values (decoded): DebugLevel >= 4
    //  - Raw Bluetooth payloads: DebugLevel > 4
    private const val LOG_FRAMES = true
    private const val LOG_METRIC_VALUES = true

    private fun canLogFrames(): Boolean =
        Debugging && DebugLevel > 4 && LOG_FRAMES

    private fun canLogMetrics(): Boolean =
        Debugging && DebugLevel >= 4 && LOG_METRIC_VALUES

    // ─────────────────────────────────────────────────────────────
    // Unit conversion constants (local)
    // ─────────────────────────────────────────────────────────────
    private const val MPS_TO_KPH = 3.6
    private const val MPS_TO_MPH = 2.23694
    private const val M_TO_MI    = 0.000621371
    private const val M_TO_KM    = 0.001
    private const val M_PER_KM   = 1000.0
    private const val M_PER_MILE = 1609.344
    private const val ROW_PACE_M = 500.0
    private const val STRIDES_TO_STEPS = 2.0 // device-dependent; template default

    private const val UNIT_METRIC = 0
    private const val UNIT_IMPERIAL = 1

    // ─────────────────────────────────────────────────────────────
    // Public entry points (called by FtmsProtocolHandler)
    // ─────────────────────────────────────────────────────────────

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun doBike(engine: BtEngine, value: ByteArray) {
        if (canLogFrames()) {
            Timber.tag(TAG).d("FTMS Bike (2AD2) len=%d %s", value.size, value.toHex())
        }
        var off = 0
        if (!has(value, off, 2)) return
        val flags = u16(value, off); off += 2
        if (canLogMetrics()) {
            Timber.tag(TAG).d("Bike flags=0x%04X", flags)
        }

        if ((flags and Flags.BIKE_MORE) == 0) off = readSpeed(engine, value, off)
        if ((flags and Flags.BIKE_AVGSPEED) != 0) off = readAvgSpeed(engine, value, off)
        if ((flags and Flags.BIKE_CADENCE) != 0) off = readCadence(engine, value, off, halfUnits = true)
        if ((flags and Flags.BIKE_AVGCAD) != 0) off = readAvgCadence(engine, value, off, halfUnits = true)
        if ((flags and Flags.BIKE_TOTALDIST) != 0) off = readDistance(engine, value, off)
        if ((flags and Flags.BIKE_RESISTANCE) != 0) off = readResistance(engine, value, off, div10 = false)
        if ((flags and Flags.BIKE_INSTPOWER) != 0) off = readPower(engine, value, off)
        if ((flags and Flags.BIKE_AVGPOWER) != 0) off = readAvgPower(engine, value, off)
        if ((flags and Flags.BIKE_EXPENERGY) != 0) off = readEnergy(engine, value, off)

        if ((flags and Flags.BIKE_HEARTRATE) != 0) off = readFtmsHeartRate(engine, value, off)
        if ((flags and Flags.BIKE_METABOLIC) != 0) off = readMet(engine, value, off)
        if ((flags and Flags.BIKE_ELAPSED) != 0) off = readElapsed(engine, value, off)
        if ((flags and Flags.BIKE_REMAINING) != 0) skip(off, 2)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun doRower(engine: BtEngine, value: ByteArray) {
        if (canLogFrames()) {
            Timber.tag(TAG).d("FTMS Rower (2AD1) len=%d %s", value.size, value.toHex())
        }
        var off = 0
        if (!has(value, off, 2)) return
        val flags = u16(value, off); off += 2
        if (canLogMetrics()) {
            Timber.tag(TAG).d("Rower flags=0x%04X", flags)
        }

        if ((flags and Flags.ROW_MORE) == 0) off = readStrokes(engine, value, off)
        if ((flags and Flags.ROW_AVGSTROKE) != 0) off = readAvgStrokes(engine, value, off)
        if ((flags and Flags.ROW_TOTALDIST) != 0) off = readDistance(engine, value, off, rower = true)
        if ((flags and Flags.ROW_INSTPACE) != 0) {
            off = readPace(engine, value, off, times10 = false, per500m = true)
        }
        if ((flags and Flags.ROW_AVGPACE) != 0) {
            off = readAvgPace(engine, value, off, times10 = false, per500m = true)
        }
        if ((flags and Flags.ROW_INSTPOWER) != 0) off = readPower(engine, value, off)
        if ((flags and Flags.ROW_AVGPOWER) != 0) off = readAvgPower(engine, value, off)
        if ((flags and Flags.ROW_RESISTANCE) != 0) off = readResistance(engine, value, off, div10 = false)
        if ((flags and Flags.ROW_EXPENERGY) != 0) off = readEnergy(engine, value, off)

        if ((flags and Flags.ROW_HEARTRATE) != 0) off = readFtmsHeartRate(engine, value, off)
        if ((flags and Flags.ROW_METABOLIC) != 0) off = readMet(engine, value, off)
        if ((flags and Flags.ROW_ELAPSED) != 0) off = readElapsed(engine, value, off)
        if ((flags and Flags.ROW_REMAINING) != 0) skip(off, 2)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun doTreadmill(engine: BtEngine, value: ByteArray) {
        if (canLogFrames()) {
            Timber.tag(TAG).d("FTMS Treadmill (2ACD) len=%d %s", value.size, value.toHex())
        }
        var off = 0
        if (!has(value, off, 2)) return
        val flags = u16(value, off); off += 2
        if (canLogMetrics()) {
            Timber.tag(TAG).d("Treadmill flags=0x%04X", flags)
        }

        if ((flags and Flags.TREAD_MOREDATA) == 0) off = readSpeed(engine, value, off)
        if ((flags and Flags.TREAD_AVGSPEED) != 0) off = readAvgSpeed(engine, value, off)
        if ((flags and Flags.TREAD_TOTALDISTANCE) != 0) off = readDistance(engine, value, off)
        if ((flags and Flags.TREAD_INCLRAMPANGLE) != 0) off = readInclineV1(engine, value, off)
        if ((flags and Flags.TREAD_ELEVATIONGAIN) != 0) off = readElevation(engine, value, off, mode = 2)
        if ((flags and Flags.TREAD_INSTANTPACE) != 0) off = readPace(engine, value, off, times10 = true)
        if ((flags and Flags.TREAD_AVGPACE) != 0) off = readAvgPace(engine, value, off, times10 = true)
        if ((flags and Flags.TREAD_EXPENERGY) != 0) off = readEnergy(engine, value, off)

        if ((flags and Flags.TREAD_HEARTRATE) != 0) off = readFtmsHeartRate(engine, value, off)
        if ((flags and Flags.TREAD_METABOLIC) != 0) off = readMet(engine, value, off)
        if ((flags and Flags.TREAD_ELAPSEDTIME) != 0) off = readElapsed(engine, value, off)
        if ((flags and Flags.TREAD_REMAININGTIME) != 0) off = skip(off, 2)
        if ((flags and Flags.TREAD_BELTFORCEPOWER) != 0) readBeltForce(engine, value, off)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun doElliptical(engine: BtEngine, value: ByteArray) {
        if (canLogFrames()) {
            Timber.tag(TAG).d("FTMS Elliptical (2ACE) len=%d %s", value.size, value.toHex())
        }
        var off = 0
        if (!has(value, off, 3)) return
        val low = u16(value, off); off += 2
        val hi = u8(value, off) shl 16; off += 1
        val flags = low + hi
        if (canLogMetrics()) {
            Timber.tag(TAG).d("Elliptical flags=0x%06X", flags)
        }

        if ((flags and Flags.ELL_MORE) == 0) off = readSpeed(engine, value, off)
        if ((flags and Flags.ELL_AVGSPEED) != 0) off = readAvgSpeed(engine, value, off)
        if ((flags and Flags.ELL_TOTALDIST) != 0) off = readDistance(engine, value, off)
        if ((flags and Flags.ELL_STEPCOUNT) != 0) off = readEllipticalCadence(engine, value, off)
        if ((flags and Flags.ELL_STRIDECOUNT) != 0) off = readStrides(engine, value, off, tenths = true)
        if ((flags and Flags.ELL_ELEVGAIN) != 0) off = readElevation(engine, value, off, mode = 3)
        if ((flags and Flags.ELL_RESISTANCE) != 0) off = readResistance(engine, value, off, div10 = true)
        if ((flags and Flags.ELL_INCLINATION) != 0) off = readInclineV2(engine, value, off)
        if ((flags and Flags.ELL_INSTPOWER) != 0) off = readPower(engine, value, off)
        if ((flags and Flags.ELL_AVGPOWER) != 0) off = readAvgPower(engine, value, off)
        if ((flags and Flags.ELL_EXPENERGY) != 0) off = readEnergy(engine, value, off)

        if ((flags and Flags.ELL_HEARTRATE) != 0) off = readFtmsHeartRate(engine, value, off)
        if ((flags and Flags.ELL_METABOLIC) != 0) off = readMet(engine, value, off)
        if ((flags and Flags.ELL_ELAPSED) != 0) off = readElapsed(engine, value, off)
        if ((flags and Flags.ELL_REMAINING) != 0) skip(off, 2)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun doStepper(engine: BtEngine, value: ByteArray) {
        if (canLogFrames()) {
            Timber.tag(TAG).d("FTMS Stepper (2ACF) len=%d %s", value.size, value.toHex())
        }
        var off = 0
        if (!has(value, off, 2)) return
        val flags = u16(value, off); off += 2
        if (canLogMetrics()) {
            Timber.tag(TAG).d("Stepper flags=0x%04X", flags)
        }

        if ((flags and Flags.STEP_MORE) == 0) off = readFloors(engine, value, off, mode = 2)
        if ((flags and Flags.STEP_STEPRATE) != 0) off = readCadence(engine, value, off, halfUnits = false)
        if ((flags and Flags.STEP_AVGSTEPR) != 0) off = readAvgCadence(engine, value, off, halfUnits = false)
        if ((flags and Flags.STEP_ELEVGAIN) != 0) off = readElevation(engine, value, off, mode = 1)
        if ((flags and Flags.STEP_EXPENERGY) != 0) off = readEnergy(engine, value, off)

        if ((flags and Flags.STEP_HEARTRATE) != 0) off = readFtmsHeartRate(engine, value, off)
        if ((flags and Flags.STEP_METABOLIC) != 0) off = readMet(engine, value, off)
        if ((flags and Flags.STEP_ELAPSED) != 0) off = readElapsed(engine, value, off)
        if ((flags and Flags.STEP_REMAINING) != 0) skip(off, 2)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun doClimber(engine: BtEngine, value: ByteArray) {
        if (canLogFrames()) {
            Timber.tag(TAG).d("FTMS Climber (2AD0) len=%d %s", value.size, value.toHex())
        }
        var off = 0
        if (!has(value, off, 2)) return
        val flags = u16(value, off); off += 2
        if (canLogMetrics()) {
            Timber.tag(TAG).d("Climber flags=0x%04X", flags)
        }

        if ((flags and Flags.CLIMB_MORE) == 0) off = readFloors(engine, value, off, mode = 1)
        if ((flags and Flags.CLIMB_STEPRATE) != 0) off = readCadence(engine, value, off, halfUnits = false)
        if ((flags and Flags.CLIMB_AVGSTEPR) != 0) off = readAvgCadence(engine, value, off, halfUnits = false)
        if ((flags and Flags.CLIMB_ELEVGAIN) != 0) off = readElevation(engine, value, off, mode = 1)
        if ((flags and Flags.CLIMB_STRIDE) != 0) off = readStrides(engine, value, off, tenths = false)
        if ((flags and Flags.CLIMB_EXPENERGY) != 0) off = readEnergy(engine, value, off)

        if ((flags and Flags.CLIMB_HEARTRATE) != 0) off = readFtmsHeartRate(engine, value, off)
        if ((flags and Flags.CLIMB_METABOLIC) != 0) off = readMet(engine, value, off)
        if ((flags and Flags.CLIMB_ELAPSED) != 0) off = readElapsed(engine, value, off)
        if ((flags and Flags.CLIMB_REMAINING) != 0) skip(off, 2)
    }

    // ─────────────────────────────────────────────────────────────
    // Field readers
    // ─────────────────────────────────────────────────────────────

    private fun readFtmsHeartRate(engine: BtEngine, b: ByteArray, off0: Int): Int {
        var off = off0
        if (has(b, off, 1)) {
            val hr = u8(b, off)
            // Merge FTMS-native HR with any external HRM via arbitration
            HrmArbitration.setFtmsHeartRate(engine, hr)
            if (canLogMetrics()) {
                Timber.tag(TAG).d("FTMS HR (bpm) → HrmArbitration: %d", hr)
            }
            off += 1
        }
        return off
    }

    private fun readStrokes(engine: BtEngine, b: ByteArray, off0: Int): Int {
        var off = off0
        if (has(b, off, 1)) {
            val sr = u8(b, off) / 2.0
            if (canLogMetrics()) {
                Timber.tag(TAG).d("Rower strokeRate (spm): %.1f", sr)
            }
            engine.state.appendStrokeRateSample(sr)
            off += 1
        }
        if (has(b, off, 2)) {
            val count = u16(b, off)
            if (canLogMetrics()) {
                Timber.tag(TAG).d("Rower strokeCount: %d", count)
            }
            logSet("strokeCount", engine.state.strokeCount.value, count, engine.state::setStrokeCount)
            off += 2
            updateLapStrokes(engine, count)
        }
        return off
    }

    private fun readAvgStrokes(engine: BtEngine, b: ByteArray, off0: Int): Int {
        var off = off0
        if (has(b, off, 1)) {
            val avg = u8(b, off) / 2.0
            if (canLogMetrics()) {
                Timber.tag(TAG).d("Rower average strokeRate (spm): %.1f", avg)
            }
            logSet("averageCadence", engine.state.averageCadence.value, avg, engine.state::setAverageCadence)
            off += 1
            feedLapCadenceAvg(engine, avg)
        }
        return off
    }

    // ─── Distance reader with rower branch (fixed to call state.*) ───────────────
    private fun readDistance(
        engine: BtEngine,
        b: ByteArray,
        off0: Int,
        rower: Boolean = false
    ): Int {
        var off = off0
        if (!has(b, off, 3)) return off

        var total = u16(b, off); off += 2
        total += (u8(b, off) shl 16); off += 1

        if (canLogMetrics()) {
            Timber.tag(TAG).d("FTMS totalDistance (m): %d", total)
        }

        // normal FTMS distance updates
        logSet("totalDistanceMeters", engine.state.totalDistanceMeters.value, total, engine.state::setTotalDistanceMeters)
        val disp = toDisplayDistance(engine, total)
        logSet("totalDistanceDisplay", engine.state.totalDistanceDisplay.value, disp, engine.state::setTotalDistanceDisplay)

        // keep distance series for rowing/speed derivation
        engine.state.appendDistanceMetersSample(total)

        // lap math
        updateLapDistance(engine, total)

        if (rower) {
            // <-- these must go through engine.state
            engine.state.updateLapRowingSpeed()
            engine.state.updateRowingSpeed()
        }

        return off
    }

    private fun readPace(
        engine: BtEngine,
        b: ByteArray,
        off0: Int,
        times10: Boolean,
        per500m: Boolean = false,
    ): Int {
        var off = off0
        if (has(b, off, 2)) {
            var pSecPerKm = u16(b, off).toDouble()
            if (times10) pSecPerKm /= 10.0
            if (per500m) pSecPerKm = rowerSecondsPer500mToSecondsPerKm(pSecPerKm)

            // Native instant pace goes through PaceArbitration (sec/km)
            PaceArbitration.setNativePace(engine, pSecPerKm)

            if (canLogMetrics()) {
                Timber.tag(TAG).d("FTMS instant pace (sec/km) → PaceArbitration: %.1f", pSecPerKm)
            }

            off += 2
        }
        return off
    }

    private fun readAvgPace(
        engine: BtEngine,
        b: ByteArray,
        off0: Int,
        times10: Boolean,
        per500m: Boolean = false,
    ): Int {
        var off = off0
        if (has(b, off, 2)) {
            var pSecPerKm = u16(b, off).toDouble()
            if (times10) pSecPerKm /= 10.0
            if (per500m) pSecPerKm = rowerSecondsPer500mToSecondsPerKm(pSecPerKm)

            // Native **average** pace uses the dedicated average pace endpoint
            PaceArbitration.setNativeAvgPace(engine, pSecPerKm)

            if (canLogMetrics()) {
                Timber.tag(TAG).d("FTMS average pace (sec/km) → PaceArbitration: %.1f", pSecPerKm)
            }

            off += 2
        }
        return off
    }

    private fun readPower(engine: BtEngine, b: ByteArray, off0: Int): Int {
        var off = off0
        if (has(b, off, 2)) {
            val v = s16(b, off)
            off += 2

            // Maintain historical smoothing accumulator (legacy behavior)
            engine.state.internalPowerSum += v.toDouble()
            engine.state.internalPowerCount++

            var inst = v
            val avg = engine.state.averagePower.value
            if (engine.state.internalPowerCount > 10 && avg != null) {
                val testAvg = engine.state.internalPowerSum / engine.state.internalPowerCount.toDouble()
                if (testAvg > 0 && testAvg + 2.0 < avg.toDouble()) {
                    val ratio = avg.toDouble() / testAvg
                    inst = (v * ratio).toInt()
                }
            }

            if (canLogMetrics()) {
                Timber.tag(TAG).d("FTMS instant power (W): %d", inst)
            }

            // Time-series + averages (updates instantPower + averagePower and series)
            engine.state.appendPowerSample(inst)

            // Peak tracking remains
            val peak = engine.state.peakPower.value ?: Int.MIN_VALUE
            if (inst > peak) {
                logSet("peakPower", engine.state.peakPower.value, inst, engine.state::setPeakPower)
            }
        }
        return off
    }

    private fun readAvgPower(engine: BtEngine, b: ByteArray, off0: Int): Int {
        var off = off0
        if (has(b, off, 2)) {
            val ap = s16(b, off)
            if (canLogMetrics()) {
                Timber.tag(TAG).d("FTMS average power (W): %d", ap)
            }
            logSet("averagePower", engine.state.averagePower.value, ap, engine.state::setAveragePower)
            off += 2
        }
        return off
    }

    private fun readResistance(engine: BtEngine, b: ByteArray, off0: Int, div10: Boolean): Int {
        var off = off0
        if (has(b, off, 2)) {
            var res = s16(b, off).toDouble()
            off += 2
            if (div10) res /= 10.0

            if (canLogMetrics()) {
                Timber.tag(TAG).d("FTMS resistance (level): %.1f", res)
            }

            // Time-series (updates resistance + series)
            engine.state.appendResistanceSample(res)
        }
        return off
    }

    private fun readEnergy(engine: BtEngine, b: ByteArray, off0: Int): Int {
        var off = off0
        if (has(b, off, 5)) {
            // Per FTMS spec: total energy (kcal) is uint16, followed by
            // energy per hour (uint16) and energy per minute (uint8).
            val machineKcal = u16(b, off)
            val perHourRaw = u16(b, off + 2)
            val perMinuteRaw = u8(b, off + 4)
            off += 5

            EnergyArbitration.setFtmsEnergy(engine, machineKcal.toDouble())
            engine.state.setExpEnergyRateKcalPerHour(
                ftmsEnergyRateKcalPerHour(perHourRaw, perMinuteRaw)
            )

            if (canLogMetrics()) {
                Timber.tag(TAG).d(
                    "FTMS energy: total=%d kcal rate=%s kcal/h",
                    machineKcal,
                    engine.state.expEnergyRateKcalPerHour.value?.toString() ?: "unavailable"
                )
            }
        }
        return off
    }

    private fun readMet(engine: BtEngine, b: ByteArray, off0: Int): Int {
        var off = off0
        if (has(b, off, 1)) {
            val met = u8(b, off).toDouble() / 10.0
            if (canLogMetrics()) {
                Timber.tag(TAG).d("FTMS MET value: %.1f", met)
            }
            logSet("metabolicEquivalent", engine.state.metabolicEquivalent.value, met, engine.state::setMetabolicEquivalent)
            off += 1
        }
        return off
    }

    /**
     * FTMS is now the single source of truth for elapsed time in this app.
     */
    private fun readElapsed(engine: BtEngine, b: ByteArray, off0: Int): Int {
        var off = off0
        if (!has(b, off, 2)) {
            return off
        }

        val ftmsElapsedSec = ((b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8))
        off += 2

        engine.state.setElapsedSeconds(ftmsElapsedSec)

        if (canLogMetrics()) {
            Timber.tag(TAG).d("FTMS elapsed time (s): %d", ftmsElapsedSec)
        }

        updateLapElapsed(engine, ftmsElapsedSec)

        engine.state.strokeCount.value?.let { sc ->
            updateLapStrokes(engine, sc)
        }

        return off
    }

    private fun readSpeed(engine: BtEngine, b: ByteArray, off0: Int): Int {
        var off = off0
        if (has(b, off, 2)) {
            val kph = u16(b, off) / 100.0
            off += 2

            if (canLogMetrics()) {
                Timber.tag(TAG).d("FTMS instant speed (km/h): %.2f", kph)
            }

            // Time-series + averages (updates speedKph + avgSpeedKph and series)
            engine.state.appendSpeedKphSample(kph)

            // NEW: feed instant speed to PaceArbitration for instant pace fallback
            val mps = kph / MPS_TO_KPH
            PaceArbitration.setSpeedMetersPerSecond(engine, mps)

            // Derived displays
            val mph = kphToMph(kph)
            logSet("speedMph", engine.state.speedMph.value, mph, engine.state::setSpeedMph)
            val disp = toDisplaySpeed(engine, kph)
            logSet("speedDisplay", engine.state.speedDisplay.value, disp, engine.state::setSpeedDisplay)

            // Peaks
            val pk = engine.state.peakSpeedKph.value ?: Double.MIN_VALUE
            if (kph > pk) {
                logSet("peakSpeedKph", engine.state.peakSpeedKph.value, kph, engine.state::setPeakSpeedKph)
                val mphPk = kphToMph(kph)
                logSet("peakSpeedMph", engine.state.peakSpeedMph.value, mphPk, engine.state::setPeakSpeedMph)
                val dispPk = toDisplaySpeed(engine, kph)
                logSet("peakSpeedDisplay", engine.state.peakSpeedDisplay.value, dispPk, engine.state::setPeakSpeedDisplay)
            }
        }
        return off
    }

    private fun readAvgSpeed(engine: BtEngine, b: ByteArray, off0: Int): Int {
        var off = off0
        if (has(b, off, 2)) {
            val kph = u16(b, off) / 100.0
            off += 2

            if (canLogMetrics()) {
                Timber.tag(TAG).d("FTMS average speed (km/h): %.2f", kph)
            }

            logSet("avgSpeedKph", engine.state.avgSpeedKph.value, kph, engine.state::setAvgSpeedKph)
            val disp = toDisplaySpeed(engine, kph)
            logSet("avgSpeedDisplay", engine.state.avgSpeedDisplay.value, disp, engine.state::setAvgSpeedDisplay)

            // NEW: compute a synthetic **average pace** (sec/km) from the FTMS average speed
            // and feed it into the dedicated average pace arbitration endpoint so the
            // canonical average pace does not depend on speed-derived heuristics inside
            // the arbitrator.
            val paceSecPerKm = if (kph > 0.0) 3600.0 / kph else null
            if (paceSecPerKm != null) {
                PaceArbitration.setNativeAvgPace(engine, paceSecPerKm)
                if (canLogMetrics()) {
                    Timber.tag(TAG).d(
                        "Synthetic AVG pace from avgSpeedKph (sec/km) → PaceArbitration: %.1f",
                        paceSecPerKm
                    )
                }
            }
        }
        return off
    }

    /** Treadmill v1 (incline int, ramp/10) */
    private fun readInclineV1(engine: BtEngine, b: ByteArray, off0: Int): Int {
        var off = off0
        if (has(b, off, 4)) {
            val inc = s16(b, off).toDouble(); off += 2
            val ramp = s16(b, off).toDouble() / 10.0; off += 2

            if (canLogMetrics()) {
                Timber.tag(TAG).d("FTMS incline (deg): %.1f, rampAngle (deg): %.1f", inc, ramp)
            }

            // Time-series (updates inclination + series)
            engine.state.appendInclineDegSample(inc)
            logSet("rampAngle", engine.state.rampAngle.value, ramp, engine.state::setRampAngle)
        }
        return off
    }

    /** Elliptical v2 (incline/10, ramp/10) */
    private fun readInclineV2(engine: BtEngine, b: ByteArray, off0: Int): Int {
        var off = off0
        if (has(b, off, 4)) {
            val inc = s16(b, off).toDouble() / 10.0; off += 2
            val ramp = s16(b, off).toDouble() / 10.0; off += 2

            if (canLogMetrics()) {
                Timber.tag(TAG).d("FTMS incline (deg): %.1f, rampAngle (deg): %.1f", inc, ramp)
            }

            // Time-series (updates inclination + series)
            engine.state.appendInclineDegSample(inc)
            logSet("rampAngle", engine.state.rampAngle.value, ramp, engine.state::setRampAngle)
        }
        return off
    }

    private fun readElevation(engine: BtEngine, b: ByteArray, off0: Int, mode: Int): Int {
        var off = off0
        when (mode) {
            1 -> { // +gain only
                if (has(b, off, 2)) {
                    val pos = u16(b, off).toDouble(); off += 2
                    if (canLogMetrics()) {
                        Timber.tag(TAG).d("FTMS elevation gain + (m): %.1f", pos)
                    }
                    engine.state.setElevationGains(pos, null)
                }
            }
            2 -> { // +gain/10 and -gain/10
                if (has(b, off, 4)) {
                    val pos = u16(b, off).toDouble() / 10.0; off += 2
                    val neg = u16(b, off).toDouble() / 10.0; off += 2
                    if (canLogMetrics()) {
                        Timber.tag(TAG).d("FTMS elevation gain +/− (m): +%.1f / −%.1f", pos, neg)
                    }
                    engine.state.setElevationGains(pos, neg)
                }
            }
            3 -> { // +gain and -gain
                if (has(b, off, 4)) {
                    val pos = u16(b, off).toDouble(); off += 2
                    val neg = u16(b, off).toDouble(); off += 2
                    if (canLogMetrics()) {
                        Timber.tag(TAG).d("FTMS elevation gain +/− (m): +%.1f / −%.1f", pos, neg)
                    }
                    engine.state.setElevationGains(pos, neg)
                }
            }
        }
        return off
    }

    private fun readBeltForce(engine: BtEngine, b: ByteArray, off0: Int): Int {
        var off = off0
        if (has(b, off, 4)) {
            val force = s16(b, off); off += 2
            val inst = s16(b, off); off += 2

            if (canLogMetrics()) {
                Timber.tag(TAG).d("FTMS beltForce (N-ish): %d, derived power (W): %d", force, inst)
            }

            // Keep the instantaneous value and graph series in sync.
            engine.state.appendForceOnBeltSample(force)

            // Power sample derived from belt force: feed time-series path
            engine.state.appendPowerSample(inst)

            val peak = engine.state.peakPower.value ?: Int.MIN_VALUE
            if (inst > peak) {
                logSet("peakPower", engine.state.peakPower.value, inst, engine.state::setPeakPower)
            }
        }
        return off
    }

    private fun readEllipticalCadence(engine: BtEngine, b: ByteArray, off0: Int): Int {
        var off = off0
        if (has(b, off, 4)) {
            val inst = u16(b, off).toDouble(); off += 2
            val avg  = u16(b, off).toDouble(); off += 2

            if (canLogMetrics()) {
                Timber.tag(TAG).d(
                    "Elliptical cadence (spm): instant=%.1f, avg=%.1f",
                    inst, avg
                )
            }

            // Time-series (updates instantCadence + averageCadence via running mean)
            engine.state.appendCadenceSpmSample(inst)

            // Also honor device-reported average cadence if present
            logSet("averageCadence", engine.state.averageCadence.value, avg, engine.state::setAverageCadence)
            feedLapCadenceAvg(engine, inst)
        }
        return off
    }

    private fun readCadence(engine: BtEngine, b: ByteArray, off0: Int, halfUnits: Boolean): Int {
        var off = off0
        if (has(b, off, 2)) {
            var inst = u16(b, off).toDouble(); off += 2
            if (halfUnits) inst /= 2.0

            if (canLogMetrics()) {
                Timber.tag(TAG).d("FTMS cadence (spm): instant=%.1f", inst)
            }

            // Time-series (updates instantCadence + running mean + series)
            engine.state.appendCadenceSpmSample(inst)

            feedLapCadenceAvg(engine, inst)
        }
        return off
    }

    private fun readAvgCadence(engine: BtEngine, b: ByteArray, off0: Int, halfUnits: Boolean): Int {
        var off = off0
        if (has(b, off, 2)) {
            var avg = u16(b, off).toDouble(); off += 2
            if (halfUnits) avg /= 2.0

            if (canLogMetrics()) {
                Timber.tag(TAG).d("FTMS average cadence (spm): %.1f", avg)
            }

            logSet("averageCadence", engine.state.averageCadence.value, avg, engine.state::setAverageCadence)
            feedLapCadenceAvg(engine, avg)
        }
        return off
    }

    private fun readFloors(engine: BtEngine, b: ByteArray, off0: Int, mode: Int): Int {
        var off = off0
        if (has(b, off, 2)) {
            val floors = u16(b, off); off += 2
            if (canLogMetrics()) {
                Timber.tag(TAG).d("FTMS totalFloors: %d", floors)
            }
            logSet("totalFloors", engine.state.totalFloors.value, floors, engine.state::setTotalFloors)
        }
        if (mode == 2 && has(b, off, 2)) {
            val steps = u16(b, off); off += 2
            if (canLogMetrics()) {
                Timber.tag(TAG).d("FTMS totalSteps: %d", steps)
            }
            logSet("totalSteps", engine.state.totalSteps.value, steps, engine.state::setTotalSteps)
            updateLapSteps(engine, steps)
        }
        return off
    }

    private fun readStrides(engine: BtEngine, b: ByteArray, off0: Int, tenths: Boolean): Int {
        var off = off0
        if (has(b, off, 2)) {
            var strides = u16(b, off).toDouble(); off += 2
            if (tenths) strides /= 10.0
            val steps = (strides * STRIDES_TO_STEPS).toInt()

            if (canLogMetrics()) {
                Timber.tag(TAG).d(
                    "FTMS strides: %.1f, derived steps: %d (×%.1f)",
                    strides, steps, STRIDES_TO_STEPS
                )
            }

            logSet("totalStrides", engine.state.totalStrides.value, strides, engine.state::setTotalStrides)
            logSet("totalSteps", engine.state.totalSteps.value, steps, engine.state::setTotalSteps)
            updateLapSteps(engine, steps)
        }
        return off
    }

    // ─────────────────────────────────────────────────────────────
    // Lap helpers
    // ─────────────────────────────────────────────────────────────

    private fun updateLapDistance(engine: BtEngine, totalMeters: Int) {
        val lapMeters = max(0, totalMeters - engine.state.lapStartDistanceMeters)
        logSet("lapDistanceMeters", engine.state.lapDistanceMeters.value, lapMeters, engine.state::setLapDistanceMeters)
        val disp = toDisplayDistance(engine, lapMeters)
        logSet("lapDistanceDisplay", engine.state.lapDistanceDisplay.value, disp, engine.state::setLapDistanceDisplay)
        recomputeLapDerived(engine)
    }

    private fun updateLapElapsed(engine: BtEngine, totalElapsedSec: Int) {
        val lap = max(0, totalElapsedSec - engine.state.lapStartElapsedSec)
        logSet("lapElapsedTimeSec", engine.state.lapElapsedTimeSec.value, lap, engine.state::setLapElapsedTimeSec)
        recomputeLapDerived(engine)
    }

    private fun updateLapSteps(engine: BtEngine, totalStepsNow: Int) {
        val lap = max(0, totalStepsNow - engine.state.lapStartSteps)
        logSet("lapSteps", engine.state.lapSteps.value, lap, engine.state::setLapSteps)
    }

    private fun updateLapStrokes(engine: BtEngine, totalStrokesNow: Int) {
        val lap = max(0, totalStrokesNow - engine.state.lapStartStrokeCount)
        logSet("lapStrokeCount", engine.state.lapStrokeCount.value, lap, engine.state::setLapStrokeCount)
        val lapSec = engine.state.lapElapsedTimeSec.value
        if (lapSec != null && lapSec > 0) {
            val spm = (engine.state.lapStrokeCount.value ?: 0).toDouble() / (lapSec / 60.0)
            logSet("lapCadenceSpm", engine.state.lapCadenceSpm.value, spm, engine.state::setLapCadenceSpm)
        }
    }

    private fun feedLapCadenceAvg(engine: BtEngine, inst: Double?) {
        if (inst == null) return
        engine.state.lapAvgCadenceTotal += inst
        engine.state.lapAvgCadenceCount += 1
        val avg = engine.state.lapAvgCadenceTotal / engine.state.lapAvgCadenceCount.toDouble()
        logSet("lapCadenceSpm", engine.state.lapCadenceSpm.value, avg, engine.state::setLapCadenceSpm)
    }

    private fun recomputeLapDerived(engine: BtEngine) {
        val d = engine.state.lapDistanceMeters.value ?: return
        val t = engine.state.lapElapsedTimeSec.value ?: return
        if (t <= 0) {
            if (canLogMetrics()) {
                Timber.tag(TAG).d("Lap derived reset: t<=0")
            }
            logSet("lapAvgSpeedMps", engine.state.lapAvgSpeedMps.value, null as Double?, engine.state::setLapAvgSpeedMps)
            logSet("lapAvgSpeedDisplay", engine.state.lapAvgSpeedDisplay.value, null as Double?, engine.state::setLapAvgSpeedDisplay)
            logSet("lapRunPaceMinPerUnit", engine.state.lapRunPaceMinPerUnit.value, null as Double?, engine.state::setLapRunPaceMinPerUnit)
            logSet("lapRowPaceMinPer500m", engine.state.lapRowPaceMinPer500m.value, null as Double?, engine.state::setLapRowPaceMinPer500m)
            return
        }
        val mps = d.toDouble() / t.toDouble()
        logSet("lapAvgSpeedMps", engine.state.lapAvgSpeedMps.value, mps, engine.state::setLapAvgSpeedMps)
        val disp = when (engine.state.units.value) {
            UNIT_METRIC   -> mps * MPS_TO_KPH
            UNIT_IMPERIAL -> mps * MPS_TO_MPH
            else          -> null
        }
        logSet("lapAvgSpeedDisplay", engine.state.lapAvgSpeedDisplay.value, disp, engine.state::setLapAvgSpeedDisplay)

        val perMeters = when (engine.state.units.value) {
            UNIT_METRIC   -> M_PER_KM
            UNIT_IMPERIAL -> M_PER_MILE
            else          -> null
        }
        val runPace = perMeters?.let { meters -> if (mps > 0.0) (meters / mps) / 60.0 else null }
        logSet("lapRunPaceMinPerUnit", engine.state.lapRunPaceMinPerUnit.value, runPace, engine.state::setLapRunPaceMinPerUnit)

        val rowPace = if (mps > 0.0) (ROW_PACE_M / mps) / 60.0 else null
        logSet("lapRowPaceMinPer500m", engine.state.lapRowPaceMinPer500m.value, rowPace, engine.state::setLapRowPaceMinPer500m)
    }

    // ─────────────────────────────────────────────────────────────
    // Display helpers (unit-aware)
    // ─────────────────────────────────────────────────────────────

    private fun toDisplaySpeed(engine: BtEngine, kph: Double?): Double? {
        if (kph == null) return null
        return when (engine.state.units.value) {
            UNIT_METRIC   -> kph
            UNIT_IMPERIAL -> kph * 0.621371
            else          -> null
        }
    }

    private fun toDisplayDistance(engine: BtEngine, meters: Int?): Double? {
        if (meters == null) return null
        return when (engine.state.units.value) {
            UNIT_METRIC   -> meters * M_TO_KM
            UNIT_IMPERIAL -> meters * M_TO_MI
            else          -> null
        }
    }

    private fun kphToMph(kph: Double) = kph * 0.621371

    // ─────────────────────────────────────────────────────────────
    // Byte helpers
    // ─────────────────────────────────────────────────────────────

    private fun has(b: ByteArray, off: Int, need: Int) = b.size >= off + need
    private fun u8(b: ByteArray, off: Int): Int = b[off].toInt() and 0xFF
    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    private fun s16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or (b[off + 1].toInt() shl 8)
    private fun skip(off: Int, n: Int) = off + n

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

    /**
     * Log only on change and then perform the setter (to keep logs useful & low-noise).
     * Metric value logs are gated on [canLogMetrics], so they appear at DebugLevel >= 4.
     */
    private inline fun <reified T> logSet(
        name: String,
        old: T?,
        new: T?,
        crossinline setter: (T?) -> Unit
    ) {
        if (old == new) {
            setter(new)
            return
        }
        if (canLogMetrics()) {
            Timber.tag(TAG).d("state.%s: %s → %s", name, old, new)
        }
        setter(new)
    }
}

/** FTMS Rower Data carries pace in seconds per 500 m; engine pace is seconds per km. */
internal fun rowerSecondsPer500mToSecondsPerKm(secondsPer500m: Double): Double =
    secondsPer500m * 2.0

/** Prefer FTMS energy-per-hour; fall back to the per-minute field when necessary. */
internal fun ftmsEnergyRateKcalPerHour(perHourRaw: Int, perMinuteRaw: Int): Int? = when {
    perHourRaw != 0xFFFF -> perHourRaw
    perMinuteRaw != 0xFF -> perMinuteRaw * 60
    else -> null
}
