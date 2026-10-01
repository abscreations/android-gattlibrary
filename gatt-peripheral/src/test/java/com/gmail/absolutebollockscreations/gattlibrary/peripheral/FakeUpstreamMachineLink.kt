package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.CommandResult
import com.gmail.absolutebollockscreations.gattlibrary.central.RejectionReason

/** Records the commands a sink submits, tagged with the token each carried. */
internal class FakeUpstreamMachineLink : UpstreamMachineLink {
        /** Mints a token for a machine key; equal keys give equal tokens. */
    fun tokenFor(key: String) = UpstreamMachineRef(key)

    override var machineToken: UpstreamMachineRef? = tokenFor("machine-1")
    override var machineConnected: Boolean = true

    /** What every command returns, unless [throwOnCall]. */
    var result: CommandResult = CommandResult.Queued
    var throwOnCall = false

    /** Machines the link still considers current; a command for any other token is MachineChanged. */
    var currentKey: String? = "machine-1"

    val calls = mutableListOf<String>()
    val tokens = mutableListOf<UpstreamMachineRef>()

    private fun record(token: UpstreamMachineRef, call: String): CommandResult {
        calls += call
        tokens += token
        if (throwOnCall) error("boom")
        if (token != machineTokenFor(currentKey)) return CommandResult.MachineChanged
        return result
    }

    private fun machineTokenFor(key: String?) = key?.let { tokenFor(it) }

    fun refuse() { result = CommandResult.Rejected(RejectionReason.NOT_ACCEPTED) }

    override fun startOrResume(token: UpstreamMachineRef) = record(token, "start")
    override fun stopOrPause(token: UpstreamMachineRef) = record(token, "stop")
    override fun setTargetSpeedKph(token: UpstreamMachineRef, kph: Float) = record(token, "speed=$kph")
    override fun setTargetInclinePercent(token: UpstreamMachineRef, percent: Float) = record(token, "incline=$percent")
    override fun setTargetResistanceLevel(token: UpstreamMachineRef, level: Float) = record(token, "resistance=$level")
    override fun setTargetPowerWatts(token: UpstreamMachineRef, watts: Int) = record(token, "power=$watts")
    override fun setWheelCircumferenceMm(token: UpstreamMachineRef, millimetres: Float) =
        record(token, "wheel=$millimetres")
    override fun setIndoorBikeSimulation(
        token: UpstreamMachineRef,
        windSpeedMps: Float,
        gradePercent: Float,
        rollingResistanceCoefficient: Float,
        windResistanceCoefficient: Float,
    ) = record(token, "sim=$windSpeedMps,$gradePercent,$rollingResistanceCoefficient,$windResistanceCoefficient")
}
