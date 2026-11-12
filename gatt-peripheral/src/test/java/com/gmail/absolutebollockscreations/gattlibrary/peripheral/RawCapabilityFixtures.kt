package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.BtEngineState
import com.gmail.absolutebollockscreations.gattlibrary.central.toDeviceCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.server.TargetCapabilities

/**
 * Downstream claims for a raw engine capability fixture, built through the production path:
 * central's canonical [toDeviceCapabilities] then [fromDeviceCapabilities] (LIB-70, which deleted
 * the raw-snapshot mapping from main). Pace is irrelevant here: target claims never read it.
 * Test-only; it goes when these tests stop building engine fixtures (Stage 3 step 6 item 3).
 */
fun TargetCapabilities.Companion.fromRawFixture(caps: BtEngineState.FtmsCapabilities): TargetCapabilities =
    fromDeviceCapabilities(caps.toDeviceCapabilities(paceMeasurement = false))
