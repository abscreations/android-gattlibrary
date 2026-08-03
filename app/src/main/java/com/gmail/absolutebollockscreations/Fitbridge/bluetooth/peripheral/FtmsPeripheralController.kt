package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtEngineState
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtServiceEngineFacade
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtServiceProfiles.FtmsMachineProfile
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Owns the peripheral lifecycle and the adapter-name save/restore dance.
 *
 * A start is treated as a transaction:
 *
 *   adapter name -> GATT database -> advertising -> foreground-service ack
 *   -> metric pump -> notifier -> RUNNING
 *
 * Failures roll those resources back in reverse ownership order. Asynchronous
 * callbacks carry an [operationEpoch] so a late success cannot revive a start
 * that a newer stop, retry, persona change, or adapter-off event superseded.
 *
 * The old `BtServiceManager` had none of this: `startFtmsAdvertising` set
 * `isAdvertising = true` on a synchronous Boolean, kept no epoch, had no
 * foreground service (so the GATT server died whenever Android trimmed the
 * backgrounded process), and renamed the Bluetooth adapter without ever
 * restoring it.
 */
class FtmsPeripheralController(
    private val context: Context,
    private val engineState: BtEngineState,
    private val engineProvider: () -> BtServiceEngineFacade? = { null },
    private val onStatus: (PeripheralStatus) -> Unit = {},
) : FtmsPeripheralAdvertiser.Listener {

    data class PeripheralStatus(
        val advertising: Boolean = false,
        val connected: Boolean = false,
        val clientId: String? = null,
        val subscribed: Boolean = false,
        val machineType: String? = null,
        val advertisedName: String? = null,
        val packetsSent: Long = 0,
        val metricsReceived: Long = 0,
        val completeRecords: Long = 0,
        val compactedRecords: Long = 0,
        val fragmentedRecords: Long = 0,
        val nativeBikingSensorsEnabled: Boolean = false,
        val wheelCircumferenceMm: Int? = null,
        val ftmsSubscribed: Boolean = false,
        val cscSubscribed: Boolean = false,
        val cpsSubscribed: Boolean = false,
        val ftmsPacketsSent: Long = 0,
        val cscPacketsSent: Long = 0,
        val cpsPacketsSent: Long = 0,
        val cumulativeWheelRevolutions: Long = 0,
        val cumulativeCrankRevolutions: Int = 0,
        val lastDisconnectStatus: Int? = null,
        val lastDisconnectReason: String? = null,
        val lastError: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())

    private val engineControlSink = EngineFtmsControlSink(engineProvider)

    /**
     * Control Point target. Normally the real machine; the data simulator swaps
     * itself in so a watch's target writes steer the synthetic machine.
     */
    private val controlSink = SwitchableFtmsControlSink(engineControlSink)
    private val store = FtmsMetricStore()

    private val advertiser = FtmsPeripheralAdvertiser(
        context = context,
        controlSink = controlSink,
        listener = this,
    )
    private val notifier = FtmsPeripheralNotifier(advertiser, store)
    private val pump = EngineMetricPump(engineState, store) { profile }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private data class DesiredStart(
        val profile: FtmsMachineProfile,
        val upstreamAddress: String?,
        val targets: TargetCapabilities,
        val cyclingConfig: CyclingSensorConfig,
    )

    /**
     * User/workout intent, kept separate from [runState].
     *
     * Temporary stack failures and an adapter power cycle set runState to STOPPED
     * but retain this value, allowing a bounded automatic restart. Explicit
     * stop/dispose clears it and therefore cancels that intent.
     */
    @Volatile
    private var desiredStart: DesiredStart? = null
    private var retryAttempt = 0
    private var retryRunnable: Runnable? = null
    private var adapterReceiverRegistered = false

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF,
                BluetoothAdapter.STATE_OFF,
                -> {
                    operationEpoch.incrementAndGet()
                    cancelRetry()
                    stopResources(publishIdle = false)
                    publish(PeripheralStatus(lastError = "Bluetooth adapter is off"))
                }

                BluetoothAdapter.STATE_ON -> restartDesired("adapter-on")
            }
        }
    }

    private enum class RunState { STOPPED, STARTING, RUNNING, STOPPING }

    /** Generation fence for advertiser and foreground-service callbacks. */
    private val operationEpoch = AtomicLong(0L)

    @Volatile
    private var runState: RunState = RunState.STOPPED

    @Volatile
    private var status = PeripheralStatus()

    val isRunning: Boolean
        get() = runState == RunState.RUNNING

    val isAdvertising: Boolean
        get() = advertiser.isAdvertising

    @Volatile
    private var profile: FtmsMachineProfile? = null
    private var targetCapabilities: TargetCapabilities = TargetCapabilities.NONE
    private var cyclingConfig: CyclingSensorConfig = CyclingSensorConfig.DISABLED

    init {
        // If the process died mid-session the phone is still named "Treadmill".
        // Put it back before doing anything else.
        restoreAdapterNameIfStranded()
        registerAdapterReceiver()
    }

    // ── public API ────────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun start(
        profile: FtmsMachineProfile,
        upstreamAddress: String?,
        targets: TargetCapabilities,
        cyclingConfig: CyclingSensorConfig,
        onResult: (Result<Unit>) -> Unit,
    ) {
        val effectiveCyclingConfig = cyclingConfig.effectiveFor(profile)
        desiredStart = DesiredStart(profile, upstreamAddress, targets, effectiveCyclingConfig)
        if (
            runState == RunState.RUNNING && this.profile == profile &&
            targetCapabilities == targets && this.cyclingConfig == effectiveCyclingConfig
        ) {
            advertiser.updateUpstreamAddress(upstreamAddress)
            onResult(Result.success(Unit))
            return
        }
        if (runState == RunState.STARTING && this.profile == profile) {
            onResult(Result.failure(IllegalStateException("FTMS peripheral is already starting")))
            return
        }

        val epoch = operationEpoch.incrementAndGet()
        if (runState != RunState.STOPPED || this.profile != null) {
            stopResources(publishIdle = false)
        }

        if (!hasAdvertisePermission()) {
            val e = IllegalStateException("Bluetooth advertising permission has not been granted")
            publish(status.copy(lastError = e.message))
            onResult(Result.failure(e))
            return
        }

        this.profile = profile
        targetCapabilities = targets
        this.cyclingConfig = effectiveCyclingConfig
        notifier.configureCycling(effectiveCyclingConfig)
        engineControlSink.beginSession(targets)
        runState = RunState.STARTING

        // Publication order matters. Android advertising includes the global
        // adapter name, and the notifier must not run until both the GATT server
        // and the foreground lifetime guarantee are established.
        val nameResult = setAdapterName(profile.advertisedName)
        if (nameResult.isFailure) {
            val failure = nameResult.exceptionOrNull()!!
            rollbackSessionState()
            publish(PeripheralStatus(lastError = failure.message))
            onResult(Result.failure(failure))
            return
        }

        advertiser.start(
            profile = profile,
            targets = targets,
            includeTrainingStatus = true,
            cyclingConfig = effectiveCyclingConfig,
            upstreamAddress = upstreamAddress,
        ) startCallback@{ result ->
            if (operationEpoch.get() != epoch) {
                onResult(Result.failure(CancellationException("FTMS start was superseded")))
                return@startCallback
            }
            result.fold(
                onSuccess = {
                    FtmsForegroundService.start(
                        context,
                        title = "Sharing as ${profile.advertisedName}",
                        text = "Other apps and watches can read this workout",
                    ) foregroundCallback@{ foregroundResult ->
                        if (operationEpoch.get() != epoch) {
                            onResult(
                                Result.failure(
                                    CancellationException("FTMS start was superseded"),
                                ),
                            )
                            return@foregroundCallback
                        }
                        foregroundResult.fold(
                            onSuccess = {
                                runState = RunState.RUNNING
                                retryAttempt = 0
                                cancelRetry()
                                pump.start(scope)
                                notifier.start(scope)
                                publish(
                                    status.copy(
                                        advertising = advertiser.isAdvertising,
                                        connected = advertiser.hasClients(),
                                        clientId = advertiser.firstClientAddress(),
                                        machineType = profile.wireName,
                                        advertisedName = profile.advertisedName,
                                        nativeBikingSensorsEnabled = effectiveCyclingConfig.enabled,
                                        wheelCircumferenceMm = effectiveCyclingConfig
                                            .wheelCircumferenceMm
                                            .takeIf { effectiveCyclingConfig.enabled },
                                        lastError = null,
                                    ),
                                )
                                onResult(Result.success(Unit))
                            },
                            onFailure = { failure ->
                                Timber.tag(TAG).w(failure, "foreground service start failed")
                                runCatching { advertiser.stop() }
                                FtmsForegroundService.stop(context)
                                restoreAdapterName()
                                store.clear()
                                rollbackSessionState()
                                publish(PeripheralStatus(lastError = failure.message))
                                onResult(Result.failure(failure))
                                scheduleRetryIfRecoverable(failure)
                            },
                        )
                    }
                },
                onFailure = { t ->
                    Timber.tag(TAG).w(t, "start failed")
                    restoreAdapterName()
                    rollbackSessionState()
                    publish(PeripheralStatus(lastError = t.message))
                    onResult(Result.failure(t))
                    scheduleRetryIfRecoverable(t)
                },
            )
        }
    }

    private fun rollbackSessionState() {
        profile = null
        targetCapabilities = TargetCapabilities.NONE
        cyclingConfig = CyclingSensorConfig.DISABLED
        notifier.configureCycling(CyclingSensorConfig.DISABLED)
        engineControlSink.endSession()
        runState = RunState.STOPPED
    }

    /**
     * The upstream address identifies a central-role link that must not consume a
     * downstream client slot. It is not part of the FTMS persona, so changing it
     * must never rebuild the GATT database.
     */
    fun updateUpstreamAddress(upstreamAddress: String?) {
        desiredStart = desiredStart?.copy(upstreamAddress = upstreamAddress)
        advertiser.updateUpstreamAddress(upstreamAddress)
    }

    fun setGarminCompatibilityFraming(enabled: Boolean) {
        notifier.garminCompatibilityFraming = enabled
        advertiser.setGarminCompatibility(enabled)
    }

    fun setNotifyIntervalMs(intervalMs: Long) {
        notifier.periodMs = intervalMs
    }

    /**
     * Redirect FTMS Control Point procedures away from the real machine.
     *
     * Used by the data simulator so a watch can take control of, and actually
     * steer, a synthetic machine. Pass null to hand control back to the engine.
     */
    fun setControlOverride(sink: FtmsControlSink?) {
        controlSink.override = sink
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        desiredStart = null
        cancelRetry()
        operationEpoch.incrementAndGet()
        stopResources(publishIdle = true)
    }

    @SuppressLint("MissingPermission")
    private fun stopResources(publishIdle: Boolean) {
        if (runState == RunState.STOPPED && profile == null) {
            targetCapabilities = TargetCapabilities.NONE
            cyclingConfig = CyclingSensorConfig.DISABLED
            notifier.configureCycling(CyclingSensorConfig.DISABLED)
            engineControlSink.endSession()
            FtmsForegroundService.stop(context)
            restoreAdapterName()
            store.clear()
            if (publishIdle) publish(PeripheralStatus())
            return
        }
        runState = RunState.STOPPING
        // Stop producers before consumers: cancel ticks first, then close the
        // queue/server, then release process lifetime and identity. Clear the
        // store last so an in-flight notifier cannot publish a torn-down sample.
        pump.stop()
        notifier.stop()
        runCatching { advertiser.stop() }
        FtmsForegroundService.stop(context)
        restoreAdapterName()
        store.clear()
        rollbackSessionState()
        if (publishIdle) publish(PeripheralStatus())
    }

    fun dispose() {
        desiredStart = null
        cancelRetry()
        operationEpoch.incrementAndGet()
        stopResources(publishIdle = true)
        advertiser.dispose()
        unregisterAdapterReceiver()
        scope.cancel()
    }

    fun currentStatus(): PeripheralStatus {
        val revolutions = notifier.cyclingRevolutions()
        return status.copy(
            packetsSent = notifier.packetsSent,
            metricsReceived = store.updateCount,
            completeRecords = notifier.completeRecords,
            compactedRecords = notifier.compactedRecords,
            fragmentedRecords = notifier.fragmentedRecords,
            ftmsSubscribed = advertiser.anyFtmsSubscribed(),
            cscSubscribed = advertiser.anyCscSubscribed(),
            cpsSubscribed = advertiser.anyCpsSubscribed(),
            ftmsPacketsSent = notifier.ftmsPacketsSent,
            cscPacketsSent = notifier.cscPacketsSent,
            cpsPacketsSent = notifier.cpsPacketsSent,
            cumulativeWheelRevolutions = revolutions.cumulativeWheelRevolutions,
            cumulativeCrankRevolutions = revolutions.cumulativeCrankRevolutions,
        )
    }

    // ── upstream-originated Fitness Machine Status mirroring ──────────────────
    //
    // These preserve a FitBridge capability the TrackMyIndoorWorkout peripheral
    // does not have: the real machine can change state on its own (safety key
    // pulled, console-set target, control taken back), and downstream clients
    // must hear about it. Every one now goes through the serialised TX queue and
    // only to clients that actually subscribed to 0x2ADA.

    fun emitStatusControlPermissionLost() {
        // The machine has taken control back. Drop every downstream client's
        // control ownership so a subsequent target write is answered with
        // CONTROL_NOT_PERMITTED rather than being forwarded and ignored.
        advertiser.revokeAllControl()
        broadcastStatus(byteArrayOf(FtmsSpec.Status.CONTROL_PERMISSION_LOST))
    }

    fun emitStatusSafetyKeyPulled() {
        broadcastStatus(byteArrayOf(FtmsSpec.Status.STOPPED_BY_SAFETY_KEY))
    }

    fun emitStatusTargetSpeedChanged(kmh: Float) {
        broadcastStatus(
            byteArrayOf(FtmsSpec.Status.TARGET_SPEED_CHANGED) +
                u16le((kmh * 100f).toInt().coerceIn(0, 0xFFFF)),
        )
    }

    fun emitStatusTargetInclineChanged(percent: Float) {
        broadcastStatus(
            byteArrayOf(FtmsSpec.Status.TARGET_INCLINE_CHANGED) +
                s16le((percent * 10f).toInt()),
        )
    }

    fun emitStatusTargetResistanceLevelChanged(level: Float) {
        broadcastStatus(
            byteArrayOf(FtmsSpec.Status.TARGET_RESISTANCE_CHANGED) +
                u8((level * 10f).toInt().coerceIn(0, 0xFF)),
        )
    }

    fun emitStatusTargetPowerChanged(watts: Int) {
        broadcastStatus(byteArrayOf(FtmsSpec.Status.TARGET_POWER_CHANGED) + s16le(watts))
    }

    fun emitStatusTargetHeartRateChanged(bpm: Int) {
        broadcastStatus(
            byteArrayOf(FtmsSpec.Status.TARGET_HEART_RATE_CHANGED) + u8(bpm.coerceIn(0, 0xFF)),
        )
    }

    private fun broadcastStatus(payload: ByteArray) {
        if (runState != RunState.RUNNING) return
        scope.launch {
            runCatching { advertiser.broadcastMachineStatus(payload) }
                .onFailure { Timber.tag(TAG).w(it, "machine status broadcast failed") }
        }
    }

    // ── FtmsPeripheralAdvertiser.Listener ─────────────────────────────────────

    override fun onClientConnected(device: BluetoothDevice) {
        // Advertising keeps running with clients attached, so reflect its real
        // state rather than forcing it false.
        publish(
            status.copy(
                advertising = advertiser.isAdvertising,
                connected = true,
                clientId = advertiser.firstClientAddress() ?: device.address,
            ),
        )
    }

    override fun onClientDisconnected(device: BluetoothDevice, status: Int) {
        // Other clients may still be attached.
        publish(
            this.status.copy(
                connected = advertiser.hasClients(),
                clientId = advertiser.firstClientAddress(),
                subscribed = advertiser.anyDataSubscribed(),
                lastDisconnectStatus = status,
                lastDisconnectReason = disconnectStatusName(status),
            ),
        )
    }

    override fun onSubscriptionChanged(connection: PeripheralConnection) {
        publish(status.copy(subscribed = advertiser.anyDataSubscribed()))
        if (connection.anyMeasurementSubscribed) {
            // Do not make the client wait a full period for its first packet.
            pump.sampleOnce()
            notifier.kick(scope)
        }
    }

    override fun onProcedureReset() {
        store.clear()
        notifier.configureCycling(cyclingConfig)
        publish(status.copy())
    }

    override fun onCscSetCumulativeWheelRevolutions(value: Long): Boolean =
        notifier.setCumulativeWheelRevolutions(value)

    override fun onAdvertisingStateChanged(advertising: Boolean, error: String?) {
        publish(status.copy(advertising = advertising, lastError = error ?: status.lastError))
    }

    // ── adapter name ──────────────────────────────────────────────────────────

    /**
     * Android has no per-advertisement name API: setIncludeDeviceName(true)
     * publishes the GLOBAL adapter name. So we rename the phone while advertising
     * and put it back afterwards.
     *
     * The original is persisted rather than held in memory because a process death
     * would otherwise leave the user's phone permanently called "Treadmill" in
     * every Bluetooth context. The old advertiser set the name and never restored
     * it at all.
     */
    @SuppressLint("MissingPermission", "HardwareIds")
    private fun setAdapterName(name: String): Result<Unit> {
        if (!hasConnectPermission()) {
            return Result.failure(
                IllegalStateException("Bluetooth connect permission has not been granted"),
            )
        }
        val adapter =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                ?: return Result.failure(
                    IllegalStateException("Bluetooth adapter is unavailable"),
                )
        return runCatching {
            val current = adapter.name
            if (current == name) return@runCatching
            if (prefs.getString(KEY_ORIGINAL_NAME, null) == null && current != null) {
                check(prefs.edit().putString(KEY_ORIGINAL_NAME, current).commit()) {
                    "Could not persist the original Bluetooth adapter name"
                }
            }
            // adapter.setName() is asynchronous on modern Android: it returns true
            // once the request is accepted, but adapter.name is not updated until
            // the stack applies it a moment later. Verifying adapter.name == name
            // synchronously here races that update. Trust the accepted request; the
            // global name propagates before it matters to the advertisement.
            check(adapter.setName(name)) { "Android rejected the Bluetooth adapter name change" }
            Timber.tag(TAG).i("Bluetooth adapter name change requested for FTMS advertising")
        }
    }

    @SuppressLint("MissingPermission")
    private fun restoreAdapterName() {
        val original = prefs.getString(KEY_ORIGINAL_NAME, null) ?: return
        if (!hasConnectPermission()) return
        val adapter =
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                ?: return
        runCatching {
            // Asynchronous, exactly as in setAdapterName: do not verify
            // adapter.name == original synchronously - that race is what leaves the
            // saved name stranded and re-firing every launch. Clear the saved
            // original once the restore request is accepted.
            check(adapter.setName(original)) { "Android rejected adapter-name restoration" }
            check(prefs.edit().remove(KEY_ORIGINAL_NAME).commit()) {
                "Could not clear the saved original adapter name"
            }
            Timber.tag(TAG).i("Bluetooth adapter name restoration requested")
        }.onFailure {
            Timber.tag(TAG).w(it, "could not restore adapter name; retaining saved name for retry")
        }
    }

    private fun restoreAdapterNameIfStranded() {
        if (prefs.getString(KEY_ORIGINAL_NAME, null) != null) {
            Timber.tag(TAG).i("found a stranded adapter name from a previous session; restoring")
            restoreAdapterName()
        }
    }

    private fun registerAdapterReceiver() {
        if (adapterReceiverRegistered) return
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(adapterReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(adapterReceiver, filter)
        }
        adapterReceiverRegistered = true
    }

    private fun unregisterAdapterReceiver() {
        if (!adapterReceiverRegistered) return
        runCatching { context.unregisterReceiver(adapterReceiver) }
        adapterReceiverRegistered = false
    }

    private fun restartDesired(reason: String) {
        val desired = desiredStart ?: return
        if (runState != RunState.STOPPED) return
        Timber.tag(TAG).i("restarting intended FTMS peripheral after %s", reason)
        start(
            desired.profile,
            desired.upstreamAddress,
            desired.targets,
            desired.cyclingConfig,
        ) { result ->
            result.onFailure { Timber.tag(TAG).w(it, "automatic FTMS restart failed") }
        }
    }

    private fun scheduleRetryIfRecoverable(failure: Throwable) {
        // Retry short-lived Android stack failures only. Permission, capability,
        // payload, user cancellation, and adapter-off failures need an external
        // state change; spinning on them would waste battery and obscure the
        // actionable error. Delays are intentionally short and bounded.
        if (desiredStart == null ||
            !isRecoverable(failure) ||
            retryAttempt >= RETRY_DELAYS_MS.size
        ) {
            return
        }
        val delayMs = RETRY_DELAYS_MS[retryAttempt++]
        cancelRetry()
        val runnable = Runnable { restartDesired("recoverable-failure-$retryAttempt") }
        retryRunnable = runnable
        main.postDelayed(runnable, delayMs)
    }

    private fun cancelRetry() {
        retryRunnable?.let(main::removeCallbacks)
        retryRunnable = null
    }

    private fun isRecoverable(failure: Throwable): Boolean {
        val message = failure.message.orEmpty().lowercase()
        return !message.contains("permission") &&
            !message.contains("cannot advertise") &&
            !message.contains("unavailable") &&
            !message.contains("payload") &&
            !message.contains("too large") &&
            !message.contains("unsupported") &&
            !message.contains("turned off") &&
            !message.contains("superseded") &&
            !message.contains("cancel")
    }

    // ── permissions ───────────────────────────────────────────────────────────

    fun hasAdvertisePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            true
        }

    private fun hasConnectPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            granted(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            true
        }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    // ── status plumbing ───────────────────────────────────────────────────────

    private fun publish(next: PeripheralStatus) {
        status = next
        val revolutions = notifier.cyclingRevolutions()
        onStatus(
            next.copy(
                packetsSent = notifier.packetsSent,
                metricsReceived = store.updateCount,
                completeRecords = notifier.completeRecords,
                compactedRecords = notifier.compactedRecords,
                fragmentedRecords = notifier.fragmentedRecords,
                ftmsSubscribed = advertiser.anyFtmsSubscribed(),
                cscSubscribed = advertiser.anyCscSubscribed(),
                cpsSubscribed = advertiser.anyCpsSubscribed(),
                ftmsPacketsSent = notifier.ftmsPacketsSent,
                cscPacketsSent = notifier.cscPacketsSent,
                cpsPacketsSent = notifier.cpsPacketsSent,
                cumulativeWheelRevolutions = revolutions.cumulativeWheelRevolutions,
                cumulativeCrankRevolutions = revolutions.cumulativeCrankRevolutions,
            ),
        )
    }

    companion object {
        private const val TAG = "FtmsController"
        private const val PREFS = "com.gmail.absolutebollockscreations.Fitbridge.peripheral"
        private const val KEY_ORIGINAL_NAME = "original_adapter_name"
        private val RETRY_DELAYS_MS = longArrayOf(250L, 750L, 1_500L)
    }
}
