package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine

/** What the UI shows for the connected machine while its capabilities are being read (LIB-9f). */
enum class MachineProbeState {
    /** No machine link. */
    IDLE,

    /** Linked; services or FTMS Feature/Supported-* Ranges still being read. Start is held. */
    PROBING,

    /** Probe finished (or timed out); the bridge can publish what it knows. Start is allowed. */
    READY,
}

/**
 * Pure probe decision for the connected machine (LIB-9f).
 *
 * The probe begins when the machine link connects. It covers two things:
 *  - GATT service discovery;
 *  - for a machine exposing the FTMS Feature characteristic (0x2ACC), the same readiness rule
 *    the bridge uses ([ProviderAdvertisingReadiness]): Feature parsed, and every claimed
 *    Supported-* Range read or waited for 5 s.
 *
 * Machines without 0x2ACC (Concept2 PM5, FE-C, Keiser and the other families) are ready as soon
 * as discovery is done: there is nothing further to probe. Whatever happens, the probe ends after
 * [PROBE_CAP_MS], so a machine that never answers cannot hold Start forever.
 */
internal object MachineProbe {

    /** Hard cap on PROBING. Covers the 10 s control-then-retry path for a failed 0x2ACC read. */
    const val PROBE_CAP_MS = 15_000L

    /** Watcher poll period. */
    const val POLL_MS = 250L

    /**
     * @param servicesDiscovered GATT discovery has completed on the machine link.
     * @param hasFtmsFeature the discovered FTMS service exposes 0x2ACC, so a feature probe runs.
     * @param readiness the bridge readiness decision for the current capability snapshot.
     * @param probeElapsedMs time since the machine link connected.
     */
    fun state(
        servicesDiscovered: Boolean,
        hasFtmsFeature: Boolean,
        readiness: ProviderAdvertisingReadiness.Decision,
        probeElapsedMs: Long,
    ): MachineProbeState = when {
        probeElapsedMs >= PROBE_CAP_MS -> MachineProbeState.READY
        !servicesDiscovered -> MachineProbeState.PROBING
        !hasFtmsFeature -> MachineProbeState.READY
        readiness == ProviderAdvertisingReadiness.Decision.Ready ||
            readiness is ProviderAdvertisingReadiness.Decision.ReadyWithoutRanges -> MachineProbeState.READY
        else -> MachineProbeState.PROBING
    }
}
