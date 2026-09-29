package com.gmail.absolutebollockscreations.gattlibrary.core

/**
 * Process-wide logging gate for the BLE stack (LIB-2/S2; replaces the S1 constant mirror).
 *
 * The host app sets these once at startup from its own debug settings. FitBridge does it in
 * `FitBridgeApp.onCreate` from `Debugging`/`DebugLevel`. Logging is process-wide, like Timber's
 * planted trees, so this is not part of the per-engine [BleConfig]. Defaults equal FitBridge's
 * current values so code that runs without the Application (unit tests) behaves as before.
 *
 * Levels: 0 errors, 1 +warnings, 3 +info, 4 debug, 5 extended (high-volume payload logging).
 */
object BleDebug {
    @Volatile
    var enabled: Boolean = true

    @Volatile
    var level: Int = 5
}
