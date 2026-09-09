package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtServiceProfiles.FtmsMachineProfile
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.TargetCapabilities
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.dataCharacteristic
import java.util.Locale
import java.util.UUID

/**
 * Normalized descriptors for the simulator's persona selector.
 *
 * Every persona row used to carry a hand-written summary sentence, and the sentences did not agree
 * with each other: some named a characteristic, some named a frame length, some named control
 * capability, most named none of it, and no two used the same word for the same idea. A person
 * picking a persona could not answer "does this one take ERG writes?" or "what resistance range
 * will it accept?" without reading the source.
 *
 * A descriptor answers those questions in the same slots, in the same order, for every persona:
 *
 *  1. [protocol]     - the wire protocol and the service/characteristic or frame identifying it.
 *  2. [controlModes] - which control kinds the persona accepts, in the shared
 *                      [SimulatorControlModeLabels.Kind] vocabulary, or "none".
 *  3. [ranges]       - the spans that matter, headed by [rangeKind].
 *  4. [metrics]      - what the persona actually emits, so an absent field is not read as a bug.
 *
 * A slot with nothing true to say is omitted rather than padded; [descriptorLine] is what the UI
 * renders under the persona [title].
 *
 * The values here are *derived from the same sources the runtime uses* - [FtmsMockControlProfile]
 * for FTMS target capability and limits, [FeCEquipmentType] for FE-C equipment facts,
 * [KickrSyntheticModel] for KICKR control limits, [SchwinnX70Ride] for the x70 resistance span -
 * never re-typed. A descriptor that drifts from behaviour is worse than no descriptor.
 */
data class SimulatorPersonaDescriptor(
    val title: String,
    val protocol: String,
    val controlModes: List<String>,
    val rangeKind: SimulatorRangeKind,
    val ranges: List<SimulatorPersonaRange>,
    val metrics: String,
) {
    /** Control kinds as one phrase; [SimulatorControlModeLabels.Kind.NONE] when the list is empty. */
    val controlSummary: String =
        if (controlModes.isEmpty()) {
            SimulatorControlModeLabels.Kind.NONE
        } else {
            controlModes.joinToString(", ")
        }

    /** Fixed-order descriptor line: protocol, control, ranges, metrics. */
    val descriptorLine: String = buildList {
        add(protocol)
        add("Control: $controlSummary")
        if (ranges.isNotEmpty()) {
            add("${rangeKind.heading}: ${ranges.joinToString(", ")}")
        }
        add("Metrics: $metrics")
    }.joinToString(" · ")
}

/** One range slot: the metric it constrains and the span, units included. */
data class SimulatorPersonaRange(val metric: String, val span: String) {
    override fun toString(): String = "$metric $span"
}

/**
 * What a persona's [SimulatorPersonaDescriptor.ranges] describe.
 *
 * The rule is uniform and deliberate: a persona that accepts control writes reports [TARGETS] -
 * what a client may *request* - because that is the number a test client needs. A persona that
 * accepts none reports [RIDE], the span its own generated workout visits, and only where the
 * persona actually declares one. Nothing reports both; mixing the two is how the old summaries
 * came to mean different things in different rows.
 */
enum class SimulatorRangeKind(val heading: String) {
    TARGETS("Targets"),
    RIDE("Ride"),
}

/** Machine names for the FTMS profiles, shared by the persona title and the status card. */
internal fun ftmsMachineLabel(machine: FtmsMachineProfile): String = when (machine) {
    FtmsMachineProfile.INDOOR_BIKE -> "Indoor bike"
    FtmsMachineProfile.TREADMILL -> "Treadmill"
    FtmsMachineProfile.ELLIPTICAL -> "Cross trainer"
    FtmsMachineProfile.INDOOR_ROWER -> "Rower"
    FtmsMachineProfile.STAIR_STEPPER -> "Step climber"
    FtmsMachineProfile.STAIR_CLIMBER -> "Stair climber"
}

/** Normalized selector descriptor for one persona. */
fun SimulatorPersona.describe(): SimulatorPersonaDescriptor = when (this) {
    SimulatorPersona.IndoorBikeSpeedAndPower -> describeIndoorBikeSpeedAndPower()
    SimulatorPersona.StrydFootpod -> describeStrydFootpod()
    is SimulatorPersona.Ftms -> describeFtms(profile)
    is SimulatorPersona.Concept2Pm5 -> describeConcept2(machine, compatibility)
    is SimulatorPersona.KeiserMSeries -> describeKeiserMSeries(profile)
    SimulatorPersona.PrecorChrono -> describePrecorChrono()
    SimulatorPersona.FlywheelSports -> describeFlywheelSports()
    SimulatorPersona.KayakFirstBlue -> SimulatorPersonaDescriptor(
        title = "KayakFirst (Blue) Indoor Paddle",
        protocol = "KayakFirst FFE0/FFE1 text protocol",
        controlModes = emptyList(),
        rangeKind = SimulatorRangeKind.RIDE,
        ranges = listOf(
            SimulatorPersonaRange("Speed", "shared rowing workout"),
            SimulatorPersonaRange("Cadence", "shared rowing workout"),
            SimulatorPersonaRange("Power", "shared rowing workout"),
        ),
        metrics = "elapsed, distance, speed, stroke rate, power, energy",
    )
    SimulatorPersona.KayakFirstOrange -> SimulatorPersonaDescriptor(
        title = "KayakFirst (Orange) Indoor Paddle",
        protocol = "KayakFirst FFE0/FFE1 timing protocol",
        controlModes = emptyList(),
        rangeKind = SimulatorRangeKind.RIDE,
        ranges = listOf(
            SimulatorPersonaRange("Raw timing", "E2 4.2.0 timing MCU"),
            SimulatorPersonaRange("Effort", "shared rowing workout"),
        ),
        metrics = "T_MIN, T_H, T_MAX, T_V, optional auxiliary RPM",
    )
    is SimulatorPersona.LifeFitness -> describeLifeFitness(machine)
    SimulatorPersona.SchwinnX70 -> describeSchwinnX70()
    is SimulatorPersona.FeCBle -> describeFeCBle(this)
    is SimulatorPersona.Kickr -> describeKickr(device)
}

// ─────────────────────────────────────────────────────────────
// Per-protocol descriptors
// ─────────────────────────────────────────────────────────────

private fun describeIndoorBikeSpeedAndPower() = SimulatorPersonaDescriptor(
    title = "Indoor Bike Speed and Power",
    protocol = "CSC 0x1816/0x2A5B + CPS 0x1818/0x2A63 + FTMS 0x1826",
    controlModes = emptyList(),
    rangeKind = SimulatorRangeKind.RIDE,
    ranges = emptyList(),
    metrics = "speed, cadence, power",
)

/**
 * Running footpod.
 *
 * The ride slot is read off the treadmill entry in [FtmsMockProfile] rather than re-typed, for the
 * reason this file exists: a descriptor that drifts from behaviour is worse than no descriptor.
 * Cadence and stride length are absent from the slot deliberately - neither is a range the persona
 * declares, both fall out of speed through `RunningGait`.
 */
private fun describeStrydFootpod(): SimulatorPersonaDescriptor {
    val ride = FtmsMockProfile.forMachine(FtmsMachineProfile.TREADMILL)
    return SimulatorPersonaDescriptor(
        title = "Stryd · Running footpod",
        protocol = "RSC 0x1814/0x2A53 + CPS 0x1818/0x2A63",
        controlModes = emptyList(),
        rangeKind = SimulatorRangeKind.RIDE,
        ranges = buildList {
            ride.speed?.let { add(SimulatorPersonaRange("speed", span(it.min, it.max, "km/h", 1))) }
            add(SimulatorPersonaRange("power", span(ride.power.min, ride.power.max, "W", 0)))
        },
        metrics = "speed, cadence, stride length, distance, power, battery",
    )
}

private fun describeFtms(profile: FtmsMachineProfile): SimulatorPersonaDescriptor {
    val capabilities = FtmsMockControlProfile.forMachine(profile)
    return SimulatorPersonaDescriptor(
        title = "FTMS · ${ftmsMachineLabel(profile)}",
        protocol = "FTMS ${shortUuid(profile.dataCharacteristic)}" +
            if (capabilities.any) " + 0x2AD9 control point" else "",
        controlModes = ftmsControlModes(capabilities),
        rangeKind = SimulatorRangeKind.TARGETS,
        ranges = ftmsTargetRanges(capabilities),
        metrics = when (profile) {
            FtmsMachineProfile.INDOOR_BIKE -> "speed, cadence, power, resistance, distance"
            FtmsMachineProfile.TREADMILL -> "speed, incline, elevation, belt force + power"
            FtmsMachineProfile.ELLIPTICAL -> "3-octet flags, step + stride count, incline, power"
            FtmsMachineProfile.INDOOR_ROWER -> "stroke rate + count, pace, power, no speed field"
            FtmsMachineProfile.STAIR_STEPPER -> "floors + step count, step rate, elevation"
            FtmsMachineProfile.STAIR_CLIMBER -> "floors, step rate, stride count, elevation"
        },
    )
}

private fun describeConcept2(
    machine: Concept2Machine,
    compatibility: Concept2Pm5CompatibilityProfile,
) = SimulatorPersonaDescriptor(
    title = "Concept2 PM5 · ${machine.displayName} · ${compatibility.gattSurface.displayName}",
    protocol = when (compatibility.gattSurface) {
        Concept2Pm5GattSurface.PRE_FTMS ->
            "Concept2 PM5 0x0030 · 0x0031-0x003B + 0x0080 · no 0x0016, no CE060040"
        Concept2Pm5GattSurface.MODERN ->
            "Concept2 PM5 0x0030 · 0x0031-0x003F + 0x0080 · machine type ${machine.wireMachineType}"
    },
    controlModes = emptyList(),
    rangeKind = SimulatorRangeKind.RIDE,
    ranges = emptyList(),
    metrics = "individual + multiplexed status, stroke, split and summary characteristics",
)

private fun describeKeiserMSeries(profile: KeiserMSeriesSimulatorProfile) = SimulatorPersonaDescriptor(
    title = "Keiser · ${profile.displayName}",
    protocol = "Non-connectable 0x0102 advertisements · 5-minute validation loop · Android best effort",
    controlModes = emptyList(),
    rangeKind = SimulatorRangeKind.RIDE,
    ranges = emptyList(),
    metrics = "dropouts, coast, repeats, boundaries, malformed frames and stale recovery",
)

private fun describePrecorChrono() = SimulatorPersonaDescriptor(
    title = "Precor · Spinner Chrono Power",
    protocol = "Proprietary 19-byte 53 59 16 frame",
    controlModes = emptyList(),
    rangeKind = SimulatorRangeKind.RIDE,
    ranges = emptyList(),
    metrics = "speed, cadence, distance, power, energy, heart rate",
)

private fun describeFlywheelSports() = SimulatorPersonaDescriptor(
    title = "Flywheel · Sports bike",
    protocol = "Proprietary Nordic UART-style 34-byte frame · corroborated",
    controlModes = emptyList(),
    rangeKind = SimulatorRangeKind.RIDE,
    ranges = emptyList(),
    metrics = "power and cadence; FitBridge derives speed, average speed and distance",
)

private fun describeLifeFitness(machine: LifeFitnessMachine) = SimulatorPersonaDescriptor(
    title = "Life Fitness · ${machine.displayName}",
    protocol = "FTMS ${shortUuid(machine.measurementUuid)} · setup-gated",
    controlModes = emptyList(),
    rangeKind = SimulatorRangeKind.RIDE,
    ranges = emptyList(),
    metrics = "generated ${machine.displayName.lowercase(Locale.US)} measurements only",
)

/**
 * One persona, not three. The 170/270/570 split advertised the same name, served the same
 * characteristics and emitted the same streams; only the generated ride differed, and varying that
 * by console made one engine speed reach a client as three different numbers.
 */
private fun describeSchwinnX70() = SimulatorPersonaDescriptor(
    title = "Schwinn 170/270 · Bike",
    protocol = "Proprietary 20/14/17-byte frames · init-gated",
    controlModes = emptyList(),
    rangeKind = SimulatorRangeKind.RIDE,
    ranges = listOf(
        SimulatorPersonaRange(
            "resistance",
            span(SchwinnX70Ride.RESISTANCE_FLOOR, SchwinnX70Ride.RESISTANCE_CEILING),
        ),
    ),
    metrics = "engine metrics, speed published in mph",
)

/**
 * FE-C over BLE.
 *
 * The control slot names the wire capability, which is finer-grained than the runtime "Control
 * mode" line can show: FE-C distinguishes basic resistance (page 48) from target power (page 49),
 * but [SimulatorControlModeLabels] has no ANT+ Erg bucket, so at run time both read as
 * [SimulatorControlModeLabels.ANT_DIRECT]. The descriptor states what the persona accepts; the
 * status card states what a client last wrote.
 *
 * The Incyclist CPS overlay is deliberately absent here - it is implicit to the equipment type
 * (`FeCEquipmentType.includesCpsOverlay`), never user-selectable, and never described in the UI.
 */
private fun describeFeCBle(persona: SimulatorPersona.FeCBle): SimulatorPersonaDescriptor {
    val equipment = persona.equipmentType
    val generic = persona.identity == FeCBleSimulatorIdentity.GENERIC
    val ride = equipment.workoutRange
    return SimulatorPersonaDescriptor(
        title = if (generic) {
            "FE-C over BLE · ${equipment.displayName}"
        } else {
            "${persona.identity.displayName} · FE-C over BLE"
        },
        protocol = buildString {
            append(if (persona.controlEnabled) "ANT+ FE-C FEC1/FEC2/FEC3" else "ANT+ FE-C FEC1/FEC2")
            append(" · pages 16/17/18/${feCSpecificPages(equipment)}/81")
        },
        controlModes = if (persona.controlEnabled) {
            listOf(
                SimulatorControlModeLabels.Kind.DIRECT,
                SimulatorControlModeLabels.Kind.ERG,
                SimulatorControlModeLabels.Kind.SIM,
            )
        } else {
            emptyList()
        },
        rangeKind = if (persona.controlEnabled) SimulatorRangeKind.TARGETS else SimulatorRangeKind.RIDE,
        ranges = if (persona.controlEnabled) {
            listOf(
                SimulatorPersonaRange(
                    "power",
                    span(
                        FeCBleSyntheticModel.MIN_CONTROLLED_POWER_WATTS,
                        FeCBleSyntheticModel.MAX_CONTROLLED_POWER_WATTS,
                        "W",
                        decimals = 0,
                    ),
                ),
                SimulatorPersonaRange("resistance", span(0.0, 100.0, "%", decimals = 0)),
            )
        } else {
            buildList {
                add(SimulatorPersonaRange("speed", span(ride.speedKph, "km/h", decimals = 1)))
                ride.powerWatts?.let { add(SimulatorPersonaRange("power", span(it, "W"))) }
                ride.inclinePercent?.let {
                    add(SimulatorPersonaRange("incline", span(it, "%", decimals = 1)))
                }
                ride.resistancePercent?.let {
                    add(SimulatorPersonaRange("resistance", span(it, "%", decimals = 0)))
                }
            }
        },
        metrics = if (generic) {
            "page 16 + page ${feCSpecificPages(equipment)} telemetry, speed, distance, heart rate"
        } else {
            "capture-grounded advertising + GATT identity, synthetic FE-C telemetry"
        },
    )
}

/**
 * KICKR / Zwift interoperability personas (debug builds only).
 *
 * The provenance words in [SimulatorPersonaDescriptor.protocol] - HCI, LOG, ADVERTISING HCI - are
 * load-bearing and asserted by `KickrSimulatorUiContractTest`: they say which parts of each
 * persona are capture-backed rather than reconstructed, and softening them would be a claim the
 * evidence does not support.
 *
 * All four implement FTMS target setting for real (Kinomap and MyWhoosh captures show third-party
 * apps driving them through 0x2AD9), so all four list the full control vocabulary. Control is
 * still negotiated per route at run time (`KickrControlRoute`).
 */
private fun describeKickr(device: KickrSimulatorPersona) = SimulatorPersonaDescriptor(
    title = device.displayLabel,
    protocol = when (device) {
        KickrSimulatorPersona.V5_STANDARD_LEGACY ->
            "LOG · standard CPS/FTMS + legacy Wahoo control point"
        KickrSimulatorPersona.CORE_ORIGINAL_ZWIFT ->
            "HCI capture-backed · standard/legacy + original 02 02 proprietary controls"
        KickrSimulatorPersona.CORE2_FC82_PROVISIONAL ->
            "ADVERTISING HCI · CPS + FTMS advertisement · capture-log-aligned GATT + FC82"
        KickrSimulatorPersona.ZWIFT_HUB_CAPTURED ->
            "LOG · captured Zwift Hub CPS/FTMS + support GATT · original proprietary service"
    },
    controlModes = listOf(
        SimulatorControlModeLabels.Kind.DIRECT,
        SimulatorControlModeLabels.Kind.ERG,
        SimulatorControlModeLabels.Kind.SIM,
    ),
    rangeKind = SimulatorRangeKind.TARGETS,
    ranges = listOf(
        SimulatorPersonaRange(
            "power",
            span(KickrSyntheticModel.MIN_POWER_WATTS, KickrSyntheticModel.MAX_POWER_WATTS, "W"),
        ),
        SimulatorPersonaRange(
            "resistance",
            span(
                KickrSyntheticModel.MIN_RESISTANCE,
                KickrSyntheticModel.MAX_RESISTANCE,
                decimals = 0,
            ),
        ),
    ),
    metrics = "power, cadence, speed, distance, heart rate, virtual gearing",
)

// ─────────────────────────────────────────────────────────────
// Derivations and formatting
// ─────────────────────────────────────────────────────────────

/**
 * The equipment-specific data page(s) this type's rotation actually emits alongside page 16.
 *
 * Mirrors `FeCBleTelemetryScheduler.encodeSpecificPageFrame`: only the two bike-shaped personas
 * carry the trainer pages, and each other type has exactly one page of its own. Saying "page 25"
 * for all of them - which the old summaries did - described a frame a treadmill never sends, and
 * cadence and power live *only* on these pages, so naming the wrong one hides where to look.
 */
internal fun feCSpecificPages(equipmentType: FeCEquipmentType): String = when (equipmentType) {
    FeCEquipmentType.TRAINER, FeCEquipmentType.STATIONARY_BIKE -> "25/26"
    FeCEquipmentType.TREADMILL -> "19"
    FeCEquipmentType.ELLIPTICAL -> "20"
    FeCEquipmentType.ROWER -> "22"
    FeCEquipmentType.CLIMBER -> "23"
    FeCEquipmentType.NORDIC_SKIER -> "24"
}

/** Control kinds implied by an FTMS [TargetCapabilities], in the shared vocabulary. */
internal fun ftmsControlModes(capabilities: TargetCapabilities): List<String> = buildList {
    val kind = SimulatorControlModeLabels.Kind
    if (
        capabilities.supportsSpeed ||
        capabilities.supportsInclination ||
        capabilities.supportsResistance
    ) {
        add(kind.DIRECT)
    }
    if (capabilities.supportsPower) add(kind.ERG)
    if (capabilities.indoorBikeSimulation) add(kind.SIM)
}

/**
 * Advertised target spans in the public units the engine stores them in.
 *
 * The divisors mirror `FtmsMockEngine.publicOf`: speed is 0.01 km/h on the wire, inclination and
 * resistance 0.1 units, power whole watts. A range that fails its own validity check is omitted
 * rather than shown - the same test `TargetCapabilities` applies before advertising it.
 */
internal fun ftmsTargetRanges(capabilities: TargetCapabilities): List<SimulatorPersonaRange> =
    buildList {
        if (capabilities.supportsSpeed) {
            capabilities.speedRange?.let {
                add(SimulatorPersonaRange("speed", span(it.minimum / 100.0, it.maximum / 100.0, "km/h", 1)))
            }
        }
        if (capabilities.supportsInclination) {
            capabilities.inclinationRange?.let {
                add(SimulatorPersonaRange("incline", span(it.minimum / 10.0, it.maximum / 10.0, "%", 1)))
            }
        }
        if (capabilities.supportsResistance) {
            capabilities.resistanceRange?.let {
                add(SimulatorPersonaRange("resistance", span(it.minimum / 10.0, it.maximum / 10.0, null, 1)))
            }
        }
        if (capabilities.supportsPower) {
            capabilities.powerRange?.let {
                add(SimulatorPersonaRange("power", span(it.minimum, it.maximum, "W")))
            }
        }
    }

/** `0x2AD2` from an assigned-number UUID, for the protocol slot. */
internal fun shortUuid(uuid: UUID): String =
    "0x" + uuid.toString().substring(4, 8).uppercase(Locale.US)

private fun span(minimum: Int, maximum: Int, unit: String? = null): String =
    joinSpan(minimum.toString(), maximum.toString(), minimum < 0, unit)

private fun span(
    minimum: Double,
    maximum: Double,
    unit: String? = null,
    decimals: Int = 1,
): String = joinSpan(
    String.format(Locale.US, "%.${decimals}f", minimum),
    String.format(Locale.US, "%.${decimals}f", maximum),
    minimum < 0.0,
    unit,
)

private fun span(range: IntRange, unit: String? = null): String =
    span(range.first, range.last, unit)

private fun span(
    range: ClosedFloatingPointRange<Double>,
    unit: String? = null,
    decimals: Int = 1,
): String = span(range.start, range.endInclusive, unit, decimals)

/**
 * An en dash joins an ordinary span; a negative lower bound gets the word instead, because
 * `-10.0–30.0` reads as one long dash run and nobody should have to parse that.
 */
private fun joinSpan(minimum: String, maximum: String, negative: Boolean, unit: String?): String {
    val joined = if (negative) "$minimum to $maximum" else "$minimum–$maximum"
    return if (unit == null) joined else "$joined $unit"
}
