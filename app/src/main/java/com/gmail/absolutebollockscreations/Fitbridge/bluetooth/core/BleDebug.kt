package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.core

/*
 * Logging gates for the BLE stack, split from the app-level `Debugging`/`DebugLevel` in
 * MainActivity.kt (LIB-1/S1) so the future ble-* modules do not import the app package.
 *
 * These mirror the app values and must stay equal to them until S2 replaces both with a
 * BleConfig supplied by the app. Levels: 0 errors, 1 +warnings, 3 +info, 4 debug,
 * 5 extended (high-volume payload logging).
 */
const val BLE_DEBUGGING = true
const val BLE_DEBUG_LEVEL = 5
