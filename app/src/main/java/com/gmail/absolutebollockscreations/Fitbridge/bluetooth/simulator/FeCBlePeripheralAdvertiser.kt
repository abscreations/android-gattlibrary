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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import timber.log.Timber
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.ConnectableAdvertisingGate
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.FtmsControlPointHandler
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.FtmsProcedureSession
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.peripheral.PeripheralConnectionRetentionGuard

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
     * instantaneous power equals the same coherent machine-state power used by page 25 on
     * Stationary Bike and the page-26 torque accumulator on Trainer; this class never generates
     * CPS data itself.
 */
internal class FeCBlePeripheralAdvertiser(
    private val context: Context,
    private val state: FeCBleMachineState,
    private val controlsEnabled: Boolean,
    private val includeCps: Boolean,
    private val identity: FeCBleSimulatorIdentity,
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
        var cscSubscribed = false
        var ftmsSubscribed = false
        var ftmsStatusSubscribed = false
        var ftmsControlIndications = false
        val ftmsProcedure = FtmsProcedureSession()
        var inFlight = false
        var inFlightUuid: UUID? = null
        var pendingFec2: ByteArray? = null
        val requestedFec2: ArrayDeque<ByteArray> = ArrayDeque()
        var pendingCps: ByteArray? = null
        var pendingCsc: ByteArray? = null
        var pendingFtms: ByteArray? = null
        val pendingFtmsProcedure: ArrayDeque<Pair<UUID, ByteArray>> = ArrayDeque()
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
    private var pendingServices: ArrayDeque<BluetoothGattService> = ArrayDeque()
    private val ftmsControl = FtmsControlPointHandler(TacxFlux2FtmsControlSink(state))

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

    fun publishCsc(frame: ByteArray) {
        if (identity != FeCBleSimulatorIdentity.TACX_NEO) return
        clients.values.forEach { client -> if (client.cscSubscribed) enqueueAux(client, frame.copyOf(), BluetoothIds.UUID_CSC_MEASUREMENT) }
    }

    fun publishFtms(frame: ByteArray) {
        if (identity != FeCBleSimulatorIdentity.TACX_FLUX_2) return
        clients.values.forEach { client ->
            if (client.ftmsSubscribed) enqueueAux(client, frame.copyOf(), BluetoothIds.UUID_BIKING_DATA)
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
        server?.let { gattSessions.open(currentEpoch, it.gattOps()) }
        if (server == null) {
            finishStart(Result.failure(IllegalStateException("Could not open FE-C GATT server")))
            return
        }
        // Every FE persona needs FEC3 for Common Page 70 requests, even when target control is
        // disabled. [controlsEnabled] gates pages 48-55; the characteristic itself is always served.
        pendingServices = ArrayDeque(FeCBleGattProfile.buildServices(includeControl = true, includeCps, identity))
        addNextService()
    }

    @SuppressLint("MissingPermission")
    private fun addNextService() {
        val next = pendingServices.removeFirstOrNull() ?: return
        if (gattSessions.addService(next) != true) {
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
        advertisingGate.reset()
        connectionRetention.clear()
        gattSessions.closeCurrent()
        server = null
        startResult?.invoke(Result.failure(IllegalStateException("FE-C start was stopped")))
        startResult = null
        listener.onState(false, 0, 0, null)
    }

    private fun callback(callbackEpoch: Long) = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (gattSessions.onServiceAdded(callbackEpoch, service)) return
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
                    if (advertisingGate.onConnected(key) == ConnectableAdvertisingGate.Action.PAUSE) {
                        pauseAdvertising()
                    }
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
            // FEC2/FEC3 are not readable (plan §6). Identity overlays additionally expose only
            // capture-established standard information reads; undocumented Tacx values stay null.
            val whole = tacxReadValue(identity, characteristic.uuid, includeCps)
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
            val isFtmsControl = identity == FeCBleSimulatorIdentity.TACX_FLUX_2 &&
                characteristic.uuid == BluetoothIds.UUID_FITNESS_MACHINE_CONTROL_POINT
            var accepted = false
            var ftmsOutcome: FtmsControlPointHandler.Outcome? = null
            if (isFtmsControl && client != null && client.ftmsControlIndications &&
                !preparedWrite && offset == 0 && client.ftmsProcedure.tryBeginProcedure()
            ) {
                ftmsOutcome = ftmsControl.handle(value, client.ftmsProcedure)
                accepted = true
            } else if (isControl && client != null && !preparedWrite && offset == 0) {
                when (val command = FeCBleControlDecoder.decode(value)) {
                    is FeCBleControlCommand.Rejected -> listener.onControlWriteRejected(command.reason)
                    is FeCBleControlCommand.Unsupported -> listener.onUnsupportedControlPage(command.page)
                    is FeCBleControlCommand.RequestDataPage -> {
                        if (client.subscribed) {
                            val messageType = if (command.acknowledgedResponse) {
                                BluetoothIds.FEC_ANT_MSG_ACKNOWLEDGED_DATA
                            } else {
                                BluetoothIds.FEC_ANT_MSG_BROADCAST_DATA
                            }
                            val response = synchronized(state) {
                                FeCBleRequestedPageEncoder.encode(
                                    requestedPage = command.requestedPage,
                                    messageType = messageType,
                                    identity = identity,
                                    state = state,
                                )
                            }
                            if (response != null) {
                                synchronized(client) {
                                    client.requestedFec2.clear()
                                    repeat(command.transmissionCount) {
                                        client.requestedFec2.addLast(response.copyOf())
                                    }
                                }
                                drain(client)
                                accepted = true
                                listener.onControlWriteAccepted(
                                    BluetoothIds.FEC_PAGE_REQUEST_DATA_PAGE,
                                    device.address,
                                )
                            } else {
                                listener.onUnsupportedControlPage(command.requestedPage)
                            }
                        } else {
                            listener.onUnsupportedControlPage(command.requestedPage)
                        }
                    }
                    else -> {
                        if (controlsEnabled && state.equipmentType.supportsControl) {
                            synchronized(state) {
                                FeCBleSyntheticModel.applyControlCommand(state, command, device.address)
                            }
                            accepted = true
                            listener.onControlWriteAccepted(
                                (value.getOrNull(4)?.toInt() ?: -1) and 0xFF,
                                device.address,
                            )
                        } else {
                            listener.onUnsupportedControlPage((value.getOrNull(4)?.toInt() ?: -1) and 0xFF)
                        }
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
            if (accepted && isFtmsControl && client != null && ftmsOutcome != null) {
                val outcome = ftmsOutcome
                main.post {
                    synchronized(client) {
                        client.pendingFtmsProcedure.addLast(
                            BluetoothIds.UUID_FITNESS_MACHINE_CONTROL_POINT to outcome.response.copyOf(),
                        )
                        // Local copy: a public property from another module cannot be smart-cast (LIB-10d).
                        val status = outcome.status
                        if (status != null && client.ftmsStatusSubscribed) {
                            client.pendingFtmsProcedure.addLast(
                                BluetoothIds.UUID_FITNESS_MACHINE_STATUS to status.copyOf(),
                            )
                        }
                    }
                    drain(client)
                }
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            val client = clients[device.address.lowercase()]
            val whole = subscriptionValueFor(client, descriptor)
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
            val enableIndication = value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
            val disable = value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
            val characteristicUuid = descriptor.characteristic.uuid
            val isFec2 = characteristicUuid == BluetoothIds.UUID_FEC_MEASUREMENT
            val isCps = includeCps && characteristicUuid == BluetoothIds.UUID_CYCLING_POWER_MEASUREMENT
            val isCsc = identity == FeCBleSimulatorIdentity.TACX_NEO && characteristicUuid == BluetoothIds.UUID_CSC_MEASUREMENT
            val isFtms = identity == FeCBleSimulatorIdentity.TACX_FLUX_2 && characteristicUuid == BluetoothIds.UUID_BIKING_DATA
            val isFtmsStatus = identity == FeCBleSimulatorIdentity.TACX_FLUX_2 && characteristicUuid == BluetoothIds.UUID_FITNESS_MACHINE_STATUS
            val isFtmsControl = identity == FeCBleSimulatorIdentity.TACX_FLUX_2 && characteristicUuid == BluetoothIds.UUID_FITNESS_MACHINE_CONTROL_POINT
            val valid = client != null && descriptor.uuid == BluetoothIds.UUID_CCCD &&
                (isFec2 || isCps || isCsc || isFtms || isFtmsStatus || isFtmsControl) &&
                !preparedWrite && offset == 0 &&
                (disable || (enable && !isFtmsControl) || (enableIndication && isFtmsControl))

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
                        synchronized(client) { client.requestedFec2.clear() }
                    }
                } else if (isCps) {
                    if (enable) {
                        client.cpsSubscribed = true
                    } else if (disable) {
                        client.cpsSubscribed = false
                        synchronized(client) { client.pendingCps = null }
                    }
                } else if (isCsc) {
                    client.cscSubscribed = enable
                    if (disable) synchronized(client) { client.pendingCsc = null }
                } else if (isFtms) {
                    client.ftmsSubscribed = enable
                    if (disable) synchronized(client) { client.pendingFtms = null }
                } else if (isFtmsStatus) {
                    client.ftmsStatusSubscribed = enable
                } else if (isFtmsControl) {
                    client.ftmsControlIndications = enableIndication
                    if (disable) {
                        client.ftmsProcedure.resetForDisconnect()
                        synchronized(client) { client.pendingFtmsProcedure.clear() }
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
            val completedUuid = synchronized(client) {
                client.inFlight = false
                client.inFlightUuid.also { client.inFlightUuid = null }
            }
            if (completedUuid == BluetoothIds.UUID_FITNESS_MACHINE_CONTROL_POINT) {
                client.ftmsProcedure.finishProcedure()
            }
            if (status == BluetoothGatt.GATT_SUCCESS) listener.onPacketSent()
            drain(client)
        }
    }

    /** Null when the descriptor isn't a CCCD this server recognizes for the given client. */
    private fun subscriptionValueFor(client: Client?, descriptor: BluetoothGattDescriptor): ByteArray? {
        if (client == null || descriptor.uuid != BluetoothIds.UUID_CCCD) return null
        val enabled = when (descriptor.characteristic.uuid) {
            BluetoothIds.UUID_FEC_MEASUREMENT -> client.subscribed to false
            BluetoothIds.UUID_CYCLING_POWER_MEASUREMENT -> if (includeCps) client.cpsSubscribed to false else null
            BluetoothIds.UUID_CSC_MEASUREMENT -> if (identity == FeCBleSimulatorIdentity.TACX_NEO) client.cscSubscribed to false else null
            BluetoothIds.UUID_BIKING_DATA -> if (identity == FeCBleSimulatorIdentity.TACX_FLUX_2) client.ftmsSubscribed to false else null
            BluetoothIds.UUID_FITNESS_MACHINE_STATUS -> if (identity == FeCBleSimulatorIdentity.TACX_FLUX_2) client.ftmsStatusSubscribed to false else null
            BluetoothIds.UUID_FITNESS_MACHINE_CONTROL_POINT -> if (identity == FeCBleSimulatorIdentity.TACX_FLUX_2) client.ftmsControlIndications to true else null
            else -> null
        } ?: return null
        return when {
            !enabled.first -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            enabled.second -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
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
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        val plan = tacxAdvertisingPlan(identity, includeCps)
        val dataBuilder = AdvertiseData.Builder()
            .setIncludeDeviceName(plan.includeNameInAdvertisement)
        plan.advertiseServices.forEach { dataBuilder.addServiceUuid(ParcelUuid(it)) }
        if (plan.manufacturerId != null && plan.manufacturerData != null) {
            dataBuilder.addManufacturerData(plan.manufacturerId, plan.manufacturerData)
        }
        val data = dataBuilder.build()
        val scanBuilder = AdvertiseData.Builder()
            .setIncludeDeviceName(!plan.includeNameInAdvertisement)
        plan.scanResponseServices.forEach { scanBuilder.addServiceUuid(ParcelUuid(it)) }
        plan.serviceData.forEach { (uuid, value) -> scanBuilder.addServiceData(ParcelUuid(uuid), value) }
        val scanResponse = scanBuilder.build()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                if (epoch.get() != currentEpoch || advertiseCallback !== this) return
                isAdvertising = true
                publishState()
                finishStart(Result.success(Unit))
            }

            override fun onStartFailure(errorCode: Int) {
                if (epoch.get() != currentEpoch || advertiseCallback !== this) return
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

    private fun enqueueAux(client: Client, frame: ByteArray, characteristicUuid: UUID) {
        require(characteristicUuid == BluetoothIds.UUID_CSC_MEASUREMENT || characteristicUuid == BluetoothIds.UUID_BIKING_DATA)
        val conflated = synchronized(client) {
            val had = if (characteristicUuid == BluetoothIds.UUID_CSC_MEASUREMENT) client.pendingCsc != null else client.pendingFtms != null
            if (characteristicUuid == BluetoothIds.UUID_CSC_MEASUREMENT) client.pendingCsc = frame else client.pendingFtms = frame
            had
        }
        if (conflated) listener.onNotificationConflated()
        drain(client)
    }

    /**
     * Android allows only one outstanding notification per device connection at a time, so this
     * drains a single characteristic's pending frame per call. Procedure indications are first;
     * the remaining measurement streams are conflated independently while Android is busy.
     */
    @SuppressLint("MissingPermission")
    private fun drain(client: Client) {
        val chosen = synchronized(client) {
            if (client.inFlight) return
            val requestedFrame = client.requestedFec2.removeFirstOrNull()
            val fec2Frame = client.pendingFec2
            val cpsFrame = client.pendingCps
            val cscFrame = client.pendingCsc
            val ftmsProcedureFrame = client.pendingFtmsProcedure.removeFirstOrNull()
            val ftmsFrame = client.pendingFtms
            val next = when {
                ftmsProcedureFrame != null -> ftmsProcedureFrame
                requestedFrame != null -> BluetoothIds.UUID_FEC_MEASUREMENT to requestedFrame
                fec2Frame != null -> {
                    client.pendingFec2 = null
                    BluetoothIds.UUID_FEC_MEASUREMENT to fec2Frame
                }
                cpsFrame != null -> {
                    client.pendingCps = null
                    BluetoothIds.UUID_CYCLING_POWER_MEASUREMENT to cpsFrame
                }
                cscFrame != null -> {
                    client.pendingCsc = null
                    BluetoothIds.UUID_CSC_MEASUREMENT to cscFrame
                }
                ftmsFrame != null -> {
                    client.pendingFtms = null
                    BluetoothIds.UUID_BIKING_DATA to ftmsFrame
                }
                else -> null
            } ?: return
            client.inFlight = true
            client.inFlightUuid = next.first
            next
        }
        val (characteristicUuid, frame) = chosen
        val characteristic = gattCharacteristicFor(characteristicUuid)
        val indicate = characteristicUuid == BluetoothIds.UUID_FITNESS_MACHINE_CONTROL_POINT
        if (characteristic == null || !notify(client.device, characteristic, frame, indicate)) {
            synchronized(client) {
                client.inFlight = false
                client.inFlightUuid = null
            }
            if (indicate) client.ftmsProcedure.finishProcedure()
        }
    }

    private fun gattCharacteristicFor(characteristicUuid: UUID): BluetoothGattCharacteristic? {
        val serviceUuid = when (characteristicUuid) {
            BluetoothIds.UUID_CYCLING_POWER_MEASUREMENT -> BluetoothIds.UUID_CYCLING_POWER_SERVICE
            BluetoothIds.UUID_CSC_MEASUREMENT -> BluetoothIds.UUID_CSC_SERVICE
            BluetoothIds.UUID_BIKING_DATA,
            BluetoothIds.UUID_FITNESS_MACHINE_STATUS,
            BluetoothIds.UUID_FITNESS_MACHINE_CONTROL_POINT,
            -> BluetoothIds.UUID_FTMS_SERVICE
            else -> BluetoothIds.UUID_FEC_SERVICE
        }
        return server?.getService(serviceUuid)?.getCharacteristic(characteristicUuid)
    }

    @SuppressLint("MissingPermission")
    private fun notify(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, value: ByteArray, indicate: Boolean = false): Boolean {
        val activeServer = server ?: return false
        return if (Build.VERSION.SDK_INT >= 33) {
            activeServer.notifyCharacteristicChanged(device, characteristic, indicate, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            activeServer.notifyCharacteristicChanged(device, characteristic, indicate)
        }
    }

    private fun publishState() = listener.onState(isAdvertising, clients.size, subscribedCount(), null)

    private fun subscribedCount(): Int = clients.values.count {
        it.subscribed || it.cpsSubscribed || it.cscSubscribed || it.ftmsSubscribed ||
            it.ftmsStatusSubscribed || it.ftmsControlIndications
    }
}
