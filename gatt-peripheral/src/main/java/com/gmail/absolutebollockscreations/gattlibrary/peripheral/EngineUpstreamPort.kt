package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.BtEngineState
import com.gmail.absolutebollockscreations.gattlibrary.central.BtServiceEngineFacade
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsMetricStore
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsUpstreamPort
import com.gmail.absolutebollockscreations.gattlibrary.server.MetricSampler
import com.gmail.absolutebollockscreations.gattlibrary.server.UpstreamControlSink

/** Bridge implementation of [FtmsUpstreamPort] over the central engine (moves off central in L24). */
class EngineUpstreamPort(
    private val engineState: BtEngineState,
    engineProvider: () -> BtServiceEngineFacade? = { null },
) : FtmsUpstreamPort {

    override val controlSink: UpstreamControlSink = EngineFtmsControlSink(engineProvider)

    override fun connectedCentralAddresses(): Set<String> =
        engineState.connectedDevices.value.mapTo(mutableSetOf()) { it.address.lowercase() }

    override fun supportsPaceMeasurement(): Boolean = engineState.supportsPaceMeasurement

    override fun createMetricSampler(
        store: FtmsMetricStore,
        profileProvider: () -> FtmsMachineProfile?,
    ): MetricSampler = EngineMetricPump(engineState, store, profileProvider)
}
