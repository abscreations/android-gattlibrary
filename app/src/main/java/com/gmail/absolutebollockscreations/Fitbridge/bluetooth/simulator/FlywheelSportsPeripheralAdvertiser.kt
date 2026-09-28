package com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator

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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.ConnectableAdvertisingGate
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.PeripheralConnectionRetentionGuard

/** Measurement-only GATT server matching the Flywheel Nordic-UART surface. */
internal class FlywheelSportsPeripheralAdvertiser(
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
        var pending: ByteArray? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val epoch = AtomicLong(0)
    private val clients = ConcurrentHashMap<String, Client>()
    private val advertisingGate = ConnectableAdvertisingGate()
    private val retention = PeripheralConnectionRetentionGuard()
    private var server: BluetoothGattServer? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var startResult: ((Result<Unit>) -> Unit)? = null
    @Volatile private var latest: ByteArray? = null
    @Volatile var isAdvertising = false
        private set

    fun publish(sample: FtmsMockSample) {
        val frame = FlywheelSportsEncoder.encode(sample)
        latest = frame.copyOf()
        clients.values.filter { it.subscribed }.forEach { enqueue(it, frame) }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE])
    fun start(onResult: (Result<Unit>) -> Unit) {
        stop()
        latest = null
        val currentEpoch = epoch.incrementAndGet()
        startResult = onResult
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (manager == null || adapter == null || !adapter.isEnabled) {
            finishStart(Result.failure(IllegalStateException("Bluetooth is unavailable or disabled")))
            return
        }
        server = manager.openGattServer(context, callback(currentEpoch))
        if (server == null) {
            finishStart(Result.failure(IllegalStateException("Could not open Flywheel GATT server")))
            return
        }
        if (server?.addService(FlywheelSportsGattProfile.buildService()) != true) {
            finishStart(Result.failure(IllegalStateException("Could not add Flywheel service")))
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        epoch.incrementAndGet()
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        advertiseCallback?.let { runCatching { manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) } }
        advertiseCallback = null
        isAdvertising = false
        clients.values.forEach { runCatching { server?.cancelConnection(it.device) } }
        clients.clear()
        advertisingGate.reset()
        retention.clear()
        runCatching { server?.clearServices() }
        runCatching { server?.close() }
        server = null
        startResult?.invoke(Result.failure(IllegalStateException("Flywheel start was stopped")))
        startResult = null
        listener.onState(false, false, false, null)
    }

    private fun callback(callbackEpoch: Long) = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (epoch.get() != callbackEpoch) return
            if (status == BluetoothGatt.GATT_SUCCESS) startAdvertising(callbackEpoch)
            else finishStart(Result.failure(IllegalStateException("Flywheel service add failed ($status)")))
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
                    retention.retainOnce(key) { runCatching { server?.connect(device, false) == true }.getOrDefault(false) }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    retention.release(key)
                    clients.remove(key)
                    if (advertisingGate.onDisconnected(key) == ConnectableAdvertisingGate.Action.RESUME) {
                        resumeAdvertising(callbackEpoch)
                    }
                }
            }
            publishState()
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
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
            // The captured protocol does not establish command semantics. RX exists only for
            // discovery compatibility, so accepted writes are deliberately ignored.
            val valid = clients.containsKey(device.address.lowercase()) &&
                FlywheelSportsGattProfile.acceptsInertRxWrite(characteristic.uuid, preparedWrite, offset)
            if (responseNeeded) server?.sendResponse(
                device,
                requestId,
                if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                0,
                null,
            )
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor,
        ) {
            val client = clients[device.address.lowercase()]
            val value = if (descriptor.uuid == BluetoothIds.UUID_CCCD &&
                descriptor.characteristic.uuid == BluetoothIds.UUID_FLYWHEEL_TX && client != null
            ) {
                if (client.subscribed) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            } else null
            when {
                value == null -> server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                offset < 0 || offset > value.size -> server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, 0, null)
                else -> server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
            }
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
            val valid = client != null && descriptor.uuid == BluetoothIds.UUID_CCCD &&
                descriptor.characteristic.uuid == BluetoothIds.UUID_FLYWHEEL_TX &&
                !preparedWrite && offset == 0 && (enable || disable)
            if (valid) {
                client.subscribed = enable
                if (disable) synchronized(client) { client.pending = null }
            }
            if (responseNeeded) server?.sendResponse(
                device, requestId,
                if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null,
            )
            if (valid && enable) latest?.let { enqueue(client, it) }
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
        val plan = FlywheelSportsAdvertisingPlan.create()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true).setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        val scanResponse = AdvertiseData.Builder().addServiceUuid(ParcelUuid(plan.serviceUuid)).build()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                if (epoch.get() != currentEpoch || advertiseCallback !== this) return
                isAdvertising = true
                publishState()
                finishStart(Result.success(Unit))
            }

            override fun onStartFailure(errorCode: Int) {
                if (epoch.get() != currentEpoch || advertiseCallback !== this) return
                val message = "Flywheel advertising failed ($errorCode)"
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

    private fun enqueue(client: Client, frame: ByteArray) {
        synchronized(client) { client.pending = frame.copyOf() }
        drain(client)
    }

    @SuppressLint("MissingPermission")
    private fun drain(client: Client) {
        val frame = synchronized(client) {
            if (client.inFlight) return
            client.pending?.also { client.pending = null; client.inFlight = true }
        } ?: return
        val characteristic = server?.getService(BluetoothIds.UUID_FLYWHEEL_SERVICE)
            ?.getCharacteristic(BluetoothIds.UUID_FLYWHEEL_TX)
        if (characteristic == null || !notify(client.device, characteristic, frame)) {
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
            run {
                characteristic.value = value
                activeServer.notifyCharacteristicChanged(device, characteristic, false)
            }
        }
    }

    private fun publishState() = listener.onState(isAdvertising, clients.isNotEmpty(), anySubscribed(), null)
    private fun anySubscribed() = clients.values.any { it.subscribed }
}
