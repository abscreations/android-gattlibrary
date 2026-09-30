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
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.gmail.absolutebollockscreations.gattlibrary.central.BluetoothIds
import com.gmail.absolutebollockscreations.gattlibrary.central.KayakFirstCommandCodec
import com.gmail.absolutebollockscreations.gattlibrary.central.KayakFirstDisplayConfiguration
import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.KayakFirstHandshakeVariant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.AdapterNameCoordinator
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.ConnectableAdvertisingGate
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.FtmsForegroundService
import com.gmail.absolutebollockscreations.gattlibrary.peripheral.advertisedName
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.KayakFirstUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.KayakFirstOrangeCommand
import com.gmail.absolutebollockscreations.gattlibrary.core.vendor.KayakFirstSpec

/** Debug peripheral for the capture-corroborated KayakFirst FFE0/FFE1 console surface. */
internal class KayakFirstPeripheralController(
    private val context: Context,
    private val onStatus: (Status) -> Unit = {},
) {
    data class Status(
        val advertising: Boolean = false,
        val connected: Boolean = false,
        val subscribed: Boolean = false,
        val generationReady: Boolean = false,
        val generationAdvancing: Boolean = false,
        val advertisedName: String? = null,
        val setupStage: String = "waiting",
        val lastCommand: String? = null,
        val displayConfiguration: KayakFirstDisplayConfiguration = KayakFirstDisplayConfiguration.DEFAULT,
        val pollRequests: Long = 0,
        val completeRecords: Long = 0,
        val malformedCommands: Long = 0,
        val queuedFragments: Int = 0,
        val droppedRecords: Long = 0,
        val packetsSent: Long = 0,
        val lastError: String? = null,
    )

    private class Client(val device: BluetoothDevice) {
        val assembler = KayakFirstSimulatorCommandAssembler()
        val queue = ArrayDeque<ByteArray>()
        var subscribed = false
        var inFlight = false
        var setupStage = "connected"
        var sport = 1
        var boatWeightKg = KayakFirstSpec.DEFAULT_BOAT_WEIGHT_KG.toDouble()
        var display = KayakFirstDisplayConfiguration.DEFAULT
        var running = false
        var lastGeneratedEventKey: Long? = null
        /** Session elapsed of the last datapoint actually put on the wire, for field 23. */
        var lastCoveredElapsedSeconds: Int? = null
        var polls = 0L
        var orangeSequence = 0L
        /** Monotonic instant of the last accepted Orange RESET; the stroke counter's origin. */
        var orangeResetAtMs: Long = 0L
    }

    private val main = Handler(Looper.getMainLooper())
    private val epoch = AtomicLong(0)

    /** Service publication and leak-free shutdown for this owner's GATT servers (LIB-9g). */
    private val gattSessions = GattServerSessions<BluetoothGattService>()
    private val clients = ConcurrentHashMap<String, Client>()
    private val advertisingGate = ConnectableAdvertisingGate()
    private var server: BluetoothGattServer? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var startResult: ((Result<Unit>) -> Unit)? = null
    private var latestSample: FtmsMockSample? = null
    private var activePersona: SimulatorPersona = SimulatorPersona.KayakFirstBlue
    private var status = Status()
    @Volatile var isAdvertising = false
        private set
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val adapterNames = AdapterNameCoordinator(
        gateway = object : AdapterNameCoordinator.Gateway {
            @get:SuppressLint("MissingPermission", "HardwareIds")
            override val currentName: String? get() = adapter()?.name
            @SuppressLint("MissingPermission")
            override fun requestName(name: String): Boolean = adapter()?.setName(name) == true
        },
        store = object : AdapterNameCoordinator.OriginalNameStore {
            override fun read(): String? = prefs.getString(KEY_ORIGINAL_NAME, null)
            override fun write(name: String): Boolean = prefs.edit().putString(KEY_ORIGINAL_NAME, name).commit()
            override fun clear(): Boolean = prefs.edit().remove(KEY_ORIGINAL_NAME).commit()
        },
        scheduler = object : AdapterNameCoordinator.Scheduler {
            private val tasks = mutableMapOf<Any, Runnable>()
            override fun postDelayed(token: Any, delayMs: Long, action: () -> Unit) {
                cancel(token)
                val task = Runnable { tasks.remove(token); action() }
                tasks[token] = task
                main.postDelayed(task, delayMs)
            }
            override fun cancel(token: Any) { tasks.remove(token)?.let(main::removeCallbacks) }
        },
    )

    fun publish(sample: FtmsMockSample) { latestSample = sample }

    @SuppressLint("MissingPermission")
    fun start(persona: SimulatorPersona, onResult: (Result<Unit>) -> Unit) {
        require(
            persona == SimulatorPersona.KayakFirstBlue ||
                persona == SimulatorPersona.KayakFirstOrange,
        )
        activePersona = persona
        val currentEpoch = epoch.incrementAndGet()
        if (!hasPermissions()) {
            val error = IllegalStateException("Bluetooth advertising permission has not been granted")
            status = Status(lastError = error.message)
            publishStatus(); onResult(Result.failure(error)); return
        }
        stopResources(restoreName = false)
        status = Status(advertisedName = persona.advertisedName)
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        if (manager == null || adapter == null || !adapter.isEnabled) {
            onResult(Result.failure(IllegalStateException("Bluetooth is unavailable or disabled"))); return
        }
        startResult = onResult
        adapterNames.apply(persona.advertisedName) nameCallback@{ result ->
            if (epoch.get() != currentEpoch) return@nameCallback
            if (result.isFailure) {
                finishStart(Result.failure(result.exceptionOrNull()!!))
                return@nameCallback
            }
            server = manager.openGattServer(context, callback(currentEpoch))
            server?.let { gattSessions.open(currentEpoch, it.gattOps()) }
            if (server == null || !gattSessions.addService(KayakFirstGattProfile.buildService())) {
                finishStart(Result.failure(IllegalStateException("Could not open KayakFirst GATT service")))
            }
        }
    }

    fun stop() {
        epoch.incrementAndGet()
        stopResources(restoreName = true)
        status = Status()
        publishStatus()
    }

    fun dispose() {
        stop()
        adapterNames.cancelAll("KayakFirst controller disposed")
    }

    @SuppressLint("MissingPermission")
    private fun stopResources(restoreName: Boolean) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        advertiseCallback?.let { runCatching { manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(it) } }
        advertiseCallback = null
        isAdvertising = false
        clients.values.forEach { client ->
            client.assembler.reset(); client.queue.clear()
            runCatching { server?.cancelConnection(client.device) }
        }
        clients.clear()
        advertisingGate.reset()
        gattSessions.closeCurrent(); server = null
        startResult = null
        FtmsForegroundService.stop(context)
        if (restoreName) adapterNames.restore()
    }

    private fun callback(callbackEpoch: Long) = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(result: Int, service: BluetoothGattService) {
            if (gattSessions.onServiceAdded(callbackEpoch, service)) return
            if (epoch.get() != callbackEpoch) return
            if (result == BluetoothGatt.GATT_SUCCESS) startAdvertising(callbackEpoch)
            else finishStart(Result.failure(IllegalStateException("KayakFirst service add failed ($result)")))
        }

        override fun onConnectionStateChange(device: BluetoothDevice, result: Int, newState: Int) {
            if (epoch.get() != callbackEpoch) return
            val key = device.address.lowercase()
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                clients.putIfAbsent(key, Client(device))
                if (advertisingGate.onConnected(key) == ConnectableAdvertisingGate.Action.PAUSE) {
                    pauseAdvertising()
                }
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                clients.remove(key)?.also { it.assembler.reset(); it.queue.clear() }
                if (advertisingGate.onDisconnected(key) == ConnectableAdvertisingGate.Action.RESUME) {
                    resumeAdvertising(callbackEpoch)
                }
            }
            publishStatus()
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            val client = clients[device.address.lowercase()]
            val valid = client != null && KayakFirstGattProfile.acceptsWrite(characteristic, preparedWrite, offset)
            if (responseNeeded) server?.sendResponse(device, requestId, if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
            if (!valid) return
            if (activePersona == SimulatorPersona.KayakFirstOrange) {
                val command = KayakFirstOrangeSimulatorProtocol.decodeCommand(value)
                if (command == null) {
                    status = status.copy(
                        malformedCommands = status.malformedCommands + 1,
                        lastCommand = "rejected Orange command",
                    )
                } else {
                    handleOrange(client, command)
                }
            } else {
                client.assembler.append(value).forEach { input ->
                    when (input) {
                        is KayakFirstSimulatorInput.Command -> handle(client, input.value)
                        is KayakFirstSimulatorInput.Rejected -> status = status.copy(malformedCommands = status.malformedCommands + 1, lastCommand = "rejected: ${input.reason}")
                    }
                }
            }
            publishStatus()
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            val client = clients[device.address.lowercase()]
            val value = if (client != null && descriptor.uuid == SigUuids.UUID_CCCD && descriptor.characteristic.uuid == KayakFirstUuids.UUID_KAYAK_FIRST_IO) {
                if (client.subscribed) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            } else null
            when {
                value == null -> server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                offset !in 0..value.size -> server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, 0, null)
                else -> server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
            }
        }

        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            val client = clients[device.address.lowercase()]
            val enable = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            val disable = value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
            val valid = client != null && descriptor.uuid == SigUuids.UUID_CCCD && descriptor.characteristic.uuid == KayakFirstUuids.UUID_KAYAK_FIRST_IO && !preparedWrite && offset == 0 && (enable || disable)
            if (valid) {
                client.subscribed = enable
                if (disable) synchronized(client) { client.queue.clear() }
            }
            if (responseNeeded) server?.sendResponse(device, requestId, if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null)
            publishStatus()
        }

        override fun onNotificationSent(device: BluetoothDevice, result: Int) {
            val client = clients[device.address.lowercase()] ?: return
            synchronized(client) { client.inFlight = false }
            if (result == BluetoothGatt.GATT_SUCCESS) status = status.copy(packetsSent = status.packetsSent + 1)
            drain(client); publishStatus()
        }
    }

    private fun handle(client: Client, command: KayakFirstSimulatorCommand) {
        status = status.copy(lastCommand = when (command) {
            KayakFirstSimulatorCommand.Reset -> "reset"
            is KayakFirstSimulatorCommand.Handshake -> "handshake"
            is KayakFirstSimulatorCommand.Display -> "display configuration"
            KayakFirstSimulatorCommand.Poll -> "poll"
            KayakFirstSimulatorCommand.Start -> "session start"
            KayakFirstSimulatorCommand.Stop -> "session stop"
        })
        when (command) {
            KayakFirstSimulatorCommand.Reset -> {
                client.setupStage = "reset"; client.running = false
                client.lastGeneratedEventKey = null
                client.lastCoveredElapsedSeconds = null
                enqueueLine(client, "1;1\r\n")
            }
            is KayakFirstSimulatorCommand.Handshake -> {
                // A console answers under the token it was addressed with, so a command-21
                // handshake is acknowledged as "21;1" and the legacy form as "2;1".
                command.sport?.let { client.sport = it }
                command.boatWeightKg?.let { client.boatWeightKg = it }
                client.setupStage = "handshake"
                val token = when (command.variant) {
                    KayakFirstHandshakeVariant.LEGACY -> "2"
                    KayakFirstHandshakeVariant.BULL -> "21"
                }
                enqueueLine(client, "$token;1\r\n")
            }
            is KayakFirstSimulatorCommand.Display -> {
                client.display = command.configuration; client.setupStage = "ready"
                enqueueLine(client, "5;1\r\n")
            }
            KayakFirstSimulatorCommand.Start -> {
                client.running = true; client.lastGeneratedEventKey = null
                client.lastCoveredElapsedSeconds = null
                enqueueLine(client, "9;1\r\n")
            }
            KayakFirstSimulatorCommand.Stop -> {
                client.running = false; enqueueLine(client, "9;3\r\n")
            }
            KayakFirstSimulatorCommand.Poll -> {
                client.polls++
                status = status.copy(pollRequests = status.pollRequests + 1)
                val sample = latestSample ?: return
                // Field 7 is the session's activity state, held for the whole of an effort the way
                // the physical console holds it, so it is derived from whether the generated
                // workout is producing movement — never from whether a new stroke happened to land
                // between two polls. Keying it on stroke arrival made it toggle at the stroke
                // period and cost every field-7-gated consumer that fraction of its samples.
                val working = client.running &&
                    ((sample.cadence ?: 0.0) > 0.0 || (sample.speedKph ?: 0.0) > 0.0)
                // Field 23 carries the "nothing new here" case instead. A poll that re-reads a
                // datapoint already sent covers zero further seconds; otherwise the frame covers
                // the session time since the last datapoint that went out.
                val eventKey = sample.movementCount?.toLong() ?: sample.elapsedSeconds.toLong()
                val fresh = client.running && client.lastGeneratedEventKey != eventKey
                val coverageSeconds = when {
                    !client.running -> 0
                    !fresh -> 0
                    else -> client.lastCoveredElapsedSeconds
                        ?.let { (sample.elapsedSeconds - it).coerceAtLeast(0) }
                        ?: sample.elapsedSeconds.coerceAtLeast(0)
                }
                if (fresh) {
                    client.lastGeneratedEventKey = eventKey
                    client.lastCoveredElapsedSeconds = sample.elapsedSeconds
                }
                val frame = KayakFirstSimulatorTelemetryEncoder.generated(
                    sample,
                    client.running,
                    working,
                    coverageSeconds,
                )
                if (enqueueRecord(client, frame)) status = status.copy(completeRecords = status.completeRecords + 1)
            }
        }
    }

    private fun handleOrange(
        client: Client,
        command: com.gmail.absolutebollockscreations.gattlibrary.core.vendor.KayakFirstOrangeCommand,
    ) {
        status = status.copy(lastCommand = command.name.lowercase())
        if (command == com.gmail.absolutebollockscreations.gattlibrary.core.vendor.KayakFirstOrangeCommand.RESET) {
            client.orangeSequence = 0L
            client.orangeResetAtMs = SystemClock.elapsedRealtime()
            client.setupStage = "reset"
            enqueueRecord(client, KayakFirstOrangeSimulatorProtocol.resetResponse())
            return
        }
        val sample = latestSample
        val tuple = KayakFirstOrangeSimulatorProtocol.dynamicTuple(
            powerWatts = sample?.powerWatts?.toDouble() ?: 0.0,
            cadenceSpm = sample?.cadence?.takeIf { it > 0.0 } ?: 60.0,
        ) ?: return
        val timing = KayakFirstOrangeSimulatorProtocol.timingMicroseconds(
            command,
            tuple,
            auxiliaryRpm = sample?.cadence ?: 0.0,
        ) ?: return
        // The sequence is a stroke counter, not a response counter: several commands answered
        // inside one stroke period share a value, and values are skipped when a stroke completes
        // between polls. See KayakFirstOrangeSimulatorProtocol.sequenceFor.
        if (client.orangeResetAtMs == 0L) client.orangeResetAtMs = SystemClock.elapsedRealtime()
        val sequence = KayakFirstOrangeSimulatorProtocol.sequenceFor(
            tuple,
            SystemClock.elapsedRealtime() - client.orangeResetAtMs,
        )
        client.orangeSequence = sequence
        client.setupStage = "ready"
        client.polls += 1L
        status = status.copy(pollRequests = status.pollRequests + 1)
        val record = KayakFirstOrangeSimulatorProtocol.response(command, sequence, timing)
        if (enqueueRecord(client, record)) {
            status = status.copy(completeRecords = status.completeRecords + 1)
        }
    }

    private fun enqueueLine(client: Client, line: String) = enqueueRecord(client, line.toByteArray(Charsets.UTF_8))

    private fun enqueueRecord(client: Client, record: ByteArray): Boolean {
        if (!client.subscribed) return false
        // Real consoles never put a whole record in one notification; they fill the 20-byte
        // ATT payload and continue in the next. This is wire behavior, not a fault mode, so it
        // is unconditional — and when a record's CR LF straddles a boundary it reproduces the
        // split-delimiter framing the central's assembler has to tolerate.
        val fragments = record.asList().chunked(20).map { it.toByteArray() }
        synchronized(client) {
            if (client.queue.size + fragments.size > MAX_QUEUED_FRAGMENTS) {
                status = status.copy(droppedRecords = status.droppedRecords + 1)
                return false
            }
            fragments.forEach(client.queue::addLast)
        }
        drain(client)
        return true
    }

    @SuppressLint("MissingPermission")
    private fun drain(client: Client) {
        val payload = synchronized(client) {
            if (client.inFlight || !client.subscribed) return
            client.queue.removeFirstOrNull()?.also { client.inFlight = true }
        } ?: return
        val io = server?.getService(KayakFirstUuids.UUID_KAYAK_FIRST_SERVICE)?.getCharacteristic(KayakFirstUuids.UUID_KAYAK_FIRST_IO)
        if (io == null || !notify(client.device, io, payload)) synchronized(client) { client.inFlight = false }
    }

    @SuppressLint("MissingPermission")
    private fun notify(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        val active = server ?: return false
        return if (Build.VERSION.SDK_INT >= 33) active.notifyCharacteristicChanged(device, characteristic, false, value) == BluetoothGatt.GATT_SUCCESS
        else @Suppress("DEPRECATION") run { characteristic.value = value; active.notifyCharacteristicChanged(device, characteristic, false) }
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    private fun startAdvertising(currentEpoch: Long) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val advertiser = manager?.adapter?.bluetoothLeAdvertiser
        if (advertiser == null) { finishStart(Result.failure(IllegalStateException("BLE advertising is unavailable"))); return }
        val plan = runCatching {
            KayakFirstAdvertisingPlan.create(
                localName = activePersona.advertisedName,
                localAddress = requireNotNull(manager.adapter.address),
            )
        }.getOrElse { KayakFirstAdvertisingPlan.create(localName = activePersona.advertisedName) }
        val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(true).setTimeout(0).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH).build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(KayakFirstUuids.UUID_KAYAK_FIRST_SERVICE))
            .addServiceUuid(ParcelUuid(KayakFirstUuids.UUID_KAYAK_FIRST_IO))
            .addManufacturerData(plan.manufacturerId, plan.manufacturerPayload)
            .build()
        val scan = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .setIncludeTxPowerLevel(true)
            .build()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                if (epoch.get() != currentEpoch || advertiseCallback !== this) return
                isAdvertising = true; status = status.copy(advertising = true)
                FtmsForegroundService.start(context, "Sharing as ${plan.localName}", "Simulated KayakFirst paddle console") { result ->
                    if (result.isSuccess) finishStart(Result.success(Unit)) else finishStart(Result.failure(result.exceptionOrNull()!!))
                }
                publishStatus()
            }
            override fun onStartFailure(errorCode: Int) {
                if (epoch.get() != currentEpoch || advertiseCallback !== this) return
                finishStart(Result.failure(IllegalStateException("KayakFirst advertising failed ($errorCode)")))
            }
        }
        advertiseCallback = callback
        advertiser.startAdvertising(settings, data, scan, callback)
    }

    @SuppressLint("MissingPermission")
    private fun pauseAdvertising() {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        advertiseCallback?.let { callback ->
            runCatching { manager?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback) }
        }
        advertiseCallback = null
        isAdvertising = false
        status = status.copy(advertising = false)
    }

    private fun resumeAdvertising(currentEpoch: Long) {
        if (server == null || clients.isNotEmpty() || advertiseCallback != null || isAdvertising) return
        startAdvertising(currentEpoch)
    }

    private fun finishStart(result: Result<Unit>) {
        val callback = startResult ?: return
        startResult = null
        if (result.isFailure) stopResources(restoreName = true)
        main.post { callback(result) }
    }

    private fun publishStatus() {
        val active = clients.values.firstOrNull()
        status = status.copy(
            advertising = isAdvertising,
            connected = clients.isNotEmpty(),
            subscribed = clients.values.any { it.subscribed },
            generationReady = clients.values.any {
                if (activePersona == SimulatorPersona.KayakFirstOrange) {
                    it.subscribed && it.setupStage == "ready"
                } else {
                    kayakFirstGenerationState(it.subscribed, it.setupStage, it.running).ready
                }
            },
            generationAdvancing = clients.values.any {
                if (activePersona == SimulatorPersona.KayakFirstOrange) {
                    it.subscribed
                } else {
                    kayakFirstGenerationState(it.subscribed, it.setupStage, it.running).advancing
                }
            },
            setupStage = active?.setupStage ?: "waiting",
            displayConfiguration = active?.display ?: KayakFirstDisplayConfiguration.DEFAULT,
            queuedFragments = clients.values.sumOf { synchronized(it) { it.queue.size } },
        )
        onStatus(status)
    }

    private fun hasPermissions() = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun adapter() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    companion object {
        private const val MAX_QUEUED_FRAGMENTS = 16
        private const val PREFS = "com.gmail.absolutebollockscreations.Fitbridge.peripheral"
        private const val KEY_ORIGINAL_NAME = "original_adapter_name"
    }
}
