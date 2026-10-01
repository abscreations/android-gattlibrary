/**
 * BtServiceControlSink.kt
 *
 * Bridge from BtEngine (client-side) → local GATT provider (btService).
 *
 * OVERVIEW
 * --------
 * This file defines the **control surface** that BtEngine uses to drive the local
 * Bluetooth LE provider stack (the `btService` module) without depending on any
 * service-layer implementation types. It also includes a lightweight logging
 * decorator and an extension helper for opt-in diagnostics.
 *
 * PRIMARY RESPONSIBILITIES
 * ------------------------
 * 1) Advertising control (engine → service)
 *    - `startAdvertisingFtms(ftmsType, capabilities)`:
 *        Begin advertising a local FTMS (0x1826) server that emulates the given
 *        [FtmsMachineProfile] (treadmill, bike, rower, etc.),
 *        using the provided [BtEngineState.FtmsCapabilities] snapshot to build
 *        Fitness Machine Feature (0x2ACC) and Supported-* Range characteristics.
 *        Returns `true` when advertising is active (or already active) and
 *        `false` when the provider is not yet ready or failed to start.
 *    - `startAdvertisingCscCps()`:
 *        Begin advertising a combined CSC (0x1816) + CPS (0x1818) bike persona.
 *    - `stopAdvertising()`:
 *        Stop all LE advertising and tear down provider-side client/session state.
 *
 * 2) Provider timing
 *    - `setNotifyIntervalMs(intervalMs)`:
 *        Adjust the provider-side notification cadence (typically forwarded into
 *        `BtServiceNotifier.setPeriodMs(...)`).
 *
 * 3) FTMS status + target mirroring (engine → service)
 *    - `onControlPermissionLost()`:
 *        Reflect upstream FTMS control loss back to connected clients
 *        (e.g. via `STATUS_CONTROL_PERMISSION_LOST` Fitness Machine Status).
 *    - `onSafetyKeyPulled()`:
 *        Reflect emergency stop / safety tether events
 *        (e.g. `STATUS_FITNESS_MACHINE_STOPPED_BY_SAFETY_KEY`).
 *    - `onTarget*Changed(...)`:
 *        Propagate upstream target changes for speed, incline, resistance,
 *        power, and heart rate so the local FTMS service can emit the
 *        corresponding Fitness Machine Status frames.
 *
 * TYPICAL IMPLEMENTATION (btService side)
 * ---------------------------------------
 *  - `startAdvertisingFtms(...)` / `startAdvertisingCscCps()`:
 *      • Switch into the appropriate `ServiceMode` (server-owned, `peripheral.BtServiceProfiles`) (FTMS or CSC_CPS).
 *      • Build the GATT service set via [BtServiceRegistry.forMode], using the provided
 *        [BtEngineState.FtmsCapabilities] to populate 0x2ACC and Supported-* Range
 *        characteristics for the current real FTMS device.
 *      • Register services on a [BluetoothGattServer] and start LE advertising
 *        via a `BtServiceAdvertiser`.
 *      • Start / configure a `BtServiceNotifier` to push FTMS / CSC / CPS data.
 *      • Return `true` if advertising is successfully running (or already running),
 *        `false` if the provider decides not to start yet (e.g. capabilities incomplete,
 *        permissions missing, platform error).
 *
 *  - `stopAdvertising()`:
 *      • Stop advertising, close or reset the GATT server, and clear state
 *        in [BtServiceConnections] + `BtServiceNotifier`.
 *
 *  - `onControlPermissionLost()`, `onSafetyKeyPulled()`, `onTarget*Changed(...)`:
 *      • Forward into `BtServiceNotifier` helpers which build and broadcast
 *        FTMS Fitness Machine Status frames to any subscribed clients, and
 *        coordinate with [BtServiceConnections] to manage synthetic “control”.
 *
 * LOGGING SUPPORT
 * ---------------
 *  - [LoggingBtServiceControlSink]:
 *      Decorator that wraps any [BtServiceControlSink] and logs every call
 *      via Timber before delegating to the underlying implementation.
 *
 *  - `BtServiceControlSink.withLogging(tag)`:
 *      Extension that returns a logging-decorated sink with the given Timber tag:
 *
 *        val rawSink: BtServiceControlSink = BtServiceManager(...)
 *        val sinkWithLogs = rawSink.withLogging("BtServiceManagerSink")
 *        engine.attachServiceControlSink(sinkWithLogs)
 *
 * This keeps BtEngine → btService wiring clean and testable while still providing
 * rich diagnostics when needed.
 */

package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.central.BtEngineState.FtmsCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import timber.log.Timber
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsSpec

/**
 * Thin control surface that BtEngine can use to drive the local provider-side
 * GATT services (btService module) without depending on service-layer types.
 *
 * Service enums / profiles are server-owned (`peripheral.BtServiceProfiles`).
 */
interface BtServiceControlSink {

    /**
     * Start advertising an FTMS (0x1826) service emulating the given machine profile.
     *
     * @param ftmsType      The virtual machine persona to expose (treadmill, bike, etc.).
     * @param capabilities  Snapshot of FTMS capabilities and Supported-* Ranges as parsed
     *                      from the *real* upstream FTMS device. All values are already in
     *                      FTMS wire units and ready to pack into 0x2ACC / 0x2AD4–0x2AD8.
     *
     * @return `true` if local FTMS advertising is now active (or was already active and
     *         acceptable for this persona), `false` if the provider is not ready to start
     *         advertising yet (e.g. incomplete capabilities, permission failure, platform
     *         error). BtEngine uses this to decide whether to keep retrying while the
     *         upstream FTMS link remains connected.
     *
     * Typical implementor: wraps BtServiceManager.startFtmsAdvertising(profile, capabilities).
     */
    fun startAdvertisingFtms(
        ftmsType: FtmsMachineProfile,
        capabilities: FtmsCapabilities
    ): Boolean

    /**
     * Start advertising a combined CSC + CPS (0x1816 + 0x1818) bike profile.
     *
     * For now this is a single “bike” persona; if we add more later we can
     * extend this method to take a `CscCpsProfile` (server-owned).
     */
    fun startAdvertisingCscCps()

    /**
     * Stop all LE advertising and tear down provider-side client state.
     */
    fun stopAdvertising()

    /**
     * Adjust the provider-side notify interval (in milliseconds).
     * Implementors typically forward this into BtServiceNotifier.
     */
    fun setNotifyIntervalMs(intervalMs: Long)

    // Machine status and control events no longer travel through this interface: they are
    // published as [ControlEvent] on `FitnessGattClient.controlEvents`.
}

/**
 * Logging decorator for [BtServiceControlSink].
 *
 * Wrap any concrete implementation with this to get Timber debug logs for
 * all sink calls without changing your existing engine/service wiring:
 *
 *   val rawSink: BtServiceControlSink = BtServiceManager(...)
 *   val loggedSink = LoggingBtServiceControlSink(rawSink, "BtServiceSink")
 *   engine.attachServiceControlSink(loggedSink)
 */
internal class LoggingBtServiceControlSink(
    private val delegate: BtServiceControlSink,
    private val tag: String = "BtServiceControlSink"
) : BtServiceControlSink {

    override fun startAdvertisingFtms(
        ftmsType: FtmsMachineProfile,
        capabilities: FtmsCapabilities
    ): Boolean {
        Timber.tag(tag).d(
            "startAdvertisingFtms(ftmsType=%s, featuresRead=%s, supports: speed=%s incl=%s res=%s power=%s)",
            ftmsType,
            capabilities.featuresRead,
            capabilities.supportsSpeed,
            capabilities.supportsInclination,
            capabilities.supportsResistance,
            capabilities.supportsPower
        )
        return delegate.startAdvertisingFtms(ftmsType, capabilities)
    }

    override fun startAdvertisingCscCps() {
        Timber.tag(tag).d("startAdvertisingCscCps()")
        delegate.startAdvertisingCscCps()
    }

    override fun stopAdvertising() {
        Timber.tag(tag).d("stopAdvertising()")
        delegate.stopAdvertising()
    }

    override fun setNotifyIntervalMs(intervalMs: Long) {
        Timber.tag(tag).d("setNotifyIntervalMs(intervalMs=%d)", intervalMs)
        delegate.setNotifyIntervalMs(intervalMs)
    }
}

/**
 * Convenience extension: wrap any [BtServiceControlSink] with a logging decorator.
 *
 * Example:
 *   val manager = BtServiceManager(context, engine.state)
 *   engine.attachServiceControlSink(manager.withLogging("BtServiceManagerSink"))
 */
internal fun BtServiceControlSink.withLogging(tag: String = "BtServiceControlSink"): BtServiceControlSink =
    LoggingBtServiceControlSink(this, tag)
