package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral

import android.Manifest
import android.annotation.SuppressLint
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
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.engine.BluetoothIds
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.Concept2Machine
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.Concept2Pm5Encoder
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.Concept2Pm5Snapshot
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.Concept2WorkoutProgram
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.SimulatorPersona
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/** PM5-only Android GATT server with capture-bounded C2a short-frame CSAFE transport. */
class Concept2Pm5PeripheralAdvertiser(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onState(advertising: Boolean, connected: Boolean, subscribed: Boolean, error: String?)
        fun onPacketSent()
    }

    private data class Frame(
        val uuid: java.util.UUID,
        val value: ByteArray,
        val conflatable: Boolean,
        val transactional: Boolean = false,
    )

    private class Client(val device: BluetoothDevice) {
        val session = Concept2Pm5Session()
        var mtu = 23
        var nextDueMs = 0L
        var nextFtmsDueMs = 0L
        var nextAdditionalStatus3DueMs = 0L
        var lastSplitSequence = 0
        var lastSummarySequence = 0
        var lastForceCurveSequence = 0
        var inFlight = false
        var postedAtMs = 0L
        val pending = ArrayDeque<Frame>()
        val pacing = Concept2Pm5EmissionPacing()
        var delivered = 0L
        var superseded = 0L
        var dropped = 0L
        var failedPosts = 0L
        var lastAckLatencyMs = 0L
        var ackLatencyTotalMs = 0L
        var ackLatencySamples = 0L
        var maxAckLatencyMs = 0L
        var maxPending = 0
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val epoch = AtomicLong(0)
    private val clients = ConcurrentHashMap<String, Client>()
    private val advertisingGate = ConnectableAdvertisingGate()
    private val connectionRetention = PeripheralConnectionRetentionGuard()
    private var server: BluetoothGattServer? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var notifier: Job? = null
    private var persona: SimulatorPersona.Concept2Pm5? = null
    private var profile: Concept2Pm5GattProfile? = null
    private var pendingServices = ArrayDeque<BluetoothGattService>()
    private var startResult: ((Result<Unit>) -> Unit)? = null
    private var nextDiagnosticsDueMs = 0L

    @Volatile private var latest: Concept2Pm5Snapshot? = null
    @Volatile private var latestWorkoutProgram: Concept2WorkoutProgram? = null
    @Volatile var isAdvertising: Boolean = false
        private set

    fun publish(snapshot: Concept2Pm5Snapshot) {
        latest = snapshot
        if (!snapshot.strokeDataAvailable) {
            clients.values.forEach { client ->
                synchronized(client) { client.pending.removeIf(::isStrokeFrame) }
            }
        }
    }

    private fun isStrokeFrame(frame: Frame): Boolean =
        frame.uuid == BluetoothIds.UUID_C2_STROKE_DATA ||
            frame.uuid == BluetoothIds.UUID_C2_ADDITIONAL_STROKE_DATA ||
            frame.uuid == BluetoothIds.UUID_C2_FORCE_CURVE_DATA ||
            (frame.uuid == BluetoothIds.UUID_C2_MULTIPLEXED_INFORMATION &&
                frame.value.firstOrNull()?.let { (it.toInt() and 0xFF) in STROKE_IDS } == true)

    fun hasWorkoutMeasurementSubscription(): Boolean =
        clients.values.any { it.session.hasWorkoutMeasurementSubscription }

    fun workoutProgram(): Concept2WorkoutProgram? = latestWorkoutProgram

    /** Returns accepted client termination as an edge; it never closes the GATT link. */
    fun consumeWorkoutTerminationRequest(): Boolean =
        clients.values.fold(false) { pending, client ->
            client.session.consumeWorkoutTerminationRequest() || pending
        }

    /** Reset CSAFE workout state for connected clients while retaining their subscriptions. */
    fun resetWorkoutState() {
        latestWorkoutProgram = null
        clients.values.forEach { client ->
            client.session.resetCsafeWorkoutState()
            synchronized(client) {
                client.pending.removeIf { it.uuid == BluetoothIds.UUID_C2_CSAFE_RESPONSE }
            }
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE])
    fun start(persona: SimulatorPersona.Concept2Pm5, onResult: (Result<Unit>) -> Unit) {
        stop()
        val currentEpoch = epoch.incrementAndGet()
        this.persona = persona
        profile = Concept2Pm5GattProfile(persona)
        startResult = onResult
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (manager == null || adapter == null || !adapter.isEnabled) {
            finishStart(Result.failure(IllegalStateException("Bluetooth is unavailable or disabled")))
            return
        }
        val opened = manager.openGattServer(context, callback(currentEpoch))
        if (opened == null) {
            finishStart(Result.failure(IllegalStateException("Could not open PM5 GATT server")))
            return
        }
        server = opened
        pendingServices = ArrayDeque(Concept2Pm5GattRegistry.buildServices(persona))
        addNextService(currentEpoch)
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        epoch.incrementAndGet()
        notifier?.cancel(); notifier = null
        advertiseCallback?.let { callback ->
            runCatching {
                val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback)
            }
        }
        advertiseCallback = null
        isAdvertising = false
        clients.values.forEach { runCatching { server?.cancelConnection(it.device) } }
        clients.clear()
        latestWorkoutProgram = null
        advertisingGate.reset()
        connectionRetention.clear()
        runCatching { server?.clearServices() }
        runCatching { server?.close() }
        server = null
        startResult?.invoke(Result.failure(IllegalStateException("PM5 start was stopped")))
        startResult = null
        listener.onState(false, false, false, null)
    }

    @SuppressLint("MissingPermission")
    private fun addNextService(currentEpoch: Long) {
        if (epoch.get() != currentEpoch) return
        val next = pendingServices.pollFirst()
        if (next == null) {
            startAdvertising(currentEpoch)
        } else if (server?.addService(next) != true) {
            finishStart(Result.failure(IllegalStateException("Could not add PM5 service ${next.uuid}")))
        }
    }

    @SuppressLint("MissingPermission")
    private fun pauseAdvertising() {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        advertiseCallback?.let { callback ->
            runCatching { manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback) }
        }
        advertiseCallback = null
        isAdvertising = false
        listener.onState(false, clients.isNotEmpty(), anySubscribed(), null)
    }

    private fun resumeAdvertising(currentEpoch: Long) {
        if (server == null || clients.isNotEmpty() || advertiseCallback != null || isAdvertising) return
        startAdvertising(currentEpoch)
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(currentEpoch: Long) {
        val selected = persona ?: return
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val advertiser = manager?.adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            finishStart(Result.failure(IllegalStateException("BLE advertising is unavailable")))
            return
        }
        val plan = Concept2Pm5AdvertisingPlan.forPersona(selected)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(plan.includeNameInAdvertisement)
            .setIncludeTxPowerLevel(plan.includeTxPowerInAdvertisement)
            .also { builder ->
                plan.advertisingServiceUuids.forEach { builder.addServiceUuid(ParcelUuid(it)) }
            }
            .build()
        val scan = AdvertiseData.Builder()
            .setIncludeDeviceName(!plan.includeNameInAdvertisement)
            .also { builder ->
                plan.scanResponseServiceUuids.forEach { builder.addServiceUuid(ParcelUuid(it)) }
                plan.scanResponseFtmsServiceData?.let {
                    builder.addServiceData(ParcelUuid(BluetoothIds.UUID_FTMS_SERVICE), it)
                }
            }
            .build()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                if (epoch.get() != currentEpoch || advertiseCallback !== this) return
                isAdvertising = true
                listener.onState(true, clients.isNotEmpty(), anySubscribed(), null)
                startNotifier()
                finishStart(Result.success(Unit))
            }

            override fun onStartFailure(errorCode: Int) {
                if (epoch.get() != currentEpoch || advertiseCallback !== this) return
                val message = "PM5 advertising failed ($errorCode)"
                listener.onState(false, clients.isNotEmpty(), anySubscribed(), message)
                finishStart(Result.failure(IllegalStateException(message)))
            }
        }
        advertiseCallback = callback
        advertiser.startAdvertising(settings, data, scan, callback)
    }

    private fun finishStart(result: Result<Unit>) {
        val callback = startResult ?: return
        startResult = null
        main.post { callback(result) }
    }

    private fun callback(callbackEpoch: Long) = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (epoch.get() != callbackEpoch) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finishStart(Result.failure(IllegalStateException("PM5 service add failed ($status)")))
            } else {
                addNextService(callbackEpoch)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (epoch.get() != callbackEpoch) return
            val key = device.address.lowercase()
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                clients.putIfAbsent(key, Client(device))
                if (advertisingGate.onConnected(key) == ConnectableAdvertisingGate.Action.PAUSE) {
                    pauseAdvertising()
                }
                connectionRetention.retainOnce(key) {
                    val retained = runCatching { server?.connect(device, false) == true }
                        .getOrElse { error ->
                            Timber.w(error, "Concept2 GATT server could not retain inbound connection")
                            false
                        }
                    if (!retained) {
                        Timber.w("Concept2 GATT server did not retain inbound connection")
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectionRetention.release(key)
                clients.remove(key)?.session?.reset()
                if (advertisingGate.onDisconnected(key) == ConnectableAdvertisingGate.Action.RESUME) {
                    resumeAdvertising(callbackEpoch)
                }
            }
            listener.onState(isAdvertising, clients.isNotEmpty(), anySubscribed(), null)
        }

        /*
         * The API-36 public SDK stub does not declare this callback, so this intentionally has no
         * `override` modifier. Samsung's runtime BluetoothGattServer already dispatches the exact
         * platform signature (and logs it in the supplied capture). Normal JVM virtual dispatch
         * reaches this method on such a runtime; on runtimes without the callback it is inert.
         *
         * Do not infer an interval when this hook is absent. The selected PM5 sample period remains
         * authoritative until Android supplies an actual negotiated value.
         */
        @Suppress("unused")
        fun onConnectionUpdated(
            device: BluetoothDevice,
            interval: Int,
            latency: Int,
            supervisionTimeout: Int,
            status: Int,
        ) {
            if (epoch.get() != callbackEpoch) return
            val client = clients[device.address.lowercase()] ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.w(
                    "PM5 connection update failed status=%d; retaining selected sample period",
                    status,
                )
                return
            }
            val now = android.os.SystemClock.elapsedRealtime()
            val accepted = synchronized(client) {
                client.pacing.recordNegotiated(interval, latency, supervisionTimeout).also {
                    if (it) {
                        client.nextDueMs = now +
                            client.pacing.effectivePeriodMs(client.session.periodMs)
                    }
                }
            }
            if (accepted) {
                Timber.i(
                    "PM5 connection pacing measured interval=%dms latency=%d timeout=%dms effective=%dms",
                    client.pacing.negotiatedIntervalMs,
                    latency,
                    client.pacing.supervisionTimeoutMs,
                    client.pacing.effectivePeriodMs(client.session.periodMs),
                )
            } else {
                Timber.w(
                    "PM5 ignored invalid connection update intervalUnits=%d latency=%d timeoutUnits=%d",
                    interval,
                    latency,
                    supervisionTimeout,
                )
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            clients[device.address.lowercase()]?.mtu = mtu.coerceIn(23, 517)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val client = clients[device.address.lowercase()] ?: Client(device).also { clients[device.address.lowercase()] = it }
            val value = when (characteristic.uuid) {
                BluetoothIds.UUID_C2_STATUS_SAMPLE_RATE -> byteArrayOf(client.session.sampleRate.toByte())
                BluetoothIds.UUID_C2_CSAFE_RESPONSE -> client.session.csafeResponse
                BluetoothIds.UUID_FITNESS_MACHINE_FEATURE -> profile?.readFtms(characteristic.uuid)
                else -> profile?.readDeviceInformation(characteristic.uuid, 0, client.mtu)
            }
            respondRead(device, requestId, offset, value)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val client = clients[device.address.lowercase()] ?: Client(device).also { clients[device.address.lowercase()] = it }
            val structurallyWritable = !preparedWrite && offset == 0
            var csafeNotification: ByteArray? = null
            val status = when (characteristic.uuid) {
                BluetoothIds.UUID_C2_STATUS_SAMPLE_RATE -> {
                    val ok = structurallyWritable && value.size == 1 &&
                        client.session.setSampleRate(value[0].toInt() and 0xFF)
                    if (ok) {
                        client.nextDueMs = 0L
                        client.nextAdditionalStatus3DueMs = 0L
                    }
                    if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
                }
                BluetoothIds.UUID_C2_HEART_RATE_RECEIVE -> {
                    if (structurallyWritable && client.session.setHeartRateInput(value)) {
                        BluetoothGatt.GATT_SUCCESS
                    } else {
                        BluetoothGatt.GATT_FAILURE
                    }
                }
                BluetoothIds.UUID_C2_CSAFE_REQUEST -> {
                    if (!structurallyWritable) {
                        BluetoothGatt.GATT_FAILURE
                    } else {
                        when (val result = client.session.acceptCsafeRequest(
                            value,
                            android.os.SystemClock.elapsedRealtime(),
                        )) {
                            is Concept2CsafeLink.RequestResult.Accepted -> {
                                csafeNotification = client.session.notificationForAcceptedCsafe(result)
                                latestWorkoutProgram = client.session.csafeWorkoutProgram
                                BluetoothGatt.GATT_SUCCESS
                            }
                            is Concept2CsafeLink.RequestResult.Rejected -> when (result.reason) {
                                Concept2CsafeLink.RejectionReason.UNSUPPORTED ->
                                    BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                                Concept2CsafeLink.RejectionReason.MALFORMED,
                                Concept2CsafeLink.RejectionReason.TOO_SOON -> BluetoothGatt.GATT_FAILURE
                            }
                        }
                    }
                }
                else -> BluetoothGatt.GATT_FAILURE
            }
            if (responseNeeded) server?.sendResponse(
                device,
                requestId,
                status,
                0,
                null,
            )
            csafeNotification?.let {
                enqueue(
                    client,
                    Frame(
                        BluetoothIds.UUID_C2_CSAFE_RESPONSE,
                        it,
                        conflatable = false,
                        transactional = true,
                    ),
                )
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            val client = clients[device.address.lowercase()]
            val value = if (descriptor.uuid == BluetoothIds.UUID_CCCD && client != null) {
                if (client.session.isSubscribed(descriptor.characteristic.uuid)) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            } else null
            respondRead(device, requestId, offset, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            val client = clients[device.address.lowercase()] ?: Client(device).also { clients[device.address.lowercase()] = it }
            val enable = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            val disable = value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
            val ok = descriptor.uuid == BluetoothIds.UUID_CCCD && !preparedWrite && offset == 0 && (enable || disable)
            if (ok) client.session.setSubscribed(descriptor.characteristic.uuid, enable)
            if (ok && descriptor.characteristic.uuid == BluetoothIds.UUID_ROWING_MACHINE_DATA) {
                client.nextFtmsDueMs = 0L
                if (disable) synchronized(client) {
                    client.pending.removeIf { it.uuid == BluetoothIds.UUID_ROWING_MACHINE_DATA }
                }
            }
            if (responseNeeded) server?.sendResponse(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null)
            if (ok && enable && descriptor.characteristic.uuid == BluetoothIds.UUID_C2_ADDITIONAL_STATUS_3) {
                latest?.let {
                    enqueueAdditionalStatus3(
                        client,
                        it,
                        force = true,
                        nowMs = android.os.SystemClock.elapsedRealtime(),
                    )
                }
            }
            listener.onState(isAdvertising, clients.isNotEmpty(), anySubscribed(), null)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val client = clients[device.address.lowercase()] ?: return
            val now = android.os.SystemClock.elapsedRealtime()
            synchronized(client) {
                client.inFlight = false
                client.lastAckLatencyMs = (now - client.postedAtMs).coerceAtLeast(0L)
                client.ackLatencyTotalMs += client.lastAckLatencyMs
                client.ackLatencySamples++
                client.maxAckLatencyMs = max(client.maxAckLatencyMs, client.lastAckLatencyMs)
                if (status == BluetoothGatt.GATT_SUCCESS) client.delivered++ else client.failedPosts++
            }
            if (status == BluetoothGatt.GATT_SUCCESS) listener.onPacketSent()
            drain(client)
        }
    }

    @SuppressLint("MissingPermission")
    private fun respondRead(device: BluetoothDevice, requestId: Int, offset: Int, whole: ByteArray?) {
        val status: Int
        val value: ByteArray?
        if (whole == null) {
            status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED; value = null
        } else if (offset < 0 || offset > whole.size) {
            status = BluetoothGatt.GATT_INVALID_OFFSET; value = null
        } else {
            status = BluetoothGatt.GATT_SUCCESS; value = whole.copyOfRange(offset, whole.size)
        }
        server?.sendResponse(device, requestId, status, offset.coerceAtLeast(0), value)
    }

    private fun startNotifier() {
        notifier?.cancel()
        nextDiagnosticsDueMs = android.os.SystemClock.elapsedRealtime() + DIAGNOSTICS_PERIOD_MS
        notifier = scope.launch {
            while (isActive) {
                val now = android.os.SystemClock.elapsedRealtime()
                val snapshot = latest
                if (snapshot != null) clients.values.forEach { client ->
                    if (now >= client.nextFtmsDueMs &&
                        client.session.isSubscribed(BluetoothIds.UUID_ROWING_MACHINE_DATA)
                    ) {
                        client.nextFtmsDueMs = now + FTMS_ROWER_PERIOD_MS
                        enqueueFtmsRower(client, snapshot)
                    }
                    enqueueAdditionalStatus3(
                        client,
                        snapshot,
                        force = now >= client.nextAdditionalStatus3DueMs,
                        nowMs = now,
                    )
                    if (now >= client.nextDueMs) {
                        client.nextDueMs = now +
                            client.pacing.effectivePeriodMs(client.session.periodMs)
                        enqueueLive(client, snapshot)
                    }
                }
                if (now >= nextDiagnosticsDueMs) {
                    logDiagnostics()
                    nextDiagnosticsDueMs = now + DIAGNOSTICS_PERIOD_MS
                }
                delay(25)
            }
        }
    }

    private fun enqueueLive(client: Client, snapshot: Concept2Pm5Snapshot) {
        val liveIds = liveIdsFor(snapshot)
        liveIds.forEach { id ->
            val uuid = Concept2Pm5Session.uuidForId(id) ?: return@forEach
            if (id != ADDITIONAL_STATUS_3_ID && client.session.isSubscribed(uuid)) {
                Concept2Pm5Encoder.individual(uuid, snapshot)?.let {
                    enqueue(client, Frame(uuid, it, true))
                }
            }
        }
        client.session.nextMuxId(liveIds)?.let { id ->
            Concept2Pm5Encoder.multiplexed(id, snapshot)?.let {
                enqueue(client, Frame(BluetoothIds.UUID_C2_MULTIPLEXED_INFORMATION, it, true))
            }
        }
        if (snapshot.splitSequence > client.lastSplitSequence) {
            client.lastSplitSequence = snapshot.splitSequence
            enqueueEventIds(client, snapshot, listOf(0x37, 0x38))
        }
        if (snapshot.summarySequence > client.lastSummarySequence) {
            client.lastSummarySequence = snapshot.summarySequence
            enqueueEventIds(client, snapshot, listOf(0x39, 0x3A, 0x3C))
        }
        if (forceCurveAvailable(persona?.machine, snapshot) &&
            snapshot.forceCurveSequence > client.lastForceCurveSequence &&
            client.session.isSubscribed(BluetoothIds.UUID_C2_FORCE_CURVE_DATA)
        ) {
            client.lastForceCurveSequence = snapshot.forceCurveSequence
            enqueueSequence(
                client,
                Concept2Pm5Encoder.forceCurve(snapshot).map {
                    Frame(BluetoothIds.UUID_C2_FORCE_CURVE_DATA, it, false)
                },
            )
        }
    }

    private fun enqueueFtmsRower(client: Client, snapshot: Concept2Pm5Snapshot) {
        val selected = persona ?: return
        if (!selected.compatibility.exposesFtms || selected.machine != Concept2Machine.ROW_ERG) return
        enqueueSequence(
            client,
            Concept2Pm5Encoder.ftmsRower(snapshot).map {
                Frame(BluetoothIds.UUID_ROWING_MACHINE_DATA, it, conflatable = false)
            },
        )
    }

    private fun enqueueAdditionalStatus3(
        client: Client,
        snapshot: Concept2Pm5Snapshot,
        force: Boolean,
        nowMs: Long,
    ) {
        val uuid = BluetoothIds.UUID_C2_ADDITIONAL_STATUS_3
        if (!client.session.isSubscribed(uuid)) return
        val value = Concept2Pm5Encoder.individual(uuid, snapshot) ?: return
        if (client.session.shouldEmitAdditionalStatus3(value, force)) {
            client.nextAdditionalStatus3DueMs = nowMs +
                client.pacing.effectivePeriodMs(client.session.periodMs)
            enqueue(client, Frame(uuid, value, true))
        }
    }

    private fun enqueueEventIds(client: Client, snapshot: Concept2Pm5Snapshot, ids: List<Int>) {
        val frames = ids.mapNotNull { id ->
            val individual = Concept2Pm5Session.uuidForId(id) ?: return@mapNotNull null
            when {
                client.session.isSubscribed(individual) ->
                    Concept2Pm5Encoder.individual(individual, snapshot)?.let { Frame(individual, it, false) }
                client.session.isSubscribed(BluetoothIds.UUID_C2_MULTIPLEXED_INFORMATION) ->
                    Concept2Pm5Encoder.multiplexed(id, snapshot)?.let { Frame(BluetoothIds.UUID_C2_MULTIPLEXED_INFORMATION, it, false) }
                else -> null
            }
        }
        enqueueSequence(client, frames)
    }

    private fun enqueue(client: Client, frame: Frame) {
        synchronized(client) {
            if (frame.conflatable) {
                val before = client.pending.size
                client.pending.removeIf { it.uuid == frame.uuid }
                client.superseded += before - client.pending.size
            }
            if (client.pending.size >= MAX_PENDING) {
                val replaceable = client.pending.firstOrNull { it.conflatable }
                if (replaceable == null) {
                    client.dropped++
                    return
                }
                client.pending.remove(replaceable)
                client.dropped++
            }
            enqueuePm5ByPriority(client.pending, frame, frame.transactional)
            client.maxPending = max(client.maxPending, client.pending.size)
        }
        drain(client)
    }

    /** Adds one force-curve or summary sequence atomically, or skips it whole if only events fill the queue. */
    private fun enqueueSequence(client: Client, frames: List<Frame>) {
        if (frames.isEmpty() || frames.size > MAX_PENDING) return
        synchronized(client) {
            while (client.pending.size + frames.size > MAX_PENDING) {
                val replaceable = client.pending.firstOrNull { it.conflatable }
                if (replaceable == null) {
                    client.dropped += frames.size
                    return
                }
                client.pending.remove(replaceable)
                client.dropped++
            }
            frames.forEach(client.pending::addLast)
            client.maxPending = max(client.maxPending, client.pending.size)
        }
        drain(client)
    }

    @SuppressLint("MissingPermission")
    private fun drain(client: Client) {
        val frame = synchronized(client) {
            if (client.inFlight) return
            client.pending.pollFirst()?.also {
                client.inFlight = true
                client.postedAtMs = android.os.SystemClock.elapsedRealtime()
            }
        } ?: return
        val serviceUuid = when (frame.uuid) {
            BluetoothIds.UUID_C2_CSAFE_RESPONSE -> BluetoothIds.UUID_C2_PM_CONTROL_SERVICE
            BluetoothIds.UUID_ROWING_MACHINE_DATA,
            BluetoothIds.UUID_BIKING_DATA -> BluetoothIds.UUID_FTMS_SERVICE
            else -> BluetoothIds.UUID_C2_ERG_SERVICE
        }
        val characteristic = server?.getService(serviceUuid)?.getCharacteristic(frame.uuid)
        if (characteristic == null || !notify(client.device, characteristic, frame.value)) {
            synchronized(client) {
                client.inFlight = false
                client.failedPosts++
            }
        }
    }

    private fun logDiagnostics() {
        clients.values.forEach { client ->
            val message = synchronized(client) {
                val meanAck = if (client.ackLatencySamples == 0L) 0L
                else client.ackLatencyTotalMs / client.ackLatencySamples
                "PM5 pacing requested=${client.session.periodMs}ms " +
                    "measured=${client.pacing.negotiatedIntervalMs}ms " +
                    "effective=${client.pacing.effectivePeriodMs(client.session.periodMs)}ms " +
                    "latency=${client.pacing.peripheralLatency} " +
                    "timeout=${client.pacing.supervisionTimeoutMs}ms " +
                    "delivered=${client.delivered} superseded=${client.superseded} " +
                    "dropped=${client.dropped} failed=${client.failedPosts} " +
                    "pending=${client.pending.size} maxPending=${client.maxPending} " +
                    "ackLast=${client.lastAckLatencyMs}ms ackMean=${meanAck}ms " +
                    "ackMax=${client.maxAckLatencyMs}ms"
            }
            Timber.i(message)
        }
    }

    @SuppressLint("MissingPermission")
    private fun notify(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        val activeServer = server ?: return false
        return if (Build.VERSION.SDK_INT >= 33) {
            activeServer.notifyCharacteristicChanged(device, characteristic, false, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            activeServer.notifyCharacteristicChanged(device, characteristic, false)
        }
    }

    private fun anySubscribed(): Boolean = clients.values.any { it.session.hasSubscriptions }

    fun dispose() { stop(); scope.cancel() }

    companion object {
        private const val FTMS_ROWER_PERIOD_MS = 1_000L
        private const val MAX_PENDING = 24
        private const val DIAGNOSTICS_PERIOD_MS = 10_000L
        private const val ADDITIONAL_STATUS_3_ID = 0x3E
        private val LIVE_IDS = listOf(0x31, 0x32, 0x33, 0x35, 0x36, 0x3B, ADDITIONAL_STATUS_3_ID)
        private val STROKE_IDS = setOf(0x35, 0x36)

        internal fun liveIdsFor(snapshot: Concept2Pm5Snapshot): List<Int> =
            if (snapshot.strokeDataAvailable) LIVE_IDS else LIVE_IDS.filterNot(STROKE_IDS::contains)

        /** BikeErg has captured 0x35/0x36 records but no physical handle-force curve. */
        internal fun forceCurveAvailable(
            machine: Concept2Machine?,
            snapshot: Concept2Pm5Snapshot,
        ): Boolean = snapshot.strokeDataAvailable &&
            machine != Concept2Machine.BIKE_ERG
    }
}
