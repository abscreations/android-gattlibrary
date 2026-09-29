package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtServiceProfiles
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtServiceProfiles.FtmsMachineProfile
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.advertisedName
import java.util.UUID

/** Protocol-level identity selected by the debug peripheral simulator. */
sealed interface SimulatorPersona {
    val advertisedName: String

    /** Indoor-bike ride published through the native CSC and Cycling Power sensor services. */
    data object IndoorBikeSpeedAndPower : SimulatorPersona {
        override val advertisedName: String = BtServiceProfiles.DEVICE_NAME_CSC_CPS
    }

    /**
     * Running footpod publishing the native RSC and Cycling Power sensor services.
     *
     * Sits directly below [IndoorBikeSpeedAndPower] because it is the running counterpart of it:
     * both are native sensor services rather than a fitness machine, and neither accepts control.
     * Where the bike persona integrates speed and cadence into wheel and crank events, this one
     * integrates speed into *stride* events and publishes them twice - as RSC cadence and, exactly
     * as the captured hardware does, as the Cycling Power crank counter.
     *
     * Wire specification and evidence: `docs/stryd-footpod-simulator-implementation.md`.
     */
    data object StrydFootpod : SimulatorPersona {
        override val advertisedName: String = "Stryd"
    }

    /**
     * Standard FTMS persona.
     *
     * @param advertisedMachineType the Fitness Machine Type written into the advertisement's FTMS
     * Service Data. It equals [profile] for every honest persona. The one deliberate exception is
     * [BROKEN_ROWER_ADVERTISEMENT]: a rower that advertises itself as a cross trainer, so a
     * client's scan filter (and its "show all devices" escape hatch) can be tested against a
     * machine whose advertisement misstates its type. GATT, data characteristic and the ride are
     * still [profile]'s; only the advertisement lies.
     */
    data class Ftms(
        val profile: FtmsMachineProfile,
        val advertisedMachineType: FtmsMachineProfile = profile,
    ) : SimulatorPersona {
        override val advertisedName: String = profile.advertisedName

        /** True when the advertisement's machine type deliberately disagrees with [profile]. */
        val misadvertisesMachineType: Boolean get() = advertisedMachineType != profile

        companion object {
            /** Standard FTMS rower whose advertisement Service Data claims Cross Trainer. */
            val BROKEN_ROWER_ADVERTISEMENT = Ftms(
                profile = FtmsMachineProfile.INDOOR_ROWER,
                advertisedMachineType = FtmsMachineProfile.ELLIPTICAL,
            )
        }
    }

    data class Concept2Pm5(
        val machine: Concept2Machine,
        val compatibility: Concept2Pm5CompatibilityProfile =
            Concept2Pm5CompatibilityProfile.forMachine(machine),
    ) : SimulatorPersona {
        init {
            require(compatibility.machine == machine) {
                "A PM5 simulator persona and compatibility profile must describe the same machine"
            }
            require(compatibility.serialNumber.length == 9 && compatibility.serialNumber.all(Char::isDigit)) {
                "A PM5 simulator serial number must contain exactly nine digits"
            }
        }

        override val advertisedName: String = compatibility.advertisedName
    }

    /** Best-effort Android host for one M-series validation identity. */
    data class KeiserMSeries(
        val profile: KeiserMSeriesSimulatorProfile,
    ) : SimulatorPersona {
        override val advertisedName: String = profile.advertisedName
    }

    /** Physical-capture-backed Precor Spinner Chrono Power fixture. */
    data object PrecorChrono : SimulatorPersona {
        override val advertisedName: String = "CHRONO 0"
    }

    /** Corroborated Flywheel Sports measurement protocol validation fixture. */
    data object FlywheelSports : SimulatorPersona {
        override val advertisedName: String = "Flywheel 1"
    }

    /** Capture-corroborated KayakFirst Blue console hosted on the evidenced FFE0/FFE1 surface. */
    data object KayakFirstBlue : SimulatorPersona {
        // Deliberately synthetic: the captures do not prove a stable physical-device name.
        override val advertisedName: String = "25100001"
    }

    /** E2 4.2.0 Orange timing-MCU protocol on the documented Blue-derived RF shell. */
    data object KayakFirstOrange : SimulatorPersona {
        override val advertisedName: String = KayakFirstOrangeSimulatorProtocol.ADVERTISED_NAME
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

    /**
     * Debug-only KICKR interoperability simulator; never offered by a release selector.
     *
     * @param controlEnabled whether this run answers FTMS target-setting procedures. All four
     * KICKR/Zwift personas implement FTMS control for real - Kinomap and MyWhoosh captures show
     * third-party apps driving them through 0x2AD9 - so this defaults on and the simulator's
     * "Accept target commands" switch exists to take it away for a data-only test, not to grant a
     * capability the persona would otherwise lack. Control is still negotiated per route
     * (`KickrControlRoute`), so this gates the FTMS route only; the legacy Wahoo and RideOn
     * control points are unaffected.
     */
    data class Kickr(
        val device: KickrSimulatorPersona,
        val controlEnabled: Boolean = true,
    ) : SimulatorPersona {
        override val advertisedName: String = KickrPersonaManifest.forPersona(device).advertising.localName
    }
}

/** Distinct identities intended to run on distinct Android simulator phones. */
enum class KeiserMSeriesSimulatorProfile(
    val displayName: String,
    val advertisedName: String,
    val systemId: Int,
    val distanceUnit: KeiserMSeriesSimulatorDistanceUnit,
    val firmwareMajor: Int,
    val firmwareMinor: Int,
    val samplePeriodMs: Long,
    val advertisingIntervalUnits: Int,
    val statsTimeoutMs: Long,
) {
    M3I_IMPERIAL(
        displayName = "M3i · Imperial validation",
        advertisedName = "M3i",
        systemId = 1,
        distanceUnit = KeiserMSeriesSimulatorDistanceUnit.MILES,
        firmwareMajor = 6,
        firmwareMinor = 0x30,
        samplePeriodMs = 354L,
        advertisingIntervalUnits = 0x0237,
        statsTimeoutMs = 2_000L,
    ),
    M3M_METRIC(
        displayName = "M3m · Metric validation",
        advertisedName = "M3m",
        systemId = 2,
        distanceUnit = KeiserMSeriesSimulatorDistanceUnit.KILOMETRES,
        firmwareMajor = 6,
        firmwareMinor = 0x30,
        samplePeriodMs = 354L,
        advertisingIntervalUnits = 0x0237,
        statsTimeoutMs = 2_000L,
    ),
    M3O_OLD_FIRMWARE(
        displayName = "M3o · Old firmware validation",
        advertisedName = "M3o",
        systemId = 3,
        distanceUnit = KeiserMSeriesSimulatorDistanceUnit.MILES,
        firmwareMajor = 6,
        firmwareMinor = 0x22,
        samplePeriodMs = 2_000L,
        advertisingIntervalUnits = 0x0C80,
        statsTimeoutMs = 7_000L,
    ),
}

enum class KeiserMSeriesSimulatorDistanceUnit(val metresPerTenth: Double) {
    MILES(160.934),
    KILOMETRES(100.0),
}

/** Shared routing facts for personas hosted by the standard FTMS/CSC/CPS provider. */
internal data class StandardSimulatorSurface(
    val profile: FtmsMachineProfile,
    val nativeBikeSensors: Boolean,
    /** Machine type carried in the advertisement's FTMS Service Data; normally [profile]. */
    val advertisedMachineType: FtmsMachineProfile = profile,
)

internal fun SimulatorPersona.standardSurface(): StandardSimulatorSurface? = when (this) {
    SimulatorPersona.IndoorBikeSpeedAndPower -> StandardSimulatorSurface(
        profile = FtmsMachineProfile.INDOOR_BIKE,
        nativeBikeSensors = true,
    )
    is SimulatorPersona.Ftms -> StandardSimulatorSurface(
        profile = profile,
        nativeBikeSensors = false,
        advertisedMachineType = advertisedMachineType,
    )
    else -> null
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
    TACX_FLUX_2(
        displayName = "Tacx Flux 2",
        // Capture name grammar is "Tacx Flux-2 <suffix>". The captured unit's suffix is not
        // copied: 90001 is an unmistakably synthetic simulator identity.
        localName = "Tacx Flux-2 90001",
        syntheticSerial = "90001",
        supplementalRevision = 3,
        mainRevision = 45,
        antHardwareRevision = 1,
        antManufacturerId = 89,
        // Common Page 80 reports decimal model 2980; this is distinct from the advertisement's
        // opaque manufacturer bytes `29 80` and must not be hex-reinterpreted.
        antModelNumber = 2980,
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
    val writeWithoutResponse: Boolean = false,
    val notifiable: Boolean = false,
)

enum class Concept2StatusPayloadRevision {
    LEGACY_12_BYTE,
}

/**
 * Which physical PM5 generation's attribute surface a persona reproduces.
 *
 * The QZ hardware captures in `fixtures/hardware/concept2_pm5_qz_debug.json` contain two
 * physically distinct PM5 units whose GATT differs by more than firmware values:
 *
 * | | `PM5 430392046` | `PM5 530382227 Row` |
 * |---|---|---|
 * | advertised services | `CE060000` only | `0x1826` FTMS + `CE060000` |
 * | Device Information | `0x0012`-`0x0015` | + `0x0011`, `0x0016`, `0x0017`, `0x0018` |
 * | erg service | `0x0031`-`0x003B`, `0x0080` | + `0x003D`, `0x003E`, `0x003F` |
 * | `CE060040` heart-rate receive | absent | present |
 *
 * [PRE_FTMS] reproduces the first column, octet for octet, because that unit is the one an
 * old-style interoperability test is meant to stand in for. A client that succeeds against
 * this persona should succeed against that hardware, and — the part a superset surface
 * breaks — a client that needs a characteristic the hardware lacks should fail here too.
 */
enum class Concept2Pm5GattSurface(val displayName: String) {
    /** Attested by `PM5 430392046`: no FTMS, no `0x0016`, no `CE060040`, erg service ends at `0x003B`. */
    PRE_FTMS("Pre-FTMS PM5"),

    /**
     * Attested by `PM5 431225189 Row`, firmware `207.000`, in the 2026-09-12 btsnoop corpus.
     * It adds the four newer Device Information characteristics, `0x003D`, and the minimal
     * FTMS Feature/Rower Data service, while omitting `0x003C`, `0x003E`, `0x003F`, and
     * `CE060040`.
     */
    MODERN("Modern PM5"),
}

/**
 * Coherent, deliberately synthetic identity and GATT contract for one ErgData-compatible persona.
 *
 * The numeric families are parser compatibility values, not claims of released Concept2 builds.
 * They select ErgData's V2 Row/Ski/Bike families while remaining below the thresholds that make
 * Additional Status 3 require fields beyond FitBridge's evidenced 12-byte payload.
 */
enum class Concept2Pm5CompatibilityProfile(
    val machine: Concept2Machine,
    val advertisedName: String,
    val serialNumber: String,
    val displayName: String,
    val hardwareRevision: String,
    val firmwareIdentity: String,
    val statusPayloadRevision: Concept2StatusPayloadRevision,
    val gattSurface: Concept2Pm5GattSurface,
    val deviceInformation: List<UUID>,
    val exposesHeartRateReceive: Boolean,
    val exposesFtms: Boolean,
    val characteristics: List<Concept2CharacteristicManifest>,
) {
    ERGDATA_V2_ROW(
        machine = Concept2Machine.ROW_ERG,
        advertisedName = "PM5 431225189 Row",
        serialNumber = "431225189",
        displayName = "Captured PM5 207 RowErg",
        hardwareRevision = "903",
        firmwareIdentity = "8200-000409-207.000\u0000",
        statusPayloadRevision = Concept2StatusPayloadRevision.LEGACY_12_BYTE,
        gattSurface = Concept2Pm5GattSurface.MODERN,
        deviceInformation = modernDeviceInformation(),
        exposesHeartRateReceive = false,
        exposesFtms = true,
        characteristics = modernErgCharacteristics(),
    ),
    ERGDATA_V2_SKI(
        machine = Concept2Machine.SKI_ERG,
        advertisedName = "PM5 900000002 Ski",
        serialNumber = "900000002",
        displayName = "Synthetic ErgData V2 SkiErg compatibility",
        hardwareRevision = "001",
        firmwareIdentity = "PM5SIMULATOR878.028 ",
        statusPayloadRevision = Concept2StatusPayloadRevision.LEGACY_12_BYTE,
        gattSurface = Concept2Pm5GattSurface.MODERN,
        deviceInformation = modernDeviceInformation(),
        exposesHeartRateReceive = false,
        exposesFtms = true,
        characteristics = modernErgCharacteristics(),
    ),
    ERGDATA_V2_BIKE(
        machine = Concept2Machine.BIKE_ERG,
        advertisedName = "PM5 900000003 Bike",
        serialNumber = "900000003",
        displayName = "Synthetic ErgData V2 BikeErg compatibility",
        hardwareRevision = "001",
        firmwareIdentity = "PM5SIMULATOR336.028 ",
        statusPayloadRevision = Concept2StatusPayloadRevision.LEGACY_12_BYTE,
        gattSurface = Concept2Pm5GattSurface.MODERN,
        deviceInformation = modernDeviceInformation(),
        exposesHeartRateReceive = false,
        exposesFtms = true,
        characteristics = modernErgCharacteristics(),
    ),

    // The pre-FTMS set. Same synthetic firmware families as their modern counterparts,
    // because the generations differ by attribute surface rather than by ErgData's
    // classification number; the serial is what tells the two personas apart on air.
    PRE_FTMS_ROW(
        machine = Concept2Machine.ROW_ERG,
        advertisedName = "PM5 900000101",
        serialNumber = "900000101",
        displayName = "Pre-FTMS RowErg (C2DataField target)",
        hardwareRevision = "001",
        firmwareIdentity = "PM5SIMULATOR178.028 ",
        statusPayloadRevision = Concept2StatusPayloadRevision.LEGACY_12_BYTE,
        gattSurface = Concept2Pm5GattSurface.PRE_FTMS,
        deviceInformation = preFtmsDeviceInformation(),
        exposesHeartRateReceive = false,
        exposesFtms = false,
        characteristics = preFtmsErgCharacteristics(),
    ),
    PRE_FTMS_SKI(
        machine = Concept2Machine.SKI_ERG,
        advertisedName = "PM5 900000102",
        serialNumber = "900000102",
        displayName = "Pre-FTMS SkiErg (C2DataField target)",
        hardwareRevision = "001",
        firmwareIdentity = "PM5SIMULATOR878.028 ",
        statusPayloadRevision = Concept2StatusPayloadRevision.LEGACY_12_BYTE,
        gattSurface = Concept2Pm5GattSurface.PRE_FTMS,
        deviceInformation = preFtmsDeviceInformation(),
        exposesHeartRateReceive = false,
        exposesFtms = false,
        characteristics = preFtmsErgCharacteristics(),
    ),
    PRE_FTMS_BIKE(
        machine = Concept2Machine.BIKE_ERG,
        advertisedName = "PM5 900000103",
        serialNumber = "900000103",
        displayName = "Pre-FTMS BikeErg (C2DataField target)",
        hardwareRevision = "001",
        firmwareIdentity = "PM5SIMULATOR336.028 ",
        statusPayloadRevision = Concept2StatusPayloadRevision.LEGACY_12_BYTE,
        gattSurface = Concept2Pm5GattSurface.PRE_FTMS,
        deviceInformation = preFtmsDeviceInformation(),
        exposesHeartRateReceive = false,
        exposesFtms = false,
        characteristics = preFtmsErgCharacteristics(),
    );

    val parsedFirmwareNumber: Float
        get() = firmwareIdentity.substring(12, 15).toFloat() +
            firmwareIdentity.substring(16).filter { it.isDigit() || it == ' ' }.toFloat() * 0.001f

    init {
        require(advertisedName.startsWith("PM"))
        require(firmwareIdentity.toByteArray(Charsets.US_ASCII).size == 20)
        if (serialNumber != "431225189") {
            require(parsedFirmwareNumber in concept2ErgDataV2Range(machine))
            require(parsedFirmwareNumber < concept2ExtendedStatus3Threshold(machine))
        }
    }

    companion object {
        /** The modern surface stays the default, so existing callers keep the ErgData-proven persona. */
        fun forMachine(machine: Concept2Machine): Concept2Pm5CompatibilityProfile =
            forMachine(machine, Concept2Pm5GattSurface.MODERN)

        fun forMachine(
            machine: Concept2Machine,
            surface: Concept2Pm5GattSurface,
        ): Concept2Pm5CompatibilityProfile = entries.single {
            it.machine == machine && it.gattSurface == surface
        }

    }
}

private fun concept2ErgDataV2Range(machine: Concept2Machine): ClosedFloatingPointRange<Float> = when (machine) {
    Concept2Machine.ROW_ERG -> 150f..199.9999f
    Concept2Machine.SKI_ERG -> 850f..899.9999f
    Concept2Machine.BIKE_ERG -> 300f..349.9999f
}

private fun concept2ExtendedStatus3Threshold(machine: Concept2Machine): Float = when (machine) {
    Concept2Machine.ROW_ERG -> 178.050f
    Concept2Machine.SKI_ERG -> 878.050f
    Concept2Machine.BIKE_ERG -> 336.050f
}

/**
 * Device Information as `PM5 530382227 Row` exposes it: the pre-FTMS four plus the model
 * number, erg machine type and the two transport-hint characteristics.
 */
private fun modernDeviceInformation(): List<UUID> = listOf(
    BluetoothIds.UUID_C2_MODEL_NUMBER,
    BluetoothIds.UUID_C2_SERIAL_NUMBER,
    BluetoothIds.UUID_C2_HARDWARE_REVISION,
    BluetoothIds.UUID_C2_FIRMWARE_REVISION,
    BluetoothIds.UUID_C2_MANUFACTURER_NAME,
    BluetoothIds.UUID_C2_MACHINE_TYPE,
    BluetoothIds.UUID_C2_ATT_RECEIVE_MTU,
    BluetoothIds.UUID_C2_LL_MAX_TX_RX_BYTES,
)

/**
 * The erg service observed on `PM5 431225189 Row`, firmware `207.000`: `0x0031` through
 * `0x003B`, then `0x0080` and `0x003D`. In particular, the captured modern monitor does not
 * expose `0x003C`, `0x003E`, `0x003F`, or the `CE060040` heart-rate receive service.
 */
private fun modernErgCharacteristics() = listOf(
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
    c2Notify(BluetoothIds.UUID_C2_MULTIPLEXED_INFORMATION),
    c2Notify(BluetoothIds.UUID_C2_FORCE_CURVE_DATA),
)

/**
 * Device Information as `PM5 430392046` exposes it: serial, hardware revision, firmware
 * revision and manufacturer name, and nothing else.
 *
 * `0x0011` model number, `0x0016` erg machine type and the `0x0017`/`0x0018` transport hints
 * are omitted because that unit does not have them. Dropping `0x0016` costs nothing for
 * ErgData, which derives Row/Ski/Bike from the firmware number and never reads `0x0016` — and
 * the hardware makes the same point more forcefully by not implementing it at all.
 */
private fun preFtmsDeviceInformation(): List<UUID> = listOf(
    BluetoothIds.UUID_C2_SERIAL_NUMBER,
    BluetoothIds.UUID_C2_HARDWARE_REVISION,
    BluetoothIds.UUID_C2_FIRMWARE_REVISION,
    BluetoothIds.UUID_C2_MANUFACTURER_NAME,
)

/**
 * The erg service as `PM5 430392046` exposes it: `0x0031` through `0x003B`, plus `0x0080`.
 *
 * `0x003C`-`0x003F` are deliberately absent. Beyond matching the hardware, this closes the
 * one difference with a demonstrated failure path: ErgData treats `0x003D` force curve and
 * `0x003E` Additional Status 3 as optional at discovery but subscribes to whichever it finds,
 * and a failed subscription still fails its initialization. Publishing neither removes that
 * branch instead of relying on firmware numbers staying below the version thresholds that
 * make `0x003E` require offsets past our 12-octet payload.
 */
private fun preFtmsErgCharacteristics() = listOf(
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
    c2Notify(BluetoothIds.UUID_C2_MULTIPLEXED_INFORMATION),
)

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
