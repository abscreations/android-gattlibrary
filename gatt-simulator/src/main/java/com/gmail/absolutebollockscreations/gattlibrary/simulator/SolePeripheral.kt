package com.gmail.absolutebollockscreations.gattlibrary.simulator

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
import android.os.SystemClock
import com.gmail.absolutebollockscreations.gattlibrary.server.AdapterNameCoordinator
import com.gmail.absolutebollockscreations.gattlibrary.server.ConnectableAdvertisingGate
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsForegroundService
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsControlPointHandler
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsControlSink
import com.gmail.absolutebollockscreations.gattlibrary.server.FtmsProcedureSession
import com.gmail.absolutebollockscreations.gattlibrary.server.TargetCapabilities
import com.gmail.absolutebollockscreations.gattlibrary.server.GattServerSessions
import com.gmail.absolutebollockscreations.gattlibrary.server.PeripheralCaptureHooks
import com.gmail.absolutebollockscreations.gattlibrary.server.PeripheralConnectionRetentionGuard
import com.gmail.absolutebollockscreations.gattlibrary.server.gattOps
import java.util.ArrayDeque

/** Hosts Sole GATT profiles, generates workout notifications and replays setup exchanges. */
@SuppressLint("MissingPermission")
internal class SolePeripheral(
    private val context: Context,
    private val onStatus: (Status) -> Unit,
) {
    data class Status(
        val name: String? = null,
        val advertising: Boolean = false,
        val connected: Boolean = false,
        val subscribed: Boolean = false,
        val packets: Long = 0,
        val error: String? = null,
    )

    private class Playback(events: List<SoleReplayEvent>) {
        val cursor = SoleReplayCursor(events)
        var scheduled = false
    }

    private class Client(val device: BluetoothDevice, val generation: Long) {
        val subscriptions = mutableMapOf<SoleAttribute, Int>()
        val playback = mutableMapOf<SoleReplayLane, Playback>()
        val queue = ArrayDeque<SoleReplayEvent>()
        val workoutQueue = ArrayDeque<SoleReplayEvent>()
        val procedure = FtmsProcedureSession()
        var inFlight: Runnable? = null
        var mtu = 23
        var closing = false
        var vendorStarted = false
        val f65Vendor = SoleF65VendorResponder()
    }

    private val main = Handler(Looper.getMainLooper())
    private val sessions = GattServerSessions<BluetoothGattService>()
    private val advertisingGate = ConnectableAdvertisingGate()
    private val retention = PeripheralConnectionRetentionGuard()
    private val clients = mutableMapOf<String, Client>()
    private val pendingServices = ArrayDeque<BluetoothGattService>()
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val prefs = context.getSharedPreferences(
        "com.gmail.absolutebollockscreations.Fitbridge.peripheral",
        Context.MODE_PRIVATE,
    )
    private val adapterNames = AdapterNameCoordinator(
        gateway = object : AdapterNameCoordinator.Gateway {
            override val currentName: String? get() = manager?.adapter?.name
            override fun requestName(name: String): Boolean = manager?.adapter?.setName(name) == true
        },
        store = object : AdapterNameCoordinator.OriginalNameStore {
            override fun read(): String? = prefs.getString("original_adapter_name", null)
            override fun write(name: String): Boolean = prefs.edit().putString("original_adapter_name", name).commit()
            override fun clear(): Boolean = prefs.edit().remove("original_adapter_name").commit()
        },
        scheduler = object : AdapterNameCoordinator.Scheduler {
            override fun postDelayed(token: Any, delayMs: Long, action: () -> Unit) {
                cancel(token)
                main.postAtTime({ action() }, token, SystemClock.uptimeMillis() + delayMs)
            }

            override fun cancel(token: Any) {
                main.removeCallbacksAndMessages(token)
            }
        },
    )

    private var epoch = 0L
    private var nextConnection = 0L
    private var capture: SoleCapture? = null
    private var server: BluetoothGattServer? = null
    private var advertisingCallback: AdvertiseCallback? = null
    private var startResult: ((Result<Unit>) -> Unit)? = null
    private var profile: SoleSimulatorProfile? = null
    private var controlPoint: FtmsControlPointHandler? = null
    private var packets = 0L
    private var lastError: String? = null
    @Volatile private var workoutRunning = false
    @Volatile var isAdvertising: Boolean = false
        private set

    fun start(profile: SoleSimulatorProfile, sink: FtmsControlSink?, onResult: (Result<Unit>) -> Unit) {
        val loaded = runCatching {
            context.assets.open("sole/${profile.name}.txt").bufferedReader().use { SoleCapture.load(it) }
        }
        main.post {
            stopOnMain()
            startResult = onResult
            val fixture = loaded.getOrElse {
                fail("Could not load the Sole capture for ${profile.advertisedName}")
                return@post
            }
            val current = epoch
            this.profile = profile
            controlPoint = sink?.let(::FtmsControlPointHandler)
            val surface = fixture.forSimulation(profile, sink?.targetCapabilities ?: TargetCapabilities.NONE)
            capture = surface
            adapterNames.apply(profile.advertisedName) { nameResult ->
                if (epoch == current) {
                    if (nameResult.isSuccess) open(current, surface)
                    else fail(nameResult.exceptionOrNull()?.message ?: "Could not set Sole advertising name")
                }
            }
        }
    }

    fun stop(onNameRestored: () -> Unit = {}) {
        if (Looper.myLooper() == main.looper) {
            stopOnMain(onNameRestored)
        } else {
            main.post { stopOnMain(onNameRestored) }
        }
    }

    private fun open(current: Long, fixture: SoleCapture) {
        val opened = runCatching { manager?.openGattServer(context, callback(current)) }.getOrNull()
            ?: return fail("Could not open Sole GATT server")
        server = opened
        sessions.open(current, opened.gattOps())
        fixture.services.forEach { (uuid, definitions) ->
            // GAP and GATT belong to Android's stack and cannot be reproduced by an app server.
            if (uuid == soleUuid("1800") || uuid == soleUuid("1801")) return@forEach
            val service = BluetoothGattService(uuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            definitions.forEach { definition ->
                val properties = definition.properties
                val permissions =
                    (if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0)
                        BluetoothGattCharacteristic.PERMISSION_READ else 0) or
                        (if (properties and WRITE_PROPERTIES != 0)
                            BluetoothGattCharacteristic.PERMISSION_WRITE else 0)
                val characteristic = BluetoothGattCharacteristic(
                    definition.key.characteristic, properties, permissions,
                )
                if (definition.hasCccd) {
                    characteristic.addDescriptor(
                        BluetoothGattDescriptor(
                            CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                        ),
                    )
                }
                service.addCharacteristic(characteristic)
            }
            pendingServices.addLast(service)
        }
        addNextService()
    }

    private fun addNextService() {
        val next = pendingServices.pollFirst()
        if (next == null) advertise()
        else if (!sessions.addService(next)) fail("Could not publish Sole service ${next.uuid}")
    }

    private fun advertise() {
        val peripheral = manager?.adapter?.bluetoothLeAdvertiser
            ?: return fail("Bluetooth LE advertising is unavailable")
        val fixture = capture ?: return
        val name = profile?.advertisedName ?: return
        val current = epoch
        val settings = AdvertiseSettings.Builder().setConnectable(true)
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(true).apply {
            fixture.advertisedServices.forEach { addServiceUuid(ParcelUuid(it)) }
            fixture.ftmsServiceData?.let { addServiceData(ParcelUuid(SoleCapture.treadmill.service), it) }
        }.build()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                if (advertisingCallback !== this || current != epoch) return
                isAdvertising = true
                publish()
                if (startResult == null) return
                FtmsForegroundService.start(context, "Sharing as $name", "Sole workout simulator") { result ->
                    if (current == epoch) {
                        if (result.isSuccess) finishStart(result)
                        else fail(result.exceptionOrNull()?.message ?: "Sole foreground service failed")
                    }
                }
            }

            override fun onStartFailure(errorCode: Int) {
                if (advertisingCallback === this && current == epoch) fail("Sole advertising failed ($errorCode)")
            }
        }
        advertisingCallback = callback
        runCatching { peripheral.startAdvertising(settings, data, callback) }
            .onFailure { fail("Could not start Sole advertising") }
    }

    private fun stopAdvertising() {
        advertisingCallback?.let { callback ->
            runCatching { manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback) }
        }
        advertisingCallback = null
        isAdvertising = false
    }

    private fun stopOnMain(onNameRestored: () -> Unit = {}) {
        workoutRunning = false
        if (profile == null && startResult == null && server == null) {
            if (adapterNames.isBusy) adapterNames.restore { onNameRestored() }
            else onNameRestored()
            return
        }
        epoch++
        stopAdvertising()
        clients.values.forEach { client ->
            PeripheralCaptureHooks.clientDisconnected(client.device.address, client.generation, "simulator_stopped")
            release(client)
            runCatching { server?.cancelConnection(client.device) }
        }
        clients.clear()
        advertisingGate.reset()
        retention.clear()
        pendingServices.clear()
        sessions.closeCurrent()
        server = null
        capture = null
        profile = null
        controlPoint = null
        packets = 0
        lastError = null
        FtmsForegroundService.stop(context)
        finishStart(Result.failure(IllegalStateException("Sole simulator stopped")))
        publish()
        adapterNames.restore { onNameRestored() }
    }

    private fun release(client: Client) {
        main.removeCallbacksAndMessages(client)
        client.playback.clear()
        client.queue.clear()
        client.workoutQueue.clear()
        client.inFlight = null
    }

    private fun fail(message: String) {
        finishStart(Result.failure(IllegalStateException(message)))
        stopOnMain()
        lastError = message
        publish()
    }

    private fun finishStart(result: Result<Unit>) {
        val callback = startResult
        startResult = null
        callback?.invoke(result)
    }

    private fun callback(current: Long) = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            main.post {
                if (!sessions.onServiceAdded(current, service) && current == epoch) {
                    if (status == BluetoothGatt.GATT_SUCCESS) addNextService()
                    else fail("Could not publish Sole service ${service.uuid} ($status)")
                }
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            main.post {
                if (epoch != current) return@post
                val key = device.address
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    clients.getOrPut(key) {
                        Client(device, ++nextConnection).also {
                            PeripheralCaptureHooks.clientConnected(key, it.generation, it.mtu)
                        }
                    }
                    if (advertisingGate.onConnected(key) == ConnectableAdvertisingGate.Action.PAUSE) stopAdvertising()
                    retention.retainOnce(key) { runCatching { server?.connect(device, false) } }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                    clients.remove(key)?.let { client ->
                        PeripheralCaptureHooks.clientDisconnected(key, client.generation, "gatt_status=$status")
                        release(client)
                    }
                    retention.release(key)
                    if (advertisingGate.onDisconnected(key) == ConnectableAdvertisingGate.Action.RESUME) advertise()
                }
                publish()
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            withClient(current, device) { it.mtu = mtu }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            withClient(current, device) { client ->
                val key = characteristic.key()
                val value = if (key == trainingStatus && controlPoint != null) {
                    client.procedure.trainingStatusPayload()
                } else capture?.services?.get(key.service)?.firstOrNull { it.key == key }?.readValue
                if (value != null && PeripheralCaptureHooks.shouldCaptureRead(offset)) {
                    PeripheralCaptureHooks.clientRead(device.address, key.characteristic, value)
                }
                read(device, requestId, offset, value)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor,
        ) {
            withClient(current, device) { client ->
                val mode = client.subscriptions[descriptor.characteristic.key()] ?: 0
                read(device, requestId, offset, byteArrayOf(mode.toByte(), 0).takeIf { descriptor.uuid == CCCD })
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            val bytes = value.copyOf()
            withClient(current, device) { client ->
                val characteristic = descriptor.characteristic
                val key = characteristic.key()
                val mode = bytes.firstOrNull()?.toInt() ?: -1
                val validMode = when (mode) {
                    0 -> true
                    1 -> characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                    2 -> characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
                    else -> false
                }
                val status = when {
                    descriptor.uuid != CCCD || preparedWrite -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                    offset != 0 -> BluetoothGatt.GATT_INVALID_OFFSET
                    bytes.size != 2 || bytes[1] != 0.toByte() || !validMode -> BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH
                    else -> BluetoothGatt.GATT_SUCCESS
                }
                if (responseNeeded) server?.sendResponse(device, requestId, status, offset, null)
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val label = when (mode) {
                        1 -> "notify"
                        2 -> "indicate"
                        else -> "disabled"
                    }
                    PeripheralCaptureHooks.clientCccd(device.address, key.characteristic, mode != 0, label)
                    if (mode == 0) {
                        client.subscriptions.remove(key)
                        client.queue.removeAll { it.key == key }
                        client.workoutQueue.removeAll { it.key == key }
                    } else {
                        client.subscriptions[key] = mode
                    }
                    when (key) {
                        SoleCapture.vendorNotify -> {
                            if (mode == 0) {
                                client.vendorStarted = false
                                client.f65Vendor.reset()
                            }
                            setLane(client, SoleReplayLane.VENDOR, mode != 0)
                        }
                        SoleCapture.controlPoint -> if (mode == 0) client.procedure.resetForDisconnect()
                    }
                    publish()
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            val bytes = value.copyOf()
            withClient(current, device) { client ->
                val key = characteristic.key()
                PeripheralCaptureHooks.clientWrote(device.address, key.characteristic, bytes, !responseNeeded)
                if (key == SoleCapture.controlPoint) {
                    writeControl(client, requestId, preparedWrite, responseNeeded, offset, bytes)
                    return@withClient
                }
                if (profile?.usesSyntheticVendorSession == true && key == SoleCapture.vendorWrite) {
                    writeF65Vendor(client, requestId, preparedWrite, responseNeeded, offset, bytes)
                    return@withClient
                }
                val lane = SoleReplayLane.VENDOR
                val playback = client.playback[lane]
                val status = when {
                    preparedWrite -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                    offset != 0 -> BluetoothGatt.GATT_INVALID_OFFSET
                    playback?.cursor?.accept(key, responseNeeded, bytes) == true -> BluetoothGatt.GATT_SUCCESS
                    else -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                }
                if (responseNeeded) server?.sendResponse(device, requestId, status, offset, null)
                if (status == BluetoothGatt.GATT_SUCCESS && playback != null) pump(client, lane, playback)
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            withClient(current, device) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            withClient(current, device) { client ->
                val timeout = client.inFlight ?: return@withClient
                main.removeCallbacks(timeout)
                client.inFlight = null
                if (status == BluetoothGatt.GATT_SUCCESS) packets++
                drain(client)
                publish()
            }
        }
    }

    private fun withClient(current: Long, device: BluetoothDevice, action: (Client) -> Unit) {
        main.post {
            if (epoch != current) return@post
            clients[device.address]?.takeUnless { it.closing }?.let(action)
        }
    }

    private fun BluetoothGattCharacteristic.key() = SoleAttribute(service.uuid, uuid)

    private fun writeControl(
        client: Client, requestId: Int, preparedWrite: Boolean, responseNeeded: Boolean,
        offset: Int, value: ByteArray,
    ) {
        val handler = controlPoint
        if (!responseNeeded) return
        val status = when {
            preparedWrite || handler == null -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
            offset != 0 -> BluetoothGatt.GATT_INVALID_OFFSET
            client.subscriptions[SoleCapture.controlPoint] != 2 -> 0xFD
            client.queue.size > MAX_QUEUE - 3 -> 0xFE
            else -> BluetoothGatt.GATT_SUCCESS
        }
        val acknowledged = server?.sendResponse(client.device, requestId, status, 0, null) == true
        if (!acknowledged || status != BluetoothGatt.GATT_SUCCESS || handler == null) return
        val running = client.procedure.operationalState == FtmsProcedureSession.OperationalState.RUNNING
        if (running != workoutRunning) {
            client.procedure.operationalState = if (workoutRunning) FtmsProcedureSession.OperationalState.RUNNING
                else FtmsProcedureSession.OperationalState.PAUSED
        }
        val outcome = handler.handle(value, client.procedure)
        client.queue.addLast(SoleReplayEvent(0, SoleReplayOperation.INDICATE, SoleCapture.controlPoint, outcome.response))
        outcome.status?.let {
            client.queue.addLast(SoleReplayEvent(0, SoleReplayOperation.NOTIFY, machineStatus, it))
        }
        if (outcome.trainingStatusChanged) {
            client.queue.addLast(SoleReplayEvent(0, SoleReplayOperation.NOTIFY, trainingStatus, client.procedure.trainingStatusPayload()))
        }
        drain(client)
    }

    private fun writeF65Vendor(
        client: Client, requestId: Int, preparedWrite: Boolean, responseNeeded: Boolean,
        offset: Int, value: ByteArray,
    ) {
        val status = when {
            preparedWrite -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
            offset != 0 -> BluetoothGatt.GATT_INVALID_OFFSET
            else -> BluetoothGatt.GATT_SUCCESS
        }
        if (responseNeeded) server?.sendResponse(client.device, requestId, status, offset, null)
        if (status != BluetoothGatt.GATT_SUCCESS) return
        val replies = client.f65Vendor.accept(value)
        if (client.subscriptions[SoleCapture.vendorNotify] == 1) {
            for (reply in replies) {
                if (client.queue.size == MAX_QUEUE) {
                    disconnectStalled(client)
                    return
                }
                client.queue.addLast(SoleReplayEvent(0, SoleReplayOperation.NOTIFY, SoleCapture.vendorNotify, reply))
            }
        }
        if (client.f65Vendor.started && !client.vendorStarted) {
            client.vendorStarted = true
            publish()
        }
        drain(client)
    }

    fun setWorkoutRunning(running: Boolean) {
        workoutRunning = running
        if (!running) main.post { clients.values.forEach { it.workoutQueue.clear() } }
    }

    fun publishWorkout(profile: SoleSimulatorProfile, sample: FtmsMockSample) {
        main.post {
            if (!workoutRunning || this.profile != profile) return@post
            for (client in clients.values) {
                if (client.closing) continue
                val maxPayload = client.mtu - 3
                val events = profile.workoutNotificationKeys(client.subscriptions, client.vendorStarted).flatMap { key ->
                    val frames = if (key == SoleCapture.vendorNotify) {
                        soleVendorWorkoutFrames(profile, sample, maxPayload)
                    } else {
                        soleFtmsWorkoutFrames(profile, sample, maxPayload)
                    }
                    frames.map { SoleReplayEvent(0, SoleReplayOperation.NOTIFY, key, it) }
                }
                if (events.isEmpty()) continue
                // Drop an entire workout sample when the queue is busy. Keep fragments together.
                if (client.queue.size + client.workoutQueue.size + events.size > MAX_QUEUE) continue
                client.workoutQueue.addAll(events)
                drain(client)
            }
        }
    }

    private fun workoutReady(client: Client): Boolean =
        !client.closing &&
            profile?.workoutNotificationKeys(client.subscriptions, client.vendorStarted)?.isNotEmpty() == true

    private fun read(device: BluetoothDevice, requestId: Int, offset: Int, value: ByteArray?) {
        when {
            value == null -> server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
            offset !in 0..value.size -> server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, 0, null)
            else -> server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
        }
    }

    private fun setLane(client: Client, lane: SoleReplayLane, enabled: Boolean) {
        if (!enabled) {
            val keys = capture?.lanes?.get(lane).orEmpty().mapTo(mutableSetOf()) { it.key }
            client.playback.remove(lane)
            client.queue.removeAll { it.key in keys }
            return
        }
        if (lane in client.playback) return
        val events = capture?.lanes?.get(lane) ?: return
        val playback = Playback(events)
        client.playback[lane] = playback
        pump(client, lane, playback)
    }

    private fun pump(client: Client, lane: SoleReplayLane, playback: Playback) {
        if (playback.scheduled) return
        val event = playback.cursor.takeOutput() ?: return
        playback.scheduled = true
        main.postAtTime({
            if (client.playback[lane] === playback && !client.closing) {
                playback.scheduled = false
                if (subscribed(client, event)) {
                    if (client.queue.size == MAX_QUEUE) {
                        disconnectStalled(client)
                        return@postAtTime
                    }
                    client.queue.addLast(event)
                    drain(client)
                    val bytes = event.value
                    if (lane == SoleReplayLane.VENDOR && bytes.size >= 4 &&
                        bytes[0] == 0x5b.toByte() && bytes[2] == 0xf0.toByte()
                    ) {
                        client.vendorStarted = true
                        publish()
                    }
                }
                if (!client.closing) pump(client, lane, playback)
            }
        }, client, SystemClock.uptimeMillis() + event.delayMs)
    }

    private fun subscribed(client: Client, event: SoleReplayEvent): Boolean =
        client.subscriptions[event.key] == if (event.operation == SoleReplayOperation.INDICATE) 2 else 1

    private fun drain(client: Client) {
        if (client.inFlight != null || client.closing) return
        val active = server ?: return
        while (true) {
            val event = client.queue.pollFirst()
                ?: (if (workoutRunning) client.workoutQueue.pollFirst() else null)
                ?: return
            if (!subscribed(client, event)) continue
            if (event.value.size > client.mtu - 3) {
                lastError = "Sole packet needs ATT MTU ${event.value.size + 3}"
                publish()
                continue
            }
            val characteristic = active.getService(event.key.service)?.getCharacteristic(event.key.characteristic)
                ?: continue
            val confirm = event.operation == SoleReplayOperation.INDICATE
            if (send(active, client.device, characteristic, confirm, event.value)) {
                PeripheralCaptureHooks.notified(client.device.address, characteristic.uuid, event.value)
                val timeout = Runnable { disconnectStalled(client) }
                client.inFlight = timeout
                main.postAtTime(timeout, client, SystemClock.uptimeMillis() + SEND_TIMEOUT_MS)
                return
            }
            lastError = "Android declined a Sole replay frame"
            publish()
        }
    }

    @Suppress("DEPRECATION")
    private fun send(
        server: BluetoothGattServer, device: BluetoothDevice, characteristic: BluetoothGattCharacteristic,
        confirm: Boolean, value: ByteArray,
    ): Boolean = if (Build.VERSION.SDK_INT >= 33) {
        server.notifyCharacteristicChanged(device, characteristic, confirm, value) == BluetoothGatt.GATT_SUCCESS
    } else {
        characteristic.value = value
        server.notifyCharacteristicChanged(device, characteristic, confirm)
    }

    private fun disconnectStalled(client: Client) {
        client.closing = true
        release(client)
        lastError = "Sole replay stopped because the BLE send queue stalled"
        runCatching { server?.cancelConnection(client.device) }
        publish()
    }

    private fun publish() {
        onStatus(
            Status(
                name = profile?.advertisedName,
                advertising = isAdvertising,
                connected = clients.isNotEmpty(),
                subscribed = clients.values.any(::workoutReady),
                packets = packets,
                error = lastError,
            ),
        )
    }

    private companion object {
        val machineStatus = SoleAttribute(soleUuid("1826"), soleUuid("2ada"))
        val trainingStatus = SoleAttribute(soleUuid("1826"), soleUuid("2ad3"))
        val CCCD = soleUuid("2902")
        const val WRITE_PROPERTIES = BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        const val MAX_QUEUE = 32
        const val SEND_TIMEOUT_MS = 5_000L
    }
}
