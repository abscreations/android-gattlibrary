package com.gmail.absolutebollockscreations.gattlibrary.central

/**
 * Names one machine for the lifetime of its connection (or its advertisement selection). Commands
 * carry the token they were prepared for, and a command prepared for a machine that has since gone
 * is refused with [CommandResult.MachineChanged] rather than redirected to whichever machine is
 * current. Take it from [FitnessGattClient.machineToken]; never persist it.
 *
 * A reconnect to the same address is a different machine as far as a token is concerned.
 */
public class MachineToken internal constructor(internal val owner: Any, internal val key: Any) {
    override fun equals(other: Any?): Boolean =
        other is MachineToken && other.owner === owner && other.key == key

    override fun hashCode(): Int = 31 * System.identityHashCode(owner) + key.hashCode()

    override fun toString(): String = "MachineToken($key)"
}

/** Why a command was refused before it reached the machine's control path. */
public enum class RejectionReason {
    /** The client was closed. */
    CLIENT_CLOSED,

    /**
     * The machine's control path declined it: no control path for this machine, control not
     * granted, the feature is not offered, or the request could not be submitted. The engine does
     * not say which, so neither does this.
     */
    NOT_ACCEPTED,
}

/**
 * What happened to a command at the moment it was submitted. This is not a confirmation: the
 * machine reports what it accepted separately, as [ControlEvent]s. Confirmation, a sent-without-
 * confirmation outcome and a timeout are not part of this vocabulary yet; they arrive with the
 * slice that can produce them, so a consumer's `when` needs an `else` branch.
 */
public sealed interface CommandResult {
    /** Submitted to the machine's control path. The machine has not necessarily acted on it. */
    public data object Queued : CommandResult

    /** Refused before it was sent. */
    public data class Rejected(val reason: RejectionReason) : CommandResult

    /** The token's machine is no longer the current one; nothing was sent. */
    public data object MachineChanged : CommandResult
}

/** A command as the backend receives it; units are the client's canonical ones. */
internal sealed interface MachineCommand {
    data object StartOrResume : MachineCommand
    data object StopOrPause : MachineCommand
    data class TargetSpeedKph(val kph: Float) : MachineCommand
    data class TargetInclinePercent(val percent: Float) : MachineCommand
    data class TargetResistanceLevel(val level: Float) : MachineCommand
    data class TargetPowerWatts(val watts: Int) : MachineCommand
    data class WheelCircumferenceMm(val millimetres: Float) : MachineCommand
    data class IndoorBikeSimulation(
        val windSpeedMps: Float,
        val gradePercent: Float,
        val rollingResistanceCoefficient: Float,
        val windResistanceCoefficient: Float,
    ) : MachineCommand
}
