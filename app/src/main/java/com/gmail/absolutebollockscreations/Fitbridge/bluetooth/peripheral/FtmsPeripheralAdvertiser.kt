package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BtServiceProfiles.FtmsMachineProfile
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Exact eight-octet value returned by the downstream Fitness Machine Feature (`0x2ACC`).
 *
 * @param upstreamPaceSupported the bridged machine's own Pace Supported bit. Treadmill pace is
 * relayed verbatim rather than synthesised, so the claim is mirrored rather than asserted: we
 * advertise pace exactly when the machine we are bridging advertises it. Claiming it otherwise
 * would advertise a measurement FitBridge cannot supply; sending the field without the bit
 * would be a protocol violation. Only the treadmill persona has the field.
 */
internal fun buildFtmsFeaturePayload(
    profile: FtmsMachineProfile,
    targets: TargetCapabilities,
    upstreamPaceSupported: Boolean = false,
    measurementCapabilities: FtmsMeasurementCapabilities = profile.measurementCapabilities,
): ByteArray {
    var measurement = measurementCapabilities.toFeatureBits()
    if (profile == FtmsMachineProfile.TREADMILL && upstreamPaceSupported) {
        measurement = measurement or (1 shl FtmsSpec.Feature.PACE)
    }
    return u32le(measurement) + u32le(targets.toTargetFeatureBits())
}

/**
 * Owns the local GATT server and the LE advertiser.
 *
 * Differences from the previous FitBridge `BtServiceAdvertiser`, all deliberate:
 *
 *  - **Phantom-proof admission.** A device is only counted as a client once it
 *    actually engages our GATT database (MTU/read/write); mere connection does
 *    not. The upstream machine and any link that pre-dates the server are
 *    excluded outright. The old advertiser admitted every `onConnectionStateChange`
 *    CONNECTED callback, so opening the server while already connected to a
 *    machine as a central immediately consumed a client slot with a phantom that
 *    would never subscribe.
 *  - **Advertising is disabled the moment a real connection request arrives.** A
 *    re-asserted legacy advertiser starves the forming link on Qualcomm
 *    controllers, so the central never completes GATT discovery.
 *  - **Explicit inbound-link retention.** The first connected callback calls
 *    `gattServer.connect(device, false)` once for that physical connection. Some
 *    Samsung/vendor stacks otherwise leave the native one-second
 *    no-application-holder timer armed and disconnect an active client after only
 *    a few packets.
 *  - **Advertising failure is propagated.** Android reports overflow and other
 *    start failures asynchronously via AdvertiseCallback, long after
 *    startAdvertising() returns. The old advertiser returned a synchronous
 *    Boolean and only logged the callback, so `BtServiceManager` set
 *    `isAdvertising = true` while nothing was on the air. [start] is therefore
 *    asynchronous and completes only once the platform has answered.
 *  - **Reads honour offset**, so a long read cannot be corrupted.
 *  - **Every notification and indication is serialised** through [GattTxQueue].
 *  - **LE 1M PHY pinning** for Garmin downstream links, and Just Works pairing
 *    auto-confirmation, neither of which existed before.
 */
class FtmsPeripheralAdvertiser(
    private val context: Context,
    private val controlSink: FtmsControlSink,
    private val listener: Listener,
    /**
     * Every address FitBridge currently holds a **central-role** link to, read
     * live on each admission decision.
     *
     * A snapshot cannot answer this. See [isPhantomDownstream].
     */
    private val centralRoleAddresses: () -> Set<String> = { emptySet() },
) {

    interface Listener {
        fun onClientConnected(device: BluetoothDevice)
        fun onClientDisconnected(device: BluetoothDevice, status: Int)
        fun onSubscriptionChanged(connection: PeripheralConnection)
        fun onProcedureReset()
        fun onClientControlReceived() {}
        fun onClientLinkStateChanged(connected: Boolean) {}
        fun onCscSetCumulativeWheelRevolutions(value: Long): Boolean
        fun onAdvertisingStateChanged(advertising: Boolean, error: String?)
    }

    private val main = Handler(Looper.getMainLooper())
    private val txScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionSequence = AtomicLong(0L)
    private val txQueue = GattTxQueue(txScope, post = ::postTx)

    private var gattServer: BluetoothGattServer? = null
    private var leAdvertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null

    /** Invalidates every asynchronous callback from an older start/stop cycle. */
    private val lifecycleEpoch = AtomicLong(0L)

    private class StartRequest(
        val epoch: Long,
        val adapter: BluetoothAdapter,
        val onResult: (Result<Unit>) -> Unit,
    ) {
        val settled = AtomicBoolean(false)
        var serviceTimeout: Runnable? = null
        var expectedServiceUuid: UUID? = null
    }

    private class AdvertisingAttempt(
        val epoch: Long,
        val advertiser: BluetoothLeAdvertiser,
        val onResult: (Result<Unit>) -> Unit,
    ) {
        val settled = AtomicBoolean(false)
        lateinit var callback: AdvertiseCallback
        lateinit var timeout: Runnable
    }

    @Volatile
    private var pendingStart: StartRequest? = null

    @Volatile
    private var advertisingAttempt: AdvertisingAttempt? = null

    private var profile: FtmsMachineProfile = FtmsMachineProfile.INDOOR_BIKE
    private var targets: TargetCapabilities = TargetCapabilities.NONE
    private var measurementCapabilities: FtmsMeasurementCapabilities =
        FtmsMachineProfile.INDOOR_BIKE.measurementCapabilities

    /**
     * The bridged machine's own Pace Supported bit, mirrored into the downstream Feature.
     *
     * Read live in [featurePayload] rather than frozen at server start: the upstream `0x2ACC`
     * read completes during machine setup, which may land after the server is open. A client
     * that reads Feature before that completes sees pace unclaimed, which is the safe way round
     * — unclaimed and unsent, rather than claimed and never delivered.
     */
    var upstreamPaceSupported: () -> Boolean = { false }
    private var includeTrainingStatus: Boolean = false
    private var cyclingConfig: CyclingSensorConfig = CyclingSensorConfig.DISABLED

    /**
     * ESSENTIAL GARMIN COMPATIBILITY WORKAROUND: keep downstream Garmin links on
     * LE 1M. A packet capture from a failing Garmin session showed Android
     * attempting a 2M PHY transition while the Garmin initiated a channel-map
     * update. On affected controllers the overlapping link-layer procedures
     * terminated the connection with native status 0x24 (LMP_PDU_NOT_ALLOWED).
     * This is a transport-level failure and therefore affects every FTMS machine
     * profile, regardless of how its measurement payload is framed.
     */
    @Volatile
    private var garminCompatibilityEnabled: Boolean = false

    /**
     * Address of the machine FitBridge is connected to as a *central*.
     *
     * Opening a GATT server surfaces every already-ACL-connected device as a
     * "server connection", including the upstream machine (we are its client). If
     * we treated that as our client it would occupy a slot, never subscribe, and
     * lock out the real downstream watch. So we ignore it.
     */
    @Volatile
    private var upstreamAddress: String? = null

    private val controlPoint = FtmsControlPointHandler(controlSink)

    /**
     * Active downstream clients, keyed by lower-cased device address. The GATT
     * callback thread mutates it; the notifier coroutine reads it. A
     * ConcurrentHashMap plus the per-connection @Volatile fields is sufficient -
     * no lock, and readers never see a torn connection.
     */
    private val connections = ConcurrentHashMap<String, PeripheralConnection>()

    /**
     * Addresses that must never occupy a client slot: the upstream machine plus
     * every device already linked to this phone when we opened the GATT server.
     */
    private val excludedAddresses = ConcurrentHashMap.newKeySet<String>()

    /**
     * Non-excluded devices currently in the CONNECTED state - candidates that
     * have connected but may not have engaged yet, plus admitted clients. This,
     * not [connections], drives advertising: we go quiet the instant any real link
     * is forming and resume only once the last one is gone.
     */
    private val connectedDevices = ConcurrentHashMap.newKeySet<String>()

    /**
     * Per-device "awaiting engagement" watchdogs, keyed by lower-cased address.
     * Armed when a connection request pauses advertising; cleared the moment the
     * device engages our GATT database (admission) or the link drops. If a peer
     * connects but never engages, the watchdog releases the stalled link and
     * resumes advertising so the machine stays discoverable.
     */
    private val engagementWatchdogs = ConcurrentHashMap<String, Runnable>()

    /**
     * Auto-confirms Just Works / consent pairing for a forming downstream link so
     * a central's bond attempt (e.g. Garmin Connect IQ `pairDevice`) cannot wedge
     * the connection waiting on user consent. Registered while the server is open.
     */
    private var pairingReceiver: BroadcastReceiver? = null

    @Volatile
    var isAdvertising: Boolean = false
        private set

    fun currentProfile(): FtmsMachineProfile = profile

    /** Snapshot of the currently attached clients. */
    fun activeConnections(): List<PeripheralConnection> = connections.values.toList()

    fun hasClients(): Boolean = connections.isNotEmpty()
    fun hasDownstreamLinks(): Boolean = connectedDevices.isNotEmpty()

    fun anyDataSubscribed(): Boolean = connections.values.any { it.anyMeasurementSubscribed }
    fun anyFtmsSubscribed(): Boolean = connections.values.any { it.dataSubscribed }
    fun anyCscSubscribed(): Boolean = connections.values.any { it.cscMeasurementSubscribed }
    fun anyCpsSubscribed(): Boolean = connections.values.any { it.cyclingPowerMeasurementSubscribed }

    fun firstClientAddress(): String? = connections.values.firstOrNull()?.device?.address

    @SuppressLint("MissingPermission")
    fun setGarminCompatibility(enabled: Boolean) {
        garminCompatibilityEnabled = enabled
        if (enabled) {
            connections.values.forEach { preferLe1m(it.device, "compatibility-enabled") }
        }
    }

    /**
     * Updates central-role exclusion metadata without touching the advertiser,
     * GATT server, services, connections, or subscriptions.
     */
    fun updateUpstreamAddress(address: String?) {
        upstreamAddress = address
    }

    private fun addressKey(device: BluetoothDevice): String = device.address.lowercase()

    // ── lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Opens the GATT server and starts advertising.
     *
     * [onResult] is invoked on the main thread once the platform has accepted or
     * rejected the advertisement - never before.
     */
    @RequiresPermission(
        allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE],
    )
    fun start(
        profile: FtmsMachineProfile,
        targets: TargetCapabilities,
        includeTrainingStatus: Boolean,
        cyclingConfig: CyclingSensorConfig,
        upstreamAddress: String?,
        measurementCapabilities: FtmsMeasurementCapabilities = profile.measurementCapabilities,
        onResult: (Result<Unit>) -> Unit,
    ) {
        // Invalidate all callbacks belonging to a previous server/advertiser.
        val epoch = lifecycleEpoch.incrementAndGet()
        cancelPendingStart("Superseded by a newer start")
        cancelAdvertisingAttempt("Superseded by a newer start")
        stopAdvertisingStable()
        connections.clear()
        connectedDevices.clear()
        clearAllEngagementWatchdogs()
        excludedAddresses.clear()
        teardownGattServer()
        txQueue.resume()

        this.profile = profile
        this.targets = targets
        this.measurementCapabilities = measurementCapabilities
        this.includeTrainingStatus = includeTrainingStatus
        this.cyclingConfig = cyclingConfig.effectiveFor(profile)
        this.upstreamAddress = upstreamAddress

        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (manager == null || adapter == null) {
            onResult(
                Result.failure(IllegalStateException("Bluetooth is unavailable on this device")),
            )
            return
        }
        if (!adapter.isEnabled) {
            onResult(Result.failure(IllegalStateException("Bluetooth is turned off")))
            return
        }

        // Snapshot everything already connected to this phone BEFORE opening the
        // server, so the pre-existing links that the framework is about to replay
        // as CONNECTED callbacks are already marked as phantoms.
        seedExcludedAddresses(manager)

        val request = StartRequest(epoch, adapter, onResult)
        pendingStart = request

        val server = manager.openGattServer(context, createGattCallback(epoch))
        if (server == null) {
            finishStart(
                request,
                Result.failure(IllegalStateException("Could not open a GATT server")),
            )
            return
        }
        gattServer = server
        registerPairingReceiver()

        publishFtmsService(request)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun publishFtmsService(request: StartRequest) {
        val server = gattServer
        if (server == null || !isCurrentEpoch(request.epoch)) {
            finishStart(
                request,
                Result.failure(CancellationException("GATT server is no longer available")),
            )
            return
        }

        val service = PeripheralGattRegistry.buildFtmsService(
            profile,
            targets,
            includeTrainingStatus,
        )
        armServiceTimeout(request, BluetoothIds.UUID_FTMS_SERVICE, "FTMS")

        if (!server.addService(service)) {
            clearServiceTimeout(request)
            finishStart(
                request,
                Result.failure(
                    IllegalStateException(
                        "The Bluetooth stack rejected the FTMS service publication request",
                    ),
                ),
            )
        }

        // addService(true) means only that Android accepted the asynchronous
        // request. The database is not usable until onServiceAdded succeeds.
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun publishAdditionalService(
        request: StartRequest,
        service: BluetoothGattService,
        label: String,
    ) {
        val server = gattServer
        if (server == null || !isCurrentEpoch(request.epoch)) {
            finishStart(
                request,
                Result.failure(CancellationException("GATT server is no longer available")),
            )
            return
        }
        armServiceTimeout(request, service.uuid, label)
        if (!server.addService(service)) {
            clearServiceTimeout(request)
            finishStart(
                request,
                Result.failure(
                    IllegalStateException(
                        "The Bluetooth stack rejected the $label service publication request",
                    ),
                ),
            )
        }
    }

    private fun armServiceTimeout(
        request: StartRequest,
        serviceUuid: UUID,
        serviceLabel: String,
    ) {
        clearServiceTimeout(request)
        request.expectedServiceUuid = serviceUuid
        // Install the timeout before addService so even an unusually fast
        // callback cannot race a subsequently posted stale timeout.
        request.serviceTimeout = Runnable {
            if (isCurrentEpoch(request.epoch) && request.expectedServiceUuid == serviceUuid) {
                finishStart(
                    request,
                    Result.failure(
                        IllegalStateException(
                            "$serviceLabel service publication did not complete within " +
                                "${SERVICE_ADD_TIMEOUT_MS}ms",
                        ),
                    ),
                )
            }
        }
        main.postDelayed(request.serviceTimeout!!, SERVICE_ADD_TIMEOUT_MS)
    }

    private fun clearServiceTimeout(request: StartRequest) {
        request.serviceTimeout?.let(main::removeCallbacks)
        request.serviceTimeout = null
        request.expectedServiceUuid = null
    }

    @RequiresPermission(
        allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE],
    )
    fun stop() {
        lifecycleEpoch.incrementAndGet()
        cancelPendingStart("Peripheral stopped")
        cancelAdvertisingAttempt("Peripheral stopped")
        stopAdvertisingStable()
        connections.values.forEach { conn ->
            txQueue.cancelSession(conn.sessionId)
            runCatching { gattServer?.cancelConnection(conn.device) }
        }
        txQueue.cancelAll()
        connections.clear()
        connectedDevices.clear()
        clearAllEngagementWatchdogs()
        excludedAddresses.clear()
        unregisterPairingReceiver()
        teardownGattServer()
    }

    fun dispose() {
        runCatching { stop() }
        txScope.cancel()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun stopAdvertisingStable() {
        val adv = leAdvertiser
        val cb = advertiseCallback
        if (adv != null && cb != null) {
            runCatching { adv.stopAdvertising(cb) }
                .onFailure { Timber.tag(TAG).w(it, "stopAdvertising threw") }
        }
        leAdvertiser = null
        advertiseCallback = null
        if (isAdvertising) {
            isAdvertising = false
            listener.onAdvertisingStateChanged(false, null)
        }
    }

    private fun finishStart(request: StartRequest, result: Result<Unit>) {
        if (!request.settled.compareAndSet(false, true)) return
        clearServiceTimeout(request)
        if (pendingStart === request) pendingStart = null

        if (result.isFailure && isCurrentEpoch(request.epoch)) {
            stopAdvertisingStable()
            unregisterPairingReceiver()
            teardownGattServer()
        }
        request.onResult(result)
    }

    private fun cancelPendingStart(reason: String) {
        val request = pendingStart ?: return
        finishStart(request, Result.failure(CancellationException(reason)))
    }

    private fun isCurrentEpoch(epoch: Long): Boolean = lifecycleEpoch.get() == epoch

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun teardownGattServer() {
        val server = gattServer ?: return
        runCatching { server.clearServices() }
        runCatching { server.close() }
        gattServer = null
    }

    // ── advertising ───────────────────────────────────────────────────────────

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun startAdvertising(
        adapter: BluetoothAdapter,
        epoch: Long,
        onResult: (Result<Unit>) -> Unit,
    ) {
        if (!isCurrentEpoch(epoch)) {
            onResult(Result.failure(CancellationException("Stale advertising request")))
            return
        }
        if (advertisingAttempt != null || isAdvertising) {
            onResult(Result.success(Unit))
            return
        }
        val advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            onResult(
                Result.failure(
                    IllegalStateException("This device cannot advertise over Bluetooth LE"),
                ),
            )
            return
        }

        val plan = runCatching {
            FtmsAdvertisingPlan.forProfile(profile, cyclingConfig.enabled)
        }.getOrElse {
            onResult(Result.failure(it))
            return
        }
        val name = plan.localName
        val serviceData = plan.serviceData
        Timber.tag(TAG).d(
            "advertising payload %d/%d octets; scan response %d/%d",
            plan.advertisingBytes,
            FtmsAdvertisingPlan.MAX_LEGACY_BYTES,
            plan.scanResponseBytes,
            FtmsAdvertisingPlan.MAX_LEGACY_BYTES,
        )

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .apply { plan.serviceUuids.forEach { addServiceUuid(ParcelUuid(it)) } }
            .addServiceData(ParcelUuid(BluetoothIds.UUID_FTMS_SERVICE), serviceData)
            .build()

        // Intentionally empty. FTMS Appearance is an optional capability this
        // product does not declare, and Android's public legacy builder has no
        // API for the Appearance AD type.
        val scanResponse = AdvertiseData.Builder().build()

        val attempt = AdvertisingAttempt(epoch, advertiser, onResult)
        attempt.timeout = Runnable {
            settleAdvertising(
                attempt,
                Result.failure(
                    IllegalStateException(
                        "Advertising did not start within ${ADVERTISE_START_TIMEOUT_MS}ms",
                    ),
                ),
            )
        }
        attempt.callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) =
                settleAdvertising(attempt, Result.success(Unit), name, serviceData)

            override fun onStartFailure(errorCode: Int) =
                settleAdvertising(
                    attempt,
                    Result.failure(
                        IllegalStateException(
                            "Advertising rejected: ${advertiseErrorName(errorCode)}",
                        ),
                    ),
                )
        }

        advertisingAttempt = attempt
        leAdvertiser = advertiser
        advertiseCallback = attempt.callback
        main.postDelayed(attempt.timeout, ADVERTISE_START_TIMEOUT_MS)

        try {
            advertiser.startAdvertising(settings, data, scanResponse, attempt.callback)
        } catch (t: Throwable) {
            settleAdvertising(attempt, Result.failure(t))
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun settleAdvertising(
        attempt: AdvertisingAttempt,
        result: Result<Unit>,
        name: String? = null,
        serviceData: ByteArray? = null,
    ) {
        if (!attempt.settled.compareAndSet(false, true)) return
        main.removeCallbacks(attempt.timeout)
        if (advertisingAttempt === attempt) advertisingAttempt = null

        val current = isCurrentEpoch(attempt.epoch)
        if (result.isSuccess && current) {
            isAdvertising = true
            Timber.tag(TAG).i(
                "advertising confirmed t=%d name='%s' serviceData=%s clients=%d",
                SystemClock.elapsedRealtime(),
                name,
                serviceData?.toHexString(),
                connections.size,
            )
            listener.onAdvertisingStateChanged(true, null)
            attempt.onResult(Result.success(Unit))
            return
        }

        runCatching { attempt.advertiser.stopAdvertising(attempt.callback) }
        if (leAdvertiser === attempt.advertiser) {
            leAdvertiser = null
            advertiseCallback = null
        }
        val failure = if (!current && result.isSuccess) {
            Result.failure(CancellationException("Stale advertising callback"))
        } else {
            result
        }
        attempt.onResult(failure)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun cancelAdvertisingAttempt(reason: String) {
        val attempt = advertisingAttempt ?: return
        settleAdvertising(attempt, Result.failure(CancellationException(reason)))
    }

    /**
     * Stop the LE advertiser without touching the GATT server or its connections.
     *
     * On Qualcomm controllers a connectable legacy advertisement is meant to be
     * consumed (auto-disabled) when a connection is accepted; re-asserting it
     * while a connection is being set up ("Legacy advertiser should be only
     * disabled on timeout, but was enabled!") leaves the controller unable to
     * service the connection's ATT traffic, so the central's service discovery
     * never progresses and it never reaches CONNECTED. We therefore go quiet
     * while a client is attaching and resume once the last one leaves.
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun pauseAdvertising(reason: String) {
        cancelAdvertisingAttempt("paused: $reason")
        val adv = leAdvertiser
        val cb = advertiseCallback
        if (adv != null && cb != null) {
            runCatching { adv.stopAdvertising(cb) }
        }
        leAdvertiser = null
        advertiseCallback = null
        if (isAdvertising) {
            isAdvertising = false
            listener.onAdvertisingStateChanged(false, null)
            Timber.tag(TAG).i("advertising paused (%s)", reason)
        }
    }

    @RequiresPermission(
        allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE],
    )
    private fun resumeAdvertising() {
        if (gattServer == null) return
        if (isAdvertising || advertisingAttempt != null) return
        val manager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val adapter = manager.adapter ?: return
        if (!adapter.isEnabled) return
        val epoch = lifecycleEpoch.get()
        startAdvertising(adapter, epoch) { result ->
            result.onFailure { listener.onAdvertisingStateChanged(false, it.message) }
        }
    }

    // ── GATT server callbacks ─────────────────────────────────────────────────

    private fun createGattCallback(callbackEpoch: Long) = object : BluetoothGattServerCallback() {

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (!isCurrentEpoch(callbackEpoch)) return
            val request = pendingStart ?: return
            if (request.epoch != callbackEpoch || service.uuid != request.expectedServiceUuid) return

            clearServiceTimeout(request)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finishStart(
                    request,
                    Result.failure(
                        IllegalStateException(
                            "${shortUuidLabel(service.uuid)} service publication failed: " +
                                "GATT status $status",
                        ),
                    ),
                )
                return
            }

            when {
                service.uuid == BluetoothIds.UUID_FTMS_SERVICE && cyclingConfig.enabled ->
                    publishAdditionalService(
                        request,
                        CyclingServiceRegistry.buildCscService(),
                        "CSC",
                    )

                service.uuid == BluetoothIds.UUID_CSC_SERVICE ->
                    publishAdditionalService(
                        request,
                        CyclingServiceRegistry.buildCyclingPowerService(),
                        "CPS",
                    )

                else -> {
                    Timber.tag(TAG).i(
                        "GATT server ready for %s nativeBike=%s",
                        profile,
                        cyclingConfig.enabled,
                    )
                    startAdvertising(request.adapter, request.epoch) { result ->
                        finishStart(request, result)
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (!isCurrentEpoch(callbackEpoch)) return
            // Phantoms - the upstream machine (we are its central) and anything
            // already connected when we opened the server - are ignored entirely,
            // in both directions. They never become clients or affect advertising.
            if (isExcluded(device)) {
                // Logged at info, not behind FTMS_VERBOSE_LOGGING.
                //
                // This used to be a verbose-only line, which made the exclusion
                // unfalsifiable: a working filter and a filter that never ran
                // produced byte-identical logs. After the 2026-08-07 fix a session
                // was captured in which no phantom was admitted and no phantom was
                // reported either, and there was no way to tell which had happened.
                //
                // A device FitBridge holds a central link to being reported as a
                // server connection is worth one line whenever it occurs. It is
                // rare - a handful per session, not per packet - so this is not
                // the per-MAC spam that flag exists to suppress.
                Timber.tag(TAG).i(
                    "ignoring phantom/upstream callback %s status=%d newState=%d " +
                        "(central-role link, never a downstream client)",
                    deviceLabel(device), status, newState,
                )
                return
            }

            Timber.tag(TAG).i(
                "downstream state callback t=%d client=%s status=%d newState=%d bond=%d",
                SystemClock.elapsedRealtime(),
                deviceLabel(device),
                status,
                newState,
                device.bondState,
            )

            val key = addressKey(device)
            val connectedNow =
                status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED

            if (connectedNow) {
                // An inbound peripheral-role link does not necessarily count as
                // being held by this GATT server in Android's native connection
                // manager. On affected stacks that leaves the one-second
                // GATT_LINK_IDLE_TIMEOUT_WHEN_NO_APP armed. Explicitly connect the
                // already-present peer so this server retains the ACL link.
                //
                // connect() may produce another connected callback, so the
                // connected-device set is also the once-per-physical-connection
                // guard. Disconnect cleanup removes the key for the next session.
                if (!connectedDevices.add(key)) return
                listener.onClientLinkStateChanged(true)
                val retained = runCatching {
                    gattServer?.connect(device, false) == true
                }.getOrElse { error ->
                    Timber.tag(TAG).w(error, "could not retain connection %s", deviceLabel(device))
                    false
                }
                if (!retained) {
                    Timber.tag(TAG).w(
                        "GATT server did not retain connection %s",
                        deviceLabel(device),
                    )
                } else {
                    Timber.tag(TAG).i("GATT server retained connection %s", deviceLabel(device))
                }
                val cachedName = runCatching { device.name }.getOrNull()
                if (shouldPinGarminConnection(
                        garminCompatibilityEnabled,
                        cachedName,
                        device.address,
                    )
                ) {
                    // Do this as soon as the downstream link is retained, before
                    // service discovery, subscriptions, or measurement traffic
                    // gives the controller an opportunity to select LE 2M.
                    preferLe1m(device, "garmin-downstream")
                }
                // Disable advertising the moment a connection request arrives. A
                // re-asserted legacy advertiser starves the forming link on
                // Qualcomm controllers, so the central never completes GATT
                // discovery. This device is NOT a client yet - admission waits for
                // its first real ATT engagement (see admit()) - but bond state is
                // never an admission criterion; anonymous and bonded peers alike
                // get a fresh session and must (re)subscribe.
                Timber.tag(TAG).i(
                    "connection request %s; pausing advertising (awaiting engagement)",
                    deviceLabel(device),
                )
                runCatching { pauseAdvertising("connection-request") }
                armEngagementWatchdog(device, callbackEpoch)
                return
            }

            // Any other transition - disconnect, or a non-success status - tears
            // the link down. Only an already-admitted client needs session cleanup
            // and a disconnect notification.
            connectedDevices.remove(key)
            listener.onClientLinkStateChanged(connectedDevices.isNotEmpty())
            clearEngagementWatchdog(key)
            val existing = connections.remove(key)
            if (existing != null) {
                // Before resetSession(), which clears the MTU and subscriptions
                // that make the record interpretable.
                PeripheralCaptureHooks.clientDisconnected(
                    address = device.address,
                    sessionId = existing.sessionId,
                    reason = "status=$status",
                )
                txQueue.cancelSession(existing.sessionId)
                existing.resetSession()
                Timber.tag(TAG).i(
                    "client disconnected %s (clients=%d)",
                    deviceLabel(device),
                    connections.size,
                )
                listener.onClientDisconnected(device, status)
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                runCatching { gattServer?.cancelConnection(device) }
            }
            // Resume advertising once no real link remains being set up or serviced.
            if (connectedDevices.isEmpty()) runCatching { resumeAdvertising() }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            if (!isCurrentEpoch(callbackEpoch)) return
            txQueue.onNotificationSent(device.address, status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            if (!isCurrentEpoch(callbackEpoch)) return
            if (isUpstream(device)) return
            val conn = admit(device)
            if (FTMS_CONNECT_TRACE) {
                Timber.tag(TAG).i(
                    "att-trace MTU t=%d %s mtu=%d matched=%s",
                    SystemClock.elapsedRealtime(), deviceLabel(device), mtu, conn != null,
                )
            }
            if (conn == null) return
            conn.onMtuChanged(mtu)
            Timber.tag(TAG).i(
                "MTU %s = %d -> notify payload %dB%s",
                deviceLabel(device),
                mtu,
                conn.maxNotifyPayload,
                if (conn.prefersCompactFraming) {
                    // Named explicitly: this changes what goes on the wire, and a
                    // silent change to the encoding would be its own diagnostic
                    // problem the next time a client misbehaves.
                    " (below MTU ${PeripheralConnection.COMPACT_FRAMING_MTU_THRESHOLD};" +
                        " using compact framing instead of More Data fragmentation)"
                } else {
                    ""
                },
            )
        }

        /*
         * There is deliberately no onConnectionUpdated override here.
         *
         * BluetoothGattCallback - the *central*-role callback - has had
         * onConnectionUpdated since API 26. BluetoothGattServerCallback does not.
         * A GATT server on Android is given no public way to learn the negotiated
         * connection interval, latency or supervision timeout of a link it is
         * serving, even though those parameters entirely determine how fast it can
         * usefully send.
         *
         * The interval is therefore estimated rather than observed - see
         * PeripheralConnection.assumedConnectionIntervalMs. If a supported API
         * ever appears, this is where it plugs in, and the estimate should be
         * deleted rather than kept alongside it.
         */

        override fun onPhyUpdate(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) {
            if (!isCurrentEpoch(callbackEpoch) || isExcluded(device)) return
            Timber.tag(TAG).i(
                "PHY update %s tx=%s rx=%s status=%s",
                deviceLabel(device), phyName(txPhy), phyName(rxPhy), gattStatusName(status),
            )
        }

        override fun onPhyRead(device: BluetoothDevice, txPhy: Int, rxPhy: Int, status: Int) {
            if (!isCurrentEpoch(callbackEpoch) || isExcluded(device)) return
            Timber.tag(TAG).i(
                "PHY read %s tx=%s rx=%s status=%s",
                deviceLabel(device), phyName(txPhy), phyName(rxPhy), gattStatusName(status),
            )
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (!isCurrentEpoch(callbackEpoch)) return
            val conn = admit(device)
            if (FTMS_CONNECT_TRACE) {
                Timber.tag(TAG).i(
                    "att-trace descWrite t=%d %s desc=%s char=%s matched=%s respNeeded=%s len=%d",
                    SystemClock.elapsedRealtime(),
                    deviceLabel(device),
                    shortUuidLabel(descriptor.uuid),
                    shortUuidLabel(descriptor.characteristic?.uuid),
                    conn != null,
                    responseNeeded,
                    value.size,
                )
            }
            var status = BluetoothGatt.GATT_SUCCESS
            var mode: PeripheralConnection.CccdMode? = null
            val characteristic = descriptor.characteristic

            when {
                conn == null -> status = BluetoothGatt.GATT_FAILURE
                !responseNeeded -> status = GattProtocol.REQUEST_NOT_SUPPORTED
                descriptor.uuid != BluetoothIds.UUID_CCCD || characteristic == null ->
                    status = BluetoothGatt.GATT_WRITE_NOT_PERMITTED

                preparedWrite -> status = GattProtocol.REQUEST_NOT_SUPPORTED
                offset != 0 -> status = GattProtocol.INVALID_OFFSET
                value.size != 2 -> status = GattProtocol.INVALID_ATTRIBUTE_LENGTH
                else -> {
                    mode = GattProtocol.decodeCccd(
                        value = value,
                        supportsNotify = characteristic.properties and
                            BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0,
                        supportsIndicate = characteristic.properties and
                            BluetoothGattCharacteristic.PROPERTY_INDICATE != 0,
                    )
                    if (mode == null) status = GattProtocol.REQUEST_NOT_SUPPORTED
                }
            }

            if (status == BluetoothGatt.GATT_SUCCESS &&
                conn != null && characteristic != null && mode != null
            ) {
                val wasDataSubscribed = conn.anyMeasurementSubscribed
                conn.onCccdWrite(characteristic.uuid, profile.dataCharacteristic, mode)
                PeripheralCaptureHooks.clientCccd(
                    address = device.address,
                    characteristic = characteristic.uuid,
                    enabled = mode != PeripheralConnection.CccdMode.DISABLED,
                    mode = mode.name,
                )
                Timber.tag(TAG).i(
                    "CCCD t=%d %s=%s [%s]",
                    SystemClock.elapsedRealtime(),
                    shortUuidLabel(characteristic.uuid),
                    mode,
                    conn.describe(),
                )
                // Only a real measurement-subscription transition needs to wake the
                // notifier. Control Point and other CCCD writes must not inject an
                // extra measurement into a slow client's next event.
                if (conn.anyMeasurementSubscribed != wasDataSubscribed) {
                    listener.onSubscriptionChanged(conn)
                }
            }
            if (FTMS_CONNECT_TRACE) {
                Timber.tag(TAG).i("att-trace descWrite -> %s", gattStatusName(status))
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, status, 0, null)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            if (!isCurrentEpoch(callbackEpoch)) return
            val server = gattServer ?: return
            val conn = admit(device)
            if (FTMS_CONNECT_TRACE) {
                Timber.tag(TAG).i(
                    "att-trace descRead t=%d %s desc=%s char=%s matched=%s offset=%d",
                    SystemClock.elapsedRealtime(),
                    deviceLabel(device),
                    shortUuidLabel(descriptor.uuid),
                    shortUuidLabel(descriptor.characteristic?.uuid),
                    conn != null,
                    offset,
                )
            }
            if (conn == null) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                return
            }
            if (descriptor.uuid != BluetoothIds.UUID_CCCD) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, 0, null)
                return
            }
            val charUuid = descriptor.characteristic?.uuid
            if (charUuid == null) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, 0, null)
                return
            }
            val read = GattProtocol.readSlice(conn.cccdMode(charUuid).wireValue, offset)
            server.sendResponse(device, requestId, read.status, offset.coerceAtLeast(0), read.value)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (!isCurrentEpoch(callbackEpoch)) return
            val server = gattServer ?: return
            val conn = admit(device)
            if (FTMS_CONNECT_TRACE) {
                Timber.tag(TAG).i(
                    "att-trace charRead t=%d %s char=%s matched=%s offset=%d",
                    SystemClock.elapsedRealtime(),
                    deviceLabel(device),
                    shortUuidLabel(characteristic.uuid),
                    conn != null,
                    offset,
                )
            }
            if (conn == null) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                return
            }
            val payload: ByteArray? = when (characteristic.uuid) {
                BluetoothIds.UUID_FITNESS_MACHINE_FEATURE -> featurePayload()
                BluetoothIds.UUID_TRAINING_STATUS -> trainingStatusPayload(conn)
                BluetoothIds.UUID_SUPPORTED_SPEED_RANGE -> targets.speedRange?.encodeU16()
                BluetoothIds.UUID_SUPPORTED_INCLINATION_RANGE -> targets.inclinationRange?.encodeS16()
                BluetoothIds.UUID_SUPPORTED_RESISTANCE_RANGE -> targets.resistanceRange?.encodeS16()
                BluetoothIds.UUID_SUPPORTED_HEART_RATE_RANGE -> targets.heartRateRange?.encodeU8()
                BluetoothIds.UUID_SUPPORTED_POWER_RANGE -> targets.powerRange?.encodeS16()
                BluetoothIds.UUID_CSC_FEATURE ->
                    CyclingSensorEncoders.cscFeature().takeIf { cyclingConfig.enabled }

                BluetoothIds.UUID_CYCLING_POWER_FEATURE ->
                    CyclingSensorEncoders.cyclingPowerFeature().takeIf { cyclingConfig.enabled }

                BluetoothIds.UUID_SENSOR_LOCATION ->
                    CyclingSensorEncoders.sensorLocation().takeIf { cyclingConfig.enabled }

                else -> null
            }
            if (payload == null) {
                server.sendResponse(
                    device,
                    requestId,
                    BluetoothGatt.GATT_READ_NOT_PERMITTED,
                    offset,
                    null,
                )
                return
            }
            val read = GattProtocol.readSlice(payload, offset)
            if (FTMS_CONNECT_TRACE) {
                Timber.tag(TAG).i(
                    "att-trace charRead -> %s %dB",
                    gattStatusName(read.status),
                    read.value?.size ?: 0,
                )
            }
            // §0.9.4: what the client was *told* about this machine. Recorded on
            // the first fragment only, so a long value fetched in slices appears
            // once and whole rather than as a run of partial rows.
            //
            // This hook existed unwired until 2026-08-06, which is why a session
            // where FitBridge advertised a Supported Inclination Range ten times
            // too wide produced a capture with no trace of it: the watch's target
            // writes were visible, the advertisement that caused them was not.
            // Reads are half the story of any downstream negotiation.
            if (PeripheralCaptureHooks.shouldCaptureRead(offset)) {
                PeripheralCaptureHooks.clientRead(device.address, characteristic.uuid, payload)
            }
            server.sendResponse(device, requestId, read.status, offset.coerceAtLeast(0), read.value)
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (!isCurrentEpoch(callbackEpoch)) return
            val server = gattServer ?: return
            val conn = admit(device)
            if (FTMS_CONNECT_TRACE) {
                Timber.tag(TAG).i(
                    "att-trace charWrite t=%d %s char=%s matched=%s respNeeded=%s len=%d value=%s",
                    SystemClock.elapsedRealtime(),
                    deviceLabel(device),
                    shortUuidLabel(characteristic.uuid),
                    conn != null,
                    responseNeeded,
                    value.size,
                    value.toHexString(),
                )
            }
            if (conn == null) {
                if (responseNeeded) {
                    server.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
                return
            }
            PeripheralCaptureHooks.clientWrote(
                address = device.address,
                characteristic = characteristic.uuid,
                payload = value,
                withoutResponse = !responseNeeded,
            )
            if (characteristic.uuid == BluetoothIds.UUID_SC_CONTROL_POINT) {
                handleCscControlPointWrite(
                    server = server,
                    conn = conn,
                    requestId = requestId,
                    preparedWrite = preparedWrite,
                    responseNeeded = responseNeeded,
                    offset = offset,
                    value = value,
                )
                return
            }
            if (characteristic.uuid != BluetoothIds.UUID_FITNESS_MACHINE_CONTROL_POINT) {
                if (responseNeeded) {
                    server.sendResponse(
                        device,
                        requestId,
                        BluetoothGatt.GATT_WRITE_NOT_PERMITTED,
                        offset,
                        null,
                    )
                }
                return
            }

            // Receipt is a session-level UI latch, even if the procedure is rejected.
            // Excluded peers, unrelated characteristics and empty/multipart writes do not count.
            if (value.isNotEmpty() && !preparedWrite && offset == 0) {
                listener.onClientControlReceived()
            }
            val validation = GattProtocol.validateControlPointWrite(
                responseNeeded = responseNeeded,
                preparedWrite = preparedWrite,
                offset = offset,
                controlPointSubscribed = conn.controlPointSubscribed,
            )
            if (validation.status != BluetoothGatt.GATT_SUCCESS) {
                if (validation.sendAttResponse) {
                    server.sendResponse(device, requestId, validation.status, 0, null)
                }
                return
            }

            // A Write Request gets its ATT response first. A Write Command has no
            // ATT response, but both transports continue through the same FTMS
            // procedure and response indication below.
            if (validation.sendAttResponse) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }

            val ownsProcedure = conn.procedure.tryBeginProcedure()
            val outcome = if (ownsProcedure) {
                controlPoint.handle(value, conn.procedure)
            } else {
                controlPoint.operationFailed(value)
            }

            Timber.tag(TAG).i(
                "CP %s -> %s",
                FtmsControlPointHandler.opcodeName(
                    value.firstOrNull()?.toInt()?.and(0xFF) ?: -1,
                ),
                FtmsControlPointHandler.resultName(outcome.response[2].toInt() and 0xFF),
            )

            // Enqueue on the callback thread up to the first suspension. This
            // preserves request order even when a client pipelines two writes;
            // Dispatchers.IO is otherwise free to start the coroutines out of
            // order. The actual Bluetooth work remains asynchronous.
            txScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    val requestedOpcode = value.firstOrNull()?.toInt()?.and(0xFF) ?: -1
                    val startedAtMs = SystemClock.elapsedRealtime()
                    val indicated = sendTx(
                        conn = conn,
                        characteristicUuid = BluetoothIds.UUID_FITNESS_MACHINE_CONTROL_POINT,
                        payload = outcome.response,
                        confirm = true,
                        priority = GattTxQueue.Priority.CONTROL,
                        purpose = "control-point-response",
                    )
                    if (!indicated) {
                        // Previously silent, which is why a 2026-08-05 session
                        // showed nine seconds of nothing before the client gave
                        // up. An unconfirmed indication also holds the single
                        // in-flight slot for its whole timeout, so this line marks
                        // the start of a measurement gap for that client.
                        Timber.tag(TAG).w(
                            "CP %s response indication NOT confirmed by %s after %dms; " +
                                "client may not know the procedure succeeded",
                            FtmsControlPointHandler.opcodeName(requestedOpcode),
                            deviceLabel(conn.device),
                            SystemClock.elapsedRealtime() - startedAtMs,
                        )
                    }

                    // The follow-ups below are deliberately NOT gated on the
                    // indication being confirmed. The procedure already executed;
                    // suppressing the state notifications because the client
                    // missed the response is what creates a split brain, where
                    // FitBridge believes control was granted and the client has
                    // been told nothing at all. A Machine Status notification is
                    // the client's other route to the same fact.
                    if (outcome.status != null && conn.statusSubscribed) {
                        sendTx(
                            conn = conn,
                            characteristicUuid = BluetoothIds.UUID_FITNESS_MACHINE_STATUS,
                            payload = outcome.status,
                            confirm = false,
                            priority = GattTxQueue.Priority.CONTROL,
                            purpose = "machine-status",
                        )
                    }
                    if (outcome.trainingStatusChanged && conn.trainingStatusSubscribed) {
                        sendTx(
                            conn = conn,
                            characteristicUuid = BluetoothIds.UUID_TRAINING_STATUS,
                            payload = conn.procedure.trainingStatusPayload(),
                            confirm = false,
                            priority = GattTxQueue.Priority.CONTROL,
                            purpose = "training-status",
                        )
                    }
                    if (outcome.resetMetrics) {
                        // Same reasoning: the reset is a consequence of the
                        // procedure having run, not of the client hearing about
                        // it. Gating it on the indication would leave FitBridge
                        // holding metrics the client believes it cleared.
                        main.post {
                            if (matchingConnection(conn.device)?.sessionId == conn.sessionId) {
                                listener.onProcedureReset()
                            }
                        }
                    }
                } finally {
                    if (ownsProcedure) conn.procedure.finishProcedure()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            if (!isCurrentEpoch(callbackEpoch)) return
            val status = if (matchingConnection(device) == null) {
                BluetoothGatt.GATT_FAILURE
            } else {
                GattProtocol.REQUEST_NOT_SUPPORTED
            }
            gattServer?.sendResponse(device, requestId, status, 0, null)
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleCscControlPointWrite(
        server: BluetoothGattServer,
        conn: PeripheralConnection,
        requestId: Int,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray,
    ) {
        val validation = GattProtocol.validateControlPointWrite(
            responseNeeded = responseNeeded,
            preparedWrite = preparedWrite,
            offset = offset,
            controlPointSubscribed = conn.cscControlPointSubscribed,
        )
        if (validation.status != BluetoothGatt.GATT_SUCCESS) {
            if (validation.sendAttResponse) {
                server.sendResponse(conn.device, requestId, validation.status, 0, null)
            }
            return
        }
        if (validation.sendAttResponse) {
            server.sendResponse(conn.device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        val opcode = value.firstOrNull()?.toInt()?.and(0xFF) ?: 0xFF
        val ownsProcedure = conn.tryBeginCscProcedure()
        val result = if (!ownsProcedure) {
            CscControlPoint.OPERATION_FAILED
        } else {
            when (val request = CscControlPoint.decode(value)) {
                is CscControlPointRequest.SetCumulativeWheelRevolutions ->
                    if (listener.onCscSetCumulativeWheelRevolutions(request.value)) {
                        CscControlPoint.SUCCESS
                    } else {
                        CscControlPoint.OPERATION_FAILED
                    }

                is CscControlPointRequest.Invalid -> CscControlPoint.INVALID_PARAMETER
                is CscControlPointRequest.Unsupported -> CscControlPoint.OPCODE_NOT_SUPPORTED
            }
        }
        val response = CscControlPoint.response(opcode, result)
        txScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                sendTx(
                    conn = conn,
                    characteristicUuid = BluetoothIds.UUID_SC_CONTROL_POINT,
                    payload = response,
                    confirm = true,
                    priority = GattTxQueue.Priority.CONTROL,
                    purpose = "csc-control-point-response",
                )
            } finally {
                if (ownsProcedure) conn.finishCscProcedure()
            }
        }
    }

    // ── payloads ──────────────────────────────────────────────────────────────

    private fun featurePayload(): ByteArray =
        buildFtmsFeaturePayload(
            profile,
            targets,
            upstreamPaceSupported(),
            measurementCapabilities,
        )

    private fun trainingStatusPayload(conn: PeripheralConnection): ByteArray =
        conn.procedure.trainingStatusPayload()

    private fun isUpstream(device: BluetoothDevice): Boolean {
        val up = upstreamAddress ?: return false
        return device.address.equals(up, ignoreCase = true)
    }

    /**
     * A device we must never treat as a downstream client.
     *
     * See [isPhantomDownstream] for what counts and why the live central-role set
     * is consulted rather than only the snapshot taken at server-open.
     */
    private fun isExcluded(device: BluetoothDevice): Boolean = isPhantomDownstream(
        deviceAddress = device.address,
        upstreamAddress = upstreamAddress,
        seededExclusions = excludedAddresses,
        centralRoleAddresses = runCatching(centralRoleAddresses).getOrElse { failure ->
            Timber.tag(TAG).w(failure, "could not read central-role addresses; admitting on snapshot only")
            emptySet()
        },
    )

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun seedExcludedAddresses(manager: BluetoothManager) {
        excludedAddresses.clear()
        runCatching {
            val preexisting = manager.getConnectedDevices(BluetoothProfile.GATT) +
                manager.getConnectedDevices(BluetoothProfile.GATT_SERVER)
            preexisting.forEach { excludedAddresses.add(it.address.lowercase()) }
        }.onFailure { Timber.tag(TAG).w(it, "could not enumerate pre-existing connections") }
        if (excludedAddresses.isNotEmpty()) {
            Timber.tag(TAG).i(
                "excluding %d pre-existing connection(s) from client admission",
                excludedAddresses.size,
            )
        }
    }

    /**
     * Promotes a connected device to a real client on its first ATT engagement
     * (MTU exchange, characteristic/descriptor read, or write). Phantom links -
     * the upstream machine, or a device already connected when we opened the
     * server - never touch our GATT database, so they are never admitted, never
     * pause advertising, and never occupy a client slot. Returns null for an
     * excluded device; otherwise the (new or existing) per-connection session.
     */
    private fun admit(device: BluetoothDevice): PeripheralConnection? {
        if (isExcluded(device)) return null
        val key = addressKey(device)
        connections[key]?.let { return it }
        val created = PeripheralConnection(device, connectionSequence.incrementAndGet())
        val existing = connections.putIfAbsent(key, created)
        if (existing != null) return existing
        connectedDevices.add(key)
        clearEngagementWatchdog(key)
        Timber.tag(TAG).i("client engaged %s (clients=%d)", deviceLabel(device), connections.size)
        PeripheralCaptureHooks.clientConnected(
            address = device.address,
            sessionId = created.sessionId,
            mtu = created.attMtu,
        )
        listener.onClientConnected(device)
        return created
    }

    private fun matchingConnection(device: BluetoothDevice): PeripheralConnection? =
        connections[addressKey(device)]

    private fun deviceLabel(device: BluetoothDevice): String =
        "#${device.address.hashCode().toUInt().toString(16).takeLast(6)}"

    @SuppressLint("MissingPermission")
    private fun preferLe1m(device: BluetoothDevice, reason: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val server = gattServer ?: return
        runCatching {
            /*
             * ESSENTIAL GARMIN COMPATIBILITY WORKAROUND — DO NOT REMOVE.
             *
             * This pins both directions of the downstream Garmin connection to LE
             * 1M. It is deliberately a per-connection GATT-server request, not an
             * advertising setting. LE 1M has more than enough capacity for the
             * FTMS stream and avoids the observed 2M PHY/channel-map procedure
             * collision that otherwise drops an active session.
             *
             * Android treats setPreferredPhy() as an asynchronous preference.
             * onPhyUpdate/onPhyRead log the controller's actual TX/RX selection;
             * callers must not interpret this method returning as confirmation.
             */
            server.setPreferredPhy(
                device,
                BluetoothDevice.PHY_LE_1M_MASK,
                BluetoothDevice.PHY_LE_1M_MASK,
                BluetoothDevice.PHY_OPTION_NO_PREFERRED,
            )
            Timber.tag(TAG).i("requested LE 1M PHY for %s (%s)", deviceLabel(device), reason)
        }.onFailure {
            Timber.tag(TAG).w(it, "could not request LE 1M PHY for %s", deviceLabel(device))
        }
    }

    private fun phyName(phy: Int): String = when (phy) {
        BluetoothDevice.PHY_LE_1M -> "LE_1M"
        BluetoothDevice.PHY_LE_2M -> "LE_2M"
        BluetoothDevice.PHY_LE_CODED -> "LE_CODED"
        else -> "UNKNOWN($phy)"
    }

    // ── engagement watchdog ────────────────────────────────────────────────────

    /**
     * Arms the awaiting-engagement watchdog for a device that just connected and
     * paused advertising. If the device never engages our GATT database within
     * [ENGAGEMENT_WATCHDOG_MS] - it connected but stalled before any ATT traffic -
     * we release the wedged link and resume advertising so other centrals (and a
     * retry from the same one) can find the machine again. Sits below the watch
     * app's own 90 s connect watchdog so we recover first, not after it gives up.
     */
    @SuppressLint("MissingPermission")
    private fun armEngagementWatchdog(device: BluetoothDevice, epoch: Long) {
        val key = addressKey(device)
        clearEngagementWatchdog(key)
        val runnable = Runnable {
            engagementWatchdogs.remove(key)
            if (!isCurrentEpoch(epoch)) return@Runnable
            // Engaged in the meantime, or already gone: nothing to reap.
            if (connections.containsKey(key) || !connectedDevices.contains(key)) return@Runnable
            Timber.tag(TAG).i(
                "engagement watchdog fired %s after %dms; releasing stalled link and resuming advertising",
                deviceLabel(device),
                ENGAGEMENT_WATCHDOG_MS,
            )
            connectedDevices.remove(key)
            runCatching { gattServer?.cancelConnection(device) }
            listener.onClientLinkStateChanged(connectedDevices.isNotEmpty())
            if (connectedDevices.isEmpty()) runCatching { resumeAdvertising() }
        }
        engagementWatchdogs[key] = runnable
        main.postDelayed(runnable, ENGAGEMENT_WATCHDOG_MS)
    }

    private fun clearEngagementWatchdog(key: String) {
        engagementWatchdogs.remove(key)?.let(main::removeCallbacks)
    }

    private fun clearAllEngagementWatchdogs() {
        engagementWatchdogs.values.forEach(main::removeCallbacks)
        engagementWatchdogs.clear()
    }

    // ── pairing auto-accept ────────────────────────────────────────────────────

    /**
     * Registers a receiver that auto-confirms Just Works / consent pairing for a
     * forming downstream link. A central such as Garmin Connect IQ initiates a
     * bond on connect; if the request waits on user consent the central can stall
     * before service discovery. We never require encryption (see
     * [PeripheralGattRegistry]) so any bond is incidental - confirming it
     * immediately just keeps the link moving. Scoped to devices we are actively
     * servicing; other pairings are left to the system. Best-effort: on builds
     * where setPairingConfirmation is privileged the call is a no-op and pure Just
     * Works completes without us.
     */
    private fun registerPairingReceiver() {
        if (pairingReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_PAIRING_REQUEST) return
                val device: BluetoothDevice? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java,
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                if (device == null || isExcluded(device)) return
                // Only cooperate with a link we are actually forming/servicing.
                if (!connectedDevices.contains(addressKey(device))) return
                val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                // PAIRING_VARIANT_CONSENT (3) is @hide in the public SDK; BLE Just
                // Works surfaces as it, so match the literal alongside the public
                // passkey-confirmation variant. Anything else (PIN/passkey entry)
                // is a real user secret and is left to the system.
                if (variant != PAIRING_VARIANT_CONSENT &&
                    variant != BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION
                ) {
                    return
                }
                val confirmed = runCatching { device.setPairingConfirmation(true) }
                    .getOrElse { error ->
                        Timber.tag(TAG).w(
                            error,
                            "auto-confirm pairing %s threw (may need privileged perm)",
                            deviceLabel(device),
                        )
                        false
                    }
                Timber.tag(TAG).i(
                    "auto-confirm pairing %s variant=%d result=%s",
                    deviceLabel(device), variant, confirmed,
                )
                if (confirmed) runCatching { abortBroadcast() }
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST).apply {
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY
        }
        runCatching {
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onSuccess {
            pairingReceiver = receiver
        }.onFailure { Timber.tag(TAG).w(it, "could not register pairing receiver") }
    }

    private fun unregisterPairingReceiver() {
        val receiver = pairingReceiver ?: return
        runCatching { context.unregisterReceiver(receiver) }
        pairingReceiver = null
    }

    // ── outbound ──────────────────────────────────────────────────────────────

    /**
     * Enqueues one complete measurement record as a single atomic payload.
     *
     * [frames] is the whole FTMS record: one frame normally, several when More
     * Data continuation is required by the negotiated MTU. Passing them together
     * is what lets [GattTxQueue] treat the record as indivisible — a fragmented
     * record can never be half-sent, and only whole records are ever superseded.
     *
     * Returns [GattTxQueue.Outcome.SUPERSEDED] when a newer record replaced this
     * one before the link was free. That is the normal steady state on a slow
     * client and is not an error.
     */
    // Internal, like [GattTxQueue] itself: the outcome type is a transmission-queue
    // detail and must not leak out of the peripheral module surface.
    internal suspend fun notifyDataRecord(
        device: BluetoothDevice,
        frames: List<ByteArray>,
    ): GattTxQueue.Outcome {
        val conn = matchingConnection(device) ?: return GattTxQueue.Outcome.FAILED
        if (!conn.dataSubscribed) return GattTxQueue.Outcome.FAILED
        return sendPayload(
            conn = conn,
            characteristicUuid = profile.dataCharacteristic,
            frames = frames,
            confirm = false,
            priority = GattTxQueue.Priority.DATA,
            purpose = "machine-data",
        )
    }

    // No single-frame data entry point: a measurement record is atomic by
    // construction, and a back door that posts one frame would let a caller
    // reintroduce the half-sent fragmented record this design exists to prevent.

    internal suspend fun notifyCscMeasurement(
        device: BluetoothDevice,
        payload: ByteArray,
    ): GattTxQueue.Outcome {
        val conn = matchingConnection(device) ?: return GattTxQueue.Outcome.FAILED
        if (!conn.cscMeasurementSubscribed) return GattTxQueue.Outcome.FAILED
        return sendPayload(
            conn = conn,
            characteristicUuid = BluetoothIds.UUID_CSC_MEASUREMENT,
            frames = listOf(payload),
            confirm = false,
            priority = GattTxQueue.Priority.DATA,
            purpose = "csc-measurement",
        )
    }

    internal suspend fun notifyCyclingPowerMeasurement(
        device: BluetoothDevice,
        payload: ByteArray,
    ): GattTxQueue.Outcome {
        val conn = matchingConnection(device) ?: return GattTxQueue.Outcome.FAILED
        if (!conn.cyclingPowerMeasurementSubscribed) return GattTxQueue.Outcome.FAILED
        return sendPayload(
            conn = conn,
            characteristicUuid = BluetoothIds.UUID_CYCLING_POWER_MEASUREMENT,
            frames = listOf(payload),
            confirm = false,
            priority = GattTxQueue.Priority.DATA,
            purpose = "cycling-power-measurement",
        )
    }

    /** Transmission-queue counters, surfaced for downstream-lag diagnosis. */
    internal fun txStats(): GattTxQueue.Stats = txQueue.stats()

    /**
     * Publishes one Fitness Machine Status notification to every subscribed
     * client. Used by the controller to mirror upstream machine events (safety
     * key, control-permission loss, target changes made on the machine itself)
     * that did not originate from a downstream Control Point write.
     */
    suspend fun broadcastMachineStatus(payload: ByteArray): Int {
        var delivered = 0
        for (conn in connections.values) {
            if (!conn.statusSubscribed) continue
            val ok = sendTx(
                conn = conn,
                characteristicUuid = BluetoothIds.UUID_FITNESS_MACHINE_STATUS,
                payload = payload,
                confirm = false,
                priority = GattTxQueue.Priority.CONTROL,
                purpose = "machine-status-broadcast",
            )
            if (ok) delivered++
        }
        return delivered
    }

    /**
     * Drops control ownership on every attached client. Called when the upstream
     * machine reports that it has taken control back, so a downstream client
     * cannot keep issuing targets the machine will ignore.
     */
    fun revokeAllControl() {
        connections.values.forEach { it.procedure.resetForDisconnect() }
    }

    private suspend fun sendTx(
        conn: PeripheralConnection,
        characteristicUuid: UUID,
        payload: ByteArray,
        confirm: Boolean,
        priority: GattTxQueue.Priority,
        purpose: String,
    ): Boolean = txQueue.send(
        sessionId = conn.sessionId,
        deviceAddress = conn.device.address,
        characteristicUuid = characteristicUuid,
        payload = payload,
        confirm = confirm,
        priority = priority,
        purpose = purpose,
    )

    private suspend fun sendPayload(
        conn: PeripheralConnection,
        characteristicUuid: UUID,
        frames: List<ByteArray>,
        confirm: Boolean,
        priority: GattTxQueue.Priority,
        purpose: String,
    ): GattTxQueue.Outcome = txQueue.sendPayload(
        sessionId = conn.sessionId,
        deviceAddress = conn.device.address,
        characteristicUuid = characteristicUuid,
        frames = frames,
        confirm = confirm,
        priority = priority,
        purpose = purpose,
    )

    @SuppressLint("MissingPermission")
    private fun postTx(request: GattTxQueue.Request): Boolean {
        val conn = connections[request.deviceAddress.lowercase()] ?: return false
        if (conn.sessionId != request.sessionId) return false
        val stillSubscribed = when (request.characteristicUuid) {
            profile.dataCharacteristic -> conn.dataSubscribed
            BluetoothIds.UUID_FITNESS_MACHINE_CONTROL_POINT -> conn.controlPointSubscribed
            BluetoothIds.UUID_FITNESS_MACHINE_STATUS -> conn.statusSubscribed
            BluetoothIds.UUID_TRAINING_STATUS -> conn.trainingStatusSubscribed
            BluetoothIds.UUID_CSC_MEASUREMENT -> conn.cscMeasurementSubscribed
            BluetoothIds.UUID_CYCLING_POWER_MEASUREMENT -> conn.cyclingPowerMeasurementSubscribed
            BluetoothIds.UUID_SC_CONTROL_POINT -> conn.cscControlPointSubscribed
            else -> false
        }
        if (!stillSubscribed) return false
        val server = gattServer ?: return false
        val serviceUuid = when (request.characteristicUuid) {
            BluetoothIds.UUID_CSC_MEASUREMENT,
            BluetoothIds.UUID_SC_CONTROL_POINT,
            -> BluetoothIds.UUID_CSC_SERVICE

            BluetoothIds.UUID_CYCLING_POWER_MEASUREMENT -> BluetoothIds.UUID_CYCLING_POWER_SERVICE
            else -> BluetoothIds.UUID_FTMS_SERVICE
        }
        val characteristic = server.getService(serviceUuid)
            ?.getCharacteristic(request.characteristicUuid) ?: return false
        // Recorded here rather than at encode time: this is the exact octet
        // sequence handed to the stack, one capture line per frame. A fragmented
        // record therefore appears as the several notifications a client actually
        // receives, which is the only way to see whether it could reassemble them.
        PeripheralCaptureHooks.notified(
            address = conn.device.address,
            characteristic = request.characteristicUuid,
            payload = request.payload,
        )
        return notifyCompat(
            server = server,
            device = conn.device,
            characteristic = characteristic,
            confirm = request.confirm,
            value = request.payload,
        )
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun notifyCompat(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        confirm: Boolean,
        value: ByteArray,
    ): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server.notifyCharacteristicChanged(device, characteristic, confirm, value) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            characteristic.value = value
            server.notifyCharacteristicChanged(device, characteristic, confirm)
        }
    } catch (t: Throwable) {
        Timber.tag(TAG).w(t, "notify failed for %s", shortUuidLabel(characteristic.uuid))
        false
    }

    companion object {
        private const val TAG = "FtmsAdvertiser"

        private const val SERVICE_ADD_TIMEOUT_MS = 5_000L
        private const val ADVERTISE_START_TIMEOUT_MS = 3_000L

        /**
         * How long a connected-but-unengaged peer may hold the link (and keep
         * advertising paused) before we release it and resume advertising. 75 s
         * sits under a typical watch app's 90 s connect watchdog so we recover
         * first.
         */
        private const val ENGAGEMENT_WATCHDOG_MS = 75_000L

        /** BluetoothDevice.PAIRING_VARIANT_CONSENT - @hide in the public SDK. */
        private const val PAIRING_VARIANT_CONSENT = 3

        fun advertiseErrorName(code: Int): String = when (code) {
            AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE"
            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS"
            AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
            AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
            AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
            else -> "UNKNOWN($code)"
        }
    }
}

internal fun shouldPinGarminConnection(
    garminCompatibilityEnabled: Boolean,
    cachedDeviceName: String?,
    deviceAddress: String? = null,
): Boolean =
    garminCompatibilityEnabled ||
        cachedDeviceName?.contains("garmin", ignoreCase = true) == true ||
        GARMIN_PUBLIC_ADDRESS_PREFIXES.any { prefix ->
            deviceAddress?.startsWith("$prefix:", ignoreCase = true) == true
        }

/** Garmin public-address prefix observed in the failing Connect IQ capture. */
private val GARMIN_PUBLIC_ADDRESS_PREFIXES = setOf("38:F9:F5")

/**
 * Whether a device reported by the GATT server is a phantom rather than a client.
 *
 * ### What Android does, and why it needs undoing
 *
 * `BluetoothGattServer` reports a connection callback for *any* device linked to
 * this phone, including devices FitBridge itself connected to as a **central**. A
 * treadmill or a heart-rate strap therefore arrives looking exactly like a watch
 * that wants our data. Admitting one costs a client slot, pauses advertising
 * while we wait for an engagement that will never come, and locks out the real
 * downstream client until a watchdog gives up on it.
 *
 * ### The defect this fixes (session of 2026-08-07, 15:59)
 *
 * Exclusion used to be two things, both fixed at a moment in time: a single
 * `upstreamAddress` - only ever the machine - and a snapshot of connected devices
 * taken **before** opening the server. Sensors are connected by the user *after*
 * the server opens, so they were in neither.
 *
 * A Garmin HRM-Pro connected five seconds after the server opened, dropped, and
 * reconnected. The reconnection was admitted as a downstream client:
 *
 * ```
 * 16:00:04.150  connection request #f0c438; pausing advertising (awaiting engagement)
 * 16:01:19.162  engagement watchdog fired #f0c438 after 75000ms
 * ```
 *
 * Seventy-five of the session's ninety-five seconds with no advertisement, so a
 * watch scanning in that window found nothing. `#f0c438` is the address hash of
 * the strap FitBridge was itself reading.
 *
 * ### Why all three inputs are kept
 *
 * - [centralRoleAddresses] is read live, so a link opened at any point during the
 *   session is covered. This is the fix; the registry lists a device from the
 *   moment its context opens, which is before the server callback can fire.
 * - [seededExclusions] still covers devices connected by *other apps* before we
 *   opened, which FitBridge's own registry knows nothing about.
 * - [upstreamAddress] is retained because it is set explicitly by the controller
 *   and does not depend on the registry having caught up.
 */
internal fun isPhantomDownstream(
    deviceAddress: String?,
    upstreamAddress: String?,
    seededExclusions: Set<String>,
    centralRoleAddresses: Set<String>,
): Boolean {
    val key = deviceAddress?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
    if (upstreamAddress?.lowercase() == key) return true
    if (seededExclusions.any { it.lowercase() == key }) return true
    return centralRoleAddresses.any { it.lowercase() == key }
}
