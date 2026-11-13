package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.CommandResult
import com.gmail.absolutebollockscreations.gattlibrary.server.TargetCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.server.UpstreamControlSink
import timber.log.Timber

/**
 * Live pass-through to the upstream fitness machine through the central client's typed commands.
 *
 * This is the part of FitBridge that TrackMyIndoorWorkout does not have - a real downstream - so
 * the honesty policy is stated explicitly:
 *
 *  - **No machine attached.** Session-transition procedures (Request Control, Reset,
 *    Start/Resume, Stop/Pause) are accepted locally, exactly as [InertFtmsControlSink] does and
 *    for the same Garmin-compatibility reason. Target setting is refused, because
 *    [targetCapabilities] is empty.
 *  - **Machine attached.** Every procedure is forwarded, and the downstream result is reported.
 *    A machine that refuses to start must not be reported to the watch as started. "Reported" is
 *    the submission result ([CommandResult.Queued] is true); the machine's own acceptance arrives
 *    later through its status characteristic.
 *  - **Bound to one machine.** [beginSession] captures the machine's [UpstreamMachineRef] and every
 *    command carries it until [endSession]. If that machine goes away and another takes its place
 *    mid-session, the commands are refused ([com.gmail.absolutebollockscreations.gattlibrary.central.CommandResult.MachineChanged]) rather than redirected
 *    to the replacement. A machine that appears only after the session began is not reached until
 *    the next session.
 *
 * A machine that runs its own session (Kayak First) accepts Start/Stop locally inside the client,
 * not here.
 */
public class ClientFtmsControlSink(
    private val link: UpstreamMachineLink,
) : UpstreamControlSink {

    @Volatile
    private var capabilities: TargetCapabilities = TargetCapabilities.NONE

    @Volatile
    private var token: UpstreamMachineRef? = null

    /** True only when this session's machine is attached AND its control link is up. */
    override val canControl: Boolean
        get() = token != null && link.machineConnected

    override val targetCapabilities: TargetCapabilities
        get() = capabilities

    // The upstream FTMS machine reports accepted targets through 0x2ADA. That event is parsed by
    // the central engine and mirrored back through BtServiceManager.
    override val reportsTargetChangesAsynchronously: Boolean
        get() = true

    /**
     * Refresh the advertised target capability from what the machine reported, and bind the
     * session to the machine that is current now. Called by the controller when a session begins,
     * so the published Target Setting Features word and the registered Supported-* Range
     * characteristics are derived from the same snapshot.
     */
    override fun beginSession(targets: TargetCapabilities) {
        capabilities = targets
        token = link.machineToken
    }

    override fun endSession() {
        capabilities = TargetCapabilities.NONE
        token = null
    }

    override fun requestControl(): Boolean = true

    override fun reset(): Boolean = true

    override fun startOrResume(): Boolean = forward("startOrResume") { l, t -> l.startOrResume(t) }

    override fun stopOrPause(stop: Boolean): Boolean =
        // The machine's control path has a single stop-or-pause operation, so both map to it. The
        // local procedure state machine still records which one the client asked for.
        forward("stopOrPause(stop=$stop)") { l, t -> l.stopOrPause(t) }

    override fun setTargetSpeedKph(kph: Double): Boolean =
        forwardTarget("setTargetSpeedKph") { l, t -> l.setTargetSpeedKph(t, kph.toFloat()) }

    override fun setTargetInclinationPercent(percent: Double): Boolean =
        forwardTarget("setTargetInclination") { l, t -> l.setTargetInclinePercent(t, percent.toFloat()) }

    override fun setTargetResistance(level: Double): Boolean =
        forwardTarget("setTargetResistance") { l, t -> l.setTargetResistanceLevel(t, level.toFloat()) }

    override fun setTargetPowerWatts(watts: Int): Boolean =
        forwardTarget("setTargetPowerWatts") { l, t -> l.setTargetPowerWatts(t, watts) }

    /** No upstream heart-rate target path exists. */
    override fun setTargetHeartRate(bpm: Int): Boolean = false

    override fun setWheelCircumferenceMm(millimetres: Double): Boolean =
        forwardTarget("setWheelCircumferenceMm") { l, t -> l.setWheelCircumferenceMm(t, millimetres.toFloat()) }

    /**
     * The upstream write is gated on Target Setting Features bit 13, so a machine that never
     * declared simulation support still reports failure rather than silent acceptance.
     */
    override fun setIndoorBikeSimulation(
        windMps: Double,
        gradePercent: Double,
        crr: Double,
        cw: Double,
    ): Boolean = forwardTarget("setIndoorBikeSimulation") { l, t ->
        l.setIndoorBikeSimulation(t, windMps.toFloat(), gradePercent.toFloat(), crr.toFloat(), cw.toFloat())
    }

    /** Session transitions: accept locally when no machine is attached, otherwise report the submission. */
    private inline fun forward(
        label: String,
        action: (UpstreamMachineLink, UpstreamMachineRef) -> CommandResult,
    ): Boolean {
        val t = token
        if (t == null || !link.machineConnected) return true
        return submit(label) { action(link, t) }
    }

    /**
     * Target setting: never optimistic. Without a machine there is nothing to target, and
     * [targetCapabilities] will already have refused the opcode.
     */
    private inline fun forwardTarget(
        label: String,
        action: (UpstreamMachineLink, UpstreamMachineRef) -> CommandResult,
    ): Boolean {
        val t = token ?: return false
        if (!link.machineConnected) return false
        return submit(label) { action(link, t) }
    }

    private inline fun submit(label: String, call: () -> CommandResult): Boolean =
        runCatching(call).fold(
            onSuccess = { result ->
                if (result !is CommandResult.Queued) Timber.tag(TAG).d("%s: %s", label, result)
                result is CommandResult.Queued
            },
            onFailure = { t ->
                Timber.tag(TAG).w(t, "%s: upstream call threw", label)
                false
            },
        )

    private companion object {
        private const val TAG = "FtmsControlSink"
    }
}
