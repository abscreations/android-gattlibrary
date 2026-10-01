package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.CommandResult
import com.gmail.absolutebollockscreations.gattlibrary.central.RejectionReason
import com.gmail.absolutebollockscreations.gattlibrary.central.DeviceCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.central.MachineIdentity
import com.gmail.absolutebollockscreations.gattlibrary.central.MeasurementSnapshot
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsMetricStore
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsUpstreamPort
import com.gmail.absolutebollockscreations.gattlibrary.server.MetricSampler
import com.gmail.absolutebollockscreations.gattlibrary.server.UpstreamControlSink

/** Bridge implementation of [FtmsUpstreamPort] over the central engine (moves off central in L24). */
class EngineUpstreamPort(
    /** Live read of the public client's measurements (`client.measurements.value`). */
    private val measurements: () -> MeasurementSnapshot,
    /** Live read of the public client capabilities (`client.capabilities.value`). */
    private val capabilities: () -> DeviceCapabilities,
    /**
     * The upstream machine the control sink commands. Without one the peripheral is data-only and
     * refuses every target-setting opcode up front rather than reporting a synthetic success.
     */
    machine: UpstreamMachineLink? = null,
) : FtmsUpstreamPort {

    override val controlSink: UpstreamControlSink = ClientFtmsControlSink(machine ?: NoUpstreamMachine)

    private val link: UpstreamMachineLink = machine ?: NoUpstreamMachine

    override fun connectedCentralAddresses(): Set<String> = link.connectedAddresses()

    override fun supportsPaceMeasurement(): Boolean = capabilities().supportsPaceMeasurement

    override fun createMetricSampler(
        store: FtmsMetricStore,
        profileProvider: () -> FtmsMachineProfile?,
    ): MetricSampler = EngineMetricPump(measurements, store, profileProvider)
}

/** The link for a bridge with no machine to command: never connected, every command refused. */
internal object NoUpstreamMachine : UpstreamMachineLink {
    private val refused = CommandResult.Rejected(RejectionReason.NOT_ACCEPTED)
    override val machineToken: UpstreamMachineRef? = null
    override fun machineIdentity(): MachineIdentity? = null
    override fun connectedAddresses(): Set<String> = emptySet()
    override val machineConnected: Boolean = false
    override fun startOrResume(token: UpstreamMachineRef) = refused
    override fun stopOrPause(token: UpstreamMachineRef) = refused
    override fun setTargetSpeedKph(token: UpstreamMachineRef, kph: Float) = refused
    override fun setTargetInclinePercent(token: UpstreamMachineRef, percent: Float) = refused
    override fun setTargetResistanceLevel(token: UpstreamMachineRef, level: Float) = refused
    override fun setTargetPowerWatts(token: UpstreamMachineRef, watts: Int) = refused
    override fun setWheelCircumferenceMm(token: UpstreamMachineRef, millimetres: Float) = refused
    override fun setIndoorBikeSimulation(
        token: UpstreamMachineRef, windSpeedMps: Float, gradePercent: Float,
        rollingResistanceCoefficient: Float, windResistanceCoefficient: Float,
    ) = refused
}
