package com.gmail.absolutebollockscreations.gattlibrary.simulator

import com.gmail.absolutebollockscreations.gattlibrary.core.ids.FecBleUuids

/** Persona-specific bounds for the deterministic simulator workout, not physical-product claims. */
data class FeCSyntheticWorkoutRange(
    val speedKph: ClosedFloatingPointRange<Double>,
    val cadencePerMinute: IntRange,
    val powerWatts: IntRange?,
    val heartRateBpm: IntRange,
    val inclinePercent: ClosedFloatingPointRange<Double>?,
    val resistancePercent: ClosedFloatingPointRange<Double>?,
    val metabolicEquivalent: ClosedFloatingPointRange<Double>,
)

/**
 * Equipment persona this FE-C-over-BLE simulator instance is playing.
 *
 * Page 16 declares equipment type on the wire, so one implementation serves all seven; see
 * `docs/fe-c-ble_simulator_implementation.md` §3.1.
 */
enum class FeCEquipmentType(
    val wireValue: Int,
    val displayName: String,
    val supportsControl: Boolean,
    /**
     * Whether this equipment type always publishes the Incyclist CPS compatibility overlay
     * (plan §18) alongside FEC1. Cycling Power is only meaningful for the two cycling personas —
     * Trainer and Stationary Bike — never Treadmill or Rower. Unlike [supportsControl] this is not
     * user-selectable: it is baked into the persona so the debug UI never needs to describe it.
     */
    val includesCpsOverlay: Boolean,
    /** Deterministic simulator fixtures, not claims about a physical product. */
    val cycleLengthMeters: Double,
    val inclinePercent: Double,
    val resistanceLevelPercent: Double,
    val metabolicEquivalent: Double,
    val maximumResistanceNewtons: Int,
    val workoutRange: FeCSyntheticWorkoutRange,
) {
    TRAINER(
        FecBleUuids.FEC_EQUIPMENT_TRAINER,
        "Trainer",
        supportsControl = true,
        includesCpsOverlay = true,
        cycleLengthMeters = 2.10,
        inclinePercent = 0.0,
        resistanceLevelPercent = 35.0,
        metabolicEquivalent = 8.0,
        maximumResistanceNewtons = 1_200,
        workoutRange = FeCSyntheticWorkoutRange(
            24.0..42.0, 72..105, 120..360, 105..172, null, 20.0..65.0, 5.0..13.0,
        ),
    ),
    STATIONARY_BIKE(
        FecBleUuids.FEC_EQUIPMENT_STATIONARY_BIKE,
        "Stationary Bike",
        supportsControl = false,
        includesCpsOverlay = true,
        cycleLengthMeters = 2.10,
        inclinePercent = 0.0,
        resistanceLevelPercent = 30.0,
        metabolicEquivalent = 7.5,
        maximumResistanceNewtons = 650,
        workoutRange = FeCSyntheticWorkoutRange(
            18.0..36.0, 65..105, 80..300, 100..168, null, 15.0..60.0, 4.0..12.0,
        ),
    ),
    TREADMILL(
        FecBleUuids.FEC_EQUIPMENT_TREADMILL,
        "Treadmill",
        supportsControl = false,
        includesCpsOverlay = false,
        cycleLengthMeters = 1.20,
        inclinePercent = 3.0,
        resistanceLevelPercent = 0.0,
        metabolicEquivalent = 9.8,
        maximumResistanceNewtons = 1_500,
        workoutRange = FeCSyntheticWorkoutRange(
            6.5..15.0, 130..185, null, 105..178, 1.0..8.0, null, 5.0..14.0,
        ),
    ),
    ELLIPTICAL(
        FecBleUuids.FEC_EQUIPMENT_ELLIPTICAL,
        "Elliptical",
        supportsControl = false,
        includesCpsOverlay = false,
        cycleLengthMeters = 1.40,
        inclinePercent = 4.0,
        resistanceLevelPercent = 40.0,
        metabolicEquivalent = 6.5,
        maximumResistanceNewtons = 800,
        workoutRange = FeCSyntheticWorkoutRange(
            6.0..14.0, 45..85, 80..260, 100..170, 2.0..10.0, 20.0..70.0, 4.0..12.0,
        ),
    ),
    ROWER(
        FecBleUuids.FEC_EQUIPMENT_ROWER,
        "Rower",
        supportsControl = false,
        includesCpsOverlay = false,
        cycleLengthMeters = 0.90,
        inclinePercent = 0.0,
        resistanceLevelPercent = 45.0,
        metabolicEquivalent = 7.5,
        maximumResistanceNewtons = 700,
        workoutRange = FeCSyntheticWorkoutRange(
            9.0..18.0, 18..34, 80..330, 100..175, null, 25.0..65.0, 4.5..13.0,
        ),
    ),
    CLIMBER(
        FecBleUuids.FEC_EQUIPMENT_CLIMBER,
        "Climber",
        supportsControl = false,
        includesCpsOverlay = false,
        cycleLengthMeters = 0.20,
        inclinePercent = 0.0,
        resistanceLevelPercent = 50.0,
        metabolicEquivalent = 8.8,
        maximumResistanceNewtons = 1_000,
        workoutRange = FeCSyntheticWorkoutRange(
            1.1..2.9, 25..65, 90..320, 110..180, null, 25.0..75.0, 6.0..15.0,
        ),
    ),
    NORDIC_SKIER(
        FecBleUuids.FEC_EQUIPMENT_NORDIC_SKIER,
        "Nordic Skier",
        supportsControl = false,
        includesCpsOverlay = false,
        cycleLengthMeters = 1.50,
        inclinePercent = 0.0,
        resistanceLevelPercent = 40.0,
        metabolicEquivalent = 9.0,
        maximumResistanceNewtons = 600,
        workoutRange = FeCSyntheticWorkoutRange(
            8.0..20.0, 30..72, 80..340, 105..178, null, 20.0..65.0, 5.0..14.0,
        ),
    ),
}

/** Page 16 / page 25 bits 4-6: the four FE-C equipment states (plan §9). */
internal enum class FeCEquipmentState(val wireValue: Int) {
    OFF(1),
    READY(2),
    IN_USE(3),
    FINISHED(4),
}

/** Page 16 byte 7 bits 0-1: heart-rate source, preserved independently of the state bits. */
internal enum class FeCHeartRateSource(val wireValue: Int) {
    INVALID(0),
    HAND_CONTACT(1),
    EM(2),
    ANT(3),
}

/**
 * GATT compatibility overlay layered on top of the FE-C-over-BLE protocol (plan §3.2).
 *
 * `NONE` is the protocol-minimal persona: FEC1 alone is the authority for FitBridge's FE-C
 * contract. `CPS_INCYCLIST_COMPAT` additionally publishes FitBridge's existing standards-compliant
 * Cycling Power Service (`0x1818`) on the same GATT server, because the current Incyclist Tacx
 * sensor's `getRequiredCharacteristics()` requires Cycling Power Measurement alongside FEC2 — a
 * stricter requirement than the transport document itself establishes (plan §18.1).
 *
 * **Not a user-selectable debug option.** [FeCEquipmentType.includesCpsOverlay] decides this
 * automatically per equipment type (Trainer and Stationary Bike always carry it; the five
 * non-cycling personas never do) — there is no separate "+ CPS" persona in the debug UI, and the
 * overlay is
 * never mentioned in a persona's label or summary text. Only the two overlays required for
 * Phase-9's definition of done are modeled; the plan's optional test-only overlays (CSC,
 * FTMS_HEALTHY, FTMS_SILENT_DATA) are deferred.
 */
enum class FeCBleSimulatorOverlay {
    NONE,
    CPS_INCYCLIST_COMPAT,
}

/** Which control page most recently established the active load-shaping behaviour. */
internal enum class FeCBleControlMode {
    FREE,
    BASIC_RESISTANCE,
    ERG,
    SIMULATION,
}

/** Decoded, retained page-50 fields. Encoded-domain only; see FEC-SQ2 — no physical unit is claimed. */
internal data class FeCBleWindConfig(
    val windCoefficient: Double,
    val windSpeedEncodedUnit: Int,
    val draftingFactor: Double,
)

/** Decoded page-51 fields. */
internal data class FeCBleTrackConfig(
    val gradePercent: Double,
    val crr: Double,
)

/** Decoded page-55 fields, Incyclist-compatible interpretation only (plan §13.5). */
internal data class FeCBleUserConfig(
    val userWeightKg: Double?,
    val rawPage: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is FeCBleUserConfig &&
                userWeightKg == other.userWeightKg &&
                rawPage.contentEquals(other.rawPage)
            )

    override fun hashCode(): Int = (userWeightKg?.hashCode() ?: 0) * 31 + rawPage.contentHashCode()
}

/**
 * One coherent mutable simulator state (plan §8). No Android objects; every emitted stream and
 * every control response renders from exactly this.
 *
 * Internal counters are deliberately wider than their wire encodings (§8.1) so long-duration runs
 * and rollover tests are straightforward: the wire value is always `internalCounter & mask`,
 * computed only at the encoder boundary.
 */
internal class FeCBleMachineState(
    val equipmentType: FeCEquipmentType,
    val controlEnabled: Boolean = equipmentType.supportsControl,
    val serialNumber: Long = DEFAULT_SERIAL,
    val softwareRevisionSupplemental: Int = DEFAULT_SW_SUPPLEMENTAL,
    val softwareRevisionMain: Int = DEFAULT_SW_MAIN,
) {
    var feState: FeCEquipmentState = FeCEquipmentState.READY

    var elapsedQuarterSeconds: Long = 0
    var distanceMeters: Double = 0.0
    var speedMps: Double = 0.0
    var heartRateBpm: Int? = null
    var heartRateSource: FeCHeartRateSource = FeCHeartRateSource.INVALID
    var virtualSpeed: Boolean = false
    var lapToggle: Boolean = false
    var distanceCapable: Boolean = true

    var cadenceRpm: Int? = null
    var instantaneousPowerWatts: Int? = null
    var accumulatedPower: Long = 0
    var eventCount: Long = 0
    var trainerStatusRaw: Int = 0
    var targetStatusRaw: Int = 0

    var cycleLengthMeters: Double = equipmentType.cycleLengthMeters
    var inclinePercent: Double = equipmentType.inclinePercent
    var resistanceLevelPercent: Double = equipmentType.resistanceLevelPercent
    var metabolicEquivalent: Double = equipmentType.metabolicEquivalent
    var caloricBurnRateKcalPerHour: Double = metabolicEquivalent * METABOLIC_FIXTURE_BODY_MASS_KG
    var accumulatedCaloriesKcal: Double = 0.0

    /** Current deterministic interval stage, surfaced only for simulator diagnostics. */
    var intervalName: String = FeCBleSyntheticModel.initialIntervalName
    var intervalSecondsRemaining: Int = FeCBleSyntheticModel.initialIntervalSeconds
    var intervalIntensityPercent: Int = 0

    // Page 19 (Treadmill) fields. Internally wider than the wire's 0.1m/byte encoding, per the
    // same §8.1 rationale as the trainer counters above - the wire value is `& 0xFF` at the
    // encoder boundary only.
    var positiveVerticalDistanceMeters: Double = 0.0
    var negativeVerticalDistanceMeters: Double = 0.0

    /** Strides, strokes, or complete climber cycles according to the active specific page. */
    var movementCount: Double = 0.0

    // Page 26 trainer wheel-torque accumulators. Doubles retain sub-unit increments until encode.
    var torqueEventCount: Long = 0
    var wheelTicks: Long = 0
    var wheelRevolutions: Double = 0.0
    var wheelPeriodTicks: Long = 0
    var accumulatedTorqueThirtySecondNm: Double = 0.0

    var controlMode: FeCBleControlMode = FeCBleControlMode.FREE
    var basicResistancePercent: Double? = null
    var targetPowerWatts: Double? = null
    var wind: FeCBleWindConfig? = null
    var track: FeCBleTrackConfig? = null
    var userConfig: FeCBleUserConfig? = null

    /** Address (diagnostics only) of the client whose control write last mutated this state. */
    var lastControlClient: String? = null

    /** Reset every field to a fresh READY generation. Used at controller start and persona switch. */
    fun reset() {
        feState = FeCEquipmentState.READY
        elapsedQuarterSeconds = 0
        distanceMeters = 0.0
        speedMps = 0.0
        heartRateBpm = null
        heartRateSource = FeCHeartRateSource.INVALID
        virtualSpeed = false
        lapToggle = false
        cadenceRpm = null
        instantaneousPowerWatts = null
        accumulatedPower = 0
        eventCount = 0
        trainerStatusRaw = 0
        targetStatusRaw = 0
        cycleLengthMeters = equipmentType.cycleLengthMeters
        inclinePercent = equipmentType.inclinePercent
        resistanceLevelPercent = equipmentType.resistanceLevelPercent
        metabolicEquivalent = equipmentType.metabolicEquivalent
        caloricBurnRateKcalPerHour = metabolicEquivalent * METABOLIC_FIXTURE_BODY_MASS_KG
        accumulatedCaloriesKcal = 0.0
        intervalName = FeCBleSyntheticModel.initialIntervalName
        intervalSecondsRemaining = FeCBleSyntheticModel.initialIntervalSeconds
        intervalIntensityPercent = 0
        positiveVerticalDistanceMeters = 0.0
        negativeVerticalDistanceMeters = 0.0
        movementCount = 0.0
        torqueEventCount = 0
        wheelTicks = 0
        wheelRevolutions = 0.0
        wheelPeriodTicks = 0
        accumulatedTorqueThirtySecondNm = 0.0
        controlMode = FeCBleControlMode.FREE
        basicResistancePercent = null
        targetPowerWatts = null
        wind = null
        track = null
        userConfig = null
        lastControlClient = null
    }

    /**
     * Enter [FeCEquipmentState.IN_USE] with the plan's §8.2 default fixture values.
     *
     * Previously this only seeded Trainer/Stationary Bike, leaving Treadmill and Rower personas
     * stuck at speed=0/cadence=null/HR=null for their entire simulated session - the bug behind
     * "no metrics received... speed and distance remain 0" for those two personas. Every
     * persona now gets a coherent starting fixture; the per-persona branches below exist only
     * because the *fields that mean anything* differ (a treadmill has no power field on its
     * wire page 19 at all, so seeding [instantaneousPowerWatts] for it would set a value the
     * encoder never transmits and the central never sees).
     */
    fun enterInUseWithDefaults() {
        feState = FeCEquipmentState.IN_USE
        heartRateBpm = DEFAULT_HEART_RATE_BPM
        heartRateSource = FeCHeartRateSource.EM
        when (equipmentType) {
            FeCEquipmentType.TRAINER, FeCEquipmentType.STATIONARY_BIKE -> {
                speedMps = DEFAULT_SPEED_MPS
                cadenceRpm = DEFAULT_CADENCE_RPM
                instantaneousPowerWatts = DEFAULT_POWER_WATTS
            }
            FeCEquipmentType.TREADMILL -> {
                speedMps = DEFAULT_TREADMILL_SPEED_MPS
                cadenceRpm = DEFAULT_TREADMILL_CADENCE_SPM
                // No instantaneousPowerWatts: page 19 has no power field (Table 8-15).
            }
            FeCEquipmentType.ELLIPTICAL -> {
                speedMps = DEFAULT_ELLIPTICAL_SPEED_MPS
                cadenceRpm = DEFAULT_ELLIPTICAL_CADENCE_SPM
                instantaneousPowerWatts = DEFAULT_ELLIPTICAL_POWER_WATTS
            }
            FeCEquipmentType.ROWER -> {
                speedMps = DEFAULT_ROWER_SPEED_MPS
                cadenceRpm = DEFAULT_ROWER_CADENCE_SPM
                instantaneousPowerWatts = DEFAULT_ROWER_POWER_WATTS
            }
            FeCEquipmentType.CLIMBER -> {
                speedMps = DEFAULT_CLIMBER_SPEED_MPS
                cadenceRpm = DEFAULT_CLIMBER_CADENCE_CPM
                instantaneousPowerWatts = DEFAULT_CLIMBER_POWER_WATTS
            }
            FeCEquipmentType.NORDIC_SKIER -> {
                speedMps = DEFAULT_NORDIC_SKIER_SPEED_MPS
                cadenceRpm = DEFAULT_NORDIC_SKIER_CADENCE_SPM
                instantaneousPowerWatts = DEFAULT_NORDIC_SKIER_POWER_WATTS
            }
        }
    }

    companion object {
        const val DEFAULT_SERIAL: Long = 0x12345678L
        const val DEFAULT_SW_SUPPLEMENTAL = 0x05
        const val DEFAULT_SW_MAIN = 0x01

        const val DEFAULT_SPEED_MPS = 8.333
        const val DEFAULT_CADENCE_RPM = 90
        const val DEFAULT_POWER_WATTS = 200
        const val DEFAULT_HEART_RATE_BPM = 140

        // Treadmill: a moderate jog. Rower: a moderate steady-state split, expressed as an
        // equivalent linear speed for page 16's speed field per plan/spec §10.1.2.3.
        const val DEFAULT_TREADMILL_SPEED_MPS = 2.5
        const val DEFAULT_TREADMILL_CADENCE_SPM = 160
        const val DEFAULT_ROWER_SPEED_MPS = 4.0
        const val DEFAULT_ROWER_CADENCE_SPM = 24
        const val DEFAULT_ROWER_POWER_WATTS = 150
        const val DEFAULT_ELLIPTICAL_SPEED_MPS = 2.2
        const val DEFAULT_ELLIPTICAL_CADENCE_SPM = 70
        const val DEFAULT_ELLIPTICAL_POWER_WATTS = 170
        const val DEFAULT_CLIMBER_SPEED_MPS = 0.5
        const val DEFAULT_CLIMBER_CADENCE_CPM = 50
        const val DEFAULT_CLIMBER_POWER_WATTS = 190
        const val DEFAULT_NORDIC_SKIER_SPEED_MPS = 3.5
        const val DEFAULT_NORDIC_SKIER_CADENCE_SPM = 55
        const val DEFAULT_NORDIC_SKIER_POWER_WATTS = 210

        /** Synthetic metabolic pages assume one fixed 75 kg rider for internally coherent kcal/h. */
        const val METABOLIC_FIXTURE_BODY_MASS_KG = 75.0
    }
}
