package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds

/**
 * Equipment persona this FE-C-over-BLE simulator instance is playing.
 *
 * Page 16 declares equipment type on the wire, so one implementation serves all four; see
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
) {
    TRAINER(
        BluetoothIds.FEC_EQUIPMENT_TRAINER,
        "Trainer",
        supportsControl = true,
        includesCpsOverlay = true,
    ),
    STATIONARY_BIKE(
        BluetoothIds.FEC_EQUIPMENT_STATIONARY_BIKE,
        "Stationary Bike",
        supportsControl = false,
        includesCpsOverlay = true,
    ),
    TREADMILL(
        BluetoothIds.FEC_EQUIPMENT_TREADMILL,
        "Treadmill",
        supportsControl = false,
        includesCpsOverlay = false,
    ),
    ROWER(
        BluetoothIds.FEC_EQUIPMENT_ROWER,
        "Rower",
        supportsControl = false,
        includesCpsOverlay = false,
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
 * automatically per equipment type (Trainer and Stationary Bike always carry it, Treadmill and
 * Rower never do) — there is no separate "+ CPS" persona in the debug UI, and the overlay is
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
        controlMode = FeCBleControlMode.FREE
        basicResistancePercent = null
        targetPowerWatts = null
        wind = null
        track = null
        userConfig = null
        lastControlClient = null
    }

    /** Enter [FeCEquipmentState.IN_USE] with the plan's §8.2 default Trainer fixture values. */
    fun enterInUseWithDefaults() {
        feState = FeCEquipmentState.IN_USE
        if (equipmentType == FeCEquipmentType.TRAINER || equipmentType == FeCEquipmentType.STATIONARY_BIKE) {
            speedMps = DEFAULT_SPEED_MPS
            cadenceRpm = DEFAULT_CADENCE_RPM
            instantaneousPowerWatts = DEFAULT_POWER_WATTS
            heartRateBpm = DEFAULT_HEART_RATE_BPM
            heartRateSource = FeCHeartRateSource.EM
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
    }
}
