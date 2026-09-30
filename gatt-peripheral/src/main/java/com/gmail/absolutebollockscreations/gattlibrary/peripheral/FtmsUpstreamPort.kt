package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import kotlinx.coroutines.CoroutineScope

/** Periodically fills a [FtmsMetricStore] from whatever supplies live metrics (LIB-19f host port). */
interface MetricSampler {
    fun start(scope: CoroutineScope)

    fun stop()

    /** Force one fresh sample, for status reads. */
    fun sampleOnce()
}

/** A control sink whose target capability follows the lifetime of a start/stop session. */
interface UpstreamControlSink : FtmsControlSink {
    /** Called when a session begins so advertised targets and registered ranges share one snapshot. */
    fun beginSession(targets: TargetCapabilities)

    fun endSession()
}

/**
 * Everything the FTMS server controller needs from the upstream machine, expressed without any
 * central type. The bridge implements it over the central engine; a test or the simulator can
 * implement it directly (L22: the server layer depends on core only).
 */
interface FtmsUpstreamPort {
    /** Where Control Point writes go: the real machine, or an inert sink when none is attached. */
    val controlSink: UpstreamControlSink

    /** Lower-cased addresses of devices currently connected in the central role. Read live. */
    fun connectedCentralAddresses(): Set<String>

    /** Whether the upstream machine declared Pace Supported. Read live. */
    fun supportsPaceMeasurement(): Boolean

    fun createMetricSampler(
        store: FtmsMetricStore,
        profileProvider: () -> FtmsMachineProfile?,
    ): MetricSampler
}
