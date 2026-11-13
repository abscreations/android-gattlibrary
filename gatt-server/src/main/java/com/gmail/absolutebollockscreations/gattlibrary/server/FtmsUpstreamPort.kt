package com.gmail.absolutebollockscreations.gattlibrary.server

import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import kotlinx.coroutines.CoroutineScope

/** Periodically fills a [FtmsMetricStore] from whatever supplies live metrics (LIB-19f host port). */
public interface MetricSampler {
    public fun start(scope: CoroutineScope)

    public fun stop()

    /** Force one fresh sample, for status reads. */
    public fun sampleOnce()
}

/** A control sink whose target capability follows the lifetime of a start/stop session. */
public interface UpstreamControlSink : FtmsControlSink {
    /** Called when a session begins so advertised targets and registered ranges share one snapshot. */
    public fun beginSession(targets: TargetCapabilities)

    public fun endSession()
}

/**
 * Everything the FTMS server controller needs from the upstream machine, expressed without any
 * central type. The bridge implements it over the central engine; a test or the simulator can
 * implement it directly (L22: the server layer depends on core only).
 */
public interface FtmsUpstreamPort {
    /** Where Control Point writes go: the real machine, or an inert sink when none is attached. */
    public val controlSink: UpstreamControlSink

    /** Lower-cased addresses of devices currently connected in the central role. Read live. */
    public fun connectedCentralAddresses(): Set<String>

    /** Whether the upstream machine declared Pace Supported. Read live. */
    public fun supportsPaceMeasurement(): Boolean

    public fun createMetricSampler(
        store: FtmsMetricStore,
        profileProvider: () -> FtmsMachineProfile?,
    ): MetricSampler
}
