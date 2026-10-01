/**
 * btEngine.utils.kt — shared utility helpers for BtEngine / BtEngineState
 *
 * PURPOSE
 *  - Centralize small, engine-specific helpers that are used across the Bluetooth
 *    engine implementation without bloating BtEngine / BtEngineState themselves.
 *
 * FEATURES
 *  - Permissions / environment:
 *      • hasConnectPermission() → checks BLUETOOTH_CONNECT via ContextCompat.
 *      • isBluetoothReady()     → true when the adapter exists and is enabled.
 *
 *  - Misc utilities:
 *      • nowMs()                → System.currentTimeMillis() wrapper.
 *      • ByteArray.toHex()      → human-readable hex dump for logging.
 *
 *  Unit conversion and lap math are not here: central is canonical (km/h, min/km, metres)
 *  and the UI converts for display; lap values are derived in MetricFusion.
 *
 * NOTES
 *  - All helpers are internal and operate on an existing BtEngine / BtEngineState
 *    instance; they do not own any state themselves.
 */

package com.gmail.absolutebollockscreations.gattlibrary.central

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/* ───────────────────────────── permissions ───────────────────────────── */

// BLUETOOTH_CONNECT is API 31+. Before that, BLUETOOTH is an install-time
// permission declared in the manifest and therefore always held; checking the
// API 31 string on an older device returns DENIED and disables the caller.
internal fun BtEngine.hasConnectPermission(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

/* ───────────────────────────── misc helpers ───────────────────────────── */

internal fun nowMs(): Long = System.currentTimeMillis()

internal fun ByteArray.toHex(): String =
    joinToString(separator = " ") { b -> "%02X".format(b) }

internal fun BtEngine.isBluetoothReady(): Boolean = (adapter?.isEnabled == true)
