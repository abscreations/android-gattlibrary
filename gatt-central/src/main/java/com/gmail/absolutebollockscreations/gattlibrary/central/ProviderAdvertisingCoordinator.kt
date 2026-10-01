package com.gmail.absolutebollockscreations.gattlibrary.central

import com.gmail.absolutebollockscreations.gattlibrary.central.BtEngineState.FtmsCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Decides when, and with what, the downstream FTMS bridge starts advertising (unit 4b step 1b).
 *
 * This is the retry loop that used to live inside [BtEngine], moved without a change in
 * behaviour so it can be tested with virtual time. Everything it needs from the engine is
 * injected as a function, which is also what lets step 4 of 4b move it into the peripheral
 * module over public inputs (capabilities, connection state, session state).
 *
 * Behaviour, as before:
 *  - One loop per session. A second request only updates the wanted persona, which the running
 *    loop re-reads on every attempt.
 *  - 1 s wait while FEATURES (0x2ACC) is unread; 250 ms poll up to 5 s for claimed ranges
 *    ([ProviderAdvertisingReadiness]); 2 s backoff when the sink refuses or throws.
 *  - The loop ends when advertising starts, when the sink is lost, or when there is neither an
 *    upstream machine nor an active sensor-only session.
 *  - A sensor-only session (no machine, session active) advertises
 *    [sensorOnlyProviderCapabilities] without waiting for any machine reads.
 */
internal class ProviderAdvertisingCoordinator(
    private val scope: CoroutineScope,
    private val sink: () -> BtServiceControlSink?,
    private val hasUpstreamMachine: () -> Boolean,
    private val sessionActive: () -> Boolean,
    private val machineCapabilities: () -> FtmsCapabilities,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val ftmsEnabled: Boolean = ENABLE_BTSERVICE_FTMS,
    private val cscCpsEnabled: Boolean = ENABLE_BTSERVICE_CSCCPS,
) {
    @Volatile var advertisingStarted: Boolean = false
        private set
    @Volatile var advertisedProfile: FtmsMachineProfile? = null
        private set
    @Volatile private var wantedProfile: FtmsMachineProfile? = null
    @Volatile var loopRunning: Boolean = false
        private set

    /** Clear everything; called whenever the upstream link (the "session") ends or starts over. */
    fun reset() {
        advertisingStarted = false
        advertisedProfile = null
        wantedProfile = null
        loopRunning = false
    }

    /** Stop local advertising through the sink, swallowing and logging any failure. */
    fun stop(reason: String) {
        val current = sink() ?: return
        try {
            Timber.tag(BtEngineState.TAG).i("Stopping local advertising: %s", reason)
            current.stopAdvertising()
        } catch (t: Throwable) {
            Timber.tag(BtEngineState.TAG).w(t, "stopProviderAdvertising failed: %s", reason)
        }
    }

    /**
     * Record the wanted persona and start the retry loop unless one is running or advertising has
     * already started. Does not advertise immediately.
     */
    fun request(profile: FtmsMachineProfile?) {
        if (!ftmsEnabled) {
            Timber.tag(BtEngineState.TAG).d(
                "Provider advertising skipped: ENABLE_BTSERVICE_FTMS=false (no FTMS/CSC/CPS advertising)"
            )
            return
        }
        if (profile == null) {
            Timber.tag(BtEngineState.TAG).w(
                "Provider advertising skipped: profile=null (no inferred machine)"
            )
            return
        }

        wantedProfile = profile

        val startSink = sink() ?: run {
            Timber.tag(BtEngineState.TAG).d("Provider advertising skipped: no serviceControlSink")
            return
        }
        if (advertisingStarted) {
            Timber.tag(BtEngineState.TAG).d(
                "Provider advertising already started for %s",
                advertisedProfile
            )
            return
        }
        if (loopRunning) {
            Timber.tag(BtEngineState.TAG).d(
                "Provider advertising retry loop already running; updated profile=%s",
                profile
            )
            return
        }

        loopRunning = true
        Timber.tag(BtEngineState.TAG).d(
            "Provider advertising retry loop starting for profile=%s (sink=%s)",
            profile,
            startSink.javaClass.simpleName
        )
        scope.launch { runLoop() }
    }

    /**
     * Apply a user-selected persona. With no machine and no session the persona is only recorded.
     * Otherwise a different persona than the one being advertised stops advertising, waits for the
     * old loop to finish, and starts a new one.
     */
    fun overridePersona(profile: FtmsMachineProfile) {
        wantedProfile = profile
        if (!hasUpstreamMachine() && !sessionActive()) {
            // No machine and no session yet: record the persona and wait. A sensor-only
            // session starting will come back through here.
            Timber.tag(BtEngineState.TAG).i(
                "Session mode override=%s recorded without an upstream FTMS connection " +
                    "or an active session",
                profile,
            )
            return
        }
        if (advertisingStarted && advertisedProfile == profile) return

        if (advertisingStarted) {
            scope.launch {
                stop("session mode override to $profile")
                advertisingStarted = false
                advertisedProfile = null
                while (loopRunning) delay(25)
                request(profile)
            }
        } else {
            // An active retry loop reads the wanted persona on each attempt; this call is
            // otherwise responsible for starting a new loop.
            request(profile)
        }
    }

    private suspend fun runLoop() {
        var attempt = 0
        // Monotonic start of the wait for claimed Supported-* Ranges (LIB-9e); null until the
        // first check that finds one missing.
        var rangeWaitStartedAtMs: Long? = null
        try {
            while (ftmsEnabled && !advertisingStarted) {
                val currentSink = sink() ?: run {
                    Timber.tag(BtEngineState.TAG).w(
                        "Provider advertising retry: serviceControlSink lost; aborting loop"
                    )
                    break
                }

                val currentProfile = wantedProfile ?: run {
                    Timber.tag(BtEngineState.TAG).w(
                        "Provider advertising retry: profile=null; waiting for profile"
                    )
                    delay(1_000)
                    continue
                }

                // A sensor-only session has no upstream machine by definition, so neither the
                // link check nor the capability wait below can ever be satisfied. An RSC-only
                // session is the first that has no machine, and it must still advertise.
                val sensorOnly = !hasUpstreamMachine() && sessionActive()
                if (!sensorOnly && !hasUpstreamMachine()) {
                    Timber.tag(BtEngineState.TAG).d(
                        "Provider advertising retry: upstream FTMS not connected and no active " +
                            "sensor-only session; stopping loop"
                    )
                    break
                }

                val capabilities = if (sensorOnly) {
                    sensorOnlyProviderCapabilities()
                } else {
                    machineCapabilities()
                }
                val now = nowMs()
                val rangeWaitMs = rangeWaitStartedAtMs?.let { now - it } ?: 0L
                when (val readiness = ProviderAdvertisingReadiness.decide(capabilities, rangeWaitMs)) {
                    ProviderAdvertisingReadiness.Decision.WaitForFeatures -> {
                        Timber.tag(BtEngineState.TAG).d(
                            "Provider advertising retry: FEATURES not read yet (caps=%s); waiting",
                            capabilities
                        )
                        delay(1_000)
                        continue
                    }
                    is ProviderAdvertisingReadiness.Decision.WaitForRanges -> {
                        if (rangeWaitStartedAtMs == null) rangeWaitStartedAtMs = now
                        Timber.tag(BtEngineState.TAG).d(
                            "Provider advertising retry: waiting for Supported ranges %s (%d ms)",
                            readiness.missing,
                            rangeWaitMs
                        )
                        delay(ProviderAdvertisingReadiness.RANGE_POLL_MS)
                        continue
                    }
                    is ProviderAdvertisingReadiness.Decision.ReadyWithoutRanges ->
                        Timber.tag(BtEngineState.TAG).w(
                            "Provider advertising: Supported ranges %s not read after %d ms; " +
                                "bridging without those controls",
                            readiness.missing,
                            rangeWaitMs
                        )
                    ProviderAdvertisingReadiness.Decision.Ready -> Unit
                }

                attempt++
                Timber.tag(BtEngineState.TAG).i(
                    "Provider advertising retry attempt #%d: requesting local FTMS advertising " +
                        "(profile=%s, capabilities=%s)",
                    attempt,
                    currentProfile,
                    capabilities
                )

                val success: Boolean = try {
                    // false means "not ready yet"
                    currentSink.startAdvertisingFtms(currentProfile, capabilities)
                } catch (t: Throwable) {
                    Timber.tag(BtEngineState.TAG).w(
                        t,
                        "Provider advertising retry attempt #%d threw; will retry",
                        attempt
                    )
                    false
                }

                if (success) {
                    advertisedProfile = currentProfile
                    advertisingStarted = true
                    Timber.tag(BtEngineState.TAG).i(
                        "Provider advertising: FTMS advertising started successfully on attempt #%d",
                        attempt
                    )
                    if (cscCpsEnabled) {
                        Timber.tag(BtEngineState.TAG).i(
                            "ENABLE_BTSERVICE_CSCCPS=true → also starting local CSC+CPS advertising"
                        )
                        runCatching { currentSink.startAdvertisingCscCps() }
                            .onFailure {
                                Timber.tag(BtEngineState.TAG).w(
                                    it,
                                    "Failed to start CSC+CPS advertising alongside FTMS"
                                )
                            }
                    }
                    break
                } else {
                    Timber.tag(BtEngineState.TAG).d(
                        "Provider advertising retry attempt #%d: startAdvertisingFtms returned false; " +
                            "btService not ready yet (capabilities may still be converging). Retrying…",
                        attempt
                    )
                    // Simple backoff to avoid hammering the stack if btService refuses.
                    delay(2_000)
                }
            }
        } finally {
            loopRunning = false
            Timber.tag(BtEngineState.TAG).d(
                "Provider advertising retry loop finished (advertisingStarted=%s, connected=%s)",
                advertisingStarted,
                hasUpstreamMachine()
            )
        }
    }
}
