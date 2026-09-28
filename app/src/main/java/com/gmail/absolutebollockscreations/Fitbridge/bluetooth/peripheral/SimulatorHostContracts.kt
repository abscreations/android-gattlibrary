package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtServiceProfiles.FtmsMachineProfile
import kotlinx.coroutines.flow.MutableStateFlow

/*
 * Contracts between the app's peripheral manager and the simulator's peripheral hub (LIB-9a).
 *
 * They live in ble-peripheral because both sides may depend on it: the simulator must not import
 * app code, and after S9b the app manager must not import simulator code.
 */

/** What the simulator peripheral hub needs from the app's peripheral manager. */
interface SimulatorHostEnvironment {

    /** Whether the KICKR simulator may run in this build (debug only in FitBridge). */
    val kickrRuntimeAllowed: Boolean

    /** The manager's published peripheral status; the hub writes family-controller status here. */
    val peripheralStatus: MutableStateFlow<FtmsPeripheralController.PeripheralStatus>

    /** The manager's single FTMS GATT server, which the hub stops before a family persona starts. */
    val ftmsController: FtmsPeripheralController

    /** The manager's Bluetooth permission and adapter gate. */
    fun canAdvertise(): Boolean

    /**
     * Start a standard FTMS (or native CSC/CPS indoor-bike) persona on the manager's FTMS server.
     * The hub has already stopped every family controller and cleared its selection.
     */
    fun startStandardPersona(
        profile: FtmsMachineProfile,
        nativeBikeSensors: Boolean,
        targets: TargetCapabilities,
        advertisedMachineType: FtmsMachineProfile,
    ): Boolean
}

/**
 * Peripherals the app manager must stop or dispose around its own downstream sessions.
 *
 * Each method reproduces, verbatim, the block the manager ran inline before LIB-9a, including
 * its existing asymmetries (recorded in the implementation log, not changed here).
 */
interface ManagedPeripheralGroup {

    /** Advertising state the manager's `isAdvertising` includes (not Flywheel or Kayak First). */
    val anyAdvertising: Boolean

    /** Manager `stop()` and `stopAdvertising()`: stop every family controller and clear selection. */
    fun stopAll()

    /** Manager `release()`: dispose every family controller and clear selection. */
    fun disposeAll()

    /** Before a bridged FTMS session: stop nine controllers (not KICKR) and reset six flags. */
    fun stopBeforeFtmsBridge()

    /** Before a bridged CSC/CPS session: stop nine controllers (not KICKR) and reset seven flags. */
    fun stopBeforeCscCpsBridge()
}
