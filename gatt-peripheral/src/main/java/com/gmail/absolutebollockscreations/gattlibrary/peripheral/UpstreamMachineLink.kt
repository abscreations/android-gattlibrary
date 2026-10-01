package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.CommandResult
import com.gmail.absolutebollockscreations.gattlibrary.central.ConnectionStatus
import com.gmail.absolutebollockscreations.gattlibrary.central.FitnessGattClient
import com.gmail.absolutebollockscreations.gattlibrary.central.MachineIdentity
import com.gmail.absolutebollockscreations.gattlibrary.central.MachineToken

/**
 * Names one upstream machine for one session. Opaque: take it from [UpstreamMachineLink.machineToken]
 * and hand it back with each command. Equal when they name the same machine.
 */
public class UpstreamMachineRef internal constructor(internal val handle: Any) {
    override fun equals(other: Any?): Boolean = other is UpstreamMachineRef && other.handle == handle
    override fun hashCode(): Int = handle.hashCode()
    override fun toString(): String = "UpstreamMachineRef($handle)"
}

/**
 * What the bridge's control sink needs from the upstream machine: which machine it is, whether it
 * is connected, and typed commands bound to a machine. [FitnessGattClient] is the production
 * implementation (see [asUpstreamMachineLink]); the interface exists because the client cannot be
 * constructed outside its module, so a sink could not otherwise be tested against a fake.
 */
public interface UpstreamMachineLink {
    /** The machine a command prepared now would be bound to, or null when there is none. */
    public val machineToken: UpstreamMachineRef?

    /** True only while the machine's control link is up (an advertisement-only machine is not). */
    public val machineConnected: Boolean

    /** The machine now, for naming the bridge and choosing its measurement set; null when there is none. */
    public fun machineIdentity(): MachineIdentity?

    /** Addresses of every connected central-role link, lower-cased: machine and sensors. */
    public fun connectedAddresses(): Set<String>

    public fun startOrResume(token: UpstreamMachineRef): CommandResult
    public fun stopOrPause(token: UpstreamMachineRef): CommandResult
    public fun setTargetSpeedKph(token: UpstreamMachineRef, kph: Float): CommandResult
    public fun setTargetInclinePercent(token: UpstreamMachineRef, percent: Float): CommandResult
    public fun setTargetResistanceLevel(token: UpstreamMachineRef, level: Float): CommandResult
    public fun setTargetPowerWatts(token: UpstreamMachineRef, watts: Int): CommandResult
    public fun setWheelCircumferenceMm(token: UpstreamMachineRef, millimetres: Float): CommandResult
    public fun setIndoorBikeSimulation(
        token: UpstreamMachineRef,
        windSpeedMps: Float,
        gradePercent: Float,
        rollingResistanceCoefficient: Float,
        windResistanceCoefficient: Float,
    ): CommandResult
}

public fun FitnessGattClient.asUpstreamMachineLink(): UpstreamMachineLink {
    val client = this
    return object : UpstreamMachineLink {
        override val machineToken: UpstreamMachineRef? get() = client.machineToken.value?.let(::UpstreamMachineRef)
        override val machineConnected: Boolean
            get() = client.connections.value.machineStatus == ConnectionStatus.CONNECTED

        override fun machineIdentity() = client.machineIdentity()
        override fun connectedAddresses(): Set<String> =
            client.connections.value.links.mapTo(mutableSetOf()) { it.address.lowercase() }

        override fun startOrResume(token: UpstreamMachineRef) = client.startOrResume(token.native())
        override fun stopOrPause(token: UpstreamMachineRef) = client.stopOrPause(token.native())
        override fun setTargetSpeedKph(token: UpstreamMachineRef, kph: Float) = client.setTargetSpeedKph(token.native(), kph)
        override fun setTargetInclinePercent(token: UpstreamMachineRef, percent: Float) =
            client.setTargetInclinePercent(token.native(), percent)
        override fun setTargetResistanceLevel(token: UpstreamMachineRef, level: Float) =
            client.setTargetResistanceLevel(token.native(), level)
        override fun setTargetPowerWatts(token: UpstreamMachineRef, watts: Int) = client.setTargetPowerWatts(token.native(), watts)
        override fun setWheelCircumferenceMm(token: UpstreamMachineRef, millimetres: Float) =
            client.setWheelCircumferenceMm(token.native(), millimetres)
        override fun setIndoorBikeSimulation(
            token: UpstreamMachineRef,
            windSpeedMps: Float,
            gradePercent: Float,
            rollingResistanceCoefficient: Float,
            windResistanceCoefficient: Float,
        ) = client.setIndoorBikeSimulation(
            token.native(), windSpeedMps, gradePercent, rollingResistanceCoefficient, windResistanceCoefficient,
        )
    }
}

private fun UpstreamMachineRef.native(): MachineToken = handle as MachineToken
