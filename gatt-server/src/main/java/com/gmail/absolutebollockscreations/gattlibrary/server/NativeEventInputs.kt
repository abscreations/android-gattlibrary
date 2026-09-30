package com.gmail.absolutebollockscreations.gattlibrary.server

/**
 * Native cycling and treadmill event data as the FTMS/CSC/CPS server layer consumes it (LIB-19f).
 *
 * These are server-owned input types: the server layer must not depend on central's decoded
 * snapshot classes (L22). The metric pump in the bridge maps central's snapshots into these.
 * Field meanings are the raw wire counters: revolution counts and event-time ticks in the units
 * the Bluetooth SIG CSC/CPS characteristics use.
 */
public data class CscNativeEventInput(
    val cumulativeWheelRevolutions: Long? = null,
    val lastWheelEventTimeTicks: Int? = null,
    val cumulativeCrankRevolutions: Int? = null,
    val lastCrankEventTimeTicks: Int? = null,
)

/** Cycling Power Measurement wheel/crank event data; same raw units as [CscNativeEventInput]. */
public data class CpsNativeEventInput(
    val cumulativeWheelRevolutions: Long? = null,
    val lastWheelEventTimeTicks: Int? = null,
    val cumulativeCrankRevolutions: Int? = null,
    val lastCrankEventTimeTicks: Int? = null,
)

/** Treadmill native pace fields, raw as read (units per the FTMS Treadmill Data characteristic). */
public data class TreadmillNativePaceInput(
    val instantaneousRaw: Int? = null,
    val averageRaw: Int? = null,
)
