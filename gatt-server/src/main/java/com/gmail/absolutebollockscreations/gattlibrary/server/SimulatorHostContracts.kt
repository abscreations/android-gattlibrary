package com.gmail.absolutebollockscreations.gattlibrary.server

import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import kotlinx.coroutines.flow.MutableStateFlow

/*
 * Contracts between the app's peripheral manager and the simulator's peripheral hub (LIB-9a).
 *
 * They live in gatt-peripheral because both sides may depend on it: the simulator must not import
 * app code, and after S9b the app manager must not import simulator code.
 */

/** What the simulator peripheral hub needs from the app's peripheral manager. */
public interface SimulatorHostEnvironment {
    /** Host-wide treadmill speed opt-in, read at advertisement start and before each write. */
    public fun allowsTreadmillSpeedControl(): Boolean = false

    /** Whether the KICKR simulator may run in this build (debug only in FitBridge). */
    public val kickrRuntimeAllowed: Boolean

    /** The manager's published peripheral status; the hub writes family-controller status here. */
    public val peripheralStatus: MutableStateFlow<FtmsPeripheralController.PeripheralStatus>

    /** The manager's single FTMS GATT server, which the hub stops before a family persona starts. */
    public val ftmsController: FtmsPeripheralController

    /** The manager's Bluetooth permission and adapter gate. */
    public fun canAdvertise(): Boolean

    /**
     * Start a standard FTMS (or native CSC/CPS indoor-bike) persona on the manager's FTMS server.
     * The hub has already stopped every family controller and cleared its selection.
     */
    public fun startStandardPersona(
        profile: FtmsMachineProfile,
        nativeBikeSensors: Boolean,
        targets: TargetCapabilities,
        advertisedMachineType: FtmsMachineProfile,
    ): Boolean
}

/**
 * Peripherals the app manager must stop or dispose around its own downstream sessions.
 *
 * **Bridge mode and simulator mode are mutually exclusive (decision L17).** A release build
 * bridges a real machine (central + FTMS pass-through peripheral); a debug build may instead run
 * the simulator as a fake machine for another device to connect to. So any bridge start stops
 * every simulator peripheral and clears every selection flag, via [stopAll].
 */
public interface ManagedPeripheralGroup {

    /** True when any of the group's peripherals is advertising (all ten family controllers). */
    public val anyAdvertising: Boolean

    /**
     * Stop every family controller and clear all selection flags. Used by the manager's `stop()`,
     * `stopAdvertising()` and, under L17, before starting any bridged FTMS or CSC/CPS session.
     */
    public fun stopAll()

    /** Manager `release()`: dispose every family controller and clear selection. */
    public fun disposeAll()

    public companion object {
        /** No simulator peripherals: release builds, where the gateway supplies none (LIB-9b). */
        public val NONE: ManagedPeripheralGroup = object : ManagedPeripheralGroup {
            override val anyAdvertising: Boolean get() = false
            override fun stopAll() = Unit
            override fun disposeAll() = Unit
        }
    }
}
