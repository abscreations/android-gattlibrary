package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import android.annotation.SuppressLint
import com.gmail.absolutebollockscreations.gattlibrary.central.BtServiceEngineFacade
import timber.log.Timber
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsControlSink
import com.gmail.absolutebollockscreations.gattlibrary.server.TargetCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.server.UpstreamControlSink

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
) : UpstreamControlSink {

    @Volatile
    private var capabilities: TargetCapabilities = TargetCapabilities.NONE

    private val engine: BtServiceEngineFacade?
        get() = engineProvider()

    /** True only when a machine is attached AND currently connected. */
    override val canControl: Boolean
        get() = engine?.isFtmsConnected == true

    override val targetCapabilities: TargetCapabilities
        get() = capabilities

    // The upstream FTMS machine reports accepted targets through 0x2ADA. That
    // event is parsed by BtEngine and mirrored back through BtServiceManager.
    override val reportsTargetChangesAsynchronously: Boolean
        get() = true

    /**
     * Refresh the advertised target capability from what the machine reported.
     * Called by the controller when a session begins, so the published Target
     * Setting Features word and the registered Supported-* Range characteristics
     * are derived from the same snapshot.
     */
    override fun beginSession(targets: TargetCapabilities) {
        capabilities = targets
    }

    override fun endSession() {
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
        forwardTarget("setTargetInclination") { it.setTargetInclinationPercent(percent.toFloat()) }

    @SuppressLint("MissingPermission")
    override fun setTargetResistance(level: Double): Boolean =
        forwardTarget("setTargetResistance") { it.setTargetResistance(level.toFloat()) }

    @SuppressLint("MissingPermission")
    override fun setTargetPowerWatts(watts: Int): Boolean =
        forwardTarget("setTargetPowerWatts") { it.setTargetPowerWatts(watts) }

    /** No upstream heart-rate target path exists on the facade. */
    override fun setTargetHeartRate(bpm: Int): Boolean = false

    @SuppressLint("MissingPermission")
    override fun setWheelCircumferenceMm(millimetres: Double): Boolean =
        forwardTarget("setWheelCircumferenceMm") { it.setWheelCircumferenceMm(millimetres.toFloat()) }

    /**
     * Forwarded since 2026-08-10 (plan §9.5.6). This previously returned `false` with the
     * comment "No upstream simulation path exists on the facade", which meant a riding app's
     * road gradient was decoded correctly and then discarded — ERG target power passed through,
     * simulation mode did not.
     *
     * The upstream write is gated on Target Setting Features bit 13, so a machine that never
     * declared simulation support still reports failure rather than silent acceptance.
     */
    @SuppressLint("MissingPermission")
    override fun setIndoorBikeSimulation(
        windMps: Double,
        gradePercent: Double,
        crr: Double,
        cw: Double,
    ): Boolean = forwardTarget("setIndoorBikeSimulation") {
        it.setIndoorBikeSimulation(
            windMps.toFloat(),
            gradePercent.toFloat(),
            crr.toFloat(),
            cw.toFloat(),
        )
    }

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
        if (e.handlesDownstreamSessionTransitionsLocally) return true
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
