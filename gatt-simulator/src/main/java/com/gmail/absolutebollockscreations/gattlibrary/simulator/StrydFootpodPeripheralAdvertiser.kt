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
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import timber.log.Timber
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.ConnectableAdvertisingGate
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.PeripheralConnectionRetentionGuard
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids

/**
 * Measurement-only Android GATT server for the Stryd footpod persona.
 *
 * Structurally this is the Precor Chrono advertiser with three differences the footpod actually
 * needs: services are added one at a time through a queue (Android only accepts one `addService`
 * in flight), subscription state is tracked *per characteristic* rather than per client, and read
 * requests are answered rather than refused - a footpod whose Feature and Sensor Location reads
 * fail is not a useful test target, and those reads are the first thing a Connect IQ or Zwift
 * client makes.
 *
 * Notifications are queued per characteristic so that a slow client cannot make the RSC stream
 * starve the CPS stream: each characteristic keeps its own single latest frame, and a newer frame
 * replaces an unsent older one rather than queueing behind it. Stale telemetry helps nobody.
 */
internal class StrydFootpodPeripheralAdvertiser(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onState(advertising: Boolean, connected: Boolean, subscribed: Boolean, error: String?)
        fun onPacketSent()
    }

    private class Client(val device: BluetoothDevice) {
        /** CCCD state per notifying characteristic. */
        val subscriptions = ConcurrentHashMap<UUID, Boolean>()
        var inFlight = false
        val pending = ArrayDeque<Pair<UUID, ByteArray>>()

        fun subscribedTo(characteristic: UUID): Boolean = subscriptions[characteristic] == true
        fun anySubscription(): Boolean = subscriptions.values.any { it }
    }

    private val main = Handler(Looper.getMainLooper())
    private val epoch = AtomicLong(0)

    /** Service publication and leak-free shutdown for this owner's GATT servers (LIB-9g). */
    private val gattSessions = GattServerSessions<BluetoothGattService>()
    private val clients = ConcurrentHashMap<String, Client>()
    private val advertisingGate = ConnectableAdvertisingGate()
    private val connectionRetention = PeripheralConnectionRetentionGuard()
    private val staticReads = StrydFootpodGattProfile.staticReadValues()
    private val pendingServices = ArrayDeque<BluetoothGattService>()
    private var server: BluetoothGattServer? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var startResult: ((Result<Unit>) -> Unit)? = null

    /** Latest value per notifying characteristic, replayed to a client that subscribes late. */
    private val latest = ConcurrentHashMap<UUID, ByteArray>()

    @Volatile var isAdvertising: Boolean = false
        private set

    /** Offer one characteristic's newest frame; it replaces any unsent frame for that UUID. */
    fun publish(characteristic: UUID, value: ByteArray) {
        latest[characteristic] = value.copyOf()
        clients.values
            .filter { it.subscribedTo(characteristic) }
            .forEach { enqueue(it, characteristic, value) }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE])
    fun start(onResult: (Result<Unit>) -> Unit) {
        stop()
        latest.clear()
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
            finishStart(Result.failure(IllegalStateException("Could not open Stryd footpod GATT server")))
            return
        }
        pendingServices.clear()
        StrydFootpodGattProfile.buildServices().forEach(pendingServices::addLast)
        if (!addNextService()) {
            finishStart(Result.failure(IllegalStateException("Could not add Stryd footpod services")))
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
        pendingServices.clear()
        clients.values.forEach { runCatching { server?.cancelConnection(it.device) } }
        clients.clear()
        advertisingGate.reset()
        connectionRetention.clear()
        gattSessions.closeCurrent()
        server = null
        startResult?.invoke(Result.failure(IllegalStateException("Stryd footpod start was stopped")))
        startResult = null
        listener.onState(false, false, false, null)
    }

    @SuppressLint("MissingPermission")
    private fun addNextService(): Boolean {
        val next = pendingServices.pollFirst() ?: return true
        return gattSessions.addService(next) == true
    }

    private fun callback(callbackEpoch: Long) = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (gattSessions.onServiceAdded(callbackEpoch, service)) return
            if (epoch.get() != callbackEpoch) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finishStart(
                    Result.failure(
                        IllegalStateException("Stryd footpod service ${service.uuid} add failed ($status)"),
                    ),
                )
                return
            }
            if (pendingServices.isEmpty()) {
                startAdvertising(callbackEpoch)
            } else if (!addNextService()) {
                finishStart(Result.failure(IllegalStateException("Could not add Stryd footpod service")))
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
                                Timber.w(error, "Stryd GATT server could not retain inbound connection")
                                false
                            }
                        if (!retained) {
                            Timber.w("Stryd GATT server did not retain inbound connection")
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
            respondRead(device, requestId, offset, readValueFor(characteristic.uuid))
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
            // Measurement only: this persona publishes no writable characteristic, so every write
            // is refused rather than silently accepted.
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
                if (client.subscribedTo(descriptor.characteristic.uuid)) {
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
            val target = descriptor.characteristic.uuid
            val enable = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            val disable = value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
            val notifiable = StrydFootpodGattProfile.notifyingCharacteristics.any { it.second == target }
            val valid = client != null && descriptor.uuid == SigUuids.UUID_CCCD &&
                notifiable && !preparedWrite && offset == 0 && (enable || disable)
            if (valid) {
                client.subscriptions[target] = enable
                if (disable) {
                    synchronized(client) { client.pending.removeAll { it.first == target } }
                }
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
            if (valid && enable) latest[target]?.let { enqueue(client, target, it) }
            publishState()
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val client = clients[device.address.lowercase()] ?: return
            synchronized(client) { client.inFlight = false }
            if (status == BluetoothGatt.GATT_SUCCESS) listener.onPacketSent()
            drain(client)
        }
    }

    /** Read values this persona serves; anything else is refused rather than answered with zero. */
    private fun readValueFor(characteristic: UUID): ByteArray? = when (characteristic) {
        SigUuids.UUID_RSC_FEATURE -> StrydFootpodEncoder.rscFeature()
        SigUuids.UUID_CYCLING_POWER_FEATURE -> StrydFootpodEncoder.cyclingPowerFeature()
        SigUuids.UUID_SENSOR_LOCATION -> StrydFootpodEncoder.sensorLocation()
        SigUuids.UUID_BATTERY_LEVEL -> latest[SigUuids.UUID_BATTERY_LEVEL]
        else -> staticReads[characteristic]
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
        val plan = StrydFootpodAdvertisingPlan.create()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(plan.includeNameInAdvertising)
            .apply { plan.serviceUuids.forEach { addServiceUuid(ParcelUuid(it)) } }
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
                val message = "Stryd footpod advertising failed ($errorCode)"
                listener.onState(false, clients.isNotEmpty(), anySubscribed(), message)
                finishStart(Result.failure(IllegalStateException(message)))
            }
        }
        advertiseCallback = callback
        advertiser.startAdvertising(settings, data, callback)
    }

    private fun finishStart(result: Result<Unit>) {
        val callback = startResult ?: return
        startResult = null
        main.post { callback(result) }
    }

    @SuppressLint("MissingPermission")
    private fun respondRead(device: BluetoothDevice, requestId: Int, offset: Int, whole: ByteArray?) {
        when {
            whole == null ->
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
            offset < 0 || offset > whole.size ->
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, 0, null)
            else -> server?.sendResponse(
                device,
                requestId,
                BluetoothGatt.GATT_SUCCESS,
                offset,
                whole.copyOfRange(offset, whole.size),
            )
        }
    }

    /** A newer frame for a characteristic replaces its unsent predecessor rather than queueing. */
    private fun enqueue(client: Client, characteristic: UUID, value: ByteArray) {
        synchronized(client) {
            client.pending.removeAll { it.first == characteristic }
            client.pending.addLast(characteristic to value.copyOf())
            while (client.pending.size > MAX_PENDING_NOTIFICATIONS) client.pending.removeFirst()
        }
        drain(client)
    }

    @SuppressLint("MissingPermission")
    private fun drain(client: Client) {
        val next = synchronized(client) {
            if (client.inFlight) return
            client.pending.pollFirst()?.also { client.inFlight = true }
        } ?: return
        val (uuid, value) = next
        val serviceUuid = StrydFootpodGattProfile.notifyingCharacteristics
            .firstOrNull { it.second == uuid }?.first
        val characteristic = serviceUuid
            ?.let { server?.getService(it)?.getCharacteristic(uuid) }
        if (characteristic == null || !notify(client.device, characteristic, value)) {
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

    private fun anySubscribed(): Boolean = clients.values.any { it.anySubscription() }

    companion object {
        /** One outstanding frame per notifying characteristic; older telemetry is not worth sending. */
        private const val MAX_PENDING_NOTIFICATIONS = 3
    }
}
