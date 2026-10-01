package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.DeviceCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Settled capabilities for a session supplied only by sensors: nothing to read or control, and the
 * answer is final. Equal to the engine's `sensorOnlyProviderCapabilities()` once converted.
 */
internal val SENSOR_ONLY_CAPABILITIES = DeviceCapabilities(featuresRead = true)

private const val TAG = "ProviderAdvertising"

/**
 * Decides when, and with what, the downstream FTMS bridge starts advertising (unit 4b step 4a-2).
 *
 * The retry loop that lived in `BtEngine` (and, since 4b step 1b, in gatt-central), moved here over
 * public inputs: the machine's published [DeviceCapabilities], its connection state and the session
 * flag. Everything is injected as a function so it can be tested with virtual time.
 *
 * Differences from the central version: it drives an [AdvertisingTarget] instead of
 * `BtServiceControlSink`; the compile-time `ENABLE_BTSERVICE_FTMS` kill switch (always true) and the
 * dead CSC/CPS follow-up start are gone. Otherwise the behaviour is the same:
 *  - One loop per session. A second request only updates the wanted persona, which the running
 *    loop re-reads on every attempt.
 *  - 1 s wait while FEATURES (0x2ACC) is unread; 250 ms poll up to 5 s for claimed ranges
 *    ([ProviderAdvertisingReadiness]); 2 s backoff when the sink refuses or throws.
 *  - The loop ends when advertising starts, when the sink is lost, or when there is neither an
 *    upstream machine nor an active sensor-only session.
 *  - A sensor-only session (no machine, session active) advertises [SENSOR_ONLY_CAPABILITIES]
 *    without waiting for any machine reads.
 */
internal class ProviderAdvertisingCoordinator(
    private val scope: CoroutineScope,
    private val sink: () -> AdvertisingTarget?,
    private val hasUpstreamMachine: () -> Boolean,
    private val sessionActive: () -> Boolean,
    private val machineCapabilities: () -> DeviceCapabilities,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
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
            Timber.tag(TAG).i("Stopping local advertising: %s", reason)
            current.stop()
        } catch (t: Throwable) {
            Timber.tag(TAG).w(t, "stopProviderAdvertising failed: %s", reason)
        }
    }

    /**
     * Record the wanted persona and start the retry loop unless one is running or advertising has
     * already started. Does not advertise immediately.
     */
    fun request(profile: FtmsMachineProfile?) {
        if (profile == null) {
            Timber.tag(TAG).w(
                "Provider advertising skipped: profile=null (no inferred machine)"
            )
            return
        }

        wantedProfile = profile

        val startSink = sink() ?: run {
            Timber.tag(TAG).d("Provider advertising skipped: no advertising target")
            return
        }
        if (advertisingStarted) {
            Timber.tag(TAG).d(
                "Provider advertising already started for %s",
                advertisedProfile
            )
            return
        }
        if (loopRunning) {
            Timber.tag(TAG).d(
                "Provider advertising retry loop already running; updated profile=%s",
                profile
            )
            return
        }

        loopRunning = true
        Timber.tag(TAG).d(
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
            Timber.tag(TAG).i(
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
            while (!advertisingStarted) {
                val currentSink = sink() ?: run {
                    Timber.tag(TAG).w(
                        "Provider advertising retry: advertising target lost; aborting loop"
                    )
                    break
                }

                val currentProfile = wantedProfile ?: run {
                    Timber.tag(TAG).w(
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
                    Timber.tag(TAG).d(
                        "Provider advertising retry: upstream FTMS not connected and no active " +
                            "sensor-only session; stopping loop"
                    )
                    break
                }

                val capabilities = if (sensorOnly) {
                    SENSOR_ONLY_CAPABILITIES
                } else {
                    machineCapabilities()
                }
                val now = nowMs()
                val rangeWaitMs = rangeWaitStartedAtMs?.let { now - it } ?: 0L
                when (val readiness = ProviderAdvertisingReadiness.decide(capabilities, rangeWaitMs)) {
                    ProviderAdvertisingReadiness.Decision.WaitForFeatures -> {
                        Timber.tag(TAG).d(
                            "Provider advertising retry: FEATURES not read yet (caps=%s); waiting",
                            capabilities
                        )
                        delay(1_000)
                        continue
                    }
                    is ProviderAdvertisingReadiness.Decision.WaitForRanges -> {
                        if (rangeWaitStartedAtMs == null) rangeWaitStartedAtMs = now
                        Timber.tag(TAG).d(
                            "Provider advertising retry: waiting for Supported ranges %s (%d ms)",
                            readiness.missing,
                            rangeWaitMs
                        )
                        delay(ProviderAdvertisingReadiness.RANGE_POLL_MS)
                        continue
                    }
                    is ProviderAdvertisingReadiness.Decision.ReadyWithoutRanges ->
                        Timber.tag(TAG).w(
                            "Provider advertising: Supported ranges %s not read after %d ms; " +
                                "bridging without those controls",
                            readiness.missing,
                            rangeWaitMs
                        )
                    ProviderAdvertisingReadiness.Decision.Ready -> Unit
                }

                attempt++
                Timber.tag(TAG).i(
                    "Provider advertising retry attempt #%d: requesting local FTMS advertising " +
                        "(profile=%s, capabilities=%s)",
                    attempt,
                    currentProfile,
                    capabilities
                )

                val success: Boolean = try {
                    // false means "not ready yet"
                    currentSink.startFtms(currentProfile, capabilities)
                } catch (t: Throwable) {
                    Timber.tag(TAG).w(
                        t,
                        "Provider advertising retry attempt #%d threw; will retry",
                        attempt
                    )
                    false
                }

                if (success) {
                    advertisedProfile = currentProfile
                    advertisingStarted = true
                    Timber.tag(TAG).i(
                        "Provider advertising: FTMS advertising started successfully on attempt #%d",
                        attempt
                    )
                    break
                } else {
                    Timber.tag(TAG).d(
                        "Provider advertising retry attempt #%d: startFtms returned false; " +
                            "btService not ready yet (capabilities may still be converging). Retrying…",
                        attempt
                    )
                    // Simple backoff to avoid hammering the stack if btService refuses.
                    delay(2_000)
                }
            }
        } finally {
            loopRunning = false
            Timber.tag(TAG).d(
                "Provider advertising retry loop finished (advertisingStarted=%s, connected=%s)",
                advertisingStarted,
                hasUpstreamMachine()
            )
        }
    }
}
