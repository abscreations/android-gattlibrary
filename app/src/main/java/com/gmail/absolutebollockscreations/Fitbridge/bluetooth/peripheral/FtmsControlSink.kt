package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import android.annotation.SuppressLint
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtServiceEngineFacade
import timber.log.Timber

/**
 * Downstream control seam.
 *
 * [FtmsControlPointHandler] owns protocol validation and response framing while
 * this interface isolates delivery to the physical machine. Splitting the two
 * is what makes the whole Control Point state machine unit-testable; the old
 * `BtServiceControlPoint` called `BtServiceEngineFacade` inline and could only
 * be exercised on real hardware.
 */
interface FtmsControlSink {

    /** False while no downstream write path exists. */
    val canControl: Boolean

    /** What the downstream can actually be asked to change. */
    val targetCapabilities: TargetCapabilities

    fun requestControl(): Boolean

    fun reset(): Boolean

    fun startOrResume(): Boolean

    /** @param stop true for Stop (param 0x01), false for Pause (param 0x02). */
    fun stopOrPause(stop: Boolean): Boolean

    fun setTargetSpeedKph(kph: Double): Boolean

    fun setTargetInclinationPercent(percent: Double): Boolean

    fun setTargetResistance(level: Double): Boolean

    fun setTargetPowerWatts(watts: Int): Boolean

    fun setTargetHeartRate(bpm: Int): Boolean

    /**
     * FTMS 0x11 Set Indoor Bike Simulation Parameters.
     *
     * Present because this is how Zwift and most sim-mode training apps drive a
     * trainer. FitBridge has no upstream path for it yet, so the default
     * implementation refuses it up front rather than reporting a synthetic
     * success.
     *
     * @param windMps      wind speed, m/s (wire: sint16, 0.001 m/s)
     * @param gradePercent grade, percent (wire: sint16, 0.01 %)
     * @param crr          coefficient of rolling resistance (wire: uint8, 0.0001)
     * @param cw           wind resistance coefficient, kg/m (wire: uint8, 0.01)
     */
    fun setIndoorBikeSimulation(
        windMps: Double,
        gradePercent: Double,
        crr: Double,
        cw: Double,
    ): Boolean
}

/**
 * Data-only implementation, used before the engine is attached.
 *
 * Control ownership, Reset, and the Start/Resume and Stop/Pause session
 * transitions are all accepted, because a data-only FTMS persona is a perfectly
 * valid machine: those procedures only move the *local* procedure state machine
 * (IDLE/RUNNING/STOPPED/PAUSED) and gate the Training Status the peripheral
 * reports. Answering OPERATION_FAILED here is strictly worse: strict centrals
 * (notably Garmin) treat a failed Start/Resume as fatal and abandon the
 * connection.
 *
 * Only the *target-setting* opcodes stay unsupported, because those genuinely
 * require a downstream we do not have; they are refused up front by [canControl]
 * and [TargetCapabilities], never reported as a synthetic success.
 */
object InertFtmsControlSink : FtmsControlSink {
    override val canControl: Boolean = false
    override val targetCapabilities: TargetCapabilities = TargetCapabilities.NONE

    override fun requestControl(): Boolean = true
    override fun reset(): Boolean = true
    override fun startOrResume(): Boolean = true
    override fun stopOrPause(stop: Boolean): Boolean = true

    override fun setTargetSpeedKph(kph: Double): Boolean = false
    override fun setTargetInclinationPercent(percent: Double): Boolean = false
    override fun setTargetResistance(level: Double): Boolean = false
    override fun setTargetPowerWatts(watts: Int): Boolean = false
    override fun setTargetHeartRate(bpm: Int): Boolean = false
    override fun setIndoorBikeSimulation(
        windMps: Double,
        gradePercent: Double,
        crr: Double,
        cw: Double,
    ): Boolean = false
}

/**
 * Live pass-through to the upstream fitness machine via [BtServiceEngineFacade].
 *
 * This is the part of FitBridge that TrackMyIndoorWorkout does not have - a real
 * downstream - so the honesty policy is stated explicitly:
 *
 *  - **No machine attached.** Session-transition procedures (Request Control,
 *    Reset, Start/Resume, Stop/Pause) are accepted locally, exactly as
 *    [InertFtmsControlSink] does and for the same Garmin-compatibility reason.
 *    Target setting is refused, because [targetCapabilities] is empty.
 *  - **Machine attached.** Every procedure is forwarded, and the downstream
 *    result is reported verbatim. A machine that refuses to start must not be
 *    reported to the watch as started.
 */
class EngineFtmsControlSink(
    private val engineProvider: () -> BtServiceEngineFacade?,
) : FtmsControlSink {

    @Volatile
    private var capabilities: TargetCapabilities = TargetCapabilities.NONE

    private val engine: BtServiceEngineFacade?
        get() = engineProvider()

    /** True only when a machine is attached AND currently connected. */
    override val canControl: Boolean
        get() = engine?.isFtmsConnected == true

    override val targetCapabilities: TargetCapabilities
        get() = capabilities

    /**
     * Refresh the advertised target capability from what the machine reported.
     * Called by the controller when a session begins, so the published Target
     * Setting Features word and the registered Supported-* Range characteristics
     * are derived from the same snapshot.
     */
    fun beginSession(targets: TargetCapabilities) {
        capabilities = targets
    }

    fun endSession() {
        capabilities = TargetCapabilities.NONE
    }

    override fun requestControl(): Boolean = true

    override fun reset(): Boolean = true

    @SuppressLint("MissingPermission")
    override fun startOrResume(): Boolean = forward("startOrResume") { it.startOrResume() }

    @SuppressLint("MissingPermission")
    override fun stopOrPause(stop: Boolean): Boolean =
        // BtServiceEngineFacade exposes a single stopOrPause() with no
        // stop-versus-pause distinction, so both map to it. The local procedure
        // state machine still records which one the client asked for.
        forward("stopOrPause(stop=$stop)") { it.stopOrPause() }

    @SuppressLint("MissingPermission")
    override fun setTargetSpeedKph(kph: Double): Boolean =
        forwardTarget("setTargetSpeedKph") { it.setTargetSpeedKph(kph.toFloat()) }

    @SuppressLint("MissingPermission")
    override fun setTargetInclinationPercent(percent: Double): Boolean =
        forwardTarget("setTargetInclination") { it.setTargetInclinationDeg(percent.toFloat()) }

    @SuppressLint("MissingPermission")
    override fun setTargetResistance(level: Double): Boolean =
        forwardTarget("setTargetResistance") { it.setTargetResistance(level.toFloat()) }

    @SuppressLint("MissingPermission")
    override fun setTargetPowerWatts(watts: Int): Boolean =
        forwardTarget("setTargetPowerWatts") { it.setTargetPowerWatts(watts) }

    /** No upstream heart-rate target path exists on the facade. */
    override fun setTargetHeartRate(bpm: Int): Boolean = false

    /** No upstream simulation path exists on the facade. */
    override fun setIndoorBikeSimulation(
        windMps: Double,
        gradePercent: Double,
        crr: Double,
        cw: Double,
    ): Boolean = false

    /**
     * Session transitions: accept locally when no machine is attached, otherwise
     * report the machine's own answer.
     */
    private inline fun forward(
        label: String,
        action: (BtServiceEngineFacade) -> Boolean,
    ): Boolean {
        val e = engine
        if (e == null || !e.isFtmsConnected) return true
        return runCatching { action(e) }.getOrElse { t ->
            Timber.tag(TAG).w(t, "%s: upstream call threw", label)
            false
        }
    }

    /**
     * Target setting: never optimistic. Without a machine there is nothing to
     * target, and [targetCapabilities] will already have refused the opcode.
     */
    private inline fun forwardTarget(
        label: String,
        action: (BtServiceEngineFacade) -> Boolean,
    ): Boolean {
        val e = engine ?: return false
        if (!e.isFtmsConnected) return false
        return runCatching { action(e) }.getOrElse { t ->
            Timber.tag(TAG).w(t, "%s: upstream call threw", label)
            false
        }
    }

    private companion object {
        private const val TAG = "FtmsControlSink"
    }
}
