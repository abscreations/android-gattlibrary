package com.gmail.absolutebollockscreations.gattlibrary.simulator

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.GattServerSessions
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.gattOps
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
import com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import timber.log.Timber
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.ConnectableAdvertisingGate
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.PeripheralConnectionRetentionGuard
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.PrecorUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids

/** Bounded per-client notification FIFO; a complete current pair replaces stale queued data. */
internal class PrecorNotificationQueue(private val capacity: Int = 4) {
    private val values = ArrayDeque<ByteArray>()

    init {
        require(capacity > 0)
    }

    fun offerAll(frames: List<ByteArray>) {
        require(frames.size <= capacity)
        while (values.size + frames.size > capacity) values.removeFirst()
        frames.forEach { values.addLast(it.copyOf()) }
    }

    fun poll(): ByteArray? = values.pollFirst()
    fun clear() = values.clear()
    fun size(): Int = values.size
}

/** Minimal measurement-only Android GATT server for the Precor Chrono simulator persona. */
internal class PrecorChronoPeripheralAdvertiser(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onState(advertising: Boolean, connected: Boolean, subscribed: Boolean, error: String?)
        fun onPacketSent()
    }

    private class Client(val device: BluetoothDevice) {
        var subscribed = false
        var inFlight = false
        val pending = PrecorNotificationQueue(MAX_PENDING_NOTIFICATIONS)
    }

    private val main = Handler(Looper.getMainLooper())
    private val epoch = AtomicLong(0)

    /** Service publication and leak-free shutdown for this owner's GATT servers (LIB-9g). */
    private val gattSessions = GattServerSessions<BluetoothGattService>()
    private val clients = ConcurrentHashMap<String, Client>()
    private val advertisingGate = ConnectableAdvertisingGate()
    private val connectionRetention = PeripheralConnectionRetentionGuard()
    private var server: BluetoothGattServer? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var startResult: ((Result<Unit>) -> Unit)? = null

    @Volatile private var latest = emptyList<ByteArray>()
    @Volatile var isAdvertising: Boolean = false
        private set

    fun publish(sample: FtmsMockSample) {
        val notifications = PrecorChronoEncoder.encodeNotifications(sample)
        latest = notifications.map(ByteArray::copyOf)
        clients.values.filter { it.subscribed }.forEach { enqueue(it, notifications) }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE])
    fun start(onResult: (Result<Unit>) -> Unit) {
        stop()
        latest = emptyList()
        val currentEpoch = epoch.incrementAndGet()
        startResult = onResult
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (manager == null || adapter == null || !adapter.isEnabled) {
            finishStart(Result.failure(IllegalStateException("Bluetooth is unavailable or disabled")))
            return
        }
        server = manager.openGattServer(context, callback(currentEpoch))
        server?.let { gattSessions.open(currentEpoch, it.gattOps()) }
        if (server == null) {
            finishStart(Result.failure(IllegalStateException("Could not open Precor Chrono GATT server")))
            return
        }
        if (gattSessions.addService(PrecorChronoGattProfile.buildService()) != true) {
            finishStart(Result.failure(IllegalStateException("Could not add Precor Chrono service")))
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        epoch.incrementAndGet()
        advertiseCallback?.let { callback ->
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            runCatching { manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback) }
        }
        advertiseCallback = null
        isAdvertising = false
        clients.values.forEach { runCatching { server?.cancelConnection(it.device) } }
        clients.clear()
        advertisingGate.reset()
        connectionRetention.clear()
        gattSessions.closeCurrent()
        server = null
        startResult?.invoke(Result.failure(IllegalStateException("Precor Chrono start was stopped")))
        startResult = null
        listener.onState(false, false, false, null)
    }

    private fun callback(callbackEpoch: Long) = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (gattSessions.onServiceAdded(callbackEpoch, service)) return
            if (epoch.get() != callbackEpoch) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                startAdvertising(callbackEpoch)
            } else {
                finishStart(Result.failure(IllegalStateException("Precor Chrono service add failed ($status)")))
            }
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (epoch.get() != callbackEpoch) return
            val key = device.address.lowercase()
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    clients.putIfAbsent(key, Client(device))
                    if (advertisingGate.onConnected(key) == ConnectableAdvertisingGate.Action.PAUSE) {
                        pauseAdvertising()
                    }
                    connectionRetention.retainOnce(key) {
                        val retained = runCatching { server?.connect(device, false) == true }
                            .getOrElse { error ->
                                Timber.w(error, "Precor GATT server could not retain inbound connection")
                                false
                            }
                        if (!retained) {
                            Timber.w("Precor GATT server did not retain inbound connection")
                        }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectionRetention.release(key)
                    clients.remove(key)
                    if (advertisingGate.onDisconnected(key) == ConnectableAdvertisingGate.Action.RESUME) {
                        resumeAdvertising(callbackEpoch)
                    }
                }
            }
            publishState()
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
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
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            val client = clients[device.address.lowercase()]
            val whole = if (descriptor.uuid == SigUuids.UUID_CCCD && client != null) {
                if (client.subscribed) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
            } else {
                null
            }
            respondRead(device, requestId, offset, whole)
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
            val client = clients[device.address.lowercase()]
            val enable = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            val disable = value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
            val valid = client != null && descriptor.uuid == SigUuids.UUID_CCCD &&
                descriptor.characteristic.uuid == PrecorUuids.UUID_PRECOR_MEASUREMENT &&
                !preparedWrite && offset == 0 && (enable || disable)
            if (valid) {
                client.subscribed = enable
                if (disable) synchronized(client) { client.pending.clear() }
            }
            if (responseNeeded) {
                server?.sendResponse(
                    device,
                    requestId,
                    if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                    0,
                    null,
                )
            }
            if (valid && enable && latest.isNotEmpty()) enqueue(client, latest)
            publishState()
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val client = clients[device.address.lowercase()] ?: return
            synchronized(client) { client.inFlight = false }
            if (status == BluetoothGatt.GATT_SUCCESS) listener.onPacketSent()
            drain(client)
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
        publishState()
    }

    private fun resumeAdvertising(currentEpoch: Long) {
        if (server == null || clients.isNotEmpty() || advertiseCallback != null || isAdvertising) return
        startAdvertising(currentEpoch)
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(currentEpoch: Long) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val advertiser = manager?.adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            finishStart(Result.failure(IllegalStateException("BLE advertising is unavailable")))
            return
        }
        val plan = PrecorChronoAdvertisingPlan.create()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(plan.includeNameInAdvertising)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeTxPowerLevel(plan.includeTxPowerInScanResponse)
            .addServiceUuid(ParcelUuid(plan.serviceUuid))
            .addManufacturerData(plan.manufacturerId, plan.manufacturerPayload)
            .build()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                if (epoch.get() != currentEpoch || advertiseCallback !== this) return
                isAdvertising = true
                publishState()
                finishStart(Result.success(Unit))
            }

            override fun onStartFailure(errorCode: Int) {
                if (epoch.get() != currentEpoch || advertiseCallback !== this) return
                val message = "Precor Chrono advertising failed ($errorCode)"
                listener.onState(false, clients.isNotEmpty(), anySubscribed(), message)
                finishStart(Result.failure(IllegalStateException(message)))
            }
        }
        advertiseCallback = callback
        advertiser.startAdvertising(settings, data, scanResponse, callback)
    }

    private fun finishStart(result: Result<Unit>) {
        val callback = startResult ?: return
        startResult = null
        main.post { callback(result) }
    }

    @SuppressLint("MissingPermission")
    private fun respondRead(device: BluetoothDevice, requestId: Int, offset: Int, whole: ByteArray?) {
        when {
            whole == null -> server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
            offset < 0 || offset > whole.size -> server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, 0, null)
            else -> server?.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                offset,
                whole.copyOfRange(offset, whole.size),
            )
        }
    }

    private fun enqueue(client: Client, frames: List<ByteArray>) {
        synchronized(client) { client.pending.offerAll(frames) }
        drain(client)
    }

    @SuppressLint("MissingPermission")
    private fun drain(client: Client) {
        val frame = synchronized(client) {
            if (client.inFlight) return
            client.pending.poll()?.also {
                client.inFlight = true
            }
        } ?: return
        val characteristic = server?.getService(PrecorUuids.UUID_PRECOR_SERVICE)
            ?.getCharacteristic(PrecorUuids.UUID_PRECOR_MEASUREMENT)
        if (characteristic == null || !notify(client.device, characteristic, frame)) {
            synchronized(client) { client.inFlight = false }
        }
    }

    @SuppressLint("MissingPermission")
    private fun notify(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean {
        val activeServer = server ?: return false
        return if (Build.VERSION.SDK_INT >= 33) {
            activeServer.notifyCharacteristicChanged(device, characteristic, false, value) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            activeServer.notifyCharacteristicChanged(device, characteristic, false)
        }
    }

    private fun publishState() =
        listener.onState(isAdvertising, clients.isNotEmpty(), anySubscribed(), null)

    private fun anySubscribed(): Boolean = clients.values.any { it.subscribed }

    companion object {
        /** Two current pairs; older telemetry is less useful than preserving current order. */
        private const val MAX_PENDING_NOTIFICATIONS = 4
    }
}
