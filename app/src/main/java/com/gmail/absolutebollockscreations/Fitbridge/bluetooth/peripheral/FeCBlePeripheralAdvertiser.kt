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
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.FeCBleControlDecoder
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.FeCBleControlCommand
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.FeCBleMachineState
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.FeCBleSyntheticModel
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.FeCBleTelemetryEncoder
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.RejectReason
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import timber.log.Timber

/**
 * Android GATT server for the FE-C-over-BLE simulator personas (plan §14, §15).
 *
 * ## Per-client notification flow control is the point of this class
 *
 * FEC2 sends nothing before a client's own CCCD is armed (plan §15.2), sends that client an
 * immediate page-16 frame the moment it is (plan §11.4), and after that never queues more than one
 * pending frame per client — a notification already in flight has its pending slot overwritten by
 * the newest frame rather than queued (plan §15.3). Two clients are otherwise fully independent.
 *
 * A connected client can subscribe to FEC2 and (when [includeCps] is true) Cycling Power
 * Measurement independently, but Android's GATT server accepts only **one outstanding
 * `notifyCharacteristicChanged` per device at a time, regardless of which characteristic it is
 * for** — this is a connection-wide constraint, not a per-characteristic one. So each client has a
 * single `inFlight` flag and up to one pending frame per characteristic; when the in-flight send
 * completes, FEC2's pending frame is drained before CPS's, and any pending frame that gets
 * overwritten by a fresher one before it could be sent still counts as conflated (plan §15.3).
 *
 * ## FEC3 mutates one shared [FeCBleMachineState]
 *
 * Control is global to the simulated machine, not per client (plan §15.4): last valid write wins,
 * and the last writer is recorded for diagnostics only. A malformed write is rejected at the
 * envelope layer and never reaches [FeCBleSyntheticModel] (plan §30.3).
 *
 * ## Incyclist CPS overlay
 *
 * When [includeCps] is true, FitBridge's existing standards-compliant Cycling Power Service
 * (`0x1818`, [CyclingServiceRegistry]) is published on the same GATT server alongside FEC1, and
 * advertised alongside FEC1's 128-bit UUID (plan §5.3, §6.3, §18). This is not a new one-off CPS
 * implementation — it reuses the same encoder the Indoor Bike FTMS persona uses. The caller
 * ([FeCBlePeripheralController]) is responsible for calling [publishCps] with a frame whose
 * instantaneous power equals the same tick's FE-C page-25 power (plan §18.2); this class never
 * generates CPS data itself.
 */
internal class FeCBlePeripheralAdvertiser(
    private val context: Context,
    private val state: FeCBleMachineState,
    private val includeControl: Boolean,
    private val includeCps: Boolean,
    private val listener: Listener,
) {
    interface Listener {
        fun onState(advertising: Boolean, connectedClients: Int, subscribedClients: Int, error: String?)
        fun onPacketSent()
        fun onNotificationConflated()
        fun onControlWriteAccepted(page: Int, clientKey: String)
        fun onControlWriteRejected(reason: RejectReason)
        fun onUnsupportedControlPage(page: Int)
    }

    private class Client(val device: BluetoothDevice) {
        var subscribed = false
        var cpsSubscribed = false
        var inFlight = false
        var pendingFec2: ByteArray? = null
        var pendingCps: ByteArray? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val epoch = AtomicLong(0)
    private val clients = ConcurrentHashMap<String, Client>()
    private val connectionRetention = PeripheralConnectionRetentionGuard()
    private var server: BluetoothGattServer? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var startResult: ((Result<Unit>) -> Unit)? = null
    private var pendingServices: ArrayDeque<BluetoothGattService> = ArrayDeque()

    @Volatile var isAdvertising: Boolean = false
        private set

    /** Send this tick's FE-C frame to every FEC2-subscribed client, respecting backpressure. */
    fun publish(frame: ByteArray) {
        clients.values.forEach { client ->
            if (client.subscribed) enqueue(client, frame.copyOf(), isCps = false)
        }
    }

    /**
     * Send this tick's Cycling Power Measurement frame to every CPS-subscribed client. A no-op
     * when [includeCps] is false, so callers do not need to guard every call site themselves.
     */
    fun publishCps(frame: ByteArray) {
        if (!includeCps) return
        clients.values.forEach { client ->
            if (client.cpsSubscribed) enqueue(client, frame.copyOf(), isCps = true)
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE])
    fun start(onResult: (Result<Unit>) -> Unit) {
        stop()
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
            finishStart(Result.failure(IllegalStateException("Could not open FE-C GATT server")))
            return
        }
        pendingServices = ArrayDeque(FeCBleGattProfile.buildServices(includeControl, includeCps))
        addNextService()
    }

    @SuppressLint("MissingPermission")
    private fun addNextService() {
        val next = pendingServices.removeFirstOrNull() ?: return
        if (server?.addService(next) != true) {
            finishStart(Result.failure(IllegalStateException("Could not add FE-C service ${next.uuid}")))
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
        connectionRetention.clear()
        runCatching { server?.clearServices() }
        runCatching { server?.close() }
        server = null
        startResult?.invoke(Result.failure(IllegalStateException("FE-C start was stopped")))
        startResult = null
        listener.onState(false, 0, 0, null)
    }

    private fun callback(callbackEpoch: Long) = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (epoch.get() != callbackEpoch) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finishStart(Result.failure(IllegalStateException("FE-C service add failed ($status)")))
                return
            }
            if (pendingServices.isEmpty()) startAdvertising(callbackEpoch) else addNextService()
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (epoch.get() != callbackEpoch) return
            val key = device.address.lowercase()
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    clients.putIfAbsent(key, Client(device))
                    connectionRetention.retainOnce(key) {
                        val retained = runCatching { server?.connect(device, false) == true }
                            .getOrElse { error ->
                                Timber.w(error, "FE-C GATT server could not retain inbound connection")
                                false
                            }
                        if (!retained) Timber.w("FE-C GATT server did not retain inbound connection")
                    }
                }
                // Plan §30.5: only that client's state dies with the link. The machine state is
                // global and survives — a second client mid-session is unaffected.
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectionRetention.release(key)
                    clients.remove(key)
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
            // FEC2/FEC3 are not readable (plan §6). The CPS overlay's two informational
            // characteristics are the only readable characteristics this server ever serves.
            val whole = when {
                !includeCps -> null
                characteristic.uuid == BluetoothIds.UUID_CYCLING_POWER_FEATURE -> CyclingSensorEncoders.cyclingPowerFeature()
                characteristic.uuid == BluetoothIds.UUID_SENSOR_LOCATION -> CyclingSensorEncoders.sensorLocation()
                else -> null
            }
            respondRead(device, requestId, offset, whole)
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
            val client = clients[device.address.lowercase()]
            val isControl = characteristic.uuid == BluetoothIds.UUID_FEC_CONTROL
            var accepted = false
            if (isControl && client != null && !preparedWrite && offset == 0) {
                when (val command = FeCBleControlDecoder.decode(value)) {
                    is FeCBleControlCommand.Rejected -> listener.onControlWriteRejected(command.reason)
                    is FeCBleControlCommand.Unsupported -> listener.onUnsupportedControlPage(command.page)
                    else -> {
                        synchronized(state) { FeCBleSyntheticModel.applyControlCommand(state, command, device.address) }
                        accepted = true
                        listener.onControlWriteAccepted((value.getOrNull(4)?.toInt() ?: -1) and 0xFF, device.address)
                    }
                }
            }
            // Plan §14 step 9: write-without-response never fabricates a protocol reply on FEC2.
            // responseNeeded is only true for a client that (incorrectly) requests one; we still
            // answer honestly rather than silently dropping the response.
            if (responseNeeded) {
                server?.sendResponse(
                    device,
                    requestId,
                    if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                    0,
                    null,
                )
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            val client = clients[device.address.lowercase()]
            val subscribedFlag = subscriptionFlagFor(client, descriptor)
            val whole = subscribedFlag?.let {
                if (it) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            respondRead(device, requestId, offset, whole)
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
            val client = clients[device.address.lowercase()]
            val enable = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            val disable = value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
            val characteristicUuid = descriptor.characteristic.uuid
            val isFec2 = characteristicUuid == BluetoothIds.UUID_FEC_MEASUREMENT
            val isCps = includeCps && characteristicUuid == BluetoothIds.UUID_CYCLING_POWER_MEASUREMENT
            val valid = client != null && descriptor.uuid == BluetoothIds.UUID_CCCD &&
                (isFec2 || isCps) && !preparedWrite && offset == 0 && (enable || disable)

            // `valid`'s definition starts with `client != null`, and the compiler proves that
            // implication here (as it did for the single-characteristic version of this check) —
            // `client` is smart-cast non-null for the rest of this block.
            if (valid) {
                if (isFec2) {
                    if (enable && !client.subscribed) {
                        client.subscribed = true
                        // Plan §11.4: immediate page-16 frame, not a wait for the next scheduled
                        // tick. No equivalent immediate-frame requirement exists for CPS.
                        val immediate = synchronized(state) { FeCBleTelemetryEncoder.encodePage16Frame(state) }
                        enqueue(client, immediate, isCps = false)
                    } else if (disable) {
                        client.subscribed = false
                        synchronized(client) { client.pendingFec2 = null }
                    }
                } else {
                    if (enable) {
                        client.cpsSubscribed = true
                    } else if (disable) {
                        client.cpsSubscribed = false
                        synchronized(client) { client.pendingCps = null }
                    }
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
            publishState()
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val client = clients[device.address.lowercase()] ?: return
            synchronized(client) { client.inFlight = false }
            if (status == BluetoothGatt.GATT_SUCCESS) listener.onPacketSent()
            drain(client)
        }
    }

    /** Null when the descriptor isn't a CCCD this server recognizes for the given client. */
    private fun subscriptionFlagFor(client: Client?, descriptor: BluetoothGattDescriptor): Boolean? {
        if (client == null || descriptor.uuid != BluetoothIds.UUID_CCCD) return null
        return when (descriptor.characteristic.uuid) {
            BluetoothIds.UUID_FEC_MEASUREMENT -> client.subscribed
            BluetoothIds.UUID_CYCLING_POWER_MEASUREMENT -> if (includeCps) client.cpsSubscribed else null
            else -> null
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(currentEpoch: Long) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val advertiser = manager?.adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            finishStart(Result.failure(IllegalStateException("BLE advertising is unavailable")))
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        // Plan §5.1: FEC1 in the advertisement itself, never local-name-only discovery. Plan
        // §5.3: the Incyclist overlay additionally advertises CPS (0x1818) alongside FEC1.
        val dataBuilder = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(BluetoothIds.UUID_FEC_SERVICE))
        if (includeCps) {
            dataBuilder.addServiceUuid(BluetoothIds.CYCLING_POWER_SERVICE_PARCEL)
        }
        val data = dataBuilder.build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                if (epoch.get() != currentEpoch) return
                isAdvertising = true
                publishState()
                finishStart(Result.success(Unit))
            }

            override fun onStartFailure(errorCode: Int) {
                if (epoch.get() != currentEpoch) return
                val message = "FE-C advertising failed ($errorCode)"
                listener.onState(false, clients.size, subscribedCount(), message)
                finishStart(Result.failure(IllegalStateException(message)))
            }
        }
        advertiseCallback = callback
        // No bonding/encryption requested (plan §5.4): the transport guide establishes none.
        runCatching { advertiser.startAdvertising(settings, data, scanResponse, callback) }
            .onFailure { finishStart(Result.failure(it)) }
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

    private fun enqueue(client: Client, frame: ByteArray, isCps: Boolean) {
        val conflated = synchronized(client) {
            val had = if (isCps) client.pendingCps != null else client.pendingFec2 != null
            if (isCps) client.pendingCps = frame else client.pendingFec2 = frame
            had
        }
        if (conflated) listener.onNotificationConflated()
        drain(client)
    }

    /**
     * Android allows only one outstanding notification per device connection at a time, so this
     * drains a single characteristic's pending frame per call — FEC2 first, then CPS — and waits
     * for [onNotificationSent] before sending the next.
     */
    @SuppressLint("MissingPermission")
    private fun drain(client: Client) {
        val chosen = synchronized(client) {
            if (client.inFlight) return
            val fec2Frame = client.pendingFec2
            val cpsFrame = client.pendingCps
            val next = when {
                fec2Frame != null -> {
                    client.pendingFec2 = null
                    BluetoothIds.UUID_FEC_MEASUREMENT to fec2Frame
                }
                cpsFrame != null -> {
                    client.pendingCps = null
                    BluetoothIds.UUID_CYCLING_POWER_MEASUREMENT to cpsFrame
                }
                else -> null
            } ?: return
            client.inFlight = true
            next
        }
        val (characteristicUuid, frame) = chosen
        val characteristic = gattCharacteristicFor(characteristicUuid)
        if (characteristic == null || !notify(client.device, characteristic, frame)) {
            synchronized(client) { client.inFlight = false }
        }
    }

    private fun gattCharacteristicFor(characteristicUuid: UUID): BluetoothGattCharacteristic? {
        val serviceUuid = if (characteristicUuid == BluetoothIds.UUID_CYCLING_POWER_MEASUREMENT) {
            BluetoothIds.UUID_CYCLING_POWER_SERVICE
        } else {
            BluetoothIds.UUID_FEC_SERVICE
        }
        return server?.getService(serviceUuid)?.getCharacteristic(characteristicUuid)
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

    private fun publishState() = listener.onState(isAdvertising, clients.size, subscribedCount(), null)

    private fun subscribedCount(): Int = clients.values.count { it.subscribed }
}
