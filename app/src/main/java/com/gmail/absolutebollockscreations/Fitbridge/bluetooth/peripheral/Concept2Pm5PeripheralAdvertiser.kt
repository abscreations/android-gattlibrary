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
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.Concept2Pm5Encoder
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.Concept2Pm5Snapshot
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.SimulatorPersona
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
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

    private data class Frame(val uuid: java.util.UUID, val value: ByteArray, val conflatable: Boolean)

    private class Client(val device: BluetoothDevice) {
        val session = Concept2Pm5Session()
        var mtu = 23
        var nextDueMs = 0L
        var nextAdditionalStatus3DueMs = 0L
        var lastSplitSequence = 0
        var lastSummarySequence = 0
        var lastForceCurveSequence = 0
        var inFlight = false
        val pending = ArrayDeque<Frame>()
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

    @Volatile private var latest: Concept2Pm5Snapshot? = null
    @Volatile var isAdvertising: Boolean = false
        private set

    fun publish(snapshot: Concept2Pm5Snapshot) { latest = snapshot }

    fun hasWorkoutMeasurementSubscription(): Boolean =
        clients.values.any { it.session.hasWorkoutMeasurementSubscription }

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
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(plan.serviceUuid))
            .build()
        val scan = AdvertiseData.Builder().setIncludeDeviceName(true).build()
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
                            is Concept2CsafeLink.RequestResult.Accepted -> BluetoothGatt.GATT_SUCCESS
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
            synchronized(client) { client.inFlight = false }
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
        notifier = scope.launch {
            while (isActive) {
                val now = android.os.SystemClock.elapsedRealtime()
                val snapshot = latest
                if (snapshot != null) clients.values.forEach { client ->
                    enqueueAdditionalStatus3(
                        client,
                        snapshot,
                        force = now >= client.nextAdditionalStatus3DueMs,
                        nowMs = now,
                    )
                    if (now >= client.nextDueMs) {
                        client.nextDueMs = now + client.session.periodMs
                        enqueueLive(client, snapshot)
                    }
                }
                delay(25)
            }
        }
    }

    private fun enqueueLive(client: Client, snapshot: Concept2Pm5Snapshot) {
        LIVE_IDS.forEach { id ->
            val uuid = Concept2Pm5Session.uuidForId(id) ?: return@forEach
            if (id != ADDITIONAL_STATUS_3_ID && client.session.isSubscribed(uuid)) {
                Concept2Pm5Encoder.individual(uuid, snapshot)?.let {
                    enqueue(client, Frame(uuid, it, true))
                }
            }
        }
        client.session.nextMuxId(LIVE_IDS)?.let { id ->
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
        if (snapshot.forceCurveSequence > client.lastForceCurveSequence &&
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
            client.nextAdditionalStatus3DueMs = nowMs + client.session.periodMs
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
            if (frame.conflatable) client.pending.removeIf { it.uuid == frame.uuid }
            if (client.pending.size >= MAX_PENDING) {
                val replaceable = client.pending.firstOrNull { it.conflatable } ?: return
                client.pending.remove(replaceable)
            }
            client.pending.addLast(frame)
        }
        drain(client)
    }

    /** Adds one force-curve or summary sequence atomically, or skips it whole if only events fill the queue. */
    private fun enqueueSequence(client: Client, frames: List<Frame>) {
        if (frames.isEmpty() || frames.size > MAX_PENDING) return
        synchronized(client) {
            while (client.pending.size + frames.size > MAX_PENDING) {
                val replaceable = client.pending.firstOrNull { it.conflatable } ?: return
                client.pending.remove(replaceable)
            }
            frames.forEach(client.pending::addLast)
        }
        drain(client)
    }

    @SuppressLint("MissingPermission")
    private fun drain(client: Client) {
        val frame = synchronized(client) {
            if (client.inFlight) return
            client.pending.pollFirst()?.also { client.inFlight = true }
        } ?: return
        val characteristic = server?.getService(BluetoothIds.UUID_C2_ERG_SERVICE)?.getCharacteristic(frame.uuid)
        if (characteristic == null || !notify(client.device, characteristic, frame.value)) {
            synchronized(client) { client.inFlight = false }
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
        private const val MAX_PENDING = 24
        private const val ADDITIONAL_STATUS_3_ID = 0x3E
        private val LIVE_IDS = listOf(0x31, 0x32, 0x33, 0x35, 0x36, 0x3B, ADDITIONAL_STATUS_3_ID)
    }
}
