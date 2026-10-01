package com.gmail.absolutebollockscreations.gattlibrary.peripheral

import com.gmail.absolutebollockscreations.gattlibrary.central.ConnectionStatus
import com.gmail.absolutebollockscreations.gattlibrary.central.DeviceCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.central.FitnessGattClient
import com.gmail.absolutebollockscreations.gattlibrary.core.ActivityType
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsMachineProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Decides when the downstream FTMS bridge is on the air and which machine it impersonates, from what
 * a [FitnessGattClient] publishes (unit 4b step 4a-3). It replaces the engine-driven advertising the
 * engine used to run through `BtServiceControlSink`.
 *
 * Persona. By default the persona is the client's [FitnessGattClient.inferredActivity], the closest
 * match the connected machine was classified as (a ski machine as rowing, for example). The app can
 * choose a different one for the session with [overrideActivity]. An override sticks: the inferred
 * value only triggers a request when it changes, so a repeat classification does not replace it.
 *
 * Lifecycle, derived from the client rather than called by the engine:
 *  - A classified machine (inferred activity becomes non-null) requests advertising. The loop in
 *    [ProviderAdvertisingCoordinator] waits for the machine's capabilities to settle.
 *  - The machine link leaving CONNECTED stops advertising. It does not reset: after a GATT error or a
 *    remote drop the old engine also stopped without resetting, so a recovered link does not restart
 *    advertising by itself. That is preserved here and is a known gap, not a decision.
 *  - The inferred activity returning to null after having been set (a new machine connection, an explicit disconnect or a
 *    release) resets, so the next classification starts advertising afresh.
 *  - [close] stops advertising and ends everything.
 *
 * Calls into the [AdvertisingTarget] can block for several seconds, so [create] runs on a background
 * dispatcher.
 */
public class FtmsBridgeAdvertising internal constructor(
    scope: CoroutineScope,
    target: () -> AdvertisingTarget?,
    inferredActivity: Flow<ActivityType?>,
    machineConnected: Flow<Boolean>,
    hasUpstreamMachine: () -> Boolean,
    sessionActive: () -> Boolean,
    machineCapabilities: () -> DeviceCapabilities,
    nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    nativeCyclingSensors: Boolean = false,
) {
    @Volatile private var nativeSensors: Boolean = nativeCyclingSensors
    private val job = SupervisorJob(scope.coroutineContext[Job])
    private val ownScope = CoroutineScope(scope.coroutineContext + job)
    private val coordinator = ProviderAdvertisingCoordinator(
        scope = ownScope,
        sink = target,
        hasUpstreamMachine = hasUpstreamMachine,
        sessionActive = sessionActive,
        machineCapabilities = machineCapabilities,
        nowMs = nowMs,
        nativeCyclingSensors = { nativeSensors },
    )

    init {
        ownScope.launch {
            var classified = false
            inferredActivity.collect { activity ->
                if (activity != null) {
                    coordinator.request(activity.toFtmsProfile())
                } else if (classified) {
                    // Only a classification going away resets. The initial "nothing yet" must not,
                    // or it would wipe a persona the app chose before this collector first ran.
                    coordinator.reset()
                }
                classified = activity != null
            }
        }
        ownScope.launch {
            var wasConnected = false
            machineConnected.collect { connected ->
                if (wasConnected && !connected) coordinator.stop("machine link left CONNECTED")
                wasConnected = connected
            }
        }
    }

    /**
     * Impersonate [activity] for this session instead of the inferred default. A heart-rate-only
     * activity has no FTMS persona and is ignored. With no machine and no session yet the choice is
     * only recorded; a different persona than the one being advertised restarts advertising.
     */
    public fun overrideActivity(activity: ActivityType) {
        val profile = activity.toFtmsProfile()
        if (profile == null) {
            Timber.tag(TAG).i("Session mode override is HRM-only; no FTMS persona override applied")
            return
        }
        coordinator.overridePersona(profile)
    }

    /** Whether native CSC and Cycling Power services are published alongside FTMS for the bike persona. */
    public val nativeCyclingSensors: Boolean get() = nativeSensors

    /**
     * Publish native Cycling Speed and Cadence plus Cycling Power services alongside FTMS while the
     * advertised persona is the indoor bike; other personas ignore it. Off by default. Changing it
     * while the bike persona is on the air restarts advertising, because the services are part of the
     * GATT database, which drops any downstream client that is connected.
     */
    public fun setNativeCyclingSensors(enabled: Boolean) {
        if (nativeSensors == enabled) return
        nativeSensors = enabled
        coordinator.restartIfAdvertising(FtmsMachineProfile.INDOOR_BIKE)
    }

    /** Stop advertising and stop reacting to the client. Safe to call more than once. */
    public fun close() {
        coordinator.stop("closed")
        job.cancel()
    }

    public companion object {
        private const val TAG = "FtmsBridgeAdvertising"

        /** Drive [target] from [client]. [scope] bounds the lifetime; the work itself runs on a background dispatcher. */
        public fun create(
            client: FitnessGattClient,
            target: () -> AdvertisingTarget?,
            scope: CoroutineScope,
            nativeCyclingSensors: Boolean = false,
        ): FtmsBridgeAdvertising = FtmsBridgeAdvertising(
            scope = CoroutineScope(scope.coroutineContext + Dispatchers.Default),
            target = target,
            inferredActivity = client.inferredActivity,
            machineConnected = client.connections.map { it.machineStatus == ConnectionStatus.CONNECTED },
            hasUpstreamMachine = { client.connections.value.machineStatus == ConnectionStatus.CONNECTED },
            sessionActive = { client.sessionActive.value },
            machineCapabilities = { client.capabilities.value },
            nativeCyclingSensors = nativeCyclingSensors,
        )
    }
}

/** Map the consumer's activity to the persona the bridge advertises; heart-rate-only has none. */
internal fun ActivityType.toFtmsProfile(): FtmsMachineProfile? = when (this) {
    ActivityType.TREADMILL -> FtmsMachineProfile.TREADMILL
    ActivityType.ELLIPTICAL -> FtmsMachineProfile.ELLIPTICAL
    ActivityType.ROWING -> FtmsMachineProfile.INDOOR_ROWER
    ActivityType.BIKING -> FtmsMachineProfile.INDOOR_BIKE
    ActivityType.STAIRCLIMBER -> FtmsMachineProfile.STAIR_CLIMBER
    ActivityType.STAIRSTEPPER -> FtmsMachineProfile.STAIR_STEPPER
    ActivityType.HEARTRATE -> null
}
