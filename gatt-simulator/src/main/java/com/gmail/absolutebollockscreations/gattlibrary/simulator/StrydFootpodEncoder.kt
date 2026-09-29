package com.gmail.absolutebollockscreations.gattlibrary.simulator

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Running Speed and Cadence (0x1814) and Cycling Power (0x1818) encoders for the Stryd footpod
 * persona.
 *
 * **Provenance.** The frame layouts, flag values, field scaling and notification cadence here come
 * from `docs/stryd-footpod-simulator-implementation.md`, an attributed wire-protocol specification
 * written from four physical Stryd units' notification traffic (8,576 frames, December 2023 to
 * December 2025). No QZ implementation code was read, ported or transliterated: the captures are
 * QZ *debug output*, the specification was written from those octets, and this file was written
 * from the specification. See the plan's licensing section and the provenance record.
 *
 * **What is capture-derived and what is not.** The octets are capture-derived. The ride is not:
 * metrics come from the shared [FtmsMockEngine] like every other persona (the same split the
 * Schwinn x70 persona settled on). What the captures pin down is how a footpod *renders* a ride,
 * and the one property a real footpod never gets wrong:
 *
 * > speed (m/s) = cadence (strides/min) x stride length (m) / 60
 *
 * That identity holds across all 1,460 moving frames in the corpus, which is why [RunningGait]
 * derives cadence from speed and then computes stride length from both, rather than generating
 * three numbers that can disagree. The same reasoning binds the two services together:
 * [cyclingPowerMeasurement]'s crank counter is fed by the *same* [RunningStrideAccumulator] as
 * [rscMeasurement]'s cadence, because a client that reads both will notice drift.
 *
 * **Not reproduced, deliberately.** The captures never recorded a characteristic *read*, so the
 * Device Information strings, the Cycling Power Feature and RSC Feature values, and the Sensor
 * Location value are absent from the evidence. The feature bitfields below are *derived from what
 * this persona emits* rather than guessed at (a persona that sets a presence bit in every frame
 * must declare the matching feature bit); the identity strings are openly synthetic. Stryd's two
 * proprietary services are not implemented at all: one has an unknown payload and the other never
 * notified once in five captures, and the plan forbids inventing either.
 */
internal object StrydFootpodEncoder {

    // ─────────────────────────────────────────────────────────────
    // RSC Measurement (0x2A53) - fixed 10 octets
    // ─────────────────────────────────────────────────────────────

    /** Instantaneous stride length present. Set in every captured frame. */
    const val RSC_FLAG_STRIDE_LENGTH = 0x01

    /** Total distance present. Set in every captured frame. */
    const val RSC_FLAG_TOTAL_DISTANCE = 0x02

    /** Walking (0) or running (1). The only flag bit that toggles at run time. */
    const val RSC_FLAG_RUNNING = 0x04

    /** `0x03`: the persona is stationary or walking. */
    const val RSC_FLAGS_WALKING = RSC_FLAG_STRIDE_LENGTH or RSC_FLAG_TOTAL_DISTANCE

    /** `0x07`: the persona is running. */
    const val RSC_FLAGS_RUNNING = RSC_FLAGS_WALKING or RSC_FLAG_RUNNING

    /**
     * Speed at or above which the captures show flag bit 2 set.
     *
     * The corpus does not contain a clean threshold sweep - session B makes exactly one
     * walk-to-run transition and the others make four each - so this is the persona's own
     * declared boundary, not a measured one. It sits inside the observed gap between the fastest
     * `0x03` frame and the slowest `0x07` frame.
     */
    const val RUN_THRESHOLD_MPS = 2.0

    /** Fixed 10-octet RSC Measurement. Stride length and total distance are always present. */
    fun rscMeasurement(
        speedMps: Double,
        cadenceStridesPerMinute: Int,
        strideLengthCm: Int,
        totalDistanceMetres: Double,
        running: Boolean,
    ): ByteArray {
        val flags = if (running) RSC_FLAGS_RUNNING else RSC_FLAGS_WALKING
        return byteArrayOf(flags.toByte()) +
            u16le((speedMps.coerceAtLeast(0.0) * 256.0).roundToInt()) +
            byteArrayOf(cadenceStridesPerMinute.coerceIn(0, 0xFF).toByte()) +
            u16le(strideLengthCm.coerceIn(0, 0xFFFF)) +
            u32le((totalDistanceMetres.coerceAtLeast(0.0) * 10.0).toLong())
    }

    /**
     * RSC Feature (0x2A54).
     *
     * Bit 0 instantaneous stride length supported, bit 1 total distance supported - the two
     * presence bits [RSC_FLAGS_WALKING] sets in every frame this persona emits. Derived from the
     * emitter rather than copied from a capture, because no capture ever read this characteristic.
     */
    fun rscFeature(): ByteArray = u16le(RSC_FLAG_STRIDE_LENGTH or RSC_FLAG_TOTAL_DISTANCE)

    // ─────────────────────────────────────────────────────────────
    // Cycling Power Measurement (0x2A63) - fixed 8 octets
    // ─────────────────────────────────────────────────────────────

    /**
     * `0x0020`, crank revolution data present, in all 1,859 captured CPS frames.
     *
     * A footpod has no crank. Stryd carries **step cadence** in the crank counter, which is why a
     * bike-shaped client - Zwift, most head units - gets a plausible cadence from a running sensor
     * that publishes no RSC at all. Reproducing that is the whole point of the persona, so the
     * counter is fed from the same stride accumulator that drives 0x2A53.
     */
    const val CPS_FLAGS = 0x0020

    /** Bit 3, crank revolution data supported: the feature bit [CPS_FLAGS] obliges. */
    const val CPS_FEATURE_CRANK_REVOLUTION_DATA = 1 shl 3

    /**
     * Sensor Location (0x2A5D) = 2, "in shoe".
     *
     * Never read in any capture. The value is this persona's own honest declaration of where it
     * claims to be worn, and it is corroborated inside this repository: `CyclingSensorEncoders`
     * already pins `SENSOR_LOCATION_REAR_HUB = 0x0D`, which fixes the shared CSC/CPS enumeration
     * and puts the two foot-mounted codes at 1 ("top of shoe") and 2 ("in shoe").
     */
    const val SENSOR_LOCATION_IN_SHOE = 0x02

    /** Fixed 8-octet Cycling Power Measurement with the crank pair Stryd always sends. */
    fun cyclingPowerMeasurement(
        instantaneousPowerWatts: Int,
        cumulativeStrides: Int,
        lastStrideEventTime1024: Int,
    ): ByteArray = u16le(CPS_FLAGS) +
        s16le(instantaneousPowerWatts.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())) +
        u16le(cumulativeStrides and 0xFFFF) +
        u16le(lastStrideEventTime1024 and 0xFFFF)

    /** Cycling Power Feature (0x2A65), derived from [CPS_FLAGS] the same way [rscFeature] is. */
    fun cyclingPowerFeature(): ByteArray = u32le(CPS_FEATURE_CRANK_REVOLUTION_DATA.toLong())

    /** Sensor Location (0x2A5D). */
    fun sensorLocation(): ByteArray = byteArrayOf(SENSOR_LOCATION_IN_SHOE.toByte())

    // ─────────────────────────────────────────────────────────────
    // Timing
    // ─────────────────────────────────────────────────────────────

    /** Both measurement characteristics notify at 1 Hz; the corpus median gap is 991 ms. */
    const val MEASUREMENT_PERIOD_MS = 1_000L

    /**
     * Battery notification period.
     *
     * Captured inter-notification gaps are always integer multiples of five seconds, which is the
     * tick this reproduces. The captures' *irregular delivery* on top of that tick - and their
     * upward drift, 45-46-47% and 67-68-69% - is not reproduced: a persona that emits a level it
     * did not derive would be inventing data, so this one discharges monotonically and says so.
     */
    const val BATTERY_PERIOD_MS = 5_000L

    // ─────────────────────────────────────────────────────────────
    // Octet helpers
    // ─────────────────────────────────────────────────────────────

    private fun u16le(value: Int): ByteArray {
        val v = value.coerceIn(0, 0xFFFF)
        return byteArrayOf((v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte())
    }

    private fun s16le(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
    )

    private fun u32le(value: Long): ByteArray {
        val v = value.coerceIn(0L, 0xFFFF_FFFFL)
        return byteArrayOf(
            (v and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(),
            ((v ushr 16) and 0xFF).toByte(),
            ((v ushr 24) and 0xFF).toByte(),
        )
    }
}

/**
 * Speed-to-gait model for the generated ride.
 *
 * Cadence is the physiological variable and stride length is the consequence, so cadence is
 * modelled from speed and stride length is then computed from both. That ordering is what keeps
 * `speed = cadence x stride / 60` exactly true in every emitted frame, as it is in every captured
 * one.
 *
 * The walking segment is fitted to the corpus (mean cadence per quarter-m/s bucket: 50 strides/min
 * at 0.75 m/s, 52 at 1.00, 58 at 1.25, 65 at 1.50, 68 at 1.75, 86 at 2.05). Above the corpus's
 * 2.10 m/s ceiling the same line would reach 128 strides/min at 13 km/h, which no runner does, so
 * the running segment saturates instead - the standard observation that cadence varies far less
 * than stride length once running. That upper segment is a declared model, not a measurement, and
 * the treadmill ride this persona uses (8-13 km/h) sits entirely inside it.
 */
internal object RunningGait {
    private const val WALK_INTERCEPT = 30.0
    private const val WALK_SLOPE = 27.0
    private const val RUN_CADENCE_AT_THRESHOLD = 84.0
    private const val RUN_CADENCE_CEILING = 95.0
    private const val RUN_SATURATION_MPS = 4.5

    /** Strides per minute - one per foot-strike pair, which is what 0x2A53 carries. */
    fun cadenceStridesPerMinute(speedMps: Double): Int {
        if (speedMps <= 0.05) return 0
        val cadence = if (speedMps < StrydFootpodEncoder.RUN_THRESHOLD_MPS) {
            WALK_INTERCEPT + WALK_SLOPE * speedMps
        } else {
            val span = (RUN_SATURATION_MPS - StrydFootpodEncoder.RUN_THRESHOLD_MPS)
            val t = ((speedMps - StrydFootpodEncoder.RUN_THRESHOLD_MPS) / span).coerceIn(0.0, 1.0)
            RUN_CADENCE_AT_THRESHOLD + (RUN_CADENCE_CEILING - RUN_CADENCE_AT_THRESHOLD) * t
        }
        return cadence.roundToInt().coerceIn(1, 0xFF)
    }

    /**
     * Full two-step stride length in centimetres, in whole centimetres as the wire carries it.
     *
     * Computed from speed and cadence so the wire identity holds after rounding, not before it.
     */
    fun strideLengthCm(speedMps: Double, cadenceStridesPerMinute: Int): Int {
        if (speedMps <= 0.05 || cadenceStridesPerMinute <= 0) return 0
        val metresPerStride = speedMps * 60.0 / cadenceStridesPerMinute
        return (metresPerStride * 100.0).roundToInt().coerceIn(0, 0xFFFF)
    }
}

/**
 * Integrates generated speed into stride events, the running counterpart of
 * `CyclingEventAccumulator`.
 *
 * The fractional phase is retained across ticks for the same reason it is there: notification
 * timing must not change the long-term stride count. Both measurement characteristics read the
 * one snapshot, so the CPS crank counter and the RSC cadence can never disagree.
 *
 * All public methods are synchronized: the notifier runs on a coroutine while the GATT server's
 * callbacks arrive on Android's binder thread.
 */
internal class RunningStrideAccumulator {

    /** One tick's coherent gait state, rendered by both encoders. */
    data class Snapshot(
        val speedMps: Double = 0.0,
        val cadenceStridesPerMinute: Int = 0,
        val strideLengthCm: Int = 0,
        val totalDistanceMetres: Double = 0.0,
        val cumulativeStrides: Int = 0,
        val lastStrideEventTime1024: Int = 0,
        val running: Boolean = false,
    )

    private var lastUpdateMs: Long? = null
    private var stridePhase = 0.0
    private var strides = 0
    private var lastStrideEventTime = 0
    private var distanceMetres = 0.0
    private var snapshot = Snapshot()

    @Synchronized
    fun update(speedMps: Double?, nowMs: Long): Snapshot {
        val speed = speedMps?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val cadence = RunningGait.cadenceStridesPerMinute(speed)
        val stride = RunningGait.strideLengthCm(speed, cadence)

        val priorMs = lastUpdateMs
        lastUpdateMs = nowMs
        if (priorMs != null) {
            val elapsedMs = nowMs - priorMs
            if (elapsedMs in 1..MAX_INTEGRATION_GAP_MS) {
                val elapsedSeconds = elapsedMs / 1000.0
                distanceMetres += speed * elapsedSeconds
                if (cadence > 0) {
                    val rate = cadence / 60.0
                    val total = stridePhase + rate * elapsedSeconds
                    val completed = floor(total).toInt()
                    stridePhase = total - completed
                    if (completed > 0) {
                        strides = (strides + completed) and UINT16_MASK
                        val secondsSinceLastEvent = stridePhase / rate
                        lastStrideEventTime = eventTimeTicks(nowMs - secondsSinceLastEvent * 1000.0)
                    }
                }
            }
        }

        snapshot = Snapshot(
            speedMps = speed,
            cadenceStridesPerMinute = cadence,
            strideLengthCm = stride,
            totalDistanceMetres = distanceMetres,
            cumulativeStrides = strides,
            lastStrideEventTime1024 = lastStrideEventTime,
            running = speed >= StrydFootpodEncoder.RUN_THRESHOLD_MPS,
        )
        return snapshot
    }

    @Synchronized
    fun snapshot(): Snapshot = snapshot

    @Synchronized
    fun reset() {
        lastUpdateMs = null
        stridePhase = 0.0
        strides = 0
        lastStrideEventTime = 0
        distanceMetres = 0.0
        snapshot = Snapshot()
    }

    private fun eventTimeTicks(eventAtMs: Double): Int {
        val ticks = floor(eventAtMs * EVENT_TIME_HZ / 1000.0).toLong()
        return (((ticks % EVENT_TIME_MODULUS) + EVENT_TIME_MODULUS) % EVENT_TIME_MODULUS).toInt()
    }

    companion object {
        /** Same bound as the cycling accumulator: a longer gap is a stall, not a stride burst. */
        const val MAX_INTEGRATION_GAP_MS = 5_000L
        private const val EVENT_TIME_HZ = 1024.0
        private const val EVENT_TIME_MODULUS = 65_536L
        private const val UINT16_MASK = 0xFFFF
    }
}

/**
 * Generated battery level for the persona.
 *
 * Deliberately simpler than the captures: real units reported noisily and non-monotonically, and
 * reproducing that shape would mean inventing a discharge model the evidence does not contain.
 * This one starts at a declared level and falls one percent per ten minutes.
 */
internal class SyntheticBatteryLevel(private val startPercent: Int = START_PERCENT) {
    fun levelAt(elapsedSeconds: Int): Int {
        val drop = elapsedSeconds / SECONDS_PER_PERCENT
        return (startPercent - drop).coerceIn(MIN_PERCENT, 100)
    }

    companion object {
        const val START_PERCENT = 87
        const val MIN_PERCENT = 1
        const val SECONDS_PER_PERCENT = 600
    }
}
