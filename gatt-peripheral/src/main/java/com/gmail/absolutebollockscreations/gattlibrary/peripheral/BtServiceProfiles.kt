package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineType

/**
 * Server-owned service-level enums and constants: service modes, the advertised local names, the
 * FTMS Service Data payload builder and the client cap. Moved from central's `BtServiceProfiles`
 * in LIB-19h so the server side never imports central. The central-side reader of that payload is
 * `central.FtmsServiceDataParser`; the two are independent by design (L23: no shared parsers or
 * encoders). The dead FMF/TSF presets, `ftmsProfileForActivity`, `dataUuidForActivity` and
 * `DEFAULT_NOTIFY_INTERVAL_MS` were deleted: nothing called them (peripheral's `FtmsProfileSpec`
 * owns the real Feature words).
 */
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
     * Max number of concurrent GATT clients we will serve.
     * This is non-spec (spec doesn’t limit us) but keeps memory and per-client state sane.
     */
    const val MAX_CLIENTS: Int = 3
}
