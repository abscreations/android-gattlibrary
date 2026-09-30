// =========================================
// File: bluetoothIds.kt (FitBridge)
// Shared between BtEngine (consumer) and btService (provider)
// =========================================


package com.gmail.absolutebollockscreations.gattlibrary.central

import android.os.ParcelUuid
import com.gmail.absolutebollockscreations.gattlibrary.core.ActivityType
import java.util.UUID
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.Concept2Uuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.FecBleUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.FlywheelUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.KayakFirstUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.LifeFitnessUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.NordicUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.PrecorUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SchwinnX70Uuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.WahooUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.ZwiftUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineType
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids

/**
 * Android-side wrappers for the identifiers gatt-core owns. Every UUID, opcode and vocabulary constant
 * moved to gatt-core (`core.ftms` in LIB-19a; `core.ids` in LIB-19b: SigUuids, Concept2Uuids,
 * PrecorUuids, SchwinnX70Uuids, LifeFitnessUuids, WahooUuids, ZwiftUuids, NordicUuids,
 * FlywheelUuids, KayakFirstUuids, FecBleUuids). What remains here needs Android types
 * (`ParcelUuid` scan-filter values) or the capture policy (diagnostically retained characteristics).
 *
 * ──────────────────────────────────────────────────────────────────────────────
 * ORGANIZATION
 * ──────────────────────────────────────────────────────────────────────────────
 *  • [BluetoothIds]         → ParcelUuid scan-filter values and the diagnostic retention list
 *  • [BtServiceProfiles]    → Ancillary service-level enums and constants
 *                              (service modes, device names,
 *                               max clients, default notify interval, FMF presets)
 *
 * Engine and service code should import from here rather than redeclaring IDs.
 */

// ============================================================================
// 1) BluetoothIds — ParcelUuid wrappers and capture retention list
// ============================================================================
object BluetoothIds {


    // ─────────────────────────────────────────────────────────────
    // FTMS (Fitness Machine Service 0x1826)
    // ─────────────────────────────────────────────────────────────
    /** FTMS service, from core so central, peripheral and simulator share one definition (LIB-19a). */
    val FTMS_SERVICE_PARCEL: ParcelUuid = ParcelUuid(FtmsUuids.UUID_FTMS_SERVICE) // for scan filter
    val HRM_SERVICE_PARCEL: ParcelUuid = ParcelUuid(SigUuids.UUID_HRM_SERVICE)
    val CSC_SERVICE_PARCEL: ParcelUuid = ParcelUuid(SigUuids.UUID_CSC_SERVICE)
    val RSC_SERVICE_PARCEL: ParcelUuid = ParcelUuid(SigUuids.UUID_RSC_SERVICE)
    val POWER_SERVICE_PARCEL: ParcelUuid = ParcelUuid(SigUuids.UUID_POWER_SERVICE)
    val CYCLING_POWER_SERVICE_PARCEL: ParcelUuid = POWER_SERVICE_PARCEL
    val C2_DISCOVERY_SERVICE_PARCEL: ParcelUuid = ParcelUuid(Concept2Uuids.UUID_C2_DISCOVERY_SERVICE)
    val C2_ERG_SERVICE_PARCEL: ParcelUuid  = ParcelUuid(Concept2Uuids.UUID_C2_ERG_SERVICE)
    val PRECOR_SERVICE_PARCEL: ParcelUuid = ParcelUuid(PrecorUuids.UUID_PRECOR_SERVICE)

    /**
     * Body metrics that are deliberately **not** redaction-registered (D102).
     *
     * Weight and height are personal data, and until 2026-09-08 they were redacted with the rest
     * of the profile. Two things changed that. They are load-bearing inputs to several vendor
     * power and energy models rather than identifiers, so a capture that hides them cannot be used
     * to diagnose a wrong power figure — the single most common thing these captures are exported
     * to investigate. And a capture is only ever produced by a user who armed it and only ever
     * leaves the device through a share sheet the user drives, so disclosure is consented rather
     * than incidental.
     *
     * The identifying fields are a different question and stay registered: a name, an email
     * address, an age or a gender diagnoses nothing, so retaining them would buy no troubleshooting
     * value at real cost. Keep that asymmetry when adding to this list — the test is whether the
     * value would appear in a bug report's *explanation*, not merely in its data.
     */
    val DIAGNOSTICALLY_RETAINED_CHARACTERISTICS: List<UUID> = listOf(
        SigUuids.UUID_USER_WEIGHT,
        SigUuids.UUID_USER_HEIGHT,
    )
    val GLUCOSE_SERVICE_PARCEL: ParcelUuid = ParcelUuid(SigUuids.UUID_GLUCOSE_SERVICE)
}

// ============================================================================
// 3) BtServiceProfiles — ancillary service-level enums / constants
//    Shared across BtEngine + btService; NOT Bluetooth IDs themselves
// ============================================================================
object BtServiceProfiles {

    // ─────────────────────────────────────────────────────────────
    // Service modes (two-mode model)
    // ─────────────────────────────────────────────────────────────
    enum class ServiceMode {
        /** Local provider exposes FTMS (0x1826) with FTMS data chars. */
        FTMS,

        /** Local provider exposes CSC (0x1816) + CPS (0x1818) together. */
        CSC_CPS,
    }

    // ─────────────────────────────────────────────────────────────
    // CSC+CPS device persona
    // ─────────────────────────────────────────────────────────────
    enum class CscCpsProfile {
        BIKE,
    }

    // ─────────────────────────────────────────────────────────────
    // Device names (advertised local name)
    // ─────────────────────────────────────────────────────────────
    /**
     * Prefix for every advertised local name, following the same convention as
     * TrackMyIndoorWorkout's "TMIW-".
     *
     * The point is attribution on a crowded scan list: a bare "Bike" or
     * "Treadmill" is indistinguishable from a real machine, and because Android
     * publishes the *global adapter name* in the advertisement, an unprefixed
     * persona is easily confused with the phone itself. "FB-" makes it obvious
     * which entry is this bridge.
     *
     * Three characters since LIB-18e (was "FBRG-"): the two octets saved go to the bridged
     * machine's own name, which the bridge now advertises after the prefix
     * ([FtmsAdvertisingPlan.bridgeLocalName] in peripheral). The names below are the fallback
     * when no machine name is known, and the names simulator standard personas use.
     */
    const val DEVICE_NAME_PREFIX: String = "FB-"

    /**
     * Prefix used before LIB-18e. Never advertised any more, but a phone left renamed by a crash
     * under an older build still carries it, so it stays recognised as a session name.
     */
    const val LEGACY_DEVICE_NAME_PREFIX: String = "FBRG-"

    const val DEVICE_NAME_FTMS_BIKE: String       = DEVICE_NAME_PREFIX + "Bike"
    const val DEVICE_NAME_FTMS_ELLIPTICAL: String = DEVICE_NAME_PREFIX + "Elliptical"
    const val DEVICE_NAME_FTMS_ROWER: String      = DEVICE_NAME_PREFIX + "Rower"
    const val DEVICE_NAME_FTMS_CLIMBER: String    = DEVICE_NAME_PREFIX + "Climber"
    const val DEVICE_NAME_FTMS_STEPPER: String    = DEVICE_NAME_PREFIX + "Stepper"
    const val DEVICE_NAME_FTMS_TREAD: String      = DEVICE_NAME_PREFIX + "Treadmill"
    /**
     * The native CSC + Cycling Power persona names itself after the two services it publishes.
     *
     * It used to alias [DEVICE_NAME_FTMS_BIKE], which put two different personas on the scan list
     * under one name: the FTMS indoor bike and the native-sensor bike both appeared as
     * "FBRG-Bike" (now "FB-Bike"), and the only way to tell which one you had connected to was to discover its
     * services. Naming it for its protocol is the same convention the descriptor line already
     * uses, and at seven characters it is *shorter* than the name [FtmsAdvertisingPlan] budgets
     * for this persona, so the advertisement gets smaller rather than larger.
     *
     * It carries no [DEVICE_NAME_PREFIX], so [AdapterNameCoordinator.isSessionName] lists it
     * explicitly - otherwise a crash mid-session would strand the user's phone permanently named
     * "CSC/CPS".
     */
    const val DEVICE_NAME_CSC_CPS: String         = "CSC/CPS"

    /**
     * Advertised local name for an FTMS persona.
     *
     * ADVERTISING BUDGET — the reason these names cannot grow. A legacy
     * advertisement is 31 octets and the name costs (2 + length):
     *
     *     3 Flags + (2 + name) + (2 + 2 per 16-bit UUID) + 7 Service Data
     *
     * A non-bike persona has 15 octets for its name (the old "FBRG-Elliptical"
     * landed on exactly 31/31); the native-sensor bike has 11. Any longer name,
     * or a second advertised UUID on a non-bike persona, overflows and Android
     * rejects the whole advertisement asynchronously. Bridged machine names are
     * truncated to this budget by [FtmsAdvertisingPlan.bridgeLocalName].
     * [FtmsAdvertisingPlan] computes this before touching the radio so the
     * failure is deterministic; FtmsAdvertisingPlanTest asserts the budget for
     * every persona.
     *
     * The indoor bike is the only persona that advertises extra UUIDs (CSC and
     * Cycling Power), and "FB-Bike" is short enough to absorb them at 27/31.
     */
    fun deviceNameFor(machine: FtmsMachineProfile): String = when (machine) {
        FtmsMachineProfile.INDOOR_BIKE   -> DEVICE_NAME_FTMS_BIKE
        FtmsMachineProfile.ELLIPTICAL    -> DEVICE_NAME_FTMS_ELLIPTICAL
        FtmsMachineProfile.INDOOR_ROWER  -> DEVICE_NAME_FTMS_ROWER
        FtmsMachineProfile.STAIR_CLIMBER -> DEVICE_NAME_FTMS_CLIMBER
        FtmsMachineProfile.STAIR_STEPPER -> DEVICE_NAME_FTMS_STEPPER
        FtmsMachineProfile.TREADMILL     -> DEVICE_NAME_FTMS_TREAD
    }

    // ─────────────────────────────────────────────────────────────
    // FTMS Service Data AD structure (FTMS v1.0 §3.1)
    // ─────────────────────────────────────────────────────────────
    //
    // On air the full structure is six octets:
    //     [len][0x16][UUID lo][UUID hi][Flags][Type lo][Type hi]
    // Android's AdvertiseData.Builder.addServiceData(ParcelUuid, ByteArray)
    // emits the length byte, the 0x16 AD type and the 16-bit UUID itself, so the
    // payload we supply is only the trailing THREE octets: Flags + Machine Type.
    //
    // Flags field (Table 3.2): bit 0 = Fitness Machine Available, bits 1-7 RFU.
    /**
     * Build the 3-octet FTMS Service Data payload for [machine].
     *
     * @param available value of the Fitness Machine Available flag. Pass false to
     *   advertise the service while signalling that no machine is usable yet.
     */
    fun ftmsServiceDataFor(machine: FtmsMachineProfile, available: Boolean = true): ByteArray {
        val type = FtmsMachineType.fitnessMachineTypeFor(machine)
        val flags = if (available) FtmsMachineType.FM_FLAG_MACHINE_AVAILABLE else 0
        return byteArrayOf(
            (flags and 0xFF).toByte(),
            (type and 0xFF).toByte(),
            ((type ushr 8) and 0xFF).toByte(),
        )
    }

    /**
     * Decode the optimistic activity hint carried in FTMS Service Data.
     *
     * Android returns only the three bytes following the 0x1826 UUID:
     * Flags + little-endian Fitness Machine Type. If a device sets multiple
     * known type bits, use the same priority as connected GATT discovery.
     */
    fun activityTypeFromFtmsServiceData(payload: ByteArray?): ActivityType? {
        if (payload == null || payload.size < 3) return null
        val machineTypes = (payload[1].toInt() and 0xFF) or
            ((payload[2].toInt() and 0xFF) shl 8)
        return when {
            machineTypes and FtmsMachineType.FM_TYPE_TREADMILL != 0 -> ActivityType.TREADMILL
            machineTypes and FtmsMachineType.FM_TYPE_ROWER != 0 -> ActivityType.ROWING
            machineTypes and FtmsMachineType.FM_TYPE_INDOOR_BIKE != 0 -> ActivityType.BIKING
            machineTypes and FtmsMachineType.FM_TYPE_CROSS_TRAINER != 0 -> ActivityType.ELLIPTICAL
            machineTypes and FtmsMachineType.FM_TYPE_STEP_CLIMBER != 0 -> ActivityType.STAIRSTEPPER
            machineTypes and FtmsMachineType.FM_TYPE_STAIR_CLIMBER != 0 -> ActivityType.STAIRCLIMBER
            else -> null
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Connection / timing
    // ─────────────────────────────────────────────────────────────
    /**
     * Max number of concurrent GATT clients we will serve.
     * This is non-spec (spec doesn’t limit us) but keeps memory and per-client state sane.
     */
    const val MAX_CLIENTS: Int = 3

    /**
     * Default notify interval used by BtServiceNotifier, in ms.
     * 1 Hz is good enough for most FTMS consumers (Zwift, 3rd party apps, etc.).
     */
    const val DEFAULT_NOTIFY_INTERVAL_MS: Long = 333L

    // ─────────────────────────────────────────────────────────────
    // FTMS Feature presets (0x2ACC FMF bitfields)
    // ─────────────────────────────────────────────────────────────
    //
    // Bluetooth SIG spec — Fitness Machine Feature field (uint32, little endian):
    //      bit  0: Average Speed Supported
    //      bit  1: Cadence Supported
    //      bit  2: Total Distance Supported
    //      bit  3: Inclination Supported
    //      bit  4: Elevation Gain Supported
    //      bit  5: Pace Supported
    //      bit  6: Step Count Supported
    //      bit  7: Resistance Level Supported
    //      bit  8: Stride Count Supported
    //      bit  9: Expended Energy Supported
    //      bit 10: Heart Rate Measurement Supported
    //      bit 11: Metabolic Equivalent Supported
    //      bit 12: Elapsed Time Supported
    //      bit 13: Remaining Time Supported
    //      bit 14: Power Measurement Supported
    //      bit 15: Force on Belt and Power Output Supported
    //      bit 16: User Data Retention Supported
    //
    // IMPORTANT: this field does NOT carry machine type. An earlier revision of
    // this file encoded "Treadmill / Cross Trainer / Stepper / Rower / Indoor Bike"
    // into bits 8..12, which actually mean Stride Count, Expended Energy, Heart Rate,
    // Metabolic Equivalent and Elapsed Time. Machine type belongs in the FTMS
    // Service Data AD structure of the advertisement (Flags uint8 + Fitness Machine
    // Type uint16), which this build does not yet emit.
    //
    //   Target Setting Feature (uint32, little endian):
    //      bit  0: Speed Target Setting          bit  8: Targeted Distance
    //      bit  1: Inclination Target Setting    bit  9: Targeted Training Time
    //      bit  2: Resistance Target Setting     bit 10..12: Targeted Time in HR Zones
    //      bit  3: Power Target Setting          bit 13: Indoor Bike Simulation Params
    //      bit  4: Heart Rate Target Setting     bit 14: Wheel Circumference
    //      bit  5: Targeted Expended Energy      bit 15: Spin Down Control
    //      bit  6: Targeted Step Number          bit 16: Targeted Cadence
    //      bit  7: Targeted Stride Number
    //    TSF is built at runtime in BtServiceAdvertiser.buildFitnessMachineFeature()
    //    from the upstream machine's real capabilities.
    //
    // Each FMF map below is derived from the fields the corresponding
    // BtService*Data encoder actually emits. Keep them in sync when an encoder
    // gains or loses a field, otherwise a strict client may discard data we send.

    private infix fun Int.b(bit: Int): Int = this or (1 shl bit)

    // Fields every encoder emits. Total distance is NOT here: the Stair Climber
    // (0x2AD0) and Step Climber (0x2ACF) data characteristics have no distance
    // field at all, so those personas must not claim bit 2.
    private val BASE_FMF: Int =
        0
            .b(9)   // expended energy
            .b(10)  // heart rate measurement
            .b(11)  // metabolic equivalent
            .b(12)  // elapsed time

    /** TREADMILL — see BtServiceTreadmillData.build(). */
    val FMF_TREADMILL: Int =
        BASE_FMF
            .b(0)   // average speed
            .b(2)   // total distance
            .b(3)   // inclination (incl + ramp angle field)
            .b(4)   // elevation gain
            .b(5)   // pace (instantaneous + average)
            .b(15)  // force on belt and power output

    /** INDOOR BIKE — see BtServiceBikeData.build(). */
    val FMF_INDOOR_BIKE: Int =
        BASE_FMF
            .b(0)   // average speed
            .b(1)   // cadence (instantaneous + average)
            .b(2)   // total distance
            .b(7)   // resistance level
            .b(14)  // power measurement (instantaneous + average)

    /** ELLIPTICAL / CROSS TRAINER — see BtServiceEllipticalData.build(). */
    val FMF_ELLIPTICAL: Int =
        BASE_FMF
            .b(0)   // average speed
            .b(2)   // total distance
            .b(3)   // inclination (incl + ramp angle field)
            .b(4)   // elevation gain
            .b(6)   // step count
            .b(7)   // resistance level
            .b(8)   // stride count
            .b(14)  // power measurement (instantaneous + average)

    /** INDOOR ROWER — see BtServiceRowerData.build(). */
    val FMF_ROWER: Int =
        BASE_FMF
            .b(1)   // cadence (stroke rate, instantaneous + average)
            .b(2)   // total distance
            .b(5)   // pace (instantaneous + average)
            .b(7)   // resistance level
            .b(14)  // power measurement (instantaneous + average)

    /**
     * STAIR CLIMBER (0x2AD0) — see BtServiceStairClimberData.build().
     *
     * Note: Step per Minute and Average Step Rate map to **Step Count Supported
     * (bit 6)**, not Cadence (bit 1) — see FTMS v1.0 Table 4.8. Only Indoor Bike
     * and Rower use the Cadence bit.
     */
    val FMF_STAIR_CLIMBER: Int =
        BASE_FMF
            .b(4)   // elevation gain
            .b(6)   // step count (also covers step rate / average step rate)
            .b(8)   // stride count

    /**
     * STAIR STEPPER / Step Climber (0x2ACF) — see BtServiceStairStepper.build().
     *
     * Same Step Count mapping as the Stair Climber — see FTMS v1.0 Table 4.7.
     */
    val FMF_STAIR_STEPPER: Int =
        BASE_FMF
            .b(4)   // elevation gain
            .b(6)   // step count (also covers step rate / average step rate)

    /**
     * Empty Target Setting Features word — the starting value that
     * [BtServiceAdvertiser.buildFitnessMachineFeature] ORs the upstream machine's
     * real control capabilities into. It is not the final TSF value.
     */
    const val TSF_NONE: Int = 0x0000_0000

    /**
     * Helper to pick FMF bitmap by FTMS machine persona.
     */
    fun fmfFor(machine: FtmsMachineProfile): Int = when (machine) {
        FtmsMachineProfile.INDOOR_BIKE   -> FMF_INDOOR_BIKE
        FtmsMachineProfile.ELLIPTICAL    -> FMF_ELLIPTICAL
        FtmsMachineProfile.INDOOR_ROWER  -> FMF_ROWER
        FtmsMachineProfile.STAIR_CLIMBER -> FMF_STAIR_CLIMBER
        FtmsMachineProfile.STAIR_STEPPER -> FMF_STAIR_STEPPER
        FtmsMachineProfile.TREADMILL     -> FMF_TREADMILL
    }

    // ─────────────────────────────────────────────────────────────
    // Activity / profile / UUID glue (shared engine + service)
    // ─────────────────────────────────────────────────────────────

    /**
     * Map ActivityType (app-facing) to an FTMS machine persona used by
     * the provider side.
     */
    fun ftmsProfileForActivity(type: ActivityType): FtmsMachineProfile? = when (type) {
        ActivityType.TREADMILL    -> FtmsMachineProfile.TREADMILL
        ActivityType.ELLIPTICAL   -> FtmsMachineProfile.ELLIPTICAL
        ActivityType.ROWING       -> FtmsMachineProfile.INDOOR_ROWER
        ActivityType.STAIRCLIMBER -> FtmsMachineProfile.STAIR_CLIMBER
        ActivityType.STAIRSTEPPER -> FtmsMachineProfile.STAIR_STEPPER
        ActivityType.BIKING       -> FtmsMachineProfile.INDOOR_BIKE
        else -> null
    }

    /**
     * Convenience: ActivityType → FTMS data characteristic UUID.
     */
    fun dataUuidForActivity(type: ActivityType): UUID? =
        ftmsProfileForActivity(type)?.let { FtmsUuids.dataUuidForProfile(it) }
}
