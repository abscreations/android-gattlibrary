// =========================================
// File: bluetoothIds.kt (FitBridge)
// Shared between BtEngine (consumer) and btService (provider)
// =========================================


package com.gmail.absolutebollockscreations.gattlibrary.central

import android.os.ParcelUuid
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
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids

/**
 * Android-side wrappers for the identifiers gatt-core owns. Every UUID, opcode and vocabulary constant
 * moved to gatt-core (`core.ftms` in LIB-19a; `core.ids` in LIB-19b: SigUuids, Concept2Uuids,
 * PrecorUuids, SchwinnX70Uuids, LifeFitnessUuids, WahooUuids, ZwiftUuids, NordicUuids,
 * FlywheelUuids, KayakFirstUuids, FecBleUuids). What remains here needs Android types
 * (`ParcelUuid` scan-filter values); the capture contract and retention policy live in `core.capture` (LIB-19d).
 *
 * ──────────────────────────────────────────────────────────────────────────────
 * ORGANIZATION
 * ──────────────────────────────────────────────────────────────────────────────
 *  • [BluetoothIds]         → ParcelUuid scan-filter values (central only)
 *  (Server-owned service enums, device names and the FTMS Service Data builder moved to
 *  `peripheral.BtServiceProfiles` in LIB-19h.)
 *
 * Engine and service code should import from here rather than redeclaring IDs.
 */

// ============================================================================
// 1) BluetoothIds — ParcelUuid wrappers
// ============================================================================
internal object BluetoothIds {


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

    val GLUCOSE_SERVICE_PARCEL: ParcelUuid = ParcelUuid(SigUuids.UUID_GLUCOSE_SERVICE)
}
