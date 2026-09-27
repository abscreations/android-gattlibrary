package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.core

// Moved from MainActivity.kt (LIB-1/S1): the BLE stack uses these, so they must not live in the
// app package. Entry names and order are unchanged; persisted values and ordinals still match.

enum class HrZoneMode { Off, MaxPercent, HeartRateReserve }
enum class ActivityType { BIKING, ELLIPTICAL, ROWING, STAIRCLIMBER, STAIRSTEPPER, TREADMILL, HEARTRATE }
