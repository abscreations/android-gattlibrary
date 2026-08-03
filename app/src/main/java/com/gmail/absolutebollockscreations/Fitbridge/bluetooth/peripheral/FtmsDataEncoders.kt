package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtServiceProfiles.FtmsMachineProfile
import kotlin.math.atan
import kotlin.math.roundToInt

/**
 * Encoders for the six FTMS machine data characteristics.
 *
 * Pure functions: snapshot in, notification payloads out. No Android imports, so
 * these are JVM-unit-testable - which matters, because byte-level flag and
 * scaling errors are exactly the class of bug that survives code review. This
 * replaces the six `BtService*Data` files, none of which were reachable from a
 * unit test because each one read `BtEngineState` and called into the GATT
 * server directly.
 *
 * Flag bit numbers below are transcribed from FTMS v1.0 tables 4.5 - 4.10. They
 * are NOT interchangeable between characteristics; in particular Indoor Bike
 * bit 1 is Average Speed and bit 2 is Instantaneous Cadence, which is the
 * opposite of the intuitive ordering.
 *
 * Fields the upstream machine cannot supply are simply omitted and their flag bit
 * left clear.
 */
object FtmsDataEncoders {

    fun encode(
        profile: FtmsMachineProfile,
        snapshot: FtmsMetricSnapshot,
        speedKph: Double?,
        maxPayload: Int = FtmsPacker.MAX_PAYLOAD,
        payloadMode: FtmsPayloadMode = FtmsPayloadMode.STRICT_FRAGMENTATION,
        onPackOutcome: (FtmsPackOutcome) -> Unit = {},
        garminCompatibilityFraming: Boolean = false,
    ): List<ByteArray> = when (profile) {
        FtmsMachineProfile.INDOOR_BIKE ->
            indoorBike(snapshot, speedKph, maxPayload, payloadMode, onPackOutcome)

        FtmsMachineProfile.TREADMILL ->
            treadmill(snapshot, speedKph, maxPayload, payloadMode, onPackOutcome)

        FtmsMachineProfile.ELLIPTICAL -> crossTrainer(
            snapshot,
            speedKph,
            maxPayload,
            payloadMode,
            onPackOutcome,
            garminCompatibilityFraming,
        )

        FtmsMachineProfile.INDOOR_ROWER ->
            rower(snapshot, maxPayload, payloadMode, onPackOutcome)

        FtmsMachineProfile.STAIR_STEPPER ->
            stepClimber(snapshot, maxPayload, payloadMode, onPackOutcome)

        FtmsMachineProfile.STAIR_CLIMBER ->
            stairClimber(snapshot, maxPayload, payloadMode, onPackOutcome)
    }

    // ── shared field builders ────────────────────────────────────────────────

    /** km/h -> uint16 in 0.01 km/h. */
    private fun speedField(kph: Double): ByteArray =
        u16le((kph * 100.0).roundToInt().coerceIn(0, 0xFFFF))

    /** Seconds per 500 metres -> uint16 in whole seconds. */
    private fun rowerPaceField(secondsPer500m: Double): ByteArray =
        u16le(secondsPer500m.roundToInt().coerceIn(0, 0xFFFE))

    /** metres -> uint24. */
    private fun distanceField(m: Int): ByteArray = u24le(m.coerceIn(0, 0xFFFFFF))

    /**
     * Total Energy (uint16 kcal) + Energy per Hour (uint16) + Energy per Minute
     * (uint8). One flag bit covers all three, so all three must be emitted.
     */
    private fun energyField(s: FtmsMetricSnapshot): ByteArray =
        u16le(s.caloriesTotal?.coerceIn(0, 0xFFFE) ?: 0xFFFF) +
            u16le(s.caloriesPerHour?.coerceIn(0, 0xFFFE) ?: 0xFFFF) +
            u8(s.caloriesPerMinute?.coerceIn(0, 0xFE) ?: 0xFF)

    /** Positive + negative cumulative elevation gain, uint16 whole metres. */
    private fun elevationPairField(s: FtmsMetricSnapshot): ByteArray =
        u16le(s.positiveElevationGainMeters?.roundToInt()?.coerceIn(0, 0xFFFE) ?: 0xFFFF) +
            u16le(s.negativeElevationGainMeters?.roundToInt()?.coerceIn(0, 0xFFFE) ?: 0xFFFF)

    /** Positive cumulative elevation gain, uint16 whole metres. */
    private fun positiveElevationField(s: FtmsMetricSnapshot): ByteArray =
        u16le(s.positiveElevationGainMeters?.roundToInt()?.coerceIn(0, 0xFFFE) ?: 0xFFFF)

    /**
     * Inclination (sint16, 0.1 %) + Ramp Angle (sint16, 0.1 degree). One flag bit
     * covers both, so both must be emitted.
     *
     * Where no ramp angle is reported it is derived from the grade:
     *     angle = atan(grade / 100) in degrees
     * More useful to a client than the 0x7FFF "not available" sentinel.
     */
    private fun inclinationAndRampField(percent: Double): ByteArray {
        val incl = (percent * 10.0).roundToInt().coerceIn(-32768, 32767)
        val rampDeg = Math.toDegrees(atan(percent / 100.0))
        val ramp = (rampDeg * 10.0).roundToInt().coerceIn(-32768, 32767)
        return s16le(incl) + s16le(ramp)
    }

    private fun hrField(bpm: Int): ByteArray = u8(bpm.coerceIn(0, 0xFF))

    private fun elapsedField(sec: Int): ByteArray = u16le(sec.coerceIn(0, 0xFFFF))

    /** Force on Belt (sint16 N) + Power Output (sint16 W), one treadmill flag. */
    private fun forceAndPowerField(forceNewtons: Int, powerWatts: Int): ByteArray =
        s16le(forceNewtons.coerceIn(-32768, 32767)) + s16le(powerWatts.coerceIn(-32768, 32767))

    // ── Indoor Bike Data, 0x2AD2 (FTMS Table 4.10) ───────────────────────────
    //
    // bit 0 More Data | 1 Average Speed | 2 Instantaneous Cadence
    // 3 Average Cadence | 4 Total Distance | 5 Resistance Level
    // 6 Instantaneous Power | 7 Average Power | 8 Expended Energy
    // 9 Heart Rate | 10 Metabolic Equivalent | 11 Elapsed Time | 12 Remaining
    private fun indoorBike(
        s: FtmsMetricSnapshot,
        speedKph: Double?,
        maxPayload: Int,
        mode: FtmsPayloadMode,
        onOutcome: (FtmsPackOutcome) -> Unit,
    ): List<ByteArray> {
        if (speedKph == null) return emptyList()

        val optionals = mutableListOf<FtmsField>()
        s.averageSpeedKph?.let { optionals += FtmsField(1, speedField(it)) }
        // Instantaneous Cadence: uint16 in 0.5 rpm, i.e. rpm * 2.
        s.cadence?.let {
            optionals += FtmsField(2, u16le((it * 2.0).roundToInt().coerceIn(0, 0xFFFF)))
        }
        s.averageCadence?.let {
            optionals += FtmsField(3, u16le((it * 2.0).roundToInt().coerceIn(0, 0xFFFF)))
        }
        s.distanceMeters?.let { optionals += FtmsField(4, distanceField(it)) }
        s.resistance?.let { optionals += FtmsField(5, s16le(it.coerceIn(-32768, 32767))) }
        s.powerWatts?.let { optionals += FtmsField(6, s16le(it.coerceIn(-32768, 32767))) }
        s.averagePowerWatts?.let { optionals += FtmsField(7, s16le(it.coerceIn(-32768, 32767))) }
        if (s.caloriesTotal != null) optionals += FtmsField(8, energyField(s))
        s.heartRate?.let { optionals += FtmsField(9, hrField(it)) }
        val elapsed = s.elapsedSeconds?.let { FtmsField(11, elapsedField(it)) }
        elapsed?.let { optionals += it }

        return FtmsPacker.pack(
            flagsWidth = 2,
            mandatory = speedField(speedKph),
            optionals = optionals,
            elapsedField = elapsed,
            maxPayload = maxPayload,
            mode = mode,
            priorityBits = listOf(1, 2, 6, 9, 5, 4, 11, 3, 7, 8),
            onOutcome = onOutcome,
        )
    }

    // ── Treadmill Data, 0x2ACD (FTMS Table 4.5) ──────────────────────────────
    //
    // bit 0 More Data | 1 Average Speed | 2 Total Distance
    // 3 Inclination + Ramp Angle | 4 Elevation Gain | 5 Instantaneous Pace
    // 6 Average Pace | 7 Expended Energy | 8 Heart Rate
    // 9 Metabolic Equivalent | 10 Elapsed Time | 11 Remaining
    // 12 Force on Belt + Power Output
    private fun treadmill(
        s: FtmsMetricSnapshot,
        speedKph: Double?,
        maxPayload: Int,
        mode: FtmsPayloadMode,
        onOutcome: (FtmsPackOutcome) -> Unit,
    ): List<ByteArray> {
        if (speedKph == null) return emptyList()

        val optionals = mutableListOf<FtmsField>()
        s.averageSpeedKph?.let { optionals += FtmsField(1, speedField(it)) }
        s.distanceMeters?.let { optionals += FtmsField(2, distanceField(it)) }
        s.inclinationPercent?.let { optionals += FtmsField(3, inclinationAndRampField(it)) }
        if (s.positiveElevationGainMeters != null || s.negativeElevationGainMeters != null) {
            optionals += FtmsField(4, elevationPairField(s))
        }
        if (s.caloriesTotal != null) optionals += FtmsField(7, energyField(s))
        s.heartRate?.let { optionals += FtmsField(8, hrField(it)) }
        val elapsed = s.elapsedSeconds?.let { FtmsField(10, elapsedField(it)) }
        elapsed?.let { optionals += it }
        if (s.forceOnBeltNewtons != null && s.powerWatts != null) {
            optionals += FtmsField(12, forceAndPowerField(s.forceOnBeltNewtons, s.powerWatts))
        }

        return FtmsPacker.pack(
            flagsWidth = 2,
            mandatory = speedField(speedKph),
            optionals = optionals,
            elapsedField = elapsed,
            maxPayload = maxPayload,
            mode = mode,
            priorityBits = listOf(12, 1, 3, 8, 2, 10, 4, 7),
            onOutcome = onOutcome,
        )
    }

    // ── Cross Trainer Data, 0x2ACE (FTMS Table 4.6) ──────────────────────────
    //
    // NOTE: 3-octet flags field, unlike every other machine data characteristic.
    //
    // bit 0 More Data | 1 Average Speed | 2 Total Distance
    // 3 Step Count (Step/min + Average Step Rate) | 4 Stride Count
    // 5 Elevation Gain | 6 Inclination + Ramp Angle | 7 Resistance Level
    // 8 Instantaneous Power | 9 Average Power | 10 Expended Energy
    // 11 Heart Rate | 12 Metabolic Equivalent | 13 Elapsed Time
    // 14 Remaining | 15 Movement Direction
    private fun crossTrainer(
        s: FtmsMetricSnapshot,
        speedKph: Double?,
        maxPayload: Int,
        mode: FtmsPayloadMode,
        onOutcome: (FtmsPackOutcome) -> Unit,
        garminCompatibilityFraming: Boolean,
    ): List<ByteArray> {
        if (speedKph == null) return emptyList()

        val optionals = mutableListOf<FtmsField>()
        s.averageSpeedKph?.let { optionals += FtmsField(1, speedField(it)) }
        s.distanceMeters?.let { optionals += FtmsField(2, distanceField(it)) }
        // Step/min + Average Step Rate share bit 3, so emit only when both are known.
        if (s.cadence != null && s.averageCadence != null) {
            optionals += FtmsField(
                3,
                u16le(s.cadence.roundToInt().coerceIn(0, 0xFFFF)) +
                    u16le(s.averageCadence.roundToInt().coerceIn(0, 0xFFFF)),
            )
        }
        s.strokeCount?.let { optionals += FtmsField(4, u16le(it.coerceIn(0, 0xFFFF))) }
        if (s.positiveElevationGainMeters != null || s.negativeElevationGainMeters != null) {
            optionals += FtmsField(5, elevationPairField(s))
        }
        s.inclinationPercent?.let { optionals += FtmsField(6, inclinationAndRampField(it)) }
        s.resistance?.let { optionals += FtmsField(7, s16le(it.coerceIn(-32768, 32767))) }
        s.powerWatts?.let { optionals += FtmsField(8, s16le(it.coerceIn(-32768, 32767))) }
        s.averagePowerWatts?.let { optionals += FtmsField(9, s16le(it.coerceIn(-32768, 32767))) }
        if (s.caloriesTotal != null) optionals += FtmsField(10, energyField(s))
        s.heartRate?.let { optionals += FtmsField(11, hrField(it)) }
        val elapsed = s.elapsedSeconds?.let { FtmsField(13, elapsedField(it)) }
        elapsed?.let { optionals += it }

        if (garminCompatibilityFraming && maxPayload == FtmsPacker.MAX_PAYLOAD) {
            return packGarminCrossTrainer(
                mandatory = speedField(speedKph),
                optionals = optionals,
                maxPayload = maxPayload,
                onOutcome = onOutcome,
            )
        }

        return FtmsPacker.pack(
            flagsWidth = 3,
            mandatory = speedField(speedKph),
            optionals = optionals,
            elapsedField = elapsed,
            maxPayload = maxPayload,
            mode = mode,
            priorityBits = listOf(1, 3, 8, 11, 4, 7, 6, 2, 13, 5, 9, 10),
            onOutcome = onOutcome,
        )
    }

    /**
     * Garmin's MTU-23 links are more reliable with two full notifications than
     * with the strict three-fragment Cross Trainer record. This opt-in layout:
     *
     *  - moves Resistance Level (bit 7) into the first frame's two spare bytes;
     *  - omits Heart Rate (bit 11);
     *  - emits Elapsed Time (bit 13) only in the first frame.
     *
     * With every remaining field populated, both frames are exactly 20 octets.
     * This deliberately relaxes strict FTMS cross-fragment ordering and elapsed
     * repetition, so it is used only for the explicit compatibility preference.
     */
    private fun packGarminCrossTrainer(
        mandatory: ByteArray,
        optionals: List<FtmsField>,
        maxPayload: Int,
        onOutcome: (FtmsPackOutcome) -> Unit,
    ): List<ByteArray> {
        val byBit = optionals.associateBy { it.bit }
        val firstFields = listOf(1, 2, 3, 4, 7, 13).mapNotNull(byBit::get)
        val continuationFields = listOf(5, 6, 8, 9, 10).mapNotNull(byBit::get)

        fun frame(moreData: Boolean, mandatoryBytes: ByteArray, fields: List<FtmsField>): ByteArray {
            var flags = if (moreData) 1 else 0
            fields.forEach { flags = flags or (1 shl it.bit) }
            val size = 3 + mandatoryBytes.size + fields.sumOf { it.bytes.size }
            require(size <= maxPayload) {
                "Garmin Cross Trainer frame is $size octets, payload limit is $maxPayload"
            }

            val out = ByteArray(size)
            out[0] = (flags and 0xFF).toByte()
            out[1] = ((flags ushr 8) and 0xFF).toByte()
            out[2] = ((flags ushr 16) and 0xFF).toByte()
            var offset = 3
            mandatoryBytes.copyInto(out, offset)
            offset += mandatoryBytes.size
            fields.forEach { field ->
                field.bytes.copyInto(out, offset)
                offset += field.bytes.size
            }
            return out
        }

        val packets = mutableListOf(frame(false, mandatory, firstFields))
        if (continuationFields.isNotEmpty()) {
            packets += frame(true, byteArrayOf(), continuationFields)
        }
        onOutcome(
            if (packets.size == 1) FtmsPackOutcome.COMPLETE else FtmsPackOutcome.FRAGMENTED,
        )
        return packets
    }

    // ── Rower Data, 0x2AD1 (FTMS Table 4.9) ──────────────────────────────────
    //
    // Mandatory when More Data == 0: Stroke Rate (uint8, 0.5 stroke/min) plus
    // Stroke Count (uint16). There is no speed field on this characteristic.
    //
    // bit 0 More Data | 1 Average Stroke Rate | 2 Total Distance
    // 3 Instantaneous Pace | 4 Average Pace | 5 Instantaneous Power
    // 6 Average Power | 7 Resistance Level | 8 Expended Energy
    // 9 Heart Rate | 10 Metabolic Equivalent | 11 Elapsed Time | 12 Remaining
    private fun rower(
        s: FtmsMetricSnapshot,
        maxPayload: Int,
        mode: FtmsPayloadMode,
        onOutcome: (FtmsPackOutcome) -> Unit,
    ): List<ByteArray> {
        // Stroke rate and count are mandatory and have no unavailable sentinel.
        val cadence = s.cadence ?: return emptyList()
        val count = s.strokeCount ?: return emptyList()
        val strokeRate = (cadence * 2.0).roundToInt().coerceIn(0, 0xFF)
        val strokeCount = count.coerceIn(0, 0xFFFF)
        val mandatory = u8(strokeRate) + u16le(strokeCount)

        val optionals = mutableListOf<FtmsField>()
        s.averageCadence?.let {
            optionals += FtmsField(1, u8((it * 2.0).roundToInt().coerceIn(0, 0xFF)))
        }
        s.distanceMeters?.let { optionals += FtmsField(2, distanceField(it)) }
        s.paceSecondsPer500m?.let { optionals += FtmsField(3, rowerPaceField(it)) }
        s.averagePaceSecondsPer500m?.let { optionals += FtmsField(4, rowerPaceField(it)) }
        s.powerWatts?.let { optionals += FtmsField(5, s16le(it.coerceIn(-32768, 32767))) }
        s.averagePowerWatts?.let { optionals += FtmsField(6, s16le(it.coerceIn(-32768, 32767))) }
        s.resistance?.let { optionals += FtmsField(7, s16le(it.coerceIn(-32768, 32767))) }
        if (s.caloriesTotal != null) optionals += FtmsField(8, energyField(s))
        s.heartRate?.let { optionals += FtmsField(9, hrField(it)) }
        val elapsed = s.elapsedSeconds?.let { FtmsField(11, elapsedField(it)) }
        elapsed?.let { optionals += it }

        return FtmsPacker.pack(
            flagsWidth = 2,
            mandatory = mandatory,
            optionals = optionals,
            elapsedField = elapsed,
            maxPayload = maxPayload,
            mode = mode,
            priorityBits = listOf(3, 4, 5, 9, 7, 2, 11, 1, 6, 8),
            onOutcome = onOutcome,
        )
    }

    // ── Step Climber Data, 0x2ACF (FTMS Table 4.7) ───────────────────────────
    //
    // In this codebase that is FtmsMachineProfile.STAIR_STEPPER.
    // Mandatory when More Data == 0: Floors (uint16) + Step Count (uint16).
    //
    // bit 0 More Data | 1 Step per Minute | 2 Average Step Rate
    // 3 Positive Elevation Gain | 4 Expended Energy | 5 Heart Rate
    // 6 Metabolic Equivalent | 7 Elapsed Time | 8 Remaining
    private fun stepClimber(
        s: FtmsMetricSnapshot,
        maxPayload: Int,
        mode: FtmsPayloadMode,
        onOutcome: (FtmsPackOutcome) -> Unit,
    ): List<ByteArray> {
        val mandatory = u16le((s.floors ?: 0).coerceIn(0, 0xFFFF)) +
            u16le((s.strokeCount ?: 0).coerceIn(0, 0xFFFF))

        val optionals = mutableListOf<FtmsField>()
        s.cadence?.let { optionals += FtmsField(1, u16le(it.roundToInt().coerceIn(0, 0xFFFF))) }
        s.averageCadence?.let {
            optionals += FtmsField(2, u16le(it.roundToInt().coerceIn(0, 0xFFFF)))
        }
        if (s.positiveElevationGainMeters != null) {
            optionals += FtmsField(3, positiveElevationField(s))
        }
        if (s.caloriesTotal != null) optionals += FtmsField(4, energyField(s))
        s.heartRate?.let { optionals += FtmsField(5, hrField(it)) }
        val elapsed = s.elapsedSeconds?.let { FtmsField(7, elapsedField(it)) }
        elapsed?.let { optionals += it }

        return FtmsPacker.pack(
            flagsWidth = 2,
            mandatory = mandatory,
            optionals = optionals,
            elapsedField = elapsed,
            maxPayload = maxPayload,
            mode = mode,
            priorityBits = listOf(1, 5, 7, 2, 3, 4),
            onOutcome = onOutcome,
        )
    }

    // ── Stair Climber Data, 0x2AD0 (FTMS Table 4.8) ──────────────────────────
    //
    // Mandatory when More Data == 0: Floors (uint16).
    //
    // bit 0 More Data | 1 Step per Minute | 2 Average Step Rate
    // 3 Positive Elevation Gain | 4 Stride Count | 5 Expended Energy
    // 6 Heart Rate | 7 Metabolic Equivalent | 8 Elapsed Time | 9 Remaining
    private fun stairClimber(
        s: FtmsMetricSnapshot,
        maxPayload: Int,
        mode: FtmsPayloadMode,
        onOutcome: (FtmsPackOutcome) -> Unit,
    ): List<ByteArray> {
        val mandatory = u16le((s.floors ?: 0).coerceIn(0, 0xFFFF))

        val optionals = mutableListOf<FtmsField>()
        s.cadence?.let { optionals += FtmsField(1, u16le(it.roundToInt().coerceIn(0, 0xFFFF))) }
        s.averageCadence?.let {
            optionals += FtmsField(2, u16le(it.roundToInt().coerceIn(0, 0xFFFF)))
        }
        if (s.positiveElevationGainMeters != null) {
            optionals += FtmsField(3, positiveElevationField(s))
        }
        s.strokeCount?.let { optionals += FtmsField(4, u16le(it.coerceIn(0, 0xFFFF))) }
        if (s.caloriesTotal != null) optionals += FtmsField(5, energyField(s))
        s.heartRate?.let { optionals += FtmsField(6, hrField(it)) }
        val elapsed = s.elapsedSeconds?.let { FtmsField(8, elapsedField(it)) }
        elapsed?.let { optionals += it }

        return FtmsPacker.pack(
            flagsWidth = 2,
            mandatory = mandatory,
            optionals = optionals,
            elapsedField = elapsed,
            maxPayload = maxPayload,
            mode = mode,
            priorityBits = listOf(1, 6, 4, 8, 2, 3, 5),
            onOutcome = onOutcome,
        )
    }
}
