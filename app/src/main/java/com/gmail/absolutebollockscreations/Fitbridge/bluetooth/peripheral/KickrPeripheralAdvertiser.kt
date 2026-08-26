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
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.KickrGattProperty
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.KickrMachineSnapshot
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.KickrPersonaManifest
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.KickrSimulatorPersona
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.KickrStandardEncoders
import com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.KickrSyntheticModel
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Build only measurement frames that the selected persona actually exposes as notifiable. */
internal fun kickrStandardMeasurementFrames(
    manifest: KickrPersonaManifest,
    snapshot: KickrMachineSnapshot,
): List<KickrTxFrame> {
    val notifiable = manifest.services
        .flatMap { it.characteristics }
        .filter { KickrGattProperty.NOTIFY in it.properties }
        .mapTo(hashSetOf()) { it.uuid }
    return buildList {
        if (BluetoothIds.UUID_CYCLING_POWER_MEASUREMENT in notifiable) {
            add(
                KickrTxFrame(
                    BluetoothIds.UUID_CYCLING_POWER_MEASUREMENT,
                    KickrStandardEncoders.cyclingPowerMeasurement(snapshot),
                    false,
                ),
            )
        }
        if (BluetoothIds.UUID_BIKING_DATA in notifiable) {
            add(
                KickrTxFrame(
                    BluetoothIds.UUID_BIKING_DATA,
                    KickrStandardEncoders.indoorBikeData(snapshot),
                    false,
                ),
            )
        }
    }
}

/** Debug-only KICKR GATT server. Proprietary decoders are attached in KS-S4 through KS-S6. */
internal class KickrPeripheralAdvertiser(
    private val context: Context,
    private val model: KickrSyntheticModel,
    private val listener: Listener,
) {
    data class Diagnostics(
        val connectedClients: Int,
        val subscribedClients: Int,
        val routes: Set<KickrControlRoute>,
        val protocolStages: Set<String>,
        val pendingFrames: Int,
        val droppedFrames: Long,
        val rejectedWrites: Long,
        val snapshot: KickrMachineSnapshot,
    )

    interface Listener {
        fun onState(advertising: Boolean, connected: Boolean, subscribed: Boolean, error: String?)
        fun onPacketSent()
    }

    private data class Client(
        val device: BluetoothDevice,
        val session: KickrPeripheralSession,
    )

    private val main = Handler(Looper.getMainLooper())
    private val epoch = AtomicLong(0)
    private val generation = AtomicLong(0)
    private val clients = ConcurrentHashMap<String, Client>()
    private val connectionRetention = PeripheralConnectionRetentionGuard()
    private var server: BluetoothGattServer? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var startResult: ((Result<Unit>) -> Unit)? = null
    private var manifest: KickrPersonaManifest? = null
    private var legacyAckMode: com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.KickrLegacyAckMode? = null
    private var pendingServices = ArrayDeque<BluetoothGattService>()
    private var characteristicServices: Map<UUID, UUID> = emptyMap()
    private var userWeight = KickrStandardEncoders.readValue(
        KickrSimulatorPersona.V5_STANDARD_LEGACY,
        BluetoothIds.UUID_USER_WEIGHT,
    )!!.bytes
    @Volatile private var rejectedWrites = 0L

    @Volatile var isAdvertising = false
        private set

    fun diagnostics(): Diagnostics {
        val active = clients.values.toList()
        return Diagnostics(
            connectedClients = active.size,
            subscribedClients = active.count { client ->
                manifest?.services?.flatMap { it.characteristics }?.any {
                    client.session.subscription(it.uuid) != PeripheralConnection.CccdMode.DISABLED
                } == true
            },
            routes = active.mapNotNull { it.session.route }.toSet(),
            protocolStages = active.mapNotNull { client ->
                client.session.zwift?.stage?.name ?: client.session.legacy?.let {
                    if (it.unlocked) "LEGACY_UNLOCKED" else "LEGACY_LOCKED"
                }
            }.toSet(),
            pendingFrames = active.sumOf { it.session.pendingCount },
            droppedFrames = active.sumOf { it.session.droppedFrames },
            rejectedWrites = rejectedWrites,
            snapshot = model.snapshot(),
        )
    }

    fun publish(snapshot: KickrMachineSnapshot) {
        val selectedManifest = manifest ?: return
        val frames = kickrStandardMeasurementFrames(selectedManifest, snapshot)
        clients.values.forEach { client ->
            synchronized(client.session) {
                frames.forEach { frame ->
                    if (client.session.subscription(frame.characteristicUuid) == PeripheralConnection.CccdMode.NOTIFY) {
                        client.session.enqueue(frame)
                    }
                }
                client.session.enqueueZwiftTelemetry()
            }
            drain(client)
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE])
    fun start(
        persona: KickrSimulatorPersona,
        legacyAckModeOverride: com.gmail.absolutebollockscreations.Fitbridge.bluetooth.simulator.KickrLegacyAckMode? = null,
        onResult: (Result<Unit>) -> Unit,
    ) {
        stop()
        val selected = KickrPersonaManifest.forPersona(persona)
        manifest = selected
        legacyAckMode = legacyAckModeOverride ?: selected.legacyAckMode
        rejectedWrites = 0
        characteristicServices = selected.services.flatMap { service ->
            service.characteristics.map { it.uuid to service.uuid }
        }.toMap()
        pendingServices = ArrayDeque(KickrGattRegistry.buildServices(selected))
        userWeight = KickrStandardEncoders.readValue(persona, BluetoothIds.UUID_USER_WEIGHT)?.bytes ?: byteArrayOf()
        val currentEpoch = epoch.incrementAndGet()
        startResult = onResult
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (manager?.adapter?.isEnabled != true) {
            finishStart(Result.failure(IllegalStateException("Bluetooth is unavailable or disabled")))
            return
        }
        server = manager.openGattServer(context, callback(currentEpoch))
        if (server == null) {
            finishStart(Result.failure(IllegalStateException("Could not open KICKR GATT server")))
            return
        }
        addNextService(currentEpoch)
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
        clients.values.forEach { client ->
            synchronized(client.session) { client.session.clear() }
            runCatching { server?.cancelConnection(client.device) }
        }
        clients.clear()
        connectionRetention.clear()
        runCatching { server?.clearServices() }
        runCatching { server?.close() }
        server = null
        manifest = null
        legacyAckMode = null
        characteristicServices = emptyMap()
        startResult?.invoke(Result.failure(IllegalStateException("KICKR simulator start was stopped")))
        startResult = null
        listener.onState(false, false, false, null)
    }

    private fun callback(callbackEpoch: Long) = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (epoch.get() != callbackEpoch) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                finishStart(Result.failure(IllegalStateException("KICKR service add failed ($status)")))
            } else {
                addNextService(callbackEpoch)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (epoch.get() != callbackEpoch) return
            val key = device.address.lowercase()
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                val client = Client(
                    device,
                    KickrPeripheralSession(
                        generation.incrementAndGet(),
                        model,
                        legacyAckMode,
                        manifest?.zwiftServiceForm,
                    ),
                )
                clients[key] = client
                PeripheralCaptureHooks.clientConnected(device.address, client.session.generation, 23)
                connectionRetention.retainOnce(key) { server?.connect(device, false) == true }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectionRetention.release(key)
                clients.remove(key)?.let { client ->
                    PeripheralCaptureHooks.clientDisconnected(
                        device.address,
                        client.session.generation,
                        "gatt_status=$status",
                    )
                    client.session.clear()
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
            val selected = manifest?.persona
            val client = clients[device.address.lowercase()]
            val value = when (characteristic.uuid) {
                BluetoothIds.UUID_USER_WEIGHT -> userWeight
                else -> selected?.let {
                    KickrStandardEncoders.readValue(
                        it,
                        characteristic.uuid,
                        client?.session?.ftms?.session?.trainingStatusPayload() ?: byteArrayOf(0x00, 0x01),
                    )?.bytes
                }
            }
            if (value != null && PeripheralCaptureHooks.shouldCaptureRead(offset)) {
                PeripheralCaptureHooks.clientRead(device.address, characteristic.uuid, value)
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
            val client = clients[device.address.lowercase()]
            PeripheralCaptureHooks.clientWrote(
                device.address,
                characteristic.uuid,
                value,
                withoutResponse = !responseNeeded,
            )
            val structurallyValid = client != null && !preparedWrite && offset == 0
            val status = when (characteristic.uuid) {
                BluetoothIds.UUID_FITNESS_MACHINE_CONTROL_POINT ->
                    if (structurallyValid) handleFtms(client, value) else BluetoothGatt.GATT_FAILURE
                BluetoothIds.UUID_WAHOO_KICKR_CONTROL ->
                    if (structurallyValid) handleLegacy(client, value) else BluetoothGatt.GATT_FAILURE
                BluetoothIds.UUID_WAHOO_RIDEON_SYNC_RX ->
                    if (structurallyValid) handleZwift(client, value) else BluetoothGatt.GATT_FAILURE
                BluetoothIds.UUID_CYCLING_POWER_CONTROL_POINT ->
                    if (structurallyValid) handleCyclingPowerControl(client, value) else BluetoothGatt.GATT_FAILURE
                BluetoothIds.UUID_USER_WEIGHT -> {
                    if (structurallyValid && value.size == 2) {
                        userWeight = value.copyOf(); BluetoothGatt.GATT_SUCCESS
                    } else BluetoothGatt.GATT_FAILURE
                }
                else -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
            }
            if (responseNeeded) server?.sendResponse(device, requestId, status, 0, null)
            if (status != BluetoothGatt.GATT_SUCCESS) rejectedWrites++
            if (status == BluetoothGatt.GATT_SUCCESS) drain(client!!)
            publishState()
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            val client = clients[device.address.lowercase()]
            val mode = client?.session?.subscription(descriptor.characteristic.uuid)
            val value = if (descriptor.uuid != BluetoothIds.UUID_CCCD || mode == null) null else when (mode) {
                PeripheralConnection.CccdMode.DISABLED -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                PeripheralConnection.CccdMode.NOTIFY -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                PeripheralConnection.CccdMode.INDICATE -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
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
            val client = clients[device.address.lowercase()]
            val target = descriptor.characteristic.uuid
            val mode = when {
                value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) -> PeripheralConnection.CccdMode.DISABLED
                value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) -> PeripheralConnection.CccdMode.NOTIFY
                value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) -> PeripheralConnection.CccdMode.INDICATE
                else -> null
            }
            val allowed = manifest?.services?.flatMap { it.characteristics }?.singleOrNull { it.uuid == target }
            val propertyAllowed = when (mode) {
                PeripheralConnection.CccdMode.DISABLED -> true
                PeripheralConnection.CccdMode.NOTIFY -> KickrGattProperty.NOTIFY in (allowed?.properties ?: emptySet())
                PeripheralConnection.CccdMode.INDICATE -> KickrGattProperty.INDICATE in (allowed?.properties ?: emptySet())
                null -> false
            }
            val ok = client != null && descriptor.uuid == BluetoothIds.UUID_CCCD &&
                !preparedWrite && offset == 0 && mode != null && propertyAllowed
            if (ok) synchronized(client.session) { client.session.subscribe(target, mode) }
            if (ok) {
                PeripheralCaptureHooks.clientCccd(
                    device.address,
                    target,
                    mode != PeripheralConnection.CccdMode.DISABLED,
                    mode.name.lowercase(),
                )
            }
            if (responseNeeded) server?.sendResponse(
                device,
                requestId,
                if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                0,
                null,
            )
            publishState()
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val client = clients[device.address.lowercase()] ?: return
            val completed = synchronized(client.session) { client.session.completeInFlight() }
            if (completed?.characteristicUuid == BluetoothIds.UUID_FITNESS_MACHINE_CONTROL_POINT) {
                client.session.ftms.session.finishProcedure()
            }
            if (status == BluetoothGatt.GATT_SUCCESS) listener.onPacketSent()
            drain(client)
        }
    }

    private fun handleFtms(client: Client, value: ByteArray): Int = synchronized(client.session) {
        if (client.session.handleFtmsControl(value) != null) BluetoothGatt.GATT_SUCCESS
        else BluetoothGatt.GATT_FAILURE
    }

    private fun handleCyclingPowerControl(client: Client, value: ByteArray): Int = synchronized(client.session) {
        if (client.session.subscription(BluetoothIds.UUID_CYCLING_POWER_CONTROL_POINT) != PeripheralConnection.CccdMode.INDICATE) {
            return BluetoothGatt.GATT_FAILURE
        }
        // Cycling Power Control Point: response opcode, requested opcode, opcode-not-supported.
        client.session.enqueue(
            KickrTxFrame(
                BluetoothIds.UUID_CYCLING_POWER_CONTROL_POINT,
                byteArrayOf(0x20, value.firstOrNull() ?: 0xFF.toByte(), 0x02),
                true,
            ),
        )
        BluetoothGatt.GATT_SUCCESS
    }

    private fun handleLegacy(client: Client, value: ByteArray): Int = synchronized(client.session) {
        if (client.session.legacy == null) return BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
        if (client.session.handleLegacyControl(value)?.accepted == true) BluetoothGatt.GATT_SUCCESS
        else BluetoothGatt.GATT_FAILURE
    }

    private fun handleZwift(client: Client, value: ByteArray): Int = synchronized(client.session) {
        if (client.session.zwift == null) return BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
        if (client.session.handleZwiftControl(value)?.accepted == true) BluetoothGatt.GATT_SUCCESS
        else BluetoothGatt.GATT_FAILURE
    }

    @SuppressLint("MissingPermission")
    private fun addNextService(currentEpoch: Long) {
        if (epoch.get() != currentEpoch) return
        val next = pendingServices.pollFirst()
        if (next == null) startAdvertising(currentEpoch)
        else if (server?.addService(next) != true) {
            finishStart(Result.failure(IllegalStateException("Could not add KICKR service ${next.uuid}")))
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(currentEpoch: Long) {
        val plan = manifest?.advertising ?: return
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val advertiser = manager?.adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            finishStart(Result.failure(IllegalStateException("BLE advertising is unavailable")))
            return
        }
        val dataBuilder = AdvertiseData.Builder().setIncludeDeviceName(true)
        plan.advertisedServices.forEach { dataBuilder.addServiceUuid(ParcelUuid(it)) }
        plan.serviceData.forEach { (uuid, value) -> dataBuilder.addServiceData(ParcelUuid(uuid), value) }
        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                if (epoch.get() != currentEpoch) return
                isAdvertising = true
                publishState()
                finishStart(Result.success(Unit))
            }

            override fun onStartFailure(errorCode: Int) {
                if (epoch.get() != currentEpoch) return
                val failure = IllegalStateException("KICKR advertising failed ($errorCode)")
                listener.onState(false, clients.isNotEmpty(), anySubscribed(), failure.message)
                finishStart(Result.failure(failure))
            }
        }
        advertiseCallback = callback
        advertiser.startAdvertising(
            AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build(),
            dataBuilder.build(),
            callback,
        )
    }

    @SuppressLint("MissingPermission")
    private fun respondRead(device: BluetoothDevice, requestId: Int, offset: Int, whole: ByteArray?) {
        when {
            whole == null -> server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
            offset < 0 || offset > whole.size -> server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, 0, null)
            else -> server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, whole.copyOfRange(offset, whole.size))
        }
    }

    @SuppressLint("MissingPermission")
    private fun drain(client: Client) {
        val frame = synchronized(client.session) { client.session.beginNext() } ?: return
        val serviceUuid = characteristicServices[frame.characteristicUuid]
        val characteristic = serviceUuid?.let { server?.getService(it)?.getCharacteristic(frame.characteristicUuid) }
        if (characteristic == null || !notify(client.device, characteristic, frame)) {
            synchronized(client.session) { client.session.completeInFlight() }
            if (frame.characteristicUuid == BluetoothIds.UUID_FITNESS_MACHINE_CONTROL_POINT) {
                client.session.ftms.session.finishProcedure()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun notify(device: BluetoothDevice, characteristic: BluetoothGattCharacteristic, frame: KickrTxFrame): Boolean {
        val activeServer = server ?: return false
        val accepted = if (Build.VERSION.SDK_INT >= 33) {
            activeServer.notifyCharacteristicChanged(device, characteristic, frame.indicate, frame.value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = frame.value
            @Suppress("DEPRECATION")
            activeServer.notifyCharacteristicChanged(device, characteristic, frame.indicate)
        }
        if (accepted) PeripheralCaptureHooks.notified(device.address, characteristic.uuid, frame.value)
        return accepted
    }

    private fun anySubscribed(): Boolean = clients.values.any { client ->
        manifest?.services?.flatMap { it.characteristics }?.any {
            client.session.subscription(it.uuid) != PeripheralConnection.CccdMode.DISABLED
        } == true
    }

    private fun publishState() = listener.onState(isAdvertising, clients.isNotEmpty(), anySubscribed(), null)

    private fun finishStart(result: Result<Unit>) {
        val callback = startResult ?: return
        startResult = null
        main.post { callback(result) }
    }
}
