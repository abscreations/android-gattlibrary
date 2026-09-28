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
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.GattServerSessions
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.gattOps
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
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import timber.log.Timber
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.ConnectableAdvertisingGate
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.PeripheralConnectionRetentionGuard

/** One characteristic's bounded FIFO. Stale frames are dropped in favour of current ones. */
internal class SchwinnX70NotificationQueue(private val capacity: Int = 6) {
    private val values = ArrayDeque<Pair<UUID, ByteArray>>()

    init {
        require(capacity > 0)
    }

    fun offerAll(frames: List<Pair<UUID, ByteArray>>) {
        while (values.size + frames.size > capacity && values.isNotEmpty()) values.removeFirst()
        frames.takeLast(capacity).forEach { values.addLast(it.first to it.second.copyOf()) }
    }

    fun poll(): Pair<UUID, ByteArray>? = values.pollFirst()
    fun clear() = values.clear()
    fun size(): Int = values.size
}

/**
 * Android GATT server for the Schwinn x70 personas.
 *
 * ## The initialization gate is the whole point of this class
 *
 * A physical x70 sends nothing until `1717b3c0-…` is written. That is the family's one piece of
 * stateful protocol, and reproducing it is what makes the simulator worth building: a client that
 * subscribes and forgets to write gets silence, exactly as it would from the console.
 *
 * The gate is per client and per link generation. Streaming starts for a characteristic when **both**
 * are true — the control write was accepted on this link, and that characteristic's CCCD is armed —
 * so either ordering works. FitBridge arms then writes; nothing guarantees another client does.
 *
 * ## Emission order is captured, not chosen
 *
 * In both single-connection captures the first notification on the link is `35ddd0a0`'s invariant
 * `01 1f 00 00`, and the first frame on the measurement characteristic is the 5-octet stream marker,
 * ahead of any 17-byte frame. Both are reproduced. A central that assumes its first notification is
 * a measurement is wrong on hardware, and this is what proves it.
 */
internal class SchwinnX70PeripheralAdvertiser(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onState(
            advertising: Boolean,
            connected: Boolean,
            initialised: Boolean,
            subscribed: Boolean,
            error: String?,
        )

        fun onPacketSent()
    }

    private class Client(val device: BluetoothDevice) {
        /** Armed CCCDs, by characteristic. */
        val subscriptions = mutableSetOf<UUID>()

        /** Whether an accepted control write has opened the gate on this link generation. */
        var initialised = false

        /** Streams already opened, so the link-open frames are sent once and not per tick. */
        val opened = mutableSetOf<UUID>()
        var inFlight = false
        val pending = SchwinnX70NotificationQueue()
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

    @Volatile private var readValues: Map<UUID, ByteArray> = emptyMap()
    @Volatile private var latest: List<Pair<UUID, ByteArray>> = emptyList()

    @Volatile var isAdvertising: Boolean = false
        private set

    /** Rejected control writes on this server, for the diagnostics surface. */
    @Volatile var rejectedWrites: Int = 0
        private set

    /**
     * Offer one tick's frames.
     *
     * A client only receives the streams whose CCCD it armed, and only once its gate is open.
     * Nothing is buffered for a client that has not written the control command — a console does not
     * hold a backlog for a client that never asked it to start.
     */
    fun publish(frames: List<Pair<UUID, ByteArray>>) {
        latest = frames.map { it.first to it.second.copyOf() }
        clients.values.forEach { client ->
            if (!client.initialised) return@forEach
            val allowed = frames.filter { it.first in client.subscriptions }
            if (allowed.isNotEmpty()) enqueue(client, allowed)
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE])
    fun start(onResult: (Result<Unit>) -> Unit) {
        stop()
        readValues = SchwinnX70GattProfile.readValues()
        latest = emptyList()
        rejectedWrites = 0
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
            finishStart(Result.failure(IllegalStateException("Could not open Schwinn x70 GATT server")))
            return
        }
        // Services are added one at a time: onServiceAdded fires per service and the next may only
        // be added after the previous completes.
        pendingServices = ArrayDeque(SchwinnX70GattProfile.buildServices())
        addNextService()
    }

    private var pendingServices: ArrayDeque<BluetoothGattService> = ArrayDeque()

    @SuppressLint("MissingPermission")
    private fun addNextService() {
        val next = pendingServices.pollFirst() ?: return
        if (gattSessions.addService(next) != true) {
            finishStart(Result.failure(IllegalStateException("Could not add Schwinn x70 service ${next.uuid}")))
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
        startResult?.invoke(Result.failure(IllegalStateException("Schwinn x70 start was stopped")))
        startResult = null
        listener.onState(false, connected = false, initialised = false, subscribed = false, error = null)
    }

    private fun callback(callbackEpoch: Long) = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (gattSessions.onServiceAdded(callbackEpoch, service)) return
            if (epoch.get() != callbackEpoch) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finishStart(Result.failure(IllegalStateException("Schwinn x70 service add failed ($status)")))
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
                    if (advertisingGate.onConnected(key) == ConnectableAdvertisingGate.Action.PAUSE) {
                        pauseAdvertising()
                    }
                    connectionRetention.retainOnce(key) {
                        val retained = runCatching { server?.connect(device, false) == true }
                            .getOrElse { error ->
                                Timber.w(error, "Schwinn x70 GATT server could not retain inbound connection")
                                false
                            }
                        if (!retained) Timber.w("Schwinn x70 GATT server did not retain inbound connection")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    // Every piece of gate state dies with the link. A reconnecting client must write
                    // the command again, which is what a console requires.
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
            // Device Information reads back the captured identity. The measurement characteristics
            // are notify-only, as the captures show, so a read of one is refused rather than served.
            respondRead(device, requestId, offset, readValues[characteristic.uuid])
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
            val isControl = characteristic.uuid == BluetoothIds.UUID_X70_CONTROL
            val accepted = client != null && isControl && !preparedWrite && offset == 0 &&
                SchwinnX70SetupGate.accepts(value)

            if (accepted) {
                client.initialised = true
                Timber.tag(TAG).i("Schwinn x70 simulator gate opened by a %d-octet command", value.size)
            } else if (isControl) {
                rejectedWrites += 1
                Timber.tag(TAG).w("Schwinn x70 simulator rejected a %d-octet control write", value.size)
            }

            if (responseNeeded) {
                server?.sendResponse(
                    device,
                    requestId,
                    if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                    0,
                    null,
                )
            }
            // `accepted` already implies a non-null client; the compiler knows it and said so.
            if (accepted) openReadyStreams(client)
            publishState()
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            val client = clients[device.address.lowercase()]
            val armed = client?.subscriptions?.contains(descriptor.characteristic.uuid) == true
            val whole = if (descriptor.uuid == BluetoothIds.UUID_CCCD && client != null) {
                if (armed) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
            } else {
                null
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
            val target = descriptor.characteristic.uuid
            val enable = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            val disable = value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
            val valid = client != null && descriptor.uuid == BluetoothIds.UUID_CCCD &&
                target in SchwinnX70GattProfile.NOTIFY_CHARACTERISTICS &&
                !preparedWrite && offset == 0 && (enable || disable)

            if (valid) {
                if (enable) client.subscriptions.add(target) else client.subscriptions.remove(target)
                if (disable) {
                    client.opened.remove(target)
                    synchronized(client) { client.pending.clear() }
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
            // Arming after the gate is already open starts that stream immediately; arming before it
            // opens nothing at all. Both orders reach streaming, neither skips the gate.
            if (valid && enable && client.initialised) openReadyStreams(client)
            publishState()
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val client = clients[device.address.lowercase()] ?: return
            synchronized(client) { client.inFlight = false }
            if (status == BluetoothGatt.GATT_SUCCESS) listener.onPacketSent()
            drain(client)
        }
    }

    /**
     * Send the link-open frames for any armed stream that has not had them yet.
     *
     * `35ddd0a0`'s invariant frame first, then the measurement characteristic's 5-octet marker —
     * the captured order, and the reason a client cannot assume its first notification is data.
     */
    private fun openReadyStreams(client: Client) {
        val opening = mutableListOf<Pair<UUID, ByteArray>>()
        if (BluetoothIds.UUID_X70_AUX_STATUS in client.subscriptions &&
            client.opened.add(BluetoothIds.UUID_X70_AUX_STATUS)
        ) {
            opening += BluetoothIds.UUID_X70_AUX_STATUS to SchwinnX70GattProfile.AUX_STATUS_FRAME.copyOf()
        }
        if (BluetoothIds.UUID_X70_MEASUREMENT in client.subscriptions &&
            client.opened.add(BluetoothIds.UUID_X70_MEASUREMENT)
        ) {
            opening += BluetoothIds.UUID_X70_MEASUREMENT to markerFrame()
        }
        // Only the streams actually opened are marked. Marking all seven would mean a client that
        // arms the aux CCCD *after* writing the control command never receives the frame that is
        // supposed to be first on its link — and arming late is legal, since the gate and the CCCDs
        // are independent.
        if (opening.isNotEmpty()) enqueue(client, opening)
    }

    /** The marker variant alternates across sessions; both are captured and both are emitted. */
    private fun markerFrame(): ByteArray =
        if (markerParity++ % 2 == 0) MARKER_01.copyOf() else MARKER_03.copyOf()

    private var markerParity = 0

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
        val plan = SchwinnX70AdvertisingPlan.create()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        // The captured advertisement carries the service UUID and the manufacturer block, and puts
        // the name in the scan response. Reproduced exactly; see SchwinnX70AdvertisingPlan for the
        // three places Android cannot.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(plan.includeNameInAdvertising)
            .addServiceUuid(ParcelUuid(plan.serviceUuid))
            .addManufacturerData(plan.manufacturerId, plan.manufacturerPayload)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(plan.includeNameInScanResponse)
            .setIncludeTxPowerLevel(plan.includeTxPowerInScanResponse)
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
                val message = "Schwinn x70 advertising failed ($errorCode)"
                listener.onState(false, clients.isNotEmpty(), anyInitialised(), anySubscribed(), message)
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

    private fun enqueue(client: Client, frames: List<Pair<UUID, ByteArray>>) {
        synchronized(client) { client.pending.offerAll(frames) }
        drain(client)
    }

    @SuppressLint("MissingPermission")
    private fun drain(client: Client) {
        val entry = synchronized(client) {
            if (client.inFlight) return
            client.pending.poll()?.also { client.inFlight = true }
        } ?: return
        val characteristic = server?.getService(BluetoothIds.UUID_X70_SERVICE)
            ?.getCharacteristic(entry.first)
        if (characteristic == null || !notify(client.device, characteristic, entry.second)) {
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

    private fun publishState() = listener.onState(
        isAdvertising,
        clients.isNotEmpty(),
        anyInitialised(),
        anySubscribed(),
        null,
    )

    private fun anySubscribed(): Boolean = clients.values.any { it.subscriptions.isNotEmpty() }
    private fun anyInitialised(): Boolean = clients.values.any { it.initialised }

    companion object {
        private const val TAG = "FitBridge"
        private val MARKER_01 = byteArrayOf(0x05, 0x09, 0x01, 0x01, 0x00)
        private val MARKER_03 = byteArrayOf(0x05, 0x09, 0x01, 0x03, 0x00)
    }
}
