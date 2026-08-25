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
 *        [BtServiceProfiles.FtmsMachineProfile] (treadmill, bike, rower, etc.),
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
 *      • Switch into the appropriate [BtServiceProfiles.ServiceMode] (FTMS or CSC_CPS).
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

package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtEngineState.FtmsCapabilities
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtServiceProfiles.FtmsMachineProfile
import timber.log.Timber

/**
 * Thin control surface that BtEngine can use to drive the local provider-side
 * GATT services (btService module) without depending on service-layer types.
 *
 * Shared enums / profiles come from [BtServiceProfiles] in bluetoothIds.kt.
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
     * extend this method to take a [BtServiceProfiles.CscCpsProfile].
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

    // ─────────────────────────────────────────────────────────────
    // FTMS status / control events (engine → service)
    // ─────────────────────────────────────────────────────────────

    /**
     * Called when the upstream FTMS device indicates that control is no longer
     * permitted (e.g. via CP result code or Fitness Machine Status 0xFF).
     *
     * Typical implementation on the service side:
     *  • Emit an FTMS Fitness Machine Status notification with
     *    [BluetoothIds.STATUS_CONTROL_PERMISSION_LOST] to all connected clients.
     *  • Optionally clear any local notion of “have control”.
     */
    fun onControlPermissionLost()

    /**
     * Called when the upstream FTMS device reports that the safety key / tether
     * has been pulled, or an emergency stop has occurred.
     *
     * Typical implementation on the service side:
     *  • Emit an FTMS Fitness Machine Status notification with
     *    [BluetoothIds.STATUS_FITNESS_MACHINE_STOPPED_BY_SAFETY_KEY].*
     *  • Optionally stop any simulated motion / timers.
     */
    fun onSafetyKeyPulled()

    /**
     * Called when the upstream FTMS device reports that the target speed has
     * changed (via Fitness Machine Status 0x05).
     *
     * @param kmh New target speed in km/h (already decoded from 0.01 km/h units).
     *
     * Typical implementation:
     *  • Emit STATUS_TARGET_SPEED_CHANGED with the encoded UInt16 parameter.
     *  • Update any local mirror of target speed if maintained.
     */
    fun onTargetSpeedChangedKmh(kmh: Float)

    /**
     * Called when the upstream FTMS device reports that the target incline has
     * changed (via Fitness Machine Status 0x06).
     *
     * @param percent New target incline in percent grade (already decoded from 0.1 % units).
     *
     * Typical implementation:
     *  • Emit STATUS_TARGET_INCLINE_CHANGED with the encoded SInt16 parameter.
     */
    fun onTargetInclineChangedPercent(percent: Float)

    /**
     * Called when the upstream FTMS device reports that the target resistance
     * level has changed (via Fitness Machine Status 0x07).
     *
     * @param level New target resistance, unitless, in 0.1 resolution (already scaled).
     *
     * Typical implementation:
     *  • Emit STATUS_TARGET_RESISTANCE_LEVEL_CHANGED with the encoded UINT8 parameter.
     */
    fun onTargetResistanceLevelChanged(level: Float)

    /**
     * Called when the upstream FTMS device reports that the target power has
     * changed (via Fitness Machine Status 0x08).
     *
     * @param watts New target power in watts.
     *
     * Typical implementation:
     *  • Emit STATUS_TARGET_POWER_CHANGED with the encoded SInt16 parameter.
     */
    fun onTargetPowerChangedWatts(watts: Int)

    /** Accepted FTMS 0x12 / KICKR 0x48 value, in millimetres. */
    fun onWheelCircumferenceChangedMm(millimetres: Double) {}

    /**
     * Called when the upstream FTMS device reports that the target heart rate
     * has changed (via Fitness Machine Status 0x09).
     *
     * @param bpm New target heart rate in beats per minute.
     *
     * Typical implementation:
     *  • Emit STATUS_TARGET_HEART_RATE_CHANGED with the encoded UINT8 parameter.
     */
    fun onTargetHeartRateChangedBpm(bpm: Int)
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
class LoggingBtServiceControlSink(
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

    override fun onControlPermissionLost() {
        Timber.tag(tag).d("onControlPermissionLost()")
        delegate.onControlPermissionLost()
    }

    override fun onSafetyKeyPulled() {
        Timber.tag(tag).d("onSafetyKeyPulled()")
        delegate.onSafetyKeyPulled()
    }

    override fun onTargetSpeedChangedKmh(kmh: Float) {
        Timber.tag(tag).d("onTargetSpeedChangedKmh(kmh=%.2f)", kmh)
        delegate.onTargetSpeedChangedKmh(kmh)
    }

    override fun onTargetInclineChangedPercent(percent: Float) {
        Timber.tag(tag).d("onTargetInclineChangedPercent(percent=%.2f)", percent)
        delegate.onTargetInclineChangedPercent(percent)
    }

    override fun onTargetResistanceLevelChanged(level: Float) {
        Timber.tag(tag).d("onTargetResistanceLevelChanged(level=%.2f)", level)
        delegate.onTargetResistanceLevelChanged(level)
    }

    override fun onTargetPowerChangedWatts(watts: Int) {
        Timber.tag(tag).d("onTargetPowerChangedWatts(watts=%d)", watts)
        delegate.onTargetPowerChangedWatts(watts)
    }

    override fun onWheelCircumferenceChangedMm(millimetres: Double) {
        Timber.tag(tag).d("onWheelCircumferenceChangedMm(millimetres=%.1f)", millimetres)
        delegate.onWheelCircumferenceChangedMm(millimetres)
    }

    override fun onTargetHeartRateChangedBpm(bpm: Int) {
        Timber.tag(tag).d("onTargetHeartRateChangedBpm(bpm=%d)", bpm)
        delegate.onTargetHeartRateChangedBpm(bpm)
    }
}

/**
 * Convenience extension: wrap any [BtServiceControlSink] with a logging decorator.
 *
 * Example:
 *   val manager = BtServiceManager(context, engine.state)
 *   engine.attachServiceControlSink(manager.withLogging("BtServiceManagerSink"))
 */
fun BtServiceControlSink.withLogging(tag: String = "BtServiceControlSink"): BtServiceControlSink =
    LoggingBtServiceControlSink(this, tag)
