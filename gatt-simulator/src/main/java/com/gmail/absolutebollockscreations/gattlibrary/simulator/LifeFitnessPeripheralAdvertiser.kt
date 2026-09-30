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
import com.gmail.absolutebollockscreations.gattlibrary.core.ftms.FtmsUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.LifeFitnessUuids
import com.gmail.absolutebollockscreations.gattlibrary.core.ids.SigUuids

/** Setup-gated multi-service GATT server reconstructed from the Phase 6 capture set. */
internal class LifeFitnessPeripheralAdvertiser(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onState(
            advertising: Boolean,
            connected: Boolean,
            subscribed: Boolean,
            statusSubscribed: Boolean,
            setupStage: LifeFitnessSetupServer.Stage?,
            error: String?,
        )

        fun onPacketSent()
    }

    private data class Notification(val uuid: UUID, val value: ByteArray)

    private data class PendingWrite(
        val requestId: Int,
        val characteristicUuid: UUID,
        val preparedWrite: Boolean,
        val responseNeeded: Boolean,
        val offset: Int,
        val value: ByteArray,
    )

    private inner class Client(val device: BluetoothDevice) {
        val setup = LifeFitnessSetupServer()
        val subscriptions = mutableSetOf<UUID>()
        val pending = ArrayDeque<Notification>()
        val writes = LifeFitnessRequestSequencer<PendingWrite> { processCharacteristicWrite(this, it) }
        var inFlight = false
        @Volatile var active = true
        var trainingStatus = TRAINING_PRE_WORKOUT.copyOf()

        val measurementSubscribed: Boolean
            get() = subscriptions.any { it in LifeFitnessMachine.entries.map(LifeFitnessMachine::measurementUuid) }

        fun clear() = synchronized(this) {
            active = false
            writes.clear()
            setup.clearSensitiveState()
        }
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
    private var servicesToAdd = emptyList<BluetoothGattService>()
    private var nextServiceIndex = 0
    private var machine: LifeFitnessMachine? = null

    @Volatile private var latestMeasurement: ByteArray? = null
    @Volatile var isAdvertising: Boolean = false
        private set

    fun publish(sample: FtmsMockSample) {
        val activeMachine = machine ?: return
        publishMeasurement(LifeFitnessPacketEncoder.encode(activeMachine, sample), sample.elapsedSeconds > 0)
    }

    private fun publishMeasurement(measurement: ByteArray, active: Boolean) {
        val activeMachine = machine ?: return
        latestMeasurement = measurement.copyOf()
        clients.values.filter { it.setup.stage == LifeFitnessSetupServer.Stage.UNLOCKED }.forEach { client ->
            if (active && !client.trainingStatus.contentEquals(TRAINING_ACTIVE)) {
                client.trainingStatus = TRAINING_ACTIVE.copyOf()
                if (FtmsUuids.UUID_TRAINING_STATUS in client.subscriptions) {
                    enqueue(client, FtmsUuids.UUID_TRAINING_STATUS, client.trainingStatus)
                }
            }
            if (
                LifeFitnessNotificationPolicy.mayStreamMeasurement(
                    client.setup.stage,
                    activeMachine.measurementUuid,
                    client.subscriptions,
                )
            ) {
                enqueue(client, activeMachine.measurementUuid, measurement)
            }
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE])
    fun start(machine: LifeFitnessMachine, onResult: (Result<Unit>) -> Unit) {
        stop()
        val currentEpoch = epoch.incrementAndGet()
        this.machine = machine
        latestMeasurement = null
        startResult = onResult
        servicesToAdd = LifeFitnessGattProfile.buildServices(machine)
        nextServiceIndex = 0
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (manager?.adapter?.isEnabled != true) {
            finishStart(Result.failure(IllegalStateException("Bluetooth is unavailable or disabled")))
            return
        }
        server = manager.openGattServer(context, callback(currentEpoch))
        server?.let { gattSessions.open(currentEpoch, it.gattOps()) }
        if (server == null) {
            finishStart(Result.failure(IllegalStateException("Could not open Life Fitness GATT server")))
            return
        }
        addNextService()
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
        clients.values.forEach { client ->
            client.clear()
            runCatching { server?.cancelConnection(client.device) }
        }
        clients.clear()
        advertisingGate.reset()
        connectionRetention.clear()
        gattSessions.closeCurrent()
        server = null
        servicesToAdd = emptyList()
        nextServiceIndex = 0
        machine = null
        latestMeasurement = null
        startResult?.invoke(Result.failure(IllegalStateException("Life Fitness start was stopped")))
        startResult = null
        listener.onState(false, false, false, false, null, null)
    }

    private fun callback(callbackEpoch: Long) = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (gattSessions.onServiceAdded(callbackEpoch, service)) return
            if (epoch.get() != callbackEpoch) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finishStart(Result.failure(IllegalStateException("Life Fitness service add failed ($status)")))
                return
            }
            addNextService()
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
                                Timber.w(error, "Life Fitness GATT server could not retain inbound connection")
                                false
                            }
                        if (!retained) {
                            Timber.w("Life Fitness GATT server did not retain inbound connection")
                        }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectionRetention.release(key)
                    clients.remove(key)?.clear()
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
            val client = clients[device.address.lowercase()]
            respondRead(device, requestId, offset, readValue(client, characteristic.uuid))
            client?.writes?.observeCompletedRequest(requestId)
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
            val client = clients[device.address.lowercase()]
            if (client == null) {
                if (responseNeeded) {
                    server?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
                return
            }
            client.writes.enqueueWrite(
                requestId,
                PendingWrite(
                    requestId = requestId,
                    characteristicUuid = characteristic.uuid,
                    preparedWrite = preparedWrite,
                    responseNeeded = responseNeeded,
                    offset = offset,
                    value = value.copyOf(),
                ),
            )
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            val client = clients[device.address.lowercase()]
            val value = if (descriptor.uuid == SigUuids.UUID_CCCD && client != null) {
                if (descriptor.characteristic.uuid in client.subscriptions) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
            } else {
                null
            }
            respondRead(device, requestId, offset, value)
            client?.writes?.observeCompletedRequest(requestId)
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
            val enable = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
            val disable = value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
            val characteristicUuid = descriptor.characteristic.uuid
            val valid = client != null && descriptor.uuid == SigUuids.UUID_CCCD &&
                !preparedWrite && offset == 0 && (enable || disable)
            if (valid) {
                if (enable) client.subscriptions += characteristicUuid else client.subscriptions -= characteristicUuid
                if (disable) synchronized(client) { client.pending.removeAll { it.uuid == characteristicUuid } }
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
            if (valid && enable) sendInitialValue(client, characteristicUuid)
            client?.writes?.observeCompletedRequest(requestId)
            publishState()
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val client = clients[device.address.lowercase()] ?: return
            synchronized(client) { client.inFlight = false }
            if (status == BluetoothGatt.GATT_SUCCESS) listener.onPacketSent()
            drain(client)
        }
    }

    private fun processCharacteristicWrite(client: Client, pending: PendingWrite) {
        val result = synchronized(client) {
            if (!client.active || clients[client.device.address.lowercase()] !== client) return

            if (!pending.preparedWrite && pending.offset == 0) {
                client.setup.write(pending.characteristicUuid, pending.value)
            } else {
                null
            }
        }
        if (pending.responseNeeded) {
            server?.sendResponse(
                client.device,
                pending.requestId,
                if (result?.accepted == true) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                0,
                null,
            )
        }
        if (result?.accepted == true) {
            LifeFitnessNotificationPolicy.setupStatusReply(result)?.let { status ->
                enqueue(client, LifeFitnessUuids.UUID_LF_STATUS, status)
            }
            if (result.stage == LifeFitnessSetupServer.Stage.UNLOCKED) {
                val measurementUuid = machine?.measurementUuid
                if (
                    measurementUuid != null && LifeFitnessNotificationPolicy.mayStreamMeasurement(
                        result.stage,
                        measurementUuid,
                        client.subscriptions,
                    )
                ) {
                    latestMeasurement?.let { enqueue(client, measurementUuid, it) }
                }
            }
        }
        publishState()
    }

    private fun addNextService() {
        if (nextServiceIndex >= servicesToAdd.size) {
            startAdvertising(epoch.get())
            return
        }
        val service = servicesToAdd[nextServiceIndex++]
        if (gattSessions.addService(service) != true) {
            finishStart(Result.failure(IllegalStateException("Could not add Life Fitness service ${service.uuid}")))
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
        val activeMachine = machine
        if (advertiser == null || activeMachine == null) {
            finishStart(Result.failure(IllegalStateException("BLE advertising is unavailable")))
            return
        }
        val plan = LifeFitnessAdvertisingPlan.create(activeMachine)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeTxPowerLevel(true)
            .addServiceUuid(ParcelUuid(FtmsUuids.UUID_FTMS_SERVICE))
            .addServiceData(ParcelUuid(plan.serviceDataService), plan.serviceData)
            .build()
        val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                if (epoch.get() != currentEpoch || advertiseCallback !== this) return
                isAdvertising = true
                publishState()
                finishStart(Result.success(Unit))
            }

            override fun onStartFailure(errorCode: Int) {
                if (epoch.get() != currentEpoch || advertiseCallback !== this) return
                val message = "Life Fitness advertising failed ($errorCode)"
                listener.onState(
                    false,
                    clients.isNotEmpty(),
                    anySubscribed(),
                    anyStatusSubscribed(),
                    currentStage(),
                    message,
                )
                finishStart(Result.failure(IllegalStateException(message)))
            }
        }
        advertiseCallback = callback
        advertiser.startAdvertising(settings, data, scanResponse, callback)
    }

    private fun readValue(client: Client?, uuid: UUID): ByteArray? {
        val activeMachine = machine ?: return null
        return when (uuid) {
            FtmsUuids.UUID_FITNESS_MACHINE_FEATURE -> lifeFitnessFeatureValue(activeMachine)
            FtmsUuids.UUID_TRAINING_STATUS -> client?.trainingStatus ?: TRAINING_PRE_WORKOUT
            SigUuids.UUID_MANUFACTURER_NAME -> "Life Fitness".toByteArray()
            SigUuids.UUID_MODEL_NUMBER -> activeMachine.displayName.toByteArray()
            SigUuids.UUID_SERIAL_NUMBER -> activeMachine.syntheticName.toByteArray()
            SigUuids.UUID_HARDWARE_REVISION -> "SIM".toByteArray()
            SigUuids.UUID_FIRMWARE_REVISION -> "SIMULATED".toByteArray()
            SigUuids.UUID_SOFTWARE_REVISION -> "FitBridge".toByteArray()
            SigUuids.UUID_PNP_ID -> byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00)
            in USER_CHARACTERISTICS -> byteArrayOf()
            else -> null
        }
    }

    private fun sendInitialValue(client: Client, uuid: UUID) {
        when (uuid) {
            FtmsUuids.UUID_TRAINING_STATUS -> enqueue(client, uuid, client.trainingStatus)
            machine?.measurementUuid -> if (
                LifeFitnessNotificationPolicy.mayStreamMeasurement(
                    client.setup.stage,
                    uuid,
                    client.subscriptions,
                )
            ) {
                latestMeasurement?.let { enqueue(client, uuid, it) }
            }
        }
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

    private fun enqueue(client: Client, uuid: UUID, value: ByteArray) {
        synchronized(client) {
            while (client.pending.size >= MAX_PENDING_NOTIFICATIONS) client.pending.removeFirst()
            client.pending.addLast(Notification(uuid, value.copyOf()))
        }
        drain(client)
    }

    @SuppressLint("MissingPermission")
    private fun drain(client: Client) {
        val notification = synchronized(client) {
            if (client.inFlight) return
            client.pending.pollFirst()?.also { client.inFlight = true }
        } ?: return
        val characteristic = findCharacteristic(notification.uuid)
        if (characteristic == null || !notify(client.device, characteristic, notification.value)) {
            synchronized(client) { client.inFlight = false }
        }
    }

    private fun findCharacteristic(uuid: UUID): BluetoothGattCharacteristic? =
        servicesToAdd.asSequence().mapNotNull { it.getCharacteristic(uuid) }.firstOrNull()

    @SuppressLint("MissingPermission")
    private fun notify(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean {
        val activeServer = server ?: return false
        val confirm = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 &&
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0
        return if (Build.VERSION.SDK_INT >= 33) {
            activeServer.notifyCharacteristicChanged(device, characteristic, confirm, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            activeServer.notifyCharacteristicChanged(device, characteristic, confirm)
        }
    }

    private fun publishState() =
        listener.onState(
            isAdvertising,
            clients.isNotEmpty(),
            anySubscribed(),
            anyStatusSubscribed(),
            currentStage(),
            null,
        )

    private fun anySubscribed(): Boolean = clients.values.any(Client::measurementSubscribed)
    private fun anyStatusSubscribed(): Boolean =
        clients.values.any { LifeFitnessUuids.UUID_LF_STATUS in it.subscriptions }
    private fun currentStage(): LifeFitnessSetupServer.Stage? = clients.values.firstOrNull()?.setup?.stage

    companion object {
        private const val MAX_PENDING_NOTIFICATIONS = 8
        private val TRAINING_PRE_WORKOUT = byteArrayOf(0x0E, 0x00)
        private val TRAINING_ACTIVE = byteArrayOf(0x0D, 0x00)
        private val TRAINING_POST_WORKOUT = byteArrayOf(0x0F, 0x00)
        private val USER_CHARACTERISTICS = setOf(
            SigUuids.UUID_USER_FIRST_NAME,
            SigUuids.UUID_USER_LAST_NAME,
            SigUuids.UUID_USER_EMAIL,
            SigUuids.UUID_USER_DATE_OF_BIRTH,
            SigUuids.UUID_USER_AGE,
            SigUuids.UUID_USER_GENDER,
            SigUuids.UUID_USER_LANGUAGE,
            SigUuids.UUID_USER_VO2_MAX,
            SigUuids.UUID_USER_MAX_RECOMMENDED_HEART_RATE,
            LifeFitnessUuids.UUID_LF_VENDOR_TOKEN_1,
            LifeFitnessUuids.UUID_LF_VENDOR_TOKEN_2,
            SigUuids.UUID_USER_WEIGHT,
            SigUuids.UUID_USER_HEIGHT,
        )
    }
}
