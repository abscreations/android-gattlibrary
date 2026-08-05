/**
 * btEngine.GattFtmsParsers.kt
 *
 * ROLE
 *  - Stateless FTMS (Fitness Machine Service) measurement parsers used by [FtmsProtocolHandler].
 *  - Decodes raw FTMS data characteristics for all supported machine types and emits
 *    source-tagged, normalized [MetricSample] values through [MetricEmitter].
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
 *    • Emits each decoded value with packet-scoped source identity and arrival time.
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
 *      • Inclination samples (percent grade) and ramp angle (degrees) for treadmills/ellipticals.
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
 * IMPLEMENTATION NOTES
 *  - The object itself is stateless; canonical state projection is owned by [MetricFusion].
 *  - Uses small byte helpers (has / u8 / u16 / s16 / skip) for every optional field.
 */

package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import android.Manifest
import androidx.annotation.RequiresPermission
import timber.log.Timber
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
    private const val STRIDES_TO_STEPS = 2.0 // device-dependent; template default

    // ─────────────────────────────────────────────────────────────
    // Public entry points (called by FtmsProtocolHandler)
    // ─────────────────────────────────────────────────────────────

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun doBike(emitter: MetricEmitter, value: ByteArray) {
        if (canLogFrames()) {
            Timber.tag(TAG).d("FTMS Bike (2AD2) len=%d %s", value.size, value.toHex())
        }
        var off = 0
        if (!has(value, off, 2)) return
        val flags = u16(value, off); off += 2
        if (canLogMetrics()) {
            Timber.tag(TAG).d("Bike flags=0x%04X", flags)
        }

        if ((flags and Flags.BIKE_MORE) == 0) off = readSpeed(emitter, value, off)
        if ((flags and Flags.BIKE_AVGSPEED) != 0) off = readAvgSpeed(emitter, value, off)
        if ((flags and Flags.BIKE_CADENCE) != 0) off = readCadence(emitter, value, off, halfUnits = true)
        if ((flags and Flags.BIKE_AVGCAD) != 0) off = readAvgCadence(emitter, value, off, halfUnits = true)
        if ((flags and Flags.BIKE_TOTALDIST) != 0) off = readDistance(emitter, value, off)
        if ((flags and Flags.BIKE_RESISTANCE) != 0) off = readResistance(emitter, value, off, div10 = false)
        if ((flags and Flags.BIKE_INSTPOWER) != 0) off = readPower(emitter, value, off)
        if ((flags and Flags.BIKE_AVGPOWER) != 0) off = readAvgPower(emitter, value, off)
        if ((flags and Flags.BIKE_EXPENERGY) != 0) off = readEnergy(emitter, value, off)

        if ((flags and Flags.BIKE_HEARTRATE) != 0) off = readFtmsHeartRate(emitter, value, off)
        if ((flags and Flags.BIKE_METABOLIC) != 0) off = readMet(emitter, value, off)
        if ((flags and Flags.BIKE_ELAPSED) != 0) off = readElapsed(emitter, value, off)
        if ((flags and Flags.BIKE_REMAINING) != 0) skip(off, 2)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun doRower(emitter: MetricEmitter, value: ByteArray) {
        if (canLogFrames()) {
            Timber.tag(TAG).d("FTMS Rower (2AD1) len=%d %s", value.size, value.toHex())
        }
        var off = 0
        if (!has(value, off, 2)) return
        val flags = u16(value, off); off += 2
        if (canLogMetrics()) {
            Timber.tag(TAG).d("Rower flags=0x%04X", flags)
        }

        if ((flags and Flags.ROW_MORE) == 0) off = readStrokes(emitter, value, off)
        if ((flags and Flags.ROW_AVGSTROKE) != 0) off = readAvgStrokes(emitter, value, off)
        if ((flags and Flags.ROW_TOTALDIST) != 0) off = readDistance(emitter, value, off)
        if ((flags and Flags.ROW_INSTPACE) != 0) {
            off = readPace(emitter, value, off, times10 = false, per500m = true)
        }
        if ((flags and Flags.ROW_AVGPACE) != 0) {
            off = readAvgPace(emitter, value, off, times10 = false, per500m = true)
        }
        if ((flags and Flags.ROW_INSTPOWER) != 0) off = readPower(emitter, value, off)
        if ((flags and Flags.ROW_AVGPOWER) != 0) off = readAvgPower(emitter, value, off)
        if ((flags and Flags.ROW_RESISTANCE) != 0) off = readResistance(emitter, value, off, div10 = false)
        if ((flags and Flags.ROW_EXPENERGY) != 0) off = readEnergy(emitter, value, off)

        if ((flags and Flags.ROW_HEARTRATE) != 0) off = readFtmsHeartRate(emitter, value, off)
        if ((flags and Flags.ROW_METABOLIC) != 0) off = readMet(emitter, value, off)
        if ((flags and Flags.ROW_ELAPSED) != 0) off = readElapsed(emitter, value, off)
        if ((flags and Flags.ROW_REMAINING) != 0) skip(off, 2)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun doTreadmill(emitter: MetricEmitter, value: ByteArray) {
        if (canLogFrames()) {
            Timber.tag(TAG).d("FTMS Treadmill (2ACD) len=%d %s", value.size, value.toHex())
        }
        var off = 0
        if (!has(value, off, 2)) return
        val flags = u16(value, off); off += 2
        if (canLogMetrics()) {
            Timber.tag(TAG).d("Treadmill flags=0x%04X", flags)
        }

        if ((flags and Flags.TREAD_MOREDATA) == 0) off = readSpeed(emitter, value, off)
        if ((flags and Flags.TREAD_AVGSPEED) != 0) off = readAvgSpeed(emitter, value, off)
        if ((flags and Flags.TREAD_TOTALDISTANCE) != 0) off = readDistance(emitter, value, off)
        if ((flags and Flags.TREAD_INCLRAMPANGLE) != 0) off = readInclineV1(emitter, value, off)
        if ((flags and Flags.TREAD_ELEVATIONGAIN) != 0) off = readElevation(emitter, value, off, mode = 2)
        if ((flags and Flags.TREAD_INSTANTPACE) != 0) off = readPace(emitter, value, off, times10 = true)
        if ((flags and Flags.TREAD_AVGPACE) != 0) off = readAvgPace(emitter, value, off, times10 = true)
        if ((flags and Flags.TREAD_EXPENERGY) != 0) off = readEnergy(emitter, value, off)

        if ((flags and Flags.TREAD_HEARTRATE) != 0) off = readFtmsHeartRate(emitter, value, off)
        if ((flags and Flags.TREAD_METABOLIC) != 0) off = readMet(emitter, value, off)
        if ((flags and Flags.TREAD_ELAPSEDTIME) != 0) off = readElapsed(emitter, value, off)
        if ((flags and Flags.TREAD_REMAININGTIME) != 0) off = skip(off, 2)
        if ((flags and Flags.TREAD_BELTFORCEPOWER) != 0) readBeltForce(emitter, value, off)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun doElliptical(emitter: MetricEmitter, value: ByteArray) {
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

        if ((flags and Flags.ELL_MORE) == 0) off = readSpeed(emitter, value, off)
        if ((flags and Flags.ELL_AVGSPEED) != 0) off = readAvgSpeed(emitter, value, off)
        if ((flags and Flags.ELL_TOTALDIST) != 0) off = readDistance(emitter, value, off)
        if ((flags and Flags.ELL_STEPCOUNT) != 0) off = readEllipticalCadence(emitter, value, off)
        if ((flags and Flags.ELL_STRIDECOUNT) != 0) off = readStrides(emitter, value, off, tenths = true)
        if ((flags and Flags.ELL_ELEVGAIN) != 0) off = readElevation(emitter, value, off, mode = 3)
        if ((flags and Flags.ELL_RESISTANCE) != 0) off = readResistance(emitter, value, off, div10 = true)
        if ((flags and Flags.ELL_INCLINATION) != 0) off = readInclineV2(emitter, value, off)
        if ((flags and Flags.ELL_INSTPOWER) != 0) off = readPower(emitter, value, off)
        if ((flags and Flags.ELL_AVGPOWER) != 0) off = readAvgPower(emitter, value, off)
        if ((flags and Flags.ELL_EXPENERGY) != 0) off = readEnergy(emitter, value, off)

        if ((flags and Flags.ELL_HEARTRATE) != 0) off = readFtmsHeartRate(emitter, value, off)
        if ((flags and Flags.ELL_METABOLIC) != 0) off = readMet(emitter, value, off)
        if ((flags and Flags.ELL_ELAPSED) != 0) off = readElapsed(emitter, value, off)
        if ((flags and Flags.ELL_REMAINING) != 0) skip(off, 2)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun doStepper(emitter: MetricEmitter, value: ByteArray) {
        if (canLogFrames()) {
            Timber.tag(TAG).d("FTMS Stepper (2ACF) len=%d %s", value.size, value.toHex())
        }
        var off = 0
        if (!has(value, off, 2)) return
        val flags = u16(value, off); off += 2
        if (canLogMetrics()) {
            Timber.tag(TAG).d("Stepper flags=0x%04X", flags)
        }

        if ((flags and Flags.STEP_MORE) == 0) off = readFloors(emitter, value, off, mode = 2)
        if ((flags and Flags.STEP_STEPRATE) != 0) off = readCadence(emitter, value, off, halfUnits = false)
        if ((flags and Flags.STEP_AVGSTEPR) != 0) off = readAvgCadence(emitter, value, off, halfUnits = false)
        if ((flags and Flags.STEP_ELEVGAIN) != 0) off = readElevation(emitter, value, off, mode = 1)
        if ((flags and Flags.STEP_EXPENERGY) != 0) off = readEnergy(emitter, value, off)

        if ((flags and Flags.STEP_HEARTRATE) != 0) off = readFtmsHeartRate(emitter, value, off)
        if ((flags and Flags.STEP_METABOLIC) != 0) off = readMet(emitter, value, off)
        if ((flags and Flags.STEP_ELAPSED) != 0) off = readElapsed(emitter, value, off)
        if ((flags and Flags.STEP_REMAINING) != 0) skip(off, 2)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun doClimber(emitter: MetricEmitter, value: ByteArray) {
        if (canLogFrames()) {
            Timber.tag(TAG).d("FTMS Climber (2AD0) len=%d %s", value.size, value.toHex())
        }
        var off = 0
        if (!has(value, off, 2)) return
        val flags = u16(value, off); off += 2
        if (canLogMetrics()) {
            Timber.tag(TAG).d("Climber flags=0x%04X", flags)
        }

        if ((flags and Flags.CLIMB_MORE) == 0) off = readFloors(emitter, value, off, mode = 1)
        if ((flags and Flags.CLIMB_STEPRATE) != 0) off = readCadence(emitter, value, off, halfUnits = false)
        if ((flags and Flags.CLIMB_AVGSTEPR) != 0) off = readAvgCadence(emitter, value, off, halfUnits = false)
        if ((flags and Flags.CLIMB_ELEVGAIN) != 0) off = readElevation(emitter, value, off, mode = 1)
        if ((flags and Flags.CLIMB_STRIDE) != 0) off = readStrides(emitter, value, off, tenths = false)
        if ((flags and Flags.CLIMB_EXPENERGY) != 0) off = readEnergy(emitter, value, off)

        if ((flags and Flags.CLIMB_HEARTRATE) != 0) off = readFtmsHeartRate(emitter, value, off)
        if ((flags and Flags.CLIMB_METABOLIC) != 0) off = readMet(emitter, value, off)
        if ((flags and Flags.CLIMB_ELAPSED) != 0) off = readElapsed(emitter, value, off)
        if ((flags and Flags.CLIMB_REMAINING) != 0) skip(off, 2)
    }

    // ─────────────────────────────────────────────────────────────
    // Field readers
    // ─────────────────────────────────────────────────────────────

    private fun readFtmsHeartRate(emitter: MetricEmitter, b: ByteArray, off0: Int): Int {
        var off = off0
        if (has(b, off, 1)) {
            val hr = u8(b, off)
            emitter.emit(MetricKind.HEART_RATE, hr)
            if (canLogMetrics()) {
                Timber.tag(TAG).d("FTMS HR sample (bpm): %d", hr)
            }
            off += 1
        }
        return off
    }

    private fun readStrokes(emitter: MetricEmitter, b: ByteArray, off0: Int): Int {
        var off = off0
        if (has(b, off, 1)) {
            val sr = u8(b, off) / 2.0
            if (canLogMetrics()) {
                Timber.tag(TAG).d("Rower strokeRate (spm): %.1f", sr)
            }
            emitter.emit(MetricKind.STROKE_RATE, sr)
            off += 1
        }
        if (has(b, off, 2)) {
            val count = u16(b, off)
            if (canLogMetrics()) {
                Timber.tag(TAG).d("Rower strokeCount: %d", count)
            }
            emitter.emit(MetricKind.STROKE_COUNT, count)
            off += 2
        }
        return off
    }

    private fun readAvgStrokes(emitter: MetricEmitter, b: ByteArray, off0: Int): Int {
        var off = off0
        if (has(b, off, 1)) {
            val avg = u8(b, off) / 2.0
            if (canLogMetrics()) {
                Timber.tag(TAG).d("Rower average strokeRate (spm): %.1f", avg)
            }
            emitter.emit(MetricKind.AVERAGE_CADENCE, avg)
            emitter.emit(MetricKind.LAP_CADENCE_SAMPLE, avg, quality = MetricQuality.DERIVED)
            off += 1
        }
        return off
    }

    // ─── Distance reader with rower branch (fixed to call state.*) ───────────────
    private fun readDistance(
        emitter: MetricEmitter,
        b: ByteArray,
        off0: Int,
    ): Int {
        var off = off0
        if (!has(b, off, 3)) return off

        var total = u16(b, off); off += 2
        total += (u8(b, off) shl 16); off += 1

        if (canLogMetrics()) {
            Timber.tag(TAG).d("FTMS totalDistance (m): %d", total)
        }

        emitter.emit(MetricKind.DISTANCE, total, nativeCumulative = total.toDouble())

        return off
    }

    private fun readPace(
        emitter: MetricEmitter,
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

            emitter.emit(MetricKind.INSTANT_PACE, pSecPerKm / 60.0)

            if (canLogMetrics()) {
                Timber.tag(TAG).d("FTMS instant pace sample (sec/km): %.1f", pSecPerKm)
            }

            off += 2
        }
        return off
    }

    private fun readAvgPace(
        emitter: MetricEmitter,
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

            emitter.emit(MetricKind.AVERAGE_PACE, pSecPerKm / 60.0)

            if (canLogMetrics()) {
                Timber.tag(TAG).d("FTMS average pace sample (sec/km): %.1f", pSecPerKm)
            }

            off += 2
        }
        return off
    }

    private fun readPower(emitter: MetricEmitter, b: ByteArray, off0: Int): Int {
        var off = off0
        if (has(b, off, 2)) {
            val v = s16(b, off)
            off += 2

            if (canLogMetrics()) {
                Timber.tag(TAG).d("FTMS instant power (W): %d", v)
            }
            emitter.emit(MetricKind.POWER, v)
        }
        return off
    }

    private fun readAvgPower(emitter: MetricEmitter, b: ByteArray, off0: Int): Int {
        var off = off0
        if (has(b, off, 2)) {
            val ap = s16(b, off)
            if (canLogMetrics()) {
                Timber.tag(TAG).d("FTMS average power (W): %d", ap)
            }
            emitter.emit(MetricKind.AVERAGE_POWER, ap)
            off += 2
        }
        return off
    }

    private fun readResistance(emitter: MetricEmitter, b: ByteArray, off0: Int, div10: Boolean): Int {
        var off = off0
        if (has(b, off, 2)) {
            var res = s16(b, off).toDouble()
            off += 2
            if (div10) res /= 10.0

            if (canLogMetrics()) {
                Timber.tag(TAG).d("FTMS resistance (level): %.1f", res)
            }

            emitter.emit(MetricKind.RESISTANCE, res)
        }
        return off
    }

    private fun readEnergy(emitter: MetricEmitter, b: ByteArray, off0: Int): Int {
        var off = off0
        if (has(b, off, 5)) {
            // Per FTMS spec: total energy (kcal) is uint16, followed by
            // energy per hour (uint16) and energy per minute (uint8).
            val machineKcal = u16(b, off)
            val perHourRaw = u16(b, off + 2)
            val perMinuteRaw = u8(b, off + 4)
            off += 5

            emitter.emit(
                MetricKind.ENERGY,
                machineKcal,
                nativeCumulative = machineKcal.toDouble(),
            )
            val rate = ftmsEnergyRateKcalPerHour(perHourRaw, perMinuteRaw)
            rate?.let { emitter.emit(MetricKind.ENERGY_RATE, it) }

            if (canLogMetrics()) {
                Timber.tag(TAG).d(
                    "FTMS energy: total=%d kcal rate=%s kcal/h",
                    machineKcal,
                    rate?.toString() ?: "unavailable"
                )
            }
        }
        return off
    }

    private fun readMet(emitter: MetricEmitter, b: ByteArray, off0: Int): Int {
        var off = off0
        if (has(b, off, 1)) {
            val met = u8(b, off).toDouble() / 10.0
            if (canLogMetrics()) {
                Timber.tag(TAG).d("FTMS MET value: %.1f", met)
            }
            emitter.emit(MetricKind.METABOLIC_EQUIVALENT, met)
            off += 1
        }
        return off
    }

    /**
     * FTMS is now the single source of truth for elapsed time in this app.
     */
    private fun readElapsed(emitter: MetricEmitter, b: ByteArray, off0: Int): Int {
        var off = off0
        if (!has(b, off, 2)) {
            return off
        }

        val ftmsElapsedSec = ((b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8))
        off += 2

        emitter.emit(MetricKind.ELAPSED, ftmsElapsedSec)

        if (canLogMetrics()) {
            Timber.tag(TAG).d("FTMS elapsed time (s): %d", ftmsElapsedSec)
        }

        return off
    }

    private fun readSpeed(emitter: MetricEmitter, b: ByteArray, off0: Int): Int {
        var off = off0
        if (has(b, off, 2)) {
            val kph = u16(b, off) / 100.0
            off += 2

            if (canLogMetrics()) {
                Timber.tag(TAG).d("FTMS instant speed (km/h): %.2f", kph)
            }

            emitter.emit(MetricKind.SPEED, kph)
        }
        return off
    }

    private fun readAvgSpeed(emitter: MetricEmitter, b: ByteArray, off0: Int): Int {
        var off = off0
        if (has(b, off, 2)) {
            val kph = u16(b, off) / 100.0
            off += 2

            if (canLogMetrics()) {
                Timber.tag(TAG).d("FTMS average speed (km/h): %.2f", kph)
            }

            emitter.emit(MetricKind.AVERAGE_SPEED, kph)
        }
        return off
    }

    /**
     * Treadmill Data (0x2ACD) Inclination and Ramp Angle Setting.
     *
     * Both are sint16. Inclination is in units of 0.1 percent grade and Ramp Angle Setting is in
     * units of 0.1 degree, so both need the same tenth scaling. Inclination previously kept its
     * raw value, which reported a -5 %..+10 % treadmill as -50..+100.
     */
    private fun readInclineV1(emitter: MetricEmitter, b: ByteArray, off0: Int): Int {
        var off = off0
        if (has(b, off, 4)) {
            val inc = s16(b, off).toDouble() / 10.0; off += 2
            val ramp = s16(b, off).toDouble() / 10.0; off += 2

            if (canLogMetrics()) {
                Timber.tag(TAG).d("FTMS incline (%%): %.1f, rampAngle (deg): %.1f", inc, ramp)
            }

            emitter.emit(MetricKind.INCLINATION, inc)
            emitter.emit(MetricKind.RAMP_ANGLE, ramp)
        }
        return off
    }

    /** Cross Trainer Data (0x2ACE): inclination in 0.1 percent, ramp angle in 0.1 degree. */
    private fun readInclineV2(emitter: MetricEmitter, b: ByteArray, off0: Int): Int {
        var off = off0
        if (has(b, off, 4)) {
            val inc = s16(b, off).toDouble() / 10.0; off += 2
            val ramp = s16(b, off).toDouble() / 10.0; off += 2

            if (canLogMetrics()) {
                Timber.tag(TAG).d("FTMS incline (%%): %.1f, rampAngle (deg): %.1f", inc, ramp)
            }

            emitter.emit(MetricKind.INCLINATION, inc)
            emitter.emit(MetricKind.RAMP_ANGLE, ramp)
        }
        return off
    }

    private fun readElevation(emitter: MetricEmitter, b: ByteArray, off0: Int, mode: Int): Int {
        var off = off0
        when (mode) {
            1 -> { // +gain only
                if (has(b, off, 2)) {
                    val pos = u16(b, off).toDouble(); off += 2
                    if (canLogMetrics()) {
                        Timber.tag(TAG).d("FTMS elevation gain + (m): %.1f", pos)
                    }
                    emitter.emitElevationGains(pos, null)
                }
            }
            2 -> { // +gain/10 and -gain/10
                if (has(b, off, 4)) {
                    val pos = u16(b, off).toDouble() / 10.0; off += 2
                    val neg = u16(b, off).toDouble() / 10.0; off += 2
                    if (canLogMetrics()) {
                        Timber.tag(TAG).d("FTMS elevation gain +/− (m): +%.1f / −%.1f", pos, neg)
                    }
                    emitter.emitElevationGains(pos, neg)
                }
            }
            3 -> { // +gain and -gain
                if (has(b, off, 4)) {
                    val pos = u16(b, off).toDouble(); off += 2
                    val neg = u16(b, off).toDouble(); off += 2
                    if (canLogMetrics()) {
                        Timber.tag(TAG).d("FTMS elevation gain +/− (m): +%.1f / −%.1f", pos, neg)
                    }
                    emitter.emitElevationGains(pos, neg)
                }
            }
        }
        return off
    }

    private fun readBeltForce(emitter: MetricEmitter, b: ByteArray, off0: Int): Int {
        var off = off0
        if (has(b, off, 4)) {
            val force = s16(b, off); off += 2
            val inst = s16(b, off); off += 2

            if (canLogMetrics()) {
                Timber.tag(TAG).d("FTMS beltForce (N-ish): %d, derived power (W): %d", force, inst)
            }

            emitter.emit(MetricKind.FORCE_ON_BELT, force)
            emitter.emit(MetricKind.POWER, inst, quality = MetricQuality.DERIVED)
        }
        return off
    }

    private fun readEllipticalCadence(emitter: MetricEmitter, b: ByteArray, off0: Int): Int {
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

            emitter.emit(MetricKind.CADENCE, inst)
            emitter.emit(MetricKind.AVERAGE_CADENCE, avg)
            emitter.emit(MetricKind.LAP_CADENCE_SAMPLE, inst, quality = MetricQuality.DERIVED)
        }
        return off
    }

    private fun readCadence(emitter: MetricEmitter, b: ByteArray, off0: Int, halfUnits: Boolean): Int {
        var off = off0
        if (has(b, off, 2)) {
            var inst = u16(b, off).toDouble(); off += 2
            if (halfUnits) inst /= 2.0

            if (canLogMetrics()) {
                Timber.tag(TAG).d("FTMS cadence (spm): instant=%.1f", inst)
            }

            emitter.emit(MetricKind.CADENCE, inst)
            emitter.emit(MetricKind.LAP_CADENCE_SAMPLE, inst, quality = MetricQuality.DERIVED)
        }
        return off
    }

    private fun readAvgCadence(emitter: MetricEmitter, b: ByteArray, off0: Int, halfUnits: Boolean): Int {
        var off = off0
        if (has(b, off, 2)) {
            var avg = u16(b, off).toDouble(); off += 2
            if (halfUnits) avg /= 2.0

            if (canLogMetrics()) {
                Timber.tag(TAG).d("FTMS average cadence (spm): %.1f", avg)
            }

            emitter.emit(MetricKind.AVERAGE_CADENCE, avg)
            emitter.emit(MetricKind.LAP_CADENCE_SAMPLE, avg, quality = MetricQuality.DERIVED)
        }
        return off
    }

    private fun readFloors(emitter: MetricEmitter, b: ByteArray, off0: Int, mode: Int): Int {
        var off = off0
        if (has(b, off, 2)) {
            val floors = u16(b, off); off += 2
            if (canLogMetrics()) {
                Timber.tag(TAG).d("FTMS totalFloors: %d", floors)
            }
            emitter.emit(MetricKind.TOTAL_FLOORS, floors)
        }
        if (mode == 2 && has(b, off, 2)) {
            val steps = u16(b, off); off += 2
            if (canLogMetrics()) {
                Timber.tag(TAG).d("FTMS totalSteps: %d", steps)
            }
            emitter.emit(MetricKind.TOTAL_STEPS, steps)
        }
        return off
    }

    private fun readStrides(emitter: MetricEmitter, b: ByteArray, off0: Int, tenths: Boolean): Int {
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

            emitter.emit(MetricKind.TOTAL_STRIDES, strides)
            emitter.emit(MetricKind.TOTAL_STEPS, steps, quality = MetricQuality.DERIVED)
        }
        return off
    }

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
