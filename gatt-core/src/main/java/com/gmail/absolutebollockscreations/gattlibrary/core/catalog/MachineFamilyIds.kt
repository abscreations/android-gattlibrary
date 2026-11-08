package com.gmail.absolutebollockscreations.gattlibrary.core.catalog

import com.gmail.absolutebollockscreations.gattlibrary.core.GattLibraryInternalApi

/**
 * Stable identifiers of the machine/sensor families the library recognizes. They are the candidate IDs the
 * central matcher produces (`sig.*` for Bluetooth SIG profiles, `vendor.*` and `kayak.first` for proprietary
 * families) and the keys any future family catalog is indexed by.
 *
 * [LIFE_FITNESS] is reserved: no matcher produces it yet (the family is currently reached through its setup
 * characteristics, not advertisement matching).
 */
@GattLibraryInternalApi
public object MachineFamilyIds {
    public const val MAXPRO: String = "vendor.maxpro"
    public const val SIG_FTMS: String = "sig.ftms"
    public const val SIG_HRM: String = "sig.hrm"
    public const val SIG_CSC: String = "sig.csc"
    public const val SIG_RSC: String = "sig.rsc"
    public const val SIG_CPS: String = "sig.cps"
    public const val SIG_GLUCOSE: String = "sig.glucose"
    public const val CONCEPT2_PM: String = "vendor.concept2.pm"
    public const val FEC_BLE: String = "vendor.fec.ble"
    public const val PRECOR_CHRONO: String = "vendor.precor.chrono"
    public const val SCHWINN_X70: String = "vendor.schwinn.x70"
    public const val WAHOO_KICKR_LEGACY: String = "vendor.wahoo.kickr.legacy"
    public const val WAHOO_RIDEON_ORIGINAL: String = "vendor.wahoo.rideon.original"
    public const val WAHOO_RIDEON_CORE2: String = "vendor.wahoo.rideon.core2"
    public const val KEISER_M_SERIES: String = "vendor.keiser.m"
    public const val FLYWHEEL_SPORTS: String = "vendor.flywheel.sports"
    public const val KAYAK_FIRST: String = "kayak.first"
    public const val LIFE_FITNESS: String = "vendor.lifefitness"
}
