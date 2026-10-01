package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.BtEngineState
import com.gmail.absolutebollockscreations.gattlibrary.central.BtServiceEngineFacade
import com.gmail.absolutebollockscreations.gattlibrary.central.DeviceCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.central.MeasurementSnapshot
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsMetricStore
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsUpstreamPort
import com.gmail.absolutebollockscreations.gattlibrary.server.MetricSampler
import com.gmail.absolutebollockscreations.gattlibrary.server.UpstreamControlSink

/** Bridge implementation of [FtmsUpstreamPort] over the central engine (moves off central in L24). */
class EngineUpstreamPort(
    private val engineState: BtEngineState,
    /** Live read of the public client's measurements (`client.measurements.value`). */
    private val measurements: () -> MeasurementSnapshot,
    /** Live read of the public client capabilities (`client.capabilities.value`). */
    private val capabilities: () -> DeviceCapabilities,
    engineProvider: () -> BtServiceEngineFacade? = { null },
) : FtmsUpstreamPort {

    override val controlSink: UpstreamControlSink = EngineFtmsControlSink(engineProvider)

    override fun connectedCentralAddresses(): Set<String> =
        engineState.connectedDevices.value.mapTo(mutableSetOf()) { it.address.lowercase() }

    override fun supportsPaceMeasurement(): Boolean = capabilities().supportsPaceMeasurement

    override fun createMetricSampler(
        store: FtmsMetricStore,
        profileProvider: () -> FtmsMachineProfile?,
    ): MetricSampler = EngineMetricPump(measurements, store, profileProvider)
}
