package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.BtEngineState
import com.gmail.absolutebollockscreations.gattlibrary.server.ReportedTargetRanges
import com.gmail.absolutebollockscreations.gattlibrary.server.TargetCapabilities

/**
 * Bridge mapping from the central engine's capability snapshot to the server-owned [ReportedTargetRanges].
 * Kept as a companion extension so existing `TargetCapabilities.fromEngineCapabilities(...)` call sites read
 * unchanged; it leaves with the bridge when peripheral moves to the public client (L24).
 */
fun TargetCapabilities.Companion.fromEngineCapabilities(caps: BtEngineState.FtmsCapabilities): TargetCapabilities =
    fromReportedRanges(
        ReportedTargetRanges(
            supportsSpeed = caps.supportsSpeed,
            supportsInclination = caps.supportsInclination,
            supportsResistance = caps.supportsResistance,
            supportsPower = caps.supportsPower,
            supportsIndoorBikeSimulation = caps.supportsIndoorBikeSimulation,
            supportsWheelCircumference = caps.supportsWheelCircumference,
            speedMin = caps.speedMin, speedMax = caps.speedMax, speedIncr = caps.speedIncr,
            speedRangeKnown = caps.speedRangeKnown,
            inclineMin = caps.inclineMin, inclineMax = caps.inclineMax, inclineIncr = caps.inclineIncr,
            inclineRangeKnown = caps.inclineRangeKnown,
            resistanceMin = caps.resistanceMin, resistanceMax = caps.resistanceMax,
            resistanceIncr = caps.resistanceIncr, resistanceRangeKnown = caps.resistanceRangeKnown,
            powerMin = caps.powerMin, powerMax = caps.powerMax, powerIncr = caps.powerIncr,
            powerRangeKnown = caps.powerRangeKnown,
        ),
    )
