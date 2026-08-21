package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtServiceProfiles.FtmsMachineProfile
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.advertisedName
import java.util.UUID

/** Protocol-level identity selected by the debug peripheral simulator. */
sealed interface SimulatorPersona {
    val advertisedName: String

    data class Ftms(val profile: FtmsMachineProfile) : SimulatorPersona {
        override val advertisedName: String = profile.advertisedName
    }

    data class Concept2Pm5(
        val machine: Concept2Machine,
        val firmware: Concept2FirmwareProfile = Concept2FirmwareProfile.PM5_V1_30,
        val serialNumber: String = machine.syntheticSerial,
    ) : SimulatorPersona {
        init {
            require(serialNumber.length == 9 && serialNumber.all(Char::isDigit)) {
                "A PM5 simulator serial number must contain exactly nine digits"
            }
        }

        override val advertisedName: String = "PM5 $serialNumber"
    }

    /** Physical-capture-backed Precor Spinner Chrono Power fixture. */
    data object PrecorChrono : SimulatorPersona {
        override val advertisedName: String = "CHRONO 0"
    }

    /** Capture-backed setup-gated Life Fitness FTMS console. */
    data class LifeFitness(
        val machine: LifeFitnessMachine,
    ) : SimulatorPersona {
        override val advertisedName: String = machine.syntheticName
    }

    /**
     * Schwinn x70 console, gated on an initialization write.
     *
     * **One persona, not three.** The plan carried selectable 170 / 270 / 570 profiles until
     * 2026-08-19. They advertised the same name, served the same characteristics and emitted the
     * same streams — the 2026-08-18 corpus had already removed every wire-visible difference — so
     * the only thing they varied was the ride, and varying the ride by console turned out to be
     * the source of more confusion than fidelity: the same engine speed reached a client as three
     * different numbers, and none of them matched what the simulator's own UI displayed.
     *
     * Metrics now come from the shared ride engine, as they do for every other persona. What stays
     * capture-derived is the thing that actually needs to be: the frame octets (§8.2).
     */
    data object SchwinnX70 : SimulatorPersona {
        override val advertisedName: String = SCHWINN_X70_ADVERTISED_NAME
    }

    /**
     * FE-C-over-BLE equipment persona (plan `docs/fe-c-ble_simulator_implementation.md`).
     *
     * Unlike every other persona here, FE-C over BLE is not driven by [FtmsMockEngine]: it owns a
     * self-contained deterministic model (`FeCBleSyntheticModel`) so ERG/SIM control writes on
     * FEC3 can converge the same `FeCBleTelemetryEncoder` output the scheduler is emitting. See
     * `FeCBlePeripheralController` for why.
     *
     * @param controlEnabled whether pages 48-55 may mutate the machine. FEC3 itself is always
     * served so every persona can answer Common Page 70 requests for required FE Capabilities.
     * @param overlay the GATT compatibility overlay layered on the protocol-minimal persona (plan
     * §3.2). Defaults to [FeCEquipmentType.includesCpsOverlay] — Trainer and Stationary Bike always
     * carry `CPS_INCYCLIST_COMPAT`, publishing FitBridge's existing standards-compliant Cycling
     * Power Service alongside FEC1, sourced from the same `FeCBleMachineState.instantaneousPowerWatts`
     * as page 25 (plan §18.2) — never an independent generator. This is not a separate debug
     * persona; it is implicit to the equipment type and never described in the UI.
     */
    data class FeCBle(
        val equipmentType: FeCEquipmentType,
        val controlEnabled: Boolean = equipmentType.supportsControl,
        val identity: FeCBleSimulatorIdentity = FeCBleSimulatorIdentity.GENERIC,
        val overlay: FeCBleSimulatorOverlay = if (equipmentType.includesCpsOverlay) {
            FeCBleSimulatorOverlay.CPS_INCYCLIST_COMPAT
        } else {
            FeCBleSimulatorOverlay.NONE
        },
    ) : SimulatorPersona {
        override val advertisedName: String = identity.localName ?: feCBleAdvertisedName(equipmentType)
    }
}

/** Capture-grounded BLE identities layered over the same deterministic FE-C trainer model. */
enum class FeCBleSimulatorIdentity(
    val displayName: String,
    val localName: String?,
    val syntheticSerial: String,
    val supplementalRevision: Int,
    val mainRevision: Int,
    val antHardwareRevision: Int? = null,
    val antManufacturerId: Int? = null,
    val antModelNumber: Int? = null,
) {
    GENERIC("FitBridge", null, "900000000", 1, 1),
    TACX_NEO(
        displayName = "Tacx Neo",
        localName = "Tacx Neo 90002",
        syntheticSerial = "90002",
        supplementalRevision = 0,
        mainRevision = 7,
        antHardwareRevision = 1,
        antManufacturerId = 89,
        antModelNumber = 2800,
    ),
}

/** Every console in the captures discovers under this name. */
const val SCHWINN_X70_ADVERTISED_NAME = "SCHWINN 170/270"

/**
 * Timing and range constants for the one x70 persona.
 *
 * The periods are still measured — 90 to 96 ms between pairs across every captured console — and
 * the resistance span is the widest any capture showed (the 170's 1 to 22), so a generated ride
 * can visit the whole legal range rather than one machine's slice of it.
 */
object SchwinnX70Ride {
    /** Median interval between supplemental/sibling pairs, across all captured consoles. */
    const val PAIR_PERIOD_MS = 96L

    /** Median interval between 17-byte main frames. */
    const val MAIN_PERIOD_MS = 96L

    /** The family's legal resistance range; the engine's level is mapped onto it. */
    const val RESISTANCE_FLOOR = 1
    const val RESISTANCE_CEILING = 22
}

/**
 * Device Information as read from the captured 270.
 *
 * The 170's was never read and the 570 has none at all (Q3), so this is the only captured identity
 * the family has and the persona uses it verbatim.
 */
object SchwinnX70Identity {
    /**
     * **NUL-padded to nineteen octets on the wire**, which the 570u logs show and no earlier record
     * captured. TMIW does a `contains` check and never noticed; a byte-exact simulator has to.
     */
    const val MANUFACTURER = "Nautilus, Inc.\u0000\u0000\u0000\u0000\u0000"
    const val MODEL_NUMBER = "SCHWINN 170/270"
    const val HARDWARE_REVISION = "SCHWINN 170/270"
    const val FIRMWARE_REVISION = "R04.12.004P"
    const val SOFTWARE_REVISION = "H44_R15"

    /** Company `0x00F4` (Nautilus), product 0, version 0, from a Bluetooth-SIG-assigned vendor. */
    val PNP_ID: ByteArray = byteArrayOf(0x01, 0xF4.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00)
}

enum class LifeFitnessMachine(
    val displayName: String,
    val profile: FtmsMachineProfile,
    val measurementUuid: UUID,
    val equipmentServiceData: ByteArray,
    val syntheticName: String,
) {
    TREADMILL(
        "Treadmill",
        FtmsMachineProfile.TREADMILL,
        BluetoothIds.UUID_TREADMILL_DATA,
        byteArrayOf(0x01, 0x01, 0x00),
        "LF1F17B1D000001094",
    ),
    CROSS_TRAINER(
        "Cross trainer",
        FtmsMachineProfile.ELLIPTICAL,
        BluetoothIds.UUID_ELLIPTICAL_DATA,
        byteArrayOf(0x01, 0x02, 0x00),
        "LF1F17B1D000002089",
    ),
    STAIR_CLIMBER(
        "Stair climber",
        FtmsMachineProfile.STAIR_CLIMBER,
        BluetoothIds.UUID_STAIR_CLIMBER_DATA,
        byteArrayOf(0x01, 0x08, 0x00),
        "LF0F17B1D000003095",
    ),
    INDOOR_BIKE(
        "Indoor bike",
        FtmsMachineProfile.INDOOR_BIKE,
        BluetoothIds.UUID_BIKING_DATA,
        byteArrayOf(0x01, 0x20, 0x00),
        "LF0F17B1D000004090",
    ),
}

/** Physical PM5 identities; Concept2's internal simulator machine codes are deliberately absent. */
enum class Concept2Machine(
    val displayName: String,
    val wireMachineType: Int,
    val syntheticSerial: String,
) {
    ROW_ERG("RowErg", 0, "900000001"),
    SKI_ERG("SkiErg", 128, "900000002"),
    BIKE_ERG("BikeErg", 192, "900000003"),
}

data class Concept2CharacteristicManifest(
    val uuid: UUID,
    val readable: Boolean = false,
    val writable: Boolean = false,
    val notifiable: Boolean = false,
)

/** Immutable PM5 GATT surface for one evidence-backed firmware profile. */
enum class Concept2FirmwareProfile(
    val displayName: String,
    val hardwareRevision: String,
    val firmwareRevision: String,
    val characteristics: List<Concept2CharacteristicManifest>,
) {
    PM5_V1_30(
        displayName = "PM5 Bluetooth 1.30 baseline (simulated)",
        hardwareRevision = "001",
        firmwareRevision = "V199.99",
        characteristics = listOf(
            c2Notify(BluetoothIds.UUID_C2_GENERAL_STATUS),
            c2Notify(BluetoothIds.UUID_C2_ADDITIONAL_STATUS_1),
            c2Notify(BluetoothIds.UUID_C2_ADDITIONAL_STATUS_2),
            Concept2CharacteristicManifest(
                BluetoothIds.UUID_C2_STATUS_SAMPLE_RATE,
                readable = true,
                writable = true,
            ),
            c2Notify(BluetoothIds.UUID_C2_STROKE_DATA),
            c2Notify(BluetoothIds.UUID_C2_ADDITIONAL_STROKE_DATA),
            c2Notify(BluetoothIds.UUID_C2_SPLIT_INTERVAL_DATA),
            c2Notify(BluetoothIds.UUID_C2_ADDITIONAL_SPLIT_INTERVAL),
            c2Notify(BluetoothIds.UUID_C2_WORKOUT_SUMMARY),
            c2Notify(BluetoothIds.UUID_C2_ADDITIONAL_WORKOUT_SUMMARY),
            c2Notify(BluetoothIds.UUID_C2_HEART_RATE_BELT_INFO),
            c2Notify(BluetoothIds.UUID_C2_ADDITIONAL_WORKOUT_SUMMARY_2),
            c2Notify(BluetoothIds.UUID_C2_FORCE_CURVE_DATA),
            c2Notify(BluetoothIds.UUID_C2_ADDITIONAL_STATUS_3),
            c2Notify(BluetoothIds.UUID_C2_LOGGED_WORKOUT),
            c2Notify(BluetoothIds.UUID_C2_MULTIPLEXED_INFORMATION),
        ),
    );
}

private fun c2Notify(uuid: UUID) = Concept2CharacteristicManifest(uuid, notifiable = true)

/**
 * Discovery correctness is by the `FEC1` service UUID (plan §5), never by this name — it exists
 * only so a human picking from the debug persona list is not shown seven identical rows.
 */
fun feCBleAdvertisedName(equipmentType: FeCEquipmentType): String = when (equipmentType) {
    FeCEquipmentType.TRAINER -> "FitBridge FE-C Trainer"
    FeCEquipmentType.STATIONARY_BIKE -> "FitBridge FE-C Bike"
    FeCEquipmentType.TREADMILL -> "FitBridge FE-C Treadmill"
    FeCEquipmentType.ELLIPTICAL -> "FitBridge FE-C Elliptical"
    FeCEquipmentType.ROWER -> "FitBridge FE-C Rower"
    FeCEquipmentType.CLIMBER -> "FitBridge FE-C Climber"
    FeCEquipmentType.NORDIC_SKIER -> "FitBridge FE-C Skier"
}
